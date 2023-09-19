package com.grattis.message.server

import akka.actor.typed.pubsub.Topic
import akka.actor.typed.{ActorRef, Behavior, Terminated}
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable

object ChannelRegistryActor extends LazyLogging {

  def apply(): Behavior[Any] = Behaviors.setup { context =>
    val topics = mutable.Map[String, TopicRegistration]()

    def removeSubscriber(channel: String, subscriber: ActorRef[TopicMessage]): Unit = {
      val topic = topics.get(channel)
      topic.foreach(t => {
        val isSubscribed = t.subscriber.keys.toSet.contains(subscriber)
        if (isSubscribed) {
          val user = t.subscriber(subscriber)
          t.subscriber.remove(subscriber)
          t.actorRef ! Topic.Unsubscribe(subscriber)
          logger.info(s"Removed subscriber for user '$user' from channel '$channel' - left nr of subscribers: '${t.subscriber.size}'")
          if (t.subscriber.isEmpty) {
            logger.info(s"No more subscribers for channel '$channel' - removing topic ...")
            context.stop(t.actorRef)
            topics.remove(channel)
          }
        }
      })
    }

    Behaviors.receiveMessage {
      case RegisterChannel(channel, replyTo) =>
        val topic = topics.getOrElse(channel, {
          val to = context.spawn(Topic[TopicMessage](s"topic-$channel"), s"Topic-$channel")
          val registration = TopicRegistration(actorRef = to, mutable.Map.empty)
          topics.put(channel, registration)
          registration
        })
        replyTo ! ChannelRegistered(channel, topic.actorRef)
        Behaviors.same
      case SubscribeForChannel(channel, user, subscriber) =>
        val topic: Option[TopicRegistration] = topics.get(channel)
        topic.foreach(t => {
          t.actorRef ! Topic.Subscribe(subscriber)
          t.subscriber.put(subscriber, user)
          context.watch(subscriber)
        })
        Behaviors.same
      case UnsubscribeForChannel(channel, subscriber) =>
        removeSubscriber(channel, subscriber)
        Behaviors.same
    }.receiveSignal {
      // should be just a fallback for the case that the subscriber is terminated
      case (_, Terminated(subscriber)) =>
        val topicSubscriber: ActorRef[TopicMessage] = subscriber.asInstanceOf[ActorRef[TopicMessage]]
        topics.foreach {
          case (channel: String, _) =>
            removeSubscriber(channel, topicSubscriber)
        }
        Behaviors.same
    }
  }
}
