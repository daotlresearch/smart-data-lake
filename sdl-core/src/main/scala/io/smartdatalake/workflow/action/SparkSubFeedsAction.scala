/*
 * Smart Data Lake - Build your data lake the smart way.
 *
 * Copyright © 2019-2020 ELCA Informatique SA (<https://www.elca.ch>)
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
import io.smartdatalake.definitions.ExecutionModeWithMainInputOutput
import io.smartdatalake.util.hdfs.PartitionValues
import io.smartdatalake.util.misc.PerformanceUtils
import io.smartdatalake.workflow.dataobject.{CanCreateDataFrame, CanWriteDataFrame, DataObject}
import io.smartdatalake.workflow.{ActionPipelineContext, ExecutionPhase, SparkSubFeed, SubFeed}
import org.apache.spark.sql.SparkSession

import scala.util.{Failure, Success, Try}

abstract class SparkSubFeedsAction extends SparkAction {

  override def inputs: Seq[DataObject with CanCreateDataFrame]
  override def outputs: Seq[DataObject with CanWriteDataFrame]
  override def recursiveInputs: Seq[DataObject with CanCreateDataFrame] = Seq()

  def mainInputId: Option[DataObjectId]
  def mainOutputId: Option[DataObjectId]

  def inputIdsToIgnoreFilter: Seq[DataObjectId]

  // prepare main input / output
  // this must be lazy because inputs / outputs is evaluated later in subclasses
  lazy val mainInput: DataObject with CanCreateDataFrame = ActionHelper.getMainDataObject[DataObject with CanCreateDataFrame](mainInputId, inputs, "input", executionModeNeedsMainInputOutput, id)
  lazy val mainOutput: DataObject with CanWriteDataFrame = ActionHelper.getMainDataObject[DataObject with CanWriteDataFrame](mainOutputId, outputs, "output", executionModeNeedsMainInputOutput, id)

  /**
   * Transform [[SparkSubFeed]]'s.
   * To be implemented by subclasses.
   *
   * @param inputSubFeeds [[SparkSubFeed]]s to be transformed
   * @param outputSubFeeds [[SparkSubFeed]]s to be enriched with transformed result
   * @return transformed [[SparkSubFeed]]s
   */
  def transform(inputSubFeeds: Seq[SparkSubFeed], outputSubFeeds: Seq[SparkSubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Seq[SparkSubFeed]

  /**
   * Transform partition values
   */
  def transformPartitionValues(partitionValues: Seq[PartitionValues])(implicit context: ActionPipelineContext): Map[PartitionValues,PartitionValues]

  private def doTransform(subFeeds: Seq[SubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Seq[SparkSubFeed] = {
    val inputMap = (inputs ++ recursiveInputs).map(i => i.id -> i).toMap
    val outputMap = outputs.map(i => i.id -> i).toMap
    // convert subfeeds to SparkSubFeed type or initialize if not yet existing
    var inputSubFeeds = subFeeds.map( subFeed =>
      ActionHelper.updateInputPartitionValues(inputMap(subFeed.dataObjectId), SparkSubFeed.fromSubFeed(subFeed))
    )
    val mainInputSubFeed = inputSubFeeds.find(_.dataObjectId == mainInput.id).get
    // create output subfeeds with transformed partition values from main input
    var outputSubFeeds = outputs.map(output => ActionHelper.updateOutputPartitionValues(output, mainInputSubFeed.toOutput(output.id), Some(transformPartitionValues)))
    // apply execution mode in init phase and store result
    if (context.phase == ExecutionPhase.Init) {
      executionModeResult = Try(
        executionMode.flatMap(_.apply(id, mainInput, mainOutput, mainInputSubFeed, transformPartitionValues))
      ).recover {
        // return empty output subfeeds if "no data dont stop"
        case ex: NoDataToProcessDontStopWarning =>
          val outputSubFeeds = outputs.map {
            output => SparkSubFeed(dataFrame = None, dataObjectId = output.id, partitionValues = Seq())
          }
          // rethrow exception with fake results added. The DAG will pass the fake results to further actions.
          throw ex.copy(results = Some(outputSubFeeds))
      }
    }
    // apply execution mode
    executionModeResult.get match { // throws exception if execution mode is Failure
      case Some(result) =>
        inputSubFeeds = inputSubFeeds.map { subFeed =>
          val inputFilter = if (subFeed.dataObjectId == mainInput.id) result.filter else None
          ActionHelper.updateInputPartitionValues(inputMap(subFeed.dataObjectId), subFeed.copy(partitionValues = result.inputPartitionValues, filter = inputFilter).breakLineage)
        }
        outputSubFeeds = outputSubFeeds.map(subFeed =>
          // we need to transform inputPartitionValues again to outputPartitionValues so that partition values from partitions not existing in mainOutput are not lost.
          ActionHelper.updateOutputPartitionValues(outputMap(subFeed.dataObjectId), subFeed.copy(partitionValues = result.inputPartitionValues, filter = result.filter).breakLineage, Some(transformPartitionValues))
        )
      case _ => Unit
    }
    inputSubFeeds = inputSubFeeds.map{ subFeed =>
      val input = inputMap(subFeed.dataObjectId)
      // prepare input SubFeed
      val ignoreFilter = inputIdsToIgnoreFilter.contains(subFeed.dataObjectId)
      val preparedSubFeed = prepareInputSubFeed(input, subFeed, ignoreFilter)
      // enrich with fresh DataFrame if needed
      enrichSubFeedDataFrame(input, preparedSubFeed, context.phase)
    }
    // transform
    outputSubFeeds = transform(inputSubFeeds, outputSubFeeds)
    // update partition values to output's partition columns and update dataObjectId
    outputSubFeeds.map { subFeed =>
        val output = outputMap.getOrElse(subFeed.dataObjectId, throw ConfigurationException(s"No output found for result ${subFeed.dataObjectId} in $id. Configured outputs are ${outputs.map(_.id.id).mkString(", ")}."))
        validateAndUpdateSubFeed(output, subFeed)
    }

  }

  /**
   * Generic init implementation for Action.init
   * */
  override final def init(subFeeds: Seq[SubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Seq[SubFeed] = {
    assert(subFeeds.size == inputs.size + recursiveInputs.size, s"Number of subFeed's must match number of inputs for SparkSubFeedActions (Action $id, subfeed's ${subFeeds.map(_.dataObjectId).mkString(",")}, inputs ${inputs.map(_.id).mkString(",")})")
    // transform
    val transformedSubFeeds = doTransform(subFeeds)
    // check output
    outputs.foreach{
      output =>
        val subFeed = transformedSubFeeds.find(_.dataObjectId == output.id)
          .getOrElse(throw new IllegalStateException(s"subFeed for output ${output.id} not found"))
        output.init(subFeed.dataFrame.get, subFeed.partitionValues)
    }
    // return
    transformedSubFeeds
  }

  /**
   * Action.exec implementation
   */
  override final def exec(subFeeds: Seq[SubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Seq[SubFeed] = {
    assert(subFeeds.size == inputs.size + recursiveInputs.size, s"Number of subFeed's must match number of inputs for SparkSubFeedActions (Action $id, subfeed's ${subFeeds.map(_.dataObjectId).mkString(",")}, inputs ${inputs.map(_.id).mkString(",")})")
    val mainInputSubFeed = subFeeds.find(_.dataObjectId == mainInput.id).getOrElse(throw new IllegalStateException(s"subFeed for main input ${mainInput.id} not found"))
    // transform
    val transformedSubFeeds = doTransform(subFeeds)
    // write output
    outputs.foreach { output =>
      val subFeed = transformedSubFeeds.find(_.dataObjectId == output.id).getOrElse(throw new IllegalStateException(s"subFeed for output ${output.id} not found"))
      logWritingStarted(subFeed)
      val isRecursiveInput = recursiveInputs.exists(_.id == subFeed.dataObjectId)
      val (noData,d) = PerformanceUtils.measureDuration {
        writeSubFeed(subFeed, output, isRecursiveInput)
      }
      logWritingFinished(subFeed, noData, d)
    }
    // return
    transformedSubFeeds
  }

  private def executionModeNeedsMainInputOutput: Boolean = {
    executionMode.exists{_.isInstanceOf[ExecutionModeWithMainInputOutput]}
  }

  override def postExec(inputSubFeeds: Seq[SubFeed], outputSubFeeds: Seq[SubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Unit = {
    super.postExec(inputSubFeeds, outputSubFeeds)
    val mainInputSubFeed = inputSubFeeds.find(_.dataObjectId == mainInput.id).get
    val mainOutputSubFeed = outputSubFeeds.find(_.dataObjectId == mainOutput.id).get
    executionMode.foreach(_.postExec(id, mainInput, mainOutput, mainInputSubFeed, mainOutputSubFeed))
  }
}
