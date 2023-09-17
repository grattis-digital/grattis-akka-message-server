package com.grattis.message.server

import akka.{Done, NotUsed}
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import akka.stream.scaladsl.*
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.ws.*
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration.*


class MessageServerIntSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with ScalaFutures {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(60.seconds, 100.millis)
  implicit val timeout: Timeout = Timeout(5.seconds)

  private val Host = "localhost"
  private val Port = 8345

  implicit val system: ActorSystem[RegisterChannel] = ActorSystem(ChannelRegistryActor(), "akka-system")

  import system.executionContext

  override def beforeAll(): Unit = {
    Http().newServerAt(Host, Port).bind(MessageServer.route())
  }

  override def afterAll(): Unit = {
    system.terminate()
  }

  "MessageServer(...) - /channels/<channelname>" should {
    "register a message channel" in {

      val receiveMessagesOne = Sink.seq[Message]
      val receiveMessagesTwo = Sink.seq[Message]

      val webSocketFlowOne: Flow[Message, Message, Future[WebSocketUpgradeResponse]] = Http().webSocketClientFlow(WebSocketRequest(s"ws://$Host:$Port/channels/test/users/user1"))
      val webSocketFlowTwo: Flow[Message, Message, Future[WebSocketUpgradeResponse]] = Http().webSocketClientFlow(WebSocketRequest(s"ws://$Host:$Port/channels/test/users/user2"))

      val twoSource = Source.maybe[TextMessage]
      val oneSource = Source.maybe[TextMessage]

      val ((sourceTwo, connectionTwo), resultTwo) =
        twoSource
          .viaMat(webSocketFlowTwo)(Keep.both)
          .recoverWithRetries(-1, { case _: Exception => Source.empty })
          .toMat(receiveMessagesTwo)(Keep.both)
          .run()

      val ((sourceOne, connectionOne), resultOne) =
        oneSource
          .viaMat(webSocketFlowOne)(Keep.both)
          .recoverWithRetries(-1, { case _: Exception => Source.empty })
          .toMat(receiveMessagesOne)(Keep.both)
          .run()

      val connected = connectionOne.map {
        case upgrade if upgrade.response.status == StatusCodes.SwitchingProtocols => Done
        case upgrade => throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
      }

      val connectedTwo = connectionTwo.map {
        case upgrade if upgrade.response.status == StatusCodes.SwitchingProtocols => Done
        case upgrade => throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
      }

      val res = for {
        _ <- connected
        _ <- connectedTwo
        _ <- Future(sourceOne.success(Some(TextMessage("Hello World!"))))
        _ <- Future(sourceTwo.success(None))
        results <- Future.sequence(Seq(resultOne, resultTwo)).map(_.flatten)
      } yield results

      whenReady(res) { results =>
        val messages = results.map(_.asTextMessage.getStrictText)
        println(messages)
        messages.filter(_ == "Hello World!").size shouldBe 2
        messages.filter(_ == "Message processed: Hello World!").size shouldBe 1
      }

    }
  }


}
