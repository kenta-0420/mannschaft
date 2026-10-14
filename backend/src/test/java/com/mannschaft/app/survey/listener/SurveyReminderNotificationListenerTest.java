package com.mannschaft.app.survey.listener;

import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.survey.event.SurveyReminderNotificationEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.StaticMessageSource;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 締切延長通知の日時表記が、イベントを Instant で運んでも受信者 locale ごとに従来どおりになることを固定する。 */
@DisplayName("SurveyReminderNotificationListener 単体テスト")
class SurveyReminderNotificationListenerTest {

    @Test
    @DisplayName("締切延長: Instant で運んだ締切が locale ごとのパターンで元の壁時計どおりに表記される")
    void 締切延長の日時表記はlocaleごとに従来どおり() {
        NotificationHelper helper = mock(NotificationHelper.class);
        StaticMessageSource ms = new StaticMessageSource();
        ms.addMessage("notification.survey.deadlineExtended.deadlinePattern", Locale.JAPANESE, "yyyy年M月d日 HH:mm");
        ms.addMessage("notification.survey.deadlineExtended.deadlinePattern", Locale.ENGLISH, "MMM d, yyyy HH:mm");
        ms.addMessage("notification.survey.deadlineExtended.title", Locale.JAPANESE, "延長");
        ms.addMessage("notification.survey.deadlineExtended.title", Locale.ENGLISH, "Extended");
        ms.addMessage("notification.survey.deadlineExtended.body", Locale.JAPANESE, "{0} は {1} まで");
        ms.addMessage("notification.survey.deadlineExtended.body", Locale.ENGLISH, "{0} until {1}");
        SurveyReminderNotificationListener listener = new SurveyReminderNotificationListener(helper, ms);
        LocalDateTime wallClock = LocalDateTime.of(2026, 12, 1, 9, 30);

        listener.onSurveyReminderNotification(new SurveyReminderNotificationEvent(
                SurveyReminderNotificationEvent.Kind.DEADLINE_EXTENDED, 1L, "TEAM", 2L, "調査",
                List.of(10L), 3L, wallClock.atZone(ZoneId.systemDefault()).toInstant()));

        ArgumentCaptor<NotificationHelper.LocalizedMessageBuilder> captor =
                ArgumentCaptor.forClass(NotificationHelper.LocalizedMessageBuilder.class);
        verify(helper).notifyAllPreAuthorizedLocalized(eq(List.of(10L)), anyString(), anyString(), anyLong(),
                any(), anyLong(), anyString(), anyLong(), captor.capture());
        assertThat(captor.getValue().build(10L, Locale.JAPANESE).body()).isEqualTo("調査 は 2026年12月1日 09:30 まで");
        assertThat(captor.getValue().build(10L, Locale.ENGLISH).body()).isEqualTo("調査 until Dec 1, 2026 09:30");
    }
}
