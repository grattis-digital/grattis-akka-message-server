package com.grattis.message.server

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.grattis.message.server.ChannelActor.{AddToChannel, ChannelActorCommand, ChannelActorEvent, ChannelUserList, ChannelUserWithRef, GetFromChannel, UserAddedToChannel}
import com.grattis.message.server.UserActor.{TopicMessage, User, UserActorCommand, ReceiveMessages}
import com.grattis.message.server.utils.GrattisActorSpec

import scala.language.postfixOps

class ChannelActorSpec extends GrattisActorSpec {

  private val channel = "testChannel"
  private val testUser = User("testUser", "1234")
  private val testMessage = TopicMessage(Some("Hello Channel"), testUser, channel)

  private val userActorCommandProbe = testKit.createTestProbe[UserActor.UserActorCommand]()

  private def channelEventSourceTestKit():
    (TestProbe[UserActor.UserActorCommand], EventSourcedBehaviorTestKit[ChannelActorCommand, ChannelActorEvent, ChannelUserList]) = {
    val userTestProbe = testKit.createTestProbe[UserActor.UserActorCommand]()
    val channelActor: Behavior[ChannelActorCommand] = ChannelActor.apply(channel, (user: UserActor.User, channel: String, ctx: ActorContext[ChannelActorCommand]) => userTestProbe.ref)
    (userTestProbe,
      EventSourcedBehaviorTestKit[ChannelActorCommand, ChannelActorEvent, ChannelUserList](
      system,
      channelActor
    ))
  }

  "ChannelActor(...)" should {

    "add a user to the channel" in {
      val user = UserActor.User("testUser", "1234")
      val addUserCmd = AddToChannel(user)
      val (_, channelActorTestKit) = channelEventSourceTestKit()
      val result = channelActorTestKit.runCommand(addUserCmd)
      result.event shouldBe UserAddedToChannel(user)
    }

    "add a user and a subscriber for an active connection to the channel" in {
      val user = UserActor.User("testUser", "1234")
      val testProbe = testKit.createTestProbe[ChannelUserWithRef]()
      val addUserCmd = AddToChannel(testUser, Some(testKit.createTestProbe[TopicMessage]().ref))
      val (userActorTestProbe, channelActorTestKit) = channelEventSourceTestKit()
      val addResult = channelActorTestKit.runCommand(addUserCmd)
      channelActorTestKit.runCommand(GetFromChannel(testUser, testProbe.ref))
      val result = testProbe.expectMessageType[ChannelUserWithRef]
      result.channelUser shouldBe testUser
      result.ref shouldBe userActorTestProbe.ref
      result.subscriber shouldBe addUserCmd.subscriber
      val userActorCommand: UserActorCommand = userActorTestProbe.receiveMessage()
      userActorCommand shouldBe a[ReceiveMessages]
    }

  }
}
