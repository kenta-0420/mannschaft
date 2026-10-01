package com.mannschaft.app.team.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.fanout.FanoutMessageKind;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionKind;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionReason;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgAffiliationRestrictionRepository;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 組織からの加盟招待・承諾・辞退・取消の書き込み（F01.2.1 §6.5）。
 *
 * <p>本クラスのメソッドが<b>トランザクションの入口</b>である。認可（組織 ADMIN／チームの加盟操作者であること）・
 * 招待先チームの可視性の確認（見えないチームは 404）・入力検証は、トランザクションの外
 * （Controller・{@link TeamOrgInviteService}）で済ませてから呼ぶ。トランザクションの最初の文をロック取得にするためである
 * （{@link TeamOrgAffiliationLockSupport} の「呼び出しの約束」）。</p>
 *
 * <h2>ロックの順序（デッドロックしない理由）</h2>
 * <ul>
 *   <li>招待の作成: 申請（§6.1）と同じく<b>チーム行 → 組織行</b>の固定順（{@link TeamOrgAffiliationLockSupport}）。
 *       同じ (チーム, 組織) への招待と申請が並行しても、後から来た側はロック待ちの後に先行の行を見て 409 になる。</li>
 *   <li>承諾: <b>チーム行 → 加盟行 → 組織行</b>。チーム行を最初に取るので、同じチームの招待・申請の作成と直列化される。
 *       組織行は状態（アーカイブ）の再確認のために取り、組織のアーカイブ（組織行だけを取る）と直列化される。</li>
 *   <li>辞退・取消: 加盟行だけを {@code FOR UPDATE} で取る（承諾・別の辞退／取消と直列化し、§6.4 の判定表を一意に決める）。</li>
 * </ul>
 * <p>どの経路も「チーム行 → 組織行」の順を崩さず、加盟行だけを取る経路はほかの行を取らないため、循環待ちが生じない。</p>
 *
 * <p>組織・通知・監査はドメインをまたぐため、2-B1 と同じポート（インターフェース）越しに呼ぶ。組織行のロックを
 * チーム行のロックと同じトランザクションで保持する必要があることが、このトランザクションがドメインをまたぐ唯一の理由である
 * （§6.5「招待作成のロック」・§6.9）。</p>
 */
@Service
public class TeamOrgInviteCommandService {

    private final TeamOrgAffiliationLockSupport lockSupport;
    private final TeamRepository teamRepository;
    private final TeamOrgMembershipRepository membershipRepository;
    private final TeamOrgAffiliationRestrictionService restrictionService;
    private final TeamOrgAffiliationRestrictionRepository restrictionRepository;
    private final TeamAffiliationOrganizationPort organizationPort;
    private final TeamAffiliationNotifier notifier;
    private final TeamAffiliationAuditRecorder auditRecorder;
    private final Clock wallClock;
    private final Clock clock;
    private final Duration declineCooldown;
    private final Duration resendCooldown;

    public TeamOrgInviteCommandService(
            TeamOrgAffiliationLockSupport lockSupport,
            TeamRepository teamRepository,
            TeamOrgMembershipRepository membershipRepository,
            TeamOrgAffiliationRestrictionService restrictionService,
            TeamOrgAffiliationRestrictionRepository restrictionRepository,
            TeamAffiliationOrganizationPort organizationPort,
            TeamAffiliationNotifier notifier,
            TeamAffiliationAuditRecorder auditRecorder,
            @Qualifier("wallClock") Clock wallClock,
            Clock clock,
            @Value("${mannschaft.affiliation.reject-cooldown-days:30}") long declineCooldownDays,
            @Value("${mannschaft.affiliation.resend-cooldown-hours:24}") long resendCooldownHours) {
        this.lockSupport = lockSupport;
        this.teamRepository = teamRepository;
        this.membershipRepository = membershipRepository;
        this.restrictionService = restrictionService;
        this.restrictionRepository = restrictionRepository;
        this.organizationPort = organizationPort;
        this.notifier = notifier;
        this.auditRecorder = auditRecorder;
        this.wallClock = wallClock;
        this.clock = clock;
        this.declineCooldown = Duration.ofDays(declineCooldownDays);
        this.resendCooldown = Duration.ofHours(resendCooldownHours);
    }

    /**
     * チームを加盟に招待する（§6.5）。作成した加盟（PENDING / ORG_INVITE）の確定値を返す。
     *
     * <p>チーム行 → 組織行の順にロックを取り、ロックの内側で状態の再確認・グループの検証・制限・既存の加盟を確かめて
     * INSERT する。ロックは INSERT とコミットまで保持する。</p>
     *
     * @param teamId         招待するチーム（操作者から見えることを確認済み）
     * @param organizationId 招待する組織（操作者が組織 ADMIN であることを確認済み）
     * @param operatorUserId 操作者
     * @param groupId        加盟後に所属させるグループ（任意）
     * @param message        添え書き（正規化・長さ検証済み。任意）
     */
    @Transactional
    public CreatedInvite invite(Long teamId, Long organizationId, Long operatorUserId, UUID groupId,
                                String message) {
        // 1. 最初の文でロックを取り、ロック取得後に状態を再確認する（削除 404・アーカイブ）
        TeamOrgAffiliationLockSupport.LockedScope scope =
                lockSupport.lockTeamThenOrganization(teamId, organizationId);
        TeamEntity team = scope.team();
        TeamAffiliationOrganizationPort.OrganizationAffiliationState organization = scope.organization();

        // 2. グループの検証（グループ機能 off で指定 → 400 TEAM_072。黙って捨てない。on は生存グループに限る）
        validateGroup(organization, organizationId, groupId);

        // 3. 制限（ORG_INVITE 方向）→ 403 TEAM_068（冷却中かブロック中かは区別しない）
        restrictionService.assertNotRestricted(organizationId, teamId, TeamOrgAffiliationDirection.ORG_INVITE);

        // 4. 同じ (team, org) の行: ACTIVE → TEAM_065 / PENDING（招待・申請どちらでも）→ TEAM_066
        Optional<TeamOrgMembershipEntity> existing =
                membershipRepository.findByTeamIdAndOrganizationId(teamId, organizationId);
        if (existing.isPresent()) {
            throw new BusinessException(existing.get().getStatus() == TeamOrgMembershipEntity.Status.ACTIVE
                    ? TeamErrorCode.TEAM_065 : TeamErrorCode.TEAM_066);
        }

        // 5. INSERT。ロックの内側なので一意制約違反は通常起きないが、起きたら並行した招待・申請として 409 TEAM_066
        TeamOrgMembershipEntity saved;
        try {
            saved = membershipRepository.saveAndFlush(TeamOrgMembershipEntity.builder()
                    .teamId(teamId)
                    .organizationId(organizationId)
                    .status(TeamOrgMembershipEntity.Status.PENDING)
                    .direction(TeamOrgAffiliationDirection.ORG_INVITE)
                    .groupId(groupId)
                    .message(message)
                    .invitedBy(operatorUserId)
                    .invitedAt(LocalDateTime.now(wallClock))
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(TeamErrorCode.TEAM_066, e);
        }

        // 6. 通知（チームの加盟操作者全員。同じトランザクションで enqueue するだけ）と監査
        notifier.enqueue(new TeamAffiliationNotice(
                NotificationType.TEAM_ORG_INVITE_RECEIVED,
                FanoutMessageKind.TEAM_ORG_INVITE_RECEIVED,
                List.of(organization.name(), team.getName()),
                TeamAffiliationNotice.RecipientScope.TEAM_AFFILIATION_OPERATORS,
                teamId,
                organizationId,
                saved.getId(),
                operatorUserId,
                "/teams/" + team.getSlug() + "/affiliations?view=invites"));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("membership_id", saved.getId());
        metadata.put("organization_id", organizationId);
        metadata.put("team_id", teamId);
        metadata.put("group_id", groupId == null ? null : groupId.toString());
        auditRecorder.record(AuditEventType.TEAM_ORG_INVITE_SENT, operatorUserId, teamId, organizationId, metadata);

        return new CreatedInvite(saved.getId(), teamId, organizationId, groupId, message, operatorUserId,
                toInstant(saved.getInvitedAt()));
    }

    /**
     * 招待を承諾する（§6.5「承諾」）。成立した加盟（ACTIVE）の確定値を返す。
     *
     * <p>承諾時にグループは変えられない。招待で指定したグループが削除済み、またはグループ機能が off なら、
     * 未分類（{@code group_id = NULL}）で加盟を成立させる（承諾は拒否しない。AC-D11）。</p>
     *
     * <p>応答は §6.4 の判定表に従う: 行が無い（他チームの ID・存在しない ID・削除済み）→ 404 {@code TEAM_070}、
     * 招待でない行・ACTIVE の行 → 409 {@code TEAM_071}。</p>
     */
    @Transactional
    public AcceptedInvite accept(Long teamId, Long membershipId, Long operatorUserId) {
        // 1. 最初の文でチーム行をロックする（同じチームの招待・申請の作成と直列化する）。削除済みなら行は無いものとして扱う
        TeamEntity team = teamRepository.findByIdForUpdate(teamId)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_070));

        // 2. 加盟行をロックして読み、判定表に従う
        TeamOrgMembershipEntity row = membershipRepository.findByIdAndTeamIdForUpdate(membershipId, teamId)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_070));
        if (row.getStatus() != TeamOrgMembershipEntity.Status.PENDING
                || row.getDirection() != TeamOrgAffiliationDirection.ORG_INVITE) {
            throw new BusinessException(TeamErrorCode.TEAM_071);
        }
        Long organizationId = row.getOrganizationId();

        // 3. 組織行をロックして状態を再確認する（§6.2 step 3 と同じ。アーカイブ済みは既存のアーカイブ拒否）
        TeamAffiliationOrganizationPort.OrganizationAffiliationState organization =
                organizationPort.lockForAffiliation(organizationId);
        if (team.getArchivedAt() != null) {
            throw new BusinessException(TeamErrorCode.TEAM_002);
        }
        if (organization.archived()) {
            throw new BusinessException(OrgErrorCode.ORG_003);
        }

        // 4. 確定グループ: 招待時の指定が、いまも組織の生存グループで、グループ機能が on のときだけ残す
        UUID confirmedGroupId = row.getGroupId() != null && organization.groupsEnabled()
                && organizationPort.isAliveGroupOfOrganization(organizationId, row.getGroupId())
                ? row.getGroupId() : null;

        // 5. 条件付き UPDATE。行ロックを保持しているので必ず1行に当たる。0 件ならロックの前提が崩れているため握り潰さない
        LocalDateTime respondedAt = LocalDateTime.now(wallClock);
        int updated = membershipRepository.acceptPendingInvite(membershipId, teamId, confirmedGroupId,
                operatorUserId, respondedAt, Instant.now(clock));
        if (updated != 1) {
            throw new IllegalStateException("ロック済みの加盟招待を条件付き UPDATE できない: membershipId=" + membershipId);
        }

        // 6. 通知（組織 ADMIN 全員）と監査
        notifier.enqueue(new TeamAffiliationNotice(
                NotificationType.TEAM_ORG_INVITE_ACCEPTED,
                FanoutMessageKind.TEAM_ORG_INVITE_ACCEPTED,
                List.of(team.getName(), organization.name()),
                TeamAffiliationNotice.RecipientScope.ORGANIZATION_ADMINS,
                organizationId,
                organizationId,
                membershipId,
                operatorUserId,
                "/organizations/" + organization.slug() + "/member-teams"));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("via", TeamOrgAffiliationDirection.ORG_INVITE.name());
        metadata.put("membership_id", membershipId);
        metadata.put("organization_id", organizationId);
        metadata.put("team_id", teamId);
        metadata.put("group_id", confirmedGroupId == null ? null : confirmedGroupId.toString());
        auditRecorder.record(AuditEventType.TEAM_ORG_MEMBERSHIP_CREATED,
                operatorUserId, teamId, organizationId, metadata);

        return new AcceptedInvite(membershipId, teamId, organizationId, confirmedGroupId, row.getInvitedBy(),
                toInstant(row.getInvitedAt()), toInstant(respondedAt));
    }

    /**
     * 招待を辞退する（§6.5「招待の拒否」）。行を削除し、ORG_INVITE 方向に30日の冷却（{@code block=true} なら無期限）を記録する。
     * 合成規則を適用した後の、いま有効な制限を返す。
     */
    @Transactional
    public RecordedRestriction decline(Long teamId, Long membershipId, Long operatorUserId, boolean block) {
        // 最初の文で加盟行を FOR UPDATE で読み、承諾・取消と直列化する（先にどちらが来たかで応答が一意に決まる）
        TeamOrgMembershipEntity row = membershipRepository.findByIdAndTeamIdForUpdate(membershipId, teamId)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_070));
        if (row.getStatus() != TeamOrgMembershipEntity.Status.PENDING
                || row.getDirection() != TeamOrgAffiliationDirection.ORG_INVITE) {
            throw new BusinessException(TeamErrorCode.TEAM_071);
        }
        int deleted = membershipRepository.deletePendingInviteByTeam(membershipId, teamId);
        if (deleted != 1) {
            throw new IllegalStateException("ロック済みの加盟招待を条件付き DELETE できない: membershipId=" + membershipId);
        }

        Long organizationId = row.getOrganizationId();
        restrictionService.record(organizationId, teamId, TeamOrgAffiliationDirection.ORG_INVITE,
                TeamOrgAffiliationRestrictionReason.DECLINED,
                block ? TeamOrgAffiliationRestrictionKind.BLOCK : TeamOrgAffiliationRestrictionKind.COOLDOWN,
                declineCooldown, operatorUserId);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("membership_id", membershipId);
        metadata.put("organization_id", organizationId);
        metadata.put("team_id", teamId);
        metadata.put("block", block);
        auditRecorder.record(AuditEventType.TEAM_ORG_INVITE_REJECTED,
                operatorUserId, teamId, organizationId, metadata);

        TeamOrgAffiliationRestrictionEntity restriction = restrictionRepository
                .findByOrganizationIdAndTeamIdAndDirection(organizationId, teamId,
                        TeamOrgAffiliationDirection.ORG_INVITE)
                .orElseThrow(() -> new IllegalStateException(
                        "辞退の直後に制限を読み出せない: organizationId=" + organizationId + " teamId=" + teamId));
        return new RecordedRestriction(restriction.getKind(), restriction.getRestrictedUntil());
    }

    /**
     * 送った招待を取り消す（§6.5「招待の取消」）。行を削除し、ORG_INVITE 方向に24時間の冷却を記録する（通知の連打防止）。
     *
     * <p>応答は §6.4 の判定表を teamSlug で指定する経路に当てはめたもの（AC-G138）: (チーム, 組織) の行が無い → 404
     * {@code TEAM_070}、ACTIVE の行・申請の行 → 409 {@code TEAM_071}。</p>
     */
    @Transactional
    public void cancel(Long teamId, Long organizationId, Long operatorUserId) {
        // 最初の文で (チーム, 組織) の行を FOR UPDATE で読み、承諾・辞退と直列化する
        TeamOrgMembershipEntity row = membershipRepository
                .findByTeamIdAndOrganizationIdForUpdate(teamId, organizationId)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_070));
        if (row.getStatus() != TeamOrgMembershipEntity.Status.PENDING
                || row.getDirection() != TeamOrgAffiliationDirection.ORG_INVITE) {
            throw new BusinessException(TeamErrorCode.TEAM_071);
        }
        int deleted = membershipRepository.deletePendingInviteByOrganization(row.getId(), organizationId);
        if (deleted != 1) {
            throw new IllegalStateException("ロック済みの加盟招待を条件付き DELETE できない: membershipId=" + row.getId());
        }

        restrictionService.record(organizationId, teamId, TeamOrgAffiliationDirection.ORG_INVITE,
                TeamOrgAffiliationRestrictionReason.CANCELLED, TeamOrgAffiliationRestrictionKind.COOLDOWN,
                resendCooldown, operatorUserId);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("membership_id", row.getId());
        metadata.put("organization_id", organizationId);
        metadata.put("team_id", teamId);
        auditRecorder.record(AuditEventType.TEAM_ORG_INVITE_CANCELLED,
                operatorUserId, teamId, organizationId, metadata);
    }

    /** 壁時計（JST。§4.6）で保存されている日時を、起きた瞬間へ戻す。 */
    private static Instant toInstant(LocalDateTime wallClockValue) {
        return wallClockValue == null
                ? null
                : wallClockValue.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant();
    }

    /** グループの検証（§6.5「招待の body」）。グループ機能 off で指定 → 400、on は組織の生存グループに限る。 */
    private void validateGroup(TeamAffiliationOrganizationPort.OrganizationAffiliationState organization,
                               Long organizationId, UUID groupId) {
        if (groupId == null) {
            return;
        }
        if (!organization.groupsEnabled()
                || !organizationPort.isAliveGroupOfOrganization(organizationId, groupId)) {
            // 他組織のグループ・削除済みグループ・グループ機能 off は、区別しない同じ 400 TEAM_072
            throw new BusinessException(TeamErrorCode.TEAM_072);
        }
    }

    /**
     * 作成した招待（コミット後に行を取り直さず、トランザクション内で確定した値だけで応答を組み立てるための値）。
     */
    public record CreatedInvite(Long id, Long teamId, Long organizationId, UUID groupId, String message,
                                Long invitedBy, Instant invitedAt) {
    }

    /** 承諾で成立した加盟の確定値（日時は起きた瞬間）。 */
    public record AcceptedInvite(Long id, Long teamId, Long organizationId, UUID groupId, Long invitedBy,
                                 Instant invitedAt, Instant respondedAt) {
    }

    /** 合成後の、いま有効な制限。 */
    public record RecordedRestriction(TeamOrgAffiliationRestrictionKind kind, Instant restrictedUntil) {
    }
}
