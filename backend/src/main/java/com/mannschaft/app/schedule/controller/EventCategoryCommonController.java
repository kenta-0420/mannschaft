package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.schedule.dto.EventCategoryResponse;
import com.mannschaft.app.schedule.dto.UpdateEventCategoryRequest;
import com.mannschaft.app.schedule.entity.ScheduleEventCategoryEntity;
import com.mannschaft.app.schedule.service.ScheduleEventCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行事カテゴリ共通コントローラー。スコープ共通の更新・削除APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/event-categories")
@Tag(name = "行事カテゴリ共通管理", description = "F03.10 スコープ共通の行事カテゴリ更新・削除")
@RequiredArgsConstructor
public class EventCategoryCommonController {

    private final ScheduleEventCategoryService categoryService;
    private final AccessControlService accessControlService;

    /**
     * 行事カテゴリを更新する。当該カテゴリが属するスコープの ADMIN/DEPUTY_ADMIN のみ実行可能。
     */
    @PatchMapping("/{categoryId}")
    @Operation(summary = "行事カテゴリ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<EventCategoryResponse>> updateCategory(
            @PathVariable Long categoryId,
            @Valid @RequestBody UpdateEventCategoryRequest request) {
        checkCategoryScopeAdminAccess(categoryId);
        ScheduleEventCategoryService.UpdateCategoryData data =
                new ScheduleEventCategoryService.UpdateCategoryData(
                        request.getName(),
                        request.getColor(),
                        request.getIcon(),
                        request.getIsDayOffCategory(),
                        request.getSortOrder());
        ScheduleEventCategoryEntity entity = categoryService.updateCategory(categoryId, data);
        return ResponseEntity.ok(ApiResponse.of(toResponse(entity)));
    }

    /**
     * 行事カテゴリを削除する。当該カテゴリが属するスコープの ADMIN/DEPUTY_ADMIN のみ実行可能。
     */
    @DeleteMapping("/{categoryId}")
    @Operation(summary = "行事カテゴリ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteCategory(
            @PathVariable Long categoryId) {
        checkCategoryScopeAdminAccess(categoryId);
        categoryService.deleteCategory(categoryId);
        return ResponseEntity.noContent().build();
    }

    /**
     * カテゴリ実体から解決したスコープに対する ADMIN 以上の権限を要求する。
     *
     * <p>path 由来ではなくカテゴリ実体（{@code team_id} / {@code organization_id}）由来の
     * スコープで判定することで、他テナントのカテゴリIDを指定した越境操作を封じる。
     * 判定水準は同ドメインの {@code TeamEventCategoryController#createCategory} /
     * {@code OrgEventCategoryController#createCategory}（ADMIN/DEPUTY_ADMIN 必須）に揃える。</p>
     *
     * @param categoryId カテゴリID
     * @throws com.mannschaft.app.common.BusinessException カテゴリが存在しない場合 / 権限がない場合（COMMON_002）
     */
    private void checkCategoryScopeAdminAccess(Long categoryId) {
        ScheduleEventCategoryEntity category = categoryService.getById(categoryId);
        Long userId = SecurityUtils.getCurrentUserId();
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (category.isTeamScope()) {
            accessControlService.checkAdminOrAbove(userId, category.getTeamId(), "TEAM");
        } else if (category.isOrganizationScope()) {
            accessControlService.checkAdminOrAbove(userId, category.getOrganizationId(), "ORGANIZATION");
        } else {
            // スコープを持たないカテゴリは判定不能のため fail-closed で拒否する
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
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
