# Reference
After running your first [examples](GettingStarted.md) this page will give you more details on all available options and the core concepts behind Smart Data Lake.

## Configuration - HOCON
The configuration files are stored in the [HOCON](https://github.com/lightbend/config/blob/master/HOCON.md) file format.
The main sections are global, connections, data objects and actions. 
As a starting point, use the [application.conf](https://github.com/smart-data-lake/sdl-examples/blob/develop/src/main/resources/application.conf) from SDL-examples. 
More details and options are described below. 

### Global Options
In the global section of the configuration you can set global configurations such as spark options used by all executions.
You can find a list of all configurations under [API docs](site/scaladocs/io/smartdatalake/app/GlobalConfig.html).  
A good list with examples can be found in the [application.conf](https://github.com/smart-data-lake/sdl-examples/blob/develop/src/main/resources/application.conf) of sdl-examples.

### Secrets User and Password Variables
Usernames, passwords and other secrets should not be stored in your configuration files in clear text as these files are often stored directly in the version control system.
They should also not be visible in logfiles after execution.

Instead of having the username and password configured directly in your configuration files, you can use secret providers
for configuration values that end with `...Variable`, like BasicAuthMode's userVariable and passwordVariable.
To configure a secret use the convention `<SecretProviderId>#<SecretName>`.

Default secret providers are:

SecretProviderId|Pattern|Meaning
---|---|---
CLEAR|CLEAR#pd|The secret will be used literally (cleartext). This is only recommended for test environments.
ENV|ENV#pd|The value for this secret will be read from the environment variable called "pd". 

You can configure custom secret providers by providing a list of secret providers with custom options and their id in GlobalConfig as follows:
```
global {
  secretProviders {
    <secretProviderId> = {
     className = <fully qualified class name of SecretProvider>
     options = { <options as key/value> }
    }
  }
}
```

Example: configure DatabricksSecretProvider with id=DBSECRETS and scope=test (scope is a specific configuration needed for accessing secrets in Databricks).
```
global {
  secretProviders {
    DBSECRETS = {
     className = io.smartdatalake.util.secrets.DatabricksSecretProvider
     options = { scope = test }
    }
  }
}
```

You can create custom SecretProvider classes by implementing trait SecretProvider and a constructor with parameter `options: Map[String,String]`.

### Local substitution
Local substitution allows to reuse the id of a configuration object inside its attribute definitions by the special token "~{id}". See the following example:
```
dataObjects {
  dataXY {
    type = HiveTableDataObject
    path = "/data/~{id}"
    table {
      db = "default"
      name = "~{id}"
    }
  }
}
```
Note: local substitution only works in a fixed set of attributes defined in Environment.configPathsForLocalSubstitution.

## Connections
Some Data Objects need a connection, e.g. JdbcTableDataObject, as they need to know how to connect to a database.
Instead of defining the connection information for every data object, you can conveniently define it in one place and just use the reference in the data objects.
The possible parameters depend on the connection type. Please note the section on [usernames and password](#user-and-password-variables).

For a list of all available connections, please consult the [API docs](site/scaladocs/io/smartdatalake/workflow/connection/package.html) directly.

In the package overview, you can also see the parameters available to each type of connection and which parameters are optional.

## Data Objects
For a list of all available data objects, please consult the [API docs](site/scaladocs/io/smartdatalake/workflow/dataobject/package.html) directly.
In the package overview, you can also see the parameters available to each type of data object and which parameters are optional.

Data objects are structured in a hierarchy as many attributes are shared between them, i.e. do Hive tables and transactional tables share common attributes modeled in TableDataObject. 

Here is an overview of all data objects:
![data object hierarchy](images/dataobject_hierarchy.png)

## Actions
For a list of all available actions, please consult the [API docs](site/scaladocs/io/smartdatalake/workflow/action/package.html) directly.

In the package overview, you can also see the parameters available to each type of action and which parameters are optional.


# Command Line
SmartDataLakeBuilder is a java application. To run on a cluster with spark-submit use **DefaultSmartDataLakeBuilder** application.
It can be started with the following command line (details see [YARN](YARN.md))
```bash
spark-submit --master yarn --deploy-mode client --class io.smartdatalake.app.DefaultSmartDataLakeBuilder target/smartdatalake_2.11-1.0.3-jar-with-dependencies.jar [arguments]
```
and takes the following arguments:
```
Usage: DefaultSmartDataLakeBuilder [options]
  -f, --feed-sel <value>   Regex pattern to select the feed to execute.
  -n, --name <value>       Optional name of the application. If not specified feed-sel is used.
  -c, --config <value>     One or multiple configuration files or directories containing configuration files, separated by comma.
  --partition-values <value>
                           Partition values to process in format <partitionColName>=<partitionValue>[,<partitionValue>,...].
  --multi-partition-values <value>
                           Multi partition values to process in format <partitionColName1>=<partitionValue>,<partitionColName2>=<partitionValue>[;(<partitionColName1>=<partitionValue>,<partitionColName2>=<partitionValue>;...].
  --parallelism <value>    Parallelism for DAG run.
  --state-path <value>     Path to save run state files. Must be set to enable recovery in case of failures.
  --override-jars <value>  Comma separated list of jars for child-first class loader. The jars must be present in classpath.
  --test <value>           Run in test mode: config -> validate configuration, dry-run -> execute prepare- and init-phase only to check environment and spark lineage
  --help                   Display the help text.
  --version                Display version information.
```
There exists the following adapted applications versions:
- **LocalSmartDataLakeBuilder**:<br>default for Spark master is `local[*]` and it has additional properties to configure Kerberos authentication. Use this application to run in a local environment (e.g. IntelliJ) without cluster deployment.  
- **DatabricksSmartDataLakeBuilder**:<br>see [MicrosoftAzure](MicrosoftAzure.md)

# Concepts

## DAG

### Execution phases - early validation
Execution of a SmartDataLakeBuilder run is designed with "early validation" in mind. This means it tries to fail as early as possible if something is wrong. 
Execution therefore involves the following phases.
1. Parse configuration: configuration is parsed and validated 
2. DAG prepare: Preconditions are validated. This includes testing Connections and DataObject structures which must exists. 
3. DAG init: Lineage of Actions according to the DAG is created and validated. For Spark Actions this involves the validation of the DataFrame lineage. A column which doesn't exist but is referenced in a later Action will fail the execution.    
4. DAG exec: Execution of Actions, data is effectively (and only) transferred during this phase.

## Execution modes
Execution modes select the data to be processed. By default, if you start SmartDataLakeBuilder, there is no filter applied. This means every Action reads all data from its input DataObjects.

You can set an execution mode by defining attribute "executionMode" of an Action. Define the chosen ExecutionMode by setting type as follows:
```
executionMode {
  type = PartitionDiffMode
  attribute1 = ...
}
```

### Fixed partition values filter
You can apply a filter manually by specifying parameter --partition-values or --multi-partition-values on the command line. The partition values specified are passed to all start-Actions of a DAG and filtered for every input DataObject by its defined partition columns.
On execution every Action takes the partition values of the input and filters them again for every output DataObject by its defined partition columns, which serve again as partition values for the input of the next Action. 
Note that during execution of the dag, no new partition values are added, they are only filtered. An exception is if you place a PartitionDiffMode in the middle of your pipeline, see next section.

### PartitionDiffMode: Dynamic partition values filter 
Alternatively you can let SmartDataLakeBuilder find missing partitions and set partition values automatically by specifying execution mode PartitionDiffMode.

By defining the **applyCondition** attribute you can give a condition to decide at runtime if the PartitionDiffMode should be applied or not.
Default is to apply the PartitionDiffMode if the given partition values are empty (partition values from command line or passed from previous action). 
Define an applyCondition by a spark sql expression working with attributes of DefaultExecutionModeExpressionData returning a boolean.

By defining the **failCondition** attribute you can give a condition to fail application of execution mode if true. 
It can be used to fail a run based on expected partitions, time and so on.
The expression is evaluated after execution of PartitionDiffMode, amongst others there are attributes inputPartitionValues, outputPartitionValues and selectedPartitionValues to make the decision.
Default is that the application of the PartitionDiffMode does not fail the action. If there is no data to process, the following actions are skipped.
Define a failCondition by a spark sql expression working with attributes of PartitionDiffModeExpressionData returning a boolean.

Example - fail if partitions are not processed in strictly increasing order of partition column "dt":
```
  failCondition = "(size(selectedPartitionValues) > 0 and array_min(transform(selectedPartitionValues, x -> x.dt)) < array_max(transform(outputPartitionValues, x -> x.dt)))"
```

Sometimes the failCondition can become quite complex with multiple terms concatenated by or-logic. 
To improve interpretabily of error messages, multiple fail conditions can be configured as array with attribute **failConditions**. For every condition you can also define a description which will be inserted into the error message. 

Finally By defining **selectExpression** you can customize which partitions are selected.
Define a spark sql expression working with attributes of PartitionDiffModeExpressionData returning a Seq(Map(String,String)).

Example - only process the last selected partition: 
```
  selectExpression = "slice(selectedPartitionValues,-1,1)"
```

By defining **alternativeOutputId** attribute you can define another DataObject which will be used to check for already existing data.
This can be used to select data to process against a DataObject later in the pipeline.

### SparkStreamingOnceMode: Incremental load 
Some DataObjects are not partitioned, but nevertheless you dont want to read all data from the input on every run. You want to load it incrementally.
This can be accomplished by specifying execution mode SparkStreamingOnceMode. Under the hood it uses "Spark Structured Streaming" and triggers a single microbatch (Trigger.Once).
"Spark Structured Streaming" helps keeping state information about processed data. It needs a checkpointLocation configured which can be given as parameter to SparkStreamingOnceMode.

Note that "Spark Structured Streaming" needs an input DataObject supporting the creation of streaming DataFrames. 
For the time being, only the input sources delivered with Spark Streaming are supported. 
This are KafkaTopicDataObject and all SparkFileDataObjects, see also [Spark StructuredStreaming](https://spark.apache.org/docs/latest/structured-streaming-programming-guide.html#creating-streaming-dataframes-and-streaming-datasets).

### SparkIncrementalMode: Incremental Load
As not every input DataObject supports the creation of streaming DataFrames, there is an other execution mode called SparkIncrementalMode.
You configure it by defining the attribute **compareCol** with a column name present in input and output DataObject. 
SparkIncrementalMode then compares the maximum values between input and output and creates a filter condition.
On execution the filter condition is applied to the input DataObject to load the missing increment.
Note that compareCol needs to have a sortable datatype.

By defining **applyCondition** attribute you can give a condition to decide at runtime if the SparkIncrementalMode should be applied or not.
Default is to apply the SparkIncrementalMode. Define an applyCondition by a spark sql expression working with attributes of DefaultExecutionModeExpressionData returning a boolean.

By defining **alternativeOutputId** attribute you can define another DataObject which will be used to check for already existing data.
This can be used to select data to process against a DataObject later in the pipeline.

### FailIfNoPartitionValuesMode
To simply check if partition values are present and fail otherwise, configure execution mode FailIfNoPartitionValuesMode.
This is useful to prevent potential reprocessing of whole table through wrong usage.

### ProcessAllMode
An execution mode which forces processing all data from it's inputs, removing partitionValues and filter conditions received from previous actions.

### CustomPartitionMode
An execution mode to create custom partition execution mode logic in scala. 
Implement trait CustomPartitionModeLogic by defining a function which receives main input&output DataObject and returns partition values to process as Seq[Map[String,String]\]

## Execution Condition
For every Action an executionCondition can be defined. The execution condition allows to define if an action is executed or skipped. The default behaviour is that an Action is skipped if at least one input SubFeed is skipped.
Define an executionCondition by a spark sql expression working with attributes of SubFeedsExpressionData returning a boolean.
The Action is skipped if the executionCondition is evaluated to false. In that case dependent actions get empty SubFeeds marked with isSkipped=true as input.

Example - skip Action only if input1 and input2 SubFeed are skipped: 
```
  executionCondition = "!inputSubFeeds.input1.isSkipped or !inputSubFeeds.input2.isSkipped"
```

Example - Always execute Action and use all existing data as input: 
```
  executionCondition = true
  executionMode = ProcessAllMode
```

## Metrics
Metrics are gathered per Action and output-DataObject when running a DAG. They can be found in log statements and are written to the state file.

Sample log message:
`2020-07-21 11:36:34 INFO  CopyAction:105 - (Action~a) finished writing DataFrame to DataObject~tgt1: duration=PT0.906S records_written=1 bytes_written=1142 num_tasks=1 stage=save`

A fail condition can be specified on Actions to fail execution if a certain condition is not met.
The condition must be specified as spark sql expression, which is evaluated as where-clause against a dataframe of metrics. Available columns are dataObjectId, key, value. 
To fail above sample log in case there are no records written, specify `"dataObjectId = 'tgt1' and key = 'records_written' and value = 0"`.

By implementing interface StateListener  you can get notified about action results & metrics. To configure state listeners set config attribute `global.stateListeners = [{className = ...}]`.

## Custom Spark Transformations
To implement custom transformation logic, define the **transformer** attribute of an Action. 
Note that the definition of the transformation looks different for:
* **1-to-1** transformations: One input DataFrame is transformed into one output DataFrame. This is the case for CopyAction, DeduplicateAction and HistorizeAction. 
* **many-to-many** transformations: Many input DataFrames are transformed into many output DataFrames. This is the case for CustomSparkAction. Configuration is defined by a CustomDfsTransformerConfig.

The config allows you to define the transformation in various languages and passing static **options** and **runtimeOptions** to the transformation. runtimeOptions are extracted at runtime from the context.
Specifying options allows to reuse a transformation in different settings. 
 
### Java/Scala
You can use Spark Dataset API in Java/Scala to define custom transformations. 
If you have a Java project, create a class that extends CustomDfTransformer or CustomDfsTransformer and implement `transform` method

If you work without Java project, it's still possible to define your transformation in Java/Scala and compile it at runtime.
For a 1-to-1 transformation you have to write a function that takes `session: SparkSession, options: Map[String,String], df: DataFrame, dataObjectName: String` as parameters and returns a `DataFrame`. 
Use **scalaFile** or **scalaCode** attribute to configure this.

For many-to-many transformations you get a Map[String,DataFrame] with the DataFrames per input DataObject as parameter, and have to return a Map[String,DataFrame] with the DataFrame per output DataObject.

See [sdl-examples](https://github.com/smart-data-lake/sdl-examples) for details.

### SQL
You can use Spark SQL to define custom transformations by defining **sqlCode** attribute.
Input dataObjects are available as tables to select from. Use tokens %{<key>} to replace with runtimeOptions in SQL code.

Example - using options in sql code for 1-to-1 transformation:
```
transformer {
  sqlCode = "select id, cnt, '%{test}' as test, %{run_id} as last_run_id from dataObject1"
  options = {
    test = "test run"
  }
  runtimeOptions = {
    last_run_id = "runId - 1" // runtime options are evaluated as spark SQL expressions against DefaultExpressionData
  }
}
```

Example - defining a many-to-many transformation:
```
transformer.sqlCode {
  dataObjectOut1 = "select id,cnt from dataObjectIn1 where group = 'test1'",
  dataObjectOut2 = "select id,cnt from dataObjectIn1 where group = 'test2'"
}
```

See [sdl-examples](https://github.com/smart-data-lake/sdl-examples) for details.

### Python
It's also possible to use Python to define a custom Spark transformation. 
Use **pythonFile** or **pythonCode** attribute to define your transformation. 
PySpark session is initialize and available under variables `sc`, `session`, `sqlContext`.
Other variables available are
* `inputDf`: Input DataFrame
* `options`: Transformation options as Map[String,String]
* `dataObjectId`: Id of input dataObject as String

Output DataFrame must be set with `setOutputDf(df)`.

For now using Python for many-to-many transformations is not possible, although it would be not so hard to implement.

Example - apply some python calculation as udf:
```
transformer.pythonCode = """
  |from pyspark.sql.functions import *
  |udf_multiply = udf(lambda x, y: x * y, "int")
  |dfResult = inputDf.select(col("name"), col("cnt"))\
  |  .withColumn("test", udf_multiply(col("cnt").cast("int"), lit(2)))
  |setOutputDf(dfResult)
"""
```

Requirements: 
* Spark 2.4.x: 
  * Python version >= 3.4 an <= 3.7
  * PySpark package matching your spark version
* Spark 3.x:
  * Python version >= 3.4
  * PySpark package matching your spark version

See Readme of [sdl-examples](https://github.com/smart-data-lake/sdl-examples) for a working example and instructions to setup python environment for IntelliJ  

How it works: under the hood a PySpark DataFrame is a proxy for a Java Spark DataFrame. PySpark uses Py4j to access Java objects in the JVM.

## Schema Evolution
SmartDataLakeBuilder is built to support schema evolution where possible. This means that data pipelines adapt themselves automatically to additional or removed columns and changes of data types if possible.

To assert that a defined list of columns is always present in the schema of a specific DataObject, use its `schemaMin` attribute to define a minimal schema. The minimal schema is validated on read and write with Spark.

To fix the schema for a specific DataObject many DataObjects support the `schema` attribute (e.g. all children of SparkFileDataObject) for reading and writing with Spark. 
The `schema` attribute allows to define the schema the DataObject tries to read data with, and can be used to avoid schema inference with Spark DataSources.
On write the DataFrame to be written must match the defined `schema` exactly (nullability and column order are ignored). 

The following list describes specific behaviour of DataObjects:
* HiveTableDataObject & TickTockHiveTableDataObject: Table schema is managed by Hive and automatically created on first write and updated on subsequent overwrites of the whole table. Changing schema for partitioned tables is not supported.
  By manipulating the table definition with DDL statements (e.g. alter table add columns) its possible to read data files with a different schema. 
* SparkFileDataObject: see detailed description in [Spark Data Sources](https://spark.apache.org/docs/latest/sql-data-sources.html).
  * Many Data Sources support schema inference (e.g. Json, Csv), but we would not recommend this for production data pipelines as the result might not be stable when new data arrives.
  * For Data Formats with included schema (e.g. Avro, Parquet), schema is read from a random data file. If data files have different schemas, Parquet Data Source supports to consolidate schemas by setting option `mergeSchema=true`. Avro Data Source does not support this.
  * If you define the `schema` attribute of the DataObject, SDL tries to read the data files with the defined schema. This is e.g. supported by the Json Data Source, but not the CSV Data Source.
* JdbcTableDataObject: The table has to be created manually or by providing a create table statement in `createSql` attribute. There is no automatic schema evolution for now.

Recipes for data pipelines with schema evolution:
* CopyAction supports schema evolution if all data is processed by overwriting the whole output DataObject. It needs an output DataObject which doesn't have a fixed schema, e.g. HiveTableDataObject.
* HistorizeAction & DeduplicateAction supports schema evolution for incremental data. They consolidate the existing data & schema of the output DataObject with a potentially new schema of the input DataObject. Then they overwrite the whole output DataObject. They need a TransactionalSparkTableDataObject as output, which doesn't have a fixed schema, e.g. TickTockHiveTableDataObject.
