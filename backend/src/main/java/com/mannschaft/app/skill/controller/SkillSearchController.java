package com.mannschaft.app.skill.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.skill.SkillStatus;
import com.mannschaft.app.skill.service.SkillCsvService;
import com.mannschaft.app.skill.service.SkillSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * スキル検索・エクスポートコントローラー。
 * チームスコープのスキル検索とCSVエクスポートを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/skills")
@RequiredArgsConstructor
public class SkillSearchController {

    private final SkillSearchService skillSearchService;
    private final SkillCsvService skillCsvService;
    private final AccessControlService accessControlService;

    /**
     * スコープ内のメンバーをスキル条件で検索する。
     * 認可: MEMBER以上（全員）
     */
    @GetMapping("/search")
    public ApiResponse<List<Map<String, Object>>> searchSkills(
            @PathVariable Long teamId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) SkillStatus status,
            @RequestParam(defaultValue = "false") boolean includeExpired,
            @RequestParam(required = false) String keyword) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");
        return skillSearchService.searchMembers(
                "TEAM", teamId, categoryId, status, includeExpired, keyword);
    }

    /**
     * スコープ内の全資格データをCSVとして非同期エクスポートする。
     * 認可: ADMIN
     */
    @GetMapping("/export")
    public ResponseEntity<ApiResponse<Void>> exportCsv(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        skillCsvService.exportAsync("TEAM", teamId, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(null));
    }
}
