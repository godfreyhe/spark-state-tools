package org.apache.spark.sql.state

import org.apache.spark.sql.{DataFrame, Dataset}
import org.apache.spark.sql.catalyst.streaming.InternalOutputModes.Update
import org.apache.spark.sql.execution.streaming.MemoryStream
import org.apache.spark.sql.execution.streaming.state.StateStore
import org.apache.spark.sql.functions.{count, max, min, sum}
import org.apache.spark.sql.streaming.StreamTest

trait StateStoreTest extends StreamTest {
  import testImplicits._

  override def afterAll(): Unit = {
    super.afterAll()
    StateStore.stop()
  }

  protected def runSmallDataStreamingAggregationQuery(
      checkpointRoot: String): Unit = {
    import org.apache.spark.sql.functions._

    val inputData = MemoryStream[Int]

    val aggregated = inputData.toDF()
      .selectExpr("value", "value % 2 AS groupKey")
      .groupBy($"groupKey")
      .agg(
        count("*").as("cnt"),
        sum("value").as("sum"),
        max("value").as("max"),
        min("value").as("min")
      )
      .as[(Int, Long, Long, Int, Int)]

    testStream(aggregated, Update)(
      StartStream(checkpointLocation = checkpointRoot),
      AddData(inputData, 3),
      CheckLastBatch((1, 1, 3, 3, 3)),
      AddData(inputData, 3, 2),
      CheckLastBatch((1, 2, 6, 3, 3), (0, 1, 2, 2, 2)),
      StopStream,
      StartStream(checkpointLocation = checkpointRoot),
      AddData(inputData, 3, 2, 1),
      CheckLastBatch((1, 4, 10, 3, 1), (0, 2, 4, 2, 2))
    )
  }

  protected def runLargeDataStreamingAggregationQuery(
      checkpointRoot: String): Unit = {
    import org.apache.spark.sql.functions._

    val inputData = MemoryStream[Int]

    val aggregated = inputData.toDF()
      .selectExpr("value", "value % 10 AS groupKey")
      .groupBy($"groupKey")
      .agg(
        count("*").as("cnt"),
        sum("value").as("sum"),
        max("value").as("max"),
        min("value").as("min")
      )
      .as[(Int, Long, Long, Int, Int)]

    // check with more data - leverage full partitions
    testStream(aggregated, Update)(
      StartStream(checkpointLocation = checkpointRoot),
      // batch 1
      AddData(inputData, 0 until 20: _*),
      CheckLastBatch(
        (0, 2, 10, 10, 0), // 0, 10
        (1, 2, 12, 11, 1), // 1, 11
        (2, 2, 14, 12, 2), // 2, 12
        (3, 2, 16, 13, 3), // 3, 13
        (4, 2, 18, 14, 4), // 4, 14
        (5, 2, 20, 15, 5), // 5, 15
        (6, 2, 22, 16, 6), // 6, 16
        (7, 2, 24, 17, 7), // 7, 17
        (8, 2, 26, 18, 8), // 8, 18
        (9, 2, 28, 19, 9) // 9, 19
      ),
      // batch 2
      AddData(inputData, 20 until 40: _*),
      CheckLastBatch(
        (0, 4, 60, 30, 0), // 0, 10, 20, 30
        (1, 4, 64, 31, 1), // 1, 11, 21, 31
        (2, 4, 68, 32, 2), // 2, 12, 22, 32
        (3, 4, 72, 33, 3), // 3, 13, 23, 33
        (4, 4, 76, 34, 4), // 4, 14, 24, 34
        (5, 4, 80, 35, 5), // 5, 15, 25, 35
        (6, 4, 84, 36, 6), // 6, 16, 26, 36
        (7, 4, 88, 37, 7), // 7, 17, 27, 37
        (8, 4, 92, 38, 8), // 8, 18, 28, 38
        (9, 4, 96, 39, 9) // 9, 19, 29, 39
      ),
      StopStream,
      StartStream(checkpointLocation = checkpointRoot),
      // batch 3
      AddData(inputData, 0, 1, 2),
      CheckLastBatch(
        (0, 5, 60, 30, 0), // 0, 10, 20, 30, 0
        (1, 5, 65, 31, 1), // 1, 11, 21, 31, 1
        (2, 5, 70, 32, 2) // 2, 12, 22, 32, 2
      )
    )
  }
}
