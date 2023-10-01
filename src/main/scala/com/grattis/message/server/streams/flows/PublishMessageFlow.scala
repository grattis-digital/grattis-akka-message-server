package com.grattis.message.server.streams.flows

import akka.NotUsed
import com.grattis.message.server.UserActor
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import com.grattis.message.server.ChannelActor


class PublishMessageFlow extends FlowBuilder[UserActor.TopicMessage, ChannelActor.PublishToChannel] {
  override def buildFlow(): Flow[UserActor.TopicMessage, ChannelActor.PublishToChannel, NotUsed] = {
    Flow[UserActor.TopicMessage].map {
        case msg: UserActor.TopicMessage if msg.message.isDefined => Some(ChannelActor.PublishToChannel(msg))
        case _ => None
      }
      .collect({ case Some(msg) => msg })
  }
}
