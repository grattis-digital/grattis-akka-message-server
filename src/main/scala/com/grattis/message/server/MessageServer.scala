package com.grattis.message.server

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Directives._
import com.typesafe.scalalogging.LazyLogging
import akka.actor.Actor
import akka.http.scaladsl.Http
import scala.util.Success
import scala.util.Failure
import scala.concurrent.duration._
import scala.util.control.NonFatal
import akka.stream.scaladsl.Source
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.MergeHub
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.BroadcastHub
import java.time.Instant
import scala.annotation.meta.param
import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import akka.http.scaladsl.server.Route
import com.grattis.message.server.{SourceRegistryActor, RegisteredSource, RegisterSource}
import scala.io.StdIn


object MessageServer {

    def route()(implicit system: ActorSystem, timeout: Timeout): Route = {
        
        val sourceRegistryActor = system.actorOf(Props(new SourceRegistryActor()), "sourceRegistryActor")
        path("channels" / Segment) { (segment) =>
            onSuccess(sourceRegistryActor ? RegisterSource(segment.toString())) {
                case registered: RegisteredSource =>
                    val requestHandler: Flow[Message, Message, _] = Flow[Message].map {
                        case TextMessage.Strict(txt) => 
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

        implicit val system = ActorSystem("akka-system")
        implicit val timeout: Timeout = Timeout(5.seconds)
        implicit val materializer: ActorMaterializer = ActorMaterializer()        
        import system.dispatcher
        
        Http().bindAndHandle(route(), "localhost", 8080).onComplete {
            case Success(binding) =>    
                sys.addShutdownHook {
                    println("Stopping message server ...")
                    try {
                        binding.terminate(10.seconds)
                        println("service stopped")
                    } catch {
                    case NonFatal(ex) =>
                        println("could not stop service")
                    }
                }
            case Failure(ex) => 
                println("Server failed to start")
        
        }
        StdIn.readLine()
    }

}
