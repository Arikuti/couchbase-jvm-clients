/*
 * Copyright (c) 2018 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.java.kv;

import com.couchbase.client.core.annotation.Stability;

public class AppendOptions extends CommonDurabilityOptions<AppendOptions> {
  public static AppendOptions DEFAULT = new AppendOptions();

  private long cas = 0;

  public AppendOptions cas(long cas) {
    this.cas = cas;
    return this;
  }

  @Stability.Internal
  public BuiltAppendOptions build() {
    return new BuiltAppendOptions();
  }

  public class BuiltAppendOptions extends BuiltCommonDurabilityOptions {

    public long cas() {
      return cas;
    }

  }

}