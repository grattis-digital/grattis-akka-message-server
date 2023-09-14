package com.grattis.message.server

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.must.Matchers
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.testkit.WSProbe
import akka.util.Timeout
import scala.concurrent.duration._
import org.scalatest.time.{Seconds, Span}
import org.scalatest.concurrent.ScalaFutures
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import com.typesafe.config.Config

class TrackerServerSpec extends AnyWordSpec with Matchers with ScalatestRouteTest with ScalaFutures {

    implicit val timeout: Timeout = Timeout(5.seconds)
    implicit val config: Config = ConfigFactory.load()
    implicit override val system = ActorSystem("MyTestSystem", config)

    "TrackerServer(...) - /channels/<channelname>" should {
        "register a tracking channel" in {
            val wsClient = WSProbe()

            WS("/channels/123", wsClient.flow) ~> MessageServer.route() ~> check {
                isWebSocketUpgrade mustEqual true
                wsClient.sendMessage("test message")
                wsClient.expectMessage("Message processed: test message")
                println("Message send")
                wsClient.sendCompletion()
                 println("Completion send")
                //wsClient.expectCompletion()
                //println("finalised")
            }
        }
    }
}