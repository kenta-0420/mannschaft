package com.mannschaft.app.organization.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamOrgMembershipRemovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 組織による加盟チームの除名（F01.2.1 §6.6・§10.1）。
 *
 * <p><b>認可（§3.1）</b>: <b>組織 ADMIN のみ</b>（DEPUTY_ADMIN・MEMBER・他組織の ADMIN、チーム側で加盟操作権限を
 * 持つだけの人は 403。AC-P08）。認可の順序は 認証（401）→ 組織の存在（404）→ 権限（403）→ 状態（404/409）。
 * 認可判定はエンドポイント本体で {@link AccessControlService} を直接呼ぶ。加盟していないチームの slug・存在しない slug は
 * 同じ 404 {@code TEAM_070}（AC-E03）。理由欄は無い（body なし。§6.6）。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{slug}/teams")
@Tag(name = "組織の加盟チーム（除名）")
@RequiredArgsConstructor
public class OrgTeamMembershipRemovalController {

    private static final String SCOPE_TYPE = "ORGANIZATION";

    private final OrganizationService organizationService;
    private final AccessControlService accessControlService;
    private final TeamOrgMembershipRemovalService removalService;

    @DeleteMapping("/{teamSlug}")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "加盟チームの除名は中核の所属管理機能として常時提供する")
    @Operation(summary = "加盟チームの除名（組織 ADMIN）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "除名成功（加盟の行を削除）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "組織 ADMIN ではない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "TEAM_070（加盟していないチーム・存在しないチームの slug・除名済みは同じ応答）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "TEAM_071（加盟が成立していない＝申請・招待の処理中）")
    public ResponseEntity<Void> remove(
            @PathVariable String slug,
            @PathVariable("teamSlug") String teamSlug) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(slug);
        if (!accessControlService.isAdmin(userId, orgId, SCOPE_TYPE)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        removalService.remove(orgId, userId, teamSlug);
        return ResponseEntity.noContent().build();
    }
}
