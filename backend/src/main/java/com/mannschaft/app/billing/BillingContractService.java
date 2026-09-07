package com.mannschaft.app.billing;

import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * （ADMIN又は明示委譲DEPUTYか）は public 入口の {@code @PreAuthorize} を一次防御とし、本サービスの書込
 * トランザクション内でも操作者行ロック後に再確認する。本サービスは受領した scope を信頼せず、子リソース系は
 * 必ず一致検証で二重防御する。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
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
    /** F20.1 実決済（AC-44）: changePlan の変更先プラン価格解決（有償なら 409 拒否）。 */
    private final BillingPriceResolver billingPriceResolver;
    /**
     * F20.3 設計判断①: entitlements 発行の共有サービス（PLAN/ADDON/BETA_GRANT 共通）。
     * 元 private {@code issueEntitlements} の INSERT ロジックを本サービスへ抽出した（挙動不変）。
     */
    private final EntitlementIssuanceService entitlementIssuanceService;
    /** 課金権限解除と契約変更予約を操作者行ロックで直列化する。 */
    private final BillingOperationAuthorizer billingOperationAuthorizer;

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

    /**
     * 柱③-B: payer 起点で検出した引継対象契約（§1.2 の検出漏れを塞ぐクエリの返り値）。
     *
     * <p>D-1（API 境界）によりサービス API はエンティティを露出できないため、
     * PR-3 の退会ハンドラ（{@code cancelAllForPayerOnWithdrawal}）が期末解約の判断に必要とする
     * 項目だけを持つ値オブジェクトとして返す。{@code pspSubscriptionRef} と
     * {@code currentPeriodEnd} は §5.1 の絞り込みにより非 null が保証される。</p>
     */
    public record HandoverTargetContract(
            UUID contractId,
            EntitlementScopeKind scopeKind,
            Long scopeId,
            ContractStatus status,
            String pspSubscriptionRef,
            LocalDateTime currentPeriodEnd) {
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

        billingOperationAuthorizer.requireCanManage(operatorUserId, scopeKind, scopeId);
        return createContractInternal(scopeKind, scopeId, organizationId,
                contractKind, planKey, featureKey, operatorUserId);
    }

    /** SYSTEM_ADMIN専用入口。Controller側のSYSTEM_ADMIN認可を経た手動付与だけが利用する。 */
    @Transactional
    public ContractResult createContractBySystemAdmin(
            EntitlementScopeKind scopeKind, Long scopeId, Long organizationId,
            ContractKind contractKind, String planKey, String featureKey, Long operatorUserId) {
        return createContractInternal(scopeKind, scopeId, organizationId,
                contractKind, planKey, featureKey, operatorUserId);
    }

    private ContractResult createContractInternal(
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
                .payerUserId(operatorUserId)
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

        entitlementIssuanceService.issue(scopeKind, scopeId, organizationId, grantedKeys,
                toSourceKind(contractKind), saved.getId(), null);

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

        billingOperationAuthorizer.requireCanManage(operatorUserId, scopeKind, scopeId);
        LocalDateTime now = LocalDateTime.now(clock);
        BillingContractEntity contract = loadContractInScope(scopeKind, scopeId, contractId);
        if (contract.getStatus() != ContractStatus.ACTIVE) {
            throw new BusinessException(EntitlementErrorCode.CONTRACT_NOT_CANCELLABLE);
        }
        // ★AC-46: 期末解約予約済み（有償・status=ACTIVE のまま cancelled_at セット済み）への再解約は 409。
        // status チェックだけでは素通りし、Stripe cancel_at_period_end の再送・valid_until の再上書きが起こるため。
        if (contract.getCancelledAt() != null) {
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

        billingOperationAuthorizer.requireCanManage(operatorUserId, scopeKind, scopeId);
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

        // ★AC-44（検分差し戻し・御裁可済み簡潔案A）: changePlan は決済レールを持たないため、有償が絡む変更は
        // 二重ガードで 409 拒否し「解約→新規契約」へ誘導する。
        // (a) 既存契約が有償（psp_subscription_ref 非 NULL）: 旧契約を CANCELLED にしても Stripe サブスクは
        //     解約されず課金継続する（孤児化）ため拒否。
        if (oldContract.getPspSubscriptionRef() != null) {
            throw new BusinessException(EntitlementErrorCode.CONTRACT_CHANGE_REQUIRES_PAYMENT);
        }
        // (b) 変更先プランが有償（価格設定済み）: Checkout を経ず priceJpySnapshot=NULL で即 ACTIVE になると
        //     有料機能の無償付与（D-4 の抜け穴）になるため拒否。
        Integer newPlanPrice = billingPriceResolver.resolveMonthlyPriceJpy(
                scopeKind, scopeId, ContractKind.PLAN, newPlanKey, null);
        if (newPlanPrice != null && newPlanPrice > 0) {
            throw new BusinessException(EntitlementErrorCode.CONTRACT_CHANGE_REQUIRES_PAYMENT);
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
                .payerUserId(operatorUserId)
                .build();
        BillingContractEntity savedNew = billingContractRepository.save(newContract);

        // pointer.contract_id を新契約へ付け替える（行を増やさない・PLAN スロット addon_feature_key="")。
        ActiveContractPointerEntity pointer = activeContractPointerRepository
                .findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                        scopeKind, scopeId, ContractKind.PLAN, "")
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND));
        pointer.setContractId(savedNew.getId());
        activeContractPointerRepository.save(pointer);

        entitlementIssuanceService.issue(scopeKind, scopeId, oldContract.getOrganizationId(), newKeys,
                EntitlementSourceKind.PLAN, savedNew.getId(), null);

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

        billingOperationAuthorizer.requireCanManage(operatorUserId, scopeKind, scopeId);
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
                .payerUserId(operatorUserId)
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
        entitlementIssuanceService.issue(contract.getScopeKind(), contract.getScopeId(),
                contract.getOrganizationId(), grantedKeys, toSourceKind(contract.getContractKind()),
                contract.getId(), null);
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
     *
     * <p><b>柱③-B 請求担当引継（CMP-260901-1538・設計書 §3.1・Codex検分1巡目P1-2対応）</b>: {@code PENDING_HANDOVER}
     * の契約はこの経路で {@code EXPIRED} 化しない。設計書上 {@code PENDING_HANDOVER} の出口は切替TX成功時の
     * {@code ACTIVE} と引継失敗時の {@code CANCELLED} のみであり、通常の {@code customer.subscription.deleted}
     * による失効遷移の対象ではない（引継の新契約に紐づく Stripe subscription の trial 取消・失敗による
     * webhook がこの経路に入り得るため、無条件 EXPIRED 化のガードから明示的に除外する）。ここで検知した場合は
     * 警告ログのみを残し状態は変更しない。将来的な自動救済（設計書 §3.6.2 の {@code MANUAL_INTERVENTION} 経由の
     * 検知・アラート）は後続 PR（PR-4）のスコープとする。</p>
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
        if (contract.getStatus() == ContractStatus.PENDING_HANDOVER) {
            // 柱③-B: PENDING_HANDOVER は customer.subscription.deleted による EXPIRED 遷移の対象外
            // （設計書§3.1。出口は切替TX成功時のACTIVE／引継失敗時のCANCELLEDのみ）。
            log.warn("柱③-B: PENDING_HANDOVER 契約への customer.subscription.deleted webhook を検出し、"
                    + "EXPIRED遷移をスキップしました（contractId={}, pspSubscriptionRef={}）。"
                    + "引継の新規trialサブスク取消/失敗の可能性があり、手動確認またはPR-4のMANUAL_INTERVENTION"
                    + "検知バッチでの救済が必要な場合があります。",
                    contract.getId(), pspSubscriptionRef);
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
     * 退会 purge 確定時の USER スコープ契約一括解約（AC-45・設計書 03 §8）。
     *
     * <p>PENDING/ACTIVE/PAST_DUE の USER スコープ契約を全て CANCELLED＋pointer 物理 DELETE＋
     * 由来 entitlements revoke＋evict する（撤回窓は閉じており復活不可で問題ない）。
     * <b>Stripe サブスクの即時解約は本メソッドの外</b>（{@link BillingPurgeEventListener}・tx 外）で行うため、
     * 有償契約の {@code psp_subscription_ref} 集合を返す。</p>
     *
     * <p>{@code REQUIRES_NEW}: {@code @TransactionalEventListener(AFTER_COMMIT)} から呼ばれる書き込みは
     * 完了済み tx への参加では silent no-op になるため独立 tx で行う（memory
     * {@code feedback_transactional_event_listener_requires_new} の掟）。</p>
     *
     * @return 即時解約すべき有償契約の Stripe Subscription ID 集合
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public List<String> cancelAllUserContractsForPurge(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<BillingContractEntity> contracts = billingContractRepository
                .findByScopeKindAndScopeIdAndStatusInAndDeletedAtIsNull(
                        EntitlementScopeKind.USER, userId,
                        List.of(ContractStatus.PENDING, ContractStatus.ACTIVE, ContractStatus.PAST_DUE));
        List<String> paidSubscriptionRefs = new ArrayList<>();
        Set<String> revokedKeys = new LinkedHashSet<>();
        for (BillingContractEntity contract : contracts) {
            if (contract.getPspSubscriptionRef() != null) {
                paidSubscriptionRefs.add(contract.getPspSubscriptionRef());
            }
            contract.setStatus(ContractStatus.CANCELLED);
            contract.setCancelledAt(now);
            billingContractRepository.save(contract);
            String slotAddonKey = contract.getContractKind() == ContractKind.ADDON
                    ? contract.getFeatureKey() : "";
            activeContractPointerRepository.hardDeleteBySlot(
                    EntitlementScopeKind.USER, userId, contract.getContractKind(), slotAddonKey);
            revokedKeys.addAll(revokeEntitlementsOfContract(contract, null, now));
        }
        if (!contracts.isEmpty()) {
            evictAfterCommit(EntitlementScopeKind.USER, userId, revokedKeys);
        }
        return paidSubscriptionRefs;
    }

    /**
     * 柱③-B: 退会予定ユーザーが<b>実質決済者（payer）</b>である TEAM/ORG 契約を検出する
     * （設計書 {@code billing_payer_handover_design.md} §1.2・§5.1・AC-3）。
     *
     * <p><b>根本原因（§1.2）</b>: {@link #cancelAllUserContractsForPurge} は
     * {@code scope_kind=USER} 固定で検索するため、ある個人が TEAM/ORG 契約の実質 payer であっても
     * この条件には一切引っかからない。結果、<b>退会30日後の物理匿名化を経てもなお、TEAM/ORG 契約は
     * 退会者個人の Stripe Customer への課金を止めずに継続する</b>。本メソッドはその検出漏れを
     * {@code payer_user_id} 起点のクエリで塞ぐ。</p>
     *
     * <p><b>絞り込み（§5.1・R2-P1-6）</b>: {@code psp_subscription_ref} と {@code current_period_end} が
     * ともに非 NULL の契約のみを返す。引継フロー（{@code trial_end} 方式）は「Stripe 側に実在する
     * サブスクの期末」を前提とするため、無償契約や PSP 未作成の {@code PENDING} 契約には適用できない
     * （それらは Stripe 操作を伴わない「payer 概念のみの更新」という別経路で扱う）。</p>
     *
     * <p><b>なぜ本メソッドが解約まで行わないか（PR 分割の境界）</b>: 検出した TEAM/ORG 契約を
     * その場で解約してはならない。§5.4 の原則により、引継要求が非終端
     * （{@code REQUESTED}/{@code ACCEPTED}/{@code REQUIRES_PAYMENT_METHOD}/{@code SWITCHING}/
     * {@code PARTIALLY_COMPLETED}/{@code MANUAL_INTERVENTION}）の間は purge 側の期末解約フォールバックを
     * <b>発火させてはならない</b>からである（引継が進行中の契約を purge が横から解約すると、
     * 承諾済みの新 payer ごと契約を失う）。またフォールバックは USER 契約のような即時解約ではなく
     * <b>期末解約</b>（{@code cancelAtPeriodEnd}）である（§5.3）。この分岐を担う退会ハンドラの実装は
     * PR-3（{@code WithdrawalStripeHandler} / {@code cancelAllForPayerOnWithdrawal}）のスコープであり、
     * 本メソッドはその<b>検出面のみ</b>を PR-2 として先行提供する。</p>
     *
     * @param payerUserId 退会予定ユーザー（{@code payer_user_id} 一致）
     * @return 引継の適用対象となる TEAM/ORG 契約（該当なしなら空）。
     *         D-1（API 境界）によりエンティティではなく {@link HandoverTargetContract} で返す
     */
    @Transactional(readOnly = true)
    public List<HandoverTargetContract> findHandoverTargetContractsForPayer(Long payerUserId) {
        return billingContractRepository
                .findByPayerUserIdAndScopeKindInAndStatusInAndPspSubscriptionRefIsNotNullAndCurrentPeriodEndIsNotNullAndDeletedAtIsNull(
                        payerUserId,
                        List.of(EntitlementScopeKind.TEAM, EntitlementScopeKind.ORG),
                        List.of(ContractStatus.PENDING, ContractStatus.ACTIVE, ContractStatus.PAST_DUE))
                .stream()
                .map(contract -> new HandoverTargetContract(
                        contract.getId(),
                        contract.getScopeKind(),
                        contract.getScopeId(),
                        contract.getStatus(),
                        contract.getPspSubscriptionRef(),
                        contract.getCurrentPeriodEnd()))
                .toList();
    }

    /**
     * 残債1（GDPR purge retry）: 退会 purge で CANCELLED 済みだが Stripe 即時解約が未確認の USER スコープ
     * 有償契約の Subscription ID 一覧を返す。
     *
     * <p>{@link #cancelAllUserContractsForPurge} は status IN (PENDING, ACTIVE, PAST_DUE) の契約のみを
     * 対象にするため、DB 遷移が既に完了した（＝status=CANCELLED になった）契約は 2 回目以降の呼び出しで
     * 対象外になる（意図した冪等性）。しかし「DB は CANCELLED 済みだが直前の Stripe 即時解約 API 呼び出しが
     * 失敗した」契約は、その冪等性ゆえに再度 {@link #cancelAllUserContractsForPurge} を呼んでも
     * subscriptionRef を再取得できない（Stripe だけ取り残される穴）。本メソッドは
     * {@link BillingContractRepository#findByScopeKindAndScopeIdAndStatusAndPspSubscriptionRefIsNotNullAndDeletedAtIsNull}
     * でこの「Stripe だけ取り残された」契約を発見し、{@code BillingPurgeEventListener#retryPurge} が
     * Stripe 解約を再試行できるようにする。</p>
     *
     * @param userId 対象ユーザー ID
     * @return Stripe 解約が未確認の Subscription ID 一覧（重複なし）
     */
    @Transactional(readOnly = true)
    public List<String> findPurgedPaidSubscriptionRefsPendingStripeCancel(Long userId) {
        return billingContractRepository
                .findByScopeKindAndScopeIdAndStatusAndPspSubscriptionRefIsNotNullAndDeletedAtIsNull(
                        EntitlementScopeKind.USER, userId, ContractStatus.CANCELLED)
                .stream()
                .map(BillingContractEntity::getPspSubscriptionRef)
                .distinct()
                .toList();
    }

    /**
     * D-3 有償解約: 期末解約予約（{@code cancel_at_period_end}）＋entitlements の {@code valid_until} を
     * {@code current_period_end} へ（webhook 未達でも期末に自動失効する保険・半開区間）。契約は ACTIVE のまま
     * {@code cancelled_at} をセットする（{@code customer.subscription.deleted} で EXPIRED＋残 revoke）。
     */
    private ContractResult cancelPaidAtPeriodEnd(
            EntitlementScopeKind scopeKind, Long scopeId, BillingContractEntity contract,
            Long operatorUserId, LocalDateTime now) {

        // 【設計注記（検分5番・判断済み）】gateway 呼び出しは cancelContract の @Transactional 内で行う。
        // 「Stripe 呼び出し→tx 更新」への再構成は、無償/有償が共有する cancelContract 経路（IDOR 検証→
        // ガード→分岐）の分解を要し P1 経路の回帰リスクが高いため現状維持とする。整合性は以下で担保される:
        //  - Stripe 失敗 → 例外で tx ロールバック（DB 無変更・再実行可能・不整合なし）。
        //  - Stripe 成功後に tx ロールバック → DB は解約予約なしのままだが、Stripe の cancel_at_period_end は
        //    有効なため期末に customer.subscription.deleted webhook が届き、expireSubscriptionContract が
        //    EXPIRED＋pointer DELETE＋残 revoke で自己修復する（webhook 自己修復）。
        //  - cancelAtPeriodEnd は Stripe 冪等キー付きで再送安全（AC-46 の二重解約ガードとも二層）。
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
