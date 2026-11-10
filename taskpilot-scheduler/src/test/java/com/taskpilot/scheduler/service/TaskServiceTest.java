package com.taskpilot.scheduler.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.common.exception.BizException;
import com.taskpilot.scheduler.dto.CronValidateResult;
import com.taskpilot.scheduler.dto.TaskCreateRequest;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.mapper.TaskMapper;
import com.taskpilot.scheduler.support.CronService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private CronService cronService;

    @InjectMocks
    private TaskService taskService;

    @BeforeEach
    void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TaskEntity.class);
    }

    @Test
    void createAppliesDefaultsAndEnablesTask() {
        when(cronService.validate(anyString())).thenReturn(
                CronValidateResult.valid(List.of(LocalDateTime.now().plusSeconds(30))));
        when(taskMapper.selectCount(any())).thenReturn(0L);
        AtomicReference<TaskEntity> saved = new AtomicReference<>();
        when(taskMapper.insert(any(TaskEntity.class))).thenAnswer(invocation -> {
            TaskEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            saved.set(entity);
            return 1;
        });
        when(taskMapper.selectById(1L)).thenAnswer(invocation -> saved.get());

        TaskEntity created = taskService.create(createRequest("demo-task"));

        assertThat(created.getId()).isEqualTo(1L);
        assertThat(created.getTaskGroup()).isEqualTo("DEFAULT");
        assertThat(created.getTriggerType()).isEqualTo(TriggerType.CRON);
        assertThat(created.getStatus()).isEqualTo(TaskStatus.ENABLED);
        assertThat(created.getShardTotal()).isEqualTo(1);
        assertThat(created.getTimeoutSeconds()).isEqualTo(60);
        assertThat(created.getRetryMax()).isZero();
        verify(taskMapper).insert(any(TaskEntity.class));
    }

    @Test
    void createRejectsDuplicateTaskName() {
        when(cronService.validate(anyString())).thenReturn(
                CronValidateResult.valid(List.of(LocalDateTime.now().plusSeconds(30))));
        when(taskMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> taskService.create(createRequest("demo-task")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("任务名称已存在");

        verify(taskMapper, never()).insert(any(TaskEntity.class));
    }

    private TaskCreateRequest createRequest(String taskName) {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setTaskName(taskName);
        request.setCronExpression("*/30 * * * * *");
        request.setHandlerName("demoHandler");
        request.setHandlerParam("{\"message\":\"hi\"}");
        return request;
    }
}
