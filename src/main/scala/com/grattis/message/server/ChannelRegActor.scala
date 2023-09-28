package com.grattis.message.server

import akka.actor.typed.pubsub.Topic
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

object ChannelRegActor {

  trait ChannelRegistryActorCommand

  case class RegisterChannel(channelName: String, replyTo: ActorRef[ChannelRegistryEntry]) extends ChannelRegistryActorCommand

  trait ChannelRegistryActorEvent

  case class ChannelCreated(channelName: String) extends ChannelRegistryActorEvent

  case class ChannelRegistryEntry(channelName: String, channelActor: ActorRef[ChannelActor.ChannelActorCommand])

  case class ChannelRegistry(entries: Set[ChannelRegistryEntry] = Set.empty)

  def apply(): Behavior[ChannelRegistryActorCommand] = Behaviors.setup {
      context =>
        EventSourcedBehavior[ChannelRegistryActorCommand, ChannelRegistryActorEvent, ChannelRegistry](
          persistenceId = PersistenceId.ofUniqueId("channel-registry"),
          emptyState = ChannelRegistry(),
          commandHandler = (state, command) => commandHandler(context, state, command),
          eventHandler = (state, event) => eventHandler(context, state, event))
    }

  private def commandHandler(context: ActorContext[ChannelRegistryActorCommand], state:ChannelRegistry, command: ChannelRegistryActorCommand): Effect[ChannelRegistryActorEvent, ChannelRegistry] = {
    command match {
      case RegisterChannel(channelName, replyTo) =>
        state.entries.find(_.channelName == channelName).map { entry =>
          replyTo ! entry
          Effect.none
        }.getOrElse {
          Effect.persist(ChannelCreated(channelName)).thenRun { updatedState =>
            updatedState.entries.find(_.channelName == channelName).map(_.channelActor).foreach { channelActor =>
              replyTo ! ChannelRegistryEntry(channelName, channelActor)
            }
          }
        }
      case _ =>
        Effect.none
    }
  }

  private def eventHandler(context: ActorContext[ChannelRegistryActorCommand], state:ChannelRegistry, event: ChannelRegistryActorEvent): ChannelRegistry = {
    event match {
      case ChannelCreated(channelName) =>
        context.log.info("Channel created: {}", channelName)
        state.entries.find(_.channelName == channelName).map(_ => state).getOrElse {
          ChannelRegistry(state.entries + ChannelRegistryEntry(channelName, context.spawn(ChannelActor(channelName), s"channel-$channelName")))
        }
    }
  }
}
