package org.apache.spark.sql.state

import java.util.UUID

import org.apache.hadoop.fs.{Path, PathFilter}
import org.apache.spark.{Partition, TaskContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.sql.execution.streaming.state.{StateStore, StateStoreConf, StateStoreId, StateStoreProviderId}
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.SerializableConfiguration

import scala.util.Try

class StateStorePartition(
    val partition: Int,
    val queryId: UUID) extends Partition {
  override def index: Int = partition
}

class StateStoreReaderRDD(
    session: SparkSession,
    keySchema: StructType,
    valueSchema: StructType,
    stateCheckpointRootLocation: String,
    batchId: Long,
    operatorId: Long,
    storeName: String)
  extends RDD[(UnsafeRow, UnsafeRow)](session.sparkContext, Nil) {

  private val storeConf = new StateStoreConf(session.sessionState.conf)

  // A Hadoop Configuration can be about 10 KB, which is pretty big, so broadcast it
  private val hadoopConfBroadcast = session.sparkContext.broadcast(
    new SerializableConfiguration(session.sessionState.newHadoopConf()))

  override def compute(split: Partition, context: TaskContext): Iterator[(UnsafeRow, UnsafeRow)] = {
    split match {
      case p: StateStorePartition =>
        val stateStoreId = StateStoreId(stateCheckpointRootLocation, operatorId,
          p.partition, storeName)
        val stateStoreProviderId = StateStoreProviderId(stateStoreId, p.queryId)

        val store = StateStore.get(stateStoreProviderId, keySchema, valueSchema,
          indexOrdinal = None, version = batchId, storeConf = storeConf,
          hadoopConf = hadoopConfBroadcast.value.value)

        val iter = store.iterator().map(pair => (pair.key, pair.value))

        // close state store provider after using
        StateStore.unload(stateStoreProviderId)

        iter

      case e => throw new IllegalStateException("Expected StateStorePartition but other type of " +
        s"partition passed - $e")
    }
  }

  override protected def getPartitions: Array[Partition] = {
    val fs = stateCheckpointPartitionsLocation.getFileSystem(hadoopConfBroadcast.value.value)
    val partitions = fs.listStatus(stateCheckpointPartitionsLocation, new PathFilter() {
      override def accept(path: Path): Boolean = {
        fs.isDirectory(path) && Try(path.getName.toInt).isSuccess && path.getName.toInt >= 0
      }
    })

    if (partitions.headOption.isEmpty) {
      Array.empty[Partition]
    } else {
      // just a dummy query id because we are actually not running streaming query
      val queryId = UUID.randomUUID()

      val partitionsSorted = partitions.sortBy(fs => fs.getPath.getName.toInt)
      val partitionNums = partitionsSorted.map(_.getPath.getName.toInt)
      // assuming no same number - they're directories hence no same name
      val head = partitionNums.head
      val tail = partitionNums(partitionNums.length - 1)
      assert((tail - head + 1) == partitionNums.length, s"No continuous partitions in state: $partitionNums")

      partitionNums.map(pn => new StateStorePartition(pn, queryId)).toArray
    }
  }

  def stateCheckpointPartitionsLocation: Path = {
    new Path(stateCheckpointRootLocation, s"$operatorId")
  }

  def stateCheckpointLocation(partitionId: Int): Path = {
    val partitionsLocation = stateCheckpointPartitionsLocation
    if (storeName == StateStoreId.DEFAULT_STORE_NAME) {
      // For reading state store data that was generated before store names were used (Spark <= 2.2)
      new Path(partitionsLocation, s"$partitionId")
    } else {
      new Path(partitionsLocation, s"$partitionId/$storeName")
    }
  }
}