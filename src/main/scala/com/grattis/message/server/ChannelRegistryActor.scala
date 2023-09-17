package com.grattis.message.server

import akka.actor.typed.pubsub.Topic
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import scala.collection.mutable

case class ChannelRegistered(channel: String, topic: ActorRef[Topic.Command[TopicMessage]])

case class RegisterChannel(
                            channel: String,
                            replyTo: ActorRef[ChannelRegistered])

trait MessageResult

case class TopicMessage(message: Option[String], user: String) extends MessageResult
case object StreamTerminationMessage extends MessageResult

object ChannelRegistryActor {

  def apply(): Behavior[RegisterChannel] = Behaviors.setup { context =>
    val topics = mutable.Map[String, ActorRef[Topic.Command[TopicMessage]]]()

    Behaviors.receiveMessage {
      case RegisterChannel(channel, replyTo) =>
        val topic: ActorRef[Topic.Command[TopicMessage]] = topics.getOrElse(channel, {
          val topic = context.spawn(Topic[TopicMessage](s"topic-$channel"), s"Topic-$channel")
          topics += (channel -> topic)
          topic
        })
        replyTo ! ChannelRegistered(channel, topic)
        Behaviors.same
    }
  }

}
