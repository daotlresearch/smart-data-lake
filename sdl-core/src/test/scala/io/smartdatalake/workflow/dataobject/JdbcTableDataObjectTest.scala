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
package io.smartdatalake.workflow.dataobject

import io.smartdatalake.definitions.SDLSaveMode
import io.smartdatalake.testutils.{DataObjectTestSuite, TestUtil}
import io.smartdatalake.workflow.SparkSubFeed
import io.smartdatalake.workflow.action.CopyAction
import io.smartdatalake.workflow.connection.{DefaultJdbcCatalog, JdbcTableConnection}
import io.smartdatalake.util.misc.DataFrameUtil.DfSDL

class JdbcTableDataObjectTest extends DataObjectTestSuite {

  import session.implicits._

  private val jdbcConnection = JdbcTableConnection("jdbcCon1", "jdbc:hsqldb:file:target/JdbcTableDataObjectTest/hsqldb", "org.hsqldb.jdbcDriver")

  test("write and read jdbc table") {
    import io.smartdatalake.util.misc.DataFrameUtil.DfSDL
    instanceRegistry.register(jdbcConnection)
    val table = Table(Some("public"), "table1")
    val dataObject = JdbcTableDataObject( "jdbcDO1", table = table, connectionId = "jdbcCon1", jdbcOptions = Map("createTableColumnTypes"->"type varchar(255), lastname varchar(255), firstname varchar(255)"))
    dataObject.dropTable
    val df = Seq(("ext","doe","john",5),("ext","smith","peter",3),("int","emma","brown",7)).toDF("type", "lastname", "firstname", "rating")
    dataObject.init(df, Seq())
    dataObject.writeDataFrame(df, Seq())
    val dfRead = dataObject.getDataFrame(Seq())
    assert(dfRead.symmetricDifference(df).isEmpty)
  }

  test("write and read case insensitive jdbc table") {
    import io.smartdatalake.util.misc.DataFrameUtil.DfSDL
    instanceRegistry.register(jdbcConnection)
    // Use double quotes for case sensitivity in HSQLDB
    val table = Table(Some("\"PUBLIC\""), "\"CaseSensitiveTable1\"")
    val dataObject = JdbcTableDataObject( "jdbcDO1", table = table, connectionId = "jdbcCon1", jdbcOptions = Map("createTableColumnTypes"->"type varchar(255), lastname varchar(255), firstname varchar(255)"))
    dataObject.dropTable
    val df = Seq(("ext","doe","john",5),("ext","smith","peter",3),("int","emma","brown",7)).toDF("type", "lastname", "firstname", "rating")
    dataObject.init(df, Seq())
    dataObject.writeDataFrame(df, Seq())
    val dfRead = dataObject.getDataFrame(Seq())
    assert(dfRead.symmetricDifference(df).isEmpty)
    dataObject.deleteAllData
  }

  test("check pre/post sql") {
    import io.smartdatalake.util.misc.DataFrameUtil.DfSDL
    instanceRegistry.register(jdbcConnection)

    val table1 = Table(Some("public"), "table1")
    val srcDO = JdbcTableDataObject( "jdbcDO1", table = table1, connectionId = "jdbcCon1", jdbcOptions = Map("createTableColumnTypes"->"type varchar(255), lastname varchar(255), firstname varchar(255)")
      , preReadSql = Some(s"insert into ${table1.fullName} values ('preRead','smith','%{feed}',3);")
      , postReadSql = Some(s"insert into ${table1.fullName} values ('postRead','smith','%{feed}',3);")
      , preWriteSql = Some(s"insert into ${table1.fullName} values ('preWrite','smith','%{feed}',3);") // should not be inserted on src
      , postWriteSql = Some(s"insert into ${table1.fullName} values ('postWrite','smith','%{feed}',3);") // should not be inserted on src
    )
    srcDO.dropTable
    val df = Seq(("ext","doe","john",5)).toDF("type", "lastname", "firstname", "rating")
    srcDO.init(df, Seq())
    srcDO.writeDataFrame(df, Seq())
    instanceRegistry.register(srcDO)

    val tgtDO = JdbcTableDataObject( "jdbcDO2", table = Table(Some("public"), "table2"), connectionId = "jdbcCon1", jdbcOptions = Map("createTableColumnTypes"->"type varchar(255), lastname varchar(255), firstname varchar(255)")
      , preReadSql = Some(s"insert into ${table1.fullName} values ('preRead','emma','%{feed}',3);") // should not be inserted on tgt
      , postReadSql = Some(s"insert into ${table1.fullName} values ('postRead','emma','%{feed}',3);") // should not be inserted on tgt
      , preWriteSql = Some(s"insert into ${table1.fullName} values ('preWrite','emma','%{feed}',3);")
      , postWriteSql = Some(s"insert into ${table1.fullName} values ('postWrite','emma','%{feed}',3);")
    )
    tgtDO.dropTable
    instanceRegistry.register(tgtDO)

    val action1 = CopyAction("ca", srcDO.id, tgtDO.id)
    val srcSubFeed = SparkSubFeed(None, srcDO.id, Seq())
    action1.init(Seq(srcSubFeed))
    action1.preExec(Seq(srcSubFeed))
    val tgtSubFeed = action1.exec(Seq(srcSubFeed))(session,contextExec).head
    action1.postExec(Seq(srcSubFeed), Seq(tgtSubFeed))

    val dfSrcExpected = Seq(("ext", "doe", "john", 5)
      , ("preRead", "smith", "feedTest", 3), ("preWrite", "emma", "feedTest", 3)
      , ("postRead", "smith", "feedTest", 3), ("postWrite", "emma", "feedTest", 3)
    ).toDF("type", "lastname", "firstname", "rating")
    srcDO.getDataFrame().symmetricDifference(dfSrcExpected).show
    assert(srcDO.getDataFrame().symmetricDifference(dfSrcExpected).isEmpty)
  }

  // query parameter doesn't work with hsqldb
  ignore("read jdbc table with query") {
    import io.smartdatalake.util.misc.DataFrameUtil.DfSDL
    instanceRegistry.register(jdbcConnection)

    // prepare data
    val table1 = Table(Some("public"), "table1")
    val dataObject1 = JdbcTableDataObject( "jdbcDO1", table = table1, connectionId = "jdbcCon1", jdbcOptions = Map("createTableColumnTypes"->"type varchar(255), lastname varchar(255), firstname varchar(255)"))
    dataObject1.dropTable
    val df = Seq(("ext","doe","john",5),("ext","smith","peter",3),("int","emma","brown",7)).toDF("type", "lastname", "firstname", "rating")
    dataObject1.init(df, Seq())
    dataObject1.writeDataFrame(df, Seq())

    // read prepared data
    val table2 = Table(Some("public"), "table1", query = Some("select lastname, firstname from public.table1"))
    val dataObject2 = JdbcTableDataObject( "jdbcDO2", table = table2, connectionId = "jdbcCon1")
    val dfRead = dataObject2.getDataFrame(Seq())
    assert(dfRead.symmetricDifference(df.select($"lastname", $"firstname")).isEmpty)

    // assert cannot write to DataObject with query defined
    intercept[IllegalArgumentException](dataObject2.writeDataFrame(df, Seq()))
  }

  test("isTableExisting should return not only the table but also the view - read jdbc:hsqldb view and table") {
    import io.smartdatalake.util.misc.DataFrameUtil.DfSDL
    instanceRegistry.register(jdbcConnection)
    try {
      val db = "public"
      val view = Table(Some(db), "test_view_191")
      val dataObjectView = JdbcTableDataObject("jdbcDO1", table = view, connectionId = "jdbcCon1")
      dataObjectView.dropTable
      val table = Table(Some(db), "test_table_191")
      val dataObjectTable = JdbcTableDataObject("jdbcDO1", table = table, connectionId = "jdbcCon1")
      dataObjectTable.dropTable

      jdbcConnection.execJdbcStatement(sql = "create view test_view_191 as (SELECT 'test_data' AS test_column from (values(0)));")
      jdbcConnection.execJdbcStatement(sql = "create table test_table_191 (test_column char(9));")
      jdbcConnection.execJdbcStatement(sql = "insert into test_table_191 (test_column) VALUES ('test_data');")

      val dfReadView = dataObjectView.getDataFrame(Seq())
      val dfReadTable = dataObjectTable.getDataFrame(Seq())

      val df = Seq(("test_data")).toDF("test_column")
      assert(jdbcConnection.catalog.asInstanceOf[DefaultJdbcCatalog].isTableExisting(s"$db.${view.name}"))
      assert(jdbcConnection.catalog.asInstanceOf[DefaultJdbcCatalog].isTableExisting(s"$db.${table.name}"))
      assert(dfReadView.symmetricDifference(df).isEmpty)
      assert(dfReadTable.symmetricDifference(df).isEmpty)

    } finally {
      jdbcConnection.execJdbcStatement(sql = "DROP view if exists test_view_191;")
      jdbcConnection.execJdbcStatement(sql = "DROP table if exists test_table_191;")
    }
  }

  test("list jdbc table virtual partitions") {
    instanceRegistry.register(jdbcConnection)
    val table = Table(Some("public"), "table1")
    val dataObject = JdbcTableDataObject( "jdbcDO1", table = table, connectionId = "jdbcCon1", virtualPartitions = Seq("abc"), jdbcOptions = Map("createTableColumnTypes"->"abc varchar(255), lastname varchar(255), firstname varchar(255)"))
    dataObject.dropTable
    // Be careful when writing lower case column names over Jdbc with Spark. When creating the table through Spark they will be surrounded with quotes and become case-sensitiv!
    // In consequence the virtual partition has to be surrounded with quotes as well, see next test case.
    val df = Seq(("ext","doe","john",5),("ext","smith","peter",3),("int","emma","brown",7)).toDF("ABC", "lastname", "firstname", "rating")
    dataObject.init(df, Seq())
    dataObject.writeDataFrame(df, Seq())
    dataObject.prepare
    dataObject.getDataFrame(Seq()).show
    assert(dataObject.isTableExisting)
    val partitionValues = dataObject.listPartitions
    assert(partitionValues.size == 2)
    assert(partitionValues.map(_.elements("abc")).toSet == Set("ext","int"))
  }

  test("list jdbc table virtual partitions case quoted identifier") {
    instanceRegistry.register(jdbcConnection)
    val table = Table(Some("public"), "table1")
    val dataObject = JdbcTableDataObject( "jdbcDO1", table = table, connectionId = "jdbcCon1", virtualPartitions = Seq("aBc"), createSql = Some("""CREATE TABLE public.table1 ("aBc" varchar(255) , lastname varchar(255) , firstname varchar(255) , rating INTEGER NOT NULL)"""))
    dataObject.dropTable
    val df = Seq(("ext","doe","john",5),("ext","smith","peter",3),("int","emma","brown",7)).toDF("abc", "lastname", "firstname", "rating")
    dataObject.prepare
    dataObject.init(df, Seq())
    dataObject.writeDataFrame(df, Seq())
    dataObject.getDataFrame(Seq()).show
    assert(dataObject.isTableExisting)
    val partitionValues = dataObject.listPartitions
    assert(partitionValues.size == 2)
    assert(partitionValues.map(_.elements("abc")).toSet == Set("ext","int"))
    dataObject.getDataFrame().select($"abc").show
  }

  test("SaveMode merge") {
    instanceRegistry.register(jdbcConnection)
    val targetTable = Table(db = Some("public"), name = "test_merge", query = None, primaryKey = Some(Seq("type","lastname","firstname")))
    val targetDO = JdbcTableDataObject( "jdbcDO1", table = targetTable, connectionId = "jdbcCon1", saveMode = SDLSaveMode.Merge, jdbcOptions = Map("createTableColumnTypes"->"type varchar(255), lastname varchar(255), firstname varchar(255)"))
    targetDO.dropTable

    // first load
    val df1 = Seq(("ext","doe","john",5),("ext","smith","peter",3))
      .toDF("type", "lastname", "firstname", "rating")
    targetDO.prepare
    targetDO.init(df1, Seq())
    targetDO.writeDataFrame(df1)
    val actual = targetDO.getDataFrame()
    val resultat = df1.isEqual(actual)
    if (!resultat) TestUtil.printFailedTestResult("Df2HiveTable",Seq())(actual)(df1)
    assert(resultat)

    // 2nd load: merge data by primary key
    val df2 = Seq(("ext","doe","john",10),("int","emma","brown",7))
      .toDF("type", "lastname", "firstname", "rating")
    targetDO.writeDataFrame(df2)
    val actual2 = targetDO.getDataFrame()
    val expected2 = Seq(("ext","doe","john",10),("ext","smith","peter",3),("int","emma","brown",7))
      .toDF("type", "lastname", "firstname", "rating")
    val resultat2: Boolean = expected2.isEqual(actual2)
    if (!resultat2) TestUtil.printFailedTestResult("SaveMode merge",Seq())(actual2)(expected2)
    assert(resultat2)
  }

  test("SaveMode merge with schema evolution") {
    instanceRegistry.register(jdbcConnection)
    val targetTable = Table(db = Some("public"), name = "test_merge", query = None, primaryKey = Some(Seq("type","lastname","firstname")))
    val targetDO = JdbcTableDataObject( "jdbcDO1", table = targetTable, connectionId = "jdbcCon1", allowSchemaEvolution = true, saveMode = SDLSaveMode.Merge, jdbcOptions = Map("createTableColumnTypes"->"type varchar(255), lastname varchar(255), firstname varchar(255)"))
    targetDO.dropTable

    // first load
    val df1 = Seq(("ext","doe","john",5),("ext","smith","peter",3))
      .toDF("type", "lastname", "firstname", "rating")
    targetDO.prepare
    targetDO.init(df1, Seq())
    targetDO.writeDataFrame(df1)
    val actual = targetDO.getDataFrame()
    val resultat = df1.isEqual(actual)
    if (!resultat) TestUtil.printFailedTestResult("Df2HiveTable",Seq())(actual)(df1)
    assert(resultat)

    // 2nd load: merge data by primary key with different schema
    // - column 'rating' deleted -> existing records will keep column rating untouched (values are preserved and not set to null), new records will get new column rating set to null.
    // - column 'rating2' added -> existing records will get new column rating2 set to null
    val df2 = Seq(("ext","doe","john",10),("int","emma","brown",7))
      .toDF("type", "lastname", "firstname", "rating2")
    targetDO.init(df2, Seq())
    targetDO.writeDataFrame(df2)
    val actual2 = targetDO.getDataFrame()
    val expected2 = Seq(("ext","doe","john",Some(5),Some(10)),("ext","smith","peter",Some(3),None),("int","emma","brown",None,Some(7)))
      .toDF("type", "lastname", "firstname", "rating", "rating2")
    val resultat2: Boolean = expected2.isEqual(actual2)
    if (!resultat2) TestUtil.printFailedTestResult("SaveMode merge",Seq())(actual2)(expected2)
    assert(resultat2)
  }
}
