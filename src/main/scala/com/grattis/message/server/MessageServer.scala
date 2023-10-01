package com.grattis.message.server

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration.*
import scala.io.StdIn
import scala.util.{Failure, Success}
import scala.util.control.NonFatal
import com.grattis.message.server.routes.RouteProvider


object MessageServer extends LazyLogging {

  def main(args: Array[String]): Unit = {

    implicit val system: ActorSystem[ChannelRegistryActor.ChannelRegistryActorCommand] = ActorSystem(ChannelRegistryActor(), "akka-system")
    import system.executionContext
    implicit val timeout: Timeout = Timeout(600.seconds)

    val routeProvider = new RouteProvider()

    Http().newServerAt("localhost", 8080).bind(routeProvider.websocketRoute()).onComplete {
      case Success(binding) =>
        sys.addShutdownHook {
          logger.info("Stopping message server ...")
          try {
            binding.terminate(10.seconds)
            logger.info("service stopped")
          } catch {
            case NonFatal(ex) =>
              logger.info("could not stop service", ex)
          }
        }
        logger.info(s"Server started: ${binding.localAddress}")
      case Failure(ex) =>
        logger.error("Server failed to start", ex)
    }
    StdIn.readLine()
  }
}
