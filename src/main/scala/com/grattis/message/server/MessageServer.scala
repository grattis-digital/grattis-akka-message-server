package com.grattis.message.server

import akka.{Done, NotUsed}
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
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.util.Timeout
import akka.http.scaladsl.server.Route
import akka.stream.{ActorAttributes, KillSwitches, Materializer, OverflowStrategy, StreamSubscriptionTimeoutTerminationMode, UniqueKillSwitch}
import com.typesafe.scalalogging.LazyLogging
import akka.stream.typed.scaladsl.ActorSource
import akka.stream.typed.scaladsl.ActorSink

import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn


object MessageServer extends LazyLogging {

    /**
     * This is a workaround for the issue described here:
     * https://github.com/akka/akka/issues/28926
     *
     * The given Scala function fancyPreMaterialize is defining a utility function that materializes a
     * given Akka Stream Source with a specified timeout and returns a tuple of the materialized value
     * and a new Source backed by a publisher. This essentially "pre-materializes" a source while also
     * applying a subscription timeout attribute to the stream.
     *
     *
     *
     */
    def fancyPreMaterialize[Out, Mat](
                                       in: Source[Out, Mat],
                                       timeout: FiniteDuration)(implicit materializer: Materializer): (Mat, Source[Out, akka.NotUsed]) = {
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

    def route()(implicit system: ActorSystem[ChannelRegistryCommand], timeout: Timeout, executionContext: ExecutionContext): Route = {

        val channelRegistryActor: ActorRef[ChannelRegistryCommand] = system

        path("channels" / Segment / "users" / Segment ) { (channel, user) =>
            // find the channel
            onSuccess(channelRegistryActor.ask(ref => RegisterChannel(channel, ref))) {
                case registered: ChannelRegistered =>
                    logger.info(s"Channel registered '$channel' for user '$user'")

                    // https://doc.akka.io/docs/akka/current/stream/operators/ActorSource/actorRef.html
                    // a source that is materialized as an actor ref
                    // the actor will receive the elements fed into the source
                    val responseSource = ActorSource.actorRef[MessageResult](
                        completionMatcher = {
                          case StreamTerminationMessage =>
                            logger.info(s"Triggered termination for subscribe stream completed for channel '$channel' and user '$user'")
                        },
                        failureMatcher = PartialFunction.empty,
                        bufferSize = 1000,
                        overflowStrategy = OverflowStrategy.dropHead
                    )

                    // a registered channel contains a topic actor ref which is used to publish messages so that
                    // all subscribers receive the message published to the topic
                    val topic: ActorRef[Topic.Command[TopicMessage]] = registered.topic

                    // the response source contains basically of two objects
                    // the first is the actor ref that is used to send messages to the source
                    // the second is the source itself - the source can be then used to materialize the stream
                    val (responseActorRef: ActorRef[MessageResult], source: Source[MessageResult, NotUsed]) = fancyPreMaterialize(responseSource, 120.seconds)

                    // the actor ref is used to subscribe to the topic - so it is basically the subscriber
                    channelRegistryActor ! SubscribeForChannel(channel, user, responseActorRef)

                    // the heartbeat source is used to send messages to the client in order to keep the connection alive
                    val heartBeatSource: Source[TextMessage.Strict, UniqueKillSwitch] = Source.tick(
                      20.second, // delay of first tick
                      20.second, // delay of subsequent ticks
                      "heartbeat" // element emitted each tick
                    )
                    .map(msg => TextMessage.Strict(msg))
                    .viaMat(KillSwitches.single)(Keep.right)

                    val (killSwitch, extractedHeartBeatSource) = heartBeatSource.preMaterialize()

                    // the clean up function is used to unregister the channel and shutdown the stream
                    def cleanUp(): Unit = {
                        logger.info(s"Channel '$channel' for user '$user' will be cleaned up")
                        // send a message to the source that the stream is completed
                        responseActorRef ! StreamTerminationMessage
                        // shutdown the heartbeat stream
                        killSwitch.shutdown()
                        // unregister the subscriber
                        channelRegistryActor ! UnsubscribeForChannel(channel, responseActorRef)
                    }

                    // the actor sink here is the target which used to send messages to the topic
                    val publishSink: Sink[Topic.Command[TopicMessage], NotUsed] = ActorSink.actorRef[Topic.Command[TopicMessage]](
                        // the actor ref is used to send messages to the topic
                        topic,
                        // the message that is sent to the topic when the stream is completed
                        Topic.Publish(TopicMessage(None, user, channel)),
                        // the message that is sent to the topic when the stream is failed
                        (ex: Throwable) => {
                            logger.error(s"Topic actor sink stream error for channel '$channel' and user '$user'", ex)
                            // publish a message to the topic that the stream is failed
                            Topic.Publish(TopicMessage(None, user, channel))
                        })

                    // all message incoming via the websocket
                    val incoming: Flow[Message, Message, NotUsed] = Flow[Message]
                      // all incoming messages are mapped to a TopicMessage if there are plain text messages
                      .map {
                        case TextMessage.Strict(msg) => Some(TopicMessage(Some(msg), user, channel))
                        case _ => None
                      }
                      // collect only the messages that are not None
                      .collect({ case Some(msg) => msg })
                      // map the messages to a tuple of the message and the topic command
                      // the topic command is used to publish the message to the topic
                      // the message is used to send the message to the source as a response for an acknowledgement
                      .map(msg => (Topic.Publish(msg), msg))
                      // the topic command is sent to the topic
                      .alsoTo(
                          Flow[(Topic.Command[TopicMessage], TopicMessage)]
                            .map {
                                case (cmd: Topic.Command[TopicMessage], _) => cmd
                            }
                            .to(publishSink)
                      )
                      // the message is used as a response for an acknowledgement
                      .map {
                        case (_, msg: TopicMessage) => msg
                      }
                      // the message is mapped to a TextMessage if the message is defined
                      .map {
                        case TopicMessage(Some(msg), _, _) => Some(TextMessage.Strict(s"Message processed: $msg"))
                        case _ => None
                      }
                      // collect only the messages that are not None
                      .collect {
                          case Some(msg: TextMessage.Strict) => msg
                      }
                      // very interesting behavior here
                      // the watch termination will be only triggered on this incoming part in case of external termination
                      // on the merged part it will not work
                      .watchTermination() { (_, done: Future[Done]) =>
                        done.onComplete {
                          case Success(_) =>
                            logger.info(s"Websocket stream completed for channel '$channel' and user '$user'")
                            cleanUp()
                          case Failure(ex) =>
                            logger.error(s"Websocket stream failed for channel '$channel' and user '$user'", ex)
                            cleanUp()
                        }
                        NotUsed
                      }

                    // message incoming from the source which is the actor ref subscribed to the topic
                    val topicSource = source
                      // collect only the messages that are TopicMessage and the message is defined
                      .collect { case m: TopicMessage if m.message.isDefined => m }
                      // filter out the messages that are not from the user
                      // this is done to prevent that the user receives his own messages
                      .filter(elem => elem.user != user)
                      // map the messages to a TextMessage
                      .map(msg => TextMessage.Strict(msg.message.get))

                    // merge the incoming messages from the websocket and the topic subscription and the heartbeat messages
                    val topicSourceFlow: Flow[Message, Message, NotUsed] =
                        // messages incoming via the web socket
                        incoming
                          // messages incoming from the topic subscription
                          .merge(topicSource)
                          // heartbeat messages in order to keep the connection alive and prevent timeouts
                          // every 20 seconds a message is sent to the client
                          .merge(extractedHeartBeatSource)
                    // handle the websocket messages
                    handleWebSocketMessages(topicSourceFlow.recover {
                        case ex: Exception =>
                            cleanUp()
                            throw new MessageServerException(s"Forcefully terminating the connection due to an error -  stream failed for channel '$channel' and  user '$user'", ex)
                    })
                case _ =>
                    complete(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, s"Could not register source for channel '$channel' and user '$user'")))
            }
        }
    }

    def main(args: Array[String]): Unit = {

        implicit val system: ActorSystem[ChannelRegistryCommand] = ActorSystem(ChannelRegistryActor(), "akka-system")
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
