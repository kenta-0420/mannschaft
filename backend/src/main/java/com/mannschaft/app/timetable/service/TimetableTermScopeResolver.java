package com.mannschaft.app.timetable.service;

import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 時間割が参照できる学期の範囲を決めるために、チームの ACTIVE な所属組織 ID を解決するクラス
 * （CMP-261105-1507）。
 *
 * <p><strong>トランザクションを持たない</strong>（{@code @Transactional} を付けてはならない）。所属組織の解決は
 * team ドメインの {@code TeamOrgMembershipRepository} を読むため、{@link TimetableService} の
 * {@code @Transactional} の中から呼ぶと番人 D-3T（トランザクション内の越境リポジトリ到達）に違反する
 * （CLAUDE.md DB 設計の原則 #5）。Controller が先に本クラスで ID の集合を解決し、結果を引数として
 * トランザクション本体へ渡す。</p>
 *
 * <p>{@code TimetableTermService#getTeamTerms} と同じ規則（ACTIVE な所属組織の学期だけを継承できる）を、
 * 時間割の作成・複製と {@code termName} の解決にも適用するための入力を作る。</p>
 */
@Component
@RequiredArgsConstructor
public class TimetableTermScopeResolver {

    private final TeamOrgMembershipQueryService teamOrgMembershipQueryService;

    /** チームの ACTIVE な所属組織 ID の集合。所属が無ければ空。 */
    public Set<Long> usableOrganizationIds(Long teamId) {
        return new HashSet<>(teamOrgMembershipQueryService.findActiveOrganizationIds(teamId));
    }
}
