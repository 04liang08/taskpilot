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
import com.taskpilot.scheduler.mapper.ExecutorMapper;
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

    public ExecutorService(ExecutorMapper executorMapper) {
        this.executorMapper = executorMapper;
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
            existing.setExecutorGroup(group);
            existing.setStatus(ExecutorStatus.ONLINE);
            existing.setLastHeartbeatTime(now);
            executorMapper.updateById(existing);
            return get(existing.getId());
        }

        ExecutorEntity entity = new ExecutorEntity();
        entity.setAppName(appName);
        entity.setExecutorGroup(group);
        entity.setAddress(address);
        entity.setStatus(ExecutorStatus.ONLINE);
        entity.setLastHeartbeatTime(now);
        try {
            executorMapper.insert(entity);
            return get(entity.getId());
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
        executorMapper.update(null, Wrappers.<ExecutorEntity>lambdaUpdate()
                .eq(ExecutorEntity::getId, existing.getId())
                .set(ExecutorEntity::getStatus, ExecutorStatus.ONLINE)
                .set(ExecutorEntity::getLastHeartbeatTime, now));
        return get(existing.getId());
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
     * 将最后心跳早于 deadline 的在线执行器标记为 OFFLINE。
     *
     * @return 本次下线的执行器数量
     */
    public int markStaleOffline(long timeoutSeconds) {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(timeoutSeconds);
        return executorMapper.update(null, Wrappers.<ExecutorEntity>lambdaUpdate()
                .eq(ExecutorEntity::getStatus, ExecutorStatus.ONLINE)
                .lt(ExecutorEntity::getLastHeartbeatTime, deadline)
                .set(ExecutorEntity::getStatus, ExecutorStatus.OFFLINE));
    }

    private ExecutorEntity findByAddress(String appName, String address) {
        return executorMapper.selectOne(Wrappers.<ExecutorEntity>lambdaQuery()
                .eq(ExecutorEntity::getAppName, appName)
                .eq(ExecutorEntity::getAddress, address));
    }
}
