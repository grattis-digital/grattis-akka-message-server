package com.grattis.message.server.streams.flows

import akka.NotUsed
import akka.stream.scaladsl.Flow


trait FlowBuilder[T, U] {
    def buildFlow(): Flow[T, U, NotUsed]
}