package com.grattis.message.server.utils

import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

trait GrattisSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterEach with BeforeAndAfterAll with ScalaFutures