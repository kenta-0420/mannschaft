package com.mannschaft.app.team.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionKind;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionReason;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 組織側の加盟申請の承認・拒否の書き込み（F01.2.1 §6.2・§6.3・§6.4）。
 *
 * <p>本クラスのメソッドが<b>トランザクションの入口</b>である。認可（組織 ADMIN であること）・入力検証・
 * 行の所在の確認（{@code (id, organization_id)} で引けなければ 404）は、トランザクションの外
 * （Controller・{@link OrgTeamApplicationReviewService}）で済ませてから呼ぶ。</p>
 *
 * <h2>ロックの順序と競合</h2>
 * <p>トランザクションの最初の文で、<b>チーム行 → 組織行</b>の固定順に {@code PESSIMISTIC_WRITE} を取り
 * （{@link TeamOrgAffiliationLockSupport#lockTeamThenOrganizationForResponse}。申請 §6.1 と同じ順序）、
 * 続けて加盟行を {@code (id, organization_id)} で {@code FOR UPDATE} で読む。取下げ（加盟行だけをロックする）・
 * 別の承認・拒否とは加盟行のロックで直列化され、ロック取得後に読んだ最新の状態だけで §6.4 の判定表に従う
 * （行が無い → 404 {@code TEAM_070}／状態か向きが違う → 409 {@code TEAM_071}）。順序は常に
 * チーム行 → 組織行 → 加盟行であり、チームのアーカイブ（チーム行 → 加盟行）・組織のアーカイブ（組織行のみ）・
 * 取下げ（加盟行のみ）のいずれとも循環待ちにならない。</p>
 *
 * <p>組織・通知・監査はドメインをまたぐため、すべてポート越しに呼ぶ。組織行のロックをチーム行のロックと同じ
 * トランザクションで保持する必要があり、これがこのトランザクションがドメインをまたぐ理由である（§6.9）。</p>
 */
@Service
public class OrgTeamApplicationReviewCommandService {

    /** 承認の通知で、未分類のときに出す既定の文言のキー（文面の引数は翻訳されないため、既定ロケールで解決する）。 */
    static final String UNASSIGNED_GROUP_LABEL_KEY = "notification.teamAffiliation.group.unassigned";

    private final TeamOrgAffiliationLockSupport lockSupport;
    private final TeamOrgMembershipRepository membershipRepository;
    private final TeamOrgAffiliationRestrictionService restrictionService;
    private final TeamAffiliationOrganizationPort organizationPort;
    private final TeamAffiliationNotifier notifier;
    private final TeamAffiliationAuditRecorder auditRecorder;
    private final MessageSource messageSource;
    private final Clock clock;
    private final Duration rejectCooldown;

    public OrgTeamApplicationReviewCommandService(
            TeamOrgAffiliationLockSupport lockSupport,
            TeamOrgMembershipRepository membershipRepository,
            TeamOrgAffiliationRestrictionService restrictionService,
            TeamAffiliationOrganizationPort organizationPort,
            TeamAffiliationNotifier notifier,
            TeamAffiliationAuditRecorder auditRecorder,
            MessageSource messageSource,
            Clock clock,
            @Value("${mannschaft.affiliation.reject-cooldown-days:30}") long rejectCooldownDays) {
        this.lockSupport = lockSupport;
        this.membershipRepository = membershipRepository;
        this.restrictionService = restrictionService;
        this.organizationPort = organizationPort;
        this.notifier = notifier;
        this.auditRecorder = auditRecorder;
        this.messageSource = messageSource;
        this.clock = clock;
        this.rejectCooldown = Duration.ofDays(rejectCooldownDays);
    }

    /**
     * 加盟申請を承認する（§6.2 step 2〜7）。承認後の加盟の確定値を返す。
     *
     * @param organizationId 組織（操作者が ADMIN であることを確認済み）
     * @param teamId         加盟行のチーム（{@code (id, organization_id)} で事前に引いた値）
     * @param membershipId   加盟行の ID
     * @param operatorUserId 操作者
     * @param overrideGroup  希望グループを上書きするか
     * @param groupId        上書きするグループ（{@code overrideGroup=true} のときだけ使う。null なら未分類）
     */
    @Transactional
    public TeamOrgAffiliationAssembler.AffiliationRow approve(Long organizationId, Long teamId, Long membershipId, Long operatorUserId,
                                       boolean overrideGroup, UUID groupId) {
        // 1. 最初の文でチーム行 → 組織行をロックし、続けて加盟行をロックして判定表に従う
        TeamOrgAffiliationLockSupport.LockedParties parties =
                lockSupport.lockTeamThenOrganizationForResponse(teamId, organizationId);
        TeamOrgMembershipEntity row = lockPendingApplication(membershipId, organizationId);

        // 2. 状態の再確認（§6.2 step 3）: 組織アーカイブ → 既存のアーカイブ拒否、チーム削除 → 404 TEAM_070、
        //    チームアーカイブ → 既存のアーカイブ拒否。いずれも行は PENDING のまま残す
        TeamAffiliationOrganizationPort.OrganizationAffiliationState organization = parties.organization();
        if (organization.archived()) {
            throw new BusinessException(OrgErrorCode.ORG_003);
        }
        TeamEntity team = requireLiveTeam(parties);
        if (team.getArchivedAt() != null) {
            throw new BusinessException(TeamErrorCode.TEAM_002);
        }

        // 3. 確定グループ（§6.2 step 4）
        UUID requestedGroupId = row.getGroupId();
        TeamAffiliationOrganizationPort.GroupRef group =
                decideGroup(organization, organizationId, requestedGroupId, overrideGroup, groupId);
        UUID finalGroupId = group == null ? null : group.id();

        // 4. 条件付き UPDATE。加盟行のロックを保持しているので必ず1行に当たる。0 件なら前提が崩れているため握り潰さない
        //    承認の瞬間は DATETIME（秒精度）に合わせて秒へ丸め、応答と DB の値を一致させる
        Instant respondedAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        int updated = finalGroupId == null
                ? membershipRepository.approvePendingApplicationUnassigned(
                        membershipId, organizationId, operatorUserId, respondedAt.getEpochSecond())
                : membershipRepository.approvePendingApplication(
                        membershipId, organizationId, finalGroupId, operatorUserId, respondedAt.getEpochSecond());
        if (updated != 1) {
            throw new IllegalStateException("ロック済みの加盟申請を条件付き UPDATE できない: membershipId=" + membershipId);
        }

        // 5. 通知（チームの加盟操作者。同じトランザクションで enqueue するだけ）と監査
        String groupLabel = group == null
                ? messageSource.getMessage(UNASSIGNED_GROUP_LABEL_KEY, null, Locale.JAPANESE)
                : group.name();
        notifier.enqueue(TeamAffiliationNotice.applicationApproved(
                teamId, team.getSlug(), organizationId, organization.name(), groupLabel,
                membershipId, operatorUserId));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("membership_id", membershipId);
        metadata.put("organization_id", organizationId);
        metadata.put("team_id", teamId);
        metadata.put("via", TeamOrgAffiliationDirection.TEAM_APPLY.name());
        metadata.put("requested_group_id", requestedGroupId == null ? null : requestedGroupId.toString());
        metadata.put("group_id", finalGroupId == null ? null : finalGroupId.toString());
        auditRecorder.record(AuditEventType.TEAM_ORG_MEMBERSHIP_CREATED,
                operatorUserId, teamId, organizationId, metadata);

        // コミット後に行を取り直さない（その間に除名・離脱で消えると 500 になる）。確定した値から応答の入力を作る
        TeamOrgAffiliationAssembler.AffiliationRow requested = TeamOrgAffiliationAssembler.AffiliationRow.from(row);
        return new TeamOrgAffiliationAssembler.AffiliationRow(membershipId, teamId, organizationId,
                TeamOrgMembershipEntity.Status.ACTIVE, TeamOrgAffiliationDirection.TEAM_APPLY, finalGroupId, null,
                requested.invitedBy(), requested.invitedAt(), respondedAt);
    }

    /**
     * 加盟申請を拒否する（§6.3 step 2〜6）。記録された制限（合成後）を返す。
     *
     * @param reason 理由（正規化・長さ検証済み。任意）
     * @param block  true なら無期限ブロック、false なら30日の冷却
     */
    @Transactional
    public TeamOrgAffiliationRestrictionService.RestrictionView reject(
            Long organizationId, Long teamId, Long membershipId, Long operatorUserId, String reason, boolean block) {
        TeamOrgAffiliationLockSupport.LockedParties parties =
                lockSupport.lockTeamThenOrganizationForResponse(teamId, organizationId);
        lockPendingApplication(membershipId, organizationId);
        // チームが削除済みなら行は §4.5 の片付けで消える。存在しない行と同じ 404 にそろえる
        TeamEntity team = requireLiveTeam(parties);

        int deleted = membershipRepository.deletePendingApplicationOfOrganization(membershipId, organizationId);
        if (deleted != 1) {
            throw new IllegalStateException("ロック済みの加盟申請を条件付き DELETE できない: membershipId=" + membershipId);
        }

        restrictionService.record(organizationId, teamId, TeamOrgAffiliationDirection.TEAM_APPLY,
                TeamOrgAffiliationRestrictionReason.REJECTED,
                block ? TeamOrgAffiliationRestrictionKind.BLOCK : TeamOrgAffiliationRestrictionKind.COOLDOWN,
                rejectCooldown, operatorUserId);
        TeamOrgAffiliationRestrictionService.RestrictionView restriction = restrictionService
                .currentRestriction(organizationId, teamId, TeamOrgAffiliationDirection.TEAM_APPLY)
                .orElseThrow(() -> new IllegalStateException(
                        "記録した直後の制限を読み出せない: organizationId=" + organizationId + " teamId=" + teamId));

        notifier.enqueue(TeamAffiliationNotice.applicationRejected(
                teamId, team.getSlug(), organizationId, parties.organization().name(), reason,
                membershipId, operatorUserId));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("membership_id", membershipId);
        metadata.put("organization_id", organizationId);
        metadata.put("team_id", teamId);
        metadata.put("reason", reason);
        metadata.put("block", block);
        auditRecorder.record(AuditEventType.TEAM_ORG_APPLICATION_REJECTED,
                operatorUserId, teamId, organizationId, metadata);
        return restriction;
    }

    /**
     * 加盟行を {@code (id, organization_id)} で {@code FOR UPDATE} で読み、§6.4 の判定表に従う。
     * 行が無い（存在しない・他組織・処理済みで消えた）→ 404 {@code TEAM_070}、PENDING / TEAM_APPLY でない → 409 {@code TEAM_071}。
     */
    private TeamOrgMembershipEntity lockPendingApplication(Long membershipId, Long organizationId) {
        TeamOrgMembershipEntity row = membershipRepository
                .findByIdAndOrganizationIdForUpdate(membershipId, organizationId)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_070));
        if (row.getStatus() != TeamOrgMembershipEntity.Status.PENDING
                || row.getDirection() != TeamOrgAffiliationDirection.TEAM_APPLY) {
            throw new BusinessException(TeamErrorCode.TEAM_071);
        }
        return row;
    }

    private static TeamEntity requireLiveTeam(TeamOrgAffiliationLockSupport.LockedParties parties) {
        if (parties.team() == null) {
            throw new BusinessException(TeamErrorCode.TEAM_070);
        }
        return parties.team();
    }

    /**
     * 確定グループを決める（§6.2 step 4）。
     * <ul>
     *   <li>グループ機能 off → 常に未分類（保存値・上書き指定にかかわらず。AC-G121）</li>
     *   <li>{@code overrideGroup=false} → 申請時の希望グループ（削除済み・他組織なら未分類）</li>
     *   <li>{@code overrideGroup=true} かつ {@code groupId} あり → その組織の生存グループでなければ 400 {@code TEAM_072}</li>
     *   <li>{@code overrideGroup=true} かつ {@code groupId=null} → 未分類（REQUIRED の組織でも許す）</li>
     * </ul>
     *
     * @return 確定グループ。未分類なら null
     */
    private TeamAffiliationOrganizationPort.GroupRef decideGroup(
            TeamAffiliationOrganizationPort.OrganizationAffiliationState organization, Long organizationId,
            UUID requestedGroupId, boolean overrideGroup, UUID groupId) {
        if (!organization.groupsEnabled()) {
            return null;
        }
        if (!overrideGroup) {
            return requestedGroupId == null ? null : aliveGroupOf(organizationId, requestedGroupId);
        }
        if (groupId == null) {
            return null;
        }
        TeamAffiliationOrganizationPort.GroupRef group = aliveGroupOf(organizationId, groupId);
        if (group == null) {
            // 他組織のグループ・削除済み・存在しないグループは区別しない
            throw new BusinessException(TeamErrorCode.TEAM_072);
        }
        return group;
    }

    /** その組織の生存グループなら表示用の最小情報を、そうでなければ null を返す。 */
    private TeamAffiliationOrganizationPort.GroupRef aliveGroupOf(Long organizationId, UUID groupId) {
        TeamAffiliationOrganizationPort.GroupRef group =
                organizationPort.findAliveGroupRefs(List.of(groupId)).get(groupId);
        return group != null && organizationId.equals(group.organizationId()) ? group : null;
    }
}
