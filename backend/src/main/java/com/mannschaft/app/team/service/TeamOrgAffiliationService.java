package com.mannschaft.app.team.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.team.dto.ApplyToOrganizationRequest;
import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * チーム側の加盟申請の入口（F01.2.1 §6.1・§6.4・§10.6）。申請・取下げ・申請中一覧。
 *
 * <p>本クラスは<b>トランザクションを持たない</b>。次の順に、トランザクションの外で済ませられることを済ませてから
 * 書き込みのトランザクション（{@link TeamOrgAffiliationCommandService}）に入り、応答の組み立て
 * （{@link TeamOrgAffiliationAssembler}）は書き込みの後にトランザクションの外で行う。</p>
 * <ol>
 *   <li>入力検証（400）</li>
 *   <li>組織の可視性の確認（見えない組織・存在しない slug・アーカイブ済みは同じ 404）</li>
 *   <li>書き込み（ロック → 状態の再確認 → 制限・重複・上限・グループの検証 → INSERT）</li>
 * </ol>
 *
 * <p>認可（チームの加盟操作者であること）は呼び出し元の Controller が {@link TeamAffiliationAccessGuard} で
 * 先に行う。ロックの最初の文より前に通常の SELECT を発行しない、という書き込み側の約束
 * （{@link TeamOrgAffiliationLockSupport}）を守るため、認可・可視性の SELECT は書き込みのトランザクションの外に置く。</p>
 */
@Service
@RequiredArgsConstructor
public class TeamOrgAffiliationService {

    /** 添え書きの上限（コードポイント数。§10.4）。 */
    static final int MESSAGE_MAX_CODE_POINTS = 500;
    /** 組織の slug の長さ（§10.4）。 */
    static final int SLUG_MIN_LENGTH = 3;
    static final int SLUG_MAX_LENGTH = 30;
    /** 一覧の1ページの既定件数と上限（§10）。 */
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final TeamAffiliationOrganizationPort organizationPort;
    private final TeamOrgAffiliationCommandService commandService;
    private final TeamOrgAffiliationAssembler assembler;
    private final TeamOrgMembershipRepository membershipRepository;

    /**
     * 組織へ加盟を申請し、作成した申請（PENDING / TEAM_APPLY）の共通表現を返す。
     *
     * @param teamId         申請するチーム（認可済み）
     * @param operatorUserId 操作者（加盟操作者であることを確認済み）
     */
    public TeamOrgAffiliationResponse apply(Long teamId, Long operatorUserId, ApplyToOrganizationRequest request) {
        String organizationSlug = validateOrganizationSlug(request);
        String message = normalizeMessage(request.message());

        // 見えない組織・存在しない slug・アーカイブ済みの組織は、区別できない同じ 404（ORG_001）にする
        Long organizationId = organizationPort.findVisibleOrganizationId(organizationSlug, operatorUserId)
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_001));

        TeamOrgAffiliationCommandService.AppliedApplication applied = commandService.apply(
                teamId, organizationId, operatorUserId, request.groupId(), message);

        // コミット後に行を取り直さない（その間に拒否・取下げで消えると 500 になる）。確定した値から組み立てる
        TeamOrgMembershipEntity snapshot = TeamOrgMembershipEntity.builder()
                .id(applied.id())
                .teamId(teamId)
                .organizationId(applied.organizationId())
                .status(TeamOrgMembershipEntity.Status.PENDING)
                .direction(TeamOrgAffiliationDirection.TEAM_APPLY)
                .groupId(applied.groupId())
                .message(applied.message())
                .invitedBy(applied.invitedBy())
                .invitedAt(applied.invitedAt())
                .build();
        return assembler.assembleForTeam(teamId, List.of(snapshot)).get(0);
    }

    /**
     * 加盟申請を取り下げる。他チームの ID・存在しない ID・削除済みは、同じ 404 {@code TEAM_070}。
     */
    public void withdraw(Long teamId, Long operatorUserId, Long membershipId) {
        commandService.withdraw(teamId, membershipId, operatorUserId);
    }

    /**
     * チームの申請中一覧（PENDING / TEAM_APPLY）を、申請日時の降順で返す。
     *
     * @param page 0 始まりのページ番号
     * @param size 1ページの件数（既定 20・上限 100）
     */
    public PagedResponse<TeamOrgAffiliationResponse> listApplications(Long teamId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Page<TeamOrgMembershipEntity> result = membershipRepository.findPageByTeamIdAndStatusAndDirection(
                teamId, TeamOrgMembershipEntity.Status.PENDING, TeamOrgAffiliationDirection.TEAM_APPLY,
                PageRequest.of(safePage, safeSize));
        List<TeamOrgAffiliationResponse> data = assembler.assembleForTeam(teamId, result.getContent());
        return PagedResponse.of(data, new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages()));
    }

    private static String validateOrganizationSlug(ApplyToOrganizationRequest request) {
        String slug = request == null || request.organizationSlug() == null ? "" : request.organizationSlug().trim();
        if (slug.length() < SLUG_MIN_LENGTH || slug.length() > SLUG_MAX_LENGTH) {
            throw new BusinessException(CommonErrorCode.COMMON_001, List.of(new ErrorResponse.FieldError(
                    "organizationSlug", "組織の slug は" + SLUG_MIN_LENGTH + "〜" + SLUG_MAX_LENGTH + "文字で指定してください")));
        }
        return slug;
    }

    /**
     * 添え書きを検証・正規化する。空文字（空白のみを含む）は null に正規化し、500コードポイントを超えたら 400。
     * 添え書きは PENDING の間だけ意味を持つ個人情報になりうるため、空の値を保存しない（§5.3・AC-G104）。
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
}
