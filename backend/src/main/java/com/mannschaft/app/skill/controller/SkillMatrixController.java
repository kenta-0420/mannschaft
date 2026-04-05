package com.mannschaft.app.skill.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.skill.SkillMapper;
import com.mannschaft.app.skill.dto.SkillCategoryResponse;
import com.mannschaft.app.skill.dto.SkillMatrixResponse;
import com.mannschaft.app.skill.dto.SkillMatrixRowResponse;
import com.mannschaft.app.skill.service.SkillCategoryService;
import com.mannschaft.app.skill.service.SkillMatrixService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * スキルマトリックスコントローラー。
 * チームスコープのスキルマトリックス（全メンバー × 全カテゴリ）を提供する。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SkillMatrixController {

    private final SkillMatrixService skillMatrixService;
    private final SkillCategoryService skillCategoryService;
    private final SkillMapper skillMapper;
    private final AccessControlService accessControlService;

    /**
     * スキルマトリックスを取得する。
     * 認可: MEMBER以上（全員）
     */
    @GetMapping("/teams/{teamId}/skill-matrix")
    public ApiResponse<SkillMatrixResponse> getMatrix(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");

        @SuppressWarnings("unchecked")
        Map<String, Object> matrixData = skillMatrixService.getMatrix("TEAM", teamId).getData();

        // カテゴリ一覧（列定義）を SkillCategoryResponse に変換
        List<SkillCategoryResponse> categories = skillMapper.toCategoryResponseList(
                skillCategoryService.getCategories("TEAM", teamId, false).getData()
        );

        // 行データを SkillMatrixRowResponse に変換
        List<SkillMatrixRowResponse> rows = skillMapper.toMatrixRowResponseList(matrixData);

        return ApiResponse.of(new SkillMatrixResponse(categories, rows));
    }
}
