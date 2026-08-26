package com.mannschaft.app.village.batch;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.village.entity.VillageFestivalEntity;
import com.mannschaft.app.village.entity.enums.VillageFestivalStatus;
import com.mannschaft.app.village.repository.VillageFestivalRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageFestivalStateTransitionBatchService} 単体テスト（F17.1 Phase 2 U5）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>SCHEDULED 祭で starts_at <= now のものは ACTIVE 化</li>
 *   <li>ACTIVE 祭で ends_at <= now のものは ENDED 化</li>
 *   <li>未来開始の SCHEDULED / 未来終了の ACTIVE はそのまま</li>
 *   <li>CANCELLED は対象外（リポジトリのフィルタで除外されるので何も呼ばれない）</li>
 *   <li>1 件失敗しても他の祭は処理される</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageFestivalStateTransitionBatchService 単体テスト")
class VillageFestivalStateTransitionBatchServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000A01");

    @Mock
    private VillageFestivalRepository festivalRepository;
    @Mock
    private AuditLogService auditLogService;
    /** F17.2 Wave2 ①: 祭 ACTIVE 化時の FESTIVAL_STARTED 還流（no-op モック）。 */
    @Mock
    private com.mannschaft.app.village.service.VillageEventFeedRefluxService refluxService;
    /** F17.2 Wave2 ③: 祭 ENDED 時の村史編纂（no-op モック）。 */
    @Mock
    private com.mannschaft.app.village.service.VillageEventArchiveService eventArchiveService;

    @InjectMocks
    private VillageFestivalStateTransitionBatchService batch;

    @Test
    @DisplayName("SCHEDULED で starts_at <= now のもののみ ACTIVE 化される")
    void scheduledToActive_onlyStarted() {
        VillageFestivalEntity past = festival(VillageFestivalStatus.SCHEDULED,
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusDays(1));
        VillageFestivalEntity future = festival(VillageFestivalStatus.SCHEDULED,
                LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(3));
        given(festivalRepository.findByStatusAndDeletedAtIsNull(VillageFestivalStatus.SCHEDULED))
                .willReturn(List.of(past, future));
        given(festivalRepository.findByStatusAndDeletedAtIsNull(VillageFestivalStatus.ACTIVE))
                .willReturn(List.of());

        batch.runBatch();

        // past だけ save される
        ArgumentCaptor<VillageFestivalEntity> cap = ArgumentCaptor.forClass(VillageFestivalEntity.class);
        verify(festivalRepository, times(1)).save(cap.capture());
        assertThat(cap.getValue().getId()).isEqualTo(past.getId());
        assertThat(cap.getValue().getStatus()).isEqualTo(VillageFestivalStatus.ACTIVE);
        verify(auditLogService, times(1)).record(
                eq(AuditEventType.VILLAGE_FESTIVAL_ACTIVATED.name()),
                any(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("ACTIVE で ends_at <= now のもののみ ENDED 化される")
    void activeToEnded_onlyEnded() {
        VillageFestivalEntity expired = festival(VillageFestivalStatus.ACTIVE,
                LocalDateTime.now().minusDays(2), LocalDateTime.now().minusMinutes(5));
        VillageFestivalEntity still = festival(VillageFestivalStatus.ACTIVE,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        given(festivalRepository.findByStatusAndDeletedAtIsNull(VillageFestivalStatus.SCHEDULED))
                .willReturn(List.of());
        given(festivalRepository.findByStatusAndDeletedAtIsNull(VillageFestivalStatus.ACTIVE))
                .willReturn(List.of(expired, still));

        batch.runBatch();

        ArgumentCaptor<VillageFestivalEntity> cap = ArgumentCaptor.forClass(VillageFestivalEntity.class);
        verify(festivalRepository, times(1)).save(cap.capture());
        assertThat(cap.getValue().getId()).isEqualTo(expired.getId());
        assertThat(cap.getValue().getStatus()).isEqualTo(VillageFestivalStatus.ENDED);
        verify(auditLogService, times(1)).record(
                eq(AuditEventType.VILLAGE_FESTIVAL_ENDED.name()),
                any(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("CANCELLED / 削除済はリポジトリで除外されるためバッチは触れない")
    void cancelledNotTouched() {
        // findByStatusAndDeletedAtIsNull の結果が空なら何も処理しない
        given(festivalRepository.findByStatusAndDeletedAtIsNull(VillageFestivalStatus.SCHEDULED))
                .willReturn(List.of());
        given(festivalRepository.findByStatusAndDeletedAtIsNull(VillageFestivalStatus.ACTIVE))
                .willReturn(List.of());

        batch.runBatch();

        verify(festivalRepository, never()).save(any());
        verify(auditLogService, never()).record(
                anyString(), any(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("1 件 ACTIVE 化失敗 → 他の祭は引き続き処理される")
    void continueOnFailure() {
        VillageFestivalEntity fail = festival(VillageFestivalStatus.SCHEDULED,
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusDays(1));
        VillageFestivalEntity ok = festival(VillageFestivalStatus.SCHEDULED,
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusDays(1));
        given(festivalRepository.findByStatusAndDeletedAtIsNull(VillageFestivalStatus.SCHEDULED))
                .willReturn(List.of(fail, ok));
        given(festivalRepository.findByStatusAndDeletedAtIsNull(VillageFestivalStatus.ACTIVE))
                .willReturn(List.of());
        // fail を渡された呼出だけ例外、ok は正常終了
        given(festivalRepository.save(any(VillageFestivalEntity.class)))
                .willAnswer(inv -> {
                    VillageFestivalEntity arg = inv.getArgument(0);
                    if (arg.getId().equals(fail.getId())) {
                        throw new RuntimeException("simulated DB failure");
                    }
                    return arg;
                });

        batch.runBatch();

        // fail と ok の両方で save 呼び出しが行われる
        verify(festivalRepository, times(2)).save(any());
        // 成功した方のみ監査ログ記録
        verify(auditLogService, times(1)).record(
                eq(AuditEventType.VILLAGE_FESTIVAL_ACTIVATED.name()),
                any(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("transitionToActive 単独: status が ACTIVE になり監査ログ記録")
    void transitionToActive_unit() {
        VillageFestivalEntity entity = festival(VillageFestivalStatus.SCHEDULED,
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusDays(1));
        given(festivalRepository.save(any(VillageFestivalEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        batch.transitionToActive(entity);

        assertThat(entity.getStatus()).isEqualTo(VillageFestivalStatus.ACTIVE);
        verify(festivalRepository).save(entity);
        verify(auditLogService).record(
                eq(AuditEventType.VILLAGE_FESTIVAL_ACTIVATED.name()),
                any(), any(), any(), any(), any(), any(), any(), anyString());
    }

    // ========================================================================
    // ヘルパ
    // ========================================================================

    private VillageFestivalEntity festival(VillageFestivalStatus status,
                                            LocalDateTime starts, LocalDateTime ends) {
        VillageFestivalEntity e = VillageFestivalEntity.builder()
                .villageId(VILLAGE_ID)
                .title("祭")
                .startsAt(starts)
                .endsAt(ends)
                .status(status)
                .createdByUserId(1L)
                .build();
        e.setId(UUID.randomUUID());
        return e;
    }
}
