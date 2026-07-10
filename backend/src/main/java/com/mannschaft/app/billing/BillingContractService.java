package com.mannschaft.app.billing;

import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F20.1: 契約サービス（作成/解約/変更・設計書 02 §3・01 §3.1.1 / §4）。
 *
 * <p><b>アクティブ契約の一意性</b>は {@code active_contract_pointers.uk_acp_slot} の DB UNIQUE で物理担保する
 * （H-1・TOCTOU 二重契約を閉じる）。並行 2 リクエストの一方は {@link DataIntegrityViolationException} →
 * {@code CONTRACT_ALREADY_ACTIVE}（409・AC-28/30）。契約履歴は {@code billing_contracts} に append-only で残す。</p>
 *
 * <p><b>原子性</b>: 解約は「契約 CANCELLED ＋ pointer 物理 DELETE ＋ 由来 entitlements 全 revoke」を単一
 * トランザクションで行う（宙ぶらりんの権利を残さない・AC-20）。プラン変更は「旧 revoke ＋ 新発行 ＋ pointer 付け替え」
 * を単一トランザクションで行う（AC-19）。</p>
 *
 * <p><b>キャッシュ整合</b>: 発行/取消した feature_key 集合を {@link EntitlementCacheEvictor} で個別 evict する
 * （設計書 02 §8・AC-16）。evict はトランザクション書き込み確定後のフローで呼ぶ。</p>
 *
 * <p><b>スコープ所有権</b>: 契約の解約/変更は {@code contractId} → 所属スコープを解決し、パスのスコープと
 * 一致検証する（不一致は {@code CONTRACT_NOT_FOUND} 404 秘匿・IDOR・03 §2）。作成時の scopeId 所有権
 * （ADMIN か）は public 入口の {@code @PreAuthorize} が一次防御（別部隊）。本サービスは受領した scope を信頼せず、
 * 子リソース系は必ず一致検証で二重防御する。</p>
 */
@Service
@RequiredArgsConstructor
public class BillingContractService {

    private final BillingContractRepository billingContractRepository;
    private final ActiveContractPointerRepository activeContractPointerRepository;
    private final EntitlementRepository entitlementRepository;
    private final PlanRepository planRepository;
    private final PlanFeatureRepository planFeatureRepository;
    private final FeatureCatalogRepository featureCatalogRepository;
    private final PlanPriceBandRepository planPriceBandRepository;
    private final ScopeMemberCountService scopeMemberCountService;
    private final EntitlementCacheEvictor cacheEvictor;
    private final Clock clock;
    /** F20.1 実決済（D-3）: 有償解約の期末解約予約に用いる決済ゲートウェイ（自社受取サブスク）。 */
    private final BillingPaymentGateway billingPaymentGateway;

    /**
     * 契約変更操作の結果（API 層 DTO 組み立て用・付与/取消 feature_key 集合を含む）。
     *
     * <p>F20.1 実決済（D-3/D-4）: {@code checkoutUrl}（決済フローの Checkout URL・無償/解約は null）と
     * {@code currentPeriodEnd}（有償解約の利用可能期限）を追加。</p>
     */
    public record ContractResult(
            UUID contractId,
            EntitlementScopeKind scopeKind,
            Long scopeId,
            ContractKind contractKind,
            String planKey,
            String featureKey,
            ContractStatus status,
            Integer memberCountSnapshot,
            Short bandNoSnapshot,
            Integer priceJpySnapshot,
            LocalDateTime contractedAt,
            LocalDateTime currentPeriodEnd,
            String checkoutUrl,
            List<String> grantedFeatureKeys,
            List<String> revokedFeatureKeys) {
    }

    // ============================================================
    // 作成（PLAN / ADDON）
    // ============================================================

    /**
     * PLAN/ADDON 契約を作成する（設計書 02 §3.1）。
     *
     * @param scopeKind      USER / TEAM / ORG
     * @param scopeId        users.id / teams.id / organizations.id
     * @param organizationId テナント（ORG=scope_id 自身 / TEAM=主所属組織 / USER=NULL・呼び出し側で解決）
     * @param contractKind   PLAN / ADDON
     * @param planKey        PLAN 時必須
     * @param featureKey     ADDON 時必須
     * @param operatorUserId 契約操作者（監査用・論理参照）
     * @return 付与された feature_key 集合を含む結果
     */
    @Transactional
    public ContractResult createContract(
            EntitlementScopeKind scopeKind, Long scopeId, Long organizationId,
            ContractKind contractKind, String planKey, String featureKey, Long operatorUserId) {

        LocalDateTime now = LocalDateTime.now(clock);
        List<String> grantedKeys;
        String slotAddonKey;

        if (contractKind == ContractKind.PLAN) {
            grantedKeys = validatePlanAndResolveFeatures(planKey);
            slotAddonKey = "";
            featureKey = null;
        } else if (contractKind == ContractKind.ADDON) {
            validateAddonFeature(featureKey);
            grantedKeys = List.of(featureKey);
            slotAddonKey = featureKey;
            planKey = null;
        } else {
            // contractKind が PLAN/ADDON 以外（enum ゆえ現状到達しないが、API 層で文字列受けした場合の防御）。
            throw new BusinessException(EntitlementErrorCode.INVALID_CONTRACT_KIND);
        }

        Integer memberCountSnapshot = null;
        Short bandNoSnapshot = null;
        if (scopeKind != EntitlementScopeKind.USER) {
            memberCountSnapshot = scopeMemberCountService.countActiveMembers(scopeKind, scopeId);
            if (contractKind == ContractKind.PLAN) {
                bandNoSnapshot = resolveBandNo(planKey, scopeKind, memberCountSnapshot);
            }
        }

        BillingContractEntity contract = BillingContractEntity.builder()
                .scopeKind(scopeKind)
                .scopeId(scopeId)
                .organizationId(organizationId)
                .contractKind(contractKind)
                .planKey(planKey)
                .featureKey(featureKey)
                .status(ContractStatus.ACTIVE)
                .memberCountSnapshot(memberCountSnapshot)
                .bandNoSnapshot(bandNoSnapshot)
                .priceJpySnapshot(null)           // ベータ中は無償（NULL）。
                .contractedAt(now)
                .createdBy(operatorUserId)
                .build();
        BillingContractEntity saved = billingContractRepository.save(contract);

        // ★アクティブ一意スロットを先に確保（uk_acp_slot が二重契約の並行 INSERT を物理拒否）。
        ActiveContractPointerEntity pointer = ActiveContractPointerEntity.builder()
                .scopeKind(scopeKind)
                .scopeId(scopeId)
                .contractKind(contractKind)
                .addonFeatureKey(slotAddonKey)
                .contractId(saved.getId())
                .organizationId(organizationId)
                .build();
        try {
            activeContractPointerRepository.saveAndFlush(pointer);
        } catch (DataIntegrityViolationException ex) {
            // H-1 TOCTOU 二重契約を DB が物理拒否（アプリ層 exists チェックのレースを閉じる・AC-28/30）。
            throw new BusinessException(EntitlementErrorCode.CONTRACT_ALREADY_ACTIVE, ex);
        }

        issueEntitlements(scopeKind, scopeId, organizationId, grantedKeys,
                toSourceKind(contractKind), saved.getId(), now);

        evictAfterCommit(scopeKind, scopeId, grantedKeys);

        return new ContractResult(saved.getId(), scopeKind, scopeId, contractKind, planKey, featureKey,
                ContractStatus.ACTIVE, memberCountSnapshot, bandNoSnapshot, null, now, null, null,
                new ArrayList<>(grantedKeys), List.of());
    }

    // ============================================================
    // 解約
    // ============================================================

    /**
     * 契約を解約する（設計書 02 §3.2・AC-20）。当該契約由来の entitlements を同一トランザクションで全 revoke する。
     *
     * @return revoke した feature_key 集合を含む結果
     */
    @Transactional
    public ContractResult cancelContract(
            EntitlementScopeKind scopeKind, Long scopeId, UUID contractId, Long operatorUserId) {

        LocalDateTime now = LocalDateTime.now(clock);
        BillingContractEntity contract = loadContractInScope(scopeKind, scopeId, contractId);
        if (contract.getStatus() != ContractStatus.ACTIVE) {
            throw new BusinessException(EntitlementErrorCode.CONTRACT_NOT_CANCELLABLE);
        }

        // D-3: 有償契約（price_jpy_snapshot 非 NULL＋PSP 紐付あり）は期末解約（cancel_at_period_end）。
        // 無償契約は従来どおり即時失効。
        boolean paid = contract.getPriceJpySnapshot() != null && contract.getPspSubscriptionRef() != null;
        if (paid) {
            return cancelPaidAtPeriodEnd(scopeKind, scopeId, contract, operatorUserId, now);
        }

        contract.setStatus(ContractStatus.CANCELLED);
        contract.setCancelledAt(now);
        billingContractRepository.save(contract);

        // アクティブスロットを物理 DELETE（再契約可能に）。
        String slotAddonKey = contract.getContractKind() == ContractKind.ADDON
                ? contract.getFeatureKey() : "";
        activeContractPointerRepository.hardDeleteBySlot(
                scopeKind, scopeId, contract.getContractKind(), slotAddonKey);

        List<String> revokedKeys = revokeEntitlementsOfContract(contract, operatorUserId, now);
        evictAfterCommit(scopeKind, scopeId, revokedKeys);

        return new ContractResult(contract.getId(), scopeKind, scopeId, contract.getContractKind(),
                contract.getPlanKey(), contract.getFeatureKey(), ContractStatus.CANCELLED,
                contract.getMemberCountSnapshot(), contract.getBandNoSnapshot(), contract.getPriceJpySnapshot(),
                contract.getContractedAt(), null, null,
                List.of(), revokedKeys);
    }

    // ============================================================
    // プラン変更
    // ============================================================

    /**
     * PLAN 契約のプランを変更する（設計書 02 §3.3・AC-19）。
     * 旧契約 CANCELLED＋由来 entitlements revoke → 新契約 ACTIVE＋新 entitlements 発行 → pointer.contract_id 付け替え。
     * ダウングレードで対象外になった機能は即 false（evict 込み）。
     *
     * @return 付与（新）・取消（旧）した feature_key 集合を含む結果
     */
    @Transactional
    public ContractResult changePlan(
            EntitlementScopeKind scopeKind, Long scopeId, UUID contractId,
            String newPlanKey, Long operatorUserId) {

        LocalDateTime now = LocalDateTime.now(clock);
        BillingContractEntity oldContract = loadContractInScope(scopeKind, scopeId, contractId);
        if (oldContract.getStatus() != ContractStatus.ACTIVE) {
            throw new BusinessException(EntitlementErrorCode.CONTRACT_NOT_CANCELLABLE);
        }
        if (oldContract.getContractKind() != ContractKind.PLAN) {
            // プラン変更は PLAN 契約のみ（ADDON は解約→再契約）。
            throw new BusinessException(EntitlementErrorCode.CONTRACT_NOT_CANCELLABLE);
        }
        if (newPlanKey != null && newPlanKey.equals(oldContract.getPlanKey())) {
            throw new BusinessException(EntitlementErrorCode.CONTRACT_ALREADY_ACTIVE);
        }
        List<String> newKeys = validatePlanAndResolveFeatures(newPlanKey);

        // 旧契約 CANCELLED＋由来 entitlements revoke。
        oldContract.setStatus(ContractStatus.CANCELLED);
        oldContract.setCancelledAt(now);
        billingContractRepository.save(oldContract);
        List<String> oldKeys = revokeEntitlementsOfContract(oldContract, operatorUserId, now);

        // 新契約 ACTIVE＋新 entitlements 発行。
        Integer memberCountSnapshot = null;
        Short bandNoSnapshot = null;
        if (scopeKind != EntitlementScopeKind.USER) {
            memberCountSnapshot = scopeMemberCountService.countActiveMembers(scopeKind, scopeId);
            bandNoSnapshot = resolveBandNo(newPlanKey, scopeKind, memberCountSnapshot);
        }
        BillingContractEntity newContract = BillingContractEntity.builder()
                .scopeKind(scopeKind)
                .scopeId(scopeId)
                .organizationId(oldContract.getOrganizationId())
                .contractKind(ContractKind.PLAN)
                .planKey(newPlanKey)
                .status(ContractStatus.ACTIVE)
                .memberCountSnapshot(memberCountSnapshot)
                .bandNoSnapshot(bandNoSnapshot)
                .priceJpySnapshot(null)
                .contractedAt(now)
                .createdBy(operatorUserId)
                .build();
        BillingContractEntity savedNew = billingContractRepository.save(newContract);

        // pointer.contract_id を新契約へ付け替える（行を増やさない・PLAN スロット addon_feature_key="")。
        ActiveContractPointerEntity pointer = activeContractPointerRepository
                .findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                        scopeKind, scopeId, ContractKind.PLAN, "")
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND));
        pointer.setContractId(savedNew.getId());
        activeContractPointerRepository.save(pointer);

        issueEntitlements(scopeKind, scopeId, oldContract.getOrganizationId(), newKeys,
                EntitlementSourceKind.PLAN, savedNew.getId(), now);

        // 旧∪新 の feature_key を evict（ダウングレードで外れた機能を即 false に）。
        Set<String> affected = new LinkedHashSet<>(oldKeys);
        affected.addAll(newKeys);
        evictAfterCommit(scopeKind, scopeId, affected);

        return new ContractResult(savedNew.getId(), scopeKind, scopeId, ContractKind.PLAN, newPlanKey, null,
                ContractStatus.ACTIVE, memberCountSnapshot, bandNoSnapshot, null, now, null, null,
                new ArrayList<>(newKeys), oldKeys);
    }

    // ============================================================
    // F20.1 実決済（D-1〜D-4・2026-07-10 御裁可）: 決済フロー契約の起票・状態遷移
    // ============================================================

    /**
     * 決済フローの契約を PENDING で起票する（entitlements は<b>未発行</b>・設計書 02）。
     *
     * <p>{@code price_jpy_snapshot} を焼き付け（遡及防止・D-4）、pointer を先に INSERT して
     * {@code uk_acp_slot} でスロットを確保する（並行二重 checkout を物理拒否）。Checkout Session 生成は
     * 呼び出し側（{@link BillingCheckoutService}）が本メソッド commit 後・トランザクション外で行う
     * （外部 API 呼び出しを @Transactional に含めない）。</p>
     *
     * @return PENDING 契約の結果（{@code grantedFeatureKeys} は空・発行は入金 webhook 時）
     */
    @Transactional
    public ContractResult createPendingPaidContract(
            EntitlementScopeKind scopeKind, Long scopeId, Long organizationId,
            ContractKind contractKind, String planKey, String featureKey, int priceJpy, Long operatorUserId) {

        LocalDateTime now = LocalDateTime.now(clock);
        String slotAddonKey;
        if (contractKind == ContractKind.PLAN) {
            validatePlanAndResolveFeatures(planKey); // 存在/enabled 検証（発行は入金時）
            slotAddonKey = "";
            featureKey = null;
        } else if (contractKind == ContractKind.ADDON) {
            validateAddonFeature(featureKey);
            slotAddonKey = featureKey;
            planKey = null;
        } else {
            throw new BusinessException(EntitlementErrorCode.INVALID_CONTRACT_KIND);
        }

        Integer memberCountSnapshot = null;
        Short bandNoSnapshot = null;
        if (scopeKind != EntitlementScopeKind.USER) {
            memberCountSnapshot = scopeMemberCountService.countActiveMembers(scopeKind, scopeId);
            if (contractKind == ContractKind.PLAN) {
                bandNoSnapshot = resolveBandNo(planKey, scopeKind, memberCountSnapshot);
            }
        }

        BillingContractEntity contract = BillingContractEntity.builder()
                .scopeKind(scopeKind)
                .scopeId(scopeId)
                .organizationId(organizationId)
                .contractKind(contractKind)
                .planKey(planKey)
                .featureKey(featureKey)
                .status(ContractStatus.PENDING)
                .memberCountSnapshot(memberCountSnapshot)
                .bandNoSnapshot(bandNoSnapshot)
                .priceJpySnapshot(priceJpy)
                .contractedAt(now)
                .createdBy(operatorUserId)
                .build();
        BillingContractEntity saved = billingContractRepository.save(contract);

        ActiveContractPointerEntity pointer = ActiveContractPointerEntity.builder()
                .scopeKind(scopeKind)
                .scopeId(scopeId)
                .contractKind(contractKind)
                .addonFeatureKey(slotAddonKey)
                .contractId(saved.getId())
                .organizationId(organizationId)
                .build();
        try {
            activeContractPointerRepository.saveAndFlush(pointer);
        } catch (DataIntegrityViolationException ex) {
            // 既存スロットが PENDING（入金前）なら 016、ACTIVE なら 006 を明示（設計書 02 §決済フロー）。
            throw pendingOrActiveConflict(scopeKind, scopeId, contractKind, slotAddonKey, ex);
        }

        return new ContractResult(saved.getId(), scopeKind, scopeId, contractKind, planKey, featureKey,
                ContractStatus.PENDING, memberCountSnapshot, bandNoSnapshot, priceJpy, now, null, null,
                List.of(), List.of());
    }

    /**
     * PENDING 契約を入金確定で ACTIVE 化し entitlements を発行する（{@code checkout.session.completed}・設計書 02）。
     *
     * <p><b>冪等</b>: 既に ACTIVE なら no-op（webhook 再送でも二重発行しない・AC-34）。PENDING 以外（CANCELLED 等）も no-op。
     * PSP 参照（customer/subscription）と {@code current_period_end} を焼き付ける。</p>
     *
     * @return 発行結果（no-op 時は現状の契約・対象契約なし時は {@code null}）
     */
    @Transactional
    public ContractResult activatePaidContract(
            UUID contractId, String pspCustomerRef, String pspSubscriptionRef, LocalDateTime currentPeriodEnd) {
        BillingContractEntity contract = billingContractRepository.findByIdAndDeletedAtIsNull(contractId).orElse(null);
        if (contract == null) {
            return null; // 対象なし（他テナント/削除済み）は呼び出し側が no-op ログ。
        }
        if (contract.getStatus() == ContractStatus.ACTIVE) {
            // 冪等: 既に発行済み。PSP 参照だけ欠けていれば補完する。
            return activeResult(contract, resolveFeatureKeys(contract));
        }
        if (contract.getStatus() != ContractStatus.PENDING) {
            return activeResult(contract, List.of());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        contract.setStatus(ContractStatus.ACTIVE);
        contract.setPspCustomerRef(pspCustomerRef);
        contract.setPspSubscriptionRef(pspSubscriptionRef);
        contract.setCurrentPeriodEnd(currentPeriodEnd);
        billingContractRepository.save(contract);

        List<String> grantedKeys = resolveFeatureKeys(contract);
        issueEntitlements(contract.getScopeKind(), contract.getScopeId(), contract.getOrganizationId(),
                grantedKeys, toSourceKind(contract.getContractKind()), contract.getId(), now);
        evictAfterCommit(contract.getScopeKind(), contract.getScopeId(), grantedKeys);

        return new ContractResult(contract.getId(), contract.getScopeKind(), contract.getScopeId(),
                contract.getContractKind(), contract.getPlanKey(), contract.getFeatureKey(), ContractStatus.ACTIVE,
                contract.getMemberCountSnapshot(), contract.getBandNoSnapshot(), contract.getPriceJpySnapshot(),
                contract.getContractedAt(), currentPeriodEnd, null,
                new ArrayList<>(grantedKeys), List.of());
    }

    /**
     * PENDING 契約を放棄（CANCELLED＋pointer 物理 DELETE）して再挑戦可能にする（{@code checkout.session.expired}・
     * 決済 API 失敗の補償）。<b>冪等</b>: PENDING 以外は no-op。entitlements は未発行のため revoke しない。
     */
    @Transactional
    public void abandonPendingContract(UUID contractId) {
        BillingContractEntity contract = billingContractRepository.findByIdAndDeletedAtIsNull(contractId).orElse(null);
        if (contract == null || contract.getStatus() != ContractStatus.PENDING) {
            return;
        }
        contract.setStatus(ContractStatus.CANCELLED);
        contract.setCancelledAt(LocalDateTime.now(clock));
        billingContractRepository.save(contract);
        String slotAddonKey = contract.getContractKind() == ContractKind.ADDON ? contract.getFeatureKey() : "";
        activeContractPointerRepository.hardDeleteBySlot(
                contract.getScopeKind(), contract.getScopeId(), contract.getContractKind(), slotAddonKey);
    }

    /**
     * 継続課金の失効（{@code customer.subscription.deleted}）: EXPIRED＋pointer 物理 DELETE＋残 entitlements revoke。
     * <b>冪等</b>: 既に EXPIRED/CANCELLED なら no-op。
     */
    @Transactional
    public void expireSubscriptionContract(String pspSubscriptionRef, LocalDateTime currentPeriodEnd) {
        BillingContractEntity contract = billingContractRepository
                .findByPspSubscriptionRefAndDeletedAtIsNull(pspSubscriptionRef).orElse(null);
        if (contract == null
                || contract.getStatus() == ContractStatus.EXPIRED
                || contract.getStatus() == ContractStatus.CANCELLED) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        contract.setStatus(ContractStatus.EXPIRED);
        if (currentPeriodEnd != null) {
            contract.setCurrentPeriodEnd(currentPeriodEnd);
        }
        billingContractRepository.save(contract);
        String slotAddonKey = contract.getContractKind() == ContractKind.ADDON ? contract.getFeatureKey() : "";
        activeContractPointerRepository.hardDeleteBySlot(
                contract.getScopeKind(), contract.getScopeId(), contract.getContractKind(), slotAddonKey);
        List<String> revokedKeys = revokeEntitlementsOfContract(contract, null, now);
        evictAfterCommit(contract.getScopeKind(), contract.getScopeId(), revokedKeys);
    }

    /**
     * 支払失敗で PAST_DUE へ（{@code invoice.payment_failed}）。<b>権利は触らない</b>
     * （{@code current_period_end} まで利用可・AC-37）。<b>冪等</b>: ACTIVE のときのみ遷移。
     */
    @Transactional
    public void markContractPastDue(String pspSubscriptionRef) {
        BillingContractEntity contract = billingContractRepository
                .findByPspSubscriptionRefAndDeletedAtIsNull(pspSubscriptionRef).orElse(null);
        if (contract == null || contract.getStatus() != ContractStatus.ACTIVE) {
            return;
        }
        contract.setStatus(ContractStatus.PAST_DUE);
        billingContractRepository.save(contract);
    }

    /**
     * 各サイクルの入金成立（{@code invoice.paid}）: {@code current_period_end} 延長・PAST_DUE→ACTIVE 回復（AC-37）。
     * entitlements は無期限（valid_until=NULL）のまま継続する。<b>冪等</b>。
     */
    @Transactional
    public void extendContractPeriod(String pspSubscriptionRef, LocalDateTime currentPeriodEnd) {
        BillingContractEntity contract = billingContractRepository
                .findByPspSubscriptionRefAndDeletedAtIsNull(pspSubscriptionRef).orElse(null);
        if (contract == null) {
            return;
        }
        if (currentPeriodEnd != null) {
            contract.setCurrentPeriodEnd(currentPeriodEnd);
        }
        if (contract.getStatus() == ContractStatus.PAST_DUE) {
            contract.setStatus(ContractStatus.ACTIVE);
        }
        billingContractRepository.save(contract);
    }

    /**
     * D-3 有償解約: 期末解約予約（{@code cancel_at_period_end}）＋entitlements の {@code valid_until} を
     * {@code current_period_end} へ（webhook 未達でも期末に自動失効する保険・半開区間）。契約は ACTIVE のまま
     * {@code cancelled_at} をセットする（{@code customer.subscription.deleted} で EXPIRED＋残 revoke）。
     */
    private ContractResult cancelPaidAtPeriodEnd(
            EntitlementScopeKind scopeKind, Long scopeId, BillingContractEntity contract,
            Long operatorUserId, LocalDateTime now) {

        Instant periodEnd = billingPaymentGateway.cancelAtPeriodEnd(contract.getPspSubscriptionRef());
        LocalDateTime periodEndLdt = periodEnd != null
                ? LocalDateTime.ofInstant(periodEnd, clock.getZone())
                : contract.getCurrentPeriodEnd();

        contract.setCancelledAt(now);
        if (periodEndLdt != null) {
            contract.setCurrentPeriodEnd(periodEndLdt);
        }
        billingContractRepository.save(contract);

        // 由来 entitlements の valid_until を期末に（半開区間 [from, periodEnd)・保険）。
        List<EntitlementEntity> rows = entitlementRepository
                .findBySourceKindAndSourceRefIdAndRevokedAtIsNull(toSourceKind(contract.getContractKind()), contract.getId());
        List<String> stillActiveKeys = new ArrayList<>();
        for (EntitlementEntity e : rows) {
            e.setValidUntil(periodEndLdt);
            stillActiveKeys.add(e.getFeatureKey());
        }
        if (!rows.isEmpty()) {
            entitlementRepository.saveAll(rows);
        }
        evictAfterCommit(scopeKind, scopeId, stillActiveKeys);

        return new ContractResult(contract.getId(), scopeKind, scopeId, contract.getContractKind(),
                contract.getPlanKey(), contract.getFeatureKey(), ContractStatus.ACTIVE,
                contract.getMemberCountSnapshot(), contract.getBandNoSnapshot(), contract.getPriceJpySnapshot(),
                contract.getContractedAt(), periodEndLdt, null,
                new ArrayList<>(stillActiveKeys), List.of());
    }

    /** 契約が既に ACTIVE のときの結果組み立て（冪等 no-op 用）。 */
    private ContractResult activeResult(BillingContractEntity contract, List<String> grantedKeys) {
        return new ContractResult(contract.getId(), contract.getScopeKind(), contract.getScopeId(),
                contract.getContractKind(), contract.getPlanKey(), contract.getFeatureKey(), contract.getStatus(),
                contract.getMemberCountSnapshot(), contract.getBandNoSnapshot(), contract.getPriceJpySnapshot(),
                contract.getContractedAt(), contract.getCurrentPeriodEnd(), null,
                new ArrayList<>(grantedKeys), List.of());
    }

    /** 契約の発行対象 feature_key を解決する（PLAN=プラン所属機能／ADDON=当該 feature・enabled 再検証しない）。 */
    private List<String> resolveFeatureKeys(BillingContractEntity contract) {
        if (contract.getContractKind() == ContractKind.ADDON) {
            return contract.getFeatureKey() == null ? List.of() : List.of(contract.getFeatureKey());
        }
        List<String> keys = new ArrayList<>();
        for (PlanFeatureEntity pf : planFeatureRepository.findByPlanKey(contract.getPlanKey())) {
            keys.add(pf.getFeatureKey());
        }
        return keys;
    }

    /** pointer UNIQUE 競合時、既存スロットの契約が PENDING なら 016、それ以外（ACTIVE 等）は 006 を投げる。 */
    private BusinessException pendingOrActiveConflict(
            EntitlementScopeKind scopeKind, Long scopeId, ContractKind contractKind,
            String slotAddonKey, DataIntegrityViolationException cause) {
        ContractStatus existing = activeContractPointerRepository
                .findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(scopeKind, scopeId, contractKind, slotAddonKey)
                .flatMap(p -> billingContractRepository.findByIdAndDeletedAtIsNull(p.getContractId()))
                .map(BillingContractEntity::getStatus)
                .orElse(null);
        if (existing == ContractStatus.PENDING) {
            return new BusinessException(EntitlementErrorCode.CONTRACT_PENDING_PAYMENT, cause);
        }
        return new BusinessException(EntitlementErrorCode.CONTRACT_ALREADY_ACTIVE, cause);
    }

    // ============================================================
    // 内部ヘルパ
    // ============================================================

    /**
     * キャッシュ evict を<b>トランザクション確定後（AFTER_COMMIT）</b>に実行する（設計書 02 §8・03 §5）。
     *
     * <p><b>なぜ commit 前に呼んではいけないか</b>: revoke/ダウングレードの書き込みがまだコミットされていない
     * 時点で evict すると、evict と commit の隙に別スレッドの {@code isEntitled} がキャッシュミス→
     * READ_COMMITTED 下で<b>未コミットの revoke が見えない DB</b> を読み→stale な {@code entitled=true} を
     * 最大 TTL 秒（60 秒）再ポピュレートしてしまう（取消の即時反映というセキュリティ性質を弱める・
     * memory {@code feedback_security_invalidation_rolled_back_by_same_tx_throw} の類型）。よって
     * <b>コミット確定後にのみ evict</b> する（ロールバック時は {@code afterCommit} が呼ばれないため evict しない）。</p>
     *
     * <p>トランザクション同期が無い文脈（単体テスト等）では即時 evict にフォールバックする。</p>
     */
    private void evictAfterCommit(EntitlementScopeKind scopeKind, Long scopeId, Collection<String> featureKeys) {
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

    /** 子リソース（contractId）→ 所属スコープを解決し、パスのスコープと一致検証する（IDOR 二重防御・03 §2）。 */
    private BillingContractEntity loadContractInScope(
            EntitlementScopeKind scopeKind, Long scopeId, UUID contractId) {
        BillingContractEntity contract = billingContractRepository.findByIdAndDeletedAtIsNull(contractId)
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND));
        if (contract.getScopeKind() != scopeKind || !contract.getScopeId().equals(scopeId)) {
            // 他スコープの契約 ID は存在自体を明かさず 404 秘匿。
            throw new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND);
        }
        return contract;
    }

    private List<String> validatePlanAndResolveFeatures(String planKey) {
        if (planKey == null || planKey.isBlank()) {
            throw new BusinessException(EntitlementErrorCode.PLAN_NOT_FOUND);
        }
        PlanEntity plan = planRepository.findById(planKey).orElse(null);
        if (plan == null || !Boolean.TRUE.equals(plan.getEnabled())) {
            throw new BusinessException(EntitlementErrorCode.PLAN_NOT_FOUND);
        }
        List<String> keys = new ArrayList<>();
        for (PlanFeatureEntity pf : planFeatureRepository.findByPlanKey(planKey)) {
            keys.add(pf.getFeatureKey());
        }
        return keys;
    }

    private void validateAddonFeature(String featureKey) {
        if (featureKey == null || featureKey.isBlank()) {
            throw new BusinessException(EntitlementErrorCode.FEATURE_NOT_FOUND);
        }
        FeatureCatalogEntity feature = featureCatalogRepository.findById(featureKey).orElse(null);
        if (feature == null || !Boolean.TRUE.equals(feature.getEnabled())) {
            throw new BusinessException(EntitlementErrorCode.FEATURE_NOT_FOUND);
        }
        if (!Boolean.TRUE.equals(feature.getAddonAvailable())) {
            throw new BusinessException(EntitlementErrorCode.ADDON_NOT_AVAILABLE);
        }
    }

    /** 発行元契約に紐づく未取消 entitlements を全 revoke し、対象 feature_key 集合を返す（AC-20）。 */
    private List<String> revokeEntitlementsOfContract(
            BillingContractEntity contract, Long operatorUserId, LocalDateTime now) {
        EntitlementSourceKind sourceKind = toSourceKind(contract.getContractKind());
        List<EntitlementEntity> rows = entitlementRepository
                .findBySourceKindAndSourceRefIdAndRevokedAtIsNull(sourceKind, contract.getId());
        List<String> revokedKeys = new ArrayList<>();
        for (EntitlementEntity e : rows) {
            e.setRevokedAt(now);
            e.setRevokedBy(operatorUserId);
            revokedKeys.add(e.getFeatureKey());
        }
        if (!rows.isEmpty()) {
            entitlementRepository.saveAll(rows);
        }
        return revokedKeys;
    }

    private void issueEntitlements(
            EntitlementScopeKind scopeKind, Long scopeId, Long organizationId,
            List<String> featureKeys, EntitlementSourceKind sourceKind, UUID sourceRefId, LocalDateTime now) {
        List<EntitlementEntity> rows = new ArrayList<>();
        for (String featureKey : featureKeys) {
            rows.add(EntitlementEntity.builder()
                    .scopeKind(scopeKind)
                    .scopeId(scopeId)
                    .featureKey(featureKey)
                    .sourceKind(sourceKind)
                    .sourceRefId(sourceRefId)
                    .validFrom(now)
                    .validUntil(null)          // 無期限（ベータ中）。
                    .organizationId(organizationId)
                    .build());
        }
        if (!rows.isEmpty()) {
            try {
                entitlementRepository.saveAll(rows);
                entitlementRepository.flush();
            } catch (DataIntegrityViolationException ex) {
                // uk_ent_grant 違反（同一発行元×同時刻の二重発行・AC-21）。
                throw new BusinessException(EntitlementErrorCode.DUPLICATE_ENTITLEMENT, ex);
            }
        }
    }

    /** 人数バンドを解決する（TEAM/ORG の PLAN のみ。バンド未定義なら null）。 */
    private Short resolveBandNo(String planKey, EntitlementScopeKind scopeKind, int memberCount) {
        PlanPriceBandScopeKind bandScope = toBandScope(scopeKind);
        if (bandScope == null) {
            return null;
        }
        for (PlanPriceBandEntity band
                : planPriceBandRepository.findByPlanKeyAndScopeKindOrderByBandNoAsc(planKey, bandScope)) {
            boolean lowerOk = band.getMinMembers() != null && memberCount >= band.getMinMembers();
            boolean upperOk = band.getMaxMembers() == null || memberCount <= band.getMaxMembers();
            if (lowerOk && upperOk) {
                return band.getBandNo();
            }
        }
        return null;
    }

    private static PlanPriceBandScopeKind toBandScope(EntitlementScopeKind scopeKind) {
        switch (scopeKind) {
            case TEAM:
                return PlanPriceBandScopeKind.TEAM;
            case ORG:
                return PlanPriceBandScopeKind.ORG;
            default:
                return null; // USER はバンド無し。
        }
    }

    private static EntitlementSourceKind toSourceKind(ContractKind contractKind) {
        return contractKind == ContractKind.ADDON
                ? EntitlementSourceKind.ADDON : EntitlementSourceKind.PLAN;
    }
}
