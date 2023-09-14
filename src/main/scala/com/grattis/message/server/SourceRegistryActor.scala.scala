package com.grattis.message.server

import akka.actor.Actor
import akka.stream.scaladsl.Source
import akka.http.scaladsl.model.ws.Message
import akka.pattern.ask
import scala.collection.mutable
import akka.stream.scaladsl.MergeHub
import akka.stream.scaladsl.Keep
import akka.NotUsed
import akka.stream.scaladsl.BroadcastHub
import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.http.scaladsl.model.ws.TextMessage
import akka.stream.BoundedSourceQueue


case class RegisterSource(sourceKey: String)
case class SourceQueue(queue: BoundedSourceQueue[String], source: Source[Message, _])
case class RegisteredSource(sourceKey: String, queue: SourceQueue)

class SourceRegistryActor(implicit val actorSystem: ActorSystem) extends Actor {

  private val registry = mutable.Map[String, SourceQueue]()  

  private def createSource() = {

    val (sink, source) = MergeHub.source[Message]
      .toMat(BroadcastHub.sink[Message])(Keep.both)
      .run()

    val queue: BoundedSourceQueue[String] = Source
      .queue[String](100000)
      .map(msg => TextMessage(msg))
      .toMat(sink)(Keep.left)
      .run()

    SourceQueue(queue, source)
  }

  override def receive: Actor.Receive = {
       case RegisterSource(sourceKey) =>
           val client = sender()
           println(s"Registering source: $sourceKey")
           val source = registry.get(sourceKey).getOrElse {
                val source = createSource()
                registry.addOne(sourceKey, source)
                source
           }        
           client ! RegisteredSource(sourceKey, source)
  }


}
