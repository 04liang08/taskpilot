package com.taskpilot.scheduler.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 统一填充 create_time / update_time，避免依赖数据库默认值导致实体回读不一致。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // strictUpdateFill 只在字段为 null 时填充；更新场景必须强制覆盖，否则会沿用旧值。
        setFieldValByName("updateTime", LocalDateTime.now(), metaObject);
    }
}