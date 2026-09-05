package com.mannschaft.app.billing;

import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 柱③-B 請求担当引継（CMP-260901-1538）: 引継フローの<b>トランザクション単位</b>（設計書 §3.1〜§3.7）。
 *
 * <p>{@link BillingPayerHandoverService} は「DB tx → commit → Stripe → DB tx」という多段構成を取る
 * （外部 API 呼び出しを長い {@code @Transactional} の内側に抱えない・既存
 * {@link BillingCheckoutService} と同流儀）。Spring の自己呼び出しではプロキシを経ず
 * {@code @Transactional} が効かないため、<b>個々のトランザクション単位を本クラスへ切り出す</b>。</p>
 *
 * <p><b>通知</b>: 業務 tx 内では {@link ApplicationEventPublisher#publishEvent} だけを行い、
 * 実配送は {@code AFTER_COMMIT} リスナー（{@link BillingPayerHandoverNotificationListener}）へ委ねる。</p>
 *
 * <p><b>時刻</b>: handover 側は {@link Instant}、{@code billing_contracts} 側は {@link LocalDateTime}。
 * 変換は既存 {@code BillingContractService#cancelPaidAtPeriodEnd} と対称に {@code clock.getZone()} を用いる
 * （{@code ZoneId.of("...")} のリテラル直書きは番人 {@code DateTimeAndZoneGuardTest} が拒否する）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingPayerHandoverTxService {

    /** 終端状態（{@code open_old_contract_id} 生成列で NULL になる 3 値・設計書 §4.2）。 */
    static final List<PayerHandoverStatus> TERMINAL_STATUSES = List.of(
            PayerHandoverStatus.COMPLETED, PayerHandoverStatus.FAILED, PayerHandoverStatus.EXPIRED);

    private final BillingPayerHandoverRequestRepository handoverRequestRepository;
    private final BillingContractRepository billingContractRepository;
    private final ActiveContractPointerRepository activeContractPointerRepository;
    private final BillingOperationAuthorizer billingOperationAuthorizer;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // ============================================================
    // 承諾（AC-11 / AC-12 / AC-16）
    // ============================================================

    /**
     * 承諾可否を<b>行ロック下で</b>検証する（設計書 §5.6・AC-11/AC-12/AC-25 の前段）。
     *
     * <p>状態を変えない読み取り専用の検証だが、{@code SELECT ... FOR UPDATE} を取るため
     * 書き込みトランザクションで実行する（{@code requireCanManage} も {@code MANDATORY}）。
     * 期限切れだけは {@code EXPIRED} への確定を伴う（設計書 §5.3）。</p>
     *
     * @return 検証済みスナップショット（支払い手段検証は tx の外で行うため本メソッドでは行わない）
     */
    @Transactional
    public AcceptValidation validateAcceptable(
            EntitlementScopeKind scopeKind, Long scopeId, UUID handoverRequestId, Long operatorUserId) {

        BillingPayerHandoverRequestEntity handover = lockOrThrow(handoverRequestId);

        // ★IDOR: URL 由来の scope と行の scope が一致しなければ、存在自体を明かさず 404 で畳む
        //   （別スコープの ADMIN が他スコープの引継要求 ID を指定して承諾できてしまうのを防ぐ）。
        requireSameScope(handover, scopeKind, scopeId);

        // 認可は「行の scope と一致確認済みの引数 scope」に対して行う（AC-11）。
        billingOperationAuthorizer.requireCanManage(operatorUserId, scopeKind, scopeId);

        Instant now = clock.instant();
        if (!handover.getExpiresAt().isAfter(now)) {
            handover.setStatus(PayerHandoverStatus.EXPIRED);
            handoverRequestRepository.save(handover);
            throw new BusinessException(EntitlementErrorCode.HANDOVER_EXPIRED);
        }
        if (handover.getStatus() != PayerHandoverStatus.REQUESTED
                && handover.getStatus() != PayerHandoverStatus.REQUIRES_PAYMENT_METHOD) {
            throw new BusinessException(EntitlementErrorCode.HANDOVER_NOT_ACCEPTABLE);
        }

        return new AcceptValidation(handover.getId(), handover.getScopeKind(), handover.getScopeId(),
                handover.getOldContractId(), handover.getStatus(), handover.getPspNewSubscriptionRef(),
                handover.getNewContractId());
    }

    /**
     * 引継要求の発行を対象スコープの他 ADMIN 全員へ通知する（設計書 §5.2・AC-9）。
     *
     * <p>{@code REQUIRED}（既定）で呼び出し元の業務トランザクションに参加する。イベントは
     * <b>業務 tx 内で publish し、配送は {@code AFTER_COMMIT} リスナーが行う</b>ため、
     * 要求行の作成がロールバックされれば通知も送られない（逆向きの不整合も生じない）。</p>
     */
    @Transactional
    public void publishHandoverRequested(UUID handoverRequestId, EntitlementScopeKind scopeKind,
            Long scopeId, List<Long> recipientUserIds, Long actorUserId) {
        eventPublisher.publishEvent(new BillingPayerHandoverNotificationEvent(
                BillingPayerHandoverNotificationKind.HANDOVER_REQUESTED,
                handoverRequestId, scopeKind, scopeId, recipientUserIds, actorUserId));
    }

    /**
     * 支払い手段未登録による差し戻し（設計書 §3.6 二段検証の1段目・AC-16/AC-19）。
     *
     * <p><b>旧契約には一切触れない</b>。この時点ではまだ旧サブスクへ {@code cancel_at_period_end} を
     * 設定していないため、旧契約は完全に無傷のまま維持される。</p>
     */
    @Transactional
    public void transitionToRequiresPaymentMethod(
            EntitlementScopeKind scopeKind, Long scopeId, UUID handoverRequestId, Long operatorUserId) {

        BillingPayerHandoverRequestEntity handover = lockOrThrow(handoverRequestId);
        requireSameScope(handover, scopeKind, scopeId);
        if (handover.getStatus() != PayerHandoverStatus.REQUESTED
                && handover.getStatus() != PayerHandoverStatus.REQUIRES_PAYMENT_METHOD) {
            throw new BusinessException(EntitlementErrorCode.HANDOVER_NOT_ACCEPTABLE);
        }
        handover.setStatus(PayerHandoverStatus.REQUIRES_PAYMENT_METHOD);
        handoverRequestRepository.save(handover);

        eventPublisher.publishEvent(new BillingPayerHandoverNotificationEvent(
                BillingPayerHandoverNotificationKind.PAYMENT_METHOD_REQUIRED,
                handover.getId(), handover.getScopeKind(), handover.getScopeId(),
                List.of(operatorUserId), operatorUserId));
    }

    /**
     * {@code ACCEPTED} へ遷移し、引継先の {@code billing_contracts} 行を
     * <b>{@code PENDING_HANDOVER}</b> で先行作成する（設計書 §3.1・P0-4）。
     *
     * <p><b>pointer は作らない</b>。{@code active_contract_pointers.uk_acp_slot} はスロット単位 UNIQUE のため、
     * 新旧2契約が同時に pointer を持とうとすると衝突する。pointer の付け替えは切替TX
     * （{@link #executeSwitchTx}）でのみ、旧削除と新作成を同一トランザクションで行う。</p>
     *
     * <p>行ロックを取り直したうえで状態を再検証するため、{@link #validateAcceptable} との間に
     * 別 ADMIN の承諾が割り込んでも遷移が二重に成立することはない（AC-12）。</p>
     */
    @Transactional
    public AcceptTransition transitionToAccepted(
            EntitlementScopeKind scopeKind, Long scopeId, UUID handoverRequestId, Long operatorUserId) {

        BillingPayerHandoverRequestEntity handover = lockOrThrow(handoverRequestId);
        requireSameScope(handover, scopeKind, scopeId);
        if (handover.getStatus() != PayerHandoverStatus.REQUESTED
                && handover.getStatus() != PayerHandoverStatus.REQUIRES_PAYMENT_METHOD) {
            throw new BusinessException(EntitlementErrorCode.HANDOVER_NOT_ACCEPTABLE);
        }

        BillingContractEntity oldContract = billingContractRepository
                .findByIdAndDeletedAtIsNull(handover.getOldContractId())
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.HANDOVER_NOT_FOUND));

        Instant now = clock.instant();
        handover.setStatus(PayerHandoverStatus.ACCEPTED);
        handover.setAcceptedAt(now);
        handover.setNewPayerUserId(operatorUserId);

        // 既に新契約が作られていれば作り直さない（承諾の再試行・冪等）。
        UUID newContractId = handover.getNewContractId();
        if (newContractId == null) {
            BillingContractEntity newContract = BillingContractEntity.builder()
                    .scopeKind(oldContract.getScopeKind())
                    .scopeId(oldContract.getScopeId())
                    .organizationId(oldContract.getOrganizationId())
                    .contractKind(oldContract.getContractKind())
                    .planKey(oldContract.getPlanKey())
                    .featureKey(oldContract.getFeatureKey())
                    .status(ContractStatus.PENDING_HANDOVER)
                    .memberCountSnapshot(oldContract.getMemberCountSnapshot())
                    .bandNoSnapshot(oldContract.getBandNoSnapshot())
                    // 価格スナップショットは旧契約から引き継ぐ（引継は値上げではない）。
                    .priceJpySnapshot(oldContract.getPriceJpySnapshot())
                    .priceBandVersionId(oldContract.getPriceBandVersionId())
                    .contractedAt(LocalDateTime.ofInstant(now, clock.getZone()))
                    .createdBy(operatorUserId)
                    .payerUserId(operatorUserId)
                    .handoverRequestId(handover.getId())
                    .build();
            newContractId = billingContractRepository.save(newContract).getId();
            handover.setNewContractId(newContractId);
        }
        handoverRequestRepository.save(handover);

        return new AcceptTransition(handover.getId(), newContractId, operatorUserId,
                oldContract.getId(), oldContract.getPriceJpySnapshot(),
                buildDisplayName(oldContract), toInstant(oldContract.getCurrentPeriodEnd()),
                handover.getPspNewSubscriptionRef());
    }

    /**
     * 新サブスク ID を永続化する（設計書 §3.2 一次防衛の要・AC-7/AC-25）。
     *
     * <p>Stripe API 成功後に別操作として書き込むため<b>原子性は成立しない</b>。
     * この間に落ちた場合は、次回リトライの回復経路（DB → List Subscriptions の順で照会）が回収する。</p>
     */
    @Transactional
    public void persistNewSubscriptionRef(UUID handoverRequestId, String newSubscriptionRef) {
        BillingPayerHandoverRequestEntity handover = lockOrThrow(handoverRequestId);
        if (handover.getPspNewSubscriptionRef() == null) {
            handover.setPspNewSubscriptionRef(newSubscriptionRef);
            handoverRequestRepository.save(handover);
        }
    }

    // ============================================================
    // (a) 引継確定（checkout.session.completed）— AC-6 / AC-31 / AC-30 1段目
    // ============================================================

    /**
     * {@code SWITCHING} へ遷移し、新サブスク ID を確定する（設計書 §3.6 (a)）。
     *
     * <p><b>冪等</b>: 既に {@code SWITCHING} 以降（または終端）なら {@code null} を返し、呼び出し側は
     * 旧サブスクへの再設定を行わない。</p>
     *
     * @return 後続の Stripe 操作に必要な参照（no-op のときは {@code null}）
     */
    @Transactional
    public CheckoutCompletion markSwitching(UUID handoverRequestId, String newSubscriptionRef) {
        BillingPayerHandoverRequestEntity handover =
                handoverRequestRepository.findByIdForUpdate(handoverRequestId).orElse(null);
        if (handover == null) {
            log.warn("柱③-B: 引継確定 webhook の対象が見つかりません handoverRequestId={}", handoverRequestId);
            return null;
        }
        if (handover.getStatus() != PayerHandoverStatus.ACCEPTED) {
            log.info("柱③-B: 引継確定 webhook は no-op（既に {} ）handoverRequestId={}",
                    handover.getStatus(), handoverRequestId);
            return null;
        }
        handover.setStatus(PayerHandoverStatus.SWITCHING);
        if (newSubscriptionRef != null) {
            handover.setPspNewSubscriptionRef(newSubscriptionRef);
        }
        handoverRequestRepository.save(handover);

        String oldSubscriptionRef = billingContractRepository
                .findByIdAndDeletedAtIsNull(handover.getOldContractId())
                .map(BillingContractEntity::getPspSubscriptionRef)
                .orElse(null);
        return new CheckoutCompletion(handover.getId(), oldSubscriptionRef,
                handover.getPspNewSubscriptionRef());
    }

    /**
     * 旧サブスクへの {@code cancel_at_period_end=true} 設定が成功した時刻を永続化する
     * （設計書 §3.6.1(a)・R4-P1-2）。
     *
     * <p><b>Stripe API 呼び出しと本 DB 書き込みは原子的ではない</b>（外部システムは DB tx に巻き込めない）。
     * 「Stripe では成功したが本書き込み前にクラッシュ」した不整合は、夜次照合バッチ（PR-4）が
     * Stripe 実物と突合して補完する前提で設計している。</p>
     */
    @Transactional
    public void persistOldCancelScheduledAt(UUID handoverRequestId, Instant scheduledAt) {
        BillingPayerHandoverRequestEntity handover = lockOrThrow(handoverRequestId);
        handover.setOldCancelScheduledAt(scheduledAt);
        handoverRequestRepository.save(handover);
    }

    /**
     * 追加認証（SCA/3DS）要求の通知を発行する（設計書 §3.6・AC-30 の1段目）。
     *
     * <p><b>状態遷移は行わない</b>。旧サブスクの {@code cancel_at_period_end=true} は既に設定済みであり、
     * 引継自体は進行中扱いのままとする。</p>
     */
    @Transactional
    public void publishAdditionalAuthRequired(UUID handoverRequestId) {
        BillingPayerHandoverRequestEntity handover =
                handoverRequestRepository.findById(handoverRequestId).orElse(null);
        if (handover == null || handover.getNewPayerUserId() == null) {
            return;
        }
        eventPublisher.publishEvent(new BillingPayerHandoverNotificationEvent(
                BillingPayerHandoverNotificationKind.ADDITIONAL_AUTH_REQUIRED,
                handover.getId(), handover.getScopeKind(), handover.getScopeId(),
                List.of(handover.getNewPayerUserId()), handover.getNewPayerUserId()));
    }

    // ============================================================
    // (b) pointer 切替（旧期末到達）— AC-27 / AC-30 2段目 / AC-32 / AC-35
    // ============================================================

    /**
     * 切替の前提情報を読み出す（設計書 §3.6 (b)）。{@code SWITCHING} 以外は {@code null}。
     *
     * <p>{@code PARTIALLY_COMPLETED}（非終端・リトライ対象）も切替の再試行対象に含める。</p>
     */
    @Transactional(readOnly = true)
    public SwitchContext loadSwitchContext(UUID handoverRequestId) {
        BillingPayerHandoverRequestEntity handover =
                handoverRequestRepository.findById(handoverRequestId).orElse(null);
        if (handover == null) {
            return null;
        }
        if (handover.getStatus() != PayerHandoverStatus.SWITCHING
                && handover.getStatus() != PayerHandoverStatus.PARTIALLY_COMPLETED) {
            return null;
        }
        BillingContractEntity oldContract = billingContractRepository
                .findByIdAndDeletedAtIsNull(handover.getOldContractId()).orElse(null);
        if (oldContract == null) {
            return null;
        }
        return new SwitchContext(handover.getId(), oldContract.getPspSubscriptionRef(),
                handover.getPspNewSubscriptionRef(), toInstant(oldContract.getCurrentPeriodEnd()));
    }

    /**
     * 引継を {@code FAILED} で確定する（設計書 §3.6.1・AC-32・R5-P2）。
     *
     * <p>Stripe 側の差し戻し（新 trial サブスクの無課金取消・旧サブスクの
     * {@code cancel_at_period_end=false}）は呼び出し側が tx の外で済ませている。本メソッドは
     * その<b>対</b>として {@code old_cancel_scheduled_at} を NULL クリアする——クリアし忘れると、
     * 同一契約への再要求時に「予約済み」と誤認され夜次照合バッチの検出対象から外れる。</p>
     *
     * <p>あわせて新契約（{@code PENDING_HANDOVER}）を {@code CANCELLED} で無効化する。
     * 旧契約の pointer は無傷のため利用者影響は無い。</p>
     */
    @Transactional
    public void markFailedAndClearCancelSchedule(UUID handoverRequestId) {
        BillingPayerHandoverRequestEntity handover = lockOrThrow(handoverRequestId);
        handover.setStatus(PayerHandoverStatus.FAILED);
        handover.setOldCancelScheduledAt(null);
        handoverRequestRepository.save(handover);

        if (handover.getNewContractId() != null) {
            billingContractRepository.findByIdAndDeletedAtIsNull(handover.getNewContractId())
                    .filter(c -> c.getStatus() == ContractStatus.PENDING_HANDOVER)
                    .ifPresent(c -> {
                        c.setStatus(ContractStatus.CANCELLED);
                        c.setCancelledAt(LocalDateTime.ofInstant(clock.instant(), clock.getZone()));
                        billingContractRepository.save(c);
                    });
        }
    }

    /**
     * {@code MANUAL_INTERVENTION} へ倒す（設計書 §3.6.2・AC-35・R5-P1-1/2）。
     *
     * <p><b>非終端</b>のため {@code open_old_contract_id} は値を保持し続け、同一契約への新規引継要求は
     * 運用者の {@code RESUME} まで物理的にブロックされる（人手対応中の二重進行を防ぐ意図的な設計）。</p>
     */
    @Transactional
    public void markManualIntervention(UUID handoverRequestId) {
        BillingPayerHandoverRequestEntity handover = lockOrThrow(handoverRequestId);
        handover.setStatus(PayerHandoverStatus.MANUAL_INTERVENTION);
        handoverRequestRepository.save(handover);
    }

    /**
     * ローカル切替TX（設計書 §3.1・§3.6 (b)・AC-6/AC-27）。
     *
     * <p><b>Stripe API 呼び出しを一切含まない</b>。Stripe 側は承諾確定（{@code checkout.session.completed}）
     * の時点で {@code cancel_at_period_end=true} が設定済みであり確定しているため、ここで行うのは
     * ローカル DB 操作だけである。</p>
     *
     * <p><b>旧 pointer の物理 DELETE と新 pointer の INSERT を同一トランザクションで行う</b>ことが、
     * entitlement 空白ゼロ・二重付与ゼロの根拠である（DB トランザクションの原子性のみに依存する）。
     * <b>分割してはならない。</b></p>
     */
    @Transactional
    public void executeSwitchTx(UUID handoverRequestId) {
        BillingPayerHandoverRequestEntity handover = lockOrThrow(handoverRequestId);
        BillingContractEntity oldContract = billingContractRepository
                .findByIdAndDeletedAtIsNull(handover.getOldContractId())
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.HANDOVER_NOT_FOUND));
        BillingContractEntity newContract = billingContractRepository
                .findByIdAndDeletedAtIsNull(handover.getNewContractId())
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.HANDOVER_NOT_FOUND));

        String slotAddonKey = oldContract.getContractKind() == ContractKind.ADDON
                ? oldContract.getFeatureKey() : "";

        // ① 旧 pointer を物理 DELETE（contract_id 一致条件つき。切替後に届く旧 webhook が
        //    新 pointer を消してしまう P0-3 の穴を、削除側でも同じ条件に揃える）。
        activeContractPointerRepository.hardDeleteBySlotAndContractId(
                oldContract.getScopeKind(), oldContract.getScopeId(),
                oldContract.getContractKind(), slotAddonKey, oldContract.getId());

        // ② 新 pointer を INSERT（①と同一トランザクション。ここを分けると entitlement に空白が生じる）。
        activeContractPointerRepository.saveAndFlush(ActiveContractPointerEntity.builder()
                .scopeKind(newContract.getScopeKind())
                .scopeId(newContract.getScopeId())
                .contractKind(newContract.getContractKind())
                .addonFeatureKey(slotAddonKey)
                .contractId(newContract.getId())
                .organizationId(newContract.getOrganizationId())
                .build());

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        oldContract.setStatus(ContractStatus.CANCELLED);
        oldContract.setCancelledAt(now);
        billingContractRepository.save(oldContract);

        newContract.setStatus(ContractStatus.ACTIVE);
        newContract.setPspSubscriptionRef(handover.getPspNewSubscriptionRef());
        billingContractRepository.save(newContract);

        handover.setStatus(PayerHandoverStatus.COMPLETED);
        handover.setCompletedAt(clock.instant());
        handoverRequestRepository.save(handover);
    }

    /**
     * ローカル切替TX の DB 書き込みだけが失敗した状態（設計書 §3.5 再定義）。
     *
     * <p><b>非終端</b>であり夜次バッチのリトライ対象。Stripe 側は既に確定済み（旧は期末で終わる）なので、
     * リトライは「pointer 付替え＋状態遷移」という冪等な操作の再実行で足りる。
     * <b>終端扱いにしてはならない</b>（リトライ経路から外れて宙ぶらりんになる）。</p>
     */
    @Transactional
    public void markPartiallyCompleted(UUID handoverRequestId) {
        BillingPayerHandoverRequestEntity handover = lockOrThrow(handoverRequestId);
        handover.setStatus(PayerHandoverStatus.PARTIALLY_COMPLETED);
        handoverRequestRepository.save(handover);
    }

    // ============================================================
    // 内部ヘルパ
    // ============================================================

    private BillingPayerHandoverRequestEntity lockOrThrow(UUID handoverRequestId) {
        return handoverRequestRepository.findByIdForUpdate(handoverRequestId)
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.HANDOVER_NOT_FOUND));
    }

    /** スコープ越境は存在自体を明かさず 404 で畳む（既存 {@code loadContractInScope} と同流儀）。 */
    private void requireSameScope(
            BillingPayerHandoverRequestEntity handover, EntitlementScopeKind scopeKind, Long scopeId) {
        if (handover.getScopeKind() != scopeKind || !handover.getScopeId().equals(scopeId)) {
            throw new BusinessException(EntitlementErrorCode.HANDOVER_NOT_FOUND);
        }
    }

    /**
     * {@code billing_contracts} の壁時計（{@link LocalDateTime}）を {@link Instant} へ変換する。
     *
     * <p>既存 {@code BillingContractService#cancelPaidAtPeriodEnd} の
     * {@code LocalDateTime.ofInstant(instant, clock.getZone())} と<b>対称</b>な逆変換であり、
     * 同じ {@code Clock} の zone を用いるため往復で値が変わらない（AC-5 の unix 秒一致の根拠）。</p>
     */
    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toInstant();
    }

    private String buildDisplayName(BillingContractEntity contract) {
        return contract.getContractKind() == ContractKind.PLAN
                ? "Mannschaft プラン: " + contract.getPlanKey()
                : "Mannschaft 機能: " + contract.getFeatureKey();
    }

    // ============================================================
    // 戻り値レコード（Entity を外へ漏らさない）
    // ============================================================

    /** 承諾可否の検証結果（{@link #validateAcceptable}）。 */
    public record AcceptValidation(
            UUID handoverRequestId, EntitlementScopeKind scopeKind, Long scopeId,
            UUID oldContractId, PayerHandoverStatus status,
            String existingNewSubscriptionRef, UUID existingNewContractId) {
    }

    /** {@code ACCEPTED} 遷移の結果（{@link #transitionToAccepted}）。 */
    public record AcceptTransition(
            UUID handoverRequestId, UUID newContractId, Long newPayerUserId, UUID oldContractId,
            Integer priceJpy, String displayName, Instant oldPeriodEnd, String existingNewSubscriptionRef) {
    }

    /** 引継確定（{@code checkout.session.completed}）の結果（{@link #markSwitching}）。 */
    public record CheckoutCompletion(
            UUID handoverRequestId, String oldSubscriptionRef, String newSubscriptionRef) {
    }

    /** 切替の前提情報（{@link #loadSwitchContext}）。 */
    public record SwitchContext(
            UUID handoverRequestId, String oldSubscriptionRef, String newSubscriptionRef, Instant oldPeriodEnd) {
    }
}
