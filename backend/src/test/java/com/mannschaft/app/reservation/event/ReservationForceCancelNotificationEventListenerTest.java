package com.mannschaft.app.reservation.event;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationForceCancelNotificationEventListener} の単体テスト
 * （Issue #2715 ロットC-3: 強行キャンセル通知本文の i18n 化）。
 *
 * <p>MessageSource は実物（{@link ReloadableResourceBundleMessageSource}）を用いる。
 * モックで引数をそのまま返す形では、鍵の欠落やフォーマット崩れを検出できないため
 * （AC の試練要件）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationForceCancelNotificationEventListener 単体テスト")
class ReservationForceCancelNotificationEventListenerTest {

    @Mock
    private NotificationHelper notificationHelper;

    @Mock
    private UserLocaleCache userLocaleCache;

    @Captor
    private ArgumentCaptor<String> titleCaptor;

    @Captor
    private ArgumentCaptor<String> bodyCaptor;

    private MessageSource messageSource;

    private ReservationForceCancelNotificationEventListener listener;

    @BeforeEach
    void setUp() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages");
        ms.setDefaultEncoding("UTF-8");
        this.messageSource = ms;
        this.listener = new ReservationForceCancelNotificationEventListener(
                notificationHelper, userLocaleCache, messageSource);
    }

    private ReservationForceCancelledByBlockEvent buildEvent(String slotTitle, String reason) {
        return new ReservationForceCancelledByBlockEvent(
                10L, 100L, 1L,
                List.of(new ReservationForceCancelledByBlockEvent.CancelledSlot(
                        LocalDateTime.of(2026, 9, 1, 10, 0), slotTitle)),
                reason);
    }

    @Test
    @DisplayName("受信者 locale が en のとき件名・本文が英語で組み立てられ、プレースホルダが残らない")
    void 英語localeで組み立てられる() {
        // given
        given(userLocaleCache.getLocale(1L)).willReturn("en");
        ReservationForceCancelledByBlockEvent event = buildEvent("Math", "training");

        // when
        listener.onForceCancelled(event);

        // then
        verify(notificationHelper).notify(
                eq(1L),
                eq("RESERVATION_CANCELLED"),
                titleCaptor.capture(),
                bodyCaptor.capture(),
                eq("RESERVATION"),
                eq(100L),
                eq(NotificationScopeType.TEAM),
                eq(10L),
                eq("/teams/10/reservations"),
                eq((Long) null));

        assertThat(titleCaptor.getValue()).isEqualTo("Your reservation has been canceled");
        assertThat(bodyCaptor.getValue())
                .doesNotContain("{0}")
                .doesNotContain("{1}")
                .contains("Math")
                .contains("training");
    }

    @Test
    @DisplayName("受信者 locale が ja のとき件名・本文が日本語で組み立てられる")
    void 日本語localeで組み立てられる() {
        given(userLocaleCache.getLocale(1L)).willReturn("ja");
        ReservationForceCancelledByBlockEvent event = buildEvent("数学", "研修");

        listener.onForceCancelled(event);

        verify(notificationHelper).notify(
                eq(1L), eq("RESERVATION_CANCELLED"),
                titleCaptor.capture(), bodyCaptor.capture(),
                eq("RESERVATION"), eq(100L),
                eq(NotificationScopeType.TEAM), eq(10L),
                eq("/teams/10/reservations"), eq((Long) null));

        assertThat(titleCaptor.getValue()).isEqualTo("ご予約がキャンセルされました");
        assertThat(bodyCaptor.getValue())
                .contains("数学")
                .contains("研修");
    }

    @Test
    @DisplayName("slotTitle が null の場合、locale に応じたデフォルト文言が使われる（プレースホルダ非残存）")
    void slotTitleがnullの場合デフォルト文言() {
        given(userLocaleCache.getLocale(1L)).willReturn("en");
        ReservationForceCancelledByBlockEvent event = buildEvent(null, null);

        listener.onForceCancelled(event);

        verify(notificationHelper).notify(
                eq(1L), eq("RESERVATION_CANCELLED"),
                titleCaptor.capture(), bodyCaptor.capture(),
                eq("RESERVATION"), eq(100L),
                eq(NotificationScopeType.TEAM), eq(10L),
                eq("/teams/10/reservations"), eq((Long) null));

        assertThat(bodyCaptor.getValue())
                .doesNotContain("{0}")
                .doesNotContain("{1}")
                .contains("your reservation");
    }

    @Test
    @DisplayName("locale 解決は1件のみ（単一受信者経路のため getLocale を1回だけ呼ぶ）")
    void locale解決は単一() {
        given(userLocaleCache.getLocale(1L)).willReturn("ja");
        listener.onForceCancelled(buildEvent("数学", null));

        verify(userLocaleCache).getLocale(1L);
    }

    /**
     * Codex検分是正（PR #2861 P2）: {@code formatSlot} が枠開始日時を固定パターン
     * {@code "M月d日 HH:mm"} で整形しており、en/de/es 受信者の本文にも日本語表記
     * （「9月1日 10:00」）が残っていた欠陥を直接突く番人。日本語（ひらがな・カタカナ・漢字）
     * の混入を正規表現で検出する。
     */
    private static final java.util.regex.Pattern JAPANESE_CHARS =
            java.util.regex.Pattern.compile("[ぁ-ゖァ-ヶ一-龠]");

    @Test
    @DisplayName("受信者 locale が en のとき、本文の日時表記にも日本語文字が残らない")
    void 英語localeで日時表記に日本語が残らない() {
        given(userLocaleCache.getLocale(1L)).willReturn("en");
        ReservationForceCancelledByBlockEvent event = buildEvent("Math", null);

        listener.onForceCancelled(event);

        verify(notificationHelper).notify(
                eq(1L), eq("RESERVATION_CANCELLED"),
                titleCaptor.capture(), bodyCaptor.capture(),
                eq("RESERVATION"), eq(100L),
                eq(NotificationScopeType.TEAM), eq(10L),
                eq("/teams/10/reservations"), eq((Long) null));

        assertThat(JAPANESE_CHARS.matcher(bodyCaptor.getValue()).find())
                .as("en 受信者の本文に日本語文字が含まれてはならない: %s", bodyCaptor.getValue())
                .isFalse();
        assertThat(bodyCaptor.getValue()).contains("9/1 10:00");
    }
}
