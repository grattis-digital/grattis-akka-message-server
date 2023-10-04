package com.grattis.message.server.streams.flows

import com.grattis.message.server.UserActor
import akka.http.scaladsl.model.ws.TextMessage
import akka.stream.scaladsl.Flow
import akka.NotUsed

class TextMessageFlow(channelUser: UserActor.User) extends FlowBuilder[UserActor.TopicMessage, TextMessage] {
  def buildFlow(): Flow[UserActor.TopicMessage, TextMessage, NotUsed] = {
    Flow[UserActor.TopicMessage].map {
      case UserActor.TopicMessage(Some(msg), user, _) if user != channelUser => Some(TextMessage.Strict(s"Received: $msg - from User: ${user.userName}"))
      case UserActor.TopicMessage(Some(msg), user, _) => Some(TextMessage.Strict(s"Send: $msg - for User: ${user.userName}"))
      case _ => None
    }.collect({ case Some(msg) => msg })
  }
}