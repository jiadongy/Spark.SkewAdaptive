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

package org.apache.spark.executor

import java.net.URL
import java.nio.ByteBuffer

import org.apache.spark.TaskState.TaskState
import org.apache.spark._
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.deploy.worker.WorkerWatcher
import org.apache.spark.rpc._
import org.apache.spark.scheduler.TaskDescription
import org.apache.spark.scheduler.cluster.CoarseGrainedClusterMessages._
import org.apache.spark.serializer.SerializerInstance
import org.apache.spark.storage._
import org.apache.spark.util.{SignalLogger, ThreadUtils, Utils}

import scala.collection.mutable
import scala.util.{Failure, Success}

private[spark] class CoarseGrainedExecutorBackend(
                                                   override val rpcEnv: RpcEnv,
                                                   driverUrl: String,
                                                   executorId: String,
                                                   hostPort: String,
                                                   cores: Int,
                                                   userClassPath: Seq[URL],
                                                   env: SparkEnv)
  extends ThreadSafeRpcEndpoint with ExecutorBackend with SkewTuneBackend with Logging {

  Utils.checkHostPort(hostPort, "Expected hostport")

  var executor: Executor = null
  @volatile var driver: Option[RpcEndpointRef] = None

  // If this CoarseGrainedExecutorBackend is changed to support multiple threads, then this may need
  // to be changed so that we don't share the serializer instance across threads
  private[this] val ser: SerializerInstance = env.closureSerializer.newInstance()

  override def onStart() {
    logInfo("Connecting to driver: " + driverUrl)
    rpcEnv.asyncSetupEndpointRefByURI(driverUrl).flatMap { ref =>
      // This is a very fast action so we can use "ThreadUtils.sameThread"
      driver = Some(ref)
      ref.ask[RegisteredExecutor.type](
        RegisterExecutor(executorId, self, hostPort, cores, extractLogUrls))
    }(ThreadUtils.sameThread).onComplete {
      // This is a very fast action so we can use "ThreadUtils.sameThread"
      case Success(msg) => Utils.tryLogNonFatalError {
        Option(self).foreach(_.send(msg)) // msg must be RegisteredExecutor
      }
      case Failure(e) => logError(s"Cannot register with driver: $driverUrl", e)
    }(ThreadUtils.sameThread)
  }

  def extractLogUrls: Map[String, String] = {
    val prefix = "SPARK_LOG_URL_"
    sys.env.filterKeys(_.startsWith(prefix))
      .map(e => (e._1.substring(prefix.length).toLowerCase, e._2))
  }

  override def receive: PartialFunction[Any, Unit] = {
    case RegisteredExecutor =>
      logInfo("Successfully registered with driver")
      val (hostname, _) = Utils.parseHostPort(hostPort)
      executor = new Executor(executorId, hostname, env, userClassPath, isLocal = false)

    case RegisterExecutorFailed(message) =>
      logError("Slave registration failed: " + message)
      System.exit(1)

    case LaunchTask(data) =>
      if (executor == null) {
        logError("Received LaunchTask command but executor was null")
        System.exit(1)
      } else {
        val taskDesc = ser.deserialize[TaskDescription](data.value)
        logInfo("Got assigned task " + taskDesc.taskId)
        executor.launchTask(this, taskId = taskDesc.taskId, attemptNumber = taskDesc.attemptNumber,
          taskDesc.name, taskDesc.serializedTask)
      }

    case KillTask(taskId, _, interruptThread) =>
      if (executor == null) {
        logError("Received KillTask command but executor was null")
        System.exit(1)
      } else {
        executor.killTask(taskId, interruptThread)
      }

    case StopExecutor =>
      logInfo("Driver commanded a shutdown")
      executor.stop()
      stop()
      rpcEnv.shutdown()

    //8.24 SkewTuneAdd : Master向Executor发送Message
    case RemoveFetchCommand(nextExecutorId, nextTaskId, taskId, allBlocks) =>
      logInfo("Driver commanded a removeFetch")
      val worker = executor.skewTuneWorkerByTaskId.get(taskId)
      if (worker.nonEmpty) {
        val returnSeq = worker.get.fetchIterator.removeFetchRequests(allBlocks)
        if (returnSeq.nonEmpty) {
          transferRemovedFetch(nextExecutorId, nextTaskId, returnSeq)
        }
      } else
        logWarning(s"Task $taskId not exists in Executor $executorId")

    case AddFetchCommand(taskId, allBlocks) =>
      logInfo("Driver commanded a addFetch")
      val worker = executor.skewTuneWorkerByTaskId.get(taskId)
      if (worker.nonEmpty) {
        worker.get.fetchIterator.addFetchRequests(allBlocks)
        logInfo(s"addFetch to task $taskId on executor $executorId .Success :　$allBlocks")
      } else
        logWarning(s"Task $taskId not exists in Executor $executorId")

    case RemoveAndAddResultCommand(allBlockIds, fromTaskId, toTaskId) =>
      logInfo("Driver commanded a removeAndAddResult")
      val workerFrom = executor.skewTuneWorkerByTaskId.get(fromTaskId)
      val workerTo = executor.skewTuneWorkerByTaskId.get(toTaskId)
      if (workerFrom.nonEmpty && workerTo.nonEmpty) {
        val returnResults = workerFrom.get.fetchIterator.removeFetchResults(allBlockIds)
        if (returnResults.nonEmpty) {
          workerTo.get.fetchIterator.addFetchResults(returnResults)
          logInfo(s"transfer Result from task $fromTaskId to task $toTaskId on executor $executorId RemoveAndAddResult :　$returnResults")
        } else
          logInfo(s"transfer Result not exist .from task $fromTaskId to task $toTaskId on executor $executorId RemoveAndAddResult :　$returnResults")
      } else
        logWarning(s"Task $fromTaskId or Task $toTaskId not exists in Executor $executorId")

    case LockTask(taskId) =>
      logInfo(s"Driver commanded a LockTask $taskId")
      val worker = executor.skewTuneWorkerByTaskId.get(taskId)
      if(worker.isDefined && worker.get.fetchIterator != null && !worker.get.fetchIterator.isLocked){
        worker.get.fetchIterator.isLocked = true
        if(executor.taskLockStatus.isDefinedAt(taskId) && !executor.taskLockStatus(taskId))
          executor.taskLockStatus.update(taskId, true)
      }

    case UnlockTask(taskId) =>
      logInfo(s"Driver commanded a UnLockTask $taskId")
      val worker = executor.skewTuneWorkerByTaskId.get(taskId)
      if(worker.isDefined && worker.get.fetchIterator != null && worker.get.fetchIterator.isLocked){
        worker.get.fetchIterator.synchronized {
          worker.get.fetchIterator.isLocked = false
          if(executor.taskLockStatus.isDefinedAt(taskId))
            executor.taskLockStatus.update(taskId, false)
          logInfo(s"ExecutorBackend commanded a UnLockTask $taskId to notify")
          worker.get.fetchIterator.notifyAll()
        }
      }
  }

  //8.24 SkewTuneAdd Executor向Master报告
  def transferRemovedFetch(nextExecutorId: String, nextTaskId: Long, returnSeq: Seq[(BlockManagerId, Seq[(BlockId, Long)])]): Unit = {
    val msg = TransferRemovedFetch(nextExecutorId, nextTaskId, returnSeq)
    logInfo(s"Executor $executorId send command removeAndAddResult $msg")
    driver match {
      case Some(driverRef) => driverRef.send(msg)
      case None => logWarning(s"Drop $msg because has not yet connected to driver")
    }
  }

  override def reportBlockStatuses(taskID: Long, seq: Seq[(BlockId, Byte)], newTaskId: Option[Long],size: Option[Long]): Unit = {
    val msg = ReportBlockStatuses(taskID, seq, newTaskId,size)
    //logInfo(s"Executor $executorId send command reportBlockStatuses $msg")
    driver match {
      case Some(driverRef) => driverRef.send(msg)
      case None => logWarning(s"Drop $msg because has not yet connected to driver")
    }
  }

  override def registerNewTask(taskID: Long, executorId: String, seq: Seq[SkewTuneBlockInfo]): Unit = {
    val msg = RegisterNewTask(taskID, executorId, seq)
    //logInfo(s"Executor $executorId send command registerNewTask $msg")
    driver match {
      case Some(driverRef) => driverRef.send(msg)
      case None => logWarning(s"Drop $msg because has not yet connected to driver")
    }
  }

  override def reportTaskFinished(taskID: Long): Unit = {
    val msg = ReportTaskFinished(taskID)
    //logInfo(s"Executor $executorId send command reportTaskFinished $msg")
    driver match {
      case Some(driverRef) => driverRef.send(msg)
      case None => logWarning(s"Drop $msg because has not yet connected to driver")
    }
  }

  //9.6 SkewTuneAdd
  override def reportTaskComputeSpeed(taskId: Long, executorId: String, speed: Float): Unit = {
    val msg = ReportTaskComputeSpeed(taskId, executorId, speed)
    //logInfo(s"Executor $executorId send command reportTaskFinished $msg")
    driver match {
      case Some(driverRef) => driverRef.send(msg)
      case None => logWarning(s"Drop $msg because has not yet connected to driver")
    }
  }

  override def reportBlockDownloadSpeed(fromExecutor: String, toExecutor: String, speed: Float): Unit = {
    val msg = ReportBlockDownloadSpeed(fromExecutor, toExecutor, speed)
    //logInfo(s"Executor $executorId send command reportTaskFinished $msg")
    driver match {
      case Some(driverRef) => driverRef.send(msg)
      case None => logWarning(s"Drop $msg because has not yet connected to driver")
    }
  }

  //End

  override def onDisconnected(remoteAddress: RpcAddress): Unit = {
    if (driver.exists(_.address == remoteAddress)) {
      logError(s"Driver $remoteAddress disassociated! Shutting down.")
      System.exit(1)
    } else {
      logWarning(s"An unknown ($remoteAddress) driver disconnected.")
    }
  }

  override def statusUpdate(taskId: Long, state: TaskState, data: ByteBuffer) {
    val msg = StatusUpdate(executorId, taskId, state, data)
    driver match {
      case Some(driverRef) => driverRef.send(msg)
      case None => logWarning(s"Drop $msg because has not yet connected to driver")
    }
  }

}

private[spark] object CoarseGrainedExecutorBackend extends Logging {

  private def run(
                   driverUrl: String,
                   executorId: String,
                   hostname: String,
                   cores: Int,
                   appId: String,
                   workerUrl: Option[String],
                   userClassPath: Seq[URL]) {

    SignalLogger.register(log)

    SparkHadoopUtil.get.runAsSparkUser { () =>
      // Debug code
      Utils.checkHost(hostname)

      // Bootstrap to fetch the driver's Spark properties.
      val executorConf = new SparkConf
      val port = executorConf.getInt("spark.executor.port", 0)
      val fetcher = RpcEnv.create(
        "driverPropsFetcher",
        hostname,
        port,
        executorConf,
        new SecurityManager(executorConf))
      val driver = fetcher.setupEndpointRefByURI(driverUrl)
      val props = driver.askWithRetry[Seq[(String, String)]](RetrieveSparkProps) ++
        Seq[(String, String)](("spark.app.id", appId))
      fetcher.shutdown()

      // Create SparkEnv using properties we fetched from the driver.
      val driverConf = new SparkConf()
      for ((key, value) <- props) {
        // this is required for SSL in standalone mode
        if (SparkConf.isExecutorStartupConf(key)) {
          driverConf.setIfMissing(key, value)
        } else {
          driverConf.set(key, value)
        }
      }
      if (driverConf.contains("spark.yarn.credentials.file")) {
        logInfo("Will periodically update credentials from: " +
          driverConf.get("spark.yarn.credentials.file"))
        SparkHadoopUtil.get.startExecutorDelegationTokenRenewer(driverConf)
      }

      val env = SparkEnv.createExecutorEnv(
        driverConf, executorId, hostname, port, cores, isLocal = false)

      // SparkEnv sets spark.driver.port so it shouldn't be 0 anymore.
      val boundPort = env.conf.getInt("spark.executor.port", 0)
      assert(boundPort != 0)

      // Start the CoarseGrainedExecutorBackend endpoint.
      val sparkHostPort = hostname + ":" + boundPort
      env.rpcEnv.setupEndpoint("Executor", new CoarseGrainedExecutorBackend(
        env.rpcEnv, driverUrl, executorId, sparkHostPort, cores, userClassPath, env))
      workerUrl.foreach { url =>
        env.rpcEnv.setupEndpoint("WorkerWatcher", new WorkerWatcher(env.rpcEnv, url))
      }
      env.rpcEnv.awaitTermination()
      SparkHadoopUtil.get.stopExecutorDelegationTokenRenewer()
    }
  }

  def main(args: Array[String]) {
    var driverUrl: String = null
    var executorId: String = null
    var hostname: String = null
    var cores: Int = 0
    var appId: String = null
    var workerUrl: Option[String] = None
    val userClassPath = new mutable.ListBuffer[URL]()

    var argv = args.toList
    while (!argv.isEmpty) {
      argv match {
        case ("--driver-url") :: value :: tail =>
          driverUrl = value
          argv = tail
        case ("--executor-id") :: value :: tail =>
          executorId = value
          argv = tail
        case ("--hostname") :: value :: tail =>
          hostname = value
          argv = tail
        case ("--cores") :: value :: tail =>
          cores = value.toInt
          argv = tail
        case ("--app-id") :: value :: tail =>
          appId = value
          argv = tail
        case ("--worker-url") :: value :: tail =>
          // Worker url is used in spark standalone mode to enforce fate-sharing with worker
          workerUrl = Some(value)
          argv = tail
        case ("--user-class-path") :: value :: tail =>
          userClassPath += new URL(value)
          argv = tail
        case Nil =>
        case tail =>
          System.err.println(s"Unrecognized options: ${tail.mkString(" ")}")
          printUsageAndExit()
      }
    }

    if (driverUrl == null || executorId == null || hostname == null || cores <= 0 ||
      appId == null) {
      printUsageAndExit()
    }

    run(driverUrl, executorId, hostname, cores, appId, workerUrl, userClassPath)
  }

  private def printUsageAndExit() = {
    System.err.println(
      """
        |"Usage: CoarseGrainedExecutorBackend [options]
        |
        | Options are:
        |   --driver-url <driverUrl>
        |   --executor-id <executorId>
        |   --hostname <hostname>
        |   --cores <cores>
        |   --app-id <appid>
        |   --worker-url <workerUrl>
        |   --user-class-path <url>
        | """.stripMargin)
    System.exit(1)
  }

}
