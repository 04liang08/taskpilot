package com.taskpilot.scheduler.support;

import com.taskpilot.scheduler.dto.CronValidateResult;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * cron 解析与预览。统一使用 Asia/Shanghai，Spring 6 段格式（秒 分 时 日 月 周）。
 */
@Service
public class CronService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int PREVIEW_COUNT = 5;

    public CronValidateResult validate(String expression) {
        try {
            CronExpression cron = CronExpression.parse(expression);
            List<LocalDateTime> nextTimes = new ArrayList<>(PREVIEW_COUNT);
            LocalDateTime cursor = LocalDateTime.now(ZONE);
            for (int i = 0; i < PREVIEW_COUNT; i++) {
                cursor = cron.next(cursor);
                if (cursor == null) {
                    break;
                }
                nextTimes.add(cursor);
            }
            if (nextTimes.isEmpty()) {
                return CronValidateResult.invalid("cron 表达式永远不会触发");
            }
            return CronValidateResult.valid(nextTimes);
        } catch (IllegalArgumentException e) {
            return CronValidateResult.invalid("cron 表达式不合法（需要 6 段：秒 分 时 日 月 周）");
        }
    }

    /**
     * 计算 from 之后的下一个触发时间。cron 非法或不会再触发时返回 null。
     * <p>时间轮推进 next_trigger_time 用这个方法，保证节奏和 cron 定义一致。</p>
     */
    public LocalDateTime nextTimeAfter(String expression, LocalDateTime from) {
        try {
            return CronExpression.parse(expression).next(from);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}