package com.grattis.message

import akka.actor.typed.ActorRef
import akka.actor.typed.pubsub.Topic

import scala.collection.mutable

package object server {

  case class ChannelRegistered(channel: String, topic: ActorRef[Topic.Command[TopicMessage]])

  trait ChannelRegistryCommand

  case class RegisterChannel(
                              channel: String,
                              replyTo: ActorRef[ChannelRegistered]) extends ChannelRegistryCommand


  case class UnsubscribeForChannel(channel: String, subscriber: ActorRef[TopicMessage]) extends ChannelRegistryCommand


  case class SubscribeForChannel(channel: String, user: String, subscriber: ActorRef[TopicMessage]) extends ChannelRegistryCommand

  trait MessageResult

  /*object MessageKind extends Enumeration {
    type MessageKind = Value
    val WriteAck, ReadAck, Heartbeat = Value
  }

  case class MessageData(id: String, kind: MessageKind.Value, text: Option[String] = null)*/

  case class TopicMessage(message: Option[String], user: String, channel: String) extends MessageResult

  case class TopicRegistration(actorRef: ActorRef[Topic.Command[TopicMessage]], subscriber: mutable.Map[ActorRef[TopicMessage], String])

  case object StreamTerminationMessage extends MessageResult

  class MessageServerException(msg: String, t: Throwable = null) extends RuntimeException(msg, t)

}
