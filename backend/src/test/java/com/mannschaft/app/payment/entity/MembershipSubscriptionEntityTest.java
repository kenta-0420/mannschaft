package com.mannschaft.app.payment.entity;

import com.mannschaft.app.payment.BillingInterval;
import com.mannschaft.app.payment.MembershipSubscriptionStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MembershipSubscriptionEntity} の状態遷移（ガード付き・不正遷移は例外）の単体テスト。
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §4.2 の状態遷移図に対応。</p>
 */
@DisplayName("MembershipSubscriptionEntity 状態遷移")
class MembershipSubscriptionEntityTest {

    private static MembershipSubscriptionEntity newPending() {
        return MembershipSubscriptionEntity.builder()
                .organizationId(100L)
                .paymentItemId(10L)
                .beneficiaryUserId(1L)
                .payerUserId(2L)
                .scopeKind(ScopeKind.TEAM)
                .scopeId(50L)
                .payeeConnectAccountId(UUID.randomUUID())
                .billingInterval(BillingInterval.MONTHLY)
                .faceAmount(1000)
                .build();
    }

    @Nested
    @DisplayName("markActive（PENDING → ACTIVE）")
    class MarkActive {

        @Test
        @DisplayName("PENDING から ACTIVE に遷移し期間をセットする")
        void pendingToActive() {
            MembershipSubscriptionEntity sub = newPending();
            assertThat(sub.getStatus()).isEqualTo(MembershipSubscriptionStatus.PENDING);

            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = LocalDate.of(2026, 6, 30);
            sub.markActive(start, end);

            assertThat(sub.getStatus()).isEqualTo(MembershipSubscriptionStatus.ACTIVE);
            assertThat(sub.getCurrentPeriodStart()).isEqualTo(start);
            assertThat(sub.getCurrentPeriodEnd()).isEqualTo(end);
        }

        @Test
        @DisplayName("ACTIVE から markActive は不正遷移で例外")
        void activeToActiveThrows() {
            MembershipSubscriptionEntity sub = newPending();
            sub.markActive(LocalDate.now(), LocalDate.now().plusMonths(1));

            assertThatThrownBy(() -> sub.markActive(LocalDate.now(), LocalDate.now().plusMonths(1)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("PAST_DUE 往復（ACTIVE → PAST_DUE → ACTIVE）")
    class PastDueRoundTrip {

        @Test
        @DisplayName("ACTIVE → PAST_DUE → ACTIVE 復帰で期間を 1 サイクル延長する")
        void activePastDueRecover() {
            MembershipSubscriptionEntity sub = newPending();
            sub.markActive(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            sub.markPastDue();
            assertThat(sub.getStatus()).isEqualTo(MembershipSubscriptionStatus.PAST_DUE);

            LocalDate newStart = LocalDate.of(2026, 7, 1);
            LocalDate newEnd = LocalDate.of(2026, 7, 31);
            sub.markRecovered(newStart, newEnd);

            assertThat(sub.getStatus()).isEqualTo(MembershipSubscriptionStatus.ACTIVE);
            assertThat(sub.getCurrentPeriodStart()).isEqualTo(newStart);
            assertThat(sub.getCurrentPeriodEnd()).isEqualTo(newEnd);
        }

        @Test
        @DisplayName("PENDING から markPastDue は不正遷移で例外")
        void pendingToPastDueThrows() {
            MembershipSubscriptionEntity sub = newPending();
            assertThatThrownBy(sub::markPastDue).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("ACTIVE から markRecovered は不正遷移で例外")
        void activeToRecoveredThrows() {
            MembershipSubscriptionEntity sub = newPending();
            sub.markActive(LocalDate.now(), LocalDate.now().plusMonths(1));
            assertThatThrownBy(() -> sub.markRecovered(LocalDate.now(), LocalDate.now().plusMonths(1)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("markCancelled（解約・期末 CANCELLED）")
    class MarkCancelled {

        @Test
        @DisplayName("ACTIVE → CANCELLED に遷移し cancelledAt を記録する")
        void activeToCancelled() {
            MembershipSubscriptionEntity sub = newPending();
            sub.markActive(LocalDate.now(), LocalDate.now().plusMonths(1));

            sub.markCancelled();

            assertThat(sub.getStatus()).isEqualTo(MembershipSubscriptionStatus.CANCELLED);
            assertThat(sub.getCancelledAt()).isNotNull();
        }

        @Test
        @DisplayName("PAST_DUE からも CANCELLED に遷移できる")
        void pastDueToCancelled() {
            MembershipSubscriptionEntity sub = newPending();
            sub.markActive(LocalDate.now(), LocalDate.now().plusMonths(1));
            sub.markPastDue();

            sub.markCancelled();

            assertThat(sub.getStatus()).isEqualTo(MembershipSubscriptionStatus.CANCELLED);
        }

        @Test
        @DisplayName("既に CANCELLED から再度 markCancelled は不正遷移で例外")
        void cancelledToCancelledThrows() {
            MembershipSubscriptionEntity sub = newPending();
            sub.markActive(LocalDate.now(), LocalDate.now().plusMonths(1));
            sub.markCancelled();

            assertThatThrownBy(sub::markCancelled).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("期末解約予約（cancel_at_period_end）")
    class CancelAtPeriodEnd {

        @Test
        @DisplayName("ACTIVE で期末解約を予約し、取り消せる")
        void scheduleAndClear() {
            MembershipSubscriptionEntity sub = newPending();
            sub.markActive(LocalDate.now(), LocalDate.now().plusMonths(1));

            sub.scheduleCancelAtPeriodEnd();
            assertThat(sub.getCancelAtPeriodEnd()).isTrue();
            assertThat(sub.getStatus()).isEqualTo(MembershipSubscriptionStatus.ACTIVE);

            sub.clearCancelAtPeriodEnd();
            assertThat(sub.getCancelAtPeriodEnd()).isFalse();
        }

        @Test
        @DisplayName("PENDING からの期末解約予約は不正遷移で例外")
        void scheduleFromPendingThrows() {
            MembershipSubscriptionEntity sub = newPending();
            assertThatThrownBy(sub::scheduleCancelAtPeriodEnd).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("予約していないのに clearCancelAtPeriodEnd は例外")
        void clearWithoutScheduleThrows() {
            MembershipSubscriptionEntity sub = newPending();
            sub.markActive(LocalDate.now(), LocalDate.now().plusMonths(1));
            assertThatThrownBy(sub::clearCancelAtPeriodEnd).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("今月スキップ（skip_until・ガード）")
    class Skip {

        @Test
        @DisplayName("ACTIVE で skip_until をセットし、解除できる。status は ACTIVE のまま")
        void applyAndClearSkip() {
            MembershipSubscriptionEntity sub = newPending();
            sub.markActive(LocalDate.now(), LocalDate.now().plusMonths(1));

            LocalDate resumesAt = LocalDate.now().plusMonths(1);
            sub.applySkipUntil(resumesAt);
            assertThat(sub.getSkipUntil()).isEqualTo(resumesAt);
            assertThat(sub.getStatus()).isEqualTo(MembershipSubscriptionStatus.ACTIVE);

            sub.clearSkip();
            assertThat(sub.getSkipUntil()).isNull();
            assertThat(sub.getStatus()).isEqualTo(MembershipSubscriptionStatus.ACTIVE);
        }

        @Test
        @DisplayName("ACTIVE 以外（PENDING）でのスキップは不正で例外")
        void skipFromPendingThrows() {
            MembershipSubscriptionEntity sub = newPending();
            assertThatThrownBy(() -> sub.applySkipUntil(LocalDate.now().plusMonths(1)))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("既にスキップ済での二重スキップは例外")
        void doubleSkipThrows() {
            MembershipSubscriptionEntity sub = newPending();
            sub.markActive(LocalDate.now(), LocalDate.now().plusMonths(1));
            sub.applySkipUntil(LocalDate.now().plusMonths(1));

            assertThatThrownBy(() -> sub.applySkipUntil(LocalDate.now().plusMonths(2)))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("スキップしていないのに clearSkip は例外")
        void clearWithoutSkipThrows() {
            MembershipSubscriptionEntity sub = newPending();
            sub.markActive(LocalDate.now(), LocalDate.now().plusMonths(1));
            assertThatThrownBy(sub::clearSkip).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Stripe ID 焼き付け")
    class LinkStripeIds {

        @Test
        @DisplayName("linkStripeIds で subscription / customer ID をセットする")
        void linksIds() {
            MembershipSubscriptionEntity sub = newPending();
            sub.linkStripeIds("sub_123", "cus_456");

            assertThat(sub.getStripeSubscriptionId()).isEqualTo("sub_123");
            assertThat(sub.getStripeCustomerId()).isEqualTo("cus_456");
        }
    }
}
