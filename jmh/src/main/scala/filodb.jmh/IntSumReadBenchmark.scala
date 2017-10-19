package filodb.jmh

import java.util.concurrent.TimeUnit

import scala.language.postfixOps
import scala.util.Random
import scalaxy.loops._

import filodb.core.metadata.Dataset
import filodb.core.query.ChunkSetReader
import filodb.core.store.ChunkSet
import filodb.memory.format.{FastFiloRowReader, FiloVector, TupleRowReader}

import ch.qos.logback.classic.{Level, Logger}
import com.googlecode.javaewah.EWAHCompressedBitmap
import org.openjdk.jmh.annotations._

object IntSumReadBenchmark {
  val dataset = Dataset("dataset", Seq("part:int"), Seq("int:int", "rownum:int"), "rownum")
  val rowIt = Iterator.from(0).map { row => (Some(scala.util.Random.nextInt), Some(row), Some(0)) }
  val partKey = dataset.partKey(0)
  val rowColumns = Seq("int", "rownum", "part")

  org.slf4j.LoggerFactory.getLogger("filodb").asInstanceOf[Logger].setLevel(Level.ERROR)
}

/**
 * Microbenchmark of simple integer summing of Filo chunks in FiloDB segments,
 * mostly to see what the theoretical peak output of scanning speeds can be.
 * Does not involve Spark (at least this one doesn't).
 */
@State(Scope.Thread)
class IntSumReadBenchmark {
  import IntSumReadBenchmark._
  val NumRows = 10000

  val chunkSet = ChunkSet(dataset, partKey, rowIt.map(TupleRowReader).take(NumRows).toSeq)
  val reader = ChunkSetReader(chunkSet, dataset, 0 until dataset.dataColumns.length)

  val NumSkips = 300  // 3% skips - not that much really
  val skips = (0 until NumSkips).map { i => Random.nextInt(NumRows) }.sorted.distinct
  val readerWithSkips = ChunkSetReader(chunkSet, dataset, 0 until dataset.dataColumns.length,
                                       EWAHCompressedBitmap.bitmapOf(skips: _*))

  /**
   * Simulation of a columnar query engine scanning the segment chunks columnar wise
   */
  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  def columnarChunkScan(): Int = {
    val intVector = reader.vectors(0).asInstanceOf[FiloVector[Int]]
    var total = 0
    for { i <- 0 until NumRows optimized } {
      total += intVector(i)
    }
    total
  }

  /**
   * Simulation of ideal row-wise scanning speed with no boxing (Spark 1.5+ w Tungsten?) and no rows to skip
   */
  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  def rowWiseChunkScan(): Int = {
    val it = reader.rowIterator()
    var sum = 0
    while(it.hasNext) {
      sum += it.next.getInt(0)
    }
    sum
  }

  /**
   * Row-wise scanning with null/isAvailable check
   */
  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  def rowWiseChunkScanNullCheck(): Int = {
    val it = reader.rowIterator()
    var sum = 0
    while(it.hasNext) {
      val row = it.next
      if (row.notNull(0)) sum += row.getInt(0)
    }
    sum
  }

  /**
   * Row-wise scanning with null/isAvailable check and rows to skip
   */
  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  def rowWiseChunkScanNullWithSkips(): Int = {
    val it = readerWithSkips.rowIterator()
    var sum = 0
    while(it.hasNext) {
      val row = it.next
      if (row.notNull(0)) sum += row.getInt(0)
    }
    sum
  }

  /**
   * Simulation of boxed row-wise scanning speed (Spark 1.4.x aggregations)
   */
  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  def rowWiseBoxedChunkScan(): Int = {
    val it = reader.rowIterator()
    var sum = 0
    while(it.hasNext) {
      sum += it.next.asInstanceOf[FastFiloRowReader].getAny(0).asInstanceOf[Int]
    }
    sum
  }
}