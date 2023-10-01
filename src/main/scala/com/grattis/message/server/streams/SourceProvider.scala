package com.grattis.message.server.streams

import akka.stream.scaladsl.Source
import scala.concurrent.duration.*
import akka.stream.KillSwitches
import akka.stream.scaladsl.Keep
import akka.stream.UniqueKillSwitch
import akka.stream.OverflowStrategy
import akka.stream.OverflowStrategy
import akka.stream.typed.scaladsl.ActorSource
import java.util.function.Predicate
import akka.stream.ActorAttributes
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.stream.StreamSubscriptionTimeoutTerminationMode
import akka.NotUsed

class SourceProvider {

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


    def heartBeatSource[T](heartBeatMsg: T)(implicit materializer: Materializer): (UniqueKillSwitch, Source[T, NotUsed]) = {
        Source.tick(
                      20.second, // delay of first tick
                      20.second, // delay of subsequent ticks
                      heartBeatMsg// element emitted each tick
                    )                
                    .viaMat(KillSwitches.single)(Keep.right).preMaterialize()
    }

    def actorSource[T](completionMatcher: PartialFunction[T, Unit])(implicit materializer: Materializer) = {
        fancyPreMaterialize(ActorSource.actorRef[T](
            completionMatcher = completionMatcher,
            failureMatcher = PartialFunction.empty,
            bufferSize = 1000,
            overflowStrategy = OverflowStrategy.dropHead
        ), 120.seconds)
    }


}