/*
 * Copyright (c) 2019 Couchbase, Inc.
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

package com.couchbase.client.java.query;

public enum  QueryStatus {
    RUNNING,
    SUCCESS,
    ERRORS,
    COMPLETED,
    STOPPED,
    TIMEOUT,
    CLOSED,
    FATAL,
    ABORTED,
    UNKNOWN;

    public static QueryStatus from(final String wireName) {
        try {
            return QueryStatus.valueOf(wireName.toUpperCase());
        } catch (Exception ex) {
            return QueryStatus.UNKNOWN;
        }
    }
}
