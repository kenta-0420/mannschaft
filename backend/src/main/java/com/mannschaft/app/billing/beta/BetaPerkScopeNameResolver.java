package com.mannschaft.app.billing.beta;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

/**
 * F20.3 ベータ特典 Phase3: シスアド審査画面向け 表示名バルク解決ヘルパ（設計書 02 §4.1 追補）。
 *
 * <p><b>越境方針（{@code BetaPerkCandidateService}/{@code LoginActivityQueryService} と同型）</b>:
 * 本クラスは user/team/org という他ドメインの {@code Repository} を read-only 参照するが、
 * <ul>
 *   <li><b>{@code @Transactional} を付けない</b> — クロスドメイン {@code @Transactional} 番人（D-3）に抵触しないため、
 *       呼び出し元（{@link BetaGrantQueryService}）の tx 境界に読み取りだけ参加する。</li>
 *   <li><b>他ドメイン Entity を import しない</b> — 各 Repository の {@code findNameMapByIdIn} は
 *       {@code Map<Long, String>} という scalar のみを返すため、クロスドメイン Entity 参照番人（D-1）にも
 *       抵触しない。</li>
 * </ul></p>
 *
 * <p>scope 種別（USER/TEAM/ORG）ごとに ID を集約して 1 クエリで解決する（呼び出し側が per-grant で
 * 呼ばないことが N+1 回避の前提・{@link BetaGrantQueryService} 側でページ単位に集約する）。</p>
 */
@Component
@RequiredArgsConstructor
public class BetaPerkScopeNameResolver {

    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;

    /**
     * scope 種別に応じた表示名 Map を一括解決する（USER→ユーザー表示名/TEAM→チーム名/ORG→組織名）。
     *
     * @param scopeKind スコープ種別
     * @param scopeIds  対象スコープ ID 集合（null/空なら空 Map を返す＝{@code IN ()} 不正 SQL を防ぐ）
     * @return scopeId → 表示名の Map（解決不能な ID は欠損。呼び出し側は {@code get()} が null なら未解決として扱う）
     */
    public Map<Long, String> resolveScopeNames(EntitlementScopeKind scopeKind, Collection<Long> scopeIds) {
        if (scopeIds == null || scopeIds.isEmpty()) {
            return Map.of();
        }
        return switch (scopeKind) {
            case USER -> userRepository.findNameMapByIdIn(scopeIds);
            case TEAM -> teamRepository.findNameMapByIdIn(scopeIds);
            case ORG -> organizationRepository.findNameMapByIdIn(scopeIds);
        };
    }

    /**
     * 付与操作者（{@code grantedBy}）のユーザー表示名を一括解決する。
     *
     * @param userIds 対象ユーザー ID 集合（null/空なら空 Map を返す）
     * @return userId → 表示名の Map
     */
    public Map<Long, String> resolveUserNames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findNameMapByIdIn(userIds);
    }
}
