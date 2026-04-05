package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.ApiResponse;
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

    /**
     * 行事カテゴリを更新する。
     */
    @PatchMapping("/{categoryId}")
    @Operation(summary = "行事カテゴリ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<EventCategoryResponse>> updateCategory(
            @PathVariable Long categoryId,
            @Valid @RequestBody UpdateEventCategoryRequest request) {
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
     * 行事カテゴリを削除する。
     */
    @DeleteMapping("/{categoryId}")
    @Operation(summary = "行事カテゴリ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteCategory(
            @PathVariable Long categoryId) {
        categoryService.deleteCategory(categoryId);
        return ResponseEntity.noContent().build();
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
