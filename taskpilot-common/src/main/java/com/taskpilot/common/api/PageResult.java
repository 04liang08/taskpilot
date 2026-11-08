package com.taskpilot.common.api;

import java.util.List;

/**
 * 分页结果。字段名与 MyBatis-Plus IPage 对齐（records/total/current/size 的对外映射）。
 */
public record PageResult<T>(List<T> records, long total, long page, long size) {

    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        return new PageResult<>(records, total, page, size);
    }

    public static <T> PageResult<T> empty(long page, long size) {
        return new PageResult<>(List.of(), 0, page, size);
    }
}