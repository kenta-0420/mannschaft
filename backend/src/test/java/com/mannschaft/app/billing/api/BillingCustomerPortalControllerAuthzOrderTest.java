package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.CreateBillingCustomerPortalSessionRequest;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * AC-61 の回帰番人: <b>scope 認可が耐久冪等性より先に走る</b>ことを実行で測る。
 *
 * <p><b>なぜこのテストが要るのか</b>（CI で実測した事故・PR #3119 run 33958636964）:
 * 当初の実装は {@code idempotencyService.begin} を先に置いていた。その結果、同一 actor が
 * 同じ {@code Idempotency-Key} を別の scope へ使い回すと、認可判定より先に request hash 不一致が
 * 起きて 409 が返った。この 409 は AC-63「Customer が ACTIVE でない」の 409 と区別が付かず、
 * <b>権限の無い scope について「存在するが ACTIVE でない」と読める存在オラクル</b>になっていた
 * （{@code BillingPortalSessionApiIT.AC61_他scopeは403} が 403 期待に対し 409 を観測）。</p>
 *
 * <p>application service 単体の順序テスト（{@link BillingCustomerPortalApplicationServiceTest}）は
 * 緑のままこの欠陥を見逃した。service へ入る<b>手前</b>に外部状態を触る段が在ったためである。
 * よって入口そのものを対象に据える。</p>
 */
@DisplayName("PR5 Portal 入口の認可順序（AC-61 回帰）")
class BillingCustomerPortalControllerAuthzOrderTest {

    private static final long ACTOR_ID = 5_001L;
    private static final long OTHER_SCOPE_ID = 5_002L;
    private static final String KEY = "key-5001";

    private final List<String> calls = new ArrayList<>();
    private final BillingDurableIdempotencyService idempotencyService =
            mock(BillingDurableIdempotencyService.class);
    private final BillingCustomerPortalApplicationService applicationService =
            mock(BillingCustomerPortalApplicationService.class);

    private final BillingCustomerPortalAccessGuard scopeGuard = (actorId, scopeKind, scopeId) -> {
        calls.add("guard");
        if (!(scopeKind == EntitlementScopeKind.USER && actorId == scopeId)) {
            throw new BusinessException(EntitlementErrorCode.SCOPE_FORBIDDEN);
        }
    };

    private final BillingCustomerPortalController controller = new BillingCustomerPortalController(
            scopeGuard, applicationService, idempotencyService, new ObjectMapper());

    private static CreateBillingCustomerPortalSessionRequest request(long scopeId) {
        return new CreateBillingCustomerPortalSessionRequest(EntitlementScopeKind.USER, scopeId);
    }

    @Test
    @DisplayName("AC61_他 scope の要求は 403 で、冪等台帳にも application service にも一切触れない")
    void AC61_認可に落ちた要求は冪等台帳へ触れない() {
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(ACTOR_ID);

            assertThatThrownBy(() -> controller.createPortalSession(request(OTHER_SCOPE_ID), KEY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(EntitlementErrorCode.SCOPE_FORBIDDEN);
        }

        assertThat(calls).as("認可だけが走ったこと").containsExactly("guard");
        // begin が走ると request hash 不一致 409 が認可より先に返り、403 と 409 が入れ替わる。
        verifyNoInteractions(idempotencyService);
        verifyNoInteractions(applicationService);
    }

    @Test
    @DisplayName("AC61_認可を通った要求だけが冪等台帳へ進む")
    void AC61_認可を通れば冪等台帳へ進む() {
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(ACTOR_ID);
            // begin の戻り値を作らずに済ませるため、begin 到達自体を例外で観測する。
            org.mockito.Mockito.when(idempotencyService.begin(
                            anyLong(), anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new BusinessException(EntitlementErrorCode.CHANGE_CONFLICT));

            assertThatThrownBy(() -> controller.createPortalSession(request(ACTOR_ID), KEY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(EntitlementErrorCode.CHANGE_CONFLICT);
        }

        assertThat(calls).containsExactly("guard");
    }
}
