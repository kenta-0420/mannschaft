package com.mannschaft.app.todo.controller;

import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.todo.ProjectStatus;
import com.mannschaft.app.todo.dto.TeamProjectSummaryResponse;
import com.mannschaft.app.todo.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * マイページ チームプロジェクト集約コントローラー。
 *
 * <p>{@code GET /api/v1/me/team-projects} を提供する。ログインユーザーが所属する全チームの
 * プロジェクトを、チーム名 / slug 付きで 1 リクエストで返す（{@link TeamProjectSummaryResponse}）。</p>
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "マイページ")
@RequiredArgsConstructor
public class MyTeamProjectController {

    private final ProjectService projectService;

    /**
     * 自分が所属する全チームのプロジェクトを集約取得する。
     */
    @GetMapping("/team-projects")
    @Operation(summary = "所属チームのプロジェクト集約")
    public ResponseEntity<PagedResponse<TeamProjectSummaryResponse>> listMyTeamProjects(
            @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(projectService.listTeamProjectsForUser(
                userId, ProjectStatus.valueOf(status), page, size));
    }
}
