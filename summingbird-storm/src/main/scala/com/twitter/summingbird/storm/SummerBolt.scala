/*
 Copyright 2013 Twitter, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.twitter.summingbird.storm

import backtype.storm.task.{OutputCollector, TopologyContext}
import backtype.storm.tuple.{Tuple, Values, Fields}
import com.twitter.algebird.{Monoid, SummingQueue}
import com.twitter.summingbird.online.Externalizer
import com.twitter.storehaus.algebra.MergeableStore
import com.twitter.summingbird.batch.{BatchID, Timestamp}
import com.twitter.summingbird.storm.option._
import com.twitter.summingbird.option.CacheSize
import com.twitter.summingbird.online.FutureQueue
import scala.collection.JavaConverters._

import com.twitter.util.{Await, Future, Promise}
import java.util.{ Map => JMap }

/**
  * The SummerBolt takes two related options: CacheSize and MaxWaitingFutures.
  * CacheSize sets the number of key-value pairs that the SinkBolt will accept
  * (and sum into an internal map) before committing out to the online store.
  *
  * To perform this commit, the SinkBolt iterates through the map of aggregated
  * kv pairs and performs a "+" on the store for each pair, sequencing these
  * "+" calls together using the Future monad. If the store has high latency,
  * these calls might take a bit of time to complete.
  *
  * MaxWaitingFutures(count) handles this problem by realizing a future
  * representing the "+" of kv-pair n only when kvpair n + 100 shows up in the bolt,
  * effectively pushing back against latency bumps in the host.
  *
  * The allowed latency before a future is forced is equal to
  * (MaxWaitingFutures * execute latency).
  *
  * @author Oscar Boykin
  * @author Sam Ritchie
  * @author Ashu Singhal
  */
import org.slf4j.LoggerFactory

object SummerBolt {
  @transient private val logger = LoggerFactory.getLogger(classOf[SummerBolt[_, _]])
}

class SummerBolt[Key, Value: Monoid](
  @transient storeSupplier: () => MergeableStore[(Key,BatchID), Value],
  @transient successHandler: OnlineSuccessHandler,
  @transient exceptionHandler: OnlineExceptionHandler,
  cacheSize: CacheSize,
  metrics: SinkStormMetrics,
  maxWaitingFutures: MaxWaitingFutures,
  includeSuccessHandler: IncludeSuccessHandler,
  anchor: AnchorTuples,
  shouldEmit: Boolean) extends BaseBolt(metrics.metrics) {
  import SummerBolt._
  import Constants._

  val storeBox = Externalizer(storeSupplier)
  lazy val storePromise = Promise[MergeableStore[(Key,BatchID), Value]]()
  lazy val store = storePromise.get
  case class MergeData(oldTuples: List[Tuple], ts: Timestamp, key: Key, delta: Value, prevValue: Option[Value])

  // See MaxWaitingFutures for a todo around removing this.
  lazy val cacheCount = cacheSize.size
  lazy val buffer = SummingQueue[Map[(Key, BatchID), (List[Tuple], Timestamp, Value)]](cacheCount.getOrElse(0))
  lazy val futureQueue = FutureQueue[MergeData](maxWaitingFutures.get)

  val exceptionHandlerBox = Externalizer(exceptionHandler)
  val successHandlerBox = Externalizer(successHandler)

  var successHandlerOpt: Option[OnlineSuccessHandler] = null

  override val fields = Some(new Fields("pair"))

  override def prepare(
    conf: JMap[_,_], context: TopologyContext, oc: OutputCollector) {
    storePromise.setValue(storeBox.get.apply)
    logger.warn("Store object realized: " + store.toString)

    super.prepare(conf, context, oc)
    // see IncludeSuccessHandler for why this is needed
    successHandlerOpt = if (includeSuccessHandler.get)
      Some(successHandlerBox.get)
    else
      None
  }

  // TODO (https://github.com/twitter/tormenta/issues/1): Think about
  // how this can help with Tormenta's open issue for a tuple
  // conversion library. Storm emits Values and receives Tuples.
  def unpack(tuple: Tuple) = {
    val id = tuple.getValue(0).asInstanceOf[BatchID]
    val key = tuple.getValue(1).asInstanceOf[Key]
    val (ts, value) = tuple.getValue(2).asInstanceOf[(Timestamp, Value)]
    ((key, id), (List(tuple), ts, value))
  }

  def toValues(time: Timestamp, item: Any): Values = new Values((time.milliSinceEpoch, item))

  def emit(mergeData: MergeData) {
     if(shouldEmit) {
      val values = toValues(mergeData.ts, (mergeData.key, (mergeData.prevValue, mergeData.delta)))
        onCollector { col =>
            if (anchor.anchor) {
              col.emit(mergeData.oldTuples.asJava, values)
            }
            else {
              col.emit(values)
            }
        }
      }
  }

  private def processFinished {
      val mergeResults = futureQueue.pop
      mergeResults.foreach { mTry =>
        val mergeRes: MergeData = mTry.get
        emit(mergeRes)
      }
  }

  override def execute(tuple: Tuple) {
    logger.info("Queue size: " + futureQueue.length)
    processFinished

    // See MaxWaitingFutures for a todo around simplifying this.
    buffer(Map(unpack(tuple))).foreach { pairs =>
      futureQueue << pairs.map { case ((k, batchID), (oldTuples, ts, delta)) =>
        val pair = ((k, batchID), delta)
        store.merge(pair).map(res => MergeData(oldTuples, ts, k, delta, res))
      }.toList
    }
    onCollector { _.ack(tuple) }
  }

  override def cleanup { Await.result(store.close) }
}
