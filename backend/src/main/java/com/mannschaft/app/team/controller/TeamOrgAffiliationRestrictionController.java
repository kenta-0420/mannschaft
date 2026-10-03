package com.mannschaft.app.team.controller;

import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.config.TeamScopeId;
import com.mannschaft.app.team.dto.TeamOrgAffiliationRestrictionResponse;
import com.mannschaft.app.team.service.TeamAffiliationAccessGuard;
import com.mannschaft.app.team.service.TeamOrgAffiliationRestrictionAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * チームが止めている組織からの招待（辞退による冷却・ブロック）の一覧と解除（F01.2.1 §5.4・§10.1）。
 *
 * <p>2本とも、操作者が当該チームの加盟操作者であることを {@link TeamAffiliationAccessGuard} で先に要求する。
 * 一覧に出るのはチームが辞退で止めた招待（{@code ORG_INVITE}・{@code DECLINED}）だけで、解除もその行に限る
 * （組織自身の取消で止まった招待や、組織が止めた申請は解除できない）。他チームの制限 ID・存在しない ID は同じ 404（AC-G137）。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamSlug}/org-affiliation-restrictions")
@Tag(name = "チームの加盟招待の制限")
@RequiredArgsConstructor
public class TeamOrgAffiliationRestrictionController {

    private final TeamAffiliationAccessGuard accessGuard;
    private final TeamOrgAffiliationRestrictionAdminService restrictionAdminService;

    @GetMapping
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "チームが止めている加盟招待の確認は中核の所属管理機能として常時提供する")
    @Operation(summary = "チームが止めている招待の一覧（チームの加盟操作者）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功（記録日時の降順）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "加盟操作権限なし")
    public ResponseEntity<PagedResponse<TeamOrgAffiliationRestrictionResponse>> list(
            @PathVariable("teamSlug") TeamScopeId teamId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        accessGuard.requireOperator(SecurityUtils.getCurrentUserId(), teamId.value());
        return ResponseEntity.ok(restrictionAdminService.listByTeam(teamId.value(), page, size));
    }

    @DeleteMapping("/{restrictionId}")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "チームが止めた加盟招待の解除は中核の所属管理機能として常時提供する")
    @Operation(summary = "チームが止めている招待の解除（チームの加盟操作者）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "解除成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "加盟操作権限なし")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "COMMON_005（制限が無い。他チームの ID・解除できない種類の行も同じ応答）")
    public ResponseEntity<Void> lift(
            @PathVariable("teamSlug") TeamScopeId teamId,
            @PathVariable("restrictionId") UUID restrictionId) {
        accessGuard.requireOperator(SecurityUtils.getCurrentUserId(), teamId.value());
        restrictionAdminService.liftByTeam(teamId.value(), restrictionId);
        return ResponseEntity.noContent().build();
    }
}
