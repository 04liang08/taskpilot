package com.taskpilot.scheduler.service;

import com.taskpilot.common.enums.ExecutorStatus;
import com.taskpilot.scheduler.dto.ExecutorRegisterRequest;
import com.taskpilot.scheduler.entity.ExecutorEntity;
import com.taskpilot.scheduler.executor.ExecutorStatusChangedEvent;
import com.taskpilot.scheduler.mapper.ExecutorMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutorServiceTest {

    @Mock
    private ExecutorMapper executorMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ExecutorService executorService;

    @BeforeEach
    void initTableInfo() {
        // 纯 Mockito 单测没有 MyBatis 会话，手动初始化 Lambda 字段缓存。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExecutorEntity.class);
    }

    @Test
    void registerInsertsNewExecutorAsOnline() {
        when(executorMapper.selectOne(any())).thenReturn(null);
        when(executorMapper.insert(any(ExecutorEntity.class))).thenAnswer(invocation -> {
            ((ExecutorEntity) invocation.getArgument(0)).setId(1L);
            return 1;
        });
        when(executorMapper.selectById(1L)).thenReturn(onlineExecutor(1L));

        ExecutorRegisterRequest request = new ExecutorRegisterRequest();
        request.setAppName("taskpilot-executor");
        request.setExecutorGroup("DEFAULT");
        request.setAddress("127.0.0.1:8081");

        ExecutorEntity result = executorService.register(request);

        assertThat(result.getStatus()).isEqualTo(ExecutorStatus.ONLINE);
        assertThat(result.getAddress()).isEqualTo("127.0.0.1:8081");
        ArgumentCaptor<ExecutorEntity> captor = ArgumentCaptor.forClass(ExecutorEntity.class);
        verify(executorMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ExecutorStatus.ONLINE);
        assertThat(captor.getValue().getLastHeartbeatTime()).isNotNull();
    }

    @Test
    void registerExistingExecutorRefreshesHeartbeat() {
        ExecutorEntity existing = onlineExecutor(2L);
        existing.setStatus(ExecutorStatus.OFFLINE);
        existing.setLastHeartbeatTime(LocalDateTime.now().minusMinutes(5));
        when(executorMapper.selectOne(any())).thenReturn(existing);
        when(executorMapper.updateById(any(ExecutorEntity.class))).thenReturn(1);
        when(executorMapper.selectById(2L)).thenReturn(onlineExecutor(2L));

        ExecutorRegisterRequest request = new ExecutorRegisterRequest();
        request.setAppName("taskpilot-executor");
        request.setAddress("127.0.0.1:8081");

        ExecutorEntity result = executorService.register(request);

        assertThat(result.getStatus()).isEqualTo(ExecutorStatus.ONLINE);
        ArgumentCaptor<ExecutorEntity> captor = ArgumentCaptor.forClass(ExecutorEntity.class);
        verify(executorMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ExecutorStatus.ONLINE);
        assertThat(captor.getValue().getLastHeartbeatTime()).isNotNull();
    }

    @Test
    void markStaleOfflineReturnsUpdatedRows() {
        ExecutorEntity stale = onlineExecutor(3L);
        when(executorMapper.selectList(any())).thenReturn(List.of(stale));
        when(executorMapper.update(isNull(), any())).thenReturn(3);

        int count = executorService.markStaleOffline(30);

        assertThat(count).isEqualTo(3);
        // 下线要发事件：Day 3 的分片重分配靠它触发。
        verify(eventPublisher).publishEvent(any(ExecutorStatusChangedEvent.class));
    }

    private ExecutorEntity onlineExecutor(Long id) {
        ExecutorEntity entity = new ExecutorEntity();
        entity.setId(id);
        entity.setAppName("taskpilot-executor");
        entity.setExecutorGroup("DEFAULT");
        entity.setAddress("127.0.0.1:8081");
        entity.setStatus(ExecutorStatus.ONLINE);
        entity.setLastHeartbeatTime(LocalDateTime.now());
        return entity;
    }
}
