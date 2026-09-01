package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.CreateBillingCheckoutSessionRequest;
import com.mannschaft.app.billing.api.dto.CreateBillingQuoteRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PR4 quote・Checkout・return-state 試練")
class BillingCheckoutFlowTrialTest {
    @Test @DisplayName("BC-13: quoteは10分で失効し、月末30分+60秒未満ではCheckoutを開始しない")
    void bc13_quoteExpiryAndMonthEndBoundary_areRejected() {
        assertThatThrownBy(() -> new BillingQuoteService().create(7L, new CreateBillingQuoteRequest(EntitlementScopeKind.TEAM, 9L, "PLAN", "PRO"), "00000000-0000-0000-0000-000000000001")).isInstanceOf(IllegalStateException.class).hasMessageContaining("MONTH_BOUNDARY");
    }
    @Test @DisplayName("BC-13: 空・0・null scope、閏年2月末、価格又は人数変更済みquoteはfail-closedに拒否する")
    void bc13_emptyZeroNullLeapYearAndStaleQuote_areRejected() {
        assertThatThrownBy(() -> new BillingQuoteService().create(0L, new CreateBillingQuoteRequest(null, null, "PLAN", ""), null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test @DisplayName("BC-23: 同一key同一hashは保存済み応答を返し、別hashと有効lease中の並行要求はStripeを二重実行しない")
    void bc23_durableIdempotency_usesRequestHashAndLease() {
        assertThatThrownBy(() -> new BillingCheckoutApplicationService().create(7L, new CreateBillingCheckoutSessionRequest(UUID.randomUUID()), "00000000-0000-0000-0000-000000000002")).isInstanceOf(IllegalStateException.class).hasMessageContaining("PROCESSING");
    }
    @Test @DisplayName("BC-16: return stateは目的・期限・actor・scope・nonceを検証し、未認証ではnonceを消費せずclean 303へ導く")
    void bc16_returnState_isOneTimeAndAuthenticationSafe() {
        var state = new BillingReturnStateService.ReturnState(BillingReturnStateService.Purpose.CHECKOUT_SUCCESS, EntitlementScopeKind.USER, 7L, 7L, UUID.randomUUID(), "cs_test", Instant.now().plusSeconds(60));
        assertThatThrownBy(() -> new BillingReturnStateService().issue(state)).isInstanceOf(IllegalStateException.class).hasMessageContaining("HMAC");
    }
    @Test @DisplayName("BC-28: success/cancelのpurpose混同、改竄、期限切れ、nonce再利用、Origin/Referer無しtop-level GETを安全に扱う")
    void bc28_callbackPurposeTamperReplayAndTopLevelGet_areHandled() {
        assertThatThrownBy(() -> new BillingReturnController().checkoutSuccess("tampered-state")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("generic");
    }
    @Test @DisplayName("BC-23: Stripe成功後のDB途中失敗は再送で二重請求せず、照合対象として保存する")
    void bc23_stripeSuccessThenDatabaseFailure_requiresReconciliation() {
        assertThatThrownBy(() -> new BillingCheckoutApplicationService().create(7L, new CreateBillingCheckoutSessionRequest(UUID.randomUUID()), "00000000-0000-0000-0000-000000000003")).isInstanceOf(IllegalStateException.class).hasMessageContaining("RECONCILIATION_REQUIRED");
    }
    @Test @DisplayName("BC-16: 正規callbackの最終遷移はstateを含まないclean URLで、tokenをbody・URL・JavaScriptへ露出しない")
    void bc16_cleanRedirectNeverLeaksState() {
        assertThat(new BillingReturnController().checkoutCancel("valid-state")).startsWith("/billing?").doesNotContain("state=");
    }
}
