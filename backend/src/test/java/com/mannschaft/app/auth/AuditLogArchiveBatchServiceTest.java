package com.mannschaft.app.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.AuditLogEntity;
import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.auth.service.AuditLogArchiveBatchService;
import com.mannschaft.app.common.storage.StorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogArchiveBatchService 単体テスト")
class AuditLogArchiveBatchServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AuditLogArchiveBatchService sut;

    // ─────────────────────────────────────────────
    // archiveOldLogs
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("archiveOldLogs")
    class ArchiveOldLogs {

        @Test
        @DisplayName("対象なし_何もしない")
        void 対象なし_何もしない() {
            // given
            when(auditLogRepository.findOlderThan(any(), any()))
                    .thenReturn(new SliceImpl<>(List.of(), Pageable.unpaged(), false));

            // when
            sut.archiveOldLogs();

            // then
            verify(storageService, never()).upload(any(), any(byte[].class), any());
            verify(auditLogRepository, never()).deleteArchivedLogs(any(), any());
            verify(jdbcTemplate, never()).execute(any(String.class));
        }

        @Test
        @DisplayName("対象あり_R2にアップロードしてパーティションDROPされる")
        void 対象あり_R2にアップロードしてパーティションDROPされる() {
            // given
            AuditLogEntity log1 = createAuditLog(1L, LocalDateTime.of(2024, 1, 15, 10, 0));
            AuditLogEntity log2 = createAuditLog(2L, LocalDateTime.of(2024, 1, 20, 12, 0));
            AuditLogEntity log3 = createAuditLog(3L, LocalDateTime.of(2024, 2, 5, 8, 0));

            when(auditLogRepository.findOlderThan(any(), any()))
                    .thenReturn(new SliceImpl<>(List.of(log1, log2, log3), Pageable.unpaged(), false));

            // when
            sut.archiveOldLogs();

            // then: 2024-01 と 2024-02 の2つのオブジェクトがR2にアップロードされる
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(storageService, org.mockito.Mockito.times(2))
                    .upload(keyCaptor.capture(), any(byte[].class), eq("application/json"));

            List<String> uploadedKeys = keyCaptor.getAllValues();
            assertThat(uploadedKeys).containsExactlyInAnyOrder(
                    "audit-archive/2024/01/audit-2024-01.json",
                    "audit-archive/2024/02/audit-2024-02.json"
            );

            // パーティション DROP が月ごとに呼ばれる
            verify(jdbcTemplate).execute(contains("p_2024_01"));
            verify(jdbcTemplate).execute(contains("p_2024_02"));
        }

        @Test
        @DisplayName("R2アップロード失敗_DB削除を実行しない")
        void R2アップロード失敗_DB削除を実行しない() {
            // given
            AuditLogEntity log1 = createAuditLog(1L, LocalDateTime.of(2024, 1, 15, 10, 0));

            when(auditLogRepository.findOlderThan(any(), any()))
                    .thenReturn(new SliceImpl<>(List.of(log1), Pageable.unpaged(), false));
            org.mockito.Mockito.doThrow(new RuntimeException("R2接続失敗"))
                    .when(storageService).upload(any(), any(byte[].class), any());

            // when: 例外はキャッチされてバッチが完了する（ログのみ）
            sut.archiveOldLogs();

            // then: パーティション DROP は実行されない
            verify(auditLogRepository, never()).deleteArchivedLogs(any(), any());
            verify(jdbcTemplate, never()).execute(any(String.class));
        }
    }

    // ─────────────────────────────────────────────
    // buildR2Key
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("buildR2Key")
    class BuildR2Key {

        @Test
        @DisplayName("年月からR2キーが正しく生成される_1桁月")
        void 年月からR2キーが正しく生成される_1桁月() {
            String key = AuditLogArchiveBatchService.buildR2Key(YearMonth.of(2024, 3));
            assertThat(key).isEqualTo("audit-archive/2024/03/audit-2024-03.json");
        }

        @Test
        @DisplayName("年月からR2キーが正しく生成される_2桁月")
        void 年月からR2キーが正しく生成される_2桁月() {
            String key = AuditLogArchiveBatchService.buildR2Key(YearMonth.of(2025, 12));
            assertThat(key).isEqualTo("audit-archive/2025/12/audit-2025-12.json");
        }
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private AuditLogEntity createAuditLog(Long id, LocalDateTime createdAt) {
        AuditLogEntity entity = AuditLogEntity.builder()
                .eventType("LOGIN_SUCCESS")
                .userId(42L)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        ReflectionTestUtils.setField(entity, "createdAt", createdAt);
        return entity;
    }
}
