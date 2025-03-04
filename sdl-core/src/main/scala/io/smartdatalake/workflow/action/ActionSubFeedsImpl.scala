/*
 * Smart Data Lake - Build your data lake the smart way.
 *
 * Copyright © 2019-2021 ELCA Informatique SA (<https://www.elca.ch>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package io.smartdatalake.workflow.action

import io.smartdatalake.config.ConfigurationException
import io.smartdatalake.config.SdlConfigObject.DataObjectId
import io.smartdatalake.definitions.Environment
import io.smartdatalake.util.hdfs.PartitionValues
import io.smartdatalake.util.misc.{PerformanceUtils, ScalaUtil}
import io.smartdatalake.workflow.dataobject.{CanHandlePartitions, DataObject, TransactionalSparkTableDataObject}
import io.smartdatalake.workflow.{ActionPipelineContext, ExecutionPhase, GenericMetrics, SparkSubFeed, SubFeed, SubFeedConverter}
import org.apache.spark.sql.SparkSession

import scala.reflect.runtime.universe._
import java.time.Duration

/**
 * Implementation of SubFeed handling.
 * This is a generic implementation that supports many input and output SubFeeds.
 * @tparam S SubFeed type this Action is designed for.
 */
abstract class ActionSubFeedsImpl[S <: SubFeed : TypeTag] extends Action {

  // Hook to override main input/output in sub classes
  def mainInputId: Option[DataObjectId] = None
  def mainOutputId: Option[DataObjectId] = None

  // Hook to ignore filters for specific inputs
  def inputIdsToIgnoreFilter: Seq[DataObjectId] = Seq()

  /**
   * put configuration validation checks here
   */
  override def validateConfig(): Unit = {
    super.validateConfig()
    // check inputIdsToIgnoreFilters
    inputIdsToIgnoreFilter.foreach(inputId => assert((inputs ++ recursiveInputs).exists(_.id == inputId), s"($id) $inputId from inputIdsToIgnoreFilter must be listed in inputIds of the same action."))
  }

  // prepare main input / output
  // this must be lazy because inputs / outputs is evaluated later in subclasses
  // Note: we don't yet decide for a main input as inputs might be skipped at runtime, but we can already create a prioritized list.
  protected lazy val prioritizedMainInputCandidates: Seq[DataObject] = getMainDataObjectCandidates(mainInputId, inputs, "input")
  protected lazy val mainOutput: DataObject = getMainDataObjectCandidates(mainOutputId, outputs, "output").head
  protected def getMainInput(inputSubFeeds: Seq[SubFeed])(implicit context: ActionPipelineContext): DataObject = {
    // take first data object which has as SubFeed which is not skipped
    prioritizedMainInputCandidates.find(dataObject => !inputSubFeeds.find(_.dataObjectId == dataObject.id).get.isSkipped || context.appConfig.isDryRun)
      .getOrElse(prioritizedMainInputCandidates.head) // otherwise just take first candidate
  }
  protected def getMainPartitionValues(inputSubFeeds: Seq[SubFeed])(implicit context: ActionPipelineContext): Seq[PartitionValues] = {
    val mainInput = getMainInput(inputSubFeeds)
    val mainInputSubFeed = inputSubFeeds.find(_.dataObjectId==mainInput.id)
    mainInputSubFeed.map(_.partitionValues).getOrElse(Seq())
  }

  // helper data structures
  private lazy val inputMap = (inputs ++ recursiveInputs).map(i => i.id -> i).toMap
  private lazy val outputMap = outputs.map(i => i.id -> i).toMap
  private val subFeedConverter = ScalaUtil.companionOf[S, SubFeedConverter[S]]

  def prepareInputSubFeeds(subFeeds: Seq[SubFeed])(implicit session: SparkSession, context: ActionPipelineContext): (Seq[S],Seq[S]) = {
    val mainInput = getMainInput(subFeeds)
    // convert subfeeds to SparkSubFeed type or initialize if not yet existing
    var inputSubFeeds: Seq[S] = subFeeds.map( subFeed =>
      updateInputPartitionValues(inputMap(subFeed.dataObjectId), subFeedConverter.fromSubFeed(subFeed))
    )
    val mainInputSubFeed = inputSubFeeds.find(_.dataObjectId == mainInput.id).get
    // create output subfeeds with transformed partition values from main input
    var outputSubFeeds: Seq[S] = outputs.map(output =>
      updateOutputPartitionValues(output, subFeedConverter.get(mainInputSubFeed.toOutput(output.id)), Some(transformPartitionValues))
    )
    // (re-)apply execution mode in init phase, streaming iteration or if not first action in pipeline (search for calls to resetExecutionResults for details)
    if (executionModeResult.isEmpty) applyExecutionMode(mainInput, mainOutput, mainInputSubFeed, transformPartitionValues)
    // apply execution mode result
    executionModeResult.get.get match { // throws exception if execution mode is Failure
      case Some(result) =>
        inputSubFeeds = inputSubFeeds.map { subFeed =>
          updateInputPartitionValues(inputMap(subFeed.dataObjectId), subFeedConverter.get(subFeed.applyExecutionModeResultForInput(result, mainInput.id)))
        }
        outputSubFeeds = outputSubFeeds.map(subFeed =>
          // we need to transform inputPartitionValues again to outputPartitionValues so that partition values from partitions not existing in mainOutput are not lost.
          updateOutputPartitionValues(outputMap(subFeed.dataObjectId), subFeedConverter.get(subFeed.applyExecutionModeResultForOutput(result)), Some(transformPartitionValues))
        )
      case _ => Unit
    }
    inputSubFeeds = inputSubFeeds.map{ subFeed =>
      // prepare input SubFeed
      val ignoreFilter = inputIdsToIgnoreFilter.contains(subFeed.dataObjectId)
      val isRecursive = recursiveInputs.exists(_.id == subFeed.dataObjectId)
      preprocessInputSubFeedCustomized(subFeed, ignoreFilter, isRecursive)
    }
    outputSubFeeds = outputSubFeeds.map(subFeed => addRunIdPartitionIfNeeded(outputMap(subFeed.dataObjectId), subFeed))
    (inputSubFeeds, outputSubFeeds)
  }

  def postprocessOutputSubFeeds(subFeeds: Seq[S])(implicit session: SparkSession, context: ActionPipelineContext): Seq[S] = {
    // assert all outputs have a subFeed
    outputs.foreach{ output =>
        subFeeds.find(_.dataObjectId == output.id).getOrElse(throw new IllegalStateException(s"($id) subFeed for output ${output.id} not found"))
    }
    // validate & update subfeeds
    subFeeds.map { subFeed =>
      outputMap.getOrElse(subFeed.dataObjectId, throw ConfigurationException(s"($id) No output found for result ${subFeed.dataObjectId}. Configured outputs are ${outputs.map(_.id.id).mkString(", ")}."))
      postprocessOutputSubFeedCustomized(subFeed)
    }
  }

  def writeOutputSubFeeds(subFeeds: Seq[S])(implicit session: SparkSession, context: ActionPipelineContext): Unit = {
    outputs.foreach { output =>
      val subFeed = subFeeds.find(_.dataObjectId == output.id).getOrElse(throw new IllegalStateException(s"($id) subFeed for output ${output.id} not found"))
      logWritingStarted(subFeed)
      val isRecursiveInput = recursiveInputs.exists(_.id == subFeed.dataObjectId)
      val (result, d) = PerformanceUtils.measureDuration {
        writeSubFeed(subFeed, isRecursiveInput)
      }
      result.metrics.foreach(m => if(m.nonEmpty) addRuntimeMetrics(Some(context.executionId), Some(output.id), GenericMetrics(s"$id-${output.id}", 1, m)))
      logWritingFinished(subFeed, result.noData, d)
    }
  }

  override def prepare(implicit session: SparkSession, context: ActionPipelineContext): Unit = {
    super.prepare
    // check main input/output by triggering lazy values
    prioritizedMainInputCandidates
    mainOutput
  }

  private def validateInputSubFeeds(subFeeds: Seq[SubFeed]): Unit = {
    val inputIds = if (handleRecursiveInputsAsSubFeeds) (inputs ++ recursiveInputs).map(_.id) else inputs.map(_.id)
    val superfluousSubFeeds = subFeeds.map(_.dataObjectId).diff(inputIds)
    val missingSubFeeds = inputIds.diff(subFeeds.map(_.dataObjectId))
    assert(superfluousSubFeeds.isEmpty && missingSubFeeds.isEmpty, s"($id) input SubFeed's must match input DataObjects: superfluous=${superfluousSubFeeds.mkString(",")} missing=${missingSubFeeds.mkString(",")})")
  }

  override final def init(subFeeds: Seq[SubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Seq[SubFeed] = {
    validateInputSubFeeds(subFeeds)
    // prepare
    var (inputSubFeeds, outputSubFeeds) = prepareInputSubFeeds(subFeeds)
    // transform
    outputSubFeeds = transform(inputSubFeeds, outputSubFeeds)
    // update partition values to output's partition columns and update dataObjectId
    outputSubFeeds = postprocessOutputSubFeeds(outputSubFeeds)
    // return
    outputSubFeeds
  }

  override final def exec(subFeeds: Seq[SubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Seq[SubFeed] = {
    validateInputSubFeeds(subFeeds)
    if (isAsynchronousProcessStarted) return outputs.map(output => SparkSubFeed(None, output.id, Seq())) // empty output subfeeds if asynchronous action started
    // prepare
    var (inputSubFeeds, outputSubFeeds) = prepareInputSubFeeds(subFeeds)
    // transform
    outputSubFeeds = transform(inputSubFeeds, outputSubFeeds)
    // check and adapt output SubFeeds
    outputSubFeeds = postprocessOutputSubFeeds(outputSubFeeds)
    // write output
    writeOutputSubFeeds(outputSubFeeds)
    // return
    outputSubFeeds
  }

  override def postExec(inputSubFeeds: Seq[SubFeed], outputSubFeeds: Seq[SubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Unit = {
    if (isAsynchronousProcessStarted) return
    super.postExec(inputSubFeeds, outputSubFeeds)
    val mainInput = getMainInput(inputSubFeeds)
    val mainInputSubFeed = inputSubFeeds.find(_.dataObjectId == mainInput.id).get
    val mainOutputSubFeed = outputSubFeeds.find(_.dataObjectId == mainOutput.id).get
    executionMode.foreach(_.postExec(id, mainInput, mainOutput, mainInputSubFeed, mainOutputSubFeed))
  }

  protected def logWritingStarted(subFeed: S)(implicit session: SparkSession, context: ActionPipelineContext): Unit = {
    val msg = s"writing to ${subFeed.dataObjectId}" + (if (subFeed.partitionValues.nonEmpty) s", partitionValues ${subFeed.partitionValues.mkString(" ")}" else "")
    logger.info(s"($id) start " + msg)
  }

  protected def logWritingFinished(subFeed: S, noData: Option[Boolean], duration: Duration)(implicit session: SparkSession, context: ActionPipelineContext): Unit = {
    val metricsLog = if (noData.contains(true)) ", no data found"
    else runtimeData.getFinalMetrics(subFeed.dataObjectId).map(_.getMainInfos).map(" "+_.map( x => x._1+"="+x._2).mkString(" ")).getOrElse("")
    logger.info(s"($id) finished writing DataFrame to ${subFeed.dataObjectId.id}: jobDuration=$duration" + metricsLog)
  }

  private def getMainDataObjectCandidates(mainId: Option[DataObjectId], dataObjects: Seq[DataObject], inputOutput: String): Seq[DataObject] = {
    if (mainId.isDefined) {
      // if mainInput is defined -> return only that DataObject
      Seq(dataObjects.find(_.id == mainId.get).getOrElse(throw ConfigurationException(s"($id) main${inputOutput}Id ${mainId.get} not found in ${inputOutput}s")))
    } else {
      // prioritize DataObjects by number of partition columns
      dataObjects.sortBy {
        case x: CanHandlePartitions if !inputIdsToIgnoreFilter.contains(x.id) => x.partitions.size
        case _ => 0
      }.reverse
    }
  }

  /**
   * Updates the partition values of a SubFeed to the partition columns of the given input data object:
   * - remove not existing columns from the partition values
   */
  private def updateInputPartitionValues(dataObject: DataObject, subFeed: S)(implicit session: SparkSession, context: ActionPipelineContext): S = {
    dataObject match {
      case partitionedDO: CanHandlePartitions =>
        // remove superfluous partitionValues
        subFeed.updatePartitionValues(partitionedDO.partitions, newPartitionValues = Some(subFeed.partitionValues)).asInstanceOf[S]
      case _ =>
        subFeed.clearPartitionValues().asInstanceOf[S]
    }
  }

  /**
   * Updates the partition values of a SubFeed to the partition columns of the given output data object:
   * - transform partition values
   * - add run_id_partition value if needed
   * - removing not existing columns from the partition values.
   */
  private def updateOutputPartitionValues(dataObject: DataObject, subFeed: S, partitionValuesTransform: Option[Seq[PartitionValues] => Map[PartitionValues,PartitionValues]] = None)(implicit session: SparkSession, context: ActionPipelineContext): S = {
    dataObject match {
      case partitionedDO: CanHandlePartitions =>
        // transform partition values
        val newPartitionValues = partitionValuesTransform.map(fn => fn(subFeed.partitionValues).values.toSeq.distinct)
          .getOrElse(subFeed.partitionValues)
        // remove superfluous partitionValues
        subFeed.updatePartitionValues(partitionedDO.partitions, breakLineageOnChange = false, newPartitionValues = Some(newPartitionValues)).asInstanceOf[S]
      case _ =>
        subFeed.clearPartitionValues(breakLineageOnChange = false).asInstanceOf[S]
    }
  }

  private def addRunIdPartitionIfNeeded(dataObject: DataObject, subFeed: S)(implicit session: SparkSession, context: ActionPipelineContext): S = {
    dataObject match {
      case partitionedDO: CanHandlePartitions =>
        if (partitionedDO.partitions.contains(Environment.runIdPartitionColumnName)) {
          val newPartitionValues = if (subFeed.partitionValues.nonEmpty) subFeed.partitionValues.map(_.addKey(Environment.runIdPartitionColumnName, context.executionId.runId.toString))
          else Seq(PartitionValues(Map(Environment.runIdPartitionColumnName -> context.executionId.runId.toString)))
          subFeed.updatePartitionValues(partitionedDO.partitions, breakLineageOnChange = false, newPartitionValues = Some(newPartitionValues)).asInstanceOf[S]
        } else subFeed
      case _ => subFeed
    }
  }

  protected def validatePartitionValuesExisting(dataObject: DataObject with CanHandlePartitions, subFeed: SubFeed)(implicit session: SparkSession, context: ActionPipelineContext): Unit = {
    // Existing partitions can only be checked if Action is at start of the DAG or if we are in Exec phase (previous Actions have been executed)
    if (subFeed.partitionValues.nonEmpty && (context.phase == ExecutionPhase.Exec || subFeed.isDAGStart)) {
      val expectedPartitions = dataObject.filterExpectedPartitionValues(subFeed.partitionValues)
      val missingPartitionValues = if (expectedPartitions.nonEmpty) PartitionValues.checkExpectedPartitionValues(dataObject.listPartitions, expectedPartitions) else Seq()
      assert(missingPartitionValues.isEmpty, s"($id) partitions ${missingPartitionValues.mkString(", ")} missing for ${dataObject.id}")
    }
  }

  /**
   * Implement additional preprocess logic for SubFeeds before transformation
   * Can be implemented by subclass.
   * @param ignoreFilter If filters should be ignored for this feed
   * @param isRecursive If subfeed is recursive (input & output)
   */
  protected def preprocessInputSubFeedCustomized(subFeed: S, ignoreFilter: Boolean, isRecursive: Boolean)(implicit session: SparkSession, context: ActionPipelineContext): S = subFeed

  /**
   * Implement additional processing logic for SubFeeds after transformation.
   * Can be implemented by subclass.
   */
  protected def postprocessOutputSubFeedCustomized(subFeed: S)(implicit session: SparkSession, context: ActionPipelineContext): S = subFeed

  /**
   * Transform partition values.
   * Can be implemented by subclass.
   */
  protected def transformPartitionValues(partitionValues: Seq[PartitionValues])(implicit session: SparkSession, context: ActionPipelineContext): Map[PartitionValues,PartitionValues] = PartitionValues.oneToOneMapping(partitionValues)

  /**
   * Transform subfeed content
   * To be implemented by subclass.
   */
  protected def transform(inputSubFeeds: Seq[S], outputSubFeeds: Seq[S])(implicit session: SparkSession, context: ActionPipelineContext): Seq[S]

  /**
   * Write subfeed data to output.
   * To be implemented by subclass.
   * @param isRecursive If subfeed is recursive (input & output)
   * @return false if there was no data to process, otherwise true.
   */
  protected def writeSubFeed(subFeed: S, isRecursive: Boolean)(implicit session: SparkSession, context: ActionPipelineContext): WriteSubFeedResult

}

/**
 * Return value of writing a SubFeed.
 * @param noData true if there was no data to write, otherwise false. If unknown set to None.
 * @param metrics Depending on the engine, metrics are received by a listener (SparkSubFeed) or can be returned directly by filling this attribute (FileSubFeed).
 */
case class WriteSubFeedResult(noData: Option[Boolean], metrics: Option[Map[String, Any]] = None)

case class SubFeedExpressionData(partitionValues: Seq[Map[String,String]], isDAGStart: Boolean, isSkipped: Boolean)
case class SubFeedsExpressionData(inputSubFeeds: Map[String, SubFeedExpressionData])
object SubFeedsExpressionData {
  def fromSubFeeds(subFeeds: Seq[SubFeed]): SubFeedsExpressionData = {
    SubFeedsExpressionData(subFeeds.map(subFeed => (subFeed.dataObjectId.id, SubFeedExpressionData(subFeed.partitionValues.map(_.getMapString), subFeed.isDAGStart, subFeed.isSkipped))).toMap)
  }
}