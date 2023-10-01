package com.grattis.message.server.streams.flows

import akka.stream.stage.GraphStage
import akka.stream.FlowShape
import com.grattis.message.server.UserActor
import akka.http.scaladsl.model.ws.Message
import akka.stream.Attributes
import akka.stream._
import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.http.scaladsl.model.ws.TextMessage

class TopicMessageFlow(channel: String, user: UserActor.User) extends FlowBuilder[Message, UserActor.TopicMessage] {
  override def buildFlow(): Flow[Message, UserActor.TopicMessage, NotUsed] = {
    Flow[Message]
      // all incoming messages are mapped to a TopicMessage if there are plain text messages
      .map {
        case TextMessage.Strict(msg) => Some(UserActor.TopicMessage(Some(msg), user, channel))
        case _ => None
      }
      // collect only the messages that are not None
      .collect({ case Some(msg) => msg })
  }
}