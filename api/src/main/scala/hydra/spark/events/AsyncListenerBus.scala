/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hydra.spark.events

import java.util.concurrent._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import com.typesafe.config.Config
import configs.syntax._
import hydra.spark.api.HydraException
import org.apache.spark.SparkContext

import scala.util.DynamicVariable

/**
  * Asynchronously passes HydraListenerEvents to registered HydraListeners.
  *
  * Until `start()` is called, all posted events are only buffered. Only after this listener bus
  * has started will events be actually propagated to all attached listeners. This listener bus
  * is stopped when `stop()` is called, and it will drop further events after stopping.
  */
private[hydra] class AsyncListenerBus(val config: Config) extends HydraListenerBus {

  self =>

  import AsyncListenerBus._

  private var sparkContext: SparkContext = _

  // Cap the capacity of the event queue so we get an explicit error (rather than
  // an OOM exception) if it's perpetually being added to more quickly than it's being drained.
  private lazy val EVENT_QUEUE_CAPACITY = validateAndGetQueueSize()
  private lazy val eventQueue = new LinkedBlockingQueue[HydraListenerEvent](EVENT_QUEUE_CAPACITY)

  private def validateAndGetQueueSize(): Int = {
    val queueSize = config.get[Int](LISTENER_BUS_EVENT_QUEUE_SIZE).valueOrElse(1000)
    if (queueSize <= 0) {
      throw new HydraException(s"$LISTENER_BUS_EVENT_QUEUE_SIZE must be > 0!")
    }
    queueSize
  }

  private val started = new AtomicBoolean(false)
  private val stopped = new AtomicBoolean(false)

  private val droppedEventsCounter = new AtomicLong(0L)

  @volatile private var lastReportTimestamp = 0L

  // Indicate if we are processing some event
  // Guarded by `self`
  private var processingEvent = false

  private val logDroppedEvent = new AtomicBoolean(false)

  // A counter that represents the number of events produced and consumed in the queue
  private val eventLock = new Semaphore(0)

  private val listenerThread = new Thread(name) {
    setDaemon(true)

    override def run(): Unit =
      AsyncListenerBus.withinListenerThread.withValue(true) {
        while (true) {
          eventLock.acquire()
          self.synchronized {
            processingEvent = true
          }
          try {
            val event = eventQueue.poll
            if (event == null) {
              // Get out of the while loop and shutdown the daemon thread
              if (!stopped.get) {
                throw new IllegalStateException("Polling `null` from eventQueue means" +
                  " the listener bus has been stopped. So `stopped` must be true")
              }
              return
            }
            postToAll(event)
          } finally {
            self.synchronized {
              processingEvent = false
            }
          }
        }
      }
  }

  /**
    * Start sending events to attached listeners.
    *
    * This first sends out all buffered events posted before this listener bus has started, then
    * listens for any additional events asynchronously while the listener bus is still running.
    * This should only be called once.
    *
    */
  def start(sparkContext: SparkContext): Unit = {
    if (started.compareAndSet(false, true)) {
      this.sparkContext = sparkContext
      listenerThread.start()
    } else {
      throw new IllegalStateException(s"$name already started!")
    }
  }

  def post(event: HydraListenerEvent): Unit = {
    if (stopped.get) {
      // Drop further events to make `listenerThread` exit ASAP
      log.error(s"$name has already stopped! Dropping event $event")
      return
    }
    val eventAdded = eventQueue.offer(event)
    if (eventAdded) {
      eventLock.release()
    } else {
      onDropEvent(event)
      droppedEventsCounter.incrementAndGet()
    }

    val droppedEvents = droppedEventsCounter.get
    if (droppedEvents > 0) {
      // Don't log too frequently
      if (System.currentTimeMillis() - lastReportTimestamp >= 60 * 1000) {
        // There may be multiple threads trying to decrease droppedEventsCounter.
        // Use "compareAndSet" to make sure only one thread can win.
        // And if another thread is increasing droppedEventsCounter, "compareAndSet" will fail and
        // then that thread will update it.
        if (droppedEventsCounter.compareAndSet(droppedEvents, 0)) {
          val prevLastReportTimestamp = lastReportTimestamp
          lastReportTimestamp = System.currentTimeMillis()
          log.warn(s"Dropped $droppedEvents HydraListenerEvents since " +
            new java.util.Date(prevLastReportTimestamp))
        }
      }
    }
  }

  /**
    * For testing only. Wait until there are no more events in the queue, or until the specified
    * time has elapsed. Throw `TimeoutException` if the specified time elapsed before the queue
    * emptied.
    * Exposed for testing.
    */
  @throws(classOf[TimeoutException])
  def waitUntilEmpty(timeoutMillis: Long): Unit = {
    val finishTime = System.currentTimeMillis + timeoutMillis
    while (!queueIsEmpty) {
      if (System.currentTimeMillis > finishTime) {
        throw new TimeoutException(
          s"The event queue is not empty after $timeoutMillis milliseconds")
      }
      /* Sleep rather than using wait/notify, because this is used only for testing and
       * wait/notify add overhead in the general case. */
      Thread.sleep(10)
    }
  }

  /**
    * For testing only. Return whether the listener daemon thread is still alive.
    * Exposed for testing.
    */
  def listenerThreadIsAlive: Boolean = listenerThread.isAlive

  /**
    * Return whether the event queue is empty.
    *
    * The use of synchronized here guarantees that all events that once belonged to this queue
    * have already been processed by all attached listeners, if this returns true.
    */
  private def queueIsEmpty: Boolean = synchronized {
    eventQueue.isEmpty && !processingEvent
  }

  /**
    * Stop the listener bus. It will wait until the queued events have been processed, but drop the
    * new events after stopping.
    */
  def stop(): Unit = {
    if (!started.get()) {
      throw new IllegalStateException(s"Attempted to stop $name that has not yet started!")
    }
    if (stopped.compareAndSet(false, true)) {
      // Call eventLock.release() so that listenerThread will poll `null` from `eventQueue` and know
      // `stop` is called.
      eventLock.release()
      listenerThread.join()
    } else {
      // Keep quiet
    }
  }

  /**
    * If the event queue exceeds its capacity, the new events will be dropped. The subclasses will be
    * notified with the dropped events.
    *
    * Note: `onDropEvent` can be called in any thread.
    */
  def onDropEvent(event: HydraListenerEvent): Unit = {
    if (logDroppedEvent.compareAndSet(false, true)) {
      // Only log the following message once to avoid duplicated annoying logs.
      log.error("Dropping HydraListenerEvent because no remaining room in event queue. " +
        "This likely means one of the HydraListeners is too slow and cannot keep up with " +
        "the rate at which tasks are being started by the scheduler.")
    }
  }
}

private[spark] object AsyncListenerBus {
  // Allows for Context to check whether stop() call is made within listener thread
  val withinListenerThread: DynamicVariable[Boolean] = new DynamicVariable[Boolean](false)

  val LISTENER_BUS_EVENT_QUEUE_SIZE = "hydra.listenerbus.eventqueue.size"

  /** The thread name of Hydra listener bus */
  val name = "HydraListenerBus"
}
