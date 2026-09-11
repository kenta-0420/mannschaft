package com.mannschaft.app.billing;

import com.mannschaft.app.billing.BillingContractOperationSagaService.OperationReservation;
import com.mannschaft.app.billing.BillingContractOperationSagaService.ReserveCommand;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Billing Center PR6a: 期末解約（B群 AC-22〜39）と解約撤回（C群 AC-40〜49）のドメイン側オーケストレーション。
 *
 * <h2>処理順（この順でなければ境界 AC が成立しない）</h2>
 * <ol>
 *   <li>利用者起点の前提検証（引継との排他・再解約・撤回可否）。ここまでは operation を作らない。</li>
 *   <li>tx1（{@link BillingContractOperationSagaService#reserve}）= operation 予約 ＋ pointer 取得 → commit。
 *       Stripe はここでは呼ばない（AC-4）。</li>
 *   <li><b>読み取り専用</b>の {@link BillingPaymentGateway#retrieveSubscription} で Stripe 実物の
 *       {@code current_period_end} を引く。これが期末の<b>権威</b>（AC-34）。</li>
 *   <li>Stripe が null なら DB の {@code current_period_end} へ fallback。<b>両方 null なら 409</b>（AC-37c）。</li>
 *   <li>解決した期末が<b>現在時刻以下</b>なら 409（AC-37 / AC-37b / AC-46）。</li>
 *   <li>ここで初めて Stripe の<b>変更系</b>（{@code cancelAtPeriodEnd} /
 *       {@code revertCancelAtPeriodEnd}）を呼ぶ（AC-39 / AC-47）。</li>
 *   <li>tx2（{@link BillingContractOperationSagaService#applyAndFinalize}）で DB 反映 ＋ APPLIED ＋
 *       pointer DELETE を同一トランザクションで行う（AC-18）。</li>
 * </ol>
 *
 * <p><b>なぜ 4〜5 を変更系より前に置くか</b>: 逆順にすると、409 を返す直前に Stripe へ
 * {@code cancel_at_period_end=true} を送ってしまい「DB は未解約なのに Stripe だけ解約予約済み」という
 * 不整合が残る。期末を解決できない／期末が過ぎている要求では、Stripe への変更系呼び出しを
 * <b>一度も発生させない</b>（AC-37 / AC-37b / AC-37c が実測する）。</p>
 *
 * <p><b>本クラスに {@code @Transactional} を付けてはならない</b>。付けると Saga の tx1/tx2 が
 * 呼び出し元の単一トランザクションへ吸収され、D1 のトランザクション分割（と Stripe 呼び出しを
 * tx の外へ出すこと）が成立しなくなる。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingContractCancelService {

    /** 期末を解決できずに operation を取り消したときの {@code error_code}。 */
    private static final String ERROR_PERIOD_END_UNRESOLVED = "PERIOD_END_UNRESOLVED";
    /** Stripe の変更系呼び出しが失敗して operation を FAILED にしたときの {@code error_code}。 */
    private static final String ERROR_STRIPE_CALL_FAILED = "STRIPE_CALL_FAILED";

    private final BillingContractRepository billingContractRepository;
    private final EntitlementRepository entitlementRepository;
    private final BillingPayerHandoverRequestRepository handoverRequestRepository;
    private final BillingPaymentGateway billingPaymentGateway;
    private final BillingContractOperationSagaService sagaService;
    private final EntitlementCacheEvictor cacheEvictor;
    private final Clock clock;

    /**
     * 解約／撤回の応答に必要な契約の見え方（API 層 DTO 組み立て用）。
     *
     * <p><b>なぜ {@link OffsetDateTime} か</b>: 新規の {@code LocalDateTime} フィールドは番人
     * {@code DateTimeAndZoneGuardTest} が拒否する（暗黙のゾーン依存を増やさないため）。
     * 境界を跨いで持ち回る時刻はオフセットを明示した型で運ぶのが正しく、
     * DB の壁時計 {@code LocalDateTime} 列との変換は注入 {@link Clock} のゾーンで一点に閉じる。</p>
     *
     * @param contractId     契約 ID
     * @param contractStatus 契約そのものの状態（解約予約後も {@code ACTIVE} のまま・AC-23）
     * @param scheduledAt    解約予約を入れた時刻（{@code cancelled_at}。予約なしなら null）
     * @param endAt          利用可能期限（＝{@code current_period_end}。AC-22/AC-37c で非 null 必須）
     * @param version        CAS 用 version（更新後）
     * @param canCancel      解約できるか
     * @param canResume      撤回できるか（期末を跨いだら false・AC-46）
     */
    public record CancelView(
            UUID contractId,
            ContractStatus contractStatus,
            OffsetDateTime scheduledAt,
            OffsetDateTime endAt,
            Long version,
            boolean canCancel,
            boolean canResume) {

        /** 解約予約中か（応答の {@code status} が {@code SCHEDULED} か {@code ACTIVE} かを決める）。 */
        public boolean cancelScheduled() {
            return scheduledAt != null;
        }
    }

    // ================================================================
    // 解約（B群）
    // ================================================================

    /**
     * 有償契約の期末解約を予約する（AC-22〜24 / AC-26 / AC-27 / AC-34〜39 / AC-48）。
     *
     * @param contractId      対象契約
     * @param expectedVersion 利用者が提示した {@code billing_contracts.version}（CAS・AC-27）
     * @param actorUserId     操作者
     * @return 解約予約後の見え方
     */
    public CancelView scheduleCancel(UUID contractId, Long expectedVersion, Long actorUserId) {
        BillingContractEntity contract = loadPaidContract(contractId);
        requireNoActiveHandover(contractId);
        if (contract.getCancelledAt() != null) {
            // AC-26: 既に期末解約を予約済み。Stripe への再送も valid_until の再上書きも起こさない。
            throw new BusinessException(EntitlementErrorCode.CONTRACT_NOT_CANCELLABLE);
        }
        requireCancellableStatus(contract.getStatus());

        OperationReservation reservation = sagaService.reserve(new ReserveCommand(
                contractId, BillingOperationKind.CANCEL, expectedVersion,
                BillingOperationActorKind.USER, actorUserId, null));

        LocalDateTime endAt = resolvePeriodEndOrRelease(reservation, contract);

        callStripeOrFail(reservation, () ->
                billingPaymentGateway.cancelAtPeriodEnd(
                        contract.getPspSubscriptionRef(), reservation.operationId()));

        return sagaService.applyAndFinalize(reservation.operationId(),
                () -> applyCancel(contractId, endAt));
    }

    // ================================================================
    // 解約撤回（C群）
    // ================================================================

    /**
     * 期末解約の予約を撤回する（AC-40〜48）。
     *
     * @param contractId      対象契約
     * @param expectedVersion 利用者が提示した {@code billing_contracts.version}（CAS・AC-44）
     * @param actorUserId     操作者
     * @return 撤回後の見え方
     */
    public CancelView resumeCancel(UUID contractId, Long expectedVersion, Long actorUserId) {
        BillingContractEntity contract = loadPaidContract(contractId);
        requireNoActiveHandover(contractId);
        if (contract.getCancelledAt() == null) {
            // AC-42: 撤回するものが無い。
            throw new BusinessException(EntitlementErrorCode.CONTRACT_NOT_CANCELLABLE);
        }
        // AC-43: 期末を過ぎて EXPIRED / CANCELLED になった契約は復活させない。
        requireCancellableStatus(contract.getStatus());

        OperationReservation reservation = sagaService.reserve(new ReserveCommand(
                contractId, BillingOperationKind.RESUME, expectedVersion,
                BillingOperationActorKind.USER, actorUserId, null));

        // AC-46: 撤回できるのは期末まで。期末を跨いでいれば（webhook 未達で status が ACTIVE のままでも）409。
        LocalDateTime endAt = resolvePeriodEndOrRelease(reservation, contract);

        callStripeOrFail(reservation, () ->
                billingPaymentGateway.revertCancelAtPeriodEnd(
                        contract.getPspSubscriptionRef(), reservation.operationId()));

        return sagaService.applyAndFinalize(reservation.operationId(),
                () -> applyResume(contractId, endAt));
    }

    // ================================================================
    // 表示（Stripe を呼ばない・AC-63）
    // ================================================================

    /**
     * 現在の契約から解約／撤回の可否を導く（Stripe を呼ばない・AC-63）。
     *
     * <p><b>Entity ではなく契約 ID を受け取る</b>: サービスの公開シグネチャに {@code @Entity} を
     * 出さない（D-1 API 境界の番人）。可視性をパッケージ内へ絞る手は使えない —— 呼び出し元の
     * {@code BillingContractCancelApplicationService} は {@code billing.api} パッケージにあり、
     * 絞ると到達できなくなるためである。読み直しは無償契約の即時失効直後に1回だけ起きる。</p>
     *
     * @param contractId 対象契約
     * @return 見え方
     */
    public CancelView viewOf(UUID contractId) {
        BillingContractEntity contract = requireContract(contractId);
        return toView(contract, contract.getCurrentPeriodEnd());
    }

    // ================================================================
    // tx2 の反映処理
    // ================================================================

    /**
     * 停止窓(b) の回収から呼ぶ「tx2 相当」の解約反映（AC-79）。
     *
     * <p>呼び出し元（{@link BillingContractOperationRecoveryService}）のトランザクションに参加する。
     * 反映の中身を回収側へ書き写すと二重実装になり、片方だけ直る事故を生むためここを正本とする。</p>
     *
     * @param contractId 対象契約
     * @param endAt      Stripe 実物の期末（権威・AC-34）
     * @return 反映後の見え方
     */
    CancelView applyRecoveredCancel(UUID contractId, LocalDateTime endAt) {
        return applyCancel(contractId, endAt);
    }

    /**
     * 停止窓(b) の回収から呼ぶ「tx2 相当」の撤回反映（AC-79）。
     *
     * @param contractId 対象契約
     * @param endAt      Stripe 実物の期末（権威・AC-34）
     * @return 反映後の見え方
     */
    CancelView applyRecoveredResume(UUID contractId, LocalDateTime endAt) {
        return applyResume(contractId, endAt);
    }

    /** tx2: {@code cancelled_at} ＋ {@code current_period_end} ＋ entitlements の {@code valid_until}。 */
    private CancelView applyCancel(UUID contractId, LocalDateTime endAt) {
        BillingContractEntity contract = requireContract(contractId);
        contract.setCancelledAt(LocalDateTime.now(clock));
        contract.setCurrentPeriodEnd(endAt);
        bumpVersion(contract);
        billingContractRepository.save(contract);

        // AC-24: 由来 entitlements の valid_until を期末に（半開区間 [from, endAt)。期末ちょうどは無効）。
        // AC-72: 発行数 N に依らず「検索1本＋一括保存1本」の定数本数で済ませる（ループ内クエリにしない）。
        List<String> keys = updateValidUntil(contract, endAt);
        evictAfterCommit(contract, keys);
        return toView(contract, endAt);
    }

    /** tx2: 解約予約の取り消し。{@code valid_until} は D5 に従い NULL（無期限）へ戻す（AC-41）。 */
    private CancelView applyResume(UUID contractId, LocalDateTime endAt) {
        BillingContractEntity contract = requireContract(contractId);
        contract.setCancelledAt(null);
        contract.setCurrentPeriodEnd(endAt);
        bumpVersion(contract);
        billingContractRepository.save(contract);

        List<String> keys = updateValidUntil(contract, null);
        evictAfterCommit(contract, keys);
        return toView(contract, endAt);
    }

    /** 由来 entitlements の {@code valid_until} を一括更新し、対象 feature_key を返す（AC-72）。 */
    private List<String> updateValidUntil(BillingContractEntity contract, LocalDateTime validUntil) {
        List<EntitlementEntity> rows = entitlementRepository
                .findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                        toSourceKind(contract.getContractKind()), contract.getId());
        List<String> keys = new ArrayList<>();
        for (EntitlementEntity e : rows) {
            e.setValidUntil(validUntil);
            keys.add(e.getFeatureKey());
        }
        if (!rows.isEmpty()) {
            entitlementRepository.saveAll(rows);
        }
        return keys;
    }

    // ================================================================
    // 期末の解決（AC-34 / AC-37 / AC-37b / AC-37c / AC-46）
    // ================================================================

    /**
     * 期末を解決する。解決できない／過ぎている場合は operation を {@code CANCELLED} で解放してから 409 を投げる
     * （<b>Stripe への変更系呼び出しは一度も発生しない</b>）。
     */
    private LocalDateTime resolvePeriodEndOrRelease(
            OperationReservation reservation, BillingContractEntity contract) {
        try {
            return resolvePeriodEnd(contract);
        } catch (RuntimeException e) {
            // 予約だけ取って先へ進めない。pointer を握ったままにせず CREATED -> CANCELLED で解放する。
            releaseQuietly(reservation, ERROR_PERIOD_END_UNRESOLVED);
            throw e;
        }
    }

    private LocalDateTime resolvePeriodEnd(BillingContractEntity contract) {
        LocalDateTime stripeEnd = retrieveStripePeriodEnd(contract.getPspSubscriptionRef());
        // AC-34: Stripe を権威として採り、DB 値は Stripe が期末を持たないときの fallback に限る。
        LocalDateTime resolved = stripeEnd != null ? stripeEnd : contract.getCurrentPeriodEnd();
        if (resolved == null) {
            // AC-37c: 正本の endAt は nullable ではない。null のまま 200 を返さず、DB も一切変更しない。
            throw new BusinessException(EntitlementErrorCode.CONTRACT_NOT_CANCELLABLE);
        }
        if (!resolved.isAfter(LocalDateTime.now(clock))) {
            // AC-37 / AC-37b / AC-46: 半開区間の方針に合わせ、期末ちょうども「過ぎている」側に倒す。
            throw new BusinessException(EntitlementErrorCode.CONTRACT_NOT_CANCELLABLE);
        }
        return resolved;
    }

    /** Stripe 実物の期末を<b>読み取り専用</b>で引く（変更系ではない）。 */
    private LocalDateTime retrieveStripePeriodEnd(String subscriptionRef) {
        BillingPaymentGateway.SubscriptionSnapshot snapshot;
        try {
            snapshot = billingPaymentGateway.retrieveSubscription(subscriptionRef);
        } catch (RuntimeException e) {
            // 参照すら通らないなら Stripe 側の障害である。500 で覆い隠さず 502 として上申する。
            throw new BusinessException(EntitlementErrorCode.STRIPE_UNAVAILABLE, e);
        }
        Instant end = snapshot == null ? null : snapshot.currentPeriodEnd();
        return end == null ? null : LocalDateTime.ofInstant(end, clock.getZone());
    }

    // ================================================================
    // Stripe 変更系の呼び出し（AC-35 / AC-39 / AC-47）
    // ================================================================

    /** Stripe の変更系を呼ぶ。失敗したら operation を FAILED で解放し 502 として上申する（AC-35）。 */
    private void callStripeOrFail(OperationReservation reservation, Runnable stripeCall) {
        sagaService.markCallingStripe(reservation.operationId());
        try {
            stripeCall.run();
        } catch (RuntimeException e) {
            // AC-6 / AC-35: FAILED へ CAS し同一トランザクションで pointer を解放する（利用者はやり直せる）。
            sagaService.failAndRelease(reservation.operationId(), ERROR_STRIPE_CALL_FAILED);
            throw new BusinessException(EntitlementErrorCode.STRIPE_UNAVAILABLE, e);
        }
    }

    // ================================================================
    // 前提検証
    // ================================================================

    /**
     * AC-48: 引継との排他は<b>旧契約基準</b>で行う。
     *
     * <p>{@code PENDING_HANDOVER} は引継の<b>新</b>契約の状態であり、利用者が解約・撤回で触る
     * <b>旧</b>契約は最後まで {@code ACTIVE} のままである。したがって「契約の status を見る」実装は
     * 一度も発火しない。{@code billing_payer_handover_requests.old_contract_id} 一致かつ非終端で判定する。</p>
     *
     * <p>塞ぐ対象は行儀の問題ではない。引継の夜次照合
     * （{@code BillingPayerHandoverService#reconcileOldCancelSchedule}）は Stripe 実物の
     * {@code cancel_at_period_end} が false なら<b>無条件に true を再設定する</b>ため、撤回を通すと
     * その晩に解約予約が復活し、利用者の撤回は翌朝消える（AC-49）。</p>
     */
    private void requireNoActiveHandover(UUID contractId) {
        List<BillingPayerHandoverRequestEntity> inFlight = handoverRequestRepository
                .findByOldContractIdAndStatusNotIn(
                        contractId, BillingPayerHandoverTxService.TERMINAL_STATUSES);
        if (!inFlight.isEmpty()) {
            // AC-38: 競合系の 409 は CHANGE_CONFLICT（ENTITLEMENT_021）で畳む。新設しない。
            throw new BusinessException(EntitlementErrorCode.CHANGE_CONFLICT);
        }
    }

    /** 解約／撤回の対象になり得る状態か（AC-36 の PAST_DUE 許可を含む・D4）。 */
    private void requireCancellableStatus(ContractStatus status) {
        if (status != ContractStatus.ACTIVE && status != ContractStatus.PAST_DUE) {
            throw new BusinessException(EntitlementErrorCode.CONTRACT_NOT_CANCELLABLE);
        }
    }

    /** 有償（PSP 紐付あり）契約であることを確かめて読む。無償契約はこの経路を通らない（AC-25）。 */
    private BillingContractEntity loadPaidContract(UUID contractId) {
        BillingContractEntity contract = requireContract(contractId);
        if (contract.getPspSubscriptionRef() == null) {
            // 無償契約は即時失効（従来経路）であり、期末解約の対象にならない。
            throw new BusinessException(EntitlementErrorCode.CONTRACT_NOT_CANCELLABLE);
        }
        return contract;
    }

    private BillingContractEntity requireContract(UUID contractId) {
        return billingContractRepository.findByIdAndDeletedAtIsNull(contractId)
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND));
    }

    // ================================================================
    // 内部ヘルパ
    // ================================================================

    private CancelView toView(BillingContractEntity contract, LocalDateTime endAt) {
        boolean operable = contract.getStatus() == ContractStatus.ACTIVE
                || contract.getStatus() == ContractStatus.PAST_DUE;
        // 既に CANCELLED / EXPIRED へ確定した契約の cancelled_at は「解約済みの記録」であって
        // 「これから期末に解約される予約」ではない。予約として見せるのは操作可能な状態のときだけ。
        boolean scheduled = operable && contract.getCancelledAt() != null;
        boolean windowOpen = endAt != null && endAt.isAfter(LocalDateTime.now(clock));
        return new CancelView(
                contract.getId(), contract.getStatus(),
                toOffset(scheduled ? contract.getCancelledAt() : null), toOffset(endAt),
                contract.getVersion(),
                operable && !scheduled,
                operable && scheduled && windowOpen);
    }

    /**
     * DB の壁時計 {@code LocalDateTime} を、注入 {@link Clock} のゾーンでオフセット付きへ変換する。
     * JVM 既定ゾーンへの暗黙の依存を持たない唯一の変換点である
     * （番人が禁止形をコメント本文からも検出するため、ここに該当 API 名は書かない）。
     */
    private OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }

    /** {@code billing_contracts.version} は Hibernate の {@code @Version} ではないため明示的に進める。 */
    private void bumpVersion(BillingContractEntity contract) {
        contract.setVersion(contract.getVersion() == null ? 1L : contract.getVersion() + 1L);
    }

    /** operation の解放に失敗しても、利用者へ返すべき元の業務例外を覆い隠さない。 */
    private void releaseQuietly(OperationReservation reservation, String errorCode) {
        try {
            sagaService.cancelAndRelease(reservation.operationId(), errorCode);
        } catch (RuntimeException releaseFailure) {
            log.error("PR6a: operation の解放に失敗した（operationId={}）。"
                            + "停止窓の回収対象として扱う必要がある",
                    reservation.operationId(), releaseFailure);
        }
    }

    /**
     * キャッシュ evict はコミット確定後に行う（未コミットの更新を stale に再ポピュレートさせない）。
     * トランザクション同期が無い文脈では即時 evict にフォールバックする。
     */
    private void evictAfterCommit(BillingContractEntity contract, Collection<String> featureKeys) {
        EntitlementScopeKind scopeKind = contract.getScopeKind();
        Long scopeId = contract.getScopeId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cacheEvictor.evictScopeFeatures(scopeKind, scopeId, featureKeys);
                }
            });
        } else {
            cacheEvictor.evictScopeFeatures(scopeKind, scopeId, featureKeys);
        }
    }

    private static EntitlementSourceKind toSourceKind(ContractKind contractKind) {
        return contractKind == ContractKind.ADDON
                ? EntitlementSourceKind.ADDON : EntitlementSourceKind.PLAN;
    }
}
