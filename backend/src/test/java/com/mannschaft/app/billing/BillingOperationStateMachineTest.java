package com.mannschaft.app.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 試練A（第2隊）: Billing Center PR6a の operation 状態機械 AC-5 / AC-10 / AC-11 / AC-12。
 *
 * <p><b>測っているのは成果物である</b>: 「どのメソッドを何回呼んだか」ではなく、
 * 状態機械が下す<b>判定そのもの</b>（辺の許否・step の値・列挙の値集合・Stripe へ渡す文字列）を測る。
 * DB を要する成果（行の有無・pointer の解放）は {@code BillingContractOperationSagaIT} が測る。</p>
 *
 * <p>AC-10 は殿の指示どおり<b>各辺を個別のテストへ落として</b>いる。とりわけ
 * {@code RECONCILIATION_REQUIRED -> terminal} の3辺は、欠けると AC-9 の reconcile 自身が
 * 状態機械に拒否されて成立しなくなるため、1辺=1テストで独立に固定する。</p>
 */
@DisplayName("試練A: PR6a operation 状態機械（AC-5/10/11/12）")
class BillingOperationStateMachineTest {

    private static final Path V196_PATH =
            Paths.get("src/main/resources/db/migration/V196.20260831142049__expand_billing_center.sql");

    // ================================================================
    // AC-10 許可される辺（各辺を個別のテストへ）
    // ================================================================

    @Nested
    @DisplayName("AC-10 許可される辺（1辺=1テスト）")
    class AllowedEdges {

        @Test
        @DisplayName("AC-10: CREATED -> CALLING_STRIPE を許す")
        void createdToCallingStripe() {
            assertThat(BillingOperationTransitions.isAllowed(
                    BillingOperationStatus.CREATED, BillingOperationStatus.CALLING_STRIPE)).isTrue();
        }

        @Test
        @DisplayName("AC-10: CREATED -> CANCELLED を許す（D8 停止窓(a) の回収でStripe呼出前に取消）")
        void createdToCancelled() {
            assertThat(BillingOperationTransitions.isAllowed(
                    BillingOperationStatus.CREATED, BillingOperationStatus.CANCELLED)).isTrue();
        }

        @Test
        @DisplayName("AC-10: CALLING_STRIPE -> APPLIED を許す")
        void callingStripeToApplied() {
            assertThat(BillingOperationTransitions.isAllowed(
                    BillingOperationStatus.CALLING_STRIPE, BillingOperationStatus.APPLIED)).isTrue();
        }

        @Test
        @DisplayName("AC-10: CALLING_STRIPE -> FAILED を許す")
        void callingStripeToFailed() {
            assertThat(BillingOperationTransitions.isAllowed(
                    BillingOperationStatus.CALLING_STRIPE, BillingOperationStatus.FAILED)).isTrue();
        }

        @Test
        @DisplayName("AC-10: CALLING_STRIPE -> RECONCILIATION_REQUIRED を許す")
        void callingStripeToReconciliationRequired() {
            assertThat(BillingOperationTransitions.isAllowed(
                    BillingOperationStatus.CALLING_STRIPE,
                    BillingOperationStatus.RECONCILIATION_REQUIRED)).isTrue();
        }

        @Test
        @DisplayName("AC-10: CALLING_STRIPE -> CANCELLED を許す")
        void callingStripeToCancelled() {
            assertThat(BillingOperationTransitions.isAllowed(
                    BillingOperationStatus.CALLING_STRIPE, BillingOperationStatus.CANCELLED)).isTrue();
        }

        @Test
        @DisplayName("AC-10: RECONCILIATION_REQUIRED -> APPLIED を許す（この辺が無いと AC-9 の reconcile が拒否される）")
        void reconciliationRequiredToApplied() {
            assertThat(BillingOperationTransitions.isAllowed(
                    BillingOperationStatus.RECONCILIATION_REQUIRED,
                    BillingOperationStatus.APPLIED)).isTrue();
        }

        @Test
        @DisplayName("AC-10: RECONCILIATION_REQUIRED -> FAILED を許す（この辺が無いと AC-9 の reconcile が拒否される）")
        void reconciliationRequiredToFailed() {
            assertThat(BillingOperationTransitions.isAllowed(
                    BillingOperationStatus.RECONCILIATION_REQUIRED,
                    BillingOperationStatus.FAILED)).isTrue();
        }

        @Test
        @DisplayName("AC-10: RECONCILIATION_REQUIRED -> CANCELLED を許す（この辺が無いと AC-9 の reconcile が拒否される）")
        void reconciliationRequiredToCancelled() {
            assertThat(BillingOperationTransitions.isAllowed(
                    BillingOperationStatus.RECONCILIATION_REQUIRED,
                    BillingOperationStatus.CANCELLED)).isTrue();
        }
    }

    // ================================================================
    // AC-10 拒否される辺（逆行・飛び越し・自己遷移・terminal からの離脱）
    // ================================================================

    @Nested
    @DisplayName("AC-10 拒否される辺（逆行・飛び越し）")
    class RejectedEdges {

        /** 許可される辺の全集合。これ以外の 6x6 の組合せは全て拒否されなければならない。 */
        private static final Set<String> ALLOWED = Set.of(
                "CREATED>CALLING_STRIPE",
                "CREATED>CANCELLED",
                "CALLING_STRIPE>APPLIED",
                "CALLING_STRIPE>FAILED",
                "CALLING_STRIPE>RECONCILIATION_REQUIRED",
                "CALLING_STRIPE>CANCELLED",
                "RECONCILIATION_REQUIRED>APPLIED",
                "RECONCILIATION_REQUIRED>FAILED",
                "RECONCILIATION_REQUIRED>CANCELLED");

        @Test
        @DisplayName("AC-10: CREATED -> APPLIED は飛び越しとして拒否する（Stripe を呼ばずに成功にできない）")
        void createdToAppliedIsRejected() {
            assertThat(BillingOperationTransitions.isAllowed(
                    BillingOperationStatus.CREATED, BillingOperationStatus.APPLIED)).isFalse();
            assertThatThrownBy(() -> BillingOperationTransitions.requireAllowed(
                    BillingOperationStatus.CREATED, BillingOperationStatus.APPLIED))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("AC-10: CALLING_STRIPE -> CREATED は逆行として拒否する")
        void callingStripeToCreatedIsRejected() {
            assertThat(BillingOperationTransitions.isAllowed(
                    BillingOperationStatus.CALLING_STRIPE, BillingOperationStatus.CREATED)).isFalse();
        }

        @Test
        @DisplayName("AC-10: APPLIED からはどの状態へも遷移できない（terminal）")
        void appliedIsTerminal() {
            for (BillingOperationStatus to : BillingOperationStatus.values()) {
                assertThat(BillingOperationTransitions.isAllowed(BillingOperationStatus.APPLIED, to))
                        .as("APPLIED -> %s", to).isFalse();
            }
        }

        @Test
        @DisplayName("AC-10: FAILED からはどの状態へも遷移できない（terminal）")
        void failedIsTerminal() {
            for (BillingOperationStatus to : BillingOperationStatus.values()) {
                assertThat(BillingOperationTransitions.isAllowed(BillingOperationStatus.FAILED, to))
                        .as("FAILED -> %s", to).isFalse();
            }
        }

        @Test
        @DisplayName("AC-10: CANCELLED からはどの状態へも遷移できない（terminal）")
        void cancelledIsTerminal() {
            for (BillingOperationStatus to : BillingOperationStatus.values()) {
                assertThat(BillingOperationTransitions.isAllowed(BillingOperationStatus.CANCELLED, to))
                        .as("CANCELLED -> %s", to).isFalse();
            }
        }

        @Test
        @DisplayName("AC-10: 許可した9辺以外の 6x6 組合せは全て拒否する（自己遷移を含む・網羅）")
        void everyOtherEdgeIsRejected() {
            List<String> wronglyAllowed = new ArrayList<>();
            for (BillingOperationStatus from : BillingOperationStatus.values()) {
                for (BillingOperationStatus to : BillingOperationStatus.values()) {
                    String edge = from.name() + ">" + to.name();
                    if (ALLOWED.contains(edge)) {
                        continue;
                    }
                    if (BillingOperationTransitions.isAllowed(from, to)) {
                        wronglyAllowed.add(edge);
                    }
                }
            }
            assertThat(wronglyAllowed).as("許可されていない辺が通っている").isEmpty();
        }

        @Test
        @DisplayName("AC-10: 許可した9辺は requireAllowed が例外を投げない（陽性対照）")
        void allowedEdgesPassRequireAllowed() {
            for (String edge : ALLOWED) {
                String[] parts = edge.split(">");
                BillingOperationStatus from = BillingOperationStatus.valueOf(parts[0]);
                BillingOperationStatus to = BillingOperationStatus.valueOf(parts[1]);
                BillingOperationTransitions.requireAllowed(from, to);
            }
        }
    }

    // ================================================================
    // AC-7 / AC-8 terminal 判定
    // ================================================================

    @Nested
    @DisplayName("AC-7/AC-8 terminal 判定")
    class TerminalClassification {

        @ParameterizedTest
        @EnumSource(value = BillingOperationStatus.class,
                names = {"APPLIED", "FAILED", "CANCELLED"})
        @DisplayName("AC-7: APPLIED / FAILED / CANCELLED は terminal")
        void terminalStatuses(BillingOperationStatus status) {
            assertThat(BillingOperationTransitions.isTerminal(status)).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = BillingOperationStatus.class,
                names = {"CREATED", "CALLING_STRIPE", "RECONCILIATION_REQUIRED"})
        @DisplayName("AC-8: CREATED / CALLING_STRIPE / RECONCILIATION_REQUIRED は terminal ではない（検疫はpointerを保持する）")
        void nonTerminalStatuses(BillingOperationStatus status) {
            assertThat(BillingOperationTransitions.isTerminal(status)).isFalse();
        }
    }

    // ================================================================
    // AC-11 step の値集合と kind ごとの遷移列
    // ================================================================

    @Nested
    @DisplayName("AC-11 step 列の値集合と kind ごとの遷移列")
    class StepColumn {

        @Test
        @DisplayName("AC-11: step の値集合は12値で固定され、全ての値が VARCHAR(32) に収まる")
        void stepValueSetIsFixedAndFitsColumn() {
            assertThat(BillingOperationStep.values()).extracting(Enum::name)
                    .containsExactlyInAnyOrder(
                            "RECEIVED",
                            "STRIPE_CANCEL_SUBSCRIPTION",
                            "STRIPE_RESUME_SUBSCRIPTION",
                            "STRIPE_SCHEDULE_DOWNGRADE",
                            "STRIPE_APPLY_PLAN_CHANGE",
                            "STRIPE_MIGRATION_SETUP",
                            "STRIPE_MIGRATION_CUTOVER",
                            "STRIPE_REPRICE_SCHEDULE",
                            "STRIPE_REFUND_ISSUE",
                            "RECONCILE_PENDING",
                            "FINALIZED",
                            "ABORTED");
            for (BillingOperationStep step : BillingOperationStep.values()) {
                assertThat(step.name().length())
                        .as("step=%s は VARCHAR(32) に収まらない", step).isLessThanOrEqualTo(32);
            }
        }

        @Test
        @DisplayName("AC-11: CREATED の step は RECEIVED（NOT NULL を満たす初期値が必ず入る）")
        void createdStepIsReceivedForEveryKind() {
            for (BillingOperationKind kind : BillingOperationKind.values()) {
                assertThat(BillingOperationTransitions.stepFor(kind, BillingOperationStatus.CREATED))
                        .as("kind=%s の初期 step", kind)
                        .isEqualTo(BillingOperationStep.RECEIVED);
            }
        }

        @Test
        @DisplayName("AC-11: CANCEL の CALLING_STRIPE は STRIPE_CANCEL_SUBSCRIPTION")
        void cancelCallingStripeStep() {
            assertThat(BillingOperationTransitions.stepFor(
                    BillingOperationKind.CANCEL, BillingOperationStatus.CALLING_STRIPE))
                    .isEqualTo(BillingOperationStep.STRIPE_CANCEL_SUBSCRIPTION);
        }

        @Test
        @DisplayName("AC-11: RESUME の CALLING_STRIPE は STRIPE_RESUME_SUBSCRIPTION")
        void resumeCallingStripeStep() {
            assertThat(BillingOperationTransitions.stepFor(
                    BillingOperationKind.RESUME, BillingOperationStatus.CALLING_STRIPE))
                    .isEqualTo(BillingOperationStep.STRIPE_RESUME_SUBSCRIPTION);
        }

        @Test
        @DisplayName("AC-11: DOWNGRADE_TO_CANCEL の CALLING_STRIPE は STRIPE_SCHEDULE_DOWNGRADE")
        void downgradeToCancelCallingStripeStep() {
            assertThat(BillingOperationTransitions.stepFor(
                    BillingOperationKind.DOWNGRADE_TO_CANCEL, BillingOperationStatus.CALLING_STRIPE))
                    .isEqualTo(BillingOperationStep.STRIPE_SCHEDULE_DOWNGRADE);
        }

        @Test
        @DisplayName("AC-11: RECONCILIATION_REQUIRED の step は全 kind 共通で RECONCILE_PENDING")
        void quarantineStepIsCommon() {
            for (BillingOperationKind kind : BillingOperationKind.values()) {
                assertThat(BillingOperationTransitions.stepFor(
                        kind, BillingOperationStatus.RECONCILIATION_REQUIRED))
                        .as("kind=%s", kind).isEqualTo(BillingOperationStep.RECONCILE_PENDING);
            }
        }

        @Test
        @DisplayName("AC-11: APPLIED の step は FINALIZED、FAILED/CANCELLED の step は ABORTED（全 kind 共通）")
        void terminalStepsAreCommon() {
            for (BillingOperationKind kind : BillingOperationKind.values()) {
                assertThat(BillingOperationTransitions.stepFor(kind, BillingOperationStatus.APPLIED))
                        .as("kind=%s APPLIED", kind).isEqualTo(BillingOperationStep.FINALIZED);
                assertThat(BillingOperationTransitions.stepFor(kind, BillingOperationStatus.FAILED))
                        .as("kind=%s FAILED", kind).isEqualTo(BillingOperationStep.ABORTED);
                assertThat(BillingOperationTransitions.stepFor(kind, BillingOperationStatus.CANCELLED))
                        .as("kind=%s CANCELLED", kind).isEqualTo(BillingOperationStep.ABORTED);
            }
        }

        @Test
        @DisplayName("AC-11: 新規 operation Entity は step を指定しなくても RECEIVED / CREATED が入る（NOT NULL 充足）")
        void entityPrePersistFillsNotNullColumns() {
            BillingContractOperationEntity entity = BillingContractOperationEntity.builder()
                    .contractId(UUID.randomUUID())
                    .billingCustomerId(UUID.randomUUID())
                    .kind(BillingOperationKind.CANCEL)
                    .idempotencyKey(UUID.randomUUID().toString())
                    .requestHash("0".repeat(64))
                    .actorKind(BillingOperationActorKind.SYSTEM)
                    .build();
            invokePrePersist(entity);
            assertThat(entity.getStep()).isEqualTo(BillingOperationStep.RECEIVED);
            assertThat(entity.getStatus()).isEqualTo(BillingOperationStatus.CREATED);
            assertThat(entity.getVersion()).isEqualTo(0L);
        }

        private void invokePrePersist(BillingContractOperationEntity entity) {
            try {
                var method = BillingContractOperationEntity.class.getDeclaredMethod("onCreate");
                method.setAccessible(true);
                method.invoke(entity);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("@PrePersist の呼び出しに失敗した", e);
            }
        }
    }

    // ================================================================
    // AC-12 Java 列挙 と V196 の CHECK 制約の一致
    // ================================================================

    @Nested
    @DisplayName("AC-12 Java 列挙と V196 CHECK 制約の一致")
    class DdlAlignment {

        @Test
        @DisplayName("AC-12: kind の Java 列挙は V196 の chk_bco_kind と完全一致し、ちょうど7値である")
        void kindMatchesCheckConstraint() {
            Set<String> ddl = extractCheckValues(readV196(), "chk_bco_kind");
            Set<String> java = Arrays.stream(BillingOperationKind.values())
                    .map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
            assertThat(ddl).hasSize(7);
            assertThat(java).isEqualTo(ddl);
        }

        @Test
        @DisplayName("AC-12: status の Java 列挙は V196 の chk_bco_status と完全一致し、ちょうど6値である")
        void statusMatchesCheckConstraint() {
            Set<String> ddl = extractCheckValues(readV196(), "chk_bco_status");
            Set<String> java = Arrays.stream(BillingOperationStatus.values())
                    .map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
            assertThat(ddl).hasSize(6);
            assertThat(java).isEqualTo(ddl);
        }

        @Test
        @DisplayName("AC-12: actor_kind の Java 列挙は USER/SYSTEM の2値であり、created_by の NULL 許容と対になる")
        void actorKindMatchesCheckConstraint() {
            assertThat(EnumSet.allOf(BillingOperationActorKind.class))
                    .containsExactlyInAnyOrder(
                            BillingOperationActorKind.USER, BillingOperationActorKind.SYSTEM);
            String sql = readV196();
            assertThat(sql).contains("chk_bco_actor");
            assertThat(sql).contains("actor_kind = 'USER' AND created_by IS NOT NULL");
            assertThat(sql).contains("actor_kind = 'SYSTEM' AND created_by IS NULL");
        }

        @Test
        @DisplayName("AC-12: step 列は V196 に CHECK が無く VARCHAR(32) NOT NULL である（実装側だけが値集合の防波堤）")
        void stepColumnHasNoCheckConstraint() {
            String sql = readV196();
            assertThat(sql).contains("step VARCHAR(32) NOT NULL");
            assertThat(sql).doesNotContain("chk_bco_step");
        }
    }

    // ================================================================
    // AC-5 Stripe 冪等キー
    // ================================================================

    @Nested
    @DisplayName("AC-5 Stripe 冪等キー")
    class IdempotencyKey {

        @Test
        @DisplayName("AC-5: Stripe 呼び出しの Idempotency-Key は billing-operation-{operationId}")
        void keyIsOperationScoped() {
            UUID operationId = UUID.fromString("0199ab01-2222-7333-8444-555566667777");
            assertThat(BillingContractOperationSagaService.stripeIdempotencyKeyOf(operationId))
                    .isEqualTo("billing-operation-0199ab01-2222-7333-8444-555566667777");
        }

        @Test
        @DisplayName("AC-5: 既存の billing-cancel-* / billing-handover-* とは別名前空間であり、subscriptionRef を含まない")
        void keyIsDistinctFromLegacyNamespaces() {
            UUID operationId = UUID.randomUUID();
            String key = BillingContractOperationSagaService.stripeIdempotencyKeyOf(operationId);
            assertThat(key).doesNotStartWith("billing-cancel-");
            assertThat(key).doesNotStartWith("billing-handover-");
            assertThat(key).doesNotStartWith("billing-purge-");
            assertThat(key).doesNotContain("sub_");
        }

        @Test
        @DisplayName("AC-5: 同一 operation の再試行では同じキーになる（Stripe 側で二重に効かない）")
        void keyIsStableAcrossRetries() {
            UUID operationId = UUID.randomUUID();
            assertThat(BillingContractOperationSagaService.stripeIdempotencyKeyOf(operationId))
                    .isEqualTo(BillingContractOperationSagaService.stripeIdempotencyKeyOf(operationId));
        }
    }

    // ================================================================
    // ヘルパ
    // ================================================================

    private static String readV196() {
        try {
            return Files.readString(V196_PATH, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("V196 migration ファイルが読めない: " + V196_PATH, e);
        }
    }

    private static Set<String> extractCheckValues(String sql, String constraintName) {
        Pattern pattern = Pattern.compile(
                Pattern.quote(constraintName) + "\\s+CHECK\\s*\\([^)]*?IN\\s*\\(([^)]*)\\)",
                Pattern.DOTALL);
        Matcher matcher = pattern.matcher(sql);
        if (!matcher.find()) {
            throw new AssertionError("V196 に制約 " + constraintName + " の IN(...) 値集合が見つからない");
        }
        return Arrays.stream(matcher.group(1).split(","))
                .map(String::trim)
                .map(token -> token.replace("'", ""))
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
