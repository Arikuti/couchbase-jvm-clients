package com.couchbase.client.core.msg.kv;

import com.couchbase.client.core.msg.BaseResponse;
import com.couchbase.client.core.msg.ResponseStatus;

public class InsertResponse extends BaseResponse {

  public InsertResponse(ResponseStatus status) {
    super(status);
  }
}
