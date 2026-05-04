package com.mannschaft.app.proxy.batch;

import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ProxyInputRecordRetentionJob 単体テスト（F14.1 Phase 13-γ）。
 */
@ExtendWith(MockitoExtension.class)
class ProxyInputRecordRetentionJobTest {

    @Mock
    private ProxyInputRecordRepository recordRepository;

    @InjectMocks
    private ProxyInputRecordRetentionJob sut;

    @Test
    @DisplayName("保管期限切れレコードがない場合は削除を実行しない")
    void noExpiredRecords() {
        LocalDate today = LocalDate.of(2026, 5, 1);
        given(recordRepository.findExpiredRecordIds(today)).willReturn(List.of());

        int result = sut.deleteExpired(today);

        assertThat(result).isZero();
        verify(recordRepository, never()).deleteByIdIn(List.of());
    }

    @Test
    @DisplayName("保管期限切れレコードがある場合は物理削除する")
    void deletesExpiredRecords() {
        LocalDate today = LocalDate.of(2026, 5, 1);
        List<Long> expiredIds = List.of(1L, 2L, 3L);
        given(recordRepository.findExpiredRecordIds(today)).willReturn(expiredIds);

        int result = sut.deleteExpired(today);

        assertThat(result).isEqualTo(3);
        verify(recordRepository).deleteByIdIn(expiredIds);
    }

    @Test
    @DisplayName("BATCH_SIZE(500)を超えるレコードは分割して削除する")
    void deleteInBatches() {
        LocalDate today = LocalDate.of(2026, 5, 1);
        // 501件で境界条件を検証
        List<Long> expiredIds = new java.util.ArrayList<>();
        for (long i = 1; i <= 501; i++) expiredIds.add(i);
        given(recordRepository.findExpiredRecordIds(today)).willReturn(expiredIds);

        int result = sut.deleteExpired(today);

        assertThat(result).isEqualTo(501);
        // 500件 + 1件の2回に分割されること
        verify(recordRepository).deleteByIdIn(expiredIds.subList(0, 500));
        verify(recordRepository).deleteByIdIn(expiredIds.subList(500, 501));
    }

    @Test
    @DisplayName("ちょうどBATCH_SIZE件の場合は1回の削除で完了する")
    void deleteExactlyOneBatch() {
        LocalDate today = LocalDate.of(2026, 5, 1);
        List<Long> expiredIds = new java.util.ArrayList<>();
        for (long i = 1; i <= 500; i++) expiredIds.add(i);
        given(recordRepository.findExpiredRecordIds(today)).willReturn(expiredIds);

        int result = sut.deleteExpired(today);

        assertThat(result).isEqualTo(500);
        verify(recordRepository).deleteByIdIn(expiredIds);
    }
}
