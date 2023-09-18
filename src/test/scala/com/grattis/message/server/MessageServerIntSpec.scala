package com.grattis.message.server

import akka.{Done, NotUsed}
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.{Flow, Sink, Source}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import akka.stream.scaladsl.*
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.ws.*
import akka.util.Timeout
import akka.stream._

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.*


class MessageServerIntSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with ScalaFutures {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(30.seconds, 100.millis)
  implicit val timeout: Timeout = Timeout(5.seconds)

  private val Host = "localhost"
  private val Port = 8345

  implicit val system: ActorSystem[ChannelRegistryCommand] = ActorSystem(ChannelRegistryActor(), "akka-system")

  import system.executionContext

  override def beforeAll(): Unit = {
    Http().newServerAt(Host, Port).bind(MessageServer.route())
  }

  override def afterAll(): Unit = {
    system.terminate()
  }

  def messageSink(responsePromise: Promise[Done], duration: FiniteDuration): Sink[Message, Future[Done]] = {
    Sink.seq[Message].mapMaterializedValue((res: Future[Seq[Message]]) => {
      res.map(_ => Done)(system.executionContext)
    })
  }

  def webSocketFlow(url: String): Flow[Message, Message, Future[WebSocketUpgradeResponse]] = {
    Http().webSocketClientFlow(WebSocketRequest(url)).recoverWithRetries(-1, {
      case _ => Source.empty
    })
  }

  "MessageServer(...) - /channels/<channelname>" should {
    "register a message channel" in {

      val source: Source[Message, NotUsed] = Source(List("Hello World!")).map(TextMessage(_))
      val sourceEmpty: Source[Message, NotUsed] = Source.empty[Message]

      val socketFlowOne = webSocketFlow(s"ws://$Host:$Port/channels/test/users/user1")
      val socketFlowTwo = webSocketFlow(s"ws://$Host:$Port/channels/test/users/user2")

      val graph = RunnableGraph.fromGraph(GraphDSL.createGraph(Sink.seq[Message]) { implicit builder => (sink: SinkShape[Message]) =>
        import GraphDSL.Implicits._

        val merge = builder.add(Merge[Message](2))

        sourceEmpty ~> socketFlowOne ~> merge
        source ~> socketFlowTwo ~> merge
        merge ~> sink

        ClosedShape
      })

      val resultFuture: Future[Seq[String]] = graph.run().map(_.map(_.asTextMessage.getStrictText))

      whenReady(resultFuture) { resultList =>
        resultList should have size 2
        resultList should contain ("Hello World!")
        resultList should contain("Message processed: Hello World!")
      }
    }
  }


}
