package com.grattis.message.server

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import com.grattis.message.server.ChannelActor.ChannelActorCommand


object UserActor {

  case class User(userName: String, id: String)

  trait MessageResult
  case class TopicMessage(message: Option[String], user: User, channel: String) extends MessageResult
    

  trait UserActorCommand

  case class AddMessage(msg: TopicMessage, replyTo: ActorRef[ChannelActor.MessagePersisted]) extends UserActorCommand

  case class RemoveMessage(msg: TopicMessage, replyTo: Option[ActorRef[TopicMessage]] = None) extends UserActorCommand

  case object Stop extends UserActorCommand

  case class ReceiveMessages(replyTo: ActorRef[ChannelActor.MessagePersisted]) extends UserActorCommand

  trait UserActorEvent

  case class MessageReceived(msg: TopicMessage) extends UserActorEvent
  case class MessageRemoved(msg: TopicMessage) extends UserActorEvent

  case class UserActorState(messagesToDeliver: Seq[TopicMessage] = Nil)

  def apply(user: User, channel: String) : Behavior[UserActorCommand] =

    Behaviors.setup {
      context =>
        EventSourcedBehavior[UserActorCommand, UserActorEvent, UserActorState](
          persistenceId = PersistenceId.ofUniqueId(s"user-${user.userName}-$channel"),
          emptyState = UserActorState(),
          commandHandler = (state, command) => commandHandler(user, context, state, command),
          eventHandler = (state, event) => eventHandler(user, context, state, event))

    }  
    
    

  private def commandHandler(user: User, context: ActorContext[UserActorCommand], state: UserActorState, command: UserActorCommand): Effect[UserActorEvent, UserActorState] = {
    command match {
      case Stop =>
        Effect.stop() 
      case toAdd: AddMessage =>
        Effect.persist(MessageReceived(toAdd.msg)).thenRun(_ => toAdd.replyTo ! ChannelActor.MessagePersisted(toAdd.msg, user))
      case toRemove: RemoveMessage =>
        Effect.persist(MessageRemoved(toRemove.msg)).thenRun(_ => toRemove.replyTo.foreach(_ ! toRemove.msg))
      case toReceive: ReceiveMessages =>
        context.log.info(s"Messages to deliver: ${state.messagesToDeliver} for user: ${user.userName}")
        state.messagesToDeliver.foreach(msg => toReceive.replyTo ! ChannelActor.MessagePersisted(msg, user))
        Effect.none
      case _ => Effect.none
    }
  }

  private def eventHandler(user: User, context: ActorContext[UserActorCommand], state: UserActorState, event: UserActorEvent): UserActorState = {
    event match {
      case msgReceived: MessageReceived =>
        context.log.info(s"Message received: ${msgReceived.msg} for user: ${user.userName}")
        UserActorState(state.messagesToDeliver ++ Seq(msgReceived.msg))
      case msgRemoved: MessageRemoved =>
        UserActorState(state.messagesToDeliver.filterNot(_ == msgRemoved.msg))
    }
  }

}
