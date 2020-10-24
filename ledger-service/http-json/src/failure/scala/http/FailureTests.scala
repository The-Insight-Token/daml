// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.http

import akka.http.javadsl.model.ws.PeerClosedConnectionException
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.stream.scaladsl.Sink

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}
import com.daml.http.domain.Offset
import com.daml.http.json.{JsonError, SprayJson}
import com.daml.ledger.api.testing.utils.SuiteResourceManagementAroundAll
import com.daml.timer.RetryStrategy
import org.scalatest._
import org.scalatest.concurrent.Eventually
import scalaz.\/
import scalaz.syntax.show._
import scalaz.syntax.tag._
import spray.json._

import scala.concurrent.duration._

@SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
final class FailureTests
    extends AsyncFreeSpec
    with HttpFailureTestFixture
    with Matchers
    with SuiteResourceManagementAroundAll
    with Eventually
    with Inside {
  import HttpServiceTestFixture._
  import WebsocketTestFixture._

  private def headersWithParties(actAs: List[String]) =
    headersWithPartyAuth(actAs, List(), ledgerId().unwrap)

  "Command submission succeeds after reconnect" in withHttpService[Assertion] {
    (uri, encoder, _, client) =>
      for {
        p <- allocateParty(client, "Alice")
        (status, _) <- postCreateCommand(
          accountCreateCommand(p, "23"),
          encoder,
          uri,
          headersWithParties(List(p.unwrap)))
        _ = status shouldBe StatusCodes.OK
        _ = proxy.disable()
        (status, output) <- postCreateCommand(
          accountCreateCommand(p, "24"),
          encoder,
          uri,
          headersWithParties(List(p.unwrap)))
        _ = status shouldBe StatusCodes.InternalServerError
        _ <- inside(output) {
          case JsObject(fields) =>
            inside(fields.get("status")) {
              case Some(JsNumber(code)) => code shouldBe 500
            }
        }
        _ = proxy.enable()
        // eventually doesn’t handle Futures in the version of scalatest we’re using.
        _ <- RetryStrategy.constant(5, 2.seconds)((_, _) =>
          for {
            (status, _) <- postCreateCommand(
              accountCreateCommand(p, "25"),
              encoder,
              uri,
              headersWithParties(List(p.unwrap)))
          } yield status shouldBe StatusCodes.OK)
      } yield succeed
  }

  "/v1/query GET succeeds after reconnect" in withHttpService[Assertion] {
    (uri, encoder, _, client) =>
      for {
        p <- allocateParty(client, "Alice")
        (status, _) <- postCreateCommand(
          accountCreateCommand(p, "23"),
          encoder,
          uri,
          headersWithParties(List(p.unwrap)))
        (status, output) <- getRequest(
          uri = uri.withPath(Uri.Path("/v1/query")),
          headersWithParties(List(p.unwrap)))
        _ <- inside(output) {
          case JsObject(fields) =>
            inside(fields.get("result")) {
              case Some(JsArray(rs)) => rs.size shouldBe 1
            }
        }
        _ = proxy.disable()
        (status, output) <- getRequest(
          uri = uri.withPath(Uri.Path("/v1/query")),
          headersWithParties(List(p.unwrap)))
        _ <- inside(output) {
          case JsObject(fields) =>
            inside(fields.get("status")) {
              case Some(JsNumber(code)) => code shouldBe 501
            }
        }
        // TODO Document this properly or adjust it
        _ = status shouldBe StatusCodes.OK
        _ = proxy.enable()
      } yield succeed
  }

  "/v1/query POST succeeds after reconnect" in withHttpService[Assertion] {
    (uri, encoder, _, client) =>
      for {
        p <- allocateParty(client, "Alice")
        (status, _) <- postCreateCommand(
          accountCreateCommand(p, "23"),
          encoder,
          uri,
          headersWithParties(List(p.unwrap)))
        _ = status shouldBe StatusCodes.OK
        query = jsObject("""{"templateIds": ["Account:Account"]}""")
        (status, output) <- postRequest(
          uri = uri.withPath(Uri.Path("/v1/query")),
          query,
          headersWithParties(List(p.unwrap)))
        _ = status shouldBe StatusCodes.OK
        _ <- inside(output) {
          case JsObject(fields) =>
            inside(fields.get("result")) {
              case Some(JsArray(rs)) => rs.size shouldBe 1
            }
        }
        _ = proxy.disable()
        (status, output) <- postRequest(
          uri = uri.withPath(Uri.Path("/v1/query")),
          query,
          headersWithParties(List(p.unwrap)))
        _ <- inside(output) {
          case JsObject(fields) =>
            inside(fields.get("status")) {
              case Some(JsNumber(code)) => code shouldBe 501
            }
        }
        // TODO Document this properly or adjust it
        _ = status shouldBe StatusCodes.OK
        _ = proxy.enable()
        // eventually doesn’t handle Futures in the version of scalatest we’re using.
        _ <- RetryStrategy.constant(5, 2.seconds)((_, _) =>
          for {
            (status, output) <- postRequest(
              uri = uri.withPath(Uri.Path("/v1/query")),
              query,
              headersWithParties(List(p.unwrap)))
            _ = status shouldBe StatusCodes.OK
            _ <- inside(output) {
              case JsObject(fields) =>
                inside(fields.get("result")) {
                  case Some(JsArray(rs)) => rs.size shouldBe 1
                }
            }
          } yield succeed)
      } yield succeed
  }

  "/v1/stream/query can reconnect" in withHttpService { (uri, encoder, _, client) =>
    val query =
      """[
          {"templateIds": ["Account:Account"]}
        ]"""

    val offset = Promise[Offset]()

    def respBefore(accountCid: domain.ContractId): Sink[JsValue, Future[Unit]] = {
      val dslSyntax = Consume.syntax[JsValue]
      import dslSyntax._
      Consume.interpret(
        for {
          ContractDelta(Vector((ctId, _)), Vector(), None) <- readOne
          _ = ctId shouldBe accountCid.unwrap
          ContractDelta(Vector(), Vector(), Some(liveStartOffset)) <- readOne
          _ = offset.success(liveStartOffset)
          _ = proxy.disable()
          _ <- drain
        } yield ()
      )
    }

    def respAfter(
        offset: domain.Offset,
        accountCid: domain.ContractId): Sink[JsValue, Future[Unit]] = {
      val dslSyntax = Consume.syntax[JsValue]
      import dslSyntax._
      Consume.interpret(
        for {
          ContractDelta(Vector((ctId, _)), Vector(), Some(newOffset)) <- readOne
          _ = ctId shouldBe accountCid.unwrap
          _ = newOffset.unwrap should be > offset.unwrap
          _ <- drain
        } yield ()
      )
    }

    for {
      p <- allocateParty(client, "p")
      (status, r) <- postCreateCommand(
        accountCreateCommand(p, "abc123"),
        encoder,
        uri,
        headers = headersWithParties(List(p.unwrap)))
      _ = status shouldBe 'success
      cid = getContractId(getResult(r))
      r <- (singleClientQueryStream(
        jwtForParties(List(p.unwrap), List(), ledgerId().unwrap),
        uri,
        query,
        keepOpen = true) via parseResp runWith respBefore(cid)).transform(x => Success(x))
      _ = inside(r) {
        case Failure(e: PeerClosedConnectionException) =>
          e.closeCode shouldBe 1011
          e.closeReason shouldBe "internal error"
      }
      offset <- offset.future
      _ = proxy.enable()
      (status, r) <- postCreateCommand(
        accountCreateCommand(p, "abc456"),
        encoder,
        uri,
        headers = headersWithParties(List(p.unwrap)))
      cid = getContractId(getResult(r))
      _ = status shouldBe 'success
      _ <- singleClientQueryStream(
        jwtForParties(List(p.unwrap), List(), ledgerId().unwrap),
        uri,
        query,
        Some(offset)) via parseResp runWith respAfter(offset, cid)
    } yield succeed

  }

  protected def jsObject(s: String): JsObject = {
    val r: JsonError \/ JsObject = for {
      jsVal <- SprayJson.parse(s).leftMap(e => JsonError(e.shows))
      jsObj <- SprayJson.mustBeJsObject(jsVal)
    } yield jsObj
    r.valueOr(e => fail(e.shows))
  }
}