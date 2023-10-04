package com.grattis.message.server

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, Terminated}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.grattis.message.server.UserActor.{User, UserActorCommand, UserActorEvent, UserActorState}
import com.grattis.message.server.utils.GrattisActorSpec

class UserActorSpec extends GrattisActorSpec {

  private val user = User("testUser", "1234")
  private val channel = "testChannel"

  private val eventSourceTestKit =
    EventSourcedBehaviorTestKit[UserActorCommand, UserActorEvent, UserActorState](
      system,
      UserActor.apply(user, channel))

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourceTestKit.clear()
  }

  def watcher(target: ActorRef[_], probe: ActorRef[Terminated]): Behavior[Any] = Behaviors.setup { context =>
    context.watch(target)
    Behaviors.receiveMessage {
      case Terminated(ref) =>
        probe.tell(Terminated(ref))
        Behaviors.stopped
    }
  }

  "UserActor(...)" should {
    "add a new message" in {
      val user = UserActor.User("testUser", "1234")
      val channel = "testChannel"
      val testProbe = testKit.createTestProbe[ChannelActor.MessagePersisted]()
      val addMessage = UserActor.AddMessage(UserActor.TopicMessage(Some("Hello World"), user, channel), testProbe.ref)
      eventSourceTestKit.runCommand(addMessage)
      testProbe.expectMessage(ChannelActor.MessagePersisted(addMessage.msg, user))
    }

    "get all received messages" in {
      val user = UserActor.User("testUser", "1234")
      val channel = "testChannel"
      val testProbe = testKit.createTestProbe[ChannelActor.MessagePersisted]()
      val testProbeReceived = testKit.createTestProbe[ChannelActor.MessagePersisted]()
      val addMessageOne = UserActor.AddMessage(UserActor.TopicMessage(Some("Hello World One"), user, channel), testProbe.ref)
      val addMessageTwo = UserActor.AddMessage(UserActor.TopicMessage(Some("Hello World Two"), user, channel), testProbe.ref)
      eventSourceTestKit.runCommand(addMessageOne)
      eventSourceTestKit.runCommand(addMessageTwo)
      testProbe.expectMessage(ChannelActor.MessagePersisted(addMessageOne.msg, user))
      testProbe.expectMessage(ChannelActor.MessagePersisted(addMessageTwo.msg, user))
      eventSourceTestKit.runCommand(UserActor.ReceiveMessages(testProbeReceived.ref))
      testProbeReceived.expectMessage(ChannelActor.MessagePersisted(addMessageOne.msg, user))
      testProbeReceived.expectMessage(ChannelActor.MessagePersisted(addMessageTwo.msg, user))
    }

  }

}
