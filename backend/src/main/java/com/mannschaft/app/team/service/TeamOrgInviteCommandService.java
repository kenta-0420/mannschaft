package com.mannschaft.app.team.service;

import com.mannschaft.app.common.BusinessException;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * 組織からの加盟招待・承諾・辞退・取消の書き込み（F01.2.1 §6.5）。
 *
 * <p>本クラスのメソッドが<b>トランザクションの入口</b>であり、トランザクションは <b>team ドメインの中に閉じる</b>
 * （CLAUDE.md 原則5）。触る表はチーム・加盟・制限だけで、組織の状態の確認・グループの検証・通知・監査は、
 * 呼び出し元の {@link TeamOrgInviteService} がトランザクションの<b>外</b>で行う（組織の確認は書き込みの前、
 * 通知と監査はコミットの後。4-A の監査と同じ形）。</p>
 *
 * <h2>ロックの順序（デッドロックしない理由）</h2>
 * <ul>
 *   <li>招待の作成: 最初の文で<b>チーム行</b>を {@code FOR UPDATE} で取る。同じチームへの招待と申請（§6.1。チーム行 → 組織行）は
 *       同じチーム行を取り合うので、後から来た側はロック待ちの後に先行の行を見て 409 になる。</li>
 *   <li>承諾: <b>チーム行 → 加盟行</b>。チーム行を最初に取るので、同じチームの招待・申請の作成と直列化される。</li>
 *   <li>辞退・取消: 加盟行だけを {@code FOR UPDATE} で取る（承諾・別の辞退／取消と直列化し、§6.4 の判定表を一意に決める）。</li>
 * </ul>
 * <p>どの経路も「チーム行 → 加盟行」の順を崩さず、組織行は取らないため、組織行を先に取る経路（申請の
 * チーム行 → 組織行、組織のアーカイブの組織行）とも循環待ちにならない。</p>
 *
 * <p>時刻は起きた瞬間（{@link Instant}・秒丸め）をアプリの {@link Clock} から取り、DB へはエポック秒で渡す
 * （加盟行の時刻列は UTC 壁時計の DATETIME。2-B2 の承認と同じ作法。IT で Clock を固定できる）。</p>
 */
@Service
public class TeamOrgInviteCommandService {

    private final TeamRepository teamRepository;
    private final TeamOrgMembershipRepository membershipRepository;
    private final TeamOrgAffiliationRestrictionService restrictionService;
    private final TeamOrgAffiliationRestrictionRepository restrictionRepository;
    private final Clock clock;
    private final Duration declineCooldown;
    private final Duration resendCooldown;

    public TeamOrgInviteCommandService(
            TeamRepository teamRepository,
            TeamOrgMembershipRepository membershipRepository,
            TeamOrgAffiliationRestrictionService restrictionService,
            TeamOrgAffiliationRestrictionRepository restrictionRepository,
            Clock clock,
            @Value("${mannschaft.affiliation.reject-cooldown-days:30}") long declineCooldownDays,
            @Value("${mannschaft.affiliation.resend-cooldown-hours:24}") long resendCooldownHours) {
        this.teamRepository = teamRepository;
        this.membershipRepository = membershipRepository;
        this.restrictionService = restrictionService;
        this.restrictionRepository = restrictionRepository;
        this.clock = clock;
        this.declineCooldown = Duration.ofDays(declineCooldownDays);
        this.resendCooldown = Duration.ofHours(resendCooldownHours);
    }

    /**
     * チームを加盟に招待する（§6.5）。作成した加盟（PENDING / ORG_INVITE）の確定値を返す。
     *
     * <p>チーム行をロックし、ロックの内側でチームの状態の再確認・制限・既存の加盟を確かめて INSERT する。
     * 組織の状態（削除・アーカイブ）とグループの検証は、呼び出し前にトランザクションの外で済ませてある。</p>
     *
     * @param teamId         招待するチーム（操作者から見えることを確認済み）
     * @param organizationId 招待する組織（操作者が組織 ADMIN で、組織が生存・未アーカイブであることを確認済み）
     * @param operatorUserId 操作者
     * @param groupId        加盟後に所属させるグループ（任意。組織の生存グループであることを確認済み）
     * @param message        添え書き（正規化・長さ検証済み。任意）
     */
    @Transactional
    public CreatedInvite invite(Long teamId, Long organizationId, Long operatorUserId, UUID groupId,
                                String message) {
        // 1. 最初の文でチーム行をロックし、ロック取得後にチームの状態を再確認する（削除 404・アーカイブ）
        TeamEntity team = teamRepository.findByIdForUpdate(teamId)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_001));
        if (team.getArchivedAt() != null) {
            throw new BusinessException(TeamErrorCode.TEAM_002);
        }

        // 2. 制限（ORG_INVITE 方向）→ 403 TEAM_068（冷却中かブロック中かは区別しない）
        restrictionService.assertNotRestricted(organizationId, teamId, TeamOrgAffiliationDirection.ORG_INVITE);

        // 3. 同じ (team, org) の行: ACTIVE → TEAM_065 / PENDING（招待・申請どちらでも）→ TEAM_066
        Optional<TeamOrgMembershipEntity> existing =
                membershipRepository.findByTeamIdAndOrganizationId(teamId, organizationId);
        if (existing.isPresent()) {
            throw new BusinessException(existing.get().getStatus() == TeamOrgMembershipEntity.Status.ACTIVE
                    ? TeamErrorCode.TEAM_065 : TeamErrorCode.TEAM_066);
        }

        // 4. INSERT。招待の瞬間は DATETIME（秒精度）に合わせて秒へ丸め、応答と DB の値を一致させる。
        //    ロックの内側なので一意制約違反は通常起きないが、起きたら並行した招待・申請として 409 TEAM_066
        Instant invitedAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        try {
            if (groupId == null) {
                membershipRepository.insertPendingInviteUnassigned(teamId, organizationId, message, operatorUserId,
                        invitedAt.getEpochSecond());
            } else {
                membershipRepository.insertPendingInvite(teamId, organizationId, groupId, message, operatorUserId,
                        invitedAt.getEpochSecond());
            }
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(TeamErrorCode.TEAM_066, e);
        }
        long membershipId = membershipRepository.lastInsertId();

        return new CreatedInvite(membershipId, teamId, team.getSlug(), team.getName(), organizationId, groupId,
                message, operatorUserId, invitedAt);
    }

    /**
     * 組織がアーカイブ・削除されたことを招待のコミット後に見つけたとき、作ったばかりの招待を取り下げる（§6.9）。
     *
     * <p>招待の作成は組織行をロックしないため、「組織の状態を確認 → 組織がアーカイブされ片付けが走る → 招待を INSERT」の
     * 順に進むと、アーカイブ済みの組織に PENDING が残りうる。そこで呼び出し側はコミットの後で組織の状態を読み直し、
     * 生存していなければ本メソッドで消す。読み直しの時点で組織のアーカイブがまだコミットされていなければ、
     * 後でコミットされたアーカイブの片付け（{@code OrganizationArchivedEvent}）がこの行を消すので、どちらの順でも残らない。</p>
     *
     * @return 消した行数（承諾などで既に PENDING でなければ 0）
     */
    @Transactional
    public int withdrawInviteOfClosedOrganization(Long membershipId, Long organizationId) {
        return membershipRepository.deletePendingInviteByOrganization(membershipId, organizationId);
    }

    /**
     * 承諾の前に、招待行がこのチームの PENDING / ORG_INVITE であることを確かめ、組織 ID を返す（ロックしない読み取り）。
     *
     * <p>組織の状態の確認（トランザクションの外）に組織 ID が要るため、書き込みの前に行を一度読む。
     * 判定表はトランザクションの中（{@link #accept}）でもロックを取ってから改めて当てる。</p>
     */
    @Transactional(readOnly = true)
    public InviteTarget findPendingInvite(Long teamId, Long membershipId) {
        TeamOrgMembershipEntity row = membershipRepository.findByIdAndTeamId(membershipId, teamId)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_070));
        if (row.getStatus() != TeamOrgMembershipEntity.Status.PENDING
                || row.getDirection() != TeamOrgAffiliationDirection.ORG_INVITE) {
            throw new BusinessException(TeamErrorCode.TEAM_071);
        }
        return new InviteTarget(row.getId(), row.getOrganizationId(), row.getGroupId());
    }

    /**
     * 招待を承諾する（§6.5「承諾」）。成立した加盟（ACTIVE）の確定値を返す。
     *
     * <p>確定グループ（招待で指定したグループが組織の生存グループで、グループ機能が on のときだけ残す）と組織の状態の確認は、
     * 呼び出し前にトランザクションの外で済ませてある。応答は §6.4 の判定表に従う: 行が無い（他チームの ID・存在しない ID・
     * 削除済み）→ 404 {@code TEAM_070}、招待でない行・ACTIVE の行 → 409 {@code TEAM_071}。</p>
     *
     * @param expectedOrganizationId 事前に読んだ組織（行の組織と一致することを確かめる）
     * @param confirmedGroupId       確定グループ（null なら未分類）
     */
    @Transactional
    public AcceptedInvite accept(Long teamId, Long membershipId, Long operatorUserId,
                                 Long expectedOrganizationId, UUID confirmedGroupId) {
        // 1. 最初の文でチーム行をロックする（同じチームの招待・申請の作成と直列化する）。削除済みなら行は無いものとして扱う
        TeamEntity team = teamRepository.findByIdForUpdate(teamId)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_070));

        // 2. 加盟行をロックして読み、判定表に従う
        TeamOrgMembershipEntity row = membershipRepository.findByIdAndTeamIdForUpdate(membershipId, teamId)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_070));
        if (row.getStatus() != TeamOrgMembershipEntity.Status.PENDING
                || row.getDirection() != TeamOrgAffiliationDirection.ORG_INVITE
                || !row.getOrganizationId().equals(expectedOrganizationId)) {
            throw new BusinessException(TeamErrorCode.TEAM_071);
        }
        if (team.getArchivedAt() != null) {
            throw new BusinessException(TeamErrorCode.TEAM_002);
        }
        TeamOrgAffiliationAssembler.AffiliationRow requested = TeamOrgAffiliationAssembler.AffiliationRow.from(row);

        // 3. 条件付き UPDATE。行ロックを保持しているので必ず1行に当たる。0 件ならロックの前提が崩れているため握り潰さない
        //    承諾の瞬間は DATETIME（秒精度）に合わせて秒へ丸め、応答と DB の値を一致させる
        Instant respondedAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        int updated = confirmedGroupId == null
                ? membershipRepository.acceptPendingInviteUnassigned(membershipId, teamId, operatorUserId,
                        respondedAt.getEpochSecond())
                : membershipRepository.acceptPendingInvite(membershipId, teamId, confirmedGroupId, operatorUserId,
                        respondedAt.getEpochSecond());
        if (updated != 1) {
            throw new IllegalStateException("ロック済みの加盟招待を条件付き UPDATE できない: membershipId=" + membershipId);
        }

        // コミット後に行を取り直さない（その間に離脱・除名で消えると 500 になる）。確定した値から応答の入力を作る
        TeamOrgAffiliationAssembler.AffiliationRow accepted = new TeamOrgAffiliationAssembler.AffiliationRow(
                membershipId, teamId, expectedOrganizationId, TeamOrgMembershipEntity.Status.ACTIVE,
                TeamOrgAffiliationDirection.ORG_INVITE, confirmedGroupId, null,
                requested.invitedBy(), requested.invitedAt(), respondedAt);
        return new AcceptedInvite(accepted, team.getName());
    }

    /**
     * 招待を辞退する（§6.5「招待の拒否」）。行を削除し、ORG_INVITE 方向に30日の冷却（{@code block=true} なら無期限）を記録する。
     * 合成規則を適用した後の、いま有効な制限を返す。
     */
    @Transactional
    public DeclinedInvite decline(Long teamId, Long membershipId, Long operatorUserId, boolean block) {
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

        TeamOrgAffiliationRestrictionEntity restriction = restrictionRepository
                .findByOrganizationIdAndTeamIdAndDirection(organizationId, teamId,
                        TeamOrgAffiliationDirection.ORG_INVITE)
                .orElseThrow(() -> new IllegalStateException(
                        "辞退の直後に制限を読み出せない: organizationId=" + organizationId + " teamId=" + teamId));
        return new DeclinedInvite(organizationId, restriction.getKind(), restriction.getRestrictedUntil());
    }

    /**
     * 送った招待を取り消す（§6.5「招待の取消」）。行を削除し、ORG_INVITE 方向に24時間の冷却を記録する（通知の連打防止）。
     *
     * <p>応答は §6.4 の判定表を teamSlug で指定する経路に当てはめたもの（AC-G138）: (チーム, 組織) の行が無い → 404
     * {@code TEAM_070}、ACTIVE の行・申請の行 → 409 {@code TEAM_071}。</p>
     *
     * @return 取り消した加盟の ID（監査ログ用）
     */
    @Transactional
    public Long cancel(Long teamId, Long organizationId, Long operatorUserId) {
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
        return row.getId();
    }

    /**
     * 作成した招待（コミット後に行を取り直さず、トランザクション内で確定した値だけで応答・通知を組み立てるための値）。
     */
    public record CreatedInvite(Long id, Long teamId, String teamSlug, String teamName, Long organizationId,
                                UUID groupId, String message, Long invitedBy, Instant invitedAt) {

        /** 応答の組み立ての入力。 */
        public TeamOrgAffiliationAssembler.AffiliationRow toRow() {
            return new TeamOrgAffiliationAssembler.AffiliationRow(id, teamId, organizationId,
                    TeamOrgMembershipEntity.Status.PENDING, TeamOrgAffiliationDirection.ORG_INVITE, groupId, message,
                    invitedBy, invitedAt, null);
        }
    }

    /** 承諾の対象（事前の読み取りの結果）。 */
    public record InviteTarget(Long membershipId, Long organizationId, UUID requestedGroupId) {
    }

    /** 承諾で成立した加盟の確定値と、通知に使うチーム名。 */
    public record AcceptedInvite(TeamOrgAffiliationAssembler.AffiliationRow row, String teamName) {
    }

    /** 辞退の結果（合成後の、いま有効な制限）。 */
    public record DeclinedInvite(Long organizationId, TeamOrgAffiliationRestrictionKind kind, Instant restrictedUntil) {
    }
}
