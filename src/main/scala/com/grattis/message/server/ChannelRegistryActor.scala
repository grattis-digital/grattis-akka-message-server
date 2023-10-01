package com.grattis.message.server

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import scala.collection.mutable

object ChannelRegistryActor {

  trait ChannelRegistryActorCommand

  case class RegisterChannel(channelName: String, replyTo: ActorRef[ChannelRegistryEntry]) extends ChannelRegistryActorCommand

  case class UnregisterChannel(channelName: String) extends ChannelRegistryActorCommand

  case class ChannelRegistryEntry(channelName: String, channelActor: ActorRef[ChannelActor.ChannelActorCommand])

  case class ChannelRegistry(entries: Set[ChannelRegistryEntry] = Set.empty)

  def apply(): Behavior[ChannelRegistryActorCommand] = Behaviors.setup {
    context =>

      val channelMap = mutable.Map.empty[String, ChannelRegistryEntry]

      Behaviors.receiveMessage {
        case RegisterChannel(channelName, replyTo) =>
          context.log.info(s"Registering channel: $channelName")
          replyTo ! channelMap.getOrElse(channelName, {
            // create a new topic as there is no topic for the channel yet
            val to = context.spawn(ChannelActor(channelName), s"channel-$channelName")
            val registryEntry = ChannelRegistryEntry(channelName, to)
            // register the topic for the channel
            channelMap.put(channelName, registryEntry)
            registryEntry
          })
          Behaviors.same
        case UnregisterChannel(channelName) =>
          context.log.info(s"Unregistering channel: $channelName")
          channelMap.remove(channelName)
          Behaviors.same
      }
  }
}
