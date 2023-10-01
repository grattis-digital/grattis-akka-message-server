package com.grattis.message.server

import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.AskPattern.*
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.typed.scaladsl.{ActorSink, ActorSource}
import akka.stream.*
import akka.util.Timeout
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
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
