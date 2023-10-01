package com.grattis.message.server.streams

import akka.actor.typed.ActorRef
import akka.stream.typed.scaladsl.ActorSink
import akka.stream.scaladsl.Sink
import akka.NotUsed

class SinkProvider {

  def actorSink[T](
                    actorRef: ActorRef[T],
                    completedMessage: T,
                    errorMessage: T,
                    handlerError: Throwable => Unit): Sink[T, NotUsed] = {
    ActorSink.actorRef[T](
      // the actor ref is used to send messages to the topic
      actorRef,
      // the message that is sent to the topic when the stream is completed
      completedMessage,
      // the message that is sent to the topic when the stream is failed
      (ex: Throwable) => {
        handlerError(ex)
        // publish a message to the topic that the stream is failed
        errorMessage
      }
    )
  }

}
