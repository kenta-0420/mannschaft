package com.mannschaft.app.files.service;

import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.files.entity.MultipartAbortCleanupEntity;
import com.mannschaft.app.files.repository.MultipartAbortCleanupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class MultipartUploadCleanupServiceTest {
    @Mock MultipartAbortCleanupRepository repository;
    @Mock R2StorageService storage;
    private MultipartUploadCleanupService service;

    @BeforeEach
    void setUp() {
        service = new MultipartUploadCleanupService(repository, storage);
        ReflectionTestUtils.setField(service, "maxAttempts", 2);
        ReflectionTestUtils.setField(service, "retentionDays", 30);
    }

    @Test
    void 設定値が不正なら起動時に拒否する() {
        ReflectionTestUtils.setField(service, "maxAttempts", 0);
        assertThatThrownBy(() -> service.validateConfiguration()).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(service, "maxAttempts", 2);
        ReflectionTestUtils.setField(service, "retentionDays", 0);
        assertThatThrownBy(() -> service.validateConfiguration()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void abort成功とNoSuchUploadは削除する() {
        MultipartAbortCleanupEntity item = item(0);
        given(repository.findByStatusAndNextAttemptAtBefore(eq("ABORT_PENDING"), any())).willReturn(List.of(item));
        given(repository.claim(any(), any(), any())).willReturn(1);
        org.mockito.Mockito.doThrow(NoSuchUploadException.builder().message("missing").build()).when(storage)
                .abortMultipartUpload(any(), any());
        int succeeded = service.retryPendingAborts(LocalDateTime.now());
        assertThat(succeeded).isEqualTo(1);
        verify(repository).delete(item);
    }

    @Test
    void claim競合時はR2を呼び出さない() {
        MultipartAbortCleanupEntity item = item(0);
        given(repository.findByStatusAndNextAttemptAtBefore(eq("ABORT_PENDING"), any())).willReturn(List.of(item));
        given(repository.claim(any(), any(), any())).willReturn(0);
        service.retryPendingAborts(LocalDateTime.now());
        verify(storage, never()).abortMultipartUpload(any(), any());
    }

    @Test
    void lease期限境界は期限以下の一括更新条件を使う() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 29, 12, 0);
        service.retryPendingAborts(now);
        verify(repository).releaseExpiredClaims(eq(now));
    }

    @Test
    void lease回収後は再claimしてabortできる() {
        MultipartAbortCleanupEntity item = item(0);
        LocalDateTime now = LocalDateTime.of(2026, 8, 29, 12, 0);
        given(repository.findByStatusAndNextAttemptAtBefore(eq("ABORT_PENDING"), eq(now))).willReturn(List.of(item));
        given(repository.claim(any(), eq(now), eq(now.plusMinutes(10)))).willReturn(1);
        assertThat(service.retryPendingAborts(now)).isEqualTo(1);
        verify(storage).abortMultipartUpload("k", "u");
    }

    @Test
    void 通常失敗は再試行状態へ戻す() {
        MultipartAbortCleanupEntity item = item(0);
        given(repository.findByStatusAndNextAttemptAtBefore(eq("ABORT_PENDING"), any())).willReturn(List.of(item));
        given(repository.claim(any(), any(), any())).willReturn(1);
        org.mockito.Mockito.doThrow(new RuntimeException("failed")).when(storage)
                .abortMultipartUpload(any(), any());
        service.retryPendingAborts(LocalDateTime.now());
        verify(repository).save(any(MultipartAbortCleanupEntity.class));
    }

    @Test
    void 最大試行回数到達時はdeadLetter保持し期限超過分を削除する() {
        MultipartAbortCleanupEntity item = item(2);
        given(repository.findByStatusAndNextAttemptAtBefore(eq("ABORT_PENDING"), any())).willReturn(List.of(item));
        service.retryPendingAborts(LocalDateTime.now());
        verify(repository).save(any(MultipartAbortCleanupEntity.class));
        verify(repository).findByStatusAndDeadLetteredAtBefore(eq("DEAD_LETTER"), any());
    }

    @Test
    void deadLetter保持期限の基準時刻を渡す() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 29, 12, 0);
        given(repository.findByStatusAndNextAttemptAtBefore(any(), any())).willReturn(List.of());
        service.retryPendingAborts(now);
        verify(repository).findByStatusAndDeadLetteredAtBefore("DEAD_LETTER", now.minusDays(30));
    }

    private MultipartAbortCleanupEntity item(int attempts) {
        return MultipartAbortCleanupEntity.builder().uploadId("u").r2Key("k").ownerId(1L)
                .contentType("video/mp4").feature("files").scopeType("PERSONAL").scopeId(1L)
                .status("ABORT_PENDING").nextAttemptAt(LocalDateTime.now()).attemptCount(attempts).build();
    }
}
