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

package io.smartdatalake.workflow.action.sparktransformer
import com.typesafe.config.Config
import io.smartdatalake.config.{ConfigurationException, FromConfigFactory, InstanceRegistry, SdlConfigObject}
import io.smartdatalake.config.SdlConfigObject.DataObjectId
import io.smartdatalake.util.hdfs.PartitionValues
import io.smartdatalake.workflow.ActionPipelineContext
import org.apache.spark.sql.{DataFrame, SparkSession}

/**
 * A Transformer to use single DataFrame Transformers as multiple DataFrame Transformers.
 * This works by selecting the SubFeeds (DataFrames) the single DataFrame Transformer should be applied to.
 * All other SubFeeds will be passed through without transformation.
 * @param transformer Configuration for a DfTransformer to be applied
 * @param subFeedsToApply Names of SubFeeds the transformation should be applied to.
 */
case class DfTransformerWrapperDfsTransformer(transformer: ParsableDfTransformer, subFeedsToApply: Seq[String]) extends ParsableDfsTransformer {
  override def name: String = transformer.name
  override def description: Option[String] = transformer.description
  override def transform(actionId: SdlConfigObject.ActionId, partitionValues: Seq[PartitionValues], dfs: Map[String, DataFrame])(implicit session: SparkSession, context: ActionPipelineContext): Map[String, DataFrame] = {
    val missingSubFeeds = subFeedsToApply.toSet.diff(dfs.keySet)
    assert(missingSubFeeds.isEmpty, s"($actionId) [transformation.$name] subFeedsToApply to apply not found in input dfs: ${missingSubFeeds.mkString(", ")}")
    dfs.map { case (subFeedName,df) => if (subFeedsToApply.contains(subFeedName)) (subFeedName, transformer.transform(actionId, partitionValues, df, DataObjectId(subFeedName))) else (subFeedName, df)}.toMap
  }
  override def transformPartitionValues(actionId: SdlConfigObject.ActionId, partitionValues: Seq[PartitionValues])(implicit session: SparkSession, context: ActionPipelineContext): Option[Map[PartitionValues, PartitionValues]] = {
    transformer.transformPartitionValues(actionId, partitionValues)
  }

  override def factory: FromConfigFactory[ParsableDfsTransformer] = DfTransformerWrapperDfsTransformer
}

object DfTransformerWrapperDfsTransformer extends FromConfigFactory[ParsableDfsTransformer] {
  override def fromConfig(config: Config)(implicit instanceRegistry: InstanceRegistry): DfTransformerWrapperDfsTransformer = {
    extract[DfTransformerWrapperDfsTransformer](config)
  }
}