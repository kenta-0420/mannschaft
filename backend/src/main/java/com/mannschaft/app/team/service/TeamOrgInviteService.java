package com.mannschaft.app.team.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.fanout.FanoutMessageKind;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.dto.InviteTeamToOrganizationRequest;
import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse;
import com.mannschaft.app.team.dto.TeamOrgRestrictionSummaryResponse;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 組織からの加盟招待の入口（F01.2.1 §6.5・§10.6）。招待・取消・送信済み一覧（組織側）と、
 * 受信招待一覧・承諾・辞退（チーム側）。
 *
 * <p>本クラスは<b>トランザクションを持たない</b>。書き込みのトランザクション（{@link TeamOrgInviteCommandService}）は
 * team ドメインの表だけを触り、ほかのドメインに関わる処理は本クラスがトランザクションの外で行う。</p>
 * <ol>
 *   <li><b>書き込みの前</b>: 入力検証、招待先チームの可視性、組織の状態（削除・アーカイブ）とグループの検証
 *       （組織ドメインの読み取り。{@link TeamAffiliationOrganizationPort}）</li>
 *   <li><b>書き込み</b>: チーム行のロック → 制限・既存の加盟 → INSERT／UPDATE／DELETE（team ドメインのみ）</li>
 *   <li><b>コミットの後</b>: 組織の状態の読み直し（招待のみ）、通知の enqueue と監査ログ（4-A の監査と同じ形）</li>
 * </ol>
 *
 * <p>認可（組織側は組織 ADMIN、チーム側はチームの加盟操作者であること）は呼び出し元の Controller が先に行う。</p>
 *
 * <h2>存在オラクルを作らない（§6.5「招待先の可視性」・AC-D09）</h2>
 * <p>招待する組織 ADMIN から visibility 上<b>見えないチーム</b>は、存在しない slug と同じステータス・同じエラーコード
 * （既存のチーム不在 404 {@code TEAM_001}）を返す。以降の判定（制限・既存の加盟・申請の有無）は見えるチームに対してだけ
 * 行うので、非公開チームの存在・加盟状況・制限状況を応答の違いから推測できない。</p>
 *
 * <h2>通知と監査はコミットの後（原子的ではない）</h2>
 * <p>通知の登録・監査の記録は、書き込みのコミット後にそれぞれのドメインのトランザクションで行う。どちらが失敗しても
 * 招待などの操作は巻き戻らない。順序は<b>監査 → 通知</b>で、通知の登録が失敗（例外として呼び出し元へ伝わる）しても
 * 先に記録した監査は残る（再試行は状態判定で拒否されるため、監査を後から回復できない）。一方、監査の保存失敗は
 * {@code AuditLogService#recordSync} の既存の挙動どおり<b>例外にならずログ（ERROR）に残るだけ</b>で、呼び出し元へは伝わらない
 * （共通サービスの挙動であり、2-C では変えない）。</p>
 */
@Service
@RequiredArgsConstructor
public class TeamOrgInviteService {

    /** 添え書きの上限（コードポイント数。§10.4 と同じ）。 */
    static final int MESSAGE_MAX_CODE_POINTS = 500;
    /** 一覧の1ページの既定件数と上限（§10）。 */
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final TeamRepository teamRepository;
    private final TeamOrgMembershipRepository membershipRepository;
    private final ContentVisibilityChecker contentVisibilityChecker;
    private final TeamAffiliationOrganizationPort organizationPort;
    private final TeamOrgInviteCommandService commandService;
    private final TeamOrgAffiliationAssembler assembler;
    private final TeamAffiliationNotifier notifier;
    private final TeamAffiliationAuditRecorder auditRecorder;

    // =====================================================================
    // 組織側
    // =====================================================================

    /**
     * チームを加盟に招待し、作成した招待（PENDING / ORG_INVITE）の共通表現を返す。
     *
     * @param organizationId 招待する組織（操作者が組織 ADMIN であることを確認済み）
     * @param operatorUserId 操作者
     */
    public TeamOrgAffiliationResponse invite(Long organizationId, Long operatorUserId,
                                             InviteTeamToOrganizationRequest request) {
        String teamSlug = request == null || request.teamSlug() == null ? "" : request.teamSlug().trim();
        if (teamSlug.isEmpty()) {
            throw new BusinessException(CommonErrorCode.COMMON_001, List.of(new ErrorResponse.FieldError(
                    "teamSlug", "招待するチームを指定してください")));
        }
        String message = normalizeMessage(request.message());

        // 見えないチーム・存在しない slug・アーカイブ済みは、区別できない同じ 404（TEAM_001）にする
        Long teamId = findVisibleTeamId(teamSlug, operatorUserId);

        // 書き込みの前に、組織の状態とグループを組織ドメインの読み取りで確かめる（チームのトランザクションに持ち込まない）
        TeamAffiliationOrganizationPort.OrganizationAffiliationState organization = requireOpenOrganization(
                organizationId);
        UUID groupId = request.groupId();
        if (groupId != null && (!organization.groupsEnabled()
                || !organizationPort.isAliveGroupOfOrganization(organizationId, groupId))) {
            // 他組織のグループ・削除済みグループ・グループ機能 off は、区別しない同じ 400 TEAM_072（黙って捨てない）
            throw new BusinessException(TeamErrorCode.TEAM_072);
        }

        TeamOrgInviteCommandService.CreatedInvite created = commandService.invite(
                teamId, organizationId, operatorUserId, groupId, message);

        // コミットの後で組織の状態を読み直す。書き込みの間にアーカイブ・削除されていたら、作った招待を取り下げる（§6.9）
        TeamAffiliationOrganizationPort.OrganizationAffiliationState current =
                organizationPort.findAffiliationState(organizationId).orElse(null);
        if (current == null || current.archived()) {
            commandService.withdrawInviteOfClosedOrganization(created.id(), organizationId);
            throw new BusinessException(current == null ? OrgErrorCode.ORG_001 : OrgErrorCode.ORG_003);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("membership_id", created.id());
        metadata.put("organization_id", organizationId);
        metadata.put("team_id", teamId);
        metadata.put("group_id", groupId == null ? null : groupId.toString());
        auditRecorder.record(AuditEventType.TEAM_ORG_INVITE_SENT, operatorUserId, teamId, organizationId, metadata);

        // 監査の後に通知する（通知の登録が失敗しても、確定した操作の監査は残る）
        notifier.enqueueAfterCommit(new TeamAffiliationNotice(
                NotificationType.TEAM_ORG_INVITE_RECEIVED,
                FanoutMessageKind.TEAM_ORG_INVITE_RECEIVED,
                List.of(organization.name(), created.teamName()),
                TeamAffiliationNotice.RecipientScope.TEAM_AFFILIATION_OPERATORS,
                teamId,
                organizationId,
                created.id(),
                operatorUserId,
                "/teams/" + created.teamSlug() + "/affiliations?view=invites"));

        // コミット後に行を取り直さない（その間に辞退・取消で消えると 500 になる）。確定した値から組み立てる
        return assembler.assembleRowsForTeam(teamId, List.of(created.toRow())).get(0);
    }

    /**
     * 送った招待を取り消す。存在しないチームの slug と、招待を送っていないチームは区別しない（同じ 404 {@code TEAM_070}）。
     */
    public void cancel(Long organizationId, Long operatorUserId, String teamSlug) {
        Long teamId = teamSlug == null
                ? null
                : teamRepository.findBySlugAndDeletedAtIsNull(teamSlug).map(TeamEntity::getId).orElse(null);
        if (teamId == null) {
            throw new BusinessException(TeamErrorCode.TEAM_070);
        }
        Long membershipId = commandService.cancel(teamId, organizationId, operatorUserId);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("membership_id", membershipId);
        metadata.put("organization_id", organizationId);
        metadata.put("team_id", teamId);
        auditRecorder.record(AuditEventType.TEAM_ORG_INVITE_CANCELLED,
                operatorUserId, teamId, organizationId, metadata);
    }

    /**
     * 組織が送った招待（PENDING / ORG_INVITE）の一覧を、招待日時の降順で返す。
     */
    public PagedResponse<TeamOrgAffiliationResponse> listSentInvites(Long organizationId, int page, int size) {
        Page<TeamOrgMembershipEntity> result = membershipRepository.findPageByOrganizationIdAndStatusAndDirection(
                organizationId, TeamOrgMembershipEntity.Status.PENDING, TeamOrgAffiliationDirection.ORG_INVITE,
                pageRequest(page, size));
        return toPaged(result, assembler.assembleForOrganization(result.getContent()));
    }

    // =====================================================================
    // チーム側
    // =====================================================================

    /**
     * チームが受け取った招待（PENDING / ORG_INVITE）の一覧を、招待日時の降順で返す。
     */
    public PagedResponse<TeamOrgAffiliationResponse> listReceivedInvites(Long teamId, int page, int size) {
        Page<TeamOrgMembershipEntity> result = membershipRepository.findPageByTeamIdAndStatusAndDirection(
                teamId, TeamOrgMembershipEntity.Status.PENDING, TeamOrgAffiliationDirection.ORG_INVITE,
                pageRequest(page, size));
        return toPaged(result, assembler.assembleForTeam(teamId, result.getContent()));
    }

    /**
     * 招待を承諾し、成立した加盟（ACTIVE）の共通表現を返す。他チームの ID・存在しない ID は同じ 404 {@code TEAM_070}。
     *
     * <p>招待で指定したグループが承諾の時点で削除済み、またはグループ機能が off なら、未分類（{@code group_id = NULL}）で
     * 加盟を成立させる（承諾は拒否しない。AC-D11）。承諾時にグループは変えられない。</p>
     */
    public TeamOrgAffiliationResponse accept(Long teamId, Long operatorUserId, Long membershipId) {
        // 1. 判定表の前段（行が無い 404・招待でない 409）と、組織 ID の取得
        TeamOrgInviteCommandService.InviteTarget target = commandService.findPendingInvite(teamId, membershipId);
        Long organizationId = target.organizationId();

        // 2. 組織の状態（§6.2 step 3 と同じ再確認）。削除済みの組織の行は片付けで消えるので、行が無いのと同じ 404
        TeamAffiliationOrganizationPort.OrganizationAffiliationState organization =
                organizationPort.findAffiliationState(organizationId)
                        .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_070));
        if (organization.archived()) {
            throw new BusinessException(OrgErrorCode.ORG_003);
        }

        // 3. 確定グループ: 招待時の指定が、いまも組織の生存グループで、グループ機能が on のときだけ残す
        UUID requested = target.requestedGroupId();
        UUID confirmedGroupId = requested != null && organization.groupsEnabled()
                && organizationPort.isAliveGroupOfOrganization(organizationId, requested)
                ? requested : null;

        // 4. 書き込み（team ドメインのトランザクション）
        TeamOrgInviteCommandService.AcceptedInvite accepted = commandService.accept(
                teamId, membershipId, operatorUserId, organizationId, confirmedGroupId);

        // 5. コミットの後: 監査 → 通知（組織 ADMIN 全員）の順。通知の登録が失敗しても、確定した操作の監査は残る
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("via", TeamOrgAffiliationDirection.ORG_INVITE.name());
        metadata.put("membership_id", membershipId);
        metadata.put("organization_id", organizationId);
        metadata.put("team_id", teamId);
        metadata.put("group_id", confirmedGroupId == null ? null : confirmedGroupId.toString());
        auditRecorder.record(AuditEventType.TEAM_ORG_MEMBERSHIP_CREATED,
                operatorUserId, teamId, organizationId, metadata);

        notifier.enqueueAfterCommit(new TeamAffiliationNotice(
                NotificationType.TEAM_ORG_INVITE_ACCEPTED,
                FanoutMessageKind.TEAM_ORG_INVITE_ACCEPTED,
                List.of(accepted.teamName(), organization.name()),
                TeamAffiliationNotice.RecipientScope.ORGANIZATION_ADMINS,
                organizationId,
                organizationId,
                membershipId,
                operatorUserId,
                "/organizations/" + organization.slug() + "/member-teams"));

        return assembler.assembleRowsForTeam(teamId, List.of(accepted.row())).get(0);
    }

    /**
     * 招待を辞退し、記録された制限（合成後）を返す。他チームの ID・存在しない ID は同じ 404 {@code TEAM_070}。
     */
    public TeamOrgRestrictionSummaryResponse decline(Long teamId, Long operatorUserId, Long membershipId,
                                                     Boolean block) {
        boolean blocking = Boolean.TRUE.equals(block);
        TeamOrgInviteCommandService.DeclinedInvite declined = commandService.decline(
                teamId, membershipId, operatorUserId, blocking);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("membership_id", membershipId);
        metadata.put("organization_id", declined.organizationId());
        metadata.put("team_id", teamId);
        metadata.put("block", blocking);
        auditRecorder.record(AuditEventType.TEAM_ORG_INVITE_REJECTED,
                operatorUserId, teamId, declined.organizationId(), metadata);

        return new TeamOrgRestrictionSummaryResponse(new TeamOrgRestrictionSummaryResponse.Restriction(
                declined.kind().name(),
                declined.restrictedUntil() == null
                        ? null
                        : declined.restrictedUntil().atZone(UserZoneLocalDateTimeParser.SERVER_ZONE)
                                .toOffsetDateTime()));
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /**
     * slug で招待先チームを引き、操作者から<b>見える</b>ときだけ ID を返す。存在しない・論理削除済み・承諾前
     * （PROVISIONED）・アーカイブ済み・見えない非公開チームは、すべて同じ 404 {@code TEAM_001}。
     */
    private Long findVisibleTeamId(String teamSlug, Long viewerUserId) {
        return teamRepository
                .findBySlugAndDeletedAtIsNullAndLifecycleStatus(teamSlug, TeamEntity.LifecycleStatus.ACTIVE)
                .map(TeamEntity::getId)
                // 可視性は F00 の TEAM ラダーに委譲する（アーカイブ済みは SYSTEM_ADMIN 以外に見えない）
                .filter(id -> contentVisibilityChecker.canView(ReferenceType.TEAM, id, viewerUserId))
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_001));
    }

    /** 組織が生存し、アーカイブされていないことを確かめる（削除済み 404 {@code ORG_001}・アーカイブ済み {@code ORG_003}）。 */
    private TeamAffiliationOrganizationPort.OrganizationAffiliationState requireOpenOrganization(Long organizationId) {
        TeamAffiliationOrganizationPort.OrganizationAffiliationState organization =
                organizationPort.findAffiliationState(organizationId)
                        .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_001));
        if (organization.archived()) {
            throw new BusinessException(OrgErrorCode.ORG_003);
        }
        return organization;
    }

    /**
     * 添え書きを検証・正規化する。空文字（空白のみを含む）は null に正規化し、500コードポイントを超えたら 400。
     */
    private static String normalizeMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.codePointCount(0, raw.length()) > MESSAGE_MAX_CODE_POINTS) {
            throw new BusinessException(CommonErrorCode.COMMON_001, List.of(new ErrorResponse.FieldError(
                    "message", "添え書きは" + MESSAGE_MAX_CODE_POINTS + "文字以内で入力してください")));
        }
        return raw;
    }

    private static PageRequest pageRequest(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize);
    }

    private static PagedResponse<TeamOrgAffiliationResponse> toPaged(Page<TeamOrgMembershipEntity> result,
                                                                    List<TeamOrgAffiliationResponse> data) {
        return PagedResponse.of(data, new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages()));
    }
}
