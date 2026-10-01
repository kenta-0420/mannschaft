package com.mannschaft.app.team.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.dto.ApproveTeamApplicationRequest;
import com.mannschaft.app.team.dto.RejectTeamApplicationRequest;
import com.mannschaft.app.team.dto.RejectTeamApplicationResponse;
import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 組織側の受信した加盟申請の入口（F01.2.1 §6.2・§6.3・§10.5・§10.6）。一覧・承認・拒否。
 *
 * <p>本クラスは<b>トランザクションを持たない</b>。次の順に、トランザクションの外で済ませられることを済ませてから
 * 書き込みのトランザクション（{@link OrgTeamApplicationReviewCommandService}）に入り、応答の組み立て
 * （{@link TeamOrgAffiliationAssembler}）は書き込みの後にトランザクションの外で行う。</p>
 * <ol>
 *   <li>入力検証（400）</li>
 *   <li>行の所在の確認: {@code (id, organization_id)} の組で引けなければ 404 {@code TEAM_070}
 *       （存在しない ID・他組織の ID・処理済みで消えた行を区別しない。存在オラクルを作らない。AC-K04）。
 *       ここで得たチーム ID を、書き込み側がチーム行 → 組織行の順にロックを取るのに使う</li>
 *   <li>書き込み（ロック → 加盟行の判定表 → 状態の再確認 → 条件付き UPDATE / DELETE → 通知・監査）</li>
 * </ol>
 *
 * <p>認可（組織 ADMIN であること）は呼び出し元の Controller が先に行う（組織 DEPUTY_ADMIN・MEMBER・他組織の ADMIN・
 * SYSTEM_ADMIN・チーム側の加盟操作者は 403。§3.1）。チーム側の取下げ（{@link TeamOrgAffiliationService}）や
 * 招待（2-C）とはメソッドを分けて置く。</p>
 */
@Service
@RequiredArgsConstructor
public class OrgTeamApplicationReviewService {

    /** 拒否の理由の上限（コードポイント数。§10.5）。 */
    static final int REASON_MAX_CODE_POINTS = 500;

    private final OrgTeamApplicationReviewCommandService commandService;
    private final TeamOrgAffiliationAssembler assembler;
    private final TeamOrgMembershipRepository membershipRepository;

    /**
     * 組織が受信した加盟申請（PENDING / TEAM_APPLY）を、申請日時の降順で返す（§10.6）。
     *
     * @param teamGroupId 希望グループで絞り込む（任意）
     * @param page        0 始まりのページ番号
     * @param size        1ページの件数（既定 20・上限 100）
     */
    public PagedResponse<TeamOrgAffiliationResponse> listApplications(Long organizationId, UUID teamGroupId,
                                                                      int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size < 1
                ? TeamOrgAffiliationService.DEFAULT_PAGE_SIZE
                : Math.min(size, TeamOrgAffiliationService.MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(safePage, safeSize);
        Page<TeamOrgMembershipEntity> result = teamGroupId == null
                ? membershipRepository.findPageByOrganizationIdAndStatusAndDirection(organizationId,
                        TeamOrgMembershipEntity.Status.PENDING, TeamOrgAffiliationDirection.TEAM_APPLY, pageable)
                : membershipRepository.findPageByOrganizationIdAndStatusAndDirectionAndGroupId(organizationId,
                        TeamOrgMembershipEntity.Status.PENDING, TeamOrgAffiliationDirection.TEAM_APPLY,
                        teamGroupId, pageable);
        List<TeamOrgAffiliationResponse> data = assembler.assembleForOrganization(result.getContent());
        return PagedResponse.of(data, new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages()));
    }

    /**
     * 加盟申請を承認し、ACTIVE になった加盟の共通表現を返す（§6.2）。
     *
     * @param organizationId 組織（操作者が ADMIN であることを確認済み）
     * @param operatorUserId 操作者
     */
    public TeamOrgAffiliationResponse approve(Long organizationId, Long operatorUserId, Long membershipId,
                                              ApproveTeamApplicationRequest request) {
        if (request == null || request.overrideGroup() == null) {
            throw new BusinessException(CommonErrorCode.COMMON_001, List.of(new ErrorResponse.FieldError(
                    "overrideGroup", "希望グループのまま承認するか（false）、グループを指定するか（true）を指定してください")));
        }
        Long teamId = locateTeamId(organizationId, membershipId);

        OrgTeamApplicationReviewCommandService.ApprovedApplication approved = commandService.approve(
                organizationId, teamId, membershipId, operatorUserId,
                request.overrideGroup(), request.groupId());

        // コミット後に行を取り直さない（その間に除名・離脱で消えると 500 になる）。確定した値から組み立てる
        TeamOrgMembershipEntity snapshot = TeamOrgMembershipEntity.builder()
                .id(approved.id())
                .teamId(approved.teamId())
                .organizationId(approved.organizationId())
                .status(TeamOrgMembershipEntity.Status.ACTIVE)
                .direction(TeamOrgAffiliationDirection.TEAM_APPLY)
                .groupId(approved.groupId())
                .invitedBy(approved.invitedBy())
                .invitedAt(LocalDateTime.ofInstant(approved.invitedAt(), UserZoneLocalDateTimeParser.SERVER_ZONE))
                .respondedBy(approved.respondedBy())
                .respondedAt(LocalDateTime.ofInstant(approved.respondedAt(), UserZoneLocalDateTimeParser.SERVER_ZONE))
                .build();
        return assembler.assembleForTeam(approved.teamId(), List.of(snapshot)).get(0);
    }

    /**
     * 加盟申請を拒否し、記録した再申請の制限を返す（§6.3）。
     *
     * @param organizationId 組織（操作者が ADMIN であることを確認済み）
     * @param operatorUserId 操作者
     */
    public RejectTeamApplicationResponse reject(Long organizationId, Long operatorUserId, Long membershipId,
                                                RejectTeamApplicationRequest request) {
        String reason = normalizeReason(request == null ? null : request.reason());
        boolean block = request != null && Boolean.TRUE.equals(request.block());
        Long teamId = locateTeamId(organizationId, membershipId);

        TeamOrgAffiliationRestrictionService.RestrictionView restriction =
                commandService.reject(organizationId, teamId, membershipId, operatorUserId, reason, block);
        return new RejectTeamApplicationResponse(new RejectTeamApplicationResponse.ApplicationRestriction(
                restriction.kind().name(),
                restriction.restrictedUntil() == null
                        ? null
                        : restriction.restrictedUntil().atZone(UserZoneLocalDateTimeParser.SERVER_ZONE)
                                .toOffsetDateTime()));
    }

    /**
     * 加盟行を {@code (id, organization_id)} の組で引き、チーム ID を返す。引けなければ 404 {@code TEAM_070}。
     * 状態（PENDING か・向き）はここでは見ない（ロックの内側で判定表に従う）。
     */
    private Long locateTeamId(Long organizationId, Long membershipId) {
        return membershipRepository.findByIdAndOrganizationId(membershipId, organizationId)
                .map(TeamOrgMembershipEntity::getTeamId)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_070));
    }

    /**
     * 理由を検証・正規化する。空文字（空白のみを含む）は null に正規化し、500コードポイントを超えたら 400。
     */
    private static String normalizeReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.codePointCount(0, raw.length()) > REASON_MAX_CODE_POINTS) {
            throw new BusinessException(CommonErrorCode.COMMON_001, List.of(new ErrorResponse.FieldError(
                    "reason", "理由は" + REASON_MAX_CODE_POINTS + "文字以内で入力してください")));
        }
        return raw;
    }
}
