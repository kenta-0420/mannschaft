package com.mannschaft.app.team.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 加盟チームのグループ割当（単体・一括）の書き込み（F01.2.1 §7.4・§10.8）。
 *
 * <p>本クラスのメソッドが<b>トランザクションの入口</b>である。認可（組織 ADMIN であること）と入力検証は
 * 呼び出し元（Controller・{@link TeamOrgGroupAssignmentService}）が済ませてから呼ぶ。</p>
 *
 * <h2>並行する削除との直列化（ロックの順序）</h2>
 * <p>グループの削除（organization ドメイン）は<b>組織行を {@code FOR UPDATE} でロックしたまま</b>グループを削除済みにし、
 * コミット後に非同期のリスナーが所属行の {@code group_id} を未分類へ戻す。割当が「グループは生きている」と確認した直後に
 * 削除がコミットされ、リスナーの付け替えが済んだ後に割当が書き込むと、削除済みグループを指す {@code group_id} が
 * 次の夜間バッチまで残る。そこで割当も<b>最初の文で同じ組織行のロックを取り</b>、ロックの内側でグループの生存を確認する。
 * こうすると削除と割当は必ずどちらかが先に完了し、後の側は先の結果を見る（割当が先なら、その後のリスナーが付け替える。
 * 削除が先なら、割当は 404 {@code ORG_064}）。組織行のロックは、チーム行のロックを取る経路（申請 §6.1）が
 * 「チーム行 → 組織行」の順で取るのと矛盾せず、本クラスはチーム行をロックしないのでデッドロックの輪も作らない。</p>
 *
 * <p>組織の情報（ロック・グループの生存・機能の on/off）は、すべてポート越しに引く。越境トランザクションになるのは
 * 組織行のロックと加盟行の更新を同じトランザクションで保持する必要があるからである。</p>
 *
 * <h2>一括割当は全部か無しか</h2>
 * <p>1件でも「その組織の ACTIVE 加盟でないチーム」を含めば、何も書かずに 400 {@code ORG_069} を送出する。
 * 検証の後に加盟が消えた場合（離脱・除名との競合）は、更新行数の不一致で検出して例外を送出し、トランザクション全体
 * （更新と監査）を巻き戻す。</p>
 */
@Service
@RequiredArgsConstructor
public class TeamOrgGroupAssignmentCommandService {

    private final TeamAffiliationOrganizationPort organizationPort;
    private final TeamRepository teamRepository;
    private final TeamOrgMembershipRepository membershipRepository;
    private final Clock clock;

    /**
     * 1チームの割当を変更する（groupId=null で未分類）。
     *
     * @param organizationId 組織（認可済み）
     * @param teamSlug       対象チームの slug
     * @param groupId        割り当てるグループ（null で未分類）
     * @return 更新後の加盟（応答の組み立て用の確定値）
     * @throws BusinessException ORG_067（機能 off）・ORG_064（他組織・削除済み・不在のグループ）・
     *                           TEAM_070（その組織の ACTIVE 加盟でないチーム）
     */
    @Transactional
    public AssignedMembership assignOne(Long organizationId, String teamSlug, UUID groupId) {
        // 最初の文で組織行をロックする（クラスのコメント「並行する削除との直列化」）
        requireGroupsEnabledAndGroupAlive(organizationId, groupId);

        TeamEntity team = teamRepository
                .findBySlugAndDeletedAtIsNullAndLifecycleStatus(teamSlug, TeamEntity.LifecycleStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_070));
        TeamOrgMembershipEntity membership = membershipRepository
                .findByTeamIdAndOrganizationId(team.getId(), organizationId)
                .filter(m -> m.getStatus() == TeamOrgMembershipEntity.Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_070));

        UUID previous = membership.getGroupId();
        if (Objects.equals(previous, groupId)) {
            return AssignedMembership.of(membership, groupId, false, previous);
        }
        AssignedMembership result = AssignedMembership.of(membership, groupId, true, previous);
        int updated = update(organizationId, List.of(team.getId()), groupId);
        if (updated != 1) {
            // 検証の後に加盟が消えた（離脱・除名との競合）。存在しない加盟と同じ応答にする
            throw new BusinessException(TeamErrorCode.TEAM_070);
        }
        return result;
    }

    /**
     * 複数チームの割当をまとめて変更する（全部か無しか）。
     *
     * <p>戻り値は「指定した（重複を除いた）チーム数」で、すでに目的のグループにいたチームも含む
     * （リクエストが完了した結果として、そのすべてのチームが目的のグループにいる）。監査ログは実際に変わったチームだけに残す。</p>
     *
     * @param organizationId 組織（認可済み）
     * @param groupId        割り当てるグループ（null で未分類）
     * @param teamSlugs      対象チームの slug（検証済み: 1〜500 件・空白なし）
     * @return 指定したチーム数（重複を除く）
     * @throws BusinessException ORG_067・ORG_064・ORG_069（その組織の ACTIVE 加盟でないチームを含む。該当 slug を同梱）
     */
    @Transactional
    public BulkResult assignBulk(Long organizationId, UUID groupId, List<String> teamSlugs) {
        requireGroupsEnabledAndGroupAlive(organizationId, groupId);

        Set<String> distinct = new LinkedHashSet<>(teamSlugs);
        Map<String, TeamEntity> teamBySlug = teamRepository
                .findBySlugInAndDeletedAtIsNullAndLifecycleStatus(distinct, TeamEntity.LifecycleStatus.ACTIVE)
                .stream().collect(Collectors.toMap(TeamEntity::getSlug, t -> t, (a, b) -> a));
        Map<Long, TeamOrgMembershipEntity> membershipByTeamId = teamBySlug.isEmpty()
                ? Map.of()
                : membershipRepository.findByOrganizationIdAndStatusAndTeamIdIn(
                                organizationId, TeamOrgMembershipEntity.Status.ACTIVE,
                                teamBySlug.values().stream().map(TeamEntity::getId).toList())
                        .stream().collect(Collectors.toMap(TeamOrgMembershipEntity::getTeamId, m -> m, (a, b) -> a));

        List<String> invalid = new ArrayList<>();
        List<TeamOrgMembershipEntity> targets = new ArrayList<>();
        for (String slug : distinct) {
            TeamEntity team = teamBySlug.get(slug);
            TeamOrgMembershipEntity membership = team == null ? null : membershipByTeamId.get(team.getId());
            if (membership == null) {
                invalid.add(slug);
            } else {
                targets.add(membership);
            }
        }
        if (!invalid.isEmpty()) {
            throw invalidTeams(invalid);
        }

        List<TeamOrgMembershipEntity> changed = targets.stream()
                .filter(m -> !Objects.equals(m.getGroupId(), groupId))
                .toList();
        // 監査の from は更新前の値。bulk UPDATE がエンティティを切り離す前に退避する
        Map<Long, UUID> previousByMembershipId = new HashMap<>();
        changed.forEach(m -> previousByMembershipId.put(m.getId(), m.getGroupId()));

        if (!changed.isEmpty()) {
            List<Long> changedTeamIds = changed.stream().map(TeamOrgMembershipEntity::getTeamId).toList();
            int updated = update(organizationId, changedTeamIds, groupId);
            if (updated != changed.size()) {
                // 検証の後に加盟が消えた（離脱・除名との競合）。全体を巻き戻すため例外を送出する
                throw invalidTeams(vanishedSlugs(organizationId, changed, teamBySlug));
            }
        }
        List<GroupChange> changes = changed.stream()
                .map(m -> new GroupChange(m.getId(), m.getTeamId(), organizationId,
                        previousByMembershipId.get(m.getId()), groupId))
                .toList();
        return new BulkResult(distinct.size(), changes);
    }

    // ───────── 内部 ─────────

    /** 組織行を排他ロックし、グループ機能 on とグループの生存（他組織・削除済み・不在は同じ）を確かめる。 */
    private void requireGroupsEnabledAndGroupAlive(Long organizationId, UUID groupId) {
        TeamAffiliationOrganizationPort.OrganizationAffiliationState organization =
                organizationPort.lockForAffiliation(organizationId);
        if (!organization.groupsEnabled()) {
            throw new BusinessException(OrgErrorCode.ORG_067);
        }
        if (groupId != null && !organizationPort.isAliveGroupOfOrganization(organizationId, groupId)) {
            throw new BusinessException(OrgErrorCode.ORG_064);
        }
    }

    private int update(Long organizationId, Collection<Long> teamIds, UUID groupId) {
        Instant now = Instant.now(clock);
        return groupId == null
                ? membershipRepository.clearGroupOfActive(organizationId, teamIds, now)
                : membershipRepository.assignGroupToActive(organizationId, teamIds, groupId, now);
    }

    /** 更新の後に ACTIVE でなくなっていたチームの slug（競合の報告用）。 */
    private List<String> vanishedSlugs(Long organizationId, List<TeamOrgMembershipEntity> changed,
                                       Map<String, TeamEntity> teamBySlug) {
        Set<Long> stillActive = membershipRepository.findByOrganizationIdAndStatusAndTeamIdIn(
                        organizationId, TeamOrgMembershipEntity.Status.ACTIVE,
                        changed.stream().map(TeamOrgMembershipEntity::getTeamId).toList())
                .stream().map(TeamOrgMembershipEntity::getTeamId).collect(Collectors.toSet());
        Set<Long> changedTeamIds = changed.stream().map(TeamOrgMembershipEntity::getTeamId)
                .collect(Collectors.toSet());
        return teamBySlug.entrySet().stream()
                .filter(e -> changedTeamIds.contains(e.getValue().getId()) && !stillActive.contains(e.getValue().getId()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static BusinessException invalidTeams(List<String> invalidSlugs) {
        return new BusinessException(OrgErrorCode.ORG_069, invalidSlugs.stream()
                .map(slug -> new ErrorResponse.FieldError("invalidTeamSlugs", slug))
                .toList());
    }

    /**
     * 更新後の加盟（応答の組み立て用の確定値。Entity を公開しない）。
     *
     * @param id             加盟の ID
     * @param teamId         チーム
     * @param organizationId 組織
     * @param direction      起点
     * @param groupId        更新後のグループ（未分類なら null）
     * @param invitedBy      PENDING を作った人
     * @param invitedAt      PENDING を作った日時（起きた瞬間。壁時計の列を {@code SERVER_ZONE} で解釈した値）
     * @param respondedAt    承諾・承認した日時（同上。無ければ null）
     * @param changed        グループが実際に変わったか（変わらない再送は監査を残さない）
     * @param previousGroupId 変更前の group_id（監査の from）
     */
    public record AssignedMembership(Long id, Long teamId, Long organizationId,
                                     TeamOrgAffiliationDirection direction, UUID groupId, Long invitedBy,
                                     Instant invitedAt, Instant respondedAt,
                                     boolean changed, UUID previousGroupId) {

        static AssignedMembership of(TeamOrgMembershipEntity m, UUID groupId, boolean changed, UUID previous) {
            return new AssignedMembership(m.getId(), m.getTeamId(), m.getOrganizationId(), m.getDirection(),
                    groupId, m.getInvitedBy(), toInstant(m.getInvitedAt()), toInstant(m.getRespondedAt()), changed, previous);
        }

        private static Instant toInstant(LocalDateTime wallClock) {
            return wallClock == null ? null : wallClock.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant();
        }
    }

    /**
     * 実際に変わった割当1件（監査用。コミット後に呼び出し元が記録する）。
     *
     * @param from 変更前の group_id（未分類なら null）
     * @param to   変更後の group_id（未分類なら null）
     */
    public record GroupChange(Long membershipId, Long teamId, Long organizationId, UUID from, UUID to) {
    }

    /**
     * 一括割当の結果。
     *
     * @param specifiedCount 指定したチーム数（重複を除く）
     * @param changes        実際に変わった割当
     */
    public record BulkResult(int specifiedCount, List<GroupChange> changes) {
    }
}
