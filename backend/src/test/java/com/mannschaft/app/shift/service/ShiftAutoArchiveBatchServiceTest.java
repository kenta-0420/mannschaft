package com.mannschaft.app.shift.service;

import com.mannschaft.app.shift.ChangeRequestStatus;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.entity.ShiftChangeRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.event.ShiftArchivedEvent;
import com.mannschaft.app.shift.repository.ShiftChangeRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link ShiftAutoArchiveBatchService} のユニットテスト。F03.5 Phase 4-0 不整合 #C 補修。
 */
@ExtendWith(MockitoExtension.class)
class ShiftAutoArchiveBatchServiceTest {

    @Mock private ShiftScheduleRepository scheduleRepository;
    @Mock private ShiftChangeRequestRepository changeRequestRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ShiftAutoArchiveBatchService batchService;

    private static final Long SCHEDULE_ID = 1L;
    private static final Long TEAM_ID = 10L;

    // =========================================================
    // runArchive
    // =========================================================

    @Nested
    @DisplayName("runArchive")
    class RunArchive {

        @Test
        @DisplayName("期限切れ PUBLISHED スケジュールを ARCHIVED に遷移し save する")
        void 期限切れスケジュールをアーカイブ() {
            ShiftScheduleEntity schedule = buildPublishedSchedule(SCHEDULE_ID, TEAM_ID,
                    LocalDate.now().minusDays(10));
            given(scheduleRepository.findPublishedExpiredBefore(any(LocalDate.class), any(Pageable.class)))
                    .willReturn(List.of(schedule));
            given(changeRequestRepository.withdrawOpenRequestsByScheduleId(any(), any())).willReturn(0);

            batchService.runArchive();

            assertThat(schedule.getStatus()).isEqualTo(ShiftScheduleStatus.ARCHIVED);
            verify(scheduleRepository).save(schedule);
        }

        @Test
        @DisplayName("ARCHIVED 遷移時に ShiftArchivedEvent を発行する（Phase 4-γ）")
        void ShiftArchivedEventを発行() {
            ShiftScheduleEntity schedule = buildPublishedSchedule(SCHEDULE_ID, TEAM_ID,
                    LocalDate.now().minusDays(10));
            given(scheduleRepository.findPublishedExpiredBefore(any(LocalDate.class), any(Pageable.class)))
                    .willReturn(List.of(schedule));
            given(changeRequestRepository.withdrawOpenRequestsByScheduleId(any(), any())).willReturn(0);

            batchService.runArchive();

            ArgumentCaptor<ShiftArchivedEvent> eventCaptor = ArgumentCaptor.forClass(ShiftArchivedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            ShiftArchivedEvent published = eventCaptor.getValue();
            assertThat(published.getScheduleId()).isEqualTo(SCHEDULE_ID);
            assertThat(published.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(published.getArchivedByUserId()).isNull();
        }

        @Test
        @DisplayName("ARCHIVED 遷移時に OPEN 変更依頼を自動 WITHDRAWN 化する")
        void OPEN変更依頼を自動WITHDRAWN() {
            ShiftScheduleEntity schedule = buildPublishedSchedule(SCHEDULE_ID, TEAM_ID,
                    LocalDate.now().minusDays(10));
            given(scheduleRepository.findPublishedExpiredBefore(any(LocalDate.class), any(Pageable.class)))
                    .willReturn(List.of(schedule));
            given(changeRequestRepository.withdrawOpenRequestsByScheduleId(eq(SCHEDULE_ID), any()))
                    .willReturn(2);

            batchService.runArchive();

            verify(changeRequestRepository).withdrawOpenRequestsByScheduleId(eq(SCHEDULE_ID), any());
        }

        @Test
        @DisplayName("対象スケジュールが0件の場合は何もしない")
        void 対象なしは処理なし() {
            given(scheduleRepository.findPublishedExpiredBefore(any(LocalDate.class), any(Pageable.class)))
                    .willReturn(List.of());

            batchService.runArchive();

            verify(scheduleRepository, never()).save(any());
            verify(changeRequestRepository, never()).withdrawOpenRequestsByScheduleId(any(), any());
        }

        @Test
        @DisplayName("楽観ロック競合発生時はスキップして他スケジュールの処理を継続する")
        void 楽観ロック競合時はスキップ() {
            ShiftScheduleEntity schedule1 = buildPublishedSchedule(1L, TEAM_ID,
                    LocalDate.now().minusDays(10));
            ShiftScheduleEntity schedule2 = buildPublishedSchedule(2L, TEAM_ID,
                    LocalDate.now().minusDays(8));
            given(scheduleRepository.findPublishedExpiredBefore(any(LocalDate.class), any(Pageable.class)))
                    .willReturn(List.of(schedule1, schedule2));
            // schedule1 で楽観ロック競合
            given(scheduleRepository.save(schedule1))
                    .willThrow(new ObjectOptimisticLockingFailureException(ShiftScheduleEntity.class, 1L));
            given(changeRequestRepository.withdrawOpenRequestsByScheduleId(eq(2L), any())).willReturn(0);

            batchService.runArchive();

            // schedule2 は正常処理される
            verify(scheduleRepository).save(schedule2);
        }
    }

    // =========================================================
    // ヘルパー
    // =========================================================

    private ShiftScheduleEntity buildPublishedSchedule(Long id, Long teamId, LocalDate endDate) {
        ShiftScheduleEntity entity = ShiftScheduleEntity.builder()
                .teamId(teamId)
                .title("テストシフト")
                .status(ShiftScheduleStatus.PUBLISHED)
                .endDate(endDate)
                .build();
        // publish() を呼んで PUBLISHED ステータスに遷移させるのではなく、
        // テスト用にリフレクションで直接ステータスをセットする
        ReflectionTestUtils.setField(entity, "id", id);
        ReflectionTestUtils.setField(entity, "status", ShiftScheduleStatus.PUBLISHED);
        return entity;
    }
}
