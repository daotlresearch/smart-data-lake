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

package io.smartdatalake.app

import java.nio.file.Files

import io.smartdatalake.config.InstanceRegistry
import io.smartdatalake.definitions.PartitionDiffMode
import io.smartdatalake.testutils.TestUtil
import io.smartdatalake.util.hdfs.{HdfsUtil, PartitionValues}
import io.smartdatalake.util.hive.HiveUtil
import io.smartdatalake.workflow.{ActionDAGRunStateStore, TaskFailedException}
import io.smartdatalake.workflow.action.customlogic.{CustomDfTransformer, CustomDfTransformerConfig}
import io.smartdatalake.workflow.action.{ActionMetadata, CopyAction, DeduplicateAction, RuntimeEventState}
import io.smartdatalake.workflow.dataobject.{HiveTableDataObject, Table, TickTockHiveTableDataObject}
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.functions.{col, udf}
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.scalatest.{BeforeAndAfter, FunSuite}

class SmartDataLakeBuilderTest extends FunSuite with BeforeAndAfter {

  protected implicit val session: SparkSession = TestUtil.sessionHiveCatalog
  import session.implicits._

  private val tempDir = Files.createTempDirectory("test")
  private val tempPath = tempDir.toAbsolutePath.toString

  val statePath = "target/stateTest/"

  test("sdlb run with 2 actions and positive top-level partition values filter, recovery after action 2 failed the first time") {

    // init sdlb
    val appName = "sdlb-recovery"
    val feedName = "test"

    HdfsUtil.deleteFiles(s"$statePath${appName}*", HdfsUtil.getHadoopFs(new Path(statePath)), false)
    val sdlb = new DefaultSmartDataLakeBuilder()
    implicit val instanceRegistry: InstanceRegistry = sdlb.instanceRegistry

    // setup DataObjects
    val srcTable = Table(Some("default"), "ap_input")
    HiveUtil.dropTable(session, srcTable.db.get, srcTable.name )
    val srcPath = tempPath+s"/${srcTable.fullName}"
    // source table has partitions columns dt and type
    val srcDO = HiveTableDataObject( "src1", srcPath, partitions = Seq("dt","type"), table = srcTable, numInitialHdfsPartitions = 1)
    instanceRegistry.register(srcDO)
    val tgt1Table = Table(Some("default"), "ap_copy1", None, Some(Seq("lastname","firstname")))
    HiveUtil.dropTable(session, tgt1Table.db.get, tgt1Table.name )
    val tgt1Path = tempPath+s"/${tgt1Table.fullName}"
    // first table has partitions columns dt and type (same as source)
    val tgt1DO = TickTockHiveTableDataObject( "tgt1", tgt1Path, partitions = Seq("dt","type"), table = tgt1Table, numInitialHdfsPartitions = 1)
    instanceRegistry.register(tgt1DO)
    val tgt2Table = Table(Some("default"), "ap_copy2", None, Some(Seq("lastname","firstname")))
    HiveUtil.dropTable(session, tgt2Table.db.get, tgt2Table.name )
    val tgt2Path = tempPath+s"/${tgt2Table.fullName}"
    // second table has partition columns dt only (reduced)
    val tgt2DO = HiveTableDataObject( "tgt2", tgt2Path, partitions = Seq("dt"), table = tgt2Table, numInitialHdfsPartitions = 1)
    instanceRegistry.register(tgt2DO)

    // prepare data
    val dfSrc = Seq(("20180101", "person", "doe","john",5) // partition 20180101 is included in partition values filter
      ,("20190101", "company", "olmo","-",10)) // partition 20190101 is not included
      .toDF("dt", "type", "lastname", "firstname", "rating")
    srcDO.writeDataFrame(dfSrc, Seq())

    // start first dag run -> fail
    // load partition 20180101 only
    val action1 = CopyAction("a", srcDO.id, tgt1DO.id, metadata = Some(ActionMetadata(feed = Some(feedName))))
    instanceRegistry.register(action1.copy())
    val action2fail = CopyAction("b", tgt1DO.id, tgt2DO.id, metadata = Some(ActionMetadata(feed = Some(feedName)))
      , transformer = Some(CustomDfTransformerConfig(className = Some(classOf[FailTransformer].getName))))
    instanceRegistry.register(action2fail.copy())
    val selectedPartitions = Seq(PartitionValues(Map("dt"->"20180101")))
    val configStart = SmartDataLakeBuilderConfig(feedSel = feedName, applicationName = Some(appName), statePath = Some(statePath)
      , partitionValues = Some(selectedPartitions))
    intercept[TaskFailedException](sdlb.run(configStart))

    // check failed results
    assert(tgt1DO.getDataFrame(Seq()).select($"rating").as[Int].collect().toSeq == Seq(5))
    assert(!tgt2DO.isTableExisting)

    // check latest state
    {
      val stateStore = ActionDAGRunStateStore(statePath, appName)
      val stateFile = stateStore.getLatestState()
      val runState = stateStore.recoverRunState(stateFile.path)
      assert(runState.attemptId == 1)
      assert(runState.actionsState.mapValues(_.state) == Map(action1.id.id -> RuntimeEventState.SUCCEEDED, action2fail.id.id -> RuntimeEventState.FAILED))
    }

    // now fill tgt1 with both partitions
    tgt1DO.writeDataFrame(dfSrc, Seq())

    // reset DataObjects
    instanceRegistry.clear
    instanceRegistry.register(srcDO)
    instanceRegistry.register(tgt1DO)
    instanceRegistry.register(tgt2DO)
    instanceRegistry.register(action1.copy())

    // start recovery dag run
    // this should execute action b with partition 20180101 only!
    val action2success = CopyAction("b", tgt1DO.id, tgt2DO.id, metadata = Some(ActionMetadata(feed = Some(feedName))))
    instanceRegistry.register(action2success.copy())
    val configRecover = SmartDataLakeBuilderConfig(applicationName = Some(appName), statePath = Some(statePath))
    sdlb.run(configRecover)

    // check results
    assert(tgt2DO.getDataFrame(Seq()).select($"rating").as[Int].collect().toSeq == Seq(5))

    // check latest state
    {
      val stateStore = ActionDAGRunStateStore(statePath, appName)
      val stateFile = stateStore.getLatestState()
      val runState = stateStore.recoverRunState(stateFile.path)
      assert(runState.attemptId == 2)
      assert(runState.actionsState.mapValues(_.state) == Map(action2success.id.id -> RuntimeEventState.SUCCEEDED))
      assert(runState.actionsState.head._2.results.head.subFeed.partitionValues == selectedPartitions)
    }
  }

  test("sdlb run with initialExecutionMode=PartitionDiffMode, increase runId on second run") {

    // init sdlb
    val appName = "sdlb-runId"
    val feedName = "test"

    HdfsUtil.deleteFiles(s"$statePath${appName}*", HdfsUtil.getHadoopFs(new Path(statePath)), false)
    val sdlb = new DefaultSmartDataLakeBuilder()
    implicit val instanceRegistry: InstanceRegistry = sdlb.instanceRegistry

    // setup DataObjects
    val srcTable = Table(Some("default"), "ap_input")
    HiveUtil.dropTable(session, srcTable.db.get, srcTable.name )
    val srcPath = tempPath+s"/${srcTable.fullName}"
    // source table has partitions columns dt and type
    val srcDO = HiveTableDataObject( "src1", srcPath, partitions = Seq("dt","type"), table = srcTable, numInitialHdfsPartitions = 1)
    instanceRegistry.register(srcDO)
    val tgt1Table = Table(Some("default"), "ap_copy", None, Some(Seq("lastname","firstname")))
    HiveUtil.dropTable(session, tgt1Table.db.get, tgt1Table.name )
    val tgt1Path = tempPath+s"/${tgt1Table.fullName}"
    // first table has partitions columns dt and type (same as source)
    val tgt1DO = TickTockHiveTableDataObject( "tgt1", tgt1Path, partitions = Seq("dt","type"), table = tgt1Table, numInitialHdfsPartitions = 1)
    instanceRegistry.register(tgt1DO)

    // fill src table with first partition
    val dfSrc1 = Seq(("20180101", "person", "doe","john",5)) // first partition 20180101
      .toDF("dt", "type", "lastname", "firstname", "rating")
    srcDO.writeDataFrame(dfSrc1, Seq())

    // start first dag run
    // use only first partition col (dt) for partition diff mode
    val action1 = CopyAction("a", srcDO.id, tgt1DO.id, initExecutionMode = Some(PartitionDiffMode(partitionColNb = Some(1))), metadata = Some(ActionMetadata(feed = Some(feedName))))
    instanceRegistry.register(action1.copy())
    val configStart = SmartDataLakeBuilderConfig(feedSel = feedName, applicationName = Some(appName), statePath = Some(statePath))
    sdlb.run(configStart)

    // check results
    assert(tgt1DO.getDataFrame(Seq()).select($"rating").as[Int].collect().toSeq == Seq(5))

    // check latest state
    {
      val stateStore = ActionDAGRunStateStore(statePath, appName)
      val stateFile = stateStore.getLatestState()
      val runState = stateStore.recoverRunState(stateFile.path)
      assert(runState.runId == 1)
      assert(runState.attemptId == 1)
      assert(runState.actionsState.mapValues(_.state) == Map(action1.id.id -> RuntimeEventState.SUCCEEDED))
      assert(runState.actionsState.head._2.results.head.subFeed.partitionValues == Seq(PartitionValues(Map("dt"->"20180101"))))
    }

    // now fill src table with second partitions
    val dfSrc2 = Seq(("20190101", "company", "olmo","-",10)) // second partition 20190101
      .toDF("dt", "type", "lastname", "firstname", "rating")
    srcDO.writeDataFrame(dfSrc2, Seq())

    // reset Actions / DataObjects
    instanceRegistry.clear
    instanceRegistry.register(srcDO)
    instanceRegistry.register(tgt1DO)
    instanceRegistry.register(action1.copy())

    // start second run
    sdlb.run(configStart)

    // check results
    assert(tgt1DO.getDataFrame(Seq()).select($"rating").as[Int].collect().toSeq == Seq(5,10))

    // check latest state
    {
      val stateStore = ActionDAGRunStateStore(statePath, appName)
      val stateFile = stateStore.getLatestState()
      val runState = stateStore.recoverRunState(stateFile.path)
      assert(runState.runId == 2)
      assert(runState.attemptId == 1)
      assert(runState.actionsState.mapValues(_.state) == Map(action1.id.id -> RuntimeEventState.SUCCEEDED))
      assert(runState.actionsState.head._2.results.head.subFeed.partitionValues == Seq(PartitionValues(Map("dt"->"20190101"))))
    }
  }

}

class FailTransformer extends CustomDfTransformer {
  def transform(session: SparkSession, options: Map[String, String], df: DataFrame, dataObjectId: String): DataFrame = {
    // DataFrame needs at least one string column in schema
    val firstStringColumn = df.schema.fields.find(_.dataType == StringType).map(_.name).get
    val udfFail = udf((s: String) => {throw new IllegalStateException("aborted by FailTransformer"); s})
    // fail at spark runtime
    df.withColumn(firstStringColumn, udfFail(col(firstStringColumn)))
  }
}