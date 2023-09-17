package com.grattis.message.server

import akka.NotUsed
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.Http

import scala.util.Success
import scala.util.Failure
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.AskPattern.*
import akka.util.Timeout
import akka.http.scaladsl.server.Route
import akka.stream.{ActorAttributes, Materializer, OverflowStrategy, StreamSubscriptionTimeoutTerminationMode}
import com.typesafe.scalalogging.LazyLogging
import akka.stream.typed.scaladsl.ActorSource
import akka.stream.typed.scaladsl.ActorSink

import scala.io.StdIn


object MessageServer extends LazyLogging {

    def fancyPreMaterialize[Out, Mat](in: Source[Out, Mat], timeout: FiniteDuration)
                                     (implicit materializer: Materializer)
    : (Mat, Source[Out, akka.NotUsed]) = {
        val timeoutAttr = ActorAttributes.streamSubscriptionTimeout(
            timeout,
            StreamSubscriptionTimeoutTerminationMode.cancel
        )

        val (mat, pub) = in
          .toMat(Sink.asPublisher(fanout = true))(Keep.both)
          .addAttributes(timeoutAttr)
          .run()

        mat -> Source.fromPublisher(pub)
    }

    def route()(implicit system: ActorSystem[RegisterChannel], timeout: Timeout): Route = {

        val channelRegistryActor: ActorRef[RegisterChannel] = system

        path("channels" / Segment / "users" / Segment ) { (channel, user) =>
            onSuccess(channelRegistryActor.ask(ref => RegisterChannel(channel, ref))) {
                case registered: ChannelRegistered =>
                    logger.info(s"channel registered: $channel")
                    val responseSource = ActorSource.actorRef[MessageResult](
                        completionMatcher = { case StreamTerminationMessage => },
                        failureMatcher = PartialFunction.empty,
                        bufferSize = 1000,
                        overflowStrategy = OverflowStrategy.dropHead
                    )
                    val topic = registered.topic
                    val (responseActorRef, source) = fancyPreMaterialize(responseSource, 120.seconds)
                    topic ! Topic.Subscribe(responseActorRef)

                    val publishSink: Sink[Topic.Command[TopicMessage], NotUsed] = ActorSink.actorRef[Topic.Command[TopicMessage]](topic, Topic.Publish(TopicMessage(None, user)), (ex: Throwable) => { Topic.Publish(TopicMessage(None, user)) })

                    val incoming: Sink[Message, NotUsed] = Flow[Message]
                      .map {
                        case TextMessage.Strict(msg) => Some(TopicMessage(Some(msg), user))
                        case _ => None
                      }
                      .collect({ case Some(msg) => msg })
                      .map(msg => (Topic.Publish(msg), msg))
                      .alsoTo(Flow[(Topic.Command[TopicMessage], TopicMessage)].map(_._1).to(publishSink))
                      .map(_._2)
                      .map {
                        case TopicMessage(Some(msg), _) => Some(TextMessage.Strict(s"Message processed: $msg"))
                        case _ => None
                      }
                      .collect({ case Some(msg) => msg }).to(Sink.seq)

                    val topicSource = source.collect({case m: TopicMessage => m}).filter(_.user != user).map(msg => TextMessage.Strict(msg.message.getOrElse("")))

                    val topicSourceFlow: Flow[Message, Message, NotUsed] = Flow.fromSinkAndSourceCoupled(incoming, topicSource)
                    handleWebSocketMessages(topicSourceFlow.recover {
                        case ex: Exception =>
                            TextMessage.Strict(s"An error occurred: ${ex.getMessage}")
                    })
                case other =>
                    complete("could not register source")
            }
        }
    }

    def main(args: Array[String]): Unit = {

        implicit val system: ActorSystem[RegisterChannel] = ActorSystem(ChannelRegistryActor(), "akka-system")
        import system.executionContext
        implicit val timeout: Timeout = Timeout(600.seconds)

        Http().newServerAt("localhost", 8080).bind(route()).onComplete {
            case Success(binding) =>
                sys.addShutdownHook {
                    logger.info("Stopping message server ...")
                    try {
                        binding.terminate(10.seconds)
                        logger.info("service stopped")
                    } catch {
                        case NonFatal(ex) =>
                            logger.info("could not stop service", ex)
                    }
                }
            case Failure(ex) =>
                logger.error("Server failed to start", ex)
        }
        StdIn.readLine()
    }
}
