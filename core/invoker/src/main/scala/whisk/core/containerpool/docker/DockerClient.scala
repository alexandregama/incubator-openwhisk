/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.containerpool.docker

import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Paths

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.event.Logging.ErrorLevel
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionId

/**
 * Serves as interface to the docker CLI tool.
 *
 * Be cautious with the ExecutionContext passed to this, as the
 * calls to the CLI are blocking.
 *
 * You only need one instance (and you shouldn't get more).
 */
class DockerClient(dockerHost: Option[String] = None)(executionContext: ExecutionContext)(implicit log: Logging)
    extends DockerApi with ProcessRunner {
    implicit private val ec = executionContext

    // Determines how to run docker. Failure to find a Docker binary implies
    // a failure to initialize this instance of DockerClient.
    protected val dockerCmd: Seq[String] = {
        val alternatives = List("/usr/bin/docker", "/usr/local/bin/docker")

        val dockerBin = Try {
            alternatives.find(a => Files.isExecutable(Paths.get(a))).get
        } getOrElse {
            throw new FileNotFoundException(s"Couldn't locate docker binary (tried: ${alternatives.mkString(", ")}).")
        }

        val host = dockerHost.map(host => Seq("--host", s"tcp://$host")).getOrElse(Seq.empty[String])
        Seq(dockerBin) ++ host
    }

    def run(image: String, args: Seq[String] = Seq.empty[String])(implicit transid: TransactionId): Future[ContainerId] =
        runCmd((Seq("run", "-d") ++ args ++ Seq(image)): _*).map(ContainerId.apply)

    def inspectIPAddress(id: ContainerId, network: String)(implicit transid: TransactionId): Future[ContainerIp] =
        runCmd("inspect", "--format", s"{{.NetworkSettings.Networks.${network}.IPAddress}}", id.asString).flatMap {
            _ match {
                case "<no value>" => Future.failed(new NoSuchElementException)
                case stdout       => Future.successful(ContainerIp(stdout))
            }
        }

    def pause(id: ContainerId)(implicit transid: TransactionId): Future[Unit] =
        runCmd("pause", id.asString).map(_ => ())

    def unpause(id: ContainerId)(implicit transid: TransactionId): Future[Unit] =
        runCmd("unpause", id.asString).map(_ => ())

    def rm(id: ContainerId)(implicit transid: TransactionId): Future[Unit] =
        runCmd("rm", "-f", id.asString).map(_ => ())

    def ps(filters: Seq[(String, String)] = Seq(), all: Boolean = false)(implicit transid: TransactionId): Future[Seq[ContainerId]] = {
        val filterArgs = filters.map { case (attr, value) => Seq("--filter", s"$attr=$value") }.flatten
        val allArg = if (all) Seq("--all") else Seq.empty[String]
        val cmd = Seq("ps", "--quiet", "--no-trunc") ++ allArg ++ filterArgs
        runCmd(cmd: _*).map(_.lines.toSeq.map(ContainerId.apply))
    }

    def pull(image: String)(implicit transid: TransactionId): Future[Unit] =
        runCmd("pull", image).map(_ => ())

    private def runCmd(args: String*)(implicit transid: TransactionId): Future[String] = {
        val cmd = dockerCmd ++ args
        val start = transid.started(this, LoggingMarkers.INVOKER_DOCKER_CMD(args.head), s"running ${cmd.mkString(" ")}")
        executeProcess(cmd: _*).andThen {
            case Success(_) => transid.finished(this, start)
            case Failure(t) => transid.failed(this, start, t.getMessage, ErrorLevel)
        }
    }
}

case class ContainerId(val asString: String) {
    require(asString.nonEmpty, "ContainerId must not be empty")
}
case class ContainerIp(val asString: String) {
    require(asString.nonEmpty, "ContainerIp must not be empty")
}

trait DockerApi {
    /**
     * Spawns a container in detached mode.
     *
     * @param image the image to start the container with
     * @param args arguments for the docker run command
     * @return id of the started container
     */
    def run(image: String, args: Seq[String] = Seq.empty[String])(implicit transid: TransactionId): Future[ContainerId]

    /**
     * Gets the IP address of a given container.
     *
     * A container may have more than one network. The container has an
     * IP address in each of these networks such that the network name
     * is needed.
     *
     * @param id the id of the container to get the IP address from
     * @param network name of the network to get the IP address from
     * @return ip of the container
     */
    def inspectIPAddress(id: ContainerId, network: String)(implicit transid: TransactionId): Future[ContainerIp]

    /**
     * Pauses the container with the given id.
     *
     * @param id the id of the container to pause
     * @return a Future completing according to the command's exit-code
     */
    def pause(id: ContainerId)(implicit transid: TransactionId): Future[Unit]

    /**
     * Unpauses the container with the given id.
     *
     * @param id the id of the container to unpause
     * @return a Future completing according to the command's exit-code
     */
    def unpause(id: ContainerId)(implicit transid: TransactionId): Future[Unit]

    /**
     * Removes the container with the given id.
     *
     * @param id the id of the container to remove
     * @return a Future completing according to the command's exit-code
     */
    def rm(id: ContainerId)(implicit transid: TransactionId): Future[Unit]

    /**
     * Returns a list of ContainerIds in the system.
     *
     * @param filters Filters to apply to the 'ps' command
     * @param all Whether or not to return stopped containers as well
     * @return A list of ContainerIds
     */
    def ps(filters: Seq[(String, String)] = Seq(), all: Boolean = false)(implicit transid: TransactionId): Future[Seq[ContainerId]]

    /**
     * Pulls the given image.
     *
     * @param image the image to pull
     * @return a Future completing once the pull is complete
     */
    def pull(image: String)(implicit transid: TransactionId): Future[Unit]
}
