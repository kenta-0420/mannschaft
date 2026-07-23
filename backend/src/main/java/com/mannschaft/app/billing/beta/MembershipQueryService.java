package com.mannschaft.app.billing.beta;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * F20.3 ベータ特典: 在籍日数（{@code membershipTenureDays}）計測サービス（設計書 02 §2・README §2）。
 *
 * <p><b>両建て定義</b>:</p>
 * <ul>
 *   <li><b>INDIVIDUAL（USER）</b>: 本人の最古有効所属 {@code memberships.joined_at}（{@code left_at IS NULL}）
 *       からの経過日数。</li>
 *   <li><b>TEAM_ORG（TEAM/ORG）</b>: スコープ自体の作成日（{@code teams.created_at} /
 *       {@code organizations.created_at}）からの経過日数。</li>
 * </ul>
 *
 * <p><b>クロスドメイン方針（{@code ScopeMemberCountService} と同型）</b>: membership / team / organization の
 * リポジトリを read-only 参照するが、<b>{@code @Transactional} を付けず</b>（D-3 回避）、各リポジトリは
 * <b>scalar（{@code LocalDateTime}）を返す</b>クエリのみ用いる（他ドメイン Entity を import しない・D-1 回避）。</p>
 *
 * <p><b>名前衝突注意</b>: team ドメインに {@code TeamOrgMembershipQueryService} は在るが本クラスとは別物。
 * 本クラスは billing.beta 専用（ベータ特典の在籍日数のみ）。</p>
 */
@Service
@RequiredArgsConstructor
public class MembershipQueryService {

    private final MembershipRepository membershipRepository;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;

    /**
     * 在籍日数を返す（設計書 02 §2）。起点日時が解決できない場合（所属無し・スコープ不在）は 0 を返す。
     *
     * @param scopeKind USER / TEAM / ORG
     * @param scopeId   users.id / teams.id / organizations.id
     * @param now       評価時刻（Clock 固定でテスト可能・呼び出し側が渡す）
     * @return 在籍日数（0 以上）
     */
    public long tenureDays(EntitlementScopeKind scopeKind, Long scopeId, LocalDateTime now) {
        if (scopeKind == null || scopeId == null || now == null) {
            return 0L;
        }
        Optional<LocalDateTime> anchor = switch (scopeKind) {
            case USER -> membershipRepository.findEarliestActiveJoinedAt(scopeId);
            case TEAM -> teamRepository.findCreatedAtById(scopeId);
            case ORG -> organizationRepository.findCreatedAtById(scopeId);
        };
        return anchor
                .filter(start -> !start.isAfter(now))
                .map(start -> Duration.between(start, now).toDays())
                .orElse(0L);
    }
}
