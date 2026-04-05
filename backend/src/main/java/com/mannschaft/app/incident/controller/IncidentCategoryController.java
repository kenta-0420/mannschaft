package com.mannschaft.app.incident.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.incident.service.IncidentCategoryService;
import com.mannschaft.app.incident.service.IncidentCategoryService.CreateIncidentCategoryRequest;
import com.mannschaft.app.incident.service.IncidentCategoryService.IncidentCategoryResponse;
import com.mannschaft.app.incident.service.IncidentCategoryService.UpdateIncidentCategoryRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * インシデントカテゴリ管理コントローラー。
 * カテゴリの作成・一覧取得・更新・論理削除を提供する。
 */
@RestController
@RequestMapping("/api/incidents/categories")
@RequiredArgsConstructor
public class IncidentCategoryController {

    private final IncidentCategoryService incidentCategoryService;

    /**
     * インシデントカテゴリを作成する。
     * 認可: ADMIN 相当
     */
    @PostMapping
    public ResponseEntity<ApiResponse<IncidentCategoryResponse>> createCategory(
            @Validated @RequestBody CreateIncidentCategoryRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        IncidentCategoryResponse response = incidentCategoryService.createCategory(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * スコープに紐づくカテゴリ一覧を取得する。
     * 認可: MEMBER 以上
     */
    @GetMapping
    public ApiResponse<List<IncidentCategoryResponse>> listCategories(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        List<IncidentCategoryResponse> response =
                incidentCategoryService.listCategories(scopeType, scopeId);
        return ApiResponse.of(response);
    }

    /**
     * インシデントカテゴリを更新する。
     * 認可: ADMIN 相当
     */
    @PutMapping("/{id}")
    public ApiResponse<IncidentCategoryResponse> updateCategory(
            @PathVariable Long id,
            @Validated @RequestBody UpdateIncidentCategoryRequest request) {
        IncidentCategoryResponse response = incidentCategoryService.updateCategory(id, request);
        return ApiResponse.of(response);
    }

    /**
     * インシデントカテゴリを論理削除する。
     * 認可: ADMIN 相当
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable Long id) {
        incidentCategoryService.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }
}
