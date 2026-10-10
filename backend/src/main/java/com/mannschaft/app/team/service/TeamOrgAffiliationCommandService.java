package com.mannschaft.app.team.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.TeamApplicationGroupMode;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * チーム側の加盟申請・取下げの書き込み（F01.2.1 §6.1・§6.4）。
 *
 * <p>本クラスのメソッドが<b>トランザクションの入口</b>である。認可（加盟操作者であること）と、
 * 組織の可視性の確認（見えない組織は 404）・入力検証は、トランザクションの外
 * （Controller・{@link TeamOrgAffiliationService}）で済ませてから呼ぶ。トランザクションの最初の文を
 * ロック取得にするためである（{@link TeamOrgAffiliationLockSupport} の「呼び出しの約束」）。</p>
 *
 * <p>組織・通知・監査・認可はドメインをまたぐため、すべてポート（インターフェース）越しに呼ぶ。
 * 組織行のロックをチーム行のロックと同じトランザクションで保持する必要があり、これがこのトランザクションが
 * ドメインをまたぐ唯一の理由である（§6.1 step 7・§6.9）。</p>
 */
@Service
public class TeamOrgAffiliationCommandService {

    /** 同時に申請できる組織の上限（PENDING / TEAM_APPLY。§6.1 step 9）。 */
    static final int MAX_PENDING_APPLICATIONS = 10;

    private final TeamOrgAffiliationLockSupport lockSupport;
    private final TeamOrgMembershipRepository membershipRepository;
    private final TeamOrgAffiliationRestrictionService restrictionService;
    private final TeamAffiliationOrganizationPort organizationPort;
    private final TeamAffiliationNotifier notifier;
    private final TeamAffiliationAuditRecorder auditRecorder;
    private final Clock wallClock;

    public TeamOrgAffiliationCommandService(
            TeamOrgAffiliationLockSupport lockSupport,
            TeamOrgMembershipRepository membershipRepository,
            TeamOrgAffiliationRestrictionService restrictionService,
            TeamAffiliationOrganizationPort organizationPort,
            TeamAffiliationNotifier notifier,
            TeamAffiliationAuditRecorder auditRecorder,
            @Qualifier("wallClock") Clock wallClock) {
        this.lockSupport = lockSupport;
        this.membershipRepository = membershipRepository;
        this.restrictionService = restrictionService;
        this.organizationPort = organizationPort;
        this.notifier = notifier;
        this.auditRecorder = auditRecorder;
        this.wallClock = wallClock;
    }

    /**
     * 組織へ加盟を申請する（§6.1 step 7〜13）。作成した加盟（PENDING / TEAM_APPLY）の確定値を返す。
     *
     * <p>チーム行 → 組織行の順にロックを取り、ロックの内側で状態の再確認・制限・既存の加盟・件数上限・
     * グループの検証を行って INSERT する。ロックは INSERT とコミットまで保持する。</p>
     *
     * @param teamId         申請するチーム（認可済み）
     * @param organizationId 申請先の組織（操作者から見えることを確認済み）
     * @param operatorUserId 操作者（加盟操作者であることを確認済み）
     * @param groupId        希望グループ（任意）
     * @param message        添え書き（正規化・長さ検証済み。任意）
     */
    @Transactional
    public AppliedApplication apply(Long teamId, Long organizationId, Long operatorUserId, UUID groupId,
                                    String message) {
        // 1. 最初の文でロックを取り、ロック取得後に状態を再確認する（削除 404・アーカイブ 409）
        TeamOrgAffiliationLockSupport.LockedScope scope =
                lockSupport.lockTeamThenOrganization(teamId, organizationId);
        TeamEntity team = scope.team();
        TeamAffiliationOrganizationPort.OrganizationAffiliationState organization = scope.organization();

        // 2. 受付 off → 403 TEAM_064（組織が見えることは呼び出し前に確認済みなので、受付状態は存在オラクルにならない）
        if (!organization.applicationEnabled()) {
            throw new BusinessException(TeamErrorCode.TEAM_064);
        }

        // 3. 制限（TEAM_APPLY 方向）→ 403 TEAM_068（冷却中かブロック中かは区別しない）
        restrictionService.assertNotRestricted(organizationId, teamId, TeamOrgAffiliationDirection.TEAM_APPLY);

        // 4. 同じ (team, org) の行: ACTIVE → TEAM_065 / PENDING（どちらの向きでも）→ TEAM_066
        Optional<TeamOrgMembershipEntity> existing =
                membershipRepository.findByTeamIdAndOrganizationId(teamId, organizationId);
        if (existing.isPresent()) {
            throw new BusinessException(existing.get().getStatus() == TeamOrgMembershipEntity.Status.ACTIVE
                    ? TeamErrorCode.TEAM_065 : TeamErrorCode.TEAM_066);
        }

        // 5. 同時申請数の上限 → 422 TEAM_069
        long pending = membershipRepository.countByTeamIdAndStatusAndDirection(teamId,
                TeamOrgMembershipEntity.Status.PENDING, TeamOrgAffiliationDirection.TEAM_APPLY);
        if (pending >= MAX_PENDING_APPLICATIONS) {
            throw new BusinessException(TeamErrorCode.TEAM_069);
        }

        // 6. グループ選択の検証（実効モード。グループ機能 off は OFF として扱う）
        validateGroupSelection(organization, organizationId, groupId);

        // 7. INSERT。ロックの内側なので一意制約違反は通常起きないが、起きたら並行申請として 409 TEAM_066 に写像する
        TeamOrgMembershipEntity saved;
        try {
            saved = membershipRepository.saveAndFlush(TeamOrgMembershipEntity.builder()
                    .teamId(teamId)
                    .organizationId(organizationId)
                    .status(TeamOrgMembershipEntity.Status.PENDING)
                    .direction(TeamOrgAffiliationDirection.TEAM_APPLY)
                    .groupId(groupId)
                    .message(message)
                    .invitedBy(operatorUserId)
                    .invitedAt(LocalDateTime.now(wallClock))
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(TeamErrorCode.TEAM_066, e);
        }

        // 8. 通知（組織 ADMIN 全員。同じトランザクションで team の outbox に予約するだけ）と監査
        notifier.enqueue(TeamAffiliationNotice.applicationReceived(
                organizationId, organization.slug(), team.getName(), organization.name(),
                saved.getId(), operatorUserId));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("membership_id", saved.getId());
        metadata.put("organization_id", organizationId);
        metadata.put("team_id", teamId);
        metadata.put("group_id", groupId == null ? null : groupId.toString());
        auditRecorder.record(AuditEventType.TEAM_ORG_APPLICATION_SUBMITTED,
                operatorUserId, teamId, organizationId, metadata);
        return new AppliedApplication(saved.getId(), organizationId, groupId, message, operatorUserId,
                saved.getInvitedAt().atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant());
    }

    /**
     * 申請の結果（コミット後に行を取り直さず、トランザクション内で確定した値だけで応答を組み立てるための値）。
     * 取り直すと、その間に拒否・取下げされて行が消えたとき応答が 500 になる。
     */
    public record AppliedApplication(Long id, Long organizationId, UUID groupId, String message,
                                     Long invitedBy, Instant invitedAt) {
    }

    /**
     * 加盟申請を取り下げる（§6.4）。
     *
     * <p>条件付き DELETE（{@code id, team_id, PENDING, TEAM_APPLY}）で行を消し、24時間の連打防止の制限を記録する。
     * 影響行数 0 のときは、操作時点の行の状態だけで応答を決める判定表に従う
     * （行が無い・他チームの ID・削除済み → 404 {@code TEAM_070}／状態または向きが前提と違う → 409 {@code TEAM_071}）。</p>
     */
    @Transactional
    public void withdraw(Long teamId, Long membershipId, Long operatorUserId) {
        // 最初の文で行を FOR UPDATE ロックして読む。承認（条件付き UPDATE）・別の取下げと直列化され、
        // ロック取得後は最新のコミット済みの状態で判定表に従える（先にどちらが来たかで応答が一意に決まる）
        Optional<TeamOrgMembershipEntity> row = membershipRepository.findByIdAndTeamIdForUpdate(membershipId, teamId);
        if (row.isEmpty()) {
            throw new BusinessException(TeamErrorCode.TEAM_070);
        }
        TeamOrgMembershipEntity target = row.get();
        if (target.getStatus() != TeamOrgMembershipEntity.Status.PENDING
                || target.getDirection() != TeamOrgAffiliationDirection.TEAM_APPLY) {
            throw new BusinessException(TeamErrorCode.TEAM_071);
        }

        // 行ロックを保持しているので、条件付き DELETE は必ず1行に当たる。0 件ならロックの前提が崩れているため握り潰さず失敗させる
        int deleted = membershipRepository.deletePendingApplication(membershipId, teamId);
        if (deleted != 1) {
            throw new IllegalStateException("ロック済みの加盟申請を条件付き DELETE できない: membershipId=" + membershipId);
        }

        Long organizationId = target.getOrganizationId();
        restrictionService.recordWithdrawal(organizationId, teamId, operatorUserId);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("membership_id", membershipId);
        metadata.put("organization_id", organizationId);
        metadata.put("team_id", teamId);
        auditRecorder.record(AuditEventType.TEAM_ORG_APPLICATION_WITHDRAWN,
                operatorUserId, teamId, organizationId, metadata);
    }

    /** グループ選択の検証（§6.1 step 10）。OFF は指定を許さず、OPTIONAL・REQUIRED は指定時に生存グループかを検証する。 */
    private void validateGroupSelection(
            TeamAffiliationOrganizationPort.OrganizationAffiliationState organization,
            Long organizationId, UUID groupId) {
        TeamApplicationGroupMode mode = organization.effectiveGroupMode();
        switch (mode) {
            case OFF -> {
                if (groupId != null) {
                    // 黙って捨てない
                    throw new BusinessException(TeamErrorCode.TEAM_072);
                }
            }
            case OPTIONAL, REQUIRED -> {
                if (groupId == null) {
                    if (mode == TeamApplicationGroupMode.REQUIRED) {
                        throw new BusinessException(TeamErrorCode.TEAM_067);
                    }
                    return;
                }
                // 他組織のグループ・削除済みグループは、不在と同じ 400 TEAM_072
                if (!organizationPort.isAliveGroupOfOrganization(organizationId, groupId)) {
                    throw new BusinessException(TeamErrorCode.TEAM_072);
                }
            }
        }
    }
}
