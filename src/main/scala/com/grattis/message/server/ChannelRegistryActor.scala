package com.grattis.message.server

import akka.actor.typed.pubsub.Topic
import akka.actor.typed.{ActorRef, Behavior, Terminated}
import akka.actor.typed.scaladsl.Behaviors

import scala.collection.mutable

object ChannelRegistryActor {

  def apply(): Behavior[Any] = Behaviors.setup { context =>
    val topics = mutable.Map[String, TopicRegistration]()
    val logger = context.log

    def removeSubscriber(channel: String, subscriber: ActorRef[TopicMessage]): Unit = {
      val topic = topics.get(channel)
      topic.foreach { t =>
        t.subscriber.get(subscriber).foreach {
          user =>
            val user = t.subscriber(subscriber)
            t.subscriber.remove(subscriber)
            t.actorRef ! Topic.Unsubscribe(subscriber)
            logger.info(s"Removed subscriber for user '$user' from channel '$channel' - left nr of subscribers: '${t.subscriber.size}'")
        }
        if (t.subscriber.isEmpty) {
          logger.info(s"No more subscribers for channel '$channel' - removing topic ...")
          context.stop(t.actorRef)
          topics.remove(channel)
        }
      }
    }

    Behaviors.receiveMessage {
      case RegisterChannel(channel, replyTo) =>
        val topic = topics.getOrElse(channel, {
          // create a new topic as there is no topic for the channel yet
          val to = context.spawn(Topic[TopicMessage](s"topic-$channel"), s"Topic-$channel")
          val registration = TopicRegistration(actorRef = to, mutable.Map.empty)
          // register the topic for the channel
          topics.put(channel, registration)
          registration
        })
        // answer the sender with the topic actor ref
        replyTo ! ChannelRegistered(channel, topic.actorRef)
        Behaviors.same
      case SubscribeForChannel(channel, user, subscriber) =>
        topics.get(channel).foreach { t =>
          // add a new subscriber to the topic
          t.actorRef ! Topic.Subscribe(subscriber)
          t.subscriber.put(subscriber, user)
          // watch the subscriber to remove it from the topic if it is terminated
          // this should be actually just a fallback as the subscriber should be removed from the topic
          // via UnsubscribeForChannel as part of the flow
          context.watch(subscriber)
        }
        Behaviors.same
      case UnsubscribeForChannel(channel, subscriber) =>
        removeSubscriber(channel, subscriber)
        Behaviors.same
    }.receiveSignal {
      // should be just a fallback for the case that the subscriber is terminated
      // in some cases it is maybe not possible to send the UnsubscribeForChannel message
      // with this handler should be guaranteed that the subscriber is removed from the topic
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
