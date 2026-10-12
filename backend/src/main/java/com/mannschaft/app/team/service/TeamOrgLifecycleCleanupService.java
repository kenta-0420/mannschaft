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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 組織・チームのアーカイブ／論理削除に伴う加盟の片付け（F01.2.1 §4.5・§6.9・2-D）。
 *
 * <h2>状態に基づく冪等な片付け</h2>
 * <p>片付けは「何が起きたか（イベント）」ではなく「いまどういう状態か」で決める。</p>
 * <ul>
 *   <li>{@link #cleanUpTeam}: チーム行を<b>論理削除済みを含めて {@code FOR UPDATE} で取り</b>（同じ team ドメイン）、ロックの下で
 *       現在の状態を確かめてから加盟行を消す。ロックはアーカイブ解除・申請・招待の作成（どれも最初にチーム行を取る）と直列化する。</li>
 *   <li>{@link #cleanUpOrganization}: 組織行は organization ドメインなのでこの tx ではロックしない。代わりに、消す候補の加盟行
 *       （当該組織の PENDING）を<b>先に {@code FOR UPDATE} でロック</b>し（索引の範囲も含む）、その後で組織の最新のコミット済みの
 *       状態をポート越しに組織ドメインの新しい読み取り tx で確かめる。ロックした行は確かめた時点より前に作られた行だけなので、
 *       組織がまだ閉じていれば、それらは閉鎖より前の申請・招待である（閉じた組織への申請は組織行のロックの下で拒否され、
 *       招待はコミット後の読み直しで取り下げられる。§6.9）。</li>
 *   <li>どちらも、アーカイブも論理削除もされていなければ何もしない（アーカイブ → 解除 → 新しい申請、の後に古い契機で呼ばれても、
 *       解除後の申請を消さない）。</li>
 *   <li>同じ契機で何度呼ばれても結果は同じ（消す行が無ければ 0 件・通知なし）。</li>
 * </ul>
 * <p>同じ片付けを2つの経路から呼ぶ。速い経路はイベント（{@code TeamOrgLifecycleListener}・AFTER_COMMIT）、
 * 回復の経路は定期バッチ（{@code TeamOrgLifecycleCleanupBatch}）で、リスナーの前のプロセス停止や、片付け・outbox の
 * 書き込みの失敗で残った行も、状態から拾い直して片付ける（イベントそのものを永続化しなくても取りこぼさない）。</p>
 *
 * <h2>通知（取消）</h2>
 * <p>消した PENDING ごとに、残った側へ {@code TEAM_ORG_PENDING_CANCELLED_BY_SYSTEM} を、削除と<b>同じトランザクション</b>で
 * team ドメインの outbox に予約する（{@link TeamAffiliationNotifier}・§6.7）。ACTIVE の相手には送らない。
 * <b>相手が知らない PENDING には送らない</b>: 招待（ORG_INVITE）は INSERT のコミット後に受信通知を予約する（2-C の C 案）ので、
 * 受信通知の予約の前（{@code invite_notified_at} が NULL）の招待はチームがまだ知らない。その取消は送らない
 * （OB13a「閉鎖を見つけた招待には outbox を書かない」と同じ結果になる）。申請（TEAM_APPLY）は INSERT と同じ tx で
 * 受信通知を予約するので、コミット済みの申請は常に組織が知っている。</p>
 * <p>論理削除済みの組織も名前を引ける（{@link TeamAffiliationOrganizationPort#findClosureStateIncludingDeleted}）ので、
 * 「アーカイブの直後に論理削除された」組織でも取消の通知を省かない。通知を送らないのは、受信者が居ない
 * （相手のチーム・組織が論理削除済み）か、相手が知らない招待のときだけで、その旨をログに残す。</p>
 *
 * <table>
 *   <caption>契機ごとの片付け</caption>
 *   <tr><th>状態</th><th>入口</th><th>PENDING</th><th>ACTIVE</th><th>制限</th></tr>
 *   <tr><td>組織のアーカイブ</td><td>{@link #cleanUpOrganization}</td><td>削除・チームへ通知</td><td>残す</td><td>残す</td></tr>
 *   <tr><td>組織の論理削除</td><td>同上</td><td>削除・チームへ通知</td><td>触らない（対象外）</td><td>物理削除</td></tr>
 *   <tr><td>チームのアーカイブ（チーム自身の API）</td><td>{@link #cancelPendingOfArchivedTeam}（{@code TeamService#archiveTeam} と同じ tx）</td><td>削除・組織へ通知</td><td>残す</td><td>残す</td></tr>
 *   <tr><td>チームのアーカイブ（後継者不在の自動アーカイブなど他ドメインの tx から）</td><td>{@link #cleanUpTeam}（コミット後）</td><td>削除・組織へ通知</td><td>残す</td><td>残す</td></tr>
 *   <tr><td>チームの論理削除</td><td>{@link #cleanUpTeam}</td><td>削除・組織へ通知</td><td>削除</td><td>物理削除</td></tr>
 * </table>
 *
 * <p><b>ロックの順序</b>: 組織側は加盟行だけ、チーム側は「チーム行 → 加盟行」。申請の作成（チーム行 → 組織行 → INSERT）・
 * 承認（チーム行 → 組織行 → 加盟行）・承諾（チーム行 → 加盟行）と同じ向きなので、循環待ちにならない。組織の状態は
 * ポート越しの別 tx で読むだけで、組織の表をこの tx に持ち込まない（CLAUDE.md 原則5・D-3P）。</p>
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
    // チーム側
    // =====================================================================

    /**
     * チームのアーカイブ（チーム自身の API）: 当該チームの PENDING（申請・招待の両方向）を削除し、相手側の組織 ADMIN へ
     * 取消を予約する。ACTIVE は残す。{@code TeamService#archiveTeam} がチーム行をロックしたトランザクションの中で呼ぶ
     * （コミットの時点で PENDING を残さない。AC-G105・AC-E07①）。
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
     * チームの状態に基づく片付け（イベントと回復バッチの共通入口）。チーム行を論理削除済みを含めて {@code FOR UPDATE} で取り、
     * <ul>
     *   <li>論理削除済み（または行が無い）なら、PENDING・ACTIVE・制限を消し、PENDING の相手（組織 ADMIN）へ取消を予約する</li>
     *   <li>アーカイブ済みなら、PENDING を消して相手へ取消を予約する（ACTIVE・制限は残す）</li>
     *   <li>どちらでもなければ何もしない（アーカイブ解除済み）</li>
     * </ul>
     *
     * @return 削除した加盟行の件数
     */
    @Transactional
    public int cleanUpTeam(Long teamId) {
        Optional<TeamEntity> locked = teamRepository.findByIdIncludingDeletedForUpdate(teamId);
        boolean deleted = locked.map(t -> t.getDeletedAt() != null).orElse(true);
        boolean archived = locked.map(t -> t.getArchivedAt() != null).orElse(false);
        if (!deleted && !archived) {
            return 0;
        }
        String teamName = locked.map(TeamEntity::getName).orElse(null);
        List<TeamOrgMembershipEntity> rows = membershipRepository.findAllByTeamIdForUpdate(teamId);
        List<TeamOrgMembershipEntity> pending = rows.stream()
                .filter(m -> m.getStatus() == TeamOrgMembershipEntity.Status.PENDING).toList();
        List<TeamOrgMembershipEntity> active = deleted
                ? rows.stream().filter(m -> m.getStatus() == TeamOrgMembershipEntity.Status.ACTIVE).toList()
                : List.of();
        int removed = deleteAndNotifyOrganizations(teamName, pending, active);
        int restrictions = deleted ? restrictionRepository.deleteAllByTeamId(teamId) : 0;
        if (removed > 0 || restrictions > 0) {
            log.info("チームの{}に伴う加盟の片付け: teamId={}, memberships={}, restrictions={}",
                    deleted ? "論理削除" : "アーカイブ", teamId, removed, restrictions);
        }
        return removed;
    }

    // =====================================================================
    // 組織側
    // =====================================================================

    /**
     * 組織の状態に基づく片付け（イベントと回復バッチの共通入口）。当該組織の PENDING を {@code FOR UPDATE} でロックしてから、
     * 組織の最新の状態（論理削除済みを含む）をポート越しの別 tx で確かめ、
     * <ul>
     *   <li>論理削除済み（または行が無い）なら、制限を物理削除し、PENDING を消して相手（チームの加盟操作者）へ取消を予約する</li>
     *   <li>アーカイブ済みなら、PENDING を消して相手へ取消を予約する（ACTIVE・制限は残す）</li>
     *   <li>どちらでもなければ何もしない（アーカイブ解除済み。解除後の新しい申請・招待を消さない）</li>
     * </ul>
     * ACTIVE には触らない（組織の論理削除時の ACTIVE の扱いは 2-D の対象外）。
     *
     * @return 削除した PENDING の件数
     */
    @Transactional
    public int cleanUpOrganization(Long organizationId) {
        // 1. 消す候補（当該組織の PENDING）を先に FOR UPDATE でロックする。索引の範囲もロックされるので、
        //    確かめてから消すまでの間に同じ組織への新しい申請・招待が割り込まない
        List<TeamOrgMembershipEntity> pending = membershipRepository.findPendingByOrganizationIdForUpdate(organizationId);
        // 2. ロックの後で、組織の最新のコミット済みの状態を組織ドメインの新しい tx で確かめる。ロックした行はこの時点より
        //    前に作られた行だけなので、まだアーカイブ・論理削除済みなら、それらは閉鎖より前の申請・招待である
        Optional<TeamAffiliationOrganizationPort.OrganizationClosureState> state =
                organizationPort.findClosureStateIncludingDeleted(organizationId);
        if (state.isPresent() && !state.get().closed()) {
            return 0; // アーカイブ解除済み（古い契機）。解除後の申請・招待を消さない
        }
        boolean deleted = state.map(TeamAffiliationOrganizationPort.OrganizationClosureState::deleted).orElse(true);
        int restrictions = deleted ? restrictionRepository.deleteAllByOrganizationId(organizationId) : 0;
        int removed = deleteAndNotifyTeams(organizationId, pending,
                state.map(TeamAffiliationOrganizationPort.OrganizationClosureState::name).orElse(null));
        if (removed > 0 || restrictions > 0) {
            log.info("組織の{}に伴う加盟の片付け: organizationId={}, pending={}, restrictions={}",
                    deleted ? "論理削除" : "アーカイブ", organizationId, removed, restrictions);
        }
        return removed;
    }

    // =====================================================================
    // 内部
    // =====================================================================

    private int deleteAndNotifyTeams(Long organizationId, List<TeamOrgMembershipEntity> pending,
                                     String organizationName) {
        if (pending.isEmpty()) {
            return 0;
        }
        Set<Long> teamIds = pending.stream().map(TeamOrgMembershipEntity::getTeamId).collect(Collectors.toSet());
        // 論理削除済みのチームは返らない（受信者が居ない。そのチームの行はチームの片付けでも消える）
        Map<Long, TeamEntity> teams = teamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(TeamEntity::getId, Function.identity()));
        int deleted = membershipRepository.deleteByIdIn(ids(pending));
        for (TeamOrgMembershipEntity row : pending) {
            boolean invite = row.getDirection() == TeamOrgAffiliationDirection.ORG_INVITE;
            if (invite && row.getInviteNotifiedAt() == null) {
                log.info("招待の受信通知の予約の前に取り消された（チームは招待を知らない）ので取消を通知しない: membershipId={}",
                        row.getId());
                continue;
            }
            TeamEntity team = teams.get(row.getTeamId());
            if (team == null) {
                log.info("取消の通知の受信者（チーム）が論理削除済みなので通知しない: membershipId={}, teamId={}",
                        row.getId(), row.getTeamId());
                continue;
            }
            if (organizationName == null) {
                // 組織の行そのものが無い（物理削除）。文面に使う名前が無いので通知できない
                log.warn("組織の行が無く取消の通知の文面を作れない: membershipId={}, organizationId={}",
                        row.getId(), organizationId);
                continue;
            }
            notifier.enqueue(TeamAffiliationNotice.pendingCancelledByOrganizationClosure(
                    team.getId(), team.getSlug(), organizationId, organizationName, invite, row.getId(), null));
        }
        return deleted;
    }

    private int deleteAndNotifyOrganizations(String teamName, List<TeamOrgMembershipEntity> pending,
                                             List<TeamOrgMembershipEntity> others) {
        if (pending.isEmpty() && others.isEmpty()) {
            return 0;
        }
        // 論理削除済みの組織は返らない（受信者が居ない。その組織の行は組織の片付けでも消える）
        Map<Long, TeamAffiliationOrganizationPort.OrganizationRef> organizations = pending.isEmpty()
                ? Map.of()
                : organizationPort.findOrganizationRefs(
                        pending.stream().map(TeamOrgMembershipEntity::getOrganizationId).collect(Collectors.toSet()));
        List<Long> ids = new ArrayList<>(ids(pending));
        ids.addAll(ids(others));
        int deleted = membershipRepository.deleteByIdIn(ids);
        for (TeamOrgMembershipEntity row : pending) {
            TeamAffiliationOrganizationPort.OrganizationRef organization = organizations.get(row.getOrganizationId());
            if (organization == null) {
                log.info("取消の通知の受信者（組織）が論理削除済みなので通知しない: membershipId={}, organizationId={}",
                        row.getId(), row.getOrganizationId());
                continue;
            }
            if (teamName == null) {
                // チームの行そのものが無い（物理削除）。文面に使う名前が無いので通知できない
                log.warn("チームの行が無く取消の通知の文面を作れない: membershipId={}, teamId={}",
                        row.getId(), row.getTeamId());
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
