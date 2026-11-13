package com.taskpilot.scheduler.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.enums.ExecutorStatus;
import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.scheduler.dto.DashboardSummary;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.entity.ExecutorEntity;
import com.taskpilot.scheduler.entity.LeaderEntity;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.leader.LeaderStateService;
import com.taskpilot.scheduler.mapper.ExecutionLogMapper;
import com.taskpilot.scheduler.mapper.ExecutorMapper;
import com.taskpilot.scheduler.mapper.TaskMapper;
import com.taskpilot.scheduler.support.CronService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class DashboardService {

    private final TaskMapper taskMapper;
    private final ExecutorMapper executorMapper;
    private final ExecutionLogMapper executionLogMapper;
    private final LeaderStateService leaderStateService;

    public DashboardService(TaskMapper taskMapper,
                            ExecutorMapper executorMapper,
                            ExecutionLogMapper executionLogMapper,
                            LeaderStateService leaderStateService) {
        this.taskMapper = taskMapper;
        this.executorMapper = executorMapper;
        this.executionLogMapper = executionLogMapper;
        this.leaderStateService = leaderStateService;
    }

    public DashboardSummary summary() {
        LocalDateTime todayStart = LocalDate.now(CronService.ZONE).atStartOfDay();

        long taskCount = taskMapper.selectCount(Wrappers.<TaskEntity>lambdaQuery());
        long enabledTaskCount = taskMapper.selectCount(Wrappers.<TaskEntity>lambdaQuery()
                .eq(TaskEntity::getStatus, TaskStatus.ENABLED));
        long onlineExecutorCount = executorMapper.selectCount(Wrappers.<ExecutorEntity>lambdaQuery()
                .eq(ExecutorEntity::getStatus, ExecutorStatus.ONLINE));

        long todaySuccess = executionLogMapper.selectCount(Wrappers.<ExecutionLogEntity>lambdaQuery()
                .eq(ExecutionLogEntity::getStatus, ExecutionStatus.SUCCESS)
                .ge(ExecutionLogEntity::getCreateTime, todayStart));
        // 超时也计入失败口径，便于概览上直接看到异常。
        long todayFailed = executionLogMapper.selectCount(Wrappers.<ExecutionLogEntity>lambdaQuery()
                .in(ExecutionLogEntity::getStatus, ExecutionStatus.FAILED, ExecutionStatus.TIMEOUT)
                .ge(ExecutionLogEntity::getCreateTime, todayStart));
        long runningCount = executionLogMapper.selectCount(Wrappers.<ExecutionLogEntity>lambdaQuery()
                .eq(ExecutionLogEntity::getStatus, ExecutionStatus.RUNNING));

        // 当前 leader 来自共享的 tp_leader，任何实例读到的都是集群视角。
        LeaderEntity leader = leaderStateService.currentLeader();
        String leaderInstance = leader == null ? null : leader.getInstanceId();
        LocalDateTime leaderSince = leader == null ? null : leader.getLeaderTime();

        return new DashboardSummary(taskCount, enabledTaskCount, onlineExecutorCount,
                todaySuccess, todayFailed, runningCount, leaderInstance, leaderSince);
    }
}