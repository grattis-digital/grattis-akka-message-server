package com.grattis.message.server

import akka.actor.typed.pubsub.Topic
import akka.actor.typed.{ActorRef, Behavior, Terminated}
import akka.actor.typed.scaladsl.Behaviors
/*import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Put, Subscribe, Unsubscribe}
import akka.actor.typed.scaladsl.adapter.*
import akka.cluster.ddata.ORSetKey*/
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable

case class ChannelRegistered(channel: String, topic: ActorRef[Topic.Command[TopicMessage]])

trait ChannelRegistryCommand

case class RegisterChannel(
                            channel: String,
                            replyTo: ActorRef[ChannelRegistered]) extends ChannelRegistryCommand

case class SubscribeForChannel(channel: String, subscriber: ActorRef[TopicMessage]) extends ChannelRegistryCommand

trait MessageResult

case class TopicMessage(message: Option[String], user: String) extends MessageResult
case object StreamTerminationMessage extends MessageResult

case class TopicRegistration(actorRef: ActorRef[Topic.Command[TopicMessage]], subscriber: mutable.Set[ActorRef[TopicMessage]])

object ChannelRegistryActor extends LazyLogging {

  def apply(): Behavior[Any] = Behaviors.setup { context =>
    val topics = mutable.Map[String, TopicRegistration]()
    //val mediator = DistributedPubSub(context.system).mediator

    Behaviors.receiveMessage {
      case RegisterChannel(channel, replyTo) =>
        val topic = topics.getOrElse(channel, {
          val to = context.spawn(Topic[TopicMessage](s"topic-$channel"), s"Topic-$channel")
          val registration = TopicRegistration(actorRef = to, mutable.Set.empty)
          topics.put(channel, registration)
          registration
        })
        //mediator ! Put(topic.actorRef.toClassic)
        replyTo ! ChannelRegistered(channel, topic.actorRef)
        Behaviors.same
      case SubscribeForChannel(channel, subscriber) =>
        val topic: Option[TopicRegistration] = topics.get(channel)
        topic.foreach(t => {
          t.actorRef ! Topic.Subscribe(subscriber)
          //mediator ! Subscribe(s"topic-$channel", t.actorRef.toClassic)
          t.subscriber.add(subscriber)
          context.watch(subscriber)
        })
        Behaviors.same
    }.receiveSignal {
      case (_, Terminated(subscriber)) =>
        val tSubscriber = subscriber.asInstanceOf[ActorRef[TopicMessage]]
        topics.foreach {
          case (channel, topic) =>
            val isSubscribed = topic.subscriber.contains(tSubscriber)
            if (isSubscribed) {
              topic.subscriber.remove(tSubscriber)
              topic.actorRef ! Topic.Unsubscribe(tSubscriber)
              //mediator ! Unsubscribe(s"topic-$channel", tSubscriber.toClassic)
              logger.info("Removed subscriber: " + tSubscriber + " from channel: " + channel + " - subscribers: " + topic.subscriber.size)
              if(topic.subscriber.isEmpty) {
                logger.info("No more subscribers for channel: " + channel + " - removing topic")
                context.stop(topic.actorRef)
                topics.remove(channel)
              }
            }
        }
        Behaviors.same
    }
  }

}
