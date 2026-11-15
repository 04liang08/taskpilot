package com.taskpilot.scheduler.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.taskpilot.common.api.PageResult;
import com.taskpilot.common.enums.ExecutorStatus;
import com.taskpilot.common.exception.BizException;
import com.taskpilot.common.exception.ErrorCode;
import com.taskpilot.scheduler.dto.ExecutorHeartbeatRequest;
import com.taskpilot.scheduler.dto.ExecutorQuery;
import com.taskpilot.scheduler.dto.ExecutorRegisterRequest;
import com.taskpilot.scheduler.entity.ExecutorEntity;
import com.taskpilot.scheduler.executor.ExecutorStatusChangedEvent;
import com.taskpilot.scheduler.mapper.ExecutorMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行器注册表：注册 upsert、心跳续约、超时下线。
 * <p>第 1 周只维护 ONLINE/OFFLINE；第 3 周的分片与失败转移会复用这张表。</p>
 */
@Service
public class ExecutorService {

    private static final String DEFAULT_GROUP = "DEFAULT";

    private final ExecutorMapper executorMapper;
    private final ApplicationEventPublisher eventPublisher;

    public ExecutorService(ExecutorMapper executorMapper, ApplicationEventPublisher eventPublisher) {
        this.executorMapper = executorMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 注册按 (app_name, address) 幂等：重复启动只刷新心跳与分组。
     */
    public ExecutorEntity register(ExecutorRegisterRequest request) {
        String appName = request.getAppName().trim();
        String address = request.getAddress().trim();
        String group = StringUtils.hasText(request.getExecutorGroup())
                ? request.getExecutorGroup().trim() : DEFAULT_GROUP;
        LocalDateTime now = LocalDateTime.now();

        ExecutorEntity existing = findByAddress(appName, address);
        if (existing != null) {
            boolean wasOffline = existing.getStatus() == ExecutorStatus.OFFLINE;
            existing.setExecutorGroup(group);
            existing.setStatus(ExecutorStatus.ONLINE);
            existing.setLastHeartbeatTime(now);
            executorMapper.updateById(existing);
            ExecutorEntity updated = get(existing.getId());
            if (wasOffline) {
                publishStatusChanged(updated, true);
            }
            return updated;
        }

        ExecutorEntity entity = new ExecutorEntity();
        entity.setAppName(appName);
        entity.setExecutorGroup(group);
        entity.setAddress(address);
        entity.setStatus(ExecutorStatus.ONLINE);
        entity.setLastHeartbeatTime(now);
        try {
            executorMapper.insert(entity);
            ExecutorEntity created = get(entity.getId());
            publishStatusChanged(created, true);
            return created;
        } catch (DuplicateKeyException e) {
            // 并发注册撞唯一键：退化为心跳更新，保证接口幂等。
            ExecutorHeartbeatRequest heartbeat = new ExecutorHeartbeatRequest();
            heartbeat.setAppName(appName);
            heartbeat.setAddress(address);
            return heartbeat(heartbeat);
        }
    }

    /**
     * 心跳顺便把执行器拉回 ONLINE。若记录不存在（例如库被重置），按默认分组补注册。
     */
    public ExecutorEntity heartbeat(ExecutorHeartbeatRequest request) {
        String appName = request.getAppName().trim();
        String address = request.getAddress().trim();
        ExecutorEntity existing = findByAddress(appName, address);
        if (existing == null) {
            ExecutorRegisterRequest register = new ExecutorRegisterRequest();
            register.setAppName(appName);
            register.setExecutorGroup(DEFAULT_GROUP);
            register.setAddress(address);
            return register(register);
        }

        LocalDateTime now = LocalDateTime.now();
        boolean wasOffline = existing.getStatus() == ExecutorStatus.OFFLINE;
        executorMapper.update(null, Wrappers.<ExecutorEntity>lambdaUpdate()
                .eq(ExecutorEntity::getId, existing.getId())
                .set(ExecutorEntity::getStatus, ExecutorStatus.ONLINE)
                .set(ExecutorEntity::getLastHeartbeatTime, now));
        ExecutorEntity updated = get(existing.getId());
        if (wasOffline) {
            // 从离线恢复：下线期间分派到它上面的分片需要重新分配（Week 3 Day 3）。
            publishStatusChanged(updated, true);
        }
        return updated;
    }

    public ExecutorEntity get(Long id) {
        ExecutorEntity entity = executorMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "执行器不存在: " + id);
        }
        return entity;
    }

    public PageResult<ExecutorEntity> page(ExecutorQuery query) {
        Page<ExecutorEntity> page = new Page<>(query.getPage(), query.getSize());
        LambdaQueryWrapper<ExecutorEntity> wrapper = Wrappers.<ExecutorEntity>lambdaQuery()
                .orderByDesc(ExecutorEntity::getId);
        if (query.getStatus() != null) {
            wrapper.eq(ExecutorEntity::getStatus, query.getStatus());
        }
        if (StringUtils.hasText(query.getGroup())) {
            wrapper.eq(ExecutorEntity::getExecutorGroup, query.getGroup().trim());
        }

        Page<ExecutorEntity> result = executorMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 在线执行器列表，供第 5 周手动触发选择目标使用。
     */
    public List<ExecutorEntity> listOnline() {
        return executorMapper.selectList(Wrappers.<ExecutorEntity>lambdaQuery()
                .eq(ExecutorEntity::getStatus, ExecutorStatus.ONLINE)
                .orderByAsc(ExecutorEntity::getId));
    }

    /**
     * 将最后心跳早于 deadline 的在线执行器标记为 OFFLINE，并发布下线事件。
     *
     * @return 本次下线的执行器数量
     */
    public int markStaleOffline(long timeoutSeconds) {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(timeoutSeconds);
        // 先查出会下线的执行器：一条 UPDATE 拿不到具体是哪几个，事件需要具体信息。
        List<ExecutorEntity> stale = executorMapper.selectList(Wrappers.<ExecutorEntity>lambdaQuery()
                .eq(ExecutorEntity::getStatus, ExecutorStatus.ONLINE)
                .lt(ExecutorEntity::getLastHeartbeatTime, deadline));
        if (stale.isEmpty()) {
            return 0;
        }
        int updated = executorMapper.update(null, Wrappers.<ExecutorEntity>lambdaUpdate()
                .eq(ExecutorEntity::getStatus, ExecutorStatus.ONLINE)
                .lt(ExecutorEntity::getLastHeartbeatTime, deadline)
                .set(ExecutorEntity::getStatus, ExecutorStatus.OFFLINE));
        for (ExecutorEntity executor : stale) {
            publishStatusChanged(executor, false);
        }
        return updated;
    }

    private void publishStatusChanged(ExecutorEntity executor, boolean online) {
        eventPublisher.publishEvent(
                new ExecutorStatusChangedEvent(executor.getId(), executor.getAddress(), online));
    }

    private ExecutorEntity findByAddress(String appName, String address) {
        return executorMapper.selectOne(Wrappers.<ExecutorEntity>lambdaQuery()
                .eq(ExecutorEntity::getAppName, appName)
                .eq(ExecutorEntity::getAddress, address));
    }
}
