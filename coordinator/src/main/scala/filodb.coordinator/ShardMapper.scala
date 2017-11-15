package filodb.coordinator

import scala.util.{Failure, Success, Try}

import akka.actor.{ActorRef, Address}

import filodb.core.DatasetRef

/**
 * Each FiloDB dataset is divided into a fixed number of shards for ingestion and distributed in-memory
 * querying. The ShardMapper keeps track of the mapping between shards and nodes for a single dataset.
 * It also keeps track of the status of each shard.
 * - Given a partition hash, find the shard and node coordinator
 * - Given a shard key hash and # bits, find the shards and node coordinators to query
 * - Given a shard key hash and partition hash, # bits, compute the shard (for ingestion partitioning)
 * - Register a node to given shard numbers
 *
 * It is not multi thread safe for mutations (registrations) but reads should be fine.
 *
 * The shard finding given a hash needs to be VERY fast, it is in the hot query and ingestion path.
  *
  * @param numShards number of shards. For this implementation, it needs to be a power of 2.
  *
 */
class ShardMapper(val numShards: Int) extends Serializable {
  import ShardMapper._

  require((numShards & (numShards - 1)) == 0, s"numShards $numShards must be a power of two")

  private final val log2NumShards = (scala.math.log10(numShards) / scala.math.log10(2)).round.toInt
  private final val shardMap = Array.fill(numShards)(ActorRef.noSender)
  private final val statusMap = Array.fill[ShardStatus](numShards)(ShardUnassigned)
  private final val log2NumShardsOneBits = (1 << log2NumShards) - 1 // results in log2NumShards one bits

  // spreadMask is precomputed for all possible spreads.
  // The spread is the array index. Value is (log2NumShards-spread) bits set to 1 followed by spread bits set to 0
  private final val spreadMask = Array.tabulate[Int](log2NumShards + 1) { i =>
    (log2NumShardsOneBits << i) & log2NumShardsOneBits
  }

  // spreadOneBits is precomputed for all possible spreads.
  // The spread is the array index. Value is spread 1 bits
  private final val spreadOneBits = Array.tabulate[Int](log2NumShards + 1) { i =>
    (1 << i) - 1
  }


  override def equals(other: Any): Boolean = other match {
    case s: ShardMapper => s.numShards == numShards && s.shardValues == shardValues
    case o: Any         => false
  }

  override def hashCode: Int = shardValues.hashCode

  override def toString: String = s"ShardMapper ${shardValues.zipWithIndex}"

  def shardValues: Seq[(ActorRef, ShardStatus)] = shardMap.zip(statusMap).toBuffer

  /**
   * Maps a partition hash to a shard number and a NodeCoordinator ActorRef
   */
  def partitionToShardNode(partitionHash: Int): ShardAndNode = {
    val shard = toShard(partitionHash, numShards) // TODO this is not right. Need to fix
    ShardAndNode(shard, shardMap(shard))
  }

  def coordForShard(shardNum: Int): ActorRef = shardMap(shardNum)
  def unassigned(shardNum: Int): Boolean = coordForShard(shardNum) == ActorRef.noSender
  def statusForShard(shardNum: Int): ShardStatus = statusMap(shardNum)


  /**
    * Use this function to identify the list of shards to query given the shard key hash.
    *
    * @param shardKeyHash This is the shard key hash, and is used to identify the shard group
    * @param spread       This is the 'spread' S assigned for a given appName. The data for every
    *                     metric in the app is spread across 2^S^ shards. Example: if S=2, data
    *                     is spread across 4 shards. If S=0, data is located in 1 shard. Bigger
    *                     apps are assigned bigger S and smaller apps are assigned small S.
    * @return The shard numbers that hold data for the given shardKeyHash
    */
  def queryShards(shardKeyHash: Int, spread: Int): Seq[Int] = {
    validateSpread(spread)

    // formulate shardMask (like CIDR mask) by setting least significant 'spread' bits to 0
    val shardMask = shardKeyHash & spreadMask(spread)

    // create a range starting from shardMask to the shardMask with last 'spread' bits set to 1
    shardMask to (shardMask | spreadOneBits(spread))
  }

  private def validateSpread(spread: Int) = {
    require(spread >= 0 && spread <= log2NumShards, s"Invalid spread $spread. log2NumShards is $log2NumShards")
  }

  /**
    * Use this function to calculate the ingestion shard for a fully specified partition id.
    * The code logic ingesting data into partitions can use this function to direct data
    * to the right partition
    *
    * @param shardKeyHash  This is the shard key hash, and is used to identify the shard group
    * @param partitionHash The 32-bit hash of the overall partition or time series key, containing all tags
    * @param spread        This is the 'spread' S assigned for a given appName. The data for every
    *                      metric in the app is spread across 2^S^ shards. Example: if S=2, data
    *                      is spread across 4 shards. If S=0, data is located in 1 shard. Bigger
    *                      apps are assigned bigger S and smaller apps are assigned small S.
    * @return The shard number that contains the partition for the record described by the given
    *         shardKeyHash and partitionHash
    */
  def ingestionShard(shardKeyHash: Int, partitionHash: Int, spread: Int): Int = {
    validateSpread(spread)

    // explanation for the one-liner:
    // first part formulates shardMask (like CIDR mask) by setting shardKeyHash's least significant 'spread' bits to 0
    // second part extracts last 'spread' bits from tagHash
    // then combine the two
    (shardKeyHash & spreadMask(spread)) | (partitionHash & spreadOneBits(spread))
  }

  @deprecated(message = "Use ingestionShard() instead of this method", since = "0.7")
  def hashToShard(shardHash: Int, partitionHash: Int, numShardBits: Int): Int = {
    ingestionShard(shardHash, partitionHash, log2NumShards - numShardBits)
  }

  /**
   * Returns all shards that match a given address - typically used to compare to cluster.selfAddress
   * for that node's own shards
   */
  def shardsForAddress(addr: Address): Seq[Int] =
    shardMap.toSeq.zipWithIndex.collect {
      case (ref, shardNum) if ref != ActorRef.noSender && ref.path.address == addr => shardNum
    }

  /**
   * Returns all the shards that have not yet been assigned or in process of being assigned
   */
  def unassignedShards: Seq[Int] =
    shardMap.toSeq.zipWithIndex.collect { case (ActorRef.noSender, shard) => shard }

  def assignedShards: Seq[Int] =
    shardMap.toSeq.zipWithIndex.collect { case (ref, shard) if ref != ActorRef.noSender => shard }

  def numAssignedShards: Int = numShards - unassignedShards.length

  /**
   * Find out if a shard is active (Normal or Recovery status) or filter a list of shards
   */
  def activeShard(shard: Int): Boolean =
    statusMap(shard) == ShardStatusNormal || statusMap(shard).isInstanceOf[ShardStatusRecovery]
  def activeShards(shards: Seq[Int]): Seq[Int] = shards.filter(activeShard)

  /**
   * Returns a set of unique NodeCoordinator ActorRefs for all assigned shards
   */
  def allNodes: Set[ActorRef] = shardMap.toSeq.filter(_ != ActorRef.noSender).toSet

  /**
   * The main API for updating a ShardMapper.
   * If you want to throw if an update does not succeed, call updateFromEvent(ev).get
   */
  def updateFromEvent(event: ShardEvent): Try[Unit] = event match {
    case IngestionStarted(_, shard, node) =>
      statusMap(shard) = ShardStatusNormal
      registerNode(Seq(shard), node)
    case ShardAssignmentStarted(_, shard, node) =>
      statusMap(shard) = ShardBeingAssigned
      registerNode(Seq(shard), node)
    case RecoveryStarted(_, shard, node, progress) =>
      statusMap(shard) = ShardStatusRecovery(progress)
      registerNode(Seq(shard), node)
    case IngestionError(_, shard, _) =>
      Success(())
    case ShardDown(_, shard) =>
      statusMap(shard) = ShardStatusDown
      Success(())
    case IngestionStopped(_, shard) =>
      statusMap(shard) = ShardStatusStopped
      Success(())
    case _ =>
      Success(())
  }

  /**
   * Returns the minimal set of events needed to reconstruct this ShardMapper
   */
  def minimalEvents(ref: DatasetRef): Seq[ShardEvent] =
    (0 until numShards).flatMap { shard =>
      statusMap(shard).minimalEvents(ref, shard, shardMap(shard))
    }

  /**
   * Registers a new node to the given shards.  Modifies state in place.
   */
  private[coordinator] def registerNode(shards: Seq[Int], coordinator: ActorRef): Try[Unit] = {
    shards.foreach { shard =>
      if (!unassigned(shard)) {
        return Failure(new IllegalArgumentException(s"Shard $shard is already assigned!"))
      } else {
        shardMap(shard) = coordinator
      }
    }
    Success(())
  }

  /**
   * Removes a coordinator ref from all shards mapped to it.  Resets the shards to no owner and
   * returns the shards removed.
   */
  private[coordinator] def removeNode(coordinator: ActorRef): Seq[Int] = {
    shardMap.toSeq.zipWithIndex.collect {
      case (ref, i) if ref == coordinator =>
        shardMap(i) = ActorRef.noSender
        i
    }
  }

  private[coordinator] def clear(): Unit = {
    for { i <- 0 until numShards } { shardMap(i) = ActorRef.noSender }
  }
}

private[filodb] object ShardMapper {

  val default = new ShardMapper(1)

  final case class ShardAndNode(shard: Int, coord: ActorRef)

  final def toShard(n: Int, numShards: Int): Int = (((n & 0xffffffffL) * numShards) >> 32).toInt

}