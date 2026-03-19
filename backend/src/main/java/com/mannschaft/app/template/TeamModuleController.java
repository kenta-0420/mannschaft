package com.mannschaft.app.template;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.template.dto.TeamModuleResponse;
import com.mannschaft.app.template.dto.ToggleModuleRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * チームモジュール管理コントローラー。チーム単位のモジュール有効化・テンプレート適用を提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/modules")
@Tag(name = "チームモジュール管理")
@RequiredArgsConstructor
public class TeamModuleController {

    private final ModuleService moduleService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * チームの有効モジュール一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "チームモジュール一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TeamModuleResponse>>> getTeamModules(
            @PathVariable Long teamId) {
        return ResponseEntity.ok(ApiResponse.of(moduleService.getTeamModules(teamId)));
    }

    /**
     * チームのモジュール有効/無効を切り替える。
     */
    @PatchMapping("/{moduleId}/toggle")
    @Operation(summary = "モジュール有効/無効切替")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "切替成功")
    public ResponseEntity<Void> toggleTeamModule(
            @PathVariable Long teamId,
            @PathVariable Long moduleId,
            @Valid @RequestBody ToggleModuleRequest request) {
        moduleService.toggleTeamModule(teamId, request, getCurrentUserId());
        return ResponseEntity.ok().build();
    }

    /**
     * テンプレートの推奨モジュールをチームに自動適用する。
     */
    @PutMapping("/template")
    @Operation(summary = "テンプレート適用")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "適用成功")
    public ResponseEntity<Void> applyTemplate(
            @PathVariable Long teamId,
            @RequestParam Long templateId) {
        moduleService.applyTemplate(teamId, templateId, getCurrentUserId());
        return ResponseEntity.ok().build();
    }
}
