package com.grattis.message.server.routes

import akka.actor.typed.ActorSystem
import akka.Done
import com.grattis.message.server.ChannelRegistryActor
import akka.util.Timeout
import scala.concurrent.{ExecutionContext, Future}
import akka.actor.typed.ActorRef
import akka.http.scaladsl.server.Directives.*
import akka.actor.typed.scaladsl.AskPattern.*
import com.typesafe.scalalogging.LazyLogging
import com.grattis.message.server.streams.SourceProvider
import com.grattis.message.server.streams.SinkProvider
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Source
import akka.NotUsed
import com.grattis.message.server.ChannelActor
import akka.http.scaladsl.model.ws.TextMessage
import akka.stream.scaladsl.Sink
import akka.stream.typed.scaladsl.ActorSink
import akka.stream.scaladsl.Flow
import akka.http.scaladsl.model.ws.Message
import com.grattis.message.server.streams.flows.FlowProvider
import scala.util.{Failure, Success}
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.ContentTypes
import scala.util.control.NonFatal
import com.grattis.message.server.UserActor
import scala.concurrent.duration.FiniteDuration
import akka.stream.Materializer
import akka.stream.ActorAttributes
import akka.stream.StreamSubscriptionTimeoutTerminationMode
import akka.stream.scaladsl.Keep
import akka.stream.typed.scaladsl.ActorSource
import akka.stream.OverflowStrategy
import scala.concurrent.duration.*
import com.grattis.message.server.UserActor.TopicMessage

class WebsocketRouteException(msg: String, t: Throwable = null) extends RuntimeException(msg, t)

object RouteProvider {
    case object StreamTerminationMessage extends UserActor.MessageResult
}

class RouteProvider(
    sourceProvider: SourceProvider = new SourceProvider(),
    sinkProvider: SinkProvider = new SinkProvider(),
    flowProvider: FlowProvider = new FlowProvider()

) extends LazyLogging {
    import RouteProvider._

    def websocketRoute()(implicit system: ActorSystem[ChannelRegistryActor.ChannelRegistryActorCommand], timeout: Timeout, executionContext: ExecutionContext): Route = {

        path("channels" / Segment / "users" / Segment ) { (channel, user) =>
            // find the channel
            onSuccess(system.ask(ref => ChannelRegistryActor.RegisterChannel(channel, ref))) {
                case registered: ChannelRegistryActor.ChannelRegistryEntry =>
                    logger.info(s"Channel registered '$channel' for user '$user'")
                    val channelUser = UserActor.User(user, user)

                    // this basically starts the source stream with the registered actor ref
                    // the response source contains basically of two objects
                    // the first is the actor ref that is used to send messages to the source
                    // the second is the source itself
                    val (responseActorRef: ActorRef[UserActor.MessageResult], source: Source[UserActor.MessageResult, NotUsed]) =
                      sourceProvider.actorSource[UserActor.MessageResult] {
                        case StreamTerminationMessage =>
                            logger.info(s"Source stream completed for channel '$channel' and user '$user'")
                      }

                    // the actor ref is used to subscribe to the topic - so it is basically the subscriber
                    registered.channelActor ! ChannelActor.AddToChannel(channelUser, Some(responseActorRef))

                    // this basically starts the heartbeat stream with the registered kill switch
                    val (killSwitch, materializedHeartBeatSource) = sourceProvider.heartBeatSource(TextMessage.Strict("heartbeat"))

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
                        registered.channelActor ! ChannelActor.UnsubscribeFromChannel(channelUser, system)
                    }

                    // the actor sink here is the target which used to send messages to the topic
                    val publishSink: Sink[ChannelActor.PublishToChannel, NotUsed] = sinkProvider.actorSink[ChannelActor.PublishToChannel](
                        registered.channelActor,
                        ChannelActor.PublishToChannel(UserActor.TopicMessage(None, channelUser, channel)),
                        ChannelActor.PublishToChannel(UserActor.TopicMessage(None, channelUser, channel)),
                        (ex: Throwable) => {
                            logger.error(s"Error occurred while publishing message to channel '$channel' and user '$user'", ex)
                        }
                    )

                    val textMessageFlow = flowProvider.textMessageFlow(channelUser).buildFlow()
                    
                    val incoming = 
                        flowProvider.topicMessageFlow(channel, channelUser).buildFlow()
                            .alsoTo(flowProvider.publishMessageFlow().buildFlow().to(publishSink))
                            .via(textMessageFlow)
                            // not the best way here to filter out the messages - but it works
                            // needed to merge the different sources
                            .filter(_ => false)
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
                        .map {
                            case msg: UserActor.TopicMessage => Some(msg)
                            case _ => None
                        }    
                        .collect({ case Some(msg) => msg })
                        .via(textMessageFlow)
                      

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
                            throw new WebsocketRouteException(s"Forcefully terminating the connection due to an error -  stream failed for channel '$channel' and  user '$user'", ex)
                    })
                case _ =>
                    complete(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, s"Could not register source for channel '$channel' and user '$user'")))
            }
        }
    }
  
}
