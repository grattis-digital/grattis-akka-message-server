package com.grattis.message.server

import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.AskPattern.*
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.typed.scaladsl.{ActorSink, ActorSource}
import akka.stream.*
import akka.util.Timeout
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn
import scala.util.{Failure, Success}
import scala.util.control.NonFatal


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

    def route()(implicit system: ActorSystem[ChannelRegActor.ChannelRegistryActorCommand], timeout: Timeout, executionContext: ExecutionContext): Route = {

        val channelRegistryActor: ActorRef[ChannelRegActor.ChannelRegistryActorCommand] = system

        path("channels" / Segment / "users" / Segment ) { (channel, user) =>
            // find the channel
            onSuccess(channelRegistryActor.ask(ref => ChannelRegActor.RegisterChannel(channel, ref))) {
                case registered: ChannelRegActor.ChannelRegistryEntry =>
                    logger.info(s"Channel registered '$channel' for user '$user'")
                    val channelUser = User(user, user)

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

                    // this basically starts the source stream with the registered actor ref
                    // the response source contains basically of two objects
                    // the first is the actor ref that is used to send messages to the source
                    // the second is the source itself
                    val (responseActorRef: ActorRef[MessageResult], source: Source[MessageResult, NotUsed]) =
                      fancyPreMaterialize(responseSource, 120.seconds)

                    // the actor ref is used to subscribe to the topic - so it is basically the subscriber
                    registered.channelActor ! ChannelActor.AddToChannel(channelUser, responseActorRef)

                    // the heartbeat source is used to send messages to the client in order to keep the connection alive
                    val heartBeatSource: Source[TextMessage.Strict, UniqueKillSwitch] = Source.tick(
                      20.second, // delay of first tick
                      20.second, // delay of subsequent ticks
                      "heartbeat" // element emitted each tick
                    )
                    .map(msg => TextMessage.Strict(msg))
                    .viaMat(KillSwitches.single)(Keep.right)

                    // this basically starts the heartbeat stream with the registered kill switch
                    val (killSwitch, materializedHeartBeatSource) = heartBeatSource.preMaterialize()

                    // the clean up function is used to unregister the channel and shutdown the stream
                    def cleanUp(): Unit = {
                        logger.info(s"Channel '$channel' for user '$user' will be cleaned up")
                        // send a message to the source that the stream is completed
                        // the Actor should be actually terminated automatically as part of the stream completion
                        // but it takes a bit longer as it seems why I decided to clean it up manually to save resources
                        responseActorRef ! StreamTerminationMessage
                        // shutdown the heartbeat stream
                        // same here - actually this thing is killed as part of the stream completion and it works fine
                        // but it takes a bit longer as it seems why I decided to clean it up manually to save resources
                        killSwitch.shutdown()
                        // the ChannelRegistryActor is used to unregister the subscriber
                        // the ChannelRegistryActor is listening also to a terminated message of the subscriber -
                        // but this takes around 60 seconds after the stream is completed - so it would block resources
                        // so I decided here to clean it up manually
                        registered.channelActor ! ChannelActor.UnsubscribeFromChannel(channelUser)
                    }

                    // the actor sink here is the target which used to send messages to the topic
                    val publishSink: Sink[ChannelActor.PublishToChannel, NotUsed] = ActorSink.actorRef[ChannelActor.PublishToChannel](
                        // the actor ref is used to send messages to the topic
                        registered.channelActor,
                        // the message that is sent to the topic when the stream is completed
                        ChannelActor.PublishToChannel(TopicMessage(None, channelUser, channel)),
                        // the message that is sent to the topic when the stream is failed
                        (ex: Throwable) => {
                            logger.error(s"Topic actor sink stream error for channel '$channel' and user '$user'", ex)
                            // publish a message to the topic that the stream is failed
                            ChannelActor.PublishToChannel(TopicMessage(None, channelUser, channel))
                        })

                    // all message incoming via the websocket
                    val incoming: Flow[Message, Message, NotUsed] = Flow[Message]
                      // all incoming messages are mapped to a TopicMessage if there are plain text messages
                      .map {
                        case TextMessage.Strict(msg) => Some(TopicMessage(Some(msg), channelUser, channel))
                        case _ => None
                      }
                      // collect only the messages that are not None
                      .collect({ case Some(msg) => msg })
                      // map the messages to a tuple of the message and the topic command
                      // the topic command is used to publish the message to the topic
                      // the message is used to send the message to the source as a response for an acknowledgement
                      .map(msg => (ChannelActor.PublishToChannel(msg), msg))
                      // the topic command is sent to the topic
                      .alsoTo(
                          Flow[(ChannelActor.PublishToChannel, TopicMessage)]
                            .map {
                                case (cmd: ChannelActor.PublishToChannel, _) => cmd
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
                      .filter(elem => elem.user != channelUser)
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
                          .merge(materializedHeartBeatSource)
                    // handle the websocket messages
                    handleWebSocketMessages(topicSourceFlow.recover {
                        case NonFatal(ex)  =>
                            cleanUp()
                            throw new MessageServerException(s"Forcefully terminating the connection due to an error -  stream failed for channel '$channel' and  user '$user'", ex)
                    })
                case _ =>
                    complete(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, s"Could not register source for channel '$channel' and user '$user'")))
            }
        }
    }

    def main(args: Array[String]): Unit = {

        implicit val system: ActorSystem[ChannelRegActor.ChannelRegistryActorCommand] = ActorSystem(ChannelRegActor(), "akka-system")
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
                logger.info(s"Server started: ${binding.localAddress}")
            case Failure(ex) =>
                logger.error("Server failed to start", ex)
        }
        StdIn.readLine()
    }
}
