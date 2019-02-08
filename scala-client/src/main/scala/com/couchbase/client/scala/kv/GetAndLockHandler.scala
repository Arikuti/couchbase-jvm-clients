package com.couchbase.client.scala.kv

import com.couchbase.client.core.msg.ResponseStatus
import com.couchbase.client.core.msg.kv.{GetAndLockRequest, GetAndLockResponse}
import com.couchbase.client.core.retry.RetryStrategy
import com.couchbase.client.core.util.Validators
import com.couchbase.client.scala.HandlerParams
import com.couchbase.client.scala.codec.Conversions
import com.couchbase.client.scala.document.GetResult
import com.couchbase.client.scala.durability.{Disabled, Durability}
import io.opentracing.Span

import scala.concurrent.duration.FiniteDuration
import scala.util.{Success, Try}


class GetAndLockHandler(hp: HandlerParams) extends RequestHandler[GetAndLockResponse, GetResult] {

  def request[T](id: String,
                 expiration: java.time.Duration,
                 parentSpan: Option[Span] = None,
                 timeout: java.time.Duration,
                 retryStrategy: RetryStrategy)
  : Try[GetAndLockRequest] = {
    Validators.notNullOrEmpty(id, "id")

    Success(new GetAndLockRequest(id,
      hp.collectionIdEncoded,
      timeout,
      hp.core.context(),
      hp.bucketName,
      retryStrategy,
      expiration))
  }

  def response(id: String, response: GetAndLockResponse): GetResult = {
    response.status() match {
      case ResponseStatus.SUCCESS =>
        new GetResult(id, response.content, response.flags(), response.cas, Option.empty)

      case _ => throw DefaultErrors.throwOnBadResult(response.status())
    }
  }
}