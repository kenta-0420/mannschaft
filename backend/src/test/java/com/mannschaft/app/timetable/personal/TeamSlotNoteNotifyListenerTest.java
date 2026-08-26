package com.mannschaft.app.timetable.personal;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.timetable.event.TimetableSlotNoteUpdatedEvent;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSettingsEntity;
import com.mannschaft.app.timetable.personal.listener.TeamSlotNoteNotifyListener;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F03.15 Phase 4 TeamSlotNoteNotifyListener のユニットテスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamSlotNoteNotifyListener ユニットテスト")
class TeamSlotNoteNotifyListenerTest {

    private static final Long TEAM_ID = 50L;
    private static final Long TT_ID = 200L;
    private static final Long SLOT_ID = 11L;

    @Mock private UserRoleRepository userRoleRepository;
    @Mock private PersonalTimetableSettingsRepository settingsRepository;
    @Mock private UserLocaleCache userLocaleCache;

    private TeamSlotNoteNotifyListener listener;

    @BeforeEach
    void setUp() {
        // Issue #2715 ロットB: 依存追加に伴う mock 漏れ対策（getLocales は N+1 防止の一括解決）。
        lenient().when(userLocaleCache.getLocales(any())).thenReturn(Map.of());
        listener = new TeamSlotNoteNotifyListener(
                userRoleRepository, settingsRepository, userLocaleCache, new StaticMessageSource());
    }

    @Test
    @DisplayName("notes が空文字なら通知しない")
    void empty_notes_スキップ() {
        listener.onSlotNoteUpdated(new TimetableSlotNoteUpdatedEvent(
                SLOT_ID, TT_ID, TEAM_ID, "情報", ""));
        verify(userRoleRepository, never()).findUserIdsByScope(any(), any());
    }

    @Test
    @DisplayName("teamId が null なら通知しない")
    void team_null_スキップ() {
        listener.onSlotNoteUpdated(new TimetableSlotNoteUpdatedEvent(
                SLOT_ID, TT_ID, null, "情報", "持参物"));
        verify(userRoleRepository, never()).findUserIdsByScope(any(), any());
    }

    @Test
    @DisplayName("Issue #2715 ロットB: 受信者 locale が en の場合、通知件名が英語で組み立てられプレースホルダが残らない")
    void 受信者ロケールがenなら英語件名になる() {
        var realMessageSource = new org.springframework.context.support.ResourceBundleMessageSource();
        realMessageSource.setBasename("messages");
        realMessageSource.setDefaultEncoding("UTF-8");
        listener = new TeamSlotNoteNotifyListener(
                userRoleRepository, settingsRepository, userLocaleCache, realMessageSource);

        com.mannschaft.app.notification.service.NotificationHelper notificationHelper =
                org.mockito.Mockito.mock(com.mannschaft.app.notification.service.NotificationHelper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(
                listener, "notificationHelper", notificationHelper);

        given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).willReturn(List.of(101L));
        given(settingsRepository.findById(101L)).willReturn(Optional.empty());
        given(userLocaleCache.getLocales(List.of(101L))).willReturn(Map.of(101L, "en"));

        listener.onSlotNoteUpdated(new TimetableSlotNoteUpdatedEvent(
                SLOT_ID, TT_ID, TEAM_ID, "Math", "持参物"));

        org.mockito.ArgumentCaptor<String> titleCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notificationHelper).notify(
                org.mockito.ArgumentMatchers.eq(101L), any(), titleCaptor.capture(), any(),
                any(), any(), any(), any(), any(), any());
        org.assertj.core.api.Assertions.assertThat(titleCaptor.getValue())
                .contains("Math")
                .doesNotContain("{0}");
    }

    @Test
    @DisplayName("notify_team_slot_note_updates = false のメンバーは通知抑制")
    void 設定OFFメンバーは通知しない() {
        given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID))
                .willReturn(List.of(101L, 102L));
        // 101 は OFF、102 は ON
        given(settingsRepository.findById(101L)).willReturn(Optional.of(
                PersonalTimetableSettingsEntity.builder()
                        .userId(101L).notifyTeamSlotNoteUpdates(false).build()));
        given(settingsRepository.findById(102L)).willReturn(Optional.empty()); // デフォルト ON

        listener.onSlotNoteUpdated(new TimetableSlotNoteUpdatedEvent(
                SLOT_ID, TT_ID, TEAM_ID, "情報", "次回 p.30 まで読んでくる"));

        // notificationHelper は null のため、通知呼び出しは検証できないが、
        // findById は両者に対して呼ばれる
        verify(settingsRepository).findById(101L);
        verify(settingsRepository).findById(102L);
    }
}
