package com.mannschaft.app.team.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.fanout.FanoutMessageKind;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 組織からの加盟招待・承諾・辞退・取消の書き込み（F01.2.1 §6.5）。
 *
 * <p>本クラスのメソッドが<b>トランザクションの入口</b>であり、トランザクションは <b>team ドメインの中に閉じる</b>
 * （CLAUDE.md 原則5）。触る表はチーム・加盟・制限だけで、組織の状態の確認・グループの検証・通知・監査は、
 * 呼び出し元の {@link TeamOrgInviteService} がトランザクションの<b>外</b>で行う（組織の確認は書き込みの前、
 * 監査はコミットの後。4-A の監査と同じ形）。通知は team ドメインの outbox に書くだけなので、トランザクションの<b>中</b>で
 * 予約する（承諾は {@link #accept} の中、招待は読み直しの後の {@link #appendInviteReceivedNotice}。
 * docs/architecture/notification_outbox.md §10）。</p>
 *
 * <p>例外として、承諾（{@link #accept}）は加盟行をロックした<b>後</b>に、組織の最新のコミット済みの閉鎖の状態をポート越しに
 * 組織ドメインの<b>新しい</b>読み取り tx（{@link TeamAffiliationOrganizationPort#findClosureStateIncludingDeleted}）で確かめる。
 * 組織の表をこの tx に持ち込まず、ロックも取らない（F01.2.1 §4.5。理由は {@link #accept}）。</p>
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
    private final TeamAffiliationNotifier notifier;
    private final TeamAffiliationOrganizationPort organizationPort;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final Duration declineCooldown;
    private final Duration resendCooldown;

    public TeamOrgInviteCommandService(
            TeamRepository teamRepository,
            TeamOrgMembershipRepository membershipRepository,
            TeamOrgAffiliationRestrictionService restrictionService,
            TeamOrgAffiliationRestrictionRepository restrictionRepository,
            TeamAffiliationNotifier notifier,
            TeamAffiliationOrganizationPort organizationPort,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            @Value("${mannschaft.affiliation.reject-cooldown-days:30}") long declineCooldownDays,
            @Value("${mannschaft.affiliation.resend-cooldown-hours:24}") long resendCooldownHours) {
        this.teamRepository = teamRepository;
        this.membershipRepository = membershipRepository;
        this.restrictionService = restrictionService;
        this.restrictionRepository = restrictionRepository;
        this.notifier = notifier;
        this.organizationPort = organizationPort;
        this.eventPublisher = eventPublisher;
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
     * @param organizationClosureGeneration 事前に読んだ組織の閉鎖の世代（{@code organizations.closure_generation}）。
     *                                      加盟行に記録する。組織行はロックしないので、呼び出し側はコミットの後に組織行の
     *                                      共有ロックで世代を読み直し、進んでいれば作った招待を取り下げる（§4.5・§6.9）
     */
    @Transactional
    public CreatedInvite invite(Long teamId, Long organizationId, Long operatorUserId, UUID groupId,
                                String message, long organizationClosureGeneration) {
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
        //    作成時点の閉鎖の世代を記録する（F01.2.1 §4.5・TeamOrgClosureGeneration）。チームの世代はチーム行のロックの下で読んだ値
        Instant invitedAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        long teamClosureGeneration = team.getClosureGeneration();
        try {
            if (groupId == null) {
                membershipRepository.insertPendingInviteUnassigned(teamId, organizationId, message, operatorUserId,
                        invitedAt.getEpochSecond(), organizationClosureGeneration, teamClosureGeneration);
            } else {
                membershipRepository.insertPendingInvite(teamId, organizationId, groupId, message, operatorUserId,
                        invitedAt.getEpochSecond(), organizationClosureGeneration, teamClosureGeneration);
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
     * 生存していないか、閉鎖の世代が事前に読んだ値より進んでいれば（その間にアーカイブ → 解除された。§4.5）本メソッドで消す。読み直しの時点で組織のアーカイブがまだコミットされていなければ、
     * 後でコミットされたアーカイブの片付け（{@code OrganizationArchivedEvent}）がこの行を消すので、どちらの順でも残らない。</p>
     *
     * @return 消した行数（承諾などで既に PENDING でなければ 0）
     */
    @Transactional
    public int withdrawInviteOfClosedOrganization(Long membershipId, Long organizationId) {
        return membershipRepository.deletePendingInviteByOrganization(membershipId, organizationId);
    }

    /**
     * 招待のコミット後の組織の読み直しを通過した後に、招待の通知を outbox に予約する（設計書 §10 C 案・OB13a〜c）。
     *
     * <p>最初の文で招待の行を {@code FOR UPDATE} で読み、まだこのチームの PENDING / ORG_INVITE であるときだけ書く。
     * 読み直しの後に辞退・取消・承諾で行が変わっていれば何も書かない（取り消された招待の通知を出さない）。
     * 辞退・取消・承諾も同じ行を {@code FOR UPDATE} で取るので、確認と書き込みの間に状態は変わらない。
     * 予約と同じ tx で {@code invite_notified_at} を立てる（2-D。組織の閉鎖の片付けが「チームが知っている招待か」を判定する）。</p>
     *
     * @return 予約したら true。招待の行が PENDING の招待として残っていなければ false
     */
    @Transactional
    public boolean appendInviteReceivedNotice(Long membershipId, Long teamId, TeamAffiliationNotice notice) {
        Optional<TeamOrgMembershipEntity> row = membershipRepository.findByIdAndTeamIdForUpdate(membershipId, teamId);
        if (row.isEmpty()
                || row.get().getStatus() != TeamOrgMembershipEntity.Status.PENDING
                || row.get().getDirection() != TeamOrgAffiliationDirection.ORG_INVITE) {
            return false;
        }
        // 受信通知を予約した印を同じ tx で残す（F01.2.1 2-D）。組織のアーカイブ・削除の片付けは、この印の無い招待
        // （チームがまだ知らない招待）には取消の通知を送らない。片付けも同じ行を FOR UPDATE で取るので、印と予約は不可分
        membershipRepository.markInviteNotified(membershipId,
                Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).getEpochSecond());
        notifier.enqueue(notice);
        return true;
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
     * <p>承諾の通知（{@code TEAM_ORG_INVITE_ACCEPTED}・組織 ADMIN 全員）は同じトランザクションで outbox に予約する。
     * 予約が失敗すれば承諾ごと巻き戻る（OB14）。</p>
     *
     * <p>組織・チームの閉鎖より前に作られた招待（§4.5。記録した閉鎖の世代がいまの世代より古い行）は承諾しない。
     * チームの世代はチーム行のロックの下で読む。組織行はこの経路ではロックしない（ロックの順序を崩さない）ので、組織の世代は
     * 加盟行をロックした<b>後</b>に、組織の最新のコミット済みの状態をポート越しの新しい読み取り tx で読む。こうすると、
     * 読む前にコミットされた閉鎖（とその後の解除）は必ず見え、読んだ後にコミットされた閉鎖の片付けは、この加盟行のロックを
     * 待ってから承諾済み（ACTIVE）の行を見る（閉鎖より前に承諾が成立した、という順序になる）。事前に tx の外で読んだ
     * 組織の状態だけに頼ると、その読み取りと承諾の間に「閉鎖 → 解除」が挟まったとき閉鎖前の招待を ACTIVE にしてしまう。</p>
     *
     * @param expectedOrganizationId 事前に読んだ組織（行の組織と一致することを確かめる）
     * @param confirmedGroupId       確定グループ（null なら未分類）
     * @param organizationName       通知の文面に使う組織名（事前に読んだ組織の状態）
     * @param organizationSlug       通知から開く画面の組織 slug
     */
    @Transactional
    public AcceptedInvite accept(Long teamId, Long membershipId, Long operatorUserId,
                                 Long expectedOrganizationId, UUID confirmedGroupId,
                                 String organizationName, String organizationSlug) {
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
        // 組織の最新のコミット済みの閉鎖の状態を、加盟行のロックの後に組織ドメインの新しい読み取り tx で確かめる（上記）。
        // 論理削除済み（行が無い）は行が無いのと同じ 404、アーカイブ済みは既存のアーカイブ拒否（行は片付けで消える）
        TeamAffiliationOrganizationPort.OrganizationClosureState organization = organizationPort
                .findClosureStateIncludingDeleted(expectedOrganizationId)
                .filter(state -> !state.deleted())
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_070));
        if (organization.archived()) {
            throw new BusinessException(OrgErrorCode.ORG_003);
        }
        // 組織・チームの閉鎖より前に作られた招待は、閉鎖の片付けの前に解除・復元されて残った無効な行（§4.5。
        // 解除で復元しない）。承諾せず（ACTIVE にしない）、行が無いのと同じ 404 で拒み、ロールバックの後に片付けさせる
        TeamOrgClosureGeneration.rejectIfCreatedBeforeClosure(
                row, organization.closureGeneration(), team, eventPublisher);
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

        // 承諾と同じトランザクションで通知を予約する（outbox への書き込みが失敗すれば承諾も巻き戻る）
        notifier.enqueue(new TeamAffiliationNotice(
                NotificationType.TEAM_ORG_INVITE_ACCEPTED,
                FanoutMessageKind.TEAM_ORG_INVITE_ACCEPTED,
                List.of(team.getName(), organizationName),
                TeamAffiliationNotice.RecipientScope.ORGANIZATION_ADMINS,
                expectedOrganizationId,
                expectedOrganizationId,
                membershipId,
                operatorUserId,
                "/organizations/" + organizationSlug + "/member-teams"));

        // コミット後に行を取り直さない（その間に離脱・除名で消えると 500 になる）。確定した値から応答の入力を作る
        TeamOrgAffiliationAssembler.AffiliationRow accepted = new TeamOrgAffiliationAssembler.AffiliationRow(
                membershipId, teamId, expectedOrganizationId, TeamOrgMembershipEntity.Status.ACTIVE,
                TeamOrgAffiliationDirection.ORG_INVITE, confirmedGroupId, null,
                requested.invitedBy(), requested.invitedAt(), respondedAt);
        return new AcceptedInvite(accepted);
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

    /** 承諾で成立した加盟の確定値。 */
    public record AcceptedInvite(TeamOrgAffiliationAssembler.AffiliationRow row) {
    }

    /** 辞退の結果（合成後の、いま有効な制限）。 */
    public record DeclinedInvite(Long organizationId, TeamOrgAffiliationRestrictionKind kind, Instant restrictedUntil) {
    }
}
