package com.mannschaft.app.todo.controller;

import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.todo.ProjectStatus;
import com.mannschaft.app.todo.dto.OrgProjectSummaryResponse;
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
 * マイページ 組織プロジェクト集約コントローラー。
 *
 * <p>{@code GET /api/v1/me/org-projects} を提供する。ログインユーザーが所属する全組織の
 * プロジェクトを、組織名 / slug 付きで 1 リクエストで返す（{@link OrgProjectSummaryResponse}）。</p>
 *
 * <p>{@link MyTeamProjectController} の組織版（対称設計）。</p>
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "マイページ")
@RequiredArgsConstructor
public class MyOrgProjectController {

    private final ProjectService projectService;

    /**
     * 自分が所属する全組織のプロジェクトを集約取得する。
     */
    @GetMapping("/org-projects")
    @Operation(summary = "所属組織のプロジェクト集約")
    public ResponseEntity<PagedResponse<OrgProjectSummaryResponse>> listMyOrgProjects(
            @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(projectService.listOrgProjectsForUser(
                userId, ProjectStatus.valueOf(status), page, size));
    }
}
