package com.mannschaft.app.billing;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F20.1: 契約時アクティブ人数の解決（人数バンド・スナップショット用・設計書 01 §3.4）。
 *
 * <p><b>正準の数え方</b>: 独自 {@code COUNT(*)} は書かず、origin/main 実在の
 * {@link MembershipRepository#countActiveDistinctUsersByScope(ScopeType, Long)} を再利用する
 * （{@code left_at IS NULL} かつ {@code DISTINCT user_id}）。F20.3 も同メソッドで整合させる。</p>
 *
 * <p><b>⚠️ ScopeType 変換の地雷（必読）</b>: billing ドメインの {@link EntitlementScopeKind} は
 * {@code USER / TEAM / ORG} だが、membership ドメインの {@link ScopeType} は
 * {@code ORGANIZATION / TEAM} のみ（USER も ORG も無い）。よって:</p>
 * <ul>
 *   <li>{@code USER} → リポジトリを呼ばず常に {@code 1}（個人スコープは頭数 1）。</li>
 *   <li>{@code ORG} → {@code ScopeType.ORGANIZATION} へ<b>綴りを変換</b>して問い合わせる。
 *       {@code ScopeType.valueOf("ORG")} は {@link IllegalArgumentException} で 500 即死するため厳禁。</li>
 *   <li>{@code TEAM} → {@code ScopeType.TEAM}。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScopeMemberCountService {

    private final MembershipRepository membershipRepository;

    /**
     * 対象スコープの契約時アクティブ人数を返す（設計書 01 §3.4）。
     *
     * @param scopeKind USER / TEAM / ORG
     * @param scopeId   users.id / teams.id / organizations.id
     * @return アクティブ人数（USER は常に 1）
     */
    public int countActiveMembers(EntitlementScopeKind scopeKind, Long scopeId) {
        if (scopeKind == null) {
            throw new IllegalArgumentException("scopeKind must not be null");
        }
        switch (scopeKind) {
            case USER:
                // USER は membership を持たない個人スコープ。リポジトリを呼ばず 1 を返す。
                return 1;
            case TEAM:
                return (int) membershipRepository.countActiveDistinctUsersByScope(ScopeType.TEAM, scopeId);
            case ORG:
                // ★ORG → ORGANIZATION へ綴り変換（valueOf("ORG") は不一致で 500）。
                return (int) membershipRepository.countActiveDistinctUsersByScope(ScopeType.ORGANIZATION, scopeId);
            default:
                throw new IllegalStateException("unhandled scopeKind: " + scopeKind);
        }
    }
}
