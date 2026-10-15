package com.mannschaft.app.billing;

import com.mannschaft.app.billing.BillingContractOperationSagaService.OperationReservation;
import com.mannschaft.app.billing.BillingContractOperationSagaService.ReserveCommand;
import com.mannschaft.app.billing.api.BillingCustomerEntity;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 試練A（第2隊）: Billing Center PR6a の operation Saga 基盤を<b>実 MySQL</b>で測る
 * （AC-1/3/4/6/7/8/9/13/18/19）。
 *
 * <p><b>@Transactional を付けていないのは意図である</b>。Saga は「tx1 を commit してから Stripe を呼び、
 * tx2 で反映する」という<b>トランザクション分割そのもの</b>が仕様（D1）であり、テストを1つの
 * トランザクションで包むと commit が一度も起きず、分割が壊れていても緑になる（偽の緑）。
 * したがって各テストは実際に commit させ、後片付けは {@link #tearDown()} が行う。</p>
 *
 * <p><b>測っているのは成果物である</b>: 呼び出し回数ではなく、commit 後の
 * {@code billing_contract_operations} / {@code active_billing_contract_operation_pointers} /
 * {@code billing_contracts} の<b>行の実値</b>を、毎回 EntityManager をクリアして DB から読み直して測る。</p>
 *
 * <p>AC-2（tx1 の原子性）は実 DB の CHECK 制約を要するため
 * {@code BillingContractOperationTx1AtomicityIT}、AC-14/AC-17b（並行）は
 * {@code BillingContractOperationConcurrencyIT}、AC-15〜17（D2/D3 の SYSTEM 経路）は
 * {@code BillingContractOperationSystemPathIT}、AC-20/21（旧経路の締め）は
 * {@code BillingContractLegacyMutationGuardIT} が担う。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("試練A: PR6a operation Saga 基盤（実MySQL）")
class BillingContractOperationSagaIT extends AbstractMySqlIntegrationTest {

    private static final String REQUEST_HASH = "a".repeat(64);

    @Autowired private BillingContractOperationSagaService sagaService;
    @Autowired private BillingContractRepository billingContractRepository;
    @Autowired private BillingContractOperationRepository operationRepository;
    @Autowired private ActiveBillingContractOperationPointerRepository pointerRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private Clock clock;
    @PersistenceContext private EntityManager entityManager;

    /** Stripe を叩かせない。AC-4 では「tx1 の中で一度も触っていない」ことの測定器にもなる。 */
    @MockitoBean private BillingPaymentGateway billingPaymentGateway;

    private Long scopeId;
    private Long actorUserId;
    private UUID customerId;
    private UUID contractId;
    private Long contractVersion;

    @BeforeEach
    void setUp() {
        Mockito.reset(billingPaymentGateway);
        scopeId = Math.abs(System.nanoTime() % 1_000_000_000L) + 700_000_000L;
        actorUserId = scopeId;
        transactionTemplate.executeWithoutResult(tx -> {
            customerId = insertCustomer();
            contractId = insertContract();
            entityManager.flush();
            entityManager.clear();
            contractVersion = billingContractRepository.findByIdAndDeletedAtIsNull(contractId)
                    .orElseThrow().getVersion();
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery(
                            "DELETE FROM active_billing_contract_operation_pointers "
                                    + "WHERE contract_id IN (SELECT id FROM billing_contracts WHERE scope_id = :s)")
                    .setParameter("s", scopeId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM billing_contract_operations "
                                    + "WHERE contract_id IN (SELECT id FROM billing_contracts WHERE scope_id = :s)")
                    .setParameter("s", scopeId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_contracts WHERE scope_id = :s")
                    .setParameter("s", scopeId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_customers WHERE scope_id = :s")
                    .setParameter("s", scopeId).executeUpdate();
        });
    }

    // ================================================================
    // AC-1 version CAS
    // ================================================================

    @Nested
    @DisplayName("AC-1 contract の FOR UPDATE と version CAS")
    class VersionCas {

        @Test
        @DisplayName("AC-1: version CAS 不一致の予約は 409（ENTITLEMENT_021）で、operation も pointer も1行も残らない")
        void staleVersionIsRejectedAndLeavesNoRow() {
            assertThatThrownBy(() -> sagaService.reserve(userCancelCommand(contractVersion + 99L)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                    .isEqualTo(EntitlementErrorCode.CHANGE_CONFLICT.getCode());

            assertThat(operationCount()).isZero();
            assertThat(pointerCount()).isZero();
        }

        @Test
        @DisplayName("AC-1: version 一致の予約は成功し、operation 1行と pointer 1行が残る（陽性対照）")
        void matchingVersionReserves() {
            OperationReservation reservation = sagaService.reserve(userCancelCommand(contractVersion));

            assertThat(reservation.status()).isEqualTo(BillingOperationStatus.CREATED);
            assertThat(reservation.step()).isEqualTo(BillingOperationStep.RECEIVED);
            assertThat(operationCount()).isEqualTo(1);
            assertThat(pointerCount()).isEqualTo(1);
            assertThat(reloadPointer().getOperationId()).isEqualTo(reservation.operationId());
        }
    }

    // ================================================================
    // AC-3 pointer 排他
    // ================================================================

    @Nested
    @DisplayName("AC-3 pointer 排他")
    class PointerExclusion {

        @Test
        @DisplayName("AC-3: pointer が既にある contract への別 mutation は 409 で、operation 行が増えない")
        void secondMutationIsRejected() {
            OperationReservation first = sagaService.reserve(userCancelCommand(contractVersion));
            Long versionAfterFirst = reloadContract().getVersion();

            assertThatThrownBy(() -> sagaService.reserve(new ReserveCommand(
                    contractId, BillingOperationKind.RESUME, versionAfterFirst,
                    BillingOperationActorKind.USER, actorUserId, REQUEST_HASH)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                    .isEqualTo(EntitlementErrorCode.CHANGE_CONFLICT.getCode());

            assertThat(operationCount()).isEqualTo(1);
            assertThat(pointerCount()).isEqualTo(1);
            assertThat(reloadPointer().getOperationId()).isEqualTo(first.operationId());
        }

        @Test
        @DisplayName("AC-3: pointer が解放された後なら別 mutation の予約が通る（陽性対照・恒久ロックになっていない）")
        void reservationSucceedsAfterRelease() {
            OperationReservation first = sagaService.reserve(userCancelCommand(contractVersion));
            sagaService.markCallingStripe(first.operationId());
            sagaService.failAndRelease(first.operationId(), "STRIPE_ERROR");

            OperationReservation second = sagaService.reserve(new ReserveCommand(
                    contractId, BillingOperationKind.RESUME, reloadContract().getVersion(),
                    BillingOperationActorKind.USER, actorUserId, REQUEST_HASH));

            assertThat(second.operationId()).isNotEqualTo(first.operationId());
            assertThat(pointerCount()).isEqualTo(1);
            assertThat(reloadPointer().getOperationId()).isEqualTo(second.operationId());
        }
    }

    // ================================================================
    // AC-4 tx1 の中で Stripe を呼ばない / commit 後に見える
    // ================================================================

    @Nested
    @DisplayName("AC-4 tx1 は commit してから Stripe へ進む")
    class CommitBeforeStripe {

        @Test
        @DisplayName("AC-4: reserve は Stripe を一度も呼ばず、戻った時点で operation と pointer が別トランザクションから見える（commit 済み）")
        void reserveCommitsWithoutTouchingStripe() {
            OperationReservation reservation = sagaService.reserve(userCancelCommand(contractVersion));

            // 別トランザクションで読み直す = tx1 が commit されていなければ 0 件になる。
            assertThat(operationCount()).isEqualTo(1);
            assertThat(pointerCount()).isEqualTo(1);
            assertThat(reloadOperation(reservation.operationId()).getStatus())
                    .isEqualTo(BillingOperationStatus.CREATED);

            verifyNoInteractions(billingPaymentGateway);
        }
    }

    // ================================================================
    // AC-6 / AC-7 terminal と pointer 解放
    // ================================================================

    @Nested
    @DisplayName("AC-6/AC-7 terminal 化と pointer 解放")
    class TerminalRelease {

        @Test
        @DisplayName("AC-6: Stripe 失敗で operation が FAILED になり、同一トランザクションで pointer が消える")
        void stripeFailureFailsOperationAndReleasesPointer() {
            OperationReservation reservation = sagaService.reserve(userCancelCommand(contractVersion));
            sagaService.markCallingStripe(reservation.operationId());

            sagaService.failAndRelease(reservation.operationId(), "ENTITLEMENT_015");

            BillingContractOperationEntity operation = reloadOperation(reservation.operationId());
            assertThat(operation.getStatus()).isEqualTo(BillingOperationStatus.FAILED);
            assertThat(operation.getStep()).isEqualTo(BillingOperationStep.ABORTED);
            assertThat(operation.getErrorCode()).isEqualTo("ENTITLEMENT_015");
            assertThat(pointerCount()).isZero();
        }

        @Test
        @DisplayName("AC-7: APPLIED は terminal であり pointer が同一トランザクションで削除される")
        void appliedReleasesPointer() {
            OperationReservation reservation = sagaService.reserve(userCancelCommand(contractVersion));
            sagaService.markCallingStripe(reservation.operationId());

            sagaService.applyAndFinalize(reservation.operationId(), () -> "done");

            assertThat(reloadOperation(reservation.operationId()).getStatus())
                    .isEqualTo(BillingOperationStatus.APPLIED);
            assertThat(pointerCount()).isZero();
        }

        @Test
        @DisplayName("AC-7: FAILED は terminal であり pointer が同一トランザクションで削除される")
        void failedReleasesPointer() {
            OperationReservation reservation = sagaService.reserve(userCancelCommand(contractVersion));
            sagaService.markCallingStripe(reservation.operationId());

            sagaService.failAndRelease(reservation.operationId(), "ENTITLEMENT_015");

            assertThat(reloadOperation(reservation.operationId()).getStatus())
                    .isEqualTo(BillingOperationStatus.FAILED);
            assertThat(pointerCount()).isZero();
        }

        @Test
        @DisplayName("AC-7: CANCELLED は terminal であり pointer が同一トランザクションで削除される（Stripe呼出前の取消）")
        void cancelledReleasesPointer() {
            OperationReservation reservation = sagaService.reserve(userCancelCommand(contractVersion));

            sagaService.cancelAndRelease(reservation.operationId(), null);

            assertThat(reloadOperation(reservation.operationId()).getStatus())
                    .isEqualTo(BillingOperationStatus.CANCELLED);
            assertThat(pointerCount()).isZero();
        }
    }

    // ================================================================
    // AC-8 / AC-9 検疫と reconcile
    // ================================================================

    @Nested
    @DisplayName("AC-8/AC-9 検疫と reconcile")
    class Quarantine {

        @Test
        @DisplayName("AC-8: RECONCILIATION_REQUIRED は terminal ではなく pointer が残る")
        void quarantineKeepsPointer() {
            OperationReservation reservation = sagaService.reserve(userCancelCommand(contractVersion));
            sagaService.markCallingStripe(reservation.operationId());

            sagaService.quarantine(reservation.operationId(), "STRIPE_TIMEOUT");

            BillingContractOperationEntity operation = reloadOperation(reservation.operationId());
            assertThat(operation.getStatus())
                    .isEqualTo(BillingOperationStatus.RECONCILIATION_REQUIRED);
            assertThat(operation.getStep()).isEqualTo(BillingOperationStep.RECONCILE_PENDING);
            assertThat(pointerCount()).as("検疫は pointer を保持する").isEqualTo(1);
        }

        @Test
        @DisplayName("AC-8: 検疫中の contract への利用者起点の mutation は 409 で弾かれる")
        void quarantineBlocksUserMutations() {
            OperationReservation reservation = sagaService.reserve(userCancelCommand(contractVersion));
            sagaService.markCallingStripe(reservation.operationId());
            sagaService.quarantine(reservation.operationId(), "STRIPE_TIMEOUT");

            assertThatThrownBy(() -> sagaService.requireNoActiveOperation(contractId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                    .isEqualTo(EntitlementErrorCode.CHANGE_CONFLICT.getCode());

            assertThatThrownBy(() -> sagaService.reserve(new ReserveCommand(
                    contractId, BillingOperationKind.RESUME, reloadContract().getVersion(),
                    BillingOperationActorKind.USER, actorUserId, REQUEST_HASH)))
                    .isInstanceOf(BusinessException.class);

            assertThat(operationCount()).as("検疫中は新しい operation を起票させない").isEqualTo(1);
        }

        @Test
        @DisplayName("AC-9: reconcile が terminal を確定させたときだけ pointer が解放される")
        void reconcileReleasesPointerOnlyWhenTerminalIsFixed() {
            OperationReservation reservation = sagaService.reserve(userCancelCommand(contractVersion));
            sagaService.markCallingStripe(reservation.operationId());
            sagaService.quarantine(reservation.operationId(), "STRIPE_TIMEOUT");
            assertThat(pointerCount()).as("確定前は解放されない").isEqualTo(1);

            sagaService.reconcile(reservation.operationId(), BillingOperationStatus.APPLIED, null);

            assertThat(reloadOperation(reservation.operationId()).getStatus())
                    .isEqualTo(BillingOperationStatus.APPLIED);
            assertThat(pointerCount()).isZero();
        }

        @Test
        @DisplayName("AC-9: reconcile の確定先に非 terminal（CALLING_STRIPE）を指定すると拒否され、pointer は保持されたままである")
        void reconcileToNonTerminalIsRejected() {
            OperationReservation reservation = sagaService.reserve(userCancelCommand(contractVersion));
            sagaService.markCallingStripe(reservation.operationId());
            sagaService.quarantine(reservation.operationId(), "STRIPE_TIMEOUT");

            assertThatThrownBy(() -> sagaService.reconcile(
                    reservation.operationId(), BillingOperationStatus.CALLING_STRIPE, null))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(reloadOperation(reservation.operationId()).getStatus())
                    .isEqualTo(BillingOperationStatus.RECONCILIATION_REQUIRED);
            assertThat(pointerCount()).isEqualTo(1);
        }
    }

    // ================================================================
    // AC-13 actor_kind
    // ================================================================

    @Nested
    @DisplayName("AC-13 actor_kind と created_by")
    class ActorKind {

        @Test
        @DisplayName("AC-13: SYSTEM 起因の operation は actor_kind=SYSTEM, created_by=NULL で記録される")
        void systemOperationHasNullCreatedBy() {
            OperationReservation reservation = sagaService.reserve(new ReserveCommand(
                    contractId, BillingOperationKind.CANCEL, contractVersion,
                    BillingOperationActorKind.SYSTEM, null, REQUEST_HASH));

            BillingContractOperationEntity operation = reloadOperation(reservation.operationId());
            assertThat(operation.getActorKind()).isEqualTo(BillingOperationActorKind.SYSTEM);
            assertThat(operation.getCreatedBy()).isNull();
        }

        @Test
        @DisplayName("AC-13: USER 起因の operation は actor_kind=USER, created_by=操作者 で記録される（陽性対照）")
        void userOperationCarriesCreatedBy() {
            OperationReservation reservation = sagaService.reserve(userCancelCommand(contractVersion));

            BillingContractOperationEntity operation = reloadOperation(reservation.operationId());
            assertThat(operation.getActorKind()).isEqualTo(BillingOperationActorKind.USER);
            assertThat(operation.getCreatedBy()).isEqualTo(actorUserId);
        }

        @Test
        @DisplayName("AC-13: USER なのに created_by が無い予約は拒否され、行が1つも残らない（chk_bco_actor に反する組合せ）")
        void userWithoutCreatedByIsRejected() {
            assertThatThrownBy(() -> sagaService.reserve(new ReserveCommand(
                    contractId, BillingOperationKind.CANCEL, contractVersion,
                    BillingOperationActorKind.USER, null, REQUEST_HASH)))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(operationCount()).isZero();
            assertThat(pointerCount()).isZero();
        }

        @Test
        @DisplayName("AC-13: SYSTEM なのに created_by がある予約は拒否され、行が1つも残らない（chk_bco_actor に反する組合せ）")
        void systemWithCreatedByIsRejected() {
            assertThatThrownBy(() -> sagaService.reserve(new ReserveCommand(
                    contractId, BillingOperationKind.CANCEL, contractVersion,
                    BillingOperationActorKind.SYSTEM, actorUserId, REQUEST_HASH)))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(operationCount()).isZero();
            assertThat(pointerCount()).isZero();
        }
    }

    // ================================================================
    // AC-18 / AC-19 tx2
    // ================================================================

    @Nested
    @DisplayName("AC-18/AC-19 tx2 での反映と補償")
    class Tx2 {

        @Test
        @DisplayName("AC-18: DB 反映（cancelled_at）は tx1 では行われず、Stripe 成功後の tx2 で確定する")
        void reflectionHappensInTx2NotTx1() {
            OperationReservation reservation = sagaService.reserve(userCancelCommand(contractVersion));
            sagaService.markCallingStripe(reservation.operationId());

            // tx1 commit 直後 = Stripe 呼び出し前。ここで解約が反映されていてはならない。
            assertThat(reloadContract().getCancelledAt())
                    .as("tx1 の時点で cancelled_at が入っているのは D1 違反").isNull();

            LocalDateTime cancelledAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
            sagaService.applyAndFinalize(reservation.operationId(), () -> {
                BillingContractEntity target = billingContractRepository
                        .findByIdAndDeletedAtIsNull(contractId).orElseThrow();
                target.setCancelledAt(cancelledAt);
                return billingContractRepository.save(target);
            });

            assertThat(reloadContract().getCancelledAt()).isNotNull();
            assertThat(reloadOperation(reservation.operationId()).getStatus())
                    .isEqualTo(BillingOperationStatus.APPLIED);
            assertThat(pointerCount()).isZero();
        }

        @Test
        @DisplayName("AC-19: tx2 が落ちたら反映がロールバックされ、operation は RECONCILIATION_REQUIRED、pointer は保持される（黙って成功にしない）")
        void tx2FailureFallsIntoQuarantine() {
            OperationReservation reservation = sagaService.reserve(userCancelCommand(contractVersion));
            sagaService.markCallingStripe(reservation.operationId());

            assertThatThrownBy(() -> sagaService.applyAndFinalize(reservation.operationId(), () -> {
                BillingContractEntity target = billingContractRepository
                        .findByIdAndDeletedAtIsNull(contractId).orElseThrow();
                target.setCancelledAt(LocalDateTime.now(clock));
                billingContractRepository.saveAndFlush(target);
                throw new IllegalStateException("tx2 の途中で落ちた");
            })).isInstanceOf(RuntimeException.class);

            assertThat(reloadContract().getCancelledAt())
                    .as("tx2 の反映はロールバックされていなければならない").isNull();
            assertThat(reloadOperation(reservation.operationId()).getStatus())
                    .isEqualTo(BillingOperationStatus.RECONCILIATION_REQUIRED);
            assertThat(pointerCount()).as("検疫中は pointer を保持する").isEqualTo(1);
        }
    }

    // ================================================================
    // フィクスチャ / DB 実読ヘルパ
    // ================================================================

    private ReserveCommand userCancelCommand(Long expectedVersion) {
        return new ReserveCommand(contractId, BillingOperationKind.CANCEL, expectedVersion,
                BillingOperationActorKind.USER, actorUserId, REQUEST_HASH);
    }

    private UUID insertCustomer() {
        BillingCustomerEntity customer = BillingCustomerEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(scopeId)
                .pspCustomerRef("cus_saga_" + scopeId)
                .status("ACTIVE")
                .provisionAttempts(0)
                .version(0L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        entityManager.persist(customer);
        entityManager.flush();
        return customer.getId();
    }

    private UUID insertContract() {
        return billingContractRepository.save(BillingContractEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(scopeId)
                .contractKind(ContractKind.PLAN)
                .planKey("FULL")
                .status(ContractStatus.ACTIVE)
                .priceJpySnapshot(1200)
                .billingCustomerId(customerId)
                .contractedAt(LocalDateTime.now(clock).minusDays(10))
                .currentPeriodEnd(LocalDateTime.now(clock).plusDays(20).truncatedTo(ChronoUnit.SECONDS))
                .createdBy(actorUserId)
                .payerUserId(actorUserId)
                .pspSubscriptionRef("sub_saga_" + scopeId)
                .build()).getId();
    }

    private long operationCount() {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            Number count = (Number) entityManager.createNativeQuery(
                            "SELECT COUNT(*) FROM billing_contract_operations WHERE contract_id = :c")
                    .setParameter("c", contractId).getSingleResult();
            return count.longValue();
        });
    }

    private long pointerCount() {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            Number count = (Number) entityManager.createNativeQuery(
                            "SELECT COUNT(*) FROM active_billing_contract_operation_pointers "
                                    + "WHERE contract_id = :c")
                    .setParameter("c", contractId).getSingleResult();
            return count.longValue();
        });
    }

    private ActiveBillingContractOperationPointerEntity reloadPointer() {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return pointerRepository.findById(contractId).orElseThrow();
        });
    }

    private BillingContractOperationEntity reloadOperation(UUID operationId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return operationRepository.findByIdAndDeletedAtIsNull(operationId).orElseThrow();
        });
    }

    private BillingContractEntity reloadContract() {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return billingContractRepository.findByIdAndDeletedAtIsNull(contractId).orElseThrow();
        });
    }
}
