package com.mannschaft.app.schedule.service;

import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleSource;
import com.mannschaft.app.schedule.entity.UserCalendarSyncSettingEntity;
import com.mannschaft.app.schedule.event.ScheduleUpdatedEvent;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.UserCalendarSyncSettingRepository;
import com.mannschaft.app.schedule.repository.UserGoogleCalendarConnectionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Google Calendar Phase 4 — EventListener 無限ループ防止テスト（AC-12 red 先行）。
 *
 * <p>対象 AC: AC-12 — {@code source = GOOGLE_IMPORT} のスケジュールが更新されても、
 * Phase 3 の自動 Google push 同期（{@link GoogleCalendarEventListener}）が
 * Google に同期しないこと（無限ループ防止）。</p>
 *
 * <p><b>仕様（設計書 P4-4 参照）</b>:</p>
 * <ul>
 *   <li>Google からインポートしたスケジュール（{@code source=GOOGLE_IMPORT}）を
 *       Mannschaft 上で更新すると {@code ScheduleUpdatedEvent} が発火する</li>
 *   <li>Phase 3 の {@link GoogleCalendarEventListener} がこれを受け取ると
 *       Google に push-back しようとする</li>
 *   <li>Google → Mannschaft → Google の無限ループを防ぐため、
 *       Phase 4 では Listener が {@code source=GOOGLE_IMPORT} を検知して
 *       {@code syncScheduleToGoogle()} を呼ばないこと</li>
 * </ul>
 *
 * <p><b>red の理由</b>: 現在の {@link GoogleCalendarEventListener#onScheduleUpdated(ScheduleUpdatedEvent)}
 * は {@code source} フィールドを確認しない実装になっている。
 * {@code source=GOOGLE_IMPORT} でも {@code syncScheduleToGoogle()} が呼ばれるため、
 * このテストは {@code verify(googleCalendarService, never()).syncScheduleToGoogle(...)}
 * でアサート失敗して red になる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GoogleCalendarEventListener Phase 4 受け入れテスト — AC-12 GOOGLE_IMPORT push 抑止（red）")
class GoogleCalendarEventListenerPhase4Test {

    @Mock
    private GoogleCalendarService googleCalendarService;

    @Mock
    private UserCalendarSyncSettingRepository syncSettingRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private UserGoogleCalendarConnectionRepository connectionRepository;

    @InjectMocks
    private GoogleCalendarEventListener eventListener;

    private static final Long SCHEDULE_ID = 2001L;
    private static final Long TEAM_ID = 20L;
    private static final Long USER_ID = 200L;

    // ========================================
    // AC-12: GOOGLE_IMPORT スケジュール更新時の push 抑止
    // ========================================

    @Nested
    @DisplayName("AC-12: source=GOOGLE_IMPORT スケジュールの更新時に Google への push を抑止する")
    class AC12GoogleImportPushSuppression {

        @Test
        @DisplayName("AC-12: source=GOOGLE_IMPORT のスケジュールが ScheduleUpdatedEvent で更新されても syncScheduleToGoogle が呼ばれない")
        void googleImportScheduleUpdated_doesNotPushToGoogle() {
            // given: source=GOOGLE_IMPORT のスケジュール
            ScheduleEntity googleImportSchedule = ScheduleEntity.builder()
                    .teamId(TEAM_ID)
                    .title("Google 取込スケジュール")
                    .startAt(LocalDateTime.of(2026, 8, 1, 10, 0))
                    .endAt(LocalDateTime.of(2026, 8, 1, 12, 0))
                    .allDay(false)
                    .eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .isException(false)
                    .source(ScheduleSource.GOOGLE_IMPORT) // Google からのインポート
                    .build();

            given(scheduleRepository.findById(SCHEDULE_ID))
                    .willReturn(Optional.of(googleImportSchedule));
            // NOTE: GOOGLE_IMPORT の場合は早期 return するため syncSettingRepository は呼ばれない。
            // UnnecessaryStubbingException を避けるため stubbing を設定しない。

            // when: Mannschaft 上でスケジュールが更新されたイベントが発火
            ScheduleUpdatedEvent event = new ScheduleUpdatedEvent(SCHEDULE_ID, USER_ID);
            eventListener.onScheduleUpdated(event);

            // then: source=GOOGLE_IMPORT のため Google への push-back を抑止する
            verify(googleCalendarService, never())
                    .syncScheduleToGoogle(any(ScheduleEntity.class), any(Long.class));
        }

        @Test
        @DisplayName("AC-12: source=MANNSCHAFT のスケジュールは通常どおり Google に同期される（抑止対象外）")
        void mannschaftScheduleUpdated_pushesToGoogle() {
            // given: source=MANNSCHAFT（通常のスケジュール）
            ScheduleEntity mannschaftSchedule = ScheduleEntity.builder()
                    .teamId(TEAM_ID)
                    .title("Mannschaft 作成スケジュール")
                    .startAt(LocalDateTime.of(2026, 8, 1, 10, 0))
                    .endAt(LocalDateTime.of(2026, 8, 1, 12, 0))
                    .allDay(false)
                    .eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .isException(false)
                    .source(ScheduleSource.MANNSCHAFT) // Mannschaft 作成
                    .build();

            UserCalendarSyncSettingEntity syncSetting = UserCalendarSyncSettingEntity.builder()
                    .userId(USER_ID)
                    .scopeType("TEAM")
                    .scopeId(TEAM_ID)
                    .isEnabled(true)
                    .build();

            given(scheduleRepository.findById(SCHEDULE_ID))
                    .willReturn(Optional.of(mannschaftSchedule));
            given(syncSettingRepository.findByScopeTypeAndScopeIdAndIsEnabledTrue("TEAM", TEAM_ID))
                    .willReturn(List.of(syncSetting));

            // when
            ScheduleUpdatedEvent event = new ScheduleUpdatedEvent(SCHEDULE_ID, USER_ID);
            eventListener.onScheduleUpdated(event);

            // then: source=MANNSCHAFT の場合は Google に push する（正常系）
            // NOTE: ScheduleEntity.id は BaseEntity 起因でテスト時には設定されないため any() で検証
            verify(googleCalendarService).syncScheduleToGoogle(mannschaftSchedule, USER_ID);
        }

        @Test
        @DisplayName("AC-12: source=GOOGLE_IMPORT スケジュールの ScheduleCreatedEvent でも Google への push を抑止する")
        void googleImportScheduleCreated_doesNotPushToGoogle() {
            // NOTE: ScheduleCreatedEvent は通常 Mannschaft 上で作成した場合のみ発火するが、
            // 将来の edge case（Google 取込後に重複作成イベントが発火する場合）への防衛として
            // onScheduleCreated でも source チェックを実施する必要がある。
            //
            // ただし AC-12 は主に「更新」のループに言及しているため、
            // このテストはボーナス検証として記載する。
            //
            // TODO: 実装後にアサートを追加
            //   given(scheduleRepository.findById(SCHEDULE_ID))
            //       .willReturn(Optional.of(googleImportSchedule));
            //   eventListener.onScheduleCreated(createdEvent);
            //   verify(googleCalendarService, never()).syncScheduleToGoogle(any(), any());
        }
    }
}
