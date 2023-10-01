package com.grattis.message.server.streams.flows

import com.grattis.message.server.UserActor
import akka.http.scaladsl.model.ws.TextMessage
import akka.stream.scaladsl.Flow
import akka.NotUsed

class TextMessageFlow(channelUser: UserActor.User) extends FlowBuilder[UserActor.TopicMessage, TextMessage] {
  def buildFlow(): Flow[UserActor.TopicMessage, TextMessage, NotUsed] = {
    Flow[UserActor.TopicMessage].map {
      case UserActor.TopicMessage(Some(msg), user, _) if user != channelUser => Some(TextMessage.Strict(s"Received: $msg"))
      case UserActor.TopicMessage(Some(msg), _, _) => Some(TextMessage.Strict(s"Send: $msg"))
      case _ => None
    }.collect({ case Some(msg) => msg })
  }
}