package com.grattis.message.server

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest, WebSocketUpgradeResponse}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import com.grattis.message.server.routes.RouteProvider
import com.grattis.message.server.utils.GrattisSpec

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}
class MessageServerIntegrationSpec extends GrattisSpec {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(30.seconds, 100.millis)
  implicit val timeout: Timeout = Timeout(10.seconds)

  private val Host = "localhost"
  private val Port = 9763

  implicit val system: ActorSystem[ChannelRegistryActor.ChannelRegistryActorCommand] = ActorSystem(ChannelRegistryActor(), "akka-system")
  import system.executionContext

  private val NrOfConnections = 9

  override def beforeAll(): Unit = {
    val routeProvider = new RouteProvider()
    Http().newServerAt(Host, Port).bind(routeProvider.websocketRoute())
  }

  "Grattis Akka Message server" should {
    "support parallel websocket connections" in {

      val exampleString = s"test - " + new java.util.Date().toString

      def connectionFlow(nr: Int): Flow[Message, Message, Future[WebSocketUpgradeResponse]] =
        Http().webSocketClientFlow(WebSocketRequest(s"ws://localhost:$Port/channels/testChannel/users/user$nr"))

      // Create shared Source.queue
      val (queue, sharedSource) = Source
        .queue[TextMessage.Strict](1000, akka.stream.OverflowStrategy.fail)
        .preMaterialize()

      val parallelConnections: Seq[Future[Seq[Message]]] = (1 to NrOfConnections).map { nr =>
        val incoming = Sink.seq[Message]
        val (upgradeResponse, response: Future[Seq[Message]]) =
          sharedSource.viaMat(connectionFlow(nr).take(NrOfConnections))(Keep.right).toMat(incoming)(Keep.both).run()
        response
      }

      queue.offer(TextMessage(exampleString))

      val futureResults = Future.sequence(parallelConnections)
      val results: Seq[Seq[Message]] = Await.result(futureResults, 100.seconds)
      results.size shouldBe NrOfConnections
      results.foreach { elList =>
        elList.size shouldBe NrOfConnections
        println("-------------------")
        elList.map(_.asTextMessage.getStrictText).sorted.foreach { el =>
          println(el)
        }
        println("-------------------")
      }
    }
  }
}