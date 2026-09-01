package com.mannschaft.app.gdpr.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 柱①「ADMINゼロ根治」AC11 — {@link PurgeStartGuard} の受け入れテスト（試練・red）。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §12.5。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurgeStartGuard 受け入れテスト（AC11・柱①ADMINゼロ根治）")
class PurgeStartGuardTest {

    @InjectMocks
    private PurgeStartGuard guard;

    private static final Long USER_ID = 1L;

    @Nested
    @DisplayName("AC11: purge開始マーク後のcancel-withdrawalは拒否、マーク前はcancelが勝つ")
    class Ac11PurgeVsCancel {

        @Test
        @DisplayName("AC11: purge開始マーク前はcancel-withdrawalが許可される（purge自体が起動しない）")
        void マーク前はcancelが勝つ() {
            assertThatCode(() -> guard.checkCancelAllowed(USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC11: purge開始マーク後はcancel-withdrawalが拒否される")
        void マーク後はcancelが拒否される() {
            guard.markPurgeStarted(USER_ID);

            assertThatThrownBy(() -> guard.checkCancelAllowed(USER_ID))
                    .isInstanceOf(com.mannschaft.app.common.BusinessException.class);
        }

        @Test
        @DisplayName("AC11: markPurgeStartedは冪等（2回呼んでも状態は変わらない）")
        void markPurgeStartedは冪等() {
            guard.markPurgeStarted(USER_ID);
            guard.markPurgeStarted(USER_ID);

            assertThatCode(() -> {
                if (!guard.isPurgeStarted(USER_ID)) {
                    throw new AssertionError("purge開始マークが記録されていない");
                }
            }).doesNotThrowAnyException();
        }
    }
}
