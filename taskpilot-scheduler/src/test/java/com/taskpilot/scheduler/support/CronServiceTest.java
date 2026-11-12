package com.taskpilot.scheduler.support;

import com.taskpilot.scheduler.dto.CronValidateResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CronServiceTest {

    private final CronService cronService = new CronService();

    @Test
    void validExpressionReturnsFiveIncreasingTimes() {
        CronValidateResult result = cronService.validate("*/30 * * * * *");

        assertThat(result.valid()).isTrue();
        assertThat(result.nextTimes()).hasSize(5);
        LocalDateTime previous = null;
        for (LocalDateTime next : result.nextTimes()) {
            assertThat(next).isNotNull();
            if (previous != null) {
                assertThat(next).isAfter(previous);
            }
            previous = next;
        }
    }

    @Test
    void invalidExpressionReturnsMessageWithoutThrowing() {
        CronValidateResult result = cronService.validate("not-a-cron");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isNotBlank();
        assertThat(result.nextTimes()).isEmpty();
    }

    @Test
    void nextTimeAfterReturnsFutureTime() {
        LocalDateTime from = LocalDateTime.of(2026, 11, 12, 10, 0, 5);

        LocalDateTime next = cronService.nextTimeAfter("*/10 * * * * *", from);

        assertThat(next).isEqualTo(LocalDateTime.of(2026, 11, 12, 10, 0, 10));
    }

    @Test
    void nextTimeAfterReturnsNullForInvalidExpression() {
        assertThat(cronService.nextTimeAfter("not-a-cron", LocalDateTime.now())).isNull();
    }
}
