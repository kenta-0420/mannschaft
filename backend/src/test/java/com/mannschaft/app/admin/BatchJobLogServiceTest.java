package com.mannschaft.app.admin;

import com.mannschaft.app.admin.dto.BatchJobLogResponse;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.repository.BatchJobLogRepository;
import com.mannschaft.app.admin.service.BatchJobLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link BatchJobLogService} の単体テスト。
 * ジョブログの記録・取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BatchJobLogService 単体テスト")
class BatchJobLogServiceTest {

    @Mock
    private BatchJobLogRepository batchJobLogRepository;

    @Mock
    private AdminMapper adminMapper;

    @InjectMocks
    private BatchJobLogService service;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final String JOB_NAME = "dailyCleanupJob";

    private BatchJobLogEntity createRunningJobLog() {
        return BatchJobLogEntity.builder()
                .jobName(JOB_NAME)
                .status(BatchJobStatus.RUNNING)
                .startedAt(LocalDateTime.now())
                .build();
    }

    private BatchJobLogResponse createJobLogResponse() {
        return new BatchJobLogResponse(
                1L, JOB_NAME, "RUNNING", LocalDateTime.now(), null, 0, null, LocalDateTime.now());
    }

    // ========================================
    // getLogs
    // ========================================

    @Nested
    @DisplayName("getLogs")
    class GetLogs {

        @Test
        @DisplayName("正常系: ページネーション付きでログ一覧が返却される")
        void 取得_ページ指定_一覧返却() {
            // Given
            List<BatchJobLogEntity> entities = List.of(createRunningJobLog());
            List<BatchJobLogResponse> responses = List.of(createJobLogResponse());
            Page<BatchJobLogEntity> page = new PageImpl<>(entities);

            given(batchJobLogRepository.findAllByOrderByStartedAtDesc(PageRequest.of(0, 10))).willReturn(page);
            given(adminMapper.toBatchJobLogResponseList(entities)).willReturn(responses);

            // When
            List<BatchJobLogResponse> result = service.getLogs(0, 10);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getJobName()).isEqualTo(JOB_NAME);
        }
    }

    // ========================================
    // getLogsByJobName
    // ========================================

    @Nested
    @DisplayName("getLogsByJobName")
    class GetLogsByJobName {

        @Test
        @DisplayName("正常系: ジョブ名でフィルタされたログが返却される")
        void 取得_ジョブ名指定_フィルタ結果() {
            // Given
            List<BatchJobLogEntity> entities = List.of(createRunningJobLog());
            List<BatchJobLogResponse> responses = List.of(createJobLogResponse());
            given(batchJobLogRepository.findByJobNameOrderByStartedAtDesc(JOB_NAME)).willReturn(entities);
            given(adminMapper.toBatchJobLogResponseList(entities)).willReturn(responses);

            // When
            List<BatchJobLogResponse> result = service.getLogsByJobName(JOB_NAME);

            // Then
            assertThat(result).hasSize(1);
            verify(batchJobLogRepository).findByJobNameOrderByStartedAtDesc(JOB_NAME);
        }

        @Test
        @DisplayName("正常系: 該当ジョブなしの場合空リストが返却される")
        void 取得_該当なし_空リスト() {
            // Given
            given(batchJobLogRepository.findByJobNameOrderByStartedAtDesc("nonExistent")).willReturn(List.of());
            given(adminMapper.toBatchJobLogResponseList(List.of())).willReturn(List.of());

            // When
            List<BatchJobLogResponse> result = service.getLogsByJobName("nonExistent");

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // startJob
    // ========================================

    @Nested
    @DisplayName("startJob")
    class StartJob {

        @Test
        @DisplayName("正常系: ジョブログがRUNNINGステータスで作成される")
        void 開始_正常_RUNNINGステータス() {
            // Given
            BatchJobLogEntity savedEntity = createRunningJobLog();
            given(batchJobLogRepository.save(any(BatchJobLogEntity.class))).willReturn(savedEntity);

            // When
            BatchJobLogEntity result = service.startJob(JOB_NAME);

            // Then
            assertThat(result.getJobName()).isEqualTo(JOB_NAME);
            assertThat(result.getStatus()).isEqualTo(BatchJobStatus.RUNNING);
            verify(batchJobLogRepository).save(any(BatchJobLogEntity.class));
        }
    }

    // ========================================
    // completeJob
    // ========================================

    @Nested
    @DisplayName("completeJob")
    class CompleteJob {

        @Test
        @DisplayName("正常系: ジョブログがSUCCESSステータスに更新される")
        void 完了_正常_SUCCESSステータス() {
            // Given
            BatchJobLogEntity logEntity = createRunningJobLog();

            // When
            service.completeJob(logEntity, 42);

            // Then
            assertThat(logEntity.getStatus()).isEqualTo(BatchJobStatus.SUCCESS);
            assertThat(logEntity.getProcessedCount()).isEqualTo(42);
            assertThat(logEntity.getFinishedAt()).isNotNull();
            verify(batchJobLogRepository).save(logEntity);
        }
    }

    // ========================================
    // failJob
    // ========================================

    @Nested
    @DisplayName("failJob")
    class FailJob {

        @Test
        @DisplayName("正常系: ジョブログがFAILEDステータスに更新される")
        void 失敗_正常_FAILEDステータス() {
            // Given
            BatchJobLogEntity logEntity = createRunningJobLog();
            String errorMessage = "NullPointerException at line 42";

            // When
            service.failJob(logEntity, errorMessage);

            // Then
            assertThat(logEntity.getStatus()).isEqualTo(BatchJobStatus.FAILED);
            assertThat(logEntity.getErrorMessage()).isEqualTo(errorMessage);
            assertThat(logEntity.getFinishedAt()).isNotNull();
            verify(batchJobLogRepository).save(logEntity);
        }
    }
}
