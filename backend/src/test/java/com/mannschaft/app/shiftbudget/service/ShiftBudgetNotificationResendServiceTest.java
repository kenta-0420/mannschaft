package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftBudgetNotificationResendService} の単体テスト（Issue #2990 L13）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftBudgetNotificationResendService 単体テスト")
class ShiftBudgetNotificationResendServiceTest {

    private static final String TYPE = "SHIFT_BUDGET_THRESHOLD_ALERT";
    private static final String TITLE_KEY = "notification.shiftBudget.thresholdAlert.title";
    private static final String BODY_KEY = "notification.shiftBudget.thresholdAlert.body80";
    private static final String SOURCE_TYPE = "SHIFT_BUDGET_ALLOCATION";
    private static final Long SOURCE_ID = 42L;
    private static final Long SCOPE_ID = 1L;
    private static final String ACTION_URL = "/shift-budget/allocations/42";

    @Mock
    private NotificationHelper notificationHelper;
    @Mock
    private UserLocaleCache userLocaleCache;

    private ShiftBudgetNotificationResendService service;

    @BeforeEach
    void setUp() {
        // 実物の MessageSource を使う（モックだと鍵の欠落もフォーマット崩れも検出できない）。
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasenames("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(false);
        service = new ShiftBudgetNotificationResendService(
                notificationHelper, userLocaleCache, messageSource);
    }

    private void resend(List<Long> userIds) {
        service.resend(userIds, TYPE, TITLE_KEY, BODY_KEY, 80, "件名", "本文",
                SOURCE_TYPE, SOURCE_ID, SCOPE_ID, ACTION_URL);
    }

    @Test
    @DisplayName("Issue #2908: 受信者 locale ごとに本文を組み立て直して再送する（ja 固定にしない）")
    void 受信者localeごとに再送する() {
        given(userLocaleCache.getLocales(List.of(10L, 11L)))
                .willReturn(Map.of(10L, "ja", 11L, "en"));

        assertThatCode(() -> resend(List.of(10L, 11L))).doesNotThrowAnyException();

        verify(notificationHelper).notify(
                eq(10L), eq(TYPE), eq("シフト予算 警告 (80%)"), eq("予算 80% に到達しました"),
                eq(SOURCE_TYPE), eq(SOURCE_ID),
                eq(NotificationScopeType.ORGANIZATION), eq(SCOPE_ID), eq(ACTION_URL), eq(null));
        verify(notificationHelper).notify(
                eq(11L), eq(TYPE), eq("Shift budget warning (80%)"), eq("Budget has reached 80%"),
                eq(SOURCE_TYPE), eq(SOURCE_ID),
                eq(NotificationScopeType.ORGANIZATION), eq(SCOPE_ID), eq(ACTION_URL), eq(null));
    }

    @Test
    @DisplayName("locale 解決は受信者数によらず 1 回だけ（N+1 防止）")
    void locale解決は1回だけ() {
        given(userLocaleCache.getLocales(any())).willReturn(Map.of());

        resend(List.of(10L, 11L, 12L));

        verify(userLocaleCache, times(1)).getLocales(List.of(10L, 11L, 12L));
        verify(notificationHelper, times(3)).notify(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("途中の 1 名が失敗 → 残りの受信者へ配送を続け、最後に失敗を例外で報告する")
    void 途中失敗でも残りへ配送し失敗を報告する() {
        given(userLocaleCache.getLocales(any())).willReturn(Map.of());
        // 11L だけを落とす。matcher を eq(11L) に絞ると STRICT_STUBS が他の受信者の呼び出しで
        // PotentialStubbingProblem を投げ、それを resend の catch が「配送失敗」として数えてしまう
        // （実装ではなくテストの都合で全員失敗に見える）。全呼び出しを受けて中で分岐する。
        willAnswer(inv -> {
            if (Long.valueOf(11L).equals(inv.getArgument(0))) {
                throw new IllegalStateException("boom");
            }
            return null;
        }).given(notificationHelper).notify(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> resend(List.of(10L, 11L, 12L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1/3")
                .hasMessageContaining("11");

        // 失敗した 11L の後ろにいる 12L にも届いていること（被害半径が 1 名に閉じている）。
        verify(notificationHelper).notify(eq(10L), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
        verify(notificationHelper).notify(eq(12L), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("全員失敗 → 成功扱いにせず例外で報告する（是正前は notifyAll が握って SUCCEEDED になっていた）")
    void 全員失敗は成功扱いにしない() {
        given(userLocaleCache.getLocales(any())).willReturn(Map.of());
        willThrow(new IllegalStateException("boom"))
                .given(notificationHelper).notify(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> resend(List.of(10L, 11L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2/2");
    }

    @Test
    @DisplayName("受信者 0 名 → notify を呼ばず、例外も投げない（空・0件の攻め口）")
    void 受信者ゼロ() {
        assertThatCode(() -> resend(List.of())).doesNotThrowAnyException();

        verify(notificationHelper, times(0)).notify(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("番人: resend は NOT_SUPPORTED でなければならない（呼び出し元の業務TXを汚さない契約）")
    void 伝播はNOT_SUPPORTED() throws Exception {
        Transactional tx = ShiftBudgetNotificationResendService.class
                .getMethod("resend", List.class, String.class, String.class, String.class,
                        Object.class, String.class, String.class, String.class,
                        Long.class, Long.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(tx)
                .as("resend に @Transactional が無い＝呼び出し元の REQUIRES_NEW にそのまま参加してしまう")
                .isNotNull();
        assertThat(tx.propagation())
                .as("""
                        NOT_SUPPORTED 以外では、通知の DB 例外が呼び出し元
                        （ShiftBudgetRetryExecutor#execute）のトランザクションを rollback-only にし、
                        catch で握って書いた markFailed / retry_count++ が commit 時に消える。""")
                .isEqualTo(Propagation.NOT_SUPPORTED);
    }
}
