package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
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
 * 組織行事カテゴリコントローラー。組織スコープのカテゴリ一覧取得・作成APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/event-categories")
@Tag(name = "組織行事カテゴリ管理", description = "F03.10 組織スコープの行事カテゴリ管理")
@RequiredArgsConstructor
public class OrgEventCategoryController {

    private final ScheduleEventCategoryService categoryService;
    private final AccessControlService accessControlService;

    /**
     * 組織行事カテゴリ一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "組織行事カテゴリ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<EventCategoryResponse>>> listCategories(
            @PathVariable Long orgId) {
        List<ScheduleEventCategoryEntity> entities =
                categoryService.getCategoriesForOrganization(orgId);
        List<EventCategoryResponse> responses = entities.stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 組織スコープの行事カテゴリを作成する。ADMIN/DEPUTY_ADMIN のみ実行可能。
     */
    @PostMapping
    @Operation(summary = "組織行事カテゴリ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<EventCategoryResponse>> createCategory(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateEventCategoryRequest request) {
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), orgId, "ORGANIZATION");
        ScheduleEventCategoryService.CreateCategoryData data =
                new ScheduleEventCategoryService.CreateCategoryData(
                        request.getName(),
                        request.getColor(),
                        request.getIcon(),
                        request.getIsDayOffCategory(),
                        request.getSortOrder());
        ScheduleEventCategoryEntity entity = categoryService.createOrgCategory(orgId, data);
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
