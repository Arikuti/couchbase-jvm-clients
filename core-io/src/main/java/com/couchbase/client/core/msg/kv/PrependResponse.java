package com.couchbase.client.core.msg.kv;

import com.couchbase.client.core.msg.BaseResponse;
import com.couchbase.client.core.msg.ResponseStatus;

import java.util.Optional;

public class PrependResponse extends BaseResponse {

  private final long cas;
  private final Optional<MutationToken> mutationToken;

  public PrependResponse(ResponseStatus status, long cas, Optional<MutationToken> mutationToken) {
    super(status);
    this.cas = cas;
    this.mutationToken = mutationToken;
  }

  public long cas() {
    return cas;
  }

  public Optional<MutationToken> mutationToken() {
    return mutationToken;
  }
}