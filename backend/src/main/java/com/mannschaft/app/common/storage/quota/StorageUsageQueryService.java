package com.mannschaft.app.common.storage.quota;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.storage.quota.dto.StorageScopeUsage;
import com.mannschaft.app.common.storage.quota.repository.StoragePlanRepository;
import com.mannschaft.app.common.storage.quota.repository.StorageSubscriptionRepository;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F13 ストレージ使用量の参照専用クエリサービス（{@code GET /api/v1/me/storage/usage} の本体）。
 *
 * <p><b>read-only・副作用なし。</b> 使用量表示のために subscription 行を新規作成してはならない
 * （{@code StorageQuotaService.ensureSubscription} は呼ばない）。未作成スコープは使用量 0・件数 0 とし、
 * その scope_level の<b>デフォルトプラン</b>の included/max を適用して返す。</p>
 *
 * <p><b>所属の列挙はサーバー側で行う。</b> クライアントから {@code scopeId} を受け取らず、本人の
 * 所属チーム/組織を {@link AccessControlService#findAffiliatedScopeIds}（user_roles ∪ memberships）で
 * 列挙する。これにより恣意的 ID 注入による他スコープ使用量の参照（漏洩）を構造的に排除する。</p>
 *
 * <p><b>性能。</b> subscription は scope_type ごとに {@code IN} 句で一括取得し、デフォルトプランは
 * scope_level 3 種をそれぞれ 1 回だけ引く（N+1 を作らない）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StorageUsageQueryService {

    private final AccessControlService accessControlService;
    private final StorageSubscriptionRepository subscriptionRepository;
    private final StoragePlanRepository planRepository;
    private final TeamService teamService;
    private final OrganizationService organizationService;

    /**
     * 本人が所属する全スコープ（個人 + 所属チーム + 所属組織）のストレージ使用量を返す。
     *
     * @param userId 認証済みユーザー ID
     * @return スコープ別使用量のリスト（PERSONAL 先頭、続いてチーム・組織）
     */
    public List<StorageScopeUsage> getMyStorageUsage(Long userId) {
        // TODO(/出陣): 実装する（試練 red 確認のため未実装。空リストを返し、試練が assertion で赤になる）。
        return List.of();
    }
}
