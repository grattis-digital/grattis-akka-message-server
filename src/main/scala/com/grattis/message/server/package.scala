package com.grattis.message

import akka.actor.typed.ActorRef
import akka.actor.typed.pubsub.Topic

import java.util.UUID
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

  case class User(userName: String, id: String)

  case class TopicMessage(message: Option[String], user: User, channel: String) extends MessageResult

  case class TopicRegistration(actorRef: ActorRef[Topic.Command[TopicMessage]], subscriber: mutable.Map[ActorRef[TopicMessage], String])

  case object StreamTerminationMessage extends MessageResult

  class MessageServerException(msg: String, t: Throwable = null) extends RuntimeException(msg, t)

}
