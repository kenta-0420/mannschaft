package com.mannschaft.app.team.service;

import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgAffiliationApplicantReadRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * F01.2.1 §10.2・§10.3: 申請受付の設定画面と申請フォームのための、team ドメインの読み取り窓口。
 *
 * <p>他ドメイン（organization）へ Entity を渡さず、表示用の値だけを返す（CLAUDE.md ドメイン境界の原則）。
 * 本サービスは team ドメインの Repository だけを参照する。チームごとの加盟操作権限の判定は呼び出し側
 * （非トランザクションの Facade）が行い、ここには権限判定済みのチーム ID だけを渡す。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TeamAffiliationApplicantQueryService {

    /** 申請フォームの myTeams の上限（§10.3）。 */
    public static final int MY_TEAMS_LIMIT = 100;

    private final TeamRepository teamRepository;
    private final TeamOrgAffiliationApplicantReadRepository readRepository;
    private final MediaUrlResolver mediaUrlResolver;
    private final Clock clock;

    /**
     * 申請フォームの myTeams の各チームと組織との関係（§10.3）。
     */
    public enum ApplicantAffiliationStatus {
        /** 関係なし（申請できる）。 */
        NONE,
        /** このチームから申請中（PENDING / TEAM_APPLY）。 */
        APPLYING,
        /** 組織から招待が届いている（PENDING / ORG_INVITE）。 */
        INVITED,
        /** 加盟済み。 */
        ACTIVE,
        /** 制限中（冷却かブロックかは区別しない）。 */
        UNAVAILABLE
    }

    /**
     * 申請フォームに出すチーム1件。
     *
     * @param iconUrl 表示用に解決済みの URL（null 可）
     */
    public record ApplicantTeam(String slug, String name, String iconUrl, ApplicantAffiliationStatus status) {
    }

    /**
     * 組織宛ての未処理の申請（PENDING / TEAM_APPLY）の件数（§10.2 {@code pendingApplicationCount}）。
     */
    public long countPendingApplications(Long organizationId) {
        return readRepository.countByOrganizationIdAndStatusAndDirection(
                organizationId, TeamOrgMembershipEntity.Status.PENDING, TeamOrgAffiliationDirection.TEAM_APPLY);
    }

    /**
     * 指定チーム群（呼び出し側で加盟操作権限を確認済み）と組織との関係を返す。
     *
     * <p>削除済み・アーカイブ済み・承諾前（PROVISIONED）のチームは含めない。並びはチーム名→ID の昇順で、
     * 最大 {@link #MY_TEAMS_LIMIT} 件。状態の優先は「加盟・申請・招待の行」→「制限」→ NONE
     * （制限は新しい申請を止めるだけなので、既に行があるチームは行の状態を見せる）。</p>
     */
    public List<ApplicantTeam> findApplicantTeams(Collection<Long> teamIds, Long organizationId) {
        List<TeamEntity> teams = applicableTeams(teamIds).stream()
                .sorted(Comparator.comparing(TeamEntity::getName, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TeamEntity::getId))
                .limit(MY_TEAMS_LIMIT)
                .toList();
        if (teams.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = teams.stream().map(TeamEntity::getId).collect(Collectors.toSet());
        Map<Long, TeamOrgMembershipEntity> memberships = readRepository
                .findByOrganizationIdAndTeamIdIn(organizationId, ids).stream()
                .collect(Collectors.toMap(TeamOrgMembershipEntity::getTeamId, Function.identity(), (a, b) -> a));
        Set<Long> restricted = new HashSet<>(
                readRepository.findRestrictedApplyTeamIds(organizationId, ids, clock.instant()));

        return teams.stream()
                .map(team -> new ApplicantTeam(
                        team.getSlug(),
                        team.getName(),
                        mediaUrlResolver.resolve(team.getIconUrl()),
                        statusOf(memberships.get(team.getId()), restricted.contains(team.getId()))))
                .toList();
    }

    /**
     * 指定チーム群に、申請者として使える（{@link #findApplicantTeams} に載る）チームが1つ以上あるか。
     * 申請ボタン判定（§10.3）が申請フォームと<b>同じ条件</b>でチームを数えるための窓口。
     */
    public boolean hasApplicableTeam(Collection<Long> teamIds) {
        return !applicableTeams(teamIds).isEmpty();
    }

    /**
     * 申請者として使えるチーム（削除済み・アーカイブ済み・承諾前 PROVISIONED を除く）。
     * 申請フォームの myTeams と申請ボタン判定の唯一の判定箇所。削除済みは {@code @SQLRestriction} で返らない。
     */
    private List<TeamEntity> applicableTeams(Collection<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return List.of();
        }
        return teamRepository.findAllById(new HashSet<>(teamIds)).stream()
                .filter(team -> team.getArchivedAt() == null)
                .filter(team -> team.getLifecycleStatus() == TeamEntity.LifecycleStatus.ACTIVE)
                .toList();
    }

    private static ApplicantAffiliationStatus statusOf(TeamOrgMembershipEntity membership, boolean restricted) {
        if (membership != null) {
            if (membership.getStatus() == TeamOrgMembershipEntity.Status.ACTIVE) {
                return ApplicantAffiliationStatus.ACTIVE;
            }
            return membership.getDirection() == TeamOrgAffiliationDirection.TEAM_APPLY
                    ? ApplicantAffiliationStatus.APPLYING
                    : ApplicantAffiliationStatus.INVITED;
        }
        return restricted ? ApplicantAffiliationStatus.UNAVAILABLE : ApplicantAffiliationStatus.NONE;
    }
}
