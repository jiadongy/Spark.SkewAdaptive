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

package org.apache.spark.scheduler.cluster

import java.nio.ByteBuffer

import org.apache.spark.TaskState.TaskState
import org.apache.spark.rpc.RpcEndpointRef
import org.apache.spark.storage.{BlockId, BlockManagerId, SkewTuneBlockInfo}
import org.apache.spark.util.{SerializableBuffer, Utils}

private[spark] sealed trait CoarseGrainedClusterMessage extends Serializable

private[spark] object CoarseGrainedClusterMessages {

  case object RetrieveSparkProps extends CoarseGrainedClusterMessage

  // Driver to executors
  case class LaunchTask(data: SerializableBuffer) extends CoarseGrainedClusterMessage

  case class KillTask(taskId: Long, executor: String, interruptThread: Boolean)
    extends CoarseGrainedClusterMessage

  //8.24 SkewTune NewAdd Driver到Executor的消息
  case class RemoveFetchCommand(nextExecutorId: String, nextTaskId: Long, taskId: Long, allBlocks: Seq[(BlockManagerId, Seq[BlockId])])
    extends CoarseGrainedClusterMessage

  case class AddFetchCommand(taskId: Long, allBlocks: Seq[(BlockManagerId, Seq[(BlockId, Long)])])
    extends CoarseGrainedClusterMessage

  case class RemoveAndAddResultCommand(allBlockIds: Seq[BlockId], fromTaskId: Long, toTaskId: Long)
    extends CoarseGrainedClusterMessage

  //End
  //8.24 SkewTune NewAdd Executor到Driver的消息
  case class TransferRemovedFetch(nextExecutorId: String, nextTaskId: Long, returnSeq: Seq[(BlockManagerId, Seq[(BlockId, Long)])])
    extends CoarseGrainedClusterMessage

  /*  object TransferRemovedFetch {
      def apply(nextExecutorId: String, nextTaskId: Long, returnSeq: Seq[(BlockManagerId, Seq[(BlockId, Long)])]): TransferRemovedFetch = {
        new TransferRemovedFetch(nextExecutorId, nextTaskId, returnSeq)
      }
    }*/
  case class ReportBlockStatuses(taskID: Long, seq: Seq[(BlockId, Byte)], newTaskId: Option[Long])
    extends CoarseGrainedClusterMessage

  /*  object ReportBlockStatuses {
      def apply(taskID: Long, seq: Seq[(BlockId, Byte)], newTaskId: Option[Long]): ReportBlockStatuses = {
        new ReportBlockStatuses(taskID, seq, newTaskId)
      }
    }*/

  case class RegisterNewTask(taskID: Long, executorId: String, seq: Seq[SkewTuneBlockInfo])
    extends CoarseGrainedClusterMessage

  /*object RegisterNewTask {
    def apply(taskID: Long, executorId: String, seq: Seq[SkewTuneBlockInfo]): RegisterNewTask = {
      new RegisterNewTask(taskID, executorId, seq)
    }
  }*/

  case class ReportTaskFinished(taskID: Long) extends CoarseGrainedClusterMessage

  /*object ReportTaskFinished {
    def apply(taskID: Long): ReportTaskFinished = {
      new ReportTaskFinished(taskID)
    }
  }*/

  //End

  case object RegisteredExecutor extends CoarseGrainedClusterMessage

  case class RegisterExecutorFailed(message: String) extends CoarseGrainedClusterMessage

  // Executors to driver
  case class RegisterExecutor(
                               executorId: String,
                               executorRef: RpcEndpointRef,
                               hostPort: String,
                               cores: Int,
                               logUrls: Map[String, String])
    extends CoarseGrainedClusterMessage {
    Utils.checkHostPort(hostPort, "Expected host port")
  }

  case class StatusUpdate(executorId: String, taskId: Long, state: TaskState,
                          data: SerializableBuffer) extends CoarseGrainedClusterMessage

  object StatusUpdate {
    /** Alternate factory method that takes a ByteBuffer directly for the data field */
    def apply(executorId: String, taskId: Long, state: TaskState, data: ByteBuffer)
    : StatusUpdate = {
      StatusUpdate(executorId, taskId, state, new SerializableBuffer(data))
    }
  }

  // Internal messages in driver
  case object ReviveOffers extends CoarseGrainedClusterMessage

  case object StopDriver extends CoarseGrainedClusterMessage

  case object StopExecutor extends CoarseGrainedClusterMessage

  case object StopExecutors extends CoarseGrainedClusterMessage

  case class RemoveExecutor(executorId: String, reason: String) extends CoarseGrainedClusterMessage

  case class SetupDriver(driver: RpcEndpointRef) extends CoarseGrainedClusterMessage

  // Exchanged between the driver and the AM in Yarn client mode
  case class AddWebUIFilter(
                             filterName: String, filterParams: Map[String, String], proxyBase: String)
    extends CoarseGrainedClusterMessage

  // Messages exchanged between the driver and the cluster manager for executor allocation
  // In Yarn mode, these are exchanged between the driver and the AM

  case class RegisterClusterManager(am: RpcEndpointRef) extends CoarseGrainedClusterMessage

  // Request executors by specifying the new total number of executors desired
  // This includes executors already pending or running
  case class RequestExecutors(requestedTotal: Int) extends CoarseGrainedClusterMessage

  case class KillExecutors(executorIds: Seq[String]) extends CoarseGrainedClusterMessage

}
