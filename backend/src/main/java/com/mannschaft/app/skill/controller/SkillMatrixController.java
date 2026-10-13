package com.mannschaft.app.skill.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.skill.SkillMapper;
import com.mannschaft.app.skill.dto.SkillCategoryResponse;
import com.mannschaft.app.skill.dto.SkillMatrixResponse;
import com.mannschaft.app.skill.dto.SkillMatrixRowResponse;
import com.mannschaft.app.skill.service.SkillCategoryService;
import com.mannschaft.app.skill.service.SkillMatrixService;
import com.mannschaft.app.skill.service.SkillViewerAccessGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
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
    private final SkillViewerAccessGuard skillViewerAccessGuard;
    private final NameResolverService nameResolverService;

    /**
     * スキルマトリックスを取得する。
     * 認可: ADMIN・SYSTEM_ADMIN・MANAGE_MEMBER を持つ DEPUTY_ADMIN（F07.5 §2。CMP-261012-0313）
     *
     * <p>行はチームの正式メンバー（ADMIN / DEPUTY_ADMIN / MEMBER の在籍。SUPPORTER・GUEST・脱退者は除く）全員。
     * 行の userId と表示名は membership・auth ドメインの公開窓口で<b>一括</b>解決し（行数に依らずクエリ数一定）、
     * 資格ドメインの読み取り tx（{@link SkillMatrixService}）へは userId だけを渡す。
     * 他ドメインの参照を資格の tx の中へ持ち込まないためである。</p>
     */
    @GetMapping("/teams/{teamId}/skill-matrix")
    public ApiResponse<SkillMatrixResponse> getMatrix(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        skillViewerAccessGuard.checkCanViewTeamMemberSkills(userId, teamId);

        List<Long> memberUserIds = accessControlService.listActiveRegularMemberIds(teamId, "TEAM").stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        Map<Long, String> displayNames = nameResolverService.resolveUserDisplayNames(memberUserIds);

        Map<String, Object> matrixData = skillMatrixService.getMatrix("TEAM", teamId, memberUserIds).getData();

        // カテゴリ一覧（列定義）を SkillCategoryResponse に変換
        List<SkillCategoryResponse> categories = skillMapper.toCategoryResponseList(
                skillCategoryService.getCategories("TEAM", teamId, false).getData()
        );

        // 行データを SkillMatrixRowResponse に変換
        List<SkillMatrixRowResponse> rows = skillMapper.toMatrixRowResponseList(matrixData, displayNames);

        return ApiResponse.of(new SkillMatrixResponse(categories, rows));
    }
}
