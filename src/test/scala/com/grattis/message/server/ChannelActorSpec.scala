package com.grattis.message.server

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.grattis.message.server.ChannelActor.{AddToChannel, ChannelActorCommand, ChannelActorEvent, ChannelUserList, ChannelUserWithRef, GetFromChannel, MessagePersisted, RemoveFromChannel, UnsubscribeFromChannel, UserAddedToChannel}
import com.grattis.message.server.ChannelRegistryActor.{ChannelRegistryActorCommand, UnregisterChannel}
import com.grattis.message.server.UserActor.{TopicMessage, User, UserActorCommand, UserActorEvent, UserActorState, ReceiveMessages}
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



    /*"add a user and a subscriber for an active connection to the channel" in {
      val user = UserActor.User("testUser", "1234")
      val testProbe = testKit.createTestProbe[ChannelUserWithRef]()
      val subscriberProbe = testKit.createTestProbe[TopicMessage]()
      val addUserCmd = AddToChannel(testUser, Some(subscriberProbe.ref))
      val result = channelEventSourceTestKit.runCommand(addUserCmd)
      channelEventSourceTestKit.runCommand(GetFromChannel(testUser, testProbe.ref))
      val message: ChannelUserWithRef = testProbe.receiveMessage()
      message.
      result.event shouldBe UserAddedToChannel(user)

    }



    "remove a user from the channel" in {
      val user = UserActor.User("testUser", "1234")
      val addUserCmd = AddToChannel(user)
      channelEventSourceTestKit.runCommand(addUserCmd)
      val removeUserCmd = RemoveFromChannel(user, testKit.spawn(Behaviors.empty[ChannelRegistryActorCommand]))
      val result = channelEventSourceTestKit.runCommand(removeUserCmd)
      result.event shouldBe ChannelActor.UserRemovedFromChannel(user)
    }

    "publish a message to all users in the channel" in {
      val testProbe = testKit.createTestProbe[MessagePersisted]()
      val user = User("testUser", "1234")
      val addUserCmd = AddToChannel(user)
      channelEventSourceTestKit.runCommand(addUserCmd)
      val message = TopicMessage(Some("Hello from channel"), user, channel)
      val publishCmd = ChannelActor.PublishToChannel(message)
      channelEventSourceTestKit.runCommand(publishCmd)

      testProbe.expectMessageType[MessagePersisted].message should be(testMessage)
    }

    "add a user to the channel and handle subscriptions" in {
      val testProbe = testKit.createTestProbe[ChannelUserWithRef]()
      val subscriberProbe = testKit.createTestProbe[TopicMessage]()
      channelEventSourceTestKit.runCommand(AddToChannel(testUser, Some(subscriberProbe.ref)))
      channelEventSourceTestKit.runCommand(GetFromChannel(testUser, testProbe.ref))
      val result = testProbe.expectMessageType[ChannelUserWithRef]
      result.channelUser should be(testUser)
      result.subscriber should be(Some(subscriberProbe.ref))
    }

    "unsubscribe a user from the channel" in {
      val registryProbe = testKit.createTestProbe[ChannelRegistryActorCommand]()
      channelEventSourceTestKit.runCommand(AddToChannel(testUser))
      channelEventSourceTestKit.runCommand(UnsubscribeFromChannel(testUser, registryProbe.ref))
      registryProbe.expectMessage(UnregisterChannel(channel))
      // Here you can check any desired behaviors or changes in state after the unsubscription
    }*/

    // You can continue to write more test cases for other functionality...

  }
}
