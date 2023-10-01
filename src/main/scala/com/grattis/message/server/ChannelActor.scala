package com.grattis.message.server

import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import scala.collection.mutable

object ChannelActor {

  trait ChannelActorCommand

  case object Stop extends ChannelActorCommand

  case class GetFromChannel(user: UserActor.User, ref: ActorRef[ChannelUserWithRef]) extends ChannelActorCommand

  case class AddToChannel(user: UserActor.User, subscriber: Option[ActorRef[UserActor.TopicMessage]] = None) extends ChannelActorCommand

  case class RemoveFromChannel(user: UserActor.User, registry: ActorRef[ChannelRegistryActor.ChannelRegistryActorCommand]) extends ChannelActorCommand

  case class UnsubscribeFromChannel(user: UserActor.User, registry: ActorRef[ChannelRegistryActor.ChannelRegistryActorCommand]) extends ChannelActorCommand

  case class PublishToChannel(message: UserActor.TopicMessage) extends ChannelActorCommand

  case class MessagePersisted(message: UserActor.TopicMessage, receiverUser: UserActor.User) extends ChannelActorCommand

  case class SubscribeToChannel(user: UserActor.User, subscriber: ActorRef[UserActor.TopicMessage]) extends ChannelActorCommand

  trait ChannelActorEvent

  case class UserAddedToChannel(user: UserActor.User) extends ChannelActorEvent

  case class UserRemovedFromChannel(user: UserActor.User) extends ChannelActorEvent

  case class ChannelUserWithRef(channelUser: UserActor.User, ref: ActorRef[UserActor.UserActorCommand], subscriber: Option[ActorRef[UserActor.TopicMessage]] = None)
  case class ChannelUserList(users: Set[ChannelUserWithRef] = Set.empty)


  private val subscriberMap = mutable.Map.empty[String, ActorRef[UserActor.TopicMessage]]

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
        if(state.users.exists(_.channelUser == toAdd.user)) {
          context.log.info(s"User ${toAdd.user} already added to channel $channel")
          toAdd.subscriber.foreach { subscriber =>
            context.self ! SubscribeToChannel(toAdd.user, subscriber)
          }
          Effect.none
        } else {
          Effect.persist(UserAddedToChannel(toAdd.user)).thenRun { _ =>
            context.log.info(s"User ${toAdd.user} added to channel $channel - sending subscribe message")
            toAdd.subscriber.foreach { subscriber =>
              context.self ! SubscribeToChannel(toAdd.user, subscriber)
            }            
          }
        }      
      case toRemove: RemoveFromChannel =>
        Effect.persist(UserRemovedFromChannel(toRemove.user)).thenRun { updatedState =>
          context.log.info(s"User ${toRemove.user} removed from channel $channel")
          context.self ! UnsubscribeFromChannel(toRemove.user, toRemove.registry)
          updatedState.users.find(_.channelUser == toRemove.user).foreach(_.ref ! UserActor.Stop)
        }
      case toUnsubscribe: UnsubscribeFromChannel =>
        context.log.info(s"User ${toUnsubscribe.user} unsubscribed from channel $channel")
        subscriberMap.remove(toUnsubscribe.user.id)
        if(subscriberMap.isEmpty) {
          state.users.foreach(_.ref ! UserActor.Stop)
          toUnsubscribe.registry ! ChannelRegistryActor.UnregisterChannel(channel)
          context.self ! Stop
        }
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
      case Stop => 
        Effect.stop()  
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
