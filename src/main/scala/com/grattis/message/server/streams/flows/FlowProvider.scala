package com.grattis.message.server.streams.flows

import com.grattis.message.server.UserActor
import akka.http.javadsl.model.ws.TextMessage

class FlowProvider {
  
    def textMessageFlow(channelUser: UserActor.User): TextMessageFlow = {
        new TextMessageFlow(channelUser)
    }

    def topicMessageFlow(channel: String, user: UserActor.User): TopicMessageFlow = {
        new TopicMessageFlow(channel, user)
    }

    def publishMessageFlow(): PublishMessageFlow = {
        new PublishMessageFlow()
    }
}
