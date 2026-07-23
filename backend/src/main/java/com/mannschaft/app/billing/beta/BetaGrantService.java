package com.mannschaft.app.billing.beta;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.EntitlementCacheEvictor;
import com.mannschaft.app.billing.EntitlementEntity;
import com.mannschaft.app.billing.EntitlementIssuanceService;
import com.mannschaft.app.billing.EntitlementRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.EntitlementSourceKind;
import com.mannschaft.app.billing.PlanFeatureEntity;
import com.mannschaft.app.billing.PlanFeatureRepository;
import com.mannschaft.app.billing.ScopeMemberCountService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.gamification.service.BetaTesterBadgeAwardService;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.service.NotificationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F20.3 ベータ特典: 付与メタ（{@code beta_grants}）と権利実体（{@code entitlements}）を単一トランザクションで
 * 束ねる本体サービス（設計書 01 §3 / 02 §3・§4）。付与・取消・延長・退会一括取消を担う。
 *
 * <p><b>単一トランザクションの原子性（AC-I1）</b>: 付与メタ save → 権利発行（{@link EntitlementIssuanceService}）
 * を同一 {@code @Transactional} 内で行い、権利発行が例外を投げれば {@code beta_grants} 行を含めて全ロールバックする。</p>
 *
 * <p><b>クロスドメイン境界</b>: 本サービスは {@code @Transactional} だが、直接依存するのは billing ドメイン内の
 * リポジトリ（{@link EntitlementRepository} / {@link PlanFeatureRepository} / {@link BetaGrantRepository}）と、
 * gamification / notification の<b>Service</b>（{@link BetaTesterBadgeAwardService} / {@link NotificationHelper}）
 * のみ。他ドメインの {@code ..repository} / {@code ..entity} を直接参照しないため、クロスドメイン番人
 * D-1 / D-3 に抵触しない（バッジ授与は gamification ドメインの Service へ委譲・在籍/組織解決は非 tx の
 * QueryService/API 層へ委譲）。</p>
 *
 * <p><b>evict は AFTER_COMMIT</b>: 取消・延長の権利変更が確定した後にのみ判定キャッシュ（{@code entitlement:check}）を
 * evict する（{@code BillingContractService.evictAfterCommit} と同型・memory
 * {@code feedback_security_invalidation_rolled_back_by_same_tx_throw}）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BetaGrantService {

    /** 付与対象の機能セット＝付与時点の FULL プラン構成のスナップショット（設計書 README §1）。 */
    private static final String FULL_PLAN_KEY = "FULL";
    /** TEAM_ORG 特典の無償期間の下限（2 年・設計書 README §1.1・AC-04）。 */
    private static final int TEAM_ORG_VALID_YEARS = 2;
    /** 延長月数の許容範囲（設計書 02 §4.3・実 400 ゲートは隊2 の DTO @Min/@Max）。 */
    private static final int EXTEND_MONTHS_MIN = 1;
    private static final int EXTEND_MONTHS_MAX = 24;

    private final BetaGrantRepository betaGrantRepository;
    private final EntitlementRepository entitlementRepository;
    private final PlanFeatureRepository planFeatureRepository;
    private final EntitlementIssuanceService entitlementIssuanceService;
    private final EntitlementCacheEvictor cacheEvictor;
    private final ScopeMemberCountService scopeMemberCountService;
    private final BetaPerkEligibilityService eligibilityService;
    private final BetaTesterBadgeAwardService betaTesterBadgeAwardService;
    private final NotificationHelper notificationHelper;
    private final MessageSource messageSource;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // ============================================================
    // 付与
    // ============================================================

    /**
     * ベータ特典を付与する（設計書 01 §3・02 §4.1）。付与メタ＋ FULL 構成の権利＋（個人のみ）称号バッジ＋通知を
     * 単一トランザクションで確定する。
     *
     * @param grantKind        INDIVIDUAL / TEAM_ORG
     * @param betaPhase        ベータ段階（1〜4・範囲外は {@link BetaPerkErrorCode#BETA_PHASE_INVALID}）
     * @param scopeKind        USER / TEAM / ORG（kind との不整合は {@link BetaPerkErrorCode#GRANT_SCOPE_MISMATCH}）
     * @param scopeId          users.id / teams.id / organizations.id
     * @param organizationId   テナント（USER=null / ORG=scope_id / TEAM=主所属組織）。<b>API 層（隊2）が
     *                         F20.1 と同じ {@code resolveOrganizationId} で解決して渡す</b>（越境 team 参照を
     *                         本 tx に持ち込まないため・設計書 01 §1）
     * @param skipCriteriaCheck true=criteria 未達でも付与（マスター運用の例外・audit に明示）／false=未達なら
     *                          {@link BetaPerkErrorCode#ACTIVITY_CRITERIA_NOT_MET}
     * @param grantedBy        付与操作者（シスアド userId。自動付与バッチは null=SYSTEM）
     * @return 保存した付与メタ（隊2 が {@code BetaGrantDetailResponse} へマップ）
     */
    @Transactional
    public BetaGrantEntity grantBetaPerk(
            GrantKind grantKind, int betaPhase, EntitlementScopeKind scopeKind, Long scopeId,
            Long organizationId, boolean skipCriteriaCheck, Long grantedBy) {

        validatePhase(betaPhase);
        validateKindScope(grantKind, scopeKind);

        // 二重付与（同一 scope × phase・取消済みも含む＝終端で再付与不可）。
        if (betaGrantRepository.findByScopeKindAndScopeIdAndBetaPhase(scopeKind, scopeId, betaPhase).isPresent()) {
            throw new BusinessException(BetaPerkErrorCode.GRANT_ALREADY_EXISTS);
        }

        // 付与条件の評価と criteria_snapshot の焼き付け。
        String criteriaSnapshot;
        if (skipCriteriaCheck) {
            criteriaSnapshot = writeJson(Map.of(
                    "skipCriteriaCheck", true,
                    "criteriaVersion", LocalDateTime.now(clock).toString()));
        } else {
            EligibilityResult result = eligibilityService.evaluate(grantKind, scopeKind, scopeId, betaPhase);
            if (!result.eligible()) {
                // 実測値/閾値を details に含める（AC-03・02 §4.1）。
                throw new BusinessException(
                        BetaPerkErrorCode.ACTIVITY_CRITERIA_NOT_MET, toFieldErrors(result));
            }
            criteriaSnapshot = buildCriteriaSnapshot(result);
        }

        // 付与時アクティブ人数スナップショット（TEAM_ORG のみ・AC-05）。
        Integer activeMemberCountSnapshot = grantKind == GrantKind.TEAM_ORG
                ? scopeMemberCountService.countActiveMembers(scopeKind, scopeId)
                : null;

        // 付与機能＝付与時点の FULL 構成スナップショット。
        List<String> grantedFeatureKeys = resolveFullPlanFeatureKeys();

        LocalDateTime now = LocalDateTime.now(clock);
        BetaGrantEntity grant = BetaGrantEntity.builder()
                .grantKind(grantKind)
                .betaPhase(betaPhase)
                .scopeKind(scopeKind)
                .scopeId(scopeId)
                .organizationId(organizationId)
                .criteriaSnapshot(criteriaSnapshot)
                .activeMemberCountSnapshot(activeMemberCountSnapshot)
                .grantedFeatureKeys(writeJson(grantedFeatureKeys))
                .transferable(false)
                .reviewFlag(false)
                .grantedAt(now)
                .grantedBy(grantedBy)
                .build();
        BetaGrantEntity saved = betaGrantRepository.save(grant);

        // 権利発行（F20.1 の発行サービスを同一 tx で呼ぶ・直接 INSERT しない）。
        // INDIVIDUAL=無期限（valid_until=null＝サービス提供期間中無償）／TEAM_ORG=付与日時+2年（下限・AC-04）。
        LocalDateTime validUntil = grantKind == GrantKind.INDIVIDUAL
                ? null : now.plusYears(TEAM_ORG_VALID_YEARS);
        entitlementIssuanceService.issue(scopeKind, scopeId, organizationId, grantedFeatureKeys,
                EntitlementSourceKind.BETA_GRANT, saved.getId(), validUntil);

        // 称号バッジ（個人のみ・§5）。授与失敗は付与本体をロールバックしない（AC-I3・非致命）。
        if (grantKind == GrantKind.INDIVIDUAL) {
            try {
                betaTesterBadgeAwardService.awardBetaTesterBadge(scopeId, betaPhase);
            } catch (RuntimeException ex) {
                log.warn("ベータ称号バッジ授与に失敗（付与本体は継続）scopeId={}, betaPhase={}", scopeId, betaPhase, ex);
            }
        }

        // 本人通知（個人特典・02 §3）。TEAM_ORG のメンバー/管理者へのファンアウトは隊2/バッチの責務。
        notifyGrantSubject(grant, validUntil);

        // 判定キャッシュ evict（AFTER_COMMIT）。
        evictAfterCommit(scopeKind, scopeId, grantedFeatureKeys);
        return saved;
    }

    // ============================================================
    // 取消
    // ============================================================

    /**
     * ベータ特典を取消す（設計書 01 §3・02 §4.2・AC-09）。付与メタ終端化＋由来 entitlements 全 revoke＋evict＋通知。
     *
     * @param grantId        取消対象
     * @param reason         取消事由（{@code WITHDRAWAL} はシステム専用・隊2 が API で 400 拒否）
     * @param operatorUserId 取消操作者（システム取消は null）
     * @param tenantOrgId    テナント境界（非 null なら organization_id 一致を要求・他テナントは 404 秘匿）。
     *                       プラットフォームシスアドは null で全 grant を対象にできる
     */
    @Transactional
    public void revoke(UUID grantId, BetaRevokeReason reason, Long operatorUserId, Long tenantOrgId) {
        if (reason == null) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        BetaGrantEntity grant = loadGrantInTenant(grantId, tenantOrgId);
        try {
            grant.revoke(reason, operatorUserId); // 二重取消は ISE。
        } catch (IllegalStateException ex) {
            throw new BusinessException(BetaPerkErrorCode.GRANT_ALREADY_REVOKED, ex);
        }
        betaGrantRepository.save(grant);

        List<String> revokedKeys = revokeEntitlementsOfGrant(grantId, operatorUserId);
        notifyGrantSubjectSimple(grant, NotificationType.BETA_PERK_REVOKED,
                "notification.beta_perk.revoked.title", "notification.beta_perk.revoked.body");
        evictAfterCommit(grant.getScopeKind(), grant.getScopeId(), revokedKeys);
    }

    // ============================================================
    // 延長（TEAM_ORG のみ・append-only）
    // ============================================================

    /**
     * TEAM_ORG 特典の期間を延長する（設計書 01 §3・02 §4.3・AC-14・AC-P4）。
     *
     * <p><b>append-only</b>: 既存 entitlements の最大 {@code valid_until} を起点に、新 {@code valid_until}=
     * 起点+月数の<b>新しい entitlement 行</b>を発行する（既存行は UPDATE しない）。
     * {@link EntitlementIssuanceService#issue} は {@code valid_from} を発行時刻に固定するため、新行は
     * {@code [now, 起点+月数)} の半開区間となる（既存行と重畳するが isEntitled は「有効な行が 1 つでもあれば true」
     * ゆえ効果は「起点+月数まで延長」で設計意図と一致・骨格の issue シグネチャは変更しない方針）。</p>
     */
    @Transactional
    public void extend(UUID grantId, int extensionMonths, Long tenantOrgId) {
        BetaGrantEntity grant = loadGrantInTenant(grantId, tenantOrgId);
        if (grant.isIndividual()) {
            throw new BusinessException(BetaPerkErrorCode.EXTEND_NOT_APPLICABLE); // 個人は無期限（AC・422）。
        }
        if (grant.isRevoked()) {
            throw new BusinessException(BetaPerkErrorCode.GRANT_ALREADY_REVOKED); // 取消済み（409）。
        }
        if (extensionMonths < EXTEND_MONTHS_MIN || extensionMonths > EXTEND_MONTHS_MAX) {
            throw new BusinessException(CommonErrorCode.COMMON_001); // 範囲外（400・隊2 DTO が一次ゲート）。
        }
        // ドメイン不変条件の再ガード（updated_at 前進含む）。
        grant.extend(extensionMonths);
        betaGrantRepository.save(grant);

        // 現行 entitlements の最大 valid_until を起点に、新行を発行（append-only）。
        List<EntitlementEntity> current = entitlementRepository
                .findBySourceKindAndSourceRefIdAndRevokedAtIsNull(EntitlementSourceKind.BETA_GRANT, grantId);
        LocalDateTime anchor = current.stream()
                .map(EntitlementEntity::getValidUntil)
                .filter(v -> v != null)
                .max(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now(clock)); // 万一全 null（無期限）なら now を起点にする防御。
        LocalDateTime newValidUntil = anchor.plusMonths(extensionMonths);
        Set<String> featureKeys = new LinkedHashSet<>();
        for (EntitlementEntity e : current) {
            featureKeys.add(e.getFeatureKey());
        }
        entitlementIssuanceService.issue(grant.getScopeKind(), grant.getScopeId(), grant.getOrganizationId(),
                new ArrayList<>(featureKeys), EntitlementSourceKind.BETA_GRANT, grantId, newValidUntil);

        notifyGrantSubjectSimple(grant, NotificationType.BETA_PERK_EXTENDED,
                "notification.beta_perk.extended.title", "notification.beta_perk.extended.body");
        evictAfterCommit(grant.getScopeKind(), grant.getScopeId(), featureKeys);
    }

    // ============================================================
    // 退会一括取消（システム・AccountPurgedEvent 起点）
    // ============================================================

    /**
     * 指定ユーザーの有効な INDIVIDUAL 特典を全取消する（設計書 02 §5.1・AC-19・退会確定 purge）。
     *
     * <p>{@code @TransactionalEventListener(AFTER_COMMIT)}（{@link BetaPerkPurgeEventListener}）から呼ばれるため
     * {@code REQUIRES_NEW}（完了済み tx への参加は silent no-op になる・memory
     * {@code feedback_transactional_event_listener_requires_new}）。退会確定ゆえ通知はしない（本人不在）。</p>
     *
     * @param userId 退会確定ユーザー
     * @param reason 取消事由（通常 {@link BetaRevokeReason#WITHDRAWAL}）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllForUser(Long userId, BetaRevokeReason reason) {
        if (userId == null || reason == null) {
            return;
        }
        List<BetaGrantEntity> grants = betaGrantRepository
                .findByScopeKindAndScopeIdAndRevokedAtIsNull(EntitlementScopeKind.USER, userId);
        Set<String> revokedKeys = new LinkedHashSet<>();
        for (BetaGrantEntity grant : grants) {
            grant.revoke(reason, null); // システム取消（revoked_by=null）。有効行のみ選択済みで二重取消は起きない。
            betaGrantRepository.save(grant);
            revokedKeys.addAll(revokeEntitlementsOfGrant(grant.getId(), null));
        }
        if (!grants.isEmpty()) {
            evictAfterCommit(EntitlementScopeKind.USER, userId, revokedKeys);
        }
    }

    // ============================================================
    // 内部ヘルパ
    // ============================================================

    private void validatePhase(int betaPhase) {
        if (betaPhase < 1 || betaPhase > 4) {
            throw new BusinessException(BetaPerkErrorCode.BETA_PHASE_INVALID);
        }
    }

    /** grant_kind × scope_kind の整合（INDIVIDUAL⇔USER / TEAM_ORG⇔TEAM|ORG・AC-16）。 */
    private void validateKindScope(GrantKind grantKind, EntitlementScopeKind scopeKind) {
        boolean ok = switch (grantKind) {
            case INDIVIDUAL -> scopeKind == EntitlementScopeKind.USER;
            case TEAM_ORG -> scopeKind == EntitlementScopeKind.TEAM || scopeKind == EntitlementScopeKind.ORG;
        };
        if (!ok) {
            throw new BusinessException(BetaPerkErrorCode.GRANT_SCOPE_MISMATCH);
        }
    }

    /** grant を取得しテナント境界を検証する（他テナント/不在は 404 秘匿・IDOR）。 */
    private BetaGrantEntity loadGrantInTenant(UUID grantId, Long tenantOrgId) {
        BetaGrantEntity grant = betaGrantRepository.findById(grantId)
                .orElseThrow(() -> new BusinessException(BetaPerkErrorCode.GRANT_NOT_FOUND));
        if (tenantOrgId != null && !tenantOrgId.equals(grant.getOrganizationId())) {
            throw new BusinessException(BetaPerkErrorCode.GRANT_NOT_FOUND); // 存在秘匿。
        }
        return grant;
    }

    /** 由来 entitlements（未取消）を全 revoke し、対象 feature_key を返す（AC-09）。 */
    private List<String> revokeEntitlementsOfGrant(UUID grantId, Long operatorUserId) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<EntitlementEntity> rows = entitlementRepository
                .findBySourceKindAndSourceRefIdAndRevokedAtIsNull(EntitlementSourceKind.BETA_GRANT, grantId);
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

    /** 付与時点の FULL プラン構成（feature_key 集合）を取得する（設計書 README §1）。 */
    private List<String> resolveFullPlanFeatureKeys() {
        List<String> keys = new ArrayList<>();
        for (PlanFeatureEntity pf : planFeatureRepository.findByPlanKey(FULL_PLAN_KEY)) {
            keys.add(pf.getFeatureKey());
        }
        return keys;
    }

    /** 付与通知（付与時のみ・個人特典）。TEAM_ORG のファンアウトは隊2/バッチの責務ゆえ本体では送らない。 */
    private void notifyGrantSubject(BetaGrantEntity grant, LocalDateTime validUntil) {
        if (!grant.isIndividual()) {
            return;
        }
        String title = resolve("notification.beta_perk.granted.title", null);
        String body = resolve("notification.beta_perk.granted.body.individual", null);
        safeNotify(grant.getScopeId(), NotificationType.BETA_PERK_GRANTED, title, body, grant);
    }

    /** 取消/延長の本人通知（個人特典のみ）。 */
    private void notifyGrantSubjectSimple(
            BetaGrantEntity grant, NotificationType type, String titleKey, String bodyKey) {
        if (!grant.isIndividual()) {
            return;
        }
        safeNotify(grant.getScopeId(), type, resolve(titleKey, null), resolve(bodyKey, null), grant);
    }

    /** 通知送信（失敗は非致命・付与本体を殺さない・WARN で可視化）。 */
    private void safeNotify(Long userId, NotificationType type, String title, String body, BetaGrantEntity grant) {
        if (userId == null) {
            return;
        }
        try {
            notificationHelper.notify(userId, type.name(), type.getPriority(), title, body,
                    type.getSourceType(), null,
                    NotificationScopeType.PERSONAL, userId, null, null);
        } catch (RuntimeException ex) {
            log.warn("ベータ特典通知の送信に失敗（本体は継続）type={}, userId={}", type, userId, ex);
        }
    }

    private String resolve(String key, Object[] args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    /** criteria_snapshot（JSON）を評価結果から組み立てる（実測値/閾値/ウィンドウ/版時刻・設計書 01 §1）。 */
    private String buildCriteriaSnapshot(EligibilityResult result) {
        Map<String, Object> snap = new LinkedHashMap<>();
        for (MetricProgress m : result.metrics()) {
            snap.put(m.metricKey(), m.actual());
            snap.put(requiredKey(m.metricKey()), m.required());
        }
        snap.put("evaluationWindowDays", result.evaluationWindowDays());
        snap.put("criteriaVersion", LocalDateTime.now(clock).toString());
        return writeJson(snap);
    }

    /** {@code activeDays}→{@code requiredActiveDays} 等、閾値キー名を組み立てる（設計書 01 §1 例）。 */
    private String requiredKey(String metricKey) {
        return "required" + Character.toUpperCase(metricKey.charAt(0)) + metricKey.substring(1);
    }

    /** 未達時の details（field=metricKey / message="actual=.., required=.."）・AC-03。 */
    private List<ErrorResponse.FieldError> toFieldErrors(EligibilityResult result) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        for (MetricProgress m : result.metrics()) {
            errors.add(new ErrorResponse.FieldError(
                    m.metricKey(), "actual=" + m.actual() + ", required=" + m.required()));
        }
        return errors;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            // criteria_snapshot / granted_feature_keys は NOT NULL。生成不能は設計不整合ゆえ握り潰さず露呈させる。
            throw new IllegalStateException("JSON シリアライズに失敗しました", ex);
        }
    }

    /** 判定キャッシュ evict を AFTER_COMMIT に遅延する（BillingContractService と同型）。 */
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
}
