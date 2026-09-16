package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** BC-16/28: key-id rotation付きHMAC stateとnonce hash CASの試練。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PR4 HMAC return state 試練")
class BillingReturnStateServiceTrialTest {
    private static final Instant NOW = Instant.parse("2028-02-10T03:00:00Z");
    private static final byte[] CURRENT_SECRET = "current-return-state-secret-32bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OLD_SECRET = "previous-return-state-secret-32byt".getBytes(StandardCharsets.UTF_8);

    @Mock private BillingReturnSigningKeyProvider keyProvider;
    @Mock private BillingReturnStateNonceRepository nonceRepository;

    private BillingReturnStateService service;

    @BeforeEach
    void setUp() {
        service = new BillingReturnStateService(Clock.fixed(NOW, ZoneOffset.UTC), keyProvider, nonceRepository);
        given(keyProvider.activeKey()).willReturn(
                new BillingReturnSigningKeyProvider.SigningKey("kid-current", CURRENT_SECRET));
    }

    @Test
    @DisplayName("BC-16: issueはpurpose/actor/scope/tab/iat/expをHMACしnonceはhashだけ保存する")
    void issue_束縛payloadを署名しnonce平文を保存しない() {
        BillingReturnStateService.ReturnState state = checkoutState(
                BillingReturnStateService.Purpose.CHECKOUT_SUCCESS, NOW.plusSeconds(3600), "nonce-raw-value");

        String token = service.issue(state);

        assertThat(token).startsWith("kid-current.");
        assertThat(token).doesNotContain("nonce-raw-value");
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(nonceRepository).register(hashCaptor.capture(),
                eq(BillingReturnStateService.Purpose.CHECKOUT_SUCCESS), eq(7L),
                eq(EntitlementScopeKind.TEAM), eq(91L), eq(NOW.plusSeconds(3600)));
        assertThat(hashCaptor.getValue()).hasSize(64).doesNotContain("nonce-raw-value");
    }

    @Test
    @DisplayName("BC-16: verifyはtokenのkidで旧鍵も検索しrotation中の正規tokenを受理する")
    void verify_旧kidの正規token_rotation中も受理する() {
        given(keyProvider.findByKid("kid-old")).willReturn(Optional.of(
                new BillingReturnSigningKeyProvider.SigningKey("kid-old", OLD_SECRET)));
        String token = issueWithOldKey(checkoutState(
                BillingReturnStateService.Purpose.CHECKOUT_CANCEL, NOW.plusSeconds(3600), "nonce-old"));

        BillingReturnStateService.ReturnState verified = service.verify(
                token, BillingReturnStateService.Purpose.CHECKOUT_CANCEL);

        assertThat(verified.actorId()).isEqualTo(7L);
        assertThat(verified.scopeKind()).isEqualTo(EntitlementScopeKind.TEAM);
        assertThat(verified.scopeId()).isEqualTo(91L);
        assertThat(verified.tab()).isEqualTo("plan");
    }

    @Test
    @DisplayName("BC-16/28: purpose混同・改竄・期限切れ・未知kidは同じgeneric例外でtokenを露出しない")
    void verify_不正state_genericでfailClosedする() {
        String[] invalidTokens = {
                "kid-current.tampered.signature",
                "kid-unknown.payload.signature",
                issueWithOldKey(checkoutState(
                        BillingReturnStateService.Purpose.CHECKOUT_SUCCESS, NOW.minusSeconds(1), "expired"))
        };

        for (String token : invalidTokens) {
            assertThatThrownBy(() -> service.verify(token, BillingReturnStateService.Purpose.PORTAL_RETURN))
                    .isInstanceOf(BillingReturnStateException.class)
                    .hasMessageNotContaining(token)
                    .hasMessageNotContaining("91");
        }
    }

    @Test
    @DisplayName("BC-16/28: nonce消費はpurpose/actor/scope/hashをCAS条件にし再利用を拒否する")
    void consumeNonce_全束縛条件で一回だけCASする() {
        BillingReturnStateService.ReturnState state = checkoutState(
                BillingReturnStateService.Purpose.PAYMENT_ACTION_RETURN, NOW.plusSeconds(900), "nonce-once");
        given(nonceRepository.consumeIfValid(any(), eq(BillingReturnStateService.Purpose.PAYMENT_ACTION_RETURN),
                eq(7L), eq(EntitlementScopeKind.TEAM), eq(91L), eq(NOW)))
                .willReturn(1, 0);

        service.consumeNonce(state, 7L);

        assertThatThrownBy(() -> service.consumeNonce(state, 7L))
                .isInstanceOf(BillingReturnStateException.class)
                .hasMessageNotContaining("nonce-once");
    }

    @Test
    @DisplayName("BC-16: Checkout state expはSession expiry+15分かつ発行から最大24時間")
    void issue_CheckoutExpiry上限を超えるstateを拒否する() {
        BillingReturnStateService.ReturnState overMax = checkoutState(
                BillingReturnStateService.Purpose.CHECKOUT_SUCCESS, NOW.plusSeconds(86401), "nonce-over-max");

        assertThatThrownBy(() -> service.issue(overMax))
                .isInstanceOf(BillingReturnStateException.class)
                .hasMessageNotContaining("nonce-over-max");
    }

    private BillingReturnStateService.ReturnState checkoutState(
            BillingReturnStateService.Purpose purpose, Instant expiresAt, String nonce) {
        return new BillingReturnStateService.ReturnState(purpose, EntitlementScopeKind.TEAM, 91L,
                7L, "plan", UUID.fromString("01999d74-5130-7000-8000-000000000030"),
                "cs_test_safe", UUID.fromString("01999d74-5130-7000-8000-000000000031"),
                NOW, expiresAt, nonce);
    }

    private String issueWithOldKey(BillingReturnStateService.ReturnState state) {
        given(keyProvider.activeKey()).willReturn(
                new BillingReturnSigningKeyProvider.SigningKey("kid-old", OLD_SECRET));
        return service.issue(state);
    }
}
