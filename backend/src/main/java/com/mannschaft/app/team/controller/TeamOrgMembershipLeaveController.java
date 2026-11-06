package com.mannschaft.app.team.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.config.TeamScopeId;
import com.mannschaft.app.team.service.TeamAffiliationAccessGuard;
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
 * チームの組織からの離脱（F01.2.1 §6.6・§10.1）。
 *
 * <p>操作者が当該チームの加盟操作者（{@code MANAGE_ORG_AFFILIATION}。チーム ADMIN は常に含む。§3.2）であることを
 * {@link TeamAffiliationAccessGuard} で先に要求する（TD・TM は付与されていなければ 403。組織の ADMIN であっても
 * チームで権限を持たなければ 403。AC-P02）。加盟していない組織の slug・存在しない slug は同じ 404 {@code TEAM_070}
 * （AC-E03）。理由欄は無い（body なし。§6.6）。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamSlug}/organizations")
@Tag(name = "チームの加盟（離脱）")
@RequiredArgsConstructor
public class TeamOrgMembershipLeaveController {

    private final TeamAffiliationAccessGuard accessGuard;
    private final TeamOrgMembershipRemovalService removalService;

    @DeleteMapping("/{orgSlug}")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "加盟している組織からの離脱は中核の所属管理機能として常時提供する")
    @Operation(summary = "組織からの離脱（チームの加盟操作者）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "離脱成功（加盟の行を削除）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "加盟操作権限なし")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "TEAM_070（加盟していない組織・存在しない組織の slug・離脱済みは同じ応答）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "TEAM_071（加盟が成立していない＝申請・招待の処理中）")
    public ResponseEntity<Void> leave(
            @PathVariable("teamSlug") TeamScopeId teamId,
            @PathVariable("orgSlug") String orgSlug) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessGuard.requireOperator(userId, teamId.value());
        removalService.leave(teamId.value(), userId, orgSlug);
        return ResponseEntity.noContent().build();
    }
}
