package com.grattis.message.server

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.Http

import scala.util.Success
import scala.util.Failure
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.Flow
import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import akka.http.scaladsl.server.Route
import com.typesafe.scalalogging.LazyLogging

import scala.io.StdIn


object MessageServer extends LazyLogging {

    def route()(implicit system: ActorSystem, timeout: Timeout): Route = {
        
        val sourceRegistryActor = system.actorOf(Props(new SourceRegistryActor()), "sourceRegistryActor")
        path("channels" / Segment) { (segment) =>
            onSuccess(sourceRegistryActor ? RegisterSource(segment)) {
                case registered: RegisteredSource =>
                    val requestHandler: Flow[Message, Message, _] = Flow[Message].map {
                        case TextMessage.Strict(txt) =>
                            logger.info(s"incoming message: $txt")
                            registered.queue.queue.offer(txt)
                            TextMessage(s"Message processed: $txt")
                        case _ => 
                            TextMessage("Message type unsupported")
                    } 
                    handleWebSocketMessages(requestHandler.merge(registered.queue.source))
                case other =>
                    complete("could not register source")
            }
        }
    }

    def main(args: Array[String]): Unit = {

        implicit val system: ActorSystem = ActorSystem("akka-system")
        implicit val timeout: Timeout = Timeout(5.seconds)
        import system.dispatcher

        Http().newServerAt("localhost", 8080).bind(route()).onComplete {
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
            case Failure(ex) =>
                logger.error("Server failed to start", ex)
        }
        StdIn.readLine()
    }
}
