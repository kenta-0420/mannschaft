package com.mannschaft.app.disclosure.batch;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.StorageErrorCode;
import com.mannschaft.app.disclosure.DisclosureOutputFormat;
import com.mannschaft.app.disclosure.entity.DisclosureAutoDeleteBatchLogEntity;
import com.mannschaft.app.disclosure.entity.DisclosureExportEntity;
import com.mannschaft.app.disclosure.repository.DisclosureAutoDeleteBatchLogRepository;
import com.mannschaft.app.disclosure.repository.DisclosureExportRepository;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DisclosureAutoDeleteBatchService} 単体テスト（F09.14 Phase 3-E）。
 *
 * <p>期限切れ抽出 / R2 削除呼び出し / DB 論理削除 / バッチログ記録 / 失敗時集計
 * を網羅する。{@code TxHelper} は {@code REQUIRES_NEW} 適用のための実装上の分割であり、
 * 単体テストではモック差替で TxHelper を直接検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DisclosureAutoDeleteBatchService 単体テスト（Phase 3-E）")
class DisclosureAutoDeleteBatchServiceTest {

    @Mock private DisclosureExportRepository exportRepository;
    @Mock private SharedFileRepository sharedFileRepository;
    @Mock private R2StorageService r2StorageService;
    @Mock private DisclosureAutoDeleteBatchLogRepository batchLogRepository;

    private DisclosureAutoDeleteBatchService.DisclosureAutoDeleteBatchTxHelper txHelper;
    private DisclosureAutoDeleteBatchService service;

    @BeforeEach
    void setUp() {
        // TxHelper は本物を組み立てる（@Transactional は単体テストでは AOP 適用されないが、
        // 内部の R2 削除 + DB 論理削除のロジックそのものを検証する）。
        txHelper = new DisclosureAutoDeleteBatchService.DisclosureAutoDeleteBatchTxHelper(
                exportRepository, sharedFileRepository, r2StorageService);
        service = new DisclosureAutoDeleteBatchService(
                exportRepository, batchLogRepository, txHelper);
        when(batchLogRepository.save(any(DisclosureAutoDeleteBatchLogEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("executeAt(): 期限切れ 0 件でもバッチログは記録される")
    void executeAt_noExpired() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 9, 2, 0);
        when(exportRepository.findExpired(eq(now), any(Pageable.class))).thenReturn(List.of());

        DisclosureAutoDeleteBatchLogEntity logEntity = service.executeAt(now);

        assertThat(logEntity.getTotalExpired()).isEqualTo(0);
        assertThat(logEntity.getTotalDeleted()).isEqualTo(0);
        assertThat(logEntity.getFailedCount()).isEqualTo(0);
        assertThat(logEntity.getErrorDetails()).isNull();
        assertThat(logEntity.getBatchRunAt()).isEqualTo(now);
        verify(batchLogRepository).save(any(DisclosureAutoDeleteBatchLogEntity.class));
        verify(r2StorageService, never()).delete(any());
    }

    @Test
    @DisplayName("executeAt(): 期限切れ 2 件すべて削除成功 → R2 delete 2 回 + softDelete 2 回")
    void executeAt_allSuccess() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 9, 2, 0);
        DisclosureExportEntity e1 = exportEntity(11L, 101L);
        DisclosureExportEntity e2 = exportEntity(12L, 102L);
        when(exportRepository.findExpired(eq(now), any(Pageable.class)))
                .thenReturn(List.of(e1, e2));

        // TxHelper.deleteOne 内部で findById される
        when(exportRepository.findById(11L)).thenReturn(Optional.of(e1));
        when(exportRepository.findById(12L)).thenReturn(Optional.of(e2));

        SharedFileEntity sf1 = sharedFile("files/ORGANIZATION/100/a.pdf");
        SharedFileEntity sf2 = sharedFile("files/ORGANIZATION/100/b.pdf");
        when(sharedFileRepository.findById(101L)).thenReturn(Optional.of(sf1));
        when(sharedFileRepository.findById(102L)).thenReturn(Optional.of(sf2));

        DisclosureAutoDeleteBatchLogEntity logEntity = service.executeAt(now);

        assertThat(logEntity.getTotalExpired()).isEqualTo(2);
        assertThat(logEntity.getTotalDeleted()).isEqualTo(2);
        assertThat(logEntity.getFailedCount()).isEqualTo(0);
        assertThat(logEntity.getErrorDetails()).isNull();

        // R2 delete が両方呼ばれた
        verify(r2StorageService).delete("files/ORGANIZATION/100/a.pdf");
        verify(r2StorageService).delete("files/ORGANIZATION/100/b.pdf");

        // 論理削除されている
        assertThat(e1.getDeletedAt()).isNotNull();
        assertThat(e2.getDeletedAt()).isNotNull();
        verify(exportRepository).save(e1);
        verify(exportRepository).save(e2);
    }

    @Test
    @DisplayName("executeAt(): R2 削除失敗時は failedCount 集計 + errorDetails に記録、他レコードは継続処理")
    void executeAt_partialFailure() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 9, 2, 0);
        DisclosureExportEntity e1 = exportEntity(21L, 201L);
        DisclosureExportEntity e2 = exportEntity(22L, 202L);
        when(exportRepository.findExpired(eq(now), any(Pageable.class)))
                .thenReturn(List.of(e1, e2));

        when(exportRepository.findById(21L)).thenReturn(Optional.of(e1));
        when(exportRepository.findById(22L)).thenReturn(Optional.of(e2));

        SharedFileEntity sf1 = sharedFile("files/ORGANIZATION/100/a.pdf");
        SharedFileEntity sf2 = sharedFile("files/ORGANIZATION/100/b.pdf");
        when(sharedFileRepository.findById(201L)).thenReturn(Optional.of(sf1));
        when(sharedFileRepository.findById(202L)).thenReturn(Optional.of(sf2));

        // 1 件目は R2 削除失敗、2 件目は成功
        doThrow(new BusinessException(StorageErrorCode.DELETE_FAILED))
                .when(r2StorageService).delete("files/ORGANIZATION/100/a.pdf");

        DisclosureAutoDeleteBatchLogEntity logEntity = service.executeAt(now);

        assertThat(logEntity.getTotalExpired()).isEqualTo(2);
        assertThat(logEntity.getTotalDeleted()).isEqualTo(1);
        assertThat(logEntity.getFailedCount()).isEqualTo(1);
        assertThat(logEntity.getErrorDetails()).contains("exportId=21").contains("BusinessException");

        // 2 件目は softDelete 済み
        assertThat(e2.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("executeAt(): SharedFile が見つからない場合は R2 削除をスキップして DB 論理削除のみ実行")
    void executeAt_sharedFileMissing() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 9, 2, 0);
        DisclosureExportEntity e1 = exportEntity(31L, 301L);
        when(exportRepository.findExpired(eq(now), any(Pageable.class)))
                .thenReturn(List.of(e1));
        when(exportRepository.findById(31L)).thenReturn(Optional.of(e1));
        when(sharedFileRepository.findById(301L)).thenReturn(Optional.empty());

        DisclosureAutoDeleteBatchLogEntity logEntity = service.executeAt(now);

        assertThat(logEntity.getTotalDeleted()).isEqualTo(1);
        assertThat(logEntity.getFailedCount()).isEqualTo(0);
        verify(r2StorageService, never()).delete(any());
        assertThat(e1.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("executeAt(): 既に論理削除済みのレコードは冪等にスキップ（softDelete 再呼び出ししない）")
    void executeAt_alreadyDeleted_idempotent() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 9, 2, 0);
        DisclosureExportEntity e1 = exportEntity(41L, 401L);
        e1.softDelete();
        when(exportRepository.findExpired(eq(now), any(Pageable.class)))
                .thenReturn(List.of(e1));
        when(exportRepository.findById(41L)).thenReturn(Optional.of(e1));

        DisclosureAutoDeleteBatchLogEntity logEntity = service.executeAt(now);

        assertThat(logEntity.getTotalDeleted()).isEqualTo(1);
        verify(r2StorageService, never()).delete(any());
        verify(sharedFileRepository, never()).findById(anyLong());
        verify(exportRepository, never()).save(any());
    }

    @Test
    @DisplayName("バッチログの batchRunAt は executeAt の引数 now と一致する（時刻注入確認）")
    void executeAt_timeInjection() {
        LocalDateTime injected = LocalDateTime.of(2030, 12, 31, 23, 59);
        when(exportRepository.findExpired(eq(injected), any(Pageable.class))).thenReturn(List.of());

        DisclosureAutoDeleteBatchLogEntity logEntity = service.executeAt(injected);

        ArgumentCaptor<DisclosureAutoDeleteBatchLogEntity> captor =
                ArgumentCaptor.forClass(DisclosureAutoDeleteBatchLogEntity.class);
        verify(batchLogRepository).save(captor.capture());
        assertThat(captor.getValue().getBatchRunAt()).isEqualTo(injected);
        assertThat(logEntity.getBatchRunAt()).isEqualTo(injected);
    }

    // ===== ヘルパー =====

    private DisclosureExportEntity exportEntity(Long id, Long sharedFileId) throws Exception {
        DisclosureExportEntity e = DisclosureExportEntity.builder()
                .scopeType("ORGANIZATION").scopeId(100L)
                .templateId(1L).templateCodeSnapshot("MLIT").templateVersionSnapshot("v1")
                .outputFormat(DisclosureOutputFormat.PDF)
                .sharedFileId(sharedFileId)
                .requesterUserId(200L).dataSnapshot("{}")
                .expiresAt(LocalDateTime.of(2026, 5, 1, 0, 0))
                .build();
        Field f = DisclosureExportEntity.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(e, id);
        return e;
    }

    private SharedFileEntity sharedFile(String key) {
        return SharedFileEntity.builder()
                .folderId(50L).name("f.pdf")
                .fileKey(key).fileSize(10L).contentType("application/pdf")
                .build();
    }
}
