package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.auth.service.PurgeMarkerService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.gdpr.GdprErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 柱①「ADMINゼロ根治」AC11 — {@link PurgeStartGuard} の受け入れテスト。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §12.5。
 * 「開始マーク」の実体は {@code users.purge_started_at}（V197）。永続化は
 * auth ドメインの狭い窓口 {@link PurgeMarkerService} に委ね（D-3/D-5 越境Repository禁止）、
 * ここでは Java 側の勝敗判定ロジックを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurgeStartGuard 受け入れテスト（AC11・柱①ADMINゼロ根治）")
class PurgeStartGuardTest {

    @Mock
    private PurgeMarkerService purgeMarkerService;

    @InjectMocks
    private PurgeStartGuard guard;

    private static final Long USER_ID = 1L;

    @Nested
    @DisplayName("AC11: purge開始マーク後のcancel-withdrawalは拒否、マーク前はcancelが勝つ")
    class Ac11PurgeVsCancel {

        @Test
        @DisplayName("AC11: purge開始マーク前はcancel-withdrawalが許可される（purge自体が起動しない）")
        void マーク前はcancelが勝つ() {
            given(purgeMarkerService.isPurgeStarted(USER_ID)).willReturn(false);

            assertThatCode(() -> guard.checkCancelAllowed(USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC11: purge開始マーク後はcancel-withdrawalが拒否される（GDPR_012・409）")
        void マーク後はcancelが拒否される() {
            given(purgeMarkerService.isPurgeStarted(USER_ID)).willReturn(true);

            assertThatThrownBy(() -> guard.checkCancelAllowed(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(GdprErrorCode.GDPR_012));
        }

        @Test
        @DisplayName("AC11: markPurgeStartedはPurgeMarkerServiceへ委譲する")
        void markPurgeStartedは委譲する() {
            guard.markPurgeStarted(USER_ID);
            guard.markPurgeStarted(USER_ID);

            verify(purgeMarkerService, times(2)).markPurgeStarted(USER_ID);
        }

        @Test
        @DisplayName("AC11: isPurgeStartedはPurgeMarkerServiceの値をそのまま返す")
        void isPurgeStartedはflagを反映する() {
            given(purgeMarkerService.isPurgeStarted(USER_ID)).willReturn(true);

            assertThat(guard.isPurgeStarted(USER_ID)).isTrue();
        }
    }
}
