package com.mannschaft.app.team.service;

import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgAffiliationRestrictionRepository;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 組織・チームのアーカイブ／論理削除に伴う加盟の片付け（F01.2.1 §4.5・§6.9・2-D）。
 *
 * <p>どの経路も、片付ける加盟行を {@code SELECT ... FOR UPDATE} で引いてから同じ ID 集合を物理削除し、
 * 消した PENDING ごとに「残った側」へ {@code TEAM_ORG_PENDING_CANCELLED_BY_SYSTEM} を team ドメインの outbox に予約する
 * （{@link TeamAffiliationNotifier}。削除と同じトランザクション。§6.7）。ACTIVE の相手側には取消を通知しない。</p>
 *
 * <table>
 *   <caption>契機ごとの片付け</caption>
 *   <tr><th>契機</th><th>呼び出し元</th><th>PENDING</th><th>ACTIVE</th><th>制限</th></tr>
 *   <tr><td>組織のアーカイブ</td><td>{@code TeamOrgLifecycleListener}（AFTER_COMMIT）</td><td>削除・チームへ通知</td><td>残す</td><td>残す</td></tr>
 *   <tr><td>組織の論理削除</td><td>同上</td><td>削除・チームへ通知</td><td>触らない（対象外）</td><td>物理削除</td></tr>
 *   <tr><td>チームのアーカイブ</td><td>{@code TeamService#archiveTeam}（同じ tx）</td><td>削除・組織へ通知</td><td>残す</td><td>残す</td></tr>
 *   <tr><td>チームの論理削除</td><td>{@code TeamOrgLifecycleListener}（AFTER_COMMIT）</td><td>削除・組織へ通知</td><td>削除</td><td>物理削除</td></tr>
 * </table>
 *
 * <p><b>組織の表示名の読み取り</b>: チーム側の契機では、通知の遷移先に組織の slug が要る。消す PENDING の集合はチーム行
 * （アーカイブ）または加盟行のロックの内側で決まるため、組織の slug・名前はトランザクションの中で
 * {@link TeamAffiliationOrganizationPort#findOrganizationRefs} から読む（読み取りだけの越境。組織行はロックしない。
 * 承認の {@code lockForAffiliation} と同じくポート越しに限る）。組織側の契機は、組織が1つなので呼び出し元が
 * トランザクションの前に読んで渡す。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamOrgLifecycleCleanupService {

    private final TeamRepository teamRepository;
    private final TeamOrgMembershipRepository membershipRepository;
    private final TeamOrgAffiliationRestrictionRepository restrictionRepository;
    private final TeamAffiliationOrganizationPort organizationPort;
    private final TeamAffiliationNotifier notifier;

    // =====================================================================
    // チーム側の契機
    // =====================================================================

    /**
     * チームのアーカイブ: 当該チームの PENDING（申請・招待の両方向）を削除し、相手側の組織 ADMIN へ取消を予約する。
     * ACTIVE は残す。{@code TeamService#archiveTeam} がチーム行をロックしたトランザクションの中で呼ぶ。
     *
     * @return 削除した PENDING の件数
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelPendingOfArchivedTeam(TeamEntity team) {
        List<TeamOrgMembershipEntity> pending = membershipRepository.findAllByTeamIdForUpdate(team.getId()).stream()
                .filter(m -> m.getStatus() == TeamOrgMembershipEntity.Status.PENDING)
                .toList();
        return deleteAndNotifyOrganizations(team.getName(), pending, List.of());
    }

    /**
     * チームの論理削除: 当該チームの PENDING・ACTIVE・制限を削除し、PENDING の相手側（組織 ADMIN）へ取消を予約する。
     *
     * @return 削除した加盟行の件数
     */
    @Transactional
    public int cleanUpDeletedTeam(Long teamId) {
        String teamName = teamRepository.findByIdIncludingDeleted(teamId).map(TeamEntity::getName).orElse(null);
        List<TeamOrgMembershipEntity> rows = membershipRepository.findAllByTeamIdForUpdate(teamId);
        List<TeamOrgMembershipEntity> pending = rows.stream()
                .filter(m -> m.getStatus() == TeamOrgMembershipEntity.Status.PENDING).toList();
        List<TeamOrgMembershipEntity> active = rows.stream()
                .filter(m -> m.getStatus() == TeamOrgMembershipEntity.Status.ACTIVE).toList();
        int deleted = deleteAndNotifyOrganizations(teamName, pending, active);
        int restrictions = restrictionRepository.deleteAllByTeamId(teamId);
        log.info("チーム削除に伴う加盟の片付け: teamId={}, memberships={}, restrictions={}", teamId, deleted, restrictions);
        return deleted;
    }

    // =====================================================================
    // 組織側の契機
    // =====================================================================

    /**
     * 組織のアーカイブ: 当該組織の PENDING（申請・招待の両方向）を削除し、相手側（チームの加盟操作者）へ取消を予約する。
     * ACTIVE と制限は残す。
     *
     * @param organizationName 通知の文面に使う組織名（呼び出し元がトランザクションの前に読んだ値）
     * @return 削除した PENDING の件数
     */
    @Transactional
    public int cancelPendingOfArchivedOrganization(Long organizationId, String organizationName) {
        return deleteAndNotifyTeams(organizationId, organizationName);
    }

    /**
     * 組織の論理削除: 当該組織の制限を物理削除し、PENDING を削除して相手側（チームの加盟操作者）へ取消を予約する。
     * ACTIVE の扱いは 2-D の対象外（触らない）。
     *
     * @param organizationName 通知の文面に使う組織名（呼び出し元がトランザクションの前に読んだ値）
     * @return 削除した PENDING の件数
     */
    @Transactional
    public int cleanUpDeletedOrganization(Long organizationId, String organizationName) {
        int restrictions = restrictionRepository.deleteAllByOrganizationId(organizationId);
        int deleted = deleteAndNotifyTeams(organizationId, organizationName);
        log.info("組織削除に伴う加盟の片付け: organizationId={}, pending={}, restrictions={}",
                organizationId, deleted, restrictions);
        return deleted;
    }

    // =====================================================================
    // 内部
    // =====================================================================

    private int deleteAndNotifyTeams(Long organizationId, String organizationName) {
        List<TeamOrgMembershipEntity> pending = membershipRepository.findPendingByOrganizationIdForUpdate(organizationId);
        if (pending.isEmpty()) {
            return 0;
        }
        Set<Long> teamIds = pending.stream().map(TeamOrgMembershipEntity::getTeamId).collect(Collectors.toSet());
        // 論理削除済みのチームは返らない（そのチームの行はチーム削除の片付けが消す。通知の相手もいない）
        Map<Long, TeamEntity> teams = teamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(TeamEntity::getId, Function.identity()));
        int deleted = membershipRepository.deleteByIdIn(ids(pending));
        for (TeamOrgMembershipEntity row : pending) {
            TeamEntity team = teams.get(row.getTeamId());
            if (team == null || organizationName == null) {
                log.info("取消の通知の相手・文面が無いので通知しない: membershipId={}", row.getId());
                continue;
            }
            notifier.enqueue(TeamAffiliationNotice.pendingCancelledByOrganizationClosure(
                    team.getId(), team.getSlug(), organizationId, organizationName,
                    row.getDirection() == TeamOrgAffiliationDirection.ORG_INVITE, row.getId(), null));
        }
        return deleted;
    }

    private int deleteAndNotifyOrganizations(String teamName, List<TeamOrgMembershipEntity> pending,
                                             List<TeamOrgMembershipEntity> others) {
        if (pending.isEmpty() && others.isEmpty()) {
            return 0;
        }
        Map<Long, TeamAffiliationOrganizationPort.OrganizationRef> organizations = pending.isEmpty()
                ? Map.of()
                : organizationPort.findOrganizationRefs(
                        pending.stream().map(TeamOrgMembershipEntity::getOrganizationId).collect(Collectors.toSet()));
        List<Long> ids = new java.util.ArrayList<>(ids(pending));
        ids.addAll(ids(others));
        int deleted = membershipRepository.deleteByIdIn(ids);
        for (TeamOrgMembershipEntity row : pending) {
            TeamAffiliationOrganizationPort.OrganizationRef organization = organizations.get(row.getOrganizationId());
            if (organization == null || teamName == null) {
                log.info("取消の通知の相手・文面が無いので通知しない: membershipId={}", row.getId());
                continue;
            }
            notifier.enqueue(TeamAffiliationNotice.pendingCancelledByTeamClosure(
                    organization.id(), organization.slug(), teamName,
                    row.getDirection() == TeamOrgAffiliationDirection.ORG_INVITE, row.getId(), null));
        }
        return deleted;
    }

    private static List<Long> ids(Collection<TeamOrgMembershipEntity> rows) {
        return rows.stream().map(TeamOrgMembershipEntity::getId).toList();
    }
}
