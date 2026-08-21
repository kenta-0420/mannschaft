package com.mannschaft.app.returnstayplan.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.returnstayplan.service.ReturnStayPlanAccessGuard;
import com.mannschaft.app.returnstayplan.service.ReturnStayPlanService;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** TEAM member return/stay plan read API. */
@RestController
@Validated
@RequestMapping("/api/v1/teams/{slug}/members")
public class ReturnStayPlanTeamController {

    private final ReturnStayPlanService service;
    private final ReturnStayPlanAccessGuard accessGuard;

    public ReturnStayPlanTeamController(
            ReturnStayPlanService service, ReturnStayPlanAccessGuard accessGuard) {
        this.service = service;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/return-stay-plans")
    public ResponseEntity<ApiResponse<MemberPlanItems>> listForMembers(
            @PathVariable String slug,
            @RequestParam @Size(min = 1, max = 400) List<Long> memberIds) {
        Long viewerUserId = SecurityUtils.getCurrentUserId();
        accessGuard.requireAuthorizedTeamId(slug, viewerUserId);
        var plans = service.listVisiblePlansForMembers(viewerUserId, slug, memberIds);
        var items = memberIds.stream()
                .map(memberId -> new MemberPlanItem(memberId, plans.get(memberId)))
                .toList();
        return ResponseEntity.ok(ApiResponse.of(new MemberPlanItems(items)));
    }

    @GetMapping("/{memberId}/return-stay-plans")
    public ResponseEntity<ApiResponse<List<ReturnStayPlanService.TeamPlanView>>> listForMember(
            @PathVariable String slug,
            @PathVariable Long memberId) {
        Long viewerUserId = SecurityUtils.getCurrentUserId();
        accessGuard.requireAuthorizedTeamId(slug, viewerUserId);
        return ResponseEntity.ok(ApiResponse.of(
                service.listVisiblePlansForMember(viewerUserId, slug, memberId)));
    }

    public record MemberPlanItems(List<MemberPlanItem> items) { }

    public record MemberPlanItem(
            Long memberId, List<ReturnStayPlanService.TeamPlanView> plans) { }
}
