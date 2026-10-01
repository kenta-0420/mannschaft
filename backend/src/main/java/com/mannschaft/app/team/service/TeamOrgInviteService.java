package com.mannschaft.app.team.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 組織からの加盟招待の入口（F01.2.1 §6.5・§10.6）。招待・取消・送信済み一覧（組織側）と、
 * 受信招待一覧・承諾・辞退（チーム側）。
 *
 * <p>本クラスは<b>トランザクションを持たない</b>。入力検証・招待先チームの可視性の確認を
 * トランザクションの外で済ませてから書き込みのトランザクション（{@link TeamOrgInviteCommandService}）に入り、
 * 応答の組み立て（{@link TeamOrgAffiliationAssembler}）は書き込みの後にトランザクションの外で行う。</p>
 *
 * <p>認可（組織側は組織 ADMIN、チーム側はチームの加盟操作者であること）は呼び出し元の Controller が先に行う。</p>
 *
 * <h2>存在オラクルを作らない（§6.5「招待先の可視性」・AC-D09）</h2>
 * <p>招待する組織 ADMIN から visibility 上<b>見えないチーム</b>は、存在しない slug と同じステータス・同じエラーコード
 * （既存のチーム不在 404 {@code TEAM_001}）を返す。以降の判定（制限・既存の加盟・申請の有無）は見えるチームに対してだけ
 * 行うので、非公開チームの存在・加盟状況・制限状況を応答の違いから推測できない。</p>
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
    private final TeamOrgInviteCommandService commandService;
    private final TeamOrgAffiliationAssembler assembler;

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

        TeamOrgInviteCommandService.CreatedInvite created = commandService.invite(
                teamId, organizationId, operatorUserId, request.groupId(), message);

        // コミット後に行を取り直さない（その間に辞退・取消で消えると 500 になる）。確定した値から組み立てる
        TeamOrgMembershipEntity snapshot = TeamOrgMembershipEntity.builder()
                .id(created.id())
                .teamId(teamId)
                .organizationId(organizationId)
                .status(TeamOrgMembershipEntity.Status.PENDING)
                .direction(TeamOrgAffiliationDirection.ORG_INVITE)
                .groupId(created.groupId())
                .message(created.message())
                .invitedBy(created.invitedBy())
                .invitedAt(toWallClock(created.invitedAt()))
                .build();
        return assembler.assembleForTeam(teamId, List.of(snapshot)).get(0);
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
        commandService.cancel(teamId, organizationId, operatorUserId);
    }

    /**
     * 組織が送った招待（PENDING / ORG_INVITE）の一覧を、招待日時の降順で返す。
     */
    public PagedResponse<TeamOrgAffiliationResponse> listSentInvites(Long organizationId, int page, int size) {
        Page<TeamOrgMembershipEntity> result = membershipRepository.findPageByOrganizationIdAndStatusAndDirection(
                organizationId, TeamOrgMembershipEntity.Status.PENDING, TeamOrgAffiliationDirection.ORG_INVITE,
                pageRequest(page, size));
        return toPaged(result, assembler.assembleAcrossTeams(result.getContent()));
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
     */
    public TeamOrgAffiliationResponse accept(Long teamId, Long operatorUserId, Long membershipId) {
        TeamOrgInviteCommandService.AcceptedInvite accepted = commandService.accept(teamId, membershipId,
                operatorUserId);
        TeamOrgMembershipEntity snapshot = TeamOrgMembershipEntity.builder()
                .id(accepted.id())
                .teamId(teamId)
                .organizationId(accepted.organizationId())
                .status(TeamOrgMembershipEntity.Status.ACTIVE)
                .direction(TeamOrgAffiliationDirection.ORG_INVITE)
                .groupId(accepted.groupId())
                .invitedBy(accepted.invitedBy())
                .invitedAt(toWallClock(accepted.invitedAt()))
                .respondedBy(operatorUserId)
                .respondedAt(toWallClock(accepted.respondedAt()))
                .build();
        return assembler.assembleForTeam(teamId, List.of(snapshot)).get(0);
    }

    /**
     * 招待を辞退し、記録された制限（合成後）を返す。他チームの ID・存在しない ID は同じ 404 {@code TEAM_070}。
     */
    public TeamOrgRestrictionSummaryResponse decline(Long teamId, Long operatorUserId, Long membershipId,
                                                     Boolean block) {
        TeamOrgInviteCommandService.RecordedRestriction restriction = commandService.decline(
                teamId, membershipId, operatorUserId, Boolean.TRUE.equals(block));
        return new TeamOrgRestrictionSummaryResponse(new TeamOrgRestrictionSummaryResponse.Restriction(
                restriction.kind().name(),
                restriction.restrictedUntil() == null
                        ? null
                        : restriction.restrictedUntil().atZone(UserZoneLocalDateTimeParser.SERVER_ZONE)
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

    /** 起きた瞬間を、加盟の行が保存している壁時計（JST。§4.6）へ戻す（応答の組み立て用の行の写し）。 */
    private static LocalDateTime toWallClock(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, UserZoneLocalDateTimeParser.SERVER_ZONE);
    }
}
