package org.ergoplatform.http.routes

// Settles F-2 / N-3 of REVIEW-C1, both trees. The assertions encode the review's prediction per tree (the tree is
// detected by whether LocallyGeneratedModifierProcessed exists): merged = 500 "Ask timed out" after the REST timeout,
// pin = 200 at once. GREEN on a tree = the prediction holds there; RED = F-2 is wrong for that tree.
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes, UniversalEntity}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.testkit.TestProbe
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Json
import io.circe.syntax._
import org.ergoplatform.http.api.BlocksApiRoute
import org.ergoplatform.modifiers.ErgoFullBlock
import org.ergoplatform.utils.Stubs
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.core.api.http.ApiErrorHandler

import scala.concurrent.duration._
import scala.util.Try

class PostBlocksSilentHolderSpec
  extends AnyFlatSpec
  with Matchers
  with ScalatestRouteTest
  with FailFastCirceSupport
  with Stubs {

  import org.ergoplatform.utils.ErgoNodeTestConstants._
  import org.ergoplatform.utils.generators.ValidBlocksGenerators._

  implicit val routeTimeout: RouteTestTimeout = RouteTestTimeout(20.seconds)

  private val restTimeout = 2.seconds
  private val shortSettings = settings.copy(
    scorexSettings = settings.scorexSettings.copy(
      restApi = settings.scorexSettings.restApi.copy(timeout = restTimeout)
    )
  )

  private val treeHasAcks: Boolean = Try(
    Class.forName("org.ergoplatform.nodeView.ErgoNodeViewHolder$ReceivableMessages$LocallyGeneratedModifierProcessed")
  ).isSuccess

  it should "answer POST /blocks when the view holder never replies" in {
    val (st, bh) = createUtxoState(settings)
    try {
      val block: ErgoFullBlock = validFullBlock(parentOpt = None, st, bh)
      val blockJson: UniversalEntity =
        HttpEntity(block.asJson.toString).withContentType(ContentTypes.`application/json`)
      val silentHolder = TestProbe()
      val route: Route = Route.seal(BlocksApiRoute(silentHolder.ref, digestReadersRef, shortSettings).route)(
        exceptionHandler = ApiErrorHandler.exceptionHandler
      )
      val started = System.nanoTime()
      Post("/blocks", blockJson) ~> route ~> check {
        val elapsedMs = (System.nanoTime() - started) / 1000000
        val body = responseAs[Json].noSpaces
        info(s"observed: tree=${if (treeHasAcks) "merged" else "pin"} status=$status elapsedMs=$elapsedMs body=${body.take(200).replace('\n', ' ')}")
        if (treeHasAcks) {
          status shouldBe StatusCodes.InternalServerError
          body should include("Ask timed out")
          elapsedMs should be >= (restTimeout.toMillis - 200)
        } else {
          status shouldBe StatusCodes.OK
          elapsedMs should be < 1000L
        }
      }
    } finally {
      st.closeStorage()
    }
  }
}
