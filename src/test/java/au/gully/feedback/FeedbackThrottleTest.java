package au.gully.feedback;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Five sends in fifteen minutes from one address; the sixth is refused until the first is fifteen minutes old.
 */
class FeedbackThrottleTest {

    @Test
    void theSixthInFifteenMinutesIsRefusedAndAnotherAddressIsNotTouched() {
        MovingClock clock = new MovingClock(Instant.parse("2026-10-03T00:00:00Z"));
        FeedbackThrottle throttle = new FeedbackThrottle(clock);
        for (int i = 0; i < FeedbackThrottle.SENDS; i++) {
            assertThat(throttle.take("203.0.113.7")).as("send " + (i + 1)).isTrue();
            clock.advance(Duration.ofMinutes(1));
        }
        assertThat(throttle.take("203.0.113.7")).as("the sixth").isFalse();
        assertThat(throttle.take("203.0.113.7")).as("and again").isFalse();
        assertThat(throttle.take("198.51.100.4")).as("another address").isTrue();

        // The first was at 00:00; at 00:15 it is out of the window and one more may go, and only one.
        clock.set(Instant.parse("2026-10-03T00:15:00Z"));
        assertThat(throttle.take("203.0.113.7")).isTrue();
        assertThat(throttle.take("203.0.113.7")).isFalse();
        // A quarter of an hour after the last, the allowance is whole again.
        clock.set(Instant.parse("2026-10-03T00:30:00Z"));
        for (int i = 0; i < FeedbackThrottle.SENDS; i++) {
            assertThat(throttle.take("203.0.113.7")).isTrue();
        }
        assertThat(throttle.take("203.0.113.7")).isFalse();
    }

    @Test
    void anUnknownAddressIsAnAddressLikeAnyOther() {
        FeedbackThrottle throttle = new FeedbackThrottle(new MovingClock(Instant.parse("2026-10-03T00:00:00Z")));
        for (int i = 0; i < FeedbackThrottle.SENDS; i++) {
            assertThat(throttle.take(null)).isTrue();
        }
        assertThat(throttle.take(null)).isFalse();
    }

    private static final class MovingClock extends Clock {

        private Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        void set(Instant at) {
            now = at;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
