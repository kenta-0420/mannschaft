package com.mannschaft.app.skill.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.skill.SkillMapper;
import com.mannschaft.app.skill.dto.CreateSkillCategoryRequest;
import com.mannschaft.app.skill.dto.SkillCategoryResponse;
import com.mannschaft.app.skill.dto.UpdateSkillCategoryRequest;
import com.mannschaft.app.skill.service.SkillCategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * スキルカテゴリ管理コントローラー。
 * チームスコープのスキルカテゴリの一覧取得・作成・更新・削除を提供する。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SkillCategoryController {

    private final SkillCategoryService skillCategoryService;
    private final SkillMapper skillMapper;
    private final AccessControlService accessControlService;

    /**
     * スキルカテゴリ一覧を取得する。
     * 認可: MEMBER以上（全員）
     */
    @GetMapping("/teams/{teamId}/skill-categories")
    public ApiResponse<List<SkillCategoryResponse>> listCategories(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");
        return ApiResponse.of(
                skillMapper.toCategoryResponseList(
                        skillCategoryService.getCategories("TEAM", teamId, includeInactive).getData()
                )
        );
    }

    /**
     * スキルカテゴリを作成する。
     * 認可: ADMIN
     */
    @PostMapping("/teams/{teamId}/skill-categories")
    public ResponseEntity<ApiResponse<SkillCategoryResponse>> createCategory(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateSkillCategoryRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        SkillCategoryResponse response = skillMapper.toResponse(
                skillCategoryService.createCategory(
                        "TEAM", teamId, userId,
                        request.name(), request.description(), request.icon(), request.sortOrder()
                ).getData()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * スキルカテゴリを更新する。
     * 認可: ADMIN
     */
    @PutMapping("/teams/{teamId}/skill-categories/{id}")
    public ApiResponse<SkillCategoryResponse> updateCategory(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @RequestBody UpdateSkillCategoryRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        return ApiResponse.of(
                skillMapper.toResponse(
                        skillCategoryService.updateCategory(
                                id, "TEAM", teamId,
                                request.name(), request.description(), request.icon(),
                                request.sortOrder(), request.isActive()
                        ).getData()
                )
        );
    }

    /**
     * スキルカテゴリを論理削除する。
     * 認可: ADMIN
     */
    @DeleteMapping("/teams/{teamId}/skill-categories/{id}")
    public ResponseEntity<Void> deleteCategory(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        skillCategoryService.deleteCategory(id, "TEAM", teamId);
        return ResponseEntity.noContent().build();
    }
}
