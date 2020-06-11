package net.snowflake.spark.snowflake

import java.sql.Connection

import net.snowflake.client.jdbc.telemetry.{Telemetry, TelemetryClient}
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.slf4j.LoggerFactory
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.ObjectMapper
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.node.{
  ArrayNode,
  ObjectNode
}
import net.snowflake.spark.snowflake.DefaultJDBCWrapper.DataBaseOperations
import net.snowflake.spark.snowflake.TelemetryTypes.TelemetryTypes

object SnowflakeTelemetry {

  private val TELEMETRY_SOURCE = "spark_connector"

  private var logs: List[(ObjectNode, Long)] = Nil // data and timestamp
  private val logger = LoggerFactory.getLogger(getClass)
  private val mapper = new ObjectMapper()

  private var hasClientInfoSent = false

  private[snowflake] var output: ObjectNode = _

  // The client info telemetry message is only sent one time.
  def sendClientInfoTelemetryIfNotYet(extraValues: Map[String, String],
                                      conn: Connection): Unit = {
    if (!hasClientInfoSent) {
      val metric = Utils.getClientInfoJson()
      for ((key, value) <- extraValues) {
        metric.put(key, value)
      }
      addLog(
        (TelemetryTypes.SPARK_CLIENT_INFO, metric),
        System.currentTimeMillis()
      )
      send(conn.getTelemetry)
      hasClientInfoSent = true
    }
  }

  def addLog(log: ((TelemetryTypes, ObjectNode), Long)): Unit = {
    logger.debug(s"""
        |Telemetry Output
        |Type: ${log._1._1}
        |Data: ${log._1._2.toString}
      """.stripMargin)

    this.synchronized {
      output = mapper.createObjectNode()
      output.put("type", log._1._1.toString)
      output.put("source", TELEMETRY_SOURCE)
      output.set("data", log._1._2)
      logs = (output, log._2) :: logs
    }

  }

  def send(telemetry: Telemetry): Unit = {
    var curLogs: List[(ObjectNode, Long)] = Nil
    this.synchronized {
      curLogs = logs
      logs = Nil
    }
    curLogs.foreach {
      case (log, timestamp) =>
        logger.debug(s"""
             |Send Telemetry
             |timestamp:$timestamp
             |log:${log.toString}"
           """.stripMargin)
        telemetry.asInstanceOf[TelemetryClient].addLogToBatch(log, timestamp)
    }
    telemetry.sendBatchAsync()
  }

  /**
    * Generate json node for giving spark plan tree,
    * if only the tree is complete (root is ReturnAnswer) Snowflake plan
    */
  def planToJson(plan: LogicalPlan): Option[(TelemetryTypes, ObjectNode)] =
    plan.nodeName match {
      case "ReturnAnswer" =>
        val (isSFPlan, json) = planTree(plan)
        if (isSFPlan) Some(TelemetryTypes.SPARK_PLAN, json) else None
      case _ => None
    }

  /**
    * Put the pushdown failure telemetry message to internal cache.
    * The message will be sent later in batch.
    *
    * @param plan The logical plan to include the unsupported operations
    * @param exception The pushdown unsupported exception
    */
  def addPushdownFailMessage(plan: LogicalPlan,
                             exception: SnowflakePushdownUnsupportedException)
  : Unit = {
    logger.info(
      s"""Pushdown fails because of operation: ${exception.unsupportedOperation}
         | message: ${exception.getMessage}
         | isKnown: ${exception.isKnownUnsupportedOperation}
           """.stripMargin)

    // Don't send telemetry message for known unsupported operations.
    if (exception.isKnownUnsupportedOperation) {
      return
    }

    val metric: ObjectNode = mapper.createObjectNode()
    metric.put("version", Utils.VERSION)
    metric.put("operation", exception.unsupportedOperation)
    metric.put("message", exception.getMessage)
    metric.put("details", exception.details)
    metric.put("plan", plan.toString())

    SnowflakeTelemetry.addLog(
      (TelemetryTypes.SPARK_PUSHDOWN_FAIL, metric),
      System.currentTimeMillis()
    )
  }

  /**
    * convert a plan tree to json
    */
  private def planTree(plan: LogicalPlan): (Boolean, ObjectNode) = {
    val result = mapper.createObjectNode()
    var action = plan.nodeName
    var isSFPlan = false
    val argsString = plan.argString
    val argsNode = mapper.createObjectNode()
    val children = mapper.createArrayNode()

    plan match {
      case LogicalRelation(sfRelation: SnowflakeRelation, _, _, _) =>
        isSFPlan = true
        action = "SnowflakeRelation"
        val schema = mapper.createArrayNode()
        sfRelation.schema.fields.map(_.dataType.typeName).foreach(schema.add)
        argsNode.set("schema", schema)

      case Filter(condition, _) =>
        argsNode.set("conditions", expToJson(condition))

      case Project(fields, _) =>
        argsNode.set("fields", expressionsToJson(fields))

      case Join(_, _, joinType, Some(condition)) =>
        argsNode.put("type", joinType.toString)
        argsNode.set("conditions", expToJson(condition))

      case Aggregate(groups, fields, _) =>
        argsNode.set("field", expressionsToJson(fields))
        argsNode.set("group", expressionsToJson(groups))

      case Limit(condition, _) =>
        argsNode.set("condition", expToJson(condition))

      case LocalLimit(condition, _) =>
        argsNode.set("condition", expToJson(condition))

      case Sort(orders, isGlobal, _) =>
        argsNode.put("global", isGlobal)
        argsNode.set("order", expressionsToJson(orders))

      case Window(namedExpressions, _, _, _) =>
        argsNode.set("expression", expressionsToJson(namedExpressions))

      case Union(_) =>
      case Expand(_, _, _) =>
      case _ =>
    }

    plan.children.foreach(x => {
      val (isSF, js) = planTree(x)
      if (isSF) isSFPlan = true
      children.add(js)
    })

    result.put("action", action)
    if (argsNode.toString == "{}") {
      result.put("args", argsString)
    } else {
      result.set("args", argsNode)
    }
    result.set("children", children)
    (isSFPlan, result)

  }

  /**
    * Expression to Json array
    */
  private def expressionsToJson(name: Seq[Expression]): ArrayNode = {
    val result = mapper.createArrayNode()
    name.map(expToJson).foreach(result.add)
    result
  }

  /**
    * Expression to Json object
    */
  private def expToJson(exp: Expression): ObjectNode = {
    val result = mapper.createObjectNode()
    if (exp.children.isEmpty) {
      result.put("source", exp.nodeName)
      result.put("type", exp.dataType.typeName)
    } else {
      result.put("operator", exp.nodeName)
      val parameters = mapper.createArrayNode()
      sortArgs(exp.nodeName, exp.children.map(expToJson))
        .foreach(parameters.add)
      result.set("parameters", parameters)
    }
    result
  }

  // Since order of arguments in some spark expression is random,
  // sort them to provide fixed result for testing.
  private def sortArgs(operator: String,
                       args: Seq[ObjectNode]): Seq[ObjectNode] =
    operator match {
      case "And" | "Or" => args.sortBy(_.toString)
      case _ => args
    }
}

object TelemetryTypes extends Enumeration {
  type TelemetryTypes = Value
  val SPARK_PLAN: Value = Value("spark_plan")
  val SPARK_STREAMING: Value = Value("spark_streaming")
  val SPARK_STREAMING_START: Value = Value("spark_streaming_start")
  val SPARK_STREAMING_END: Value = Value("spark_streaming_end")
  val SPARK_EGRESS: Value = Value("spark_egress")
  val SPARK_CLIENT_INFO: Value = Value("spark_client_info")
  val SPARK_PUSHDOWN_FAIL: Value = Value("spark_pushdown_fail")
}
