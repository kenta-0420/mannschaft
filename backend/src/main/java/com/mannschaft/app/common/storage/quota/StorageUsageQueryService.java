package com.mannschaft.app.common.storage.quota;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.storage.quota.dto.StorageScopeUsage;
import com.mannschaft.app.common.storage.quota.entity.StoragePlanEntity;
import com.mannschaft.app.common.storage.quota.entity.StorageSubscriptionEntity;
import com.mannschaft.app.common.storage.quota.repository.StoragePlanRepository;
import com.mannschaft.app.common.storage.quota.repository.StorageSubscriptionRepository;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * F13 ストレージ使用量の参照専用クエリサービス（{@code GET /api/v1/me/storage/usage} の本体）。
 *
 * <p><b>read-only・副作用なし。</b> 使用量表示のために subscription 行を新規作成してはならない
 * （{@link StorageQuotaService#checkQuota} 系が呼ぶ ensureSubscription は使わない）。未作成スコープは
 * 使用量 0・件数 0 とし、その scope_level の<b>デフォルトプラン</b>の included/max を適用して返す。</p>
 *
 * <p><b>所属の列挙はサーバー側で行う。</b> クライアントから {@code scopeId} を受け取らず、本人の
 * 所属チーム/組織を {@link AccessControlService#findAffiliatedScopeIds}（user_roles ∪ memberships）で
 * 列挙する。これにより恣意的 ID 注入による他スコープ使用量の参照（漏洩）を構造的に排除する。
 * 列挙・問い合わせは {@code PERSONAL} / {@code TEAM} / {@code ORGANIZATION} の 3 種のみ。
 * {@code TOURNAMENT} 等の使用量は主催 {@code ORGANIZATION} の subscription に集約済みのため個別に引かない。</p>
 *
 * <p><b>性能。</b> subscription は scope_type ごとに {@code IN} 句で一括取得し（{@link #buildScopes}）、
 * デフォルトプランは scope_level 3 種をそれぞれ最大 1 回だけ引く（{@link #defaultPlan} のキャッシュ）。
 * subscription が参照する plan も {@code findAllById} でまとめて引く（N+1 を作らない）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StorageUsageQueryService {

    /** PERSONAL スコープの表示名（API データ値。FE 側の i18n はキー解決で別途行う）。 */
    private static final String PERSONAL_SCOPE_NAME = "個人";

    private final AccessControlService accessControlService;
    private final StorageSubscriptionRepository subscriptionRepository;
    private final StoragePlanRepository planRepository;
    private final TeamService teamService;
    private final OrganizationService organizationService;

    /**
     * 本人が所属する全スコープ（個人 + 所属チーム + 所属組織）のストレージ使用量を返す。
     *
     * @param userId 認証済みユーザー ID
     * @return スコープ別使用量のリスト（PERSONAL 先頭、続いて所属チーム・所属組織）
     */
    public List<StorageScopeUsage> getMyStorageUsage(Long userId) {
        // scope_level ごとのデフォルトプランは最大 1 回だけ引く（IN クエリ間で共有）。
        Map<String, StoragePlanEntity> defaultPlanCache = new HashMap<>();

        List<StorageScopeUsage> result = new ArrayList<>();

        // --- PERSONAL（本人 1 件・固定） ---
        result.addAll(buildScopes(
                StorageScopeType.PERSONAL.name(),
                Set.of(userId),
                Map.of(userId, PERSONAL_SCOPE_NAME),
                Map.of(),   // PERSONAL は slug 無し
                defaultPlanCache));

        // --- TEAM（所属チーム） ---
        Set<Long> teamIds = accessControlService.findAffiliatedScopeIds(userId, "TEAM");
        if (!teamIds.isEmpty()) {
            result.addAll(buildScopes(
                    StorageScopeType.TEAM.name(),
                    teamIds,
                    teamService.getNamesByIds(teamIds),
                    teamService.getSlugsByIds(teamIds),
                    defaultPlanCache));
        }

        // --- ORGANIZATION（所属組織） ---
        Set<Long> orgIds = accessControlService.findAffiliatedScopeIds(userId, "ORGANIZATION");
        if (!orgIds.isEmpty()) {
            result.addAll(buildScopes(
                    StorageScopeType.ORGANIZATION.name(),
                    orgIds,
                    organizationService.getNamesByIds(orgIds),
                    organizationService.getSlugsByIds(orgIds),
                    defaultPlanCache));
        }

        return result;
    }

    /**
     * 同一 scope_type の複数 scopeId について使用量 DTO を組み立てる。
     *
     * <p>subscription は {@code IN} 句で一括取得し、未作成スコープは 0 + デフォルトプランで補う。
     * subscription が参照するプランは {@code findAllById} で一括解決する。名前が解決できないスコープ
     * （論理削除済み等）は出力に含めない（所属一覧 API と同じ「不在はスキップ」挙動）。</p>
     *
     * @param scopeType        scope_type（PERSONAL / TEAM / ORGANIZATION）
     * @param scopeIds         対象 scopeId 群（空でないこと。空は呼び出し側でスキップ）
     * @param names            scopeId → 表示名
     * @param slugs            scopeId → slug（PERSONAL は空）
     * @param defaultPlanCache scope_type → デフォルトプランのキャッシュ
     */
    private List<StorageScopeUsage> buildScopes(String scopeType,
                                                Collection<Long> scopeIds,
                                                Map<Long, String> names,
                                                Map<Long, String> slugs,
                                                Map<String, StoragePlanEntity> defaultPlanCache) {
        List<StorageSubscriptionEntity> subs =
                subscriptionRepository.findByScopeTypeAndScopeIdIn(scopeType, scopeIds);
        Map<Long, StorageSubscriptionEntity> subByScopeId = subs.stream()
                .collect(Collectors.toMap(StorageSubscriptionEntity::getScopeId, Function.identity()));

        // subscription が参照する plan を一括解決（N+1 回避）。
        Set<Long> planIds = subs.stream()
                .map(StorageSubscriptionEntity::getPlanId)
                .collect(Collectors.toSet());
        Map<Long, StoragePlanEntity> planById = planIds.isEmpty()
                ? Map.of()
                : planRepository.findAllById(planIds).stream()
                        .collect(Collectors.toMap(StoragePlanEntity::getId, Function.identity()));

        List<StorageScopeUsage> out = new ArrayList<>();
        for (Long scopeId : scopeIds) {
            String scopeName = names.get(scopeId);
            // 名前が解決できないスコープ（論理削除済み等）は出力しない。
            // PERSONAL は固定名を渡しているため常に解決される。
            if (scopeName == null) {
                continue;
            }

            StorageSubscriptionEntity sub = subByScopeId.get(scopeId);
            long usedBytes = sub != null && sub.getUsedBytes() != null ? sub.getUsedBytes() : 0L;
            int fileCount = sub != null && sub.getFileCount() != null ? sub.getFileCount() : 0;

            // プラン: subscription があればその plan、無ければ（または plan 不在なら）デフォルトプラン。
            StoragePlanEntity plan = sub != null ? planById.get(sub.getPlanId()) : null;
            if (plan == null) {
                plan = defaultPlan(scopeType, defaultPlanCache);
            }

            long includedBytes = plan != null && plan.getIncludedBytes() != null
                    ? plan.getIncludedBytes() : 0L;
            Long maxBytes = plan != null ? plan.getMaxBytes() : null;
            // included が 0 のときはゼロ除算を避けて 0% とする。
            double usagePercent = includedBytes > 0
                    ? (double) usedBytes * 100.0 / (double) includedBytes
                    : 0.0;

            out.add(new StorageScopeUsage(
                    scopeType, scopeId, scopeName, slugs.get(scopeId),
                    usedBytes, fileCount, includedBytes, maxBytes, usagePercent));
        }
        return out;
    }

    /** scope_level のデフォルトプランを取得する（キャッシュ。未設定なら null）。 */
    private StoragePlanEntity defaultPlan(String scopeLevel, Map<String, StoragePlanEntity> cache) {
        if (cache.containsKey(scopeLevel)) {
            return cache.get(scopeLevel);
        }
        StoragePlanEntity plan = planRepository
                .findFirstByScopeLevelAndIsDefaultTrueAndDeletedAtIsNull(scopeLevel)
                .orElse(null);
        cache.put(scopeLevel, plan);
        return plan;
    }
}
