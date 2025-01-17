/*
 * Copyright 2021 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package controllers

import config.AppConfig
import connectors.EnrolmentStoreConnector
import controllers.actions.TrustsAuthorisedFunctions
import models.EnrolmentStoreResponse.{AlreadyClaimed, NotClaimed, ServerError}
import models._
import org.mockito.Matchers.{any, eq => mEq}
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{EitherValues, RecoverMethods}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsString, Json}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.auth.core.retrieve.{EmptyRetrieval, Retrieval, ~}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class TrustAuthControllerSpec extends PlaySpec with GuiceOneAppPerSuite with MockitoSugar with ScalaFutures with EitherValues with RecoverMethods {

  private val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  private val agentEnrolment = Enrolment("HMRC-AS-AGENT", List(EnrolmentIdentifier("AgentReferenceNumber", "SomeARN")), "Activated", None)

  private val mockAuthConnector: AuthConnector = mock[AuthConnector]
  private val mockEnrolmentStoreConnector: EnrolmentStoreConnector = mock[EnrolmentStoreConnector]

  private type RetrievalType = Option[String] ~ Option[AffinityGroup] ~ Enrolments

  private def authRetrievals(affinityGroup: AffinityGroup, enrolment: Enrolments) =
    Future.successful(new ~(new ~(Some("id"), Some(affinityGroup)), enrolment))

  private lazy val trustsAuth = new TrustsAuthorisedFunctions(mockAuthConnector, appConfig)

  private def applicationBuilder(): GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .overrides(bind[TrustsAuthorisedFunctions].toInstance(trustsAuth))
      .overrides(bind[EnrolmentStoreConnector].toInstance(mockEnrolmentStoreConnector))

  "authorisedForIdentifier with a utr" when {

    val utr = "0987654321"

    val utrTrustsEnrolment = Enrolment("HMRC-TERS-ORG", List(EnrolmentIdentifier("SAUTR", utr)), "Activated", None)

    val utrEnrolments = Enrolments(Set(agentEnrolment, utrTrustsEnrolment))

    "authenticating an agent" when {

      "an Agent user hasn't enrolled an Agent Services Account" must {

        "redirect the user to the create agent services page" in {

          val noEnrollment = Enrolments(Set())

          val app = applicationBuilder().build()

          when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
            .thenReturn(authRetrievals(AffinityGroup.Agent, noEnrollment))

          val request = FakeRequest(GET, controllers.routes.TrustAuthController.agentAuthorised().url)
          val result = route(app, request).value

          status(result) mustBe OK

          val response = contentAsJson(result).as[TrustAuthResponse]
          response mustBe TrustAuthDenied(appConfig.createAgentServicesAccountUrl)
        }
      }

      "an agent user has correct enrolled in Agent Services Account" must {

        "allow authentication" in {
          val agentEnrolments = Enrolments(Set(agentEnrolment))

          val app = applicationBuilder().build()

          when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
            .thenReturn(authRetrievals(AffinityGroup.Agent, agentEnrolments))

          val request = FakeRequest(GET, controllers.routes.TrustAuthController.agentAuthorised().url)
          val result = route(app, request).value

          status(result) mustBe OK

          val response = contentAsJson(result).as[TrustAuthResponse]
          response mustBe TrustAuthAgentAllowed("SomeARN")
        }
      }

      "trust is claimed and agent is authorised" must {

        "return authorised for a UTR" in {

          when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
            .thenReturn(authRetrievals(AffinityGroup.Agent, utrEnrolments))

          val predicatedMatcher = mEq(
            Enrolment("HMRC-TERS-ORG")
              .withIdentifier("SAUTR", utr)
              .withDelegatedAuthRule("trust-auth")
          )

          when(mockAuthConnector.authorise(predicatedMatcher, mEq(EmptyRetrieval))(any(), any()))
            .thenReturn(Future.successful(()))

          when(mockEnrolmentStoreConnector.checkIfAlreadyClaimed(mEq(UTR(utr)))(any(), any()))
            .thenReturn(Future.successful(AlreadyClaimed))

          val app = applicationBuilder().build()

          val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(utr).url)

          val result = route(app, request).value
          status(result) mustBe OK

          val response = contentAsJson(result).as[TrustAuthResponse]
          response mustBe TrustAuthAllowed()
        }
      }

      "trust has not been claimed by a trustee" must {

        "redirect to trust not claimed page" in {

          when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
            .thenReturn(authRetrievals(AffinityGroup.Agent, utrEnrolments))

          when(mockEnrolmentStoreConnector.checkIfAlreadyClaimed(mEq(UTR(utr)))(any[HeaderCarrier], any[ExecutionContext]))
            .thenReturn(Future.successful(NotClaimed))

          val app = applicationBuilder().build()

          val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(utr).url)

          val result = route(app, request).value
          status(result) mustBe OK

          val response = contentAsJson(result).as[TrustAuthResponse]
          response mustBe TrustAuthDenied(appConfig.trustNotClaimedUrl)
        }
      }

      "agent has not been authorised for any trusts" must {

        "redirect to agent not authorised" in {

          val enrolments = Enrolments(Set(agentEnrolment))

          when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
            .thenReturn(authRetrievals(AffinityGroup.Agent, enrolments))

          val predicatedMatcher = mEq(
            Enrolment("HMRC-TERS-ORG")
              .withIdentifier("SAUTR", utr)
              .withDelegatedAuthRule("trust-auth")
          )

          when(mockAuthConnector.authorise(predicatedMatcher, mEq(EmptyRetrieval))(any(), any()))
            .thenReturn(Future.failed(InsufficientEnrolments()))

          when(mockEnrolmentStoreConnector.checkIfAlreadyClaimed(mEq(UTR(utr)))(any(), any()))
            .thenReturn(Future.successful(AlreadyClaimed))

          val app = applicationBuilder().build()

          val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(utr).url)

          val result = route(app, request).value
          status(result) mustBe OK

          val response = contentAsJson(result).as[TrustAuthResponse]
          response mustBe TrustAuthDenied(appConfig.agentNotAuthorisedUrl)
        }
      }

      "an agent that has a trusts enrolment without matching submitted utr" must {

        "redirect to agent not authorised" in {

          val enrolments = Enrolments(Set(
            agentEnrolment,
            Enrolment("HMRC-TERS-ORG", List(EnrolmentIdentifier("SAUTR", "1234567890")), "Activated", None)
          ))

          when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
            .thenReturn(authRetrievals(AffinityGroup.Agent, enrolments))

          val predicatedMatcher = mEq(
            Enrolment("HMRC-TERS-ORG")
              .withIdentifier("SAUTR", utr)
              .withDelegatedAuthRule("trust-auth")
          )

          when(mockAuthConnector.authorise(predicatedMatcher, mEq(EmptyRetrieval))(any(), any()))
            .thenReturn(Future.failed(InsufficientEnrolments()))


          when(mockEnrolmentStoreConnector.checkIfAlreadyClaimed(mEq(UTR(utr)))(any(), any()))
            .thenReturn(Future.successful(AlreadyClaimed))

          val app = applicationBuilder().build()

          val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(utr).url)

          val result = route(app, request).value
          status(result) mustBe OK

          val response = contentAsJson(result).as[TrustAuthResponse]
          response mustBe TrustAuthDenied(appConfig.agentNotAuthorisedUrl)
        }
      }

    }

    "authenticating an organisation user" when {

      "organisation user has an enrolment for the trust" when {

        "relationship does not exist in Trust IV" must {

          "redirect to trust IV for a non claiming check" in {

            val enrolments = Enrolments(Set(utrTrustsEnrolment))

            when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
              .thenReturn(authRetrievals(AffinityGroup.Organisation, enrolments))

            val expectedRelationship = Relationship("Trusts", Set(BusinessKey("utr", utr)))

            when(mockAuthConnector.authorise(mEq(expectedRelationship), mEq(EmptyRetrieval))(any(), any()))
              .thenReturn(Future.failed(FailedRelationship()))

            val app = applicationBuilder().build()

            val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(utr).url)

            val result = route(app, request).value
            status(result) mustBe OK

            val response = contentAsJson(result).as[TrustAuthDenied]
            response.redirectUrl must include("/maintain-this-trust")
          }

        }

        "relationship does exist in Trust IV" must {

          "return OK" in {

            val enrolments = Enrolments(Set(utrTrustsEnrolment))

            when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
              .thenReturn(authRetrievals(AffinityGroup.Organisation, enrolments))

            val expectedRelationship = Relationship("Trusts", Set(BusinessKey("utr", utr)))

            when(mockAuthConnector.authorise(mEq(expectedRelationship), mEq(EmptyRetrieval))(any(), any()))
              .thenReturn(Future.successful(()))

            val app = applicationBuilder().build()

            val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(utr).url)

            val result = route(app, request).value
            status(result) mustBe OK

            val response = contentAsJson(result).as[TrustAuthResponse]
            response mustBe TrustAuthAllowed()
          }
        }
      }

      "organisation user has no enrolment for the trust" when {

        "unable to determine if the UTR belongs to a different org account" must {

          "redirect to tech difficulties" in {
            val enrolments = Enrolments(Set())

            when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
              .thenReturn(authRetrievals(AffinityGroup.Organisation, enrolments))

            when(mockEnrolmentStoreConnector.checkIfAlreadyClaimed(mEq(UTR(utr)))(any(), any()))
              .thenReturn(Future.successful(ServerError))

            val app = applicationBuilder().build()

            val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(utr).url)

            val result = route(app, request).value
            status(result) mustBe INTERNAL_SERVER_ERROR
          }
        }

        "utr is already claimed by a different org account" must {

          "redirect to already claimed" in {

            val enrolments = Enrolments(Set())

            when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
              .thenReturn(authRetrievals(AffinityGroup.Organisation, enrolments))

            when(mockEnrolmentStoreConnector.checkIfAlreadyClaimed(mEq(UTR(utr)))(any(), any()))
              .thenReturn(Future.successful(AlreadyClaimed))

            val app = applicationBuilder().build()

            val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(utr).url)

            val result = route(app, request).value
            status(result) mustBe OK

            val response = contentAsJson(result).as[TrustAuthResponse]
            response mustBe TrustAuthDenied(appConfig.alreadyClaimedUrl)
          }
        }

        "utr is not already claimed by an org account" must {

          "redirect to claim a trust" in {

            val enrolments = Enrolments(Set())

            when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
              .thenReturn(authRetrievals(AffinityGroup.Organisation, enrolments))

            when(mockEnrolmentStoreConnector.checkIfAlreadyClaimed(mEq(UTR(utr)))(any(), any()))
              .thenReturn(Future.successful(NotClaimed))

            val app = applicationBuilder().build()

            val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(utr).url)

            val result = route(app, request).value
            status(result) mustBe OK

            val response = contentAsJson(result).as[TrustAuthDenied]
            response.redirectUrl must include("/claim-a-trust")
          }
        }
      }
    }

    "passing a non authenticated request" must {

      "redirect to the login page" in {

        val app = applicationBuilder().build()

        when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
          .thenReturn(Future failed BearerTokenExpired())

        val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(utr).url)

        val futuristicResult = route(app, request).value
        recoverToSucceededIf[BearerTokenExpired](futuristicResult)
      }
    }

  }

  "authorisedForIdentifier with a urn" when {

    val urn = "XATRUST12345678"
    val urnTrustsEnrolment = Enrolment("HMRC-TERSNT-ORG", List(EnrolmentIdentifier("URN", urn)), "Activated", None)
    val urnEnrolments = Enrolments(Set(agentEnrolment, urnTrustsEnrolment))

    "authenticating an agent user" when {

      "an Agent user hasn't enrolled an Agent Services Account" must {

        "redirect the user to the create agent services page" in {

          val noEnrollment = Enrolments(Set())

          val app = applicationBuilder().build()

          when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
            .thenReturn(authRetrievals(AffinityGroup.Agent, noEnrollment))

          val request = FakeRequest(GET, controllers.routes.TrustAuthController.agentAuthorised().url)
          val result = route(app, request).value

          status(result) mustBe OK

          val response = contentAsJson(result).as[TrustAuthResponse]
          response mustBe TrustAuthDenied(appConfig.createAgentServicesAccountUrl)
        }
      }

      "an agent user has correct enrolled in Agent Services Account" must {
        "allow authentication" in {
          val agentEnrolments = Enrolments(Set(agentEnrolment))

          val app = applicationBuilder().build()

          when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
            .thenReturn(authRetrievals(AffinityGroup.Agent, agentEnrolments))

          val request = FakeRequest(GET, controllers.routes.TrustAuthController.agentAuthorised().url)
          val result = route(app, request).value

          status(result) mustBe OK

          val response = contentAsJson(result).as[TrustAuthResponse]
          response mustBe TrustAuthAgentAllowed("SomeARN")
        }
      }

      "trust is claimed and agent is authorised" must {

        "return OK" in {

          when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
            .thenReturn(authRetrievals(AffinityGroup.Agent, urnEnrolments))

          val predicatedMatcher = mEq(
            Enrolment("HMRC-TERSNT-ORG")
              .withIdentifier("URN", urn)
              .withDelegatedAuthRule("trust-auth")
          )

          when(mockAuthConnector.authorise(predicatedMatcher, mEq(EmptyRetrieval))(any(), any()))
            .thenReturn(Future.successful(()))

          when(mockEnrolmentStoreConnector.checkIfAlreadyClaimed(mEq(URN(urn)))(any(), any()))
            .thenReturn(Future.successful(AlreadyClaimed))

          val app = applicationBuilder().build()

          val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(urn).url)

          val result = route(app, request).value
          status(result) mustBe OK

          val response = contentAsJson(result).as[TrustAuthResponse]
          response mustBe TrustAuthAllowed()
        }
      }

      "trust has not been claimed by a trustee" must {

        "redirect to trust not claimed page" in {

          when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
            .thenReturn(authRetrievals(AffinityGroup.Agent, urnEnrolments))

          when(mockEnrolmentStoreConnector.checkIfAlreadyClaimed(mEq(URN(urn)))(any[HeaderCarrier], any[ExecutionContext]))
            .thenReturn(Future.successful(NotClaimed))

          val app = applicationBuilder().build()

          val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(urn).url)

          val result = route(app, request).value
          status(result) mustBe OK

          val response = contentAsJson(result).as[TrustAuthResponse]
          response mustBe TrustAuthDenied(appConfig.trustNotClaimedUrl)
        }
      }

      "agent has not been authorised for any trusts" must {

        "redirect to agent not authorised" in {

          val enrolments = Enrolments(Set(agentEnrolment))

          when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
            .thenReturn(authRetrievals(AffinityGroup.Agent, enrolments))

          val predicatedMatcher = mEq(
            Enrolment("HMRC-TERSNT-ORG")
              .withIdentifier("URN", urn)
              .withDelegatedAuthRule("trust-auth")
          )

          when(mockAuthConnector.authorise(predicatedMatcher, mEq(EmptyRetrieval))(any(), any()))
            .thenReturn(Future.failed(InsufficientEnrolments()))

          when(mockEnrolmentStoreConnector.checkIfAlreadyClaimed(mEq(URN(urn)))(any(), any()))
            .thenReturn(Future.successful(AlreadyClaimed))

          val app = applicationBuilder().build()

          val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(urn).url)

          val result = route(app, request).value
          status(result) mustBe OK

          val response = contentAsJson(result).as[TrustAuthResponse]
          response mustBe TrustAuthDenied(appConfig.agentNotAuthorisedUrl)
        }
      }

      "an agent that has a trusts enrolment without matching submitted urn" must {

        "redirect to agent not authorised" in {

          val enrolments = Enrolments(Set(
            agentEnrolment,
            Enrolment("HMRC-TERSNT-ORG", List(EnrolmentIdentifier("URN", "1234567890")), "Activated", None)
          ))

          when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
            .thenReturn(authRetrievals(AffinityGroup.Agent, enrolments))

          val predicatedMatcher = mEq(
            Enrolment("HMRC-TERSNT-ORG")
              .withIdentifier("URN", urn)
              .withDelegatedAuthRule("trust-auth")
          )

          when(mockAuthConnector.authorise(predicatedMatcher, mEq(EmptyRetrieval))(any(), any()))
            .thenReturn(Future.failed(InsufficientEnrolments()))


          when(mockEnrolmentStoreConnector.checkIfAlreadyClaimed(mEq(URN(urn)))(any(), any()))
            .thenReturn(Future.successful(AlreadyClaimed))

          val app = applicationBuilder().build()

          val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(urn).url)

          val result = route(app, request).value
          status(result) mustBe OK

          val response = contentAsJson(result).as[TrustAuthResponse]
          response mustBe TrustAuthDenied(appConfig.agentNotAuthorisedUrl)
        }
      }

    }

    "authenticating an organisation user" when {

      "organisation user has an enrolment for the trust" when {

        "relationship does not exist in Trust IV" must {

          "redirect to trust IV for a non claiming check" in {

            val enrolments = Enrolments(Set(urnTrustsEnrolment))

            when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
              .thenReturn(authRetrievals(AffinityGroup.Organisation, enrolments))

            val expectedRelationship = Relationship("Trusts", Set(BusinessKey("urn", urn)))

            when(mockAuthConnector.authorise(mEq(expectedRelationship), mEq(EmptyRetrieval))(any(), any()))
              .thenReturn(Future.failed(FailedRelationship()))

            val app = applicationBuilder().build()

            val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(urn).url)

            val result = route(app, request).value
            status(result) mustBe OK

            val response = contentAsJson(result).as[TrustAuthDenied]
            response.redirectUrl must include("/maintain-this-trust")
          }

        }

        "relationship does exist in Trust IV" must {

          "return OK" in {

            val enrolments = Enrolments(Set(urnTrustsEnrolment))

            when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
              .thenReturn(authRetrievals(AffinityGroup.Organisation, enrolments))

            val expectedRelationship = Relationship("Trusts", Set(BusinessKey("urn", urn)))

            when(mockAuthConnector.authorise(mEq(expectedRelationship), mEq(EmptyRetrieval))(any(), any()))
              .thenReturn(Future.successful(()))

            val app = applicationBuilder().build()

            val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(urn).url)

            val result = route(app, request).value
            status(result) mustBe OK

            val response = contentAsJson(result).as[TrustAuthResponse]
            response mustBe TrustAuthAllowed()
          }
        }
      }

      "organisation user has no enrolment for the trust" when {

        "unable to determine if the urn belongs to a different org account" must {

          "redirect to tech difficulties" in {
            val enrolments = Enrolments(Set())

            when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
              .thenReturn(authRetrievals(AffinityGroup.Organisation, enrolments))

            when(mockEnrolmentStoreConnector.checkIfAlreadyClaimed(mEq(URN(urn)))(any(), any()))
              .thenReturn(Future.successful(ServerError))

            val app = applicationBuilder().build()

            val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(urn).url)

            val result = route(app, request).value
            status(result) mustBe INTERNAL_SERVER_ERROR
          }
        }

        "urn is already claimed by a different org account" must {

          "redirect to already claimed" in {

            val enrolments = Enrolments(Set())

            when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
              .thenReturn(authRetrievals(AffinityGroup.Organisation, enrolments))

            when(mockEnrolmentStoreConnector.checkIfAlreadyClaimed(mEq(URN(urn)))(any(), any()))
              .thenReturn(Future.successful(AlreadyClaimed))

            val app = applicationBuilder().build()

            val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(urn).url)

            val result = route(app, request).value
            status(result) mustBe OK

            val response = contentAsJson(result).as[TrustAuthResponse]
            response mustBe TrustAuthDenied(appConfig.alreadyClaimedUrl)
          }
        }

        "urn is not already claimed by an org account" must {

          "redirect to claim a trust" in {

            val enrolments = Enrolments(Set())

            when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
              .thenReturn(authRetrievals(AffinityGroup.Organisation, enrolments))

            when(mockEnrolmentStoreConnector.checkIfAlreadyClaimed(mEq(URN(urn)))(any(), any()))
              .thenReturn(Future.successful(NotClaimed))

            val app = applicationBuilder().build()

            val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(urn).url)

            val result = route(app, request).value
            status(result) mustBe OK

            val response = contentAsJson(result).as[TrustAuthDenied]
            response.redirectUrl must include("/claim-a-trust")
          }
        }
      }
    }

    "passing a non authenticated request" must {

      "redirect to the login page" in {

        val app = applicationBuilder().build()

        when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
          .thenReturn(Future failed BearerTokenExpired())

        val request = FakeRequest(GET, controllers.routes.TrustAuthController.authorisedForIdentifier(urn).url)

        val futuristicResult = route(app, request).value
        recoverToSucceededIf[BearerTokenExpired](futuristicResult)
      }
    }

  }

  "authorise access code" must {

    val enrolments = Enrolments(Set())

    "return OK with TrustAuthAllowed(true) if access code is included in list of decoded access codes" in {

      when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
        .thenReturn(authRetrievals(AffinityGroup.Organisation, enrolments))

      val accessCode = "known-access-code"

      val app = applicationBuilder()
        .configure(("accessCodes", List(accessCode)))
        .build()

      val request = FakeRequest(POST, controllers.routes.TrustAuthController.authoriseAccessCode().url)
        .withJsonBody(JsString(accessCode))

      val result = route(app, request).value
      status(result) mustBe OK
      contentAsJson(result) mustBe Json.toJson(TrustAuthAllowed(authorised = true))
    }

    "return OK with TrustAuthAllowed(false) if access code is not included in list of decoded access codes" in {

      when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
        .thenReturn(authRetrievals(AffinityGroup.Organisation, enrolments))

      val accessCode = "unknown-access-code"

      val app = applicationBuilder()
        .configure(("accessCodes", List()))
        .build()

      val request = FakeRequest(POST, controllers.routes.TrustAuthController.authoriseAccessCode().url)
        .withJsonBody(JsString(accessCode))

      val result = route(app, request).value
      status(result) mustBe OK
      contentAsJson(result) mustBe Json.toJson(TrustAuthAllowed(authorised = false))
    }

    "return INTERNAL_SERVER_ERROR if access code is not in request body" in {

      when(mockAuthConnector.authorise(any(), any[Retrieval[RetrievalType]]())(any(), any()))
        .thenReturn(authRetrievals(AffinityGroup.Organisation, enrolments))

      val app = applicationBuilder()
        .configure(("accessCodes", List()))
        .build()

      val request = FakeRequest(POST, controllers.routes.TrustAuthController.authoriseAccessCode().url)

      val result = route(app, request).value
      status(result) mustBe INTERNAL_SERVER_ERROR
    }
  }
}
