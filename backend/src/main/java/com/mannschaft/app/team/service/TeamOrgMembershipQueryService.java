package com.mannschaft.app.team.service;

import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * チーム−組織所属ドメインの読み取り公開クエリサービス（他ドメインへ ID 一覧のみを提供する境界）。
 *
 * <p><b>目的</b>: 他ドメイン（例: billing の {@code ScopeClassificationService}）が
 * {@code TeamOrgMembershipEntity} / {@code TeamOrgMembershipRepository} を直接参照せず、
 * 「チームの ACTIVE 所属組織 ID 一覧」という<b>primitive の List</b> だけを Service 経由で得られるようにする
 * （CLAUDE.md ドメイン境界の原則「異なるドメインの Entity を直接参照しない・ID のみ保持」）。</p>
 *
 * <p>本サービスは team ドメイン内で完結する（自ドメインの {@code TeamOrgMembershipRepository} のみ参照）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamOrgMembershipQueryService {

    private final TeamOrgMembershipRepository teamOrgMembershipRepository;

    /**
     * チームが所属する ACTIVE な組織の ID 一覧を返す（無所属なら空リスト）。
     *
     * <p>F20.1 のチーム営利/非営利導出に用いる（無所属チーム＝非営利扱い・README §3.3 / R-2・AC-13）。</p>
     *
     * @param teamId チーム ID
     * @return ACTIVE 所属組織 ID のリスト（無所属は空）
     */
    public List<Long> findActiveOrganizationIds(Long teamId) {
        if (teamId == null) {
            return List.of();
        }
        return teamOrgMembershipRepository
                .findByTeamIdAndStatus(teamId, TeamOrgMembershipEntity.Status.ACTIVE)
                .stream()
                .map(TeamOrgMembershipEntity::getOrganizationId)
                .toList();
    }
}
