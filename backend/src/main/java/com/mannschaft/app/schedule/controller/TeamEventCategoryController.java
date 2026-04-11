package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.schedule.dto.CreateEventCategoryRequest;
import com.mannschaft.app.schedule.dto.EventCategoryResponse;
import com.mannschaft.app.schedule.entity.ScheduleEventCategoryEntity;
import com.mannschaft.app.schedule.service.ScheduleEventCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * チーム行事カテゴリコントローラー。チームスコープのカテゴリ一覧取得・作成APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/event-categories")
@Tag(name = "チーム行事カテゴリ管理", description = "F03.10 チームスコープの行事カテゴリ管理")
@RequiredArgsConstructor
public class TeamEventCategoryController {

    private final ScheduleEventCategoryService categoryService;
    private final TeamOrgMembershipRepository teamOrgMembershipRepository;
    private final AccessControlService accessControlService;

    /**
     * チーム行事カテゴリ一覧を取得する（チーム固有 + 親組織カテゴリのマージ結果）。
     */
    @GetMapping
    @Operation(summary = "チーム行事カテゴリ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<EventCategoryResponse>>> listCategories(
            @PathVariable Long teamId) {
        Long organizationId = teamOrgMembershipRepository
                .findFirstByTeamIdAndStatus(teamId, TeamOrgMembershipEntity.Status.ACTIVE)
                .map(TeamOrgMembershipEntity::getOrganizationId)
                .orElse(null);
        List<ScheduleEventCategoryEntity> entities =
                categoryService.getCategoriesForTeam(teamId, organizationId);
        List<EventCategoryResponse> responses = entities.stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * チームスコープの行事カテゴリを作成する。ADMIN/DEPUTY_ADMIN のみ実行可能。
     */
    @PostMapping
    @Operation(summary = "チーム行事カテゴリ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<EventCategoryResponse>> createCategory(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateEventCategoryRequest request) {
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), teamId, "TEAM");
        ScheduleEventCategoryService.CreateCategoryData data =
                new ScheduleEventCategoryService.CreateCategoryData(
                        request.getName(),
                        request.getColor(),
                        request.getIcon(),
                        request.getIsDayOffCategory(),
                        request.getSortOrder());
        ScheduleEventCategoryEntity entity = categoryService.createTeamCategory(teamId, data);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(toResponse(entity)));
    }

    private EventCategoryResponse toResponse(ScheduleEventCategoryEntity entity) {
        String scope = entity.isTeamScope() ? "TEAM" : "ORGANIZATION";
        return new EventCategoryResponse(
                entity.getId(),
                entity.getName(),
                entity.getColor(),
                entity.getIcon(),
                entity.getIsDayOffCategory(),
                entity.getSortOrder(),
                scope);
    }
}
