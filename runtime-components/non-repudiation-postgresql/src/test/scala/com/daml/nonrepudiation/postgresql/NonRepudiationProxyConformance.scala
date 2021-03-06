// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.nonrepudiation.postgresql

import java.time.Clock

import cats.effect.{ContextShift, IO}
import com.daml.doobie.logging.Slf4jLogHandler
import com.daml.ledger.api.testtool.infrastructure.{
  LedgerTestCasesRunner,
  LedgerTestSummary,
  Result,
}
import com.daml.ledger.api.testtool.suites.ClosedWorldIT
import com.daml.ledger.api.testtool.tests._
import com.daml.ledger.api.v1.command_service.CommandServiceGrpc.CommandService
import com.daml.ledger.api.v1.command_submission_service.CommandSubmissionServiceGrpc.CommandSubmissionService
import com.daml.ledger.resources.{ResourceContext, ResourceOwner}
import com.daml.nonrepudiation.client.SigningInterceptor
import com.daml.nonrepudiation.resources.HikariTransactorResourceOwner
import com.daml.nonrepudiation.{AlgorithmString, MetricsReporterOwner, NonRepudiationProxy}
import com.daml.platform.sandbox.config.SandboxConfig
import com.daml.platform.sandboxnext.{Runner => Sandbox}
import com.daml.ports.Port
import com.daml.testing.postgresql.PostgresAroundAll
import doobie.util.log.LogHandler
import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import io.grpc.netty.NettyChannelBuilder
import org.scalatest.OptionValues
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import sun.security.tools.keytool.CertAndKeyGen
import sun.security.x509.X500Name

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

final class NonRepudiationProxyConformance
    extends AsyncFlatSpec
    with Matchers
    with OptionValues
    with PostgresAroundAll {

  import NonRepudiationProxyConformance._

  behavior of "NonRepudiationProxy"

  it should "pass all conformance tests" in {
    implicit val context: ResourceContext = ResourceContext(executionContext)
    implicit val shift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
    implicit val logHandler: LogHandler = Slf4jLogHandler(classOf[NonRepudiationProxyConformance])
    val config = SandboxConfig.defaultConfig.copy(port = Port.Dynamic)

    val proxyName = InProcessServerBuilder.generateName()
    val proxyBuilder = InProcessServerBuilder.forName(proxyName)
    val proxyChannel = InProcessChannelBuilder.forName(proxyName).build()

    val generator = new CertAndKeyGen(AlgorithmString.RSA, AlgorithmString.SHA256withRSA)
    generator.generate(2048)
    val key = generator.getPrivateKey
    val certificate = generator.getSelfCertificate(
      new X500Name("CN=Non-Repudiation Test,O=Digital Asset,L=Zurich,C=CH"),
      1.hour.toSeconds,
    )

    val proxy =
      for {
        sandboxPort <- new Sandbox(config)
        sandboxChannelBuilder = NettyChannelBuilder
          .forAddress("localhost", sandboxPort.value)
          .usePlaintext()
        sandboxChannel <- ResourceOwner.forChannel(
          sandboxChannelBuilder,
          shutdownTimeout = 5.seconds,
        )
        _ <- MetricsReporterOwner.slf4j[ResourceContext](period = 5.seconds)
        transactor <- ownTransactor(postgresDatabase.url, maxPoolSize = 10)
        db = Tables.initialize(transactor)
        _ = db.certificates.put(certificate)
        proxy <- NonRepudiationProxy.owner[ResourceContext](
          sandboxChannel,
          proxyBuilder,
          db.certificates,
          db.signedPayloads,
          Clock.systemUTC(),
          CommandService.scalaDescriptor.fullName,
          CommandSubmissionService.scalaDescriptor.fullName,
        )
      } yield proxy

    proxy.use { _ =>
      val runner = new LedgerTestCasesRunner(
        testCases = ConformanceTestCases,
        participants = Vector(proxyChannel),
        commandInterceptors = Seq(
          new SigningInterceptor(key, certificate)
        ),
      )

      runner.runTests.map { summaries =>
        summaries.foldLeft(succeed) { case (_, LedgerTestSummary(_, name, description, result)) =>
          withClue(s"$name: $description") {
            result.toOption.value shouldBe a[Result.Succeeded]
          }
        }
      }
    }
  }

}

object NonRepudiationProxyConformance {

  private val ConformanceTestCases =
    Tests
      .default()
      .filter {
        case _: ClosedWorldIT => false
        case _ => true
      }
      .flatMap(_.tests)

  private def ownTransactor(jdbcUrl: String, maxPoolSize: Int)(implicit cs: ContextShift[IO]) =
    HikariTransactorResourceOwner(ResourceOwner)(jdbcUrl, maxPoolSize)

}
