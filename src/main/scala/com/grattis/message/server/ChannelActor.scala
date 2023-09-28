package com.grattis.message.server

import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import scala.collection.mutable

object ChannelActor {

  trait ChannelActorCommand

  case class GetFromChannel(user: User, ref: ActorRef[ChannelUserWithRef]) extends ChannelActorCommand

  case class AddToChannel(user: User, subscriber: ActorRef[TopicMessage]) extends ChannelActorCommand

  case class RemoveFromChannel(user: User) extends ChannelActorCommand

  case class UnsubscribeFromChannel(user: User) extends ChannelActorCommand

  case class PublishToChannel(message: TopicMessage) extends ChannelActorCommand

  case class MessagePersisted(message: TopicMessage, receiverUser: User) extends ChannelActorCommand


  case class SubscribeToChannel(user: User, subscriber: ActorRef[TopicMessage]) extends ChannelActorCommand

  trait ChannelActorEvent

  case class UserAddedToChannel(user: User) extends ChannelActorEvent

  case class UserRemovedFromChannel(user: User) extends ChannelActorEvent

  case class ChannelUserWithRef(channelUser: User, ref: ActorRef[UserActor.UserActorCommand], subscriber: Option[ActorRef[TopicMessage]] = None)
  case class ChannelUserList(users: Set[ChannelUserWithRef] = Set.empty)


  private val subscriberMap = mutable.Map.empty[String, ActorRef[TopicMessage]]

  def apply(channel: String): Behavior[ChannelActorCommand] =
    Behaviors.setup {
      context =>
        EventSourcedBehavior[ChannelActorCommand, ChannelActorEvent, ChannelUserList](
          persistenceId = PersistenceId.ofUniqueId(s"$channel"),
          emptyState = ChannelUserList(),
          commandHandler = (state, command) => commandHandler(channel, context, state, command),
          eventHandler = (state, event) => eventHandler(channel, context, state, event))
    }

  private def commandHandler(
                              channel: String,
                              context: ActorContext[ChannelActorCommand],
                              state: ChannelUserList, command: ChannelActorCommand
                            ): Effect[ChannelActorEvent, ChannelUserList] = {
    command match {
      case fromChannel: GetFromChannel =>
        state.users.find(_.channelUser == fromChannel.user).map {
          userWithRef =>
            fromChannel.ref ! userWithRef
            Effect.none
        }.getOrElse {
          Effect.none
        }
      case toAdd: AddToChannel =>
        Effect.persist(UserAddedToChannel(toAdd.user)).thenRun { _ =>
          context.log.info(s"User ${toAdd.user} added to channel $channel - sending subscribe message")
          context.self ! SubscribeToChannel(toAdd.user, toAdd.subscriber)
        }
      case toRemove: RemoveFromChannel =>
        Effect.persist(UserRemovedFromChannel(toRemove.user)).thenRun { updatedState =>
          updatedState.users.find(_.channelUser == toRemove.user).foreach(_.ref ! UserActor.Stop)
          subscriberMap.remove(toRemove.user.id)
        }
      case toUnsubscribe: UnsubscribeFromChannel =>
        subscriberMap.remove(toUnsubscribe.user.id)
        Effect.none
      case subscribe: SubscribeToChannel =>
        context.log.info(s"User ${subscribe.user} subscribed to channel $channel")
        state.users.find(_.channelUser == subscribe.user).map {
          userWithRef =>
            subscriberMap.put(subscribe.user.id, subscribe.subscriber)
            userWithRef.ref ! UserActor.ReceiveMessages(context.self)
            Effect.none
        }.getOrElse {
          Effect.none
        }
      case publish: PublishToChannel =>
        state.users.foreach { user =>
          user.ref ! UserActor.AddMessage(publish.message, context.self)
        }
        Effect.none
      case persistedMessage: MessagePersisted =>
        subscriberMap.get(persistedMessage.receiverUser.id).foreach(_ ! persistedMessage.message)
        Effect.none
      case _ =>
        Effect.none
    }
  }

  private def eventHandler(
                            channel: String,
                            context: ActorContext[ChannelActorCommand],
                            state: ChannelUserList,
                            event: ChannelActorEvent
                          ): ChannelUserList = {
    event match {
      case added: UserAddedToChannel =>
        context.log.info(s"User ${added.user} added to channel $channel")
        state.users.find(_.channelUser == added.user).map(_ => state).getOrElse {
          val userActor = context.spawn(UserActor(added.user, channel), added.user.id)
          ChannelUserList(
            state.users + ChannelUserWithRef(added.user, userActor))
        }
      case removed: UserRemovedFromChannel =>
        val userToRemove = state.users.find(_.channelUser == removed.user)
        ChannelUserList(userToRemove.map(state.users - _).getOrElse(state.users))
    }
  }
}
