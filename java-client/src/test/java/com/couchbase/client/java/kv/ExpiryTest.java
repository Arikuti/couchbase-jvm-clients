/*
 * Copyright 2021 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.java.kv;

import com.couchbase.client.core.error.InvalidArgumentException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

class ExpiryTest {

  @Test
  void absolute() {
    // almost certainly a programming error
    assertInvalid(Instant.ofEpochSecond(-1));
    assertInvalid(Instant.ofEpochMilli(-1));
    assertInvalid(Instant.ofEpochMilli(1));

    // must accept, since this is how "get with expiry" represents "no expiry"
    assertValid(Instant.ofEpochSecond(0));

    // almost certainly a programming error, and the epoch seconds would be misinterpreted as durations (< 30 day, give or take)
    assertInvalid(Instant.ofEpochSecond(1));
    assertInvalid(Instant.ofEpochSecond((Duration.ofDays(31).getSeconds() - 1)));

    // first valid expiry instant ...ish.. the true value is 30 days (plus one second?) but it's fine to err on the side of caution.
    assertValid(Instant.ofEpochSecond(Duration.ofDays(31).getSeconds()));

    // max value for 32-bit unsigned integer
    assertValid(Instant.ofEpochSecond(4294967295L));

    // too big for 32-bit unsigned integer
    assertInvalid(Instant.ofEpochSecond(4294967296L));
  }

  @Test
  void shortDurationsAreEncodedVerbatim() {
    // this behavior isn't *critical*, but it does mitigate clock drift for very short durations
    assertEncodedAsSecondsFromNow(Duration.ofSeconds(1));
    assertEncodedAsSecondsFromNow(Duration.ofDays(30).minusSeconds(1));
  }

  @Test
  void longDurationsAreConvertedToEpochSecond() {
    // if duration is exactly 30 days, err on the side of caution and convert to epoch second
    assertEncodedAsEpochSecond(Duration.ofDays(30));
    assertEncodedAsEpochSecond(Duration.ofDays(30).plusSeconds(1));

    // Due to historical madness related to JCBC-1645, this is the max duration we're considering valid
    assertEncodedAsEpochSecond(Duration.ofDays(365 * 50));
  }

  @Test
  void durationsMustBeBetweenOneSecondAndFiftyYears() {
    // almost certainly a programming error
    assertInvalid(Duration.ZERO);
    assertInvalid(Duration.ofSeconds(-1));

    // we're calling this invalid due to the history of JCBC-1645
    assertInvalid(Duration.ofDays(365 * 50).plusSeconds(1));
  }

  @Test
  void relativeThrowsIfPastEndOfTime() {
    // As we get closer to the end of time, it will be possible for a duration
    // less than 50 years to still be too long because it ends after 2106-02-07T06:28:15Z
    long currentTimeMillis = 4294967295L * 1000;
    assertThrows(InvalidArgumentException.class, () -> Expiry.relative(Duration.ofDays(31))
        .encode(() -> currentTimeMillis));
  }

  @Test
  void theEndIsNotNigh() {
    if (LocalDate.now().getYear() >= 2067) {
      fail("If we're still using an unsigned 32-bit int for the expiry field, then" +
          " the maximum expiry instant is <= 30 years in the future." +
          " It might be time to start thinking about widening the field.");
    }
  }

  private static void assertInvalid(Instant expiry) {
    assertThrows(InvalidArgumentException.class, () -> Expiry.absolute(expiry));
  }

  private static void assertInvalid(Duration expiry) {
    assertThrows(InvalidArgumentException.class, () -> Expiry.relative(expiry));
  }

  private static void assertValid(Instant expiry) {
    long result = Expiry.absolute(expiry).encode();
    assertEquals(expiry.getEpochSecond(), result);
  }

  private static void assertEncodedAsSecondsFromNow(Duration expiry) {
    long result = Expiry.relative(expiry).encode();
    assertEquals(expiry.getSeconds(), result);
  }

  private static void assertEncodedAsEpochSecond(Duration expiry) {
    long currentTimeMillis = 1000L;
    long result = Expiry.relative(expiry).encode(() -> currentTimeMillis);
    assertEquals(1 + expiry.getSeconds(), result);
  }
}
