package com.mannschaft.app.line.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.line.ScopeType;
import com.mannschaft.app.line.dto.CreateLineBotConfigRequest;
import com.mannschaft.app.line.dto.LineBotConfigResponse;
import com.mannschaft.app.line.dto.LineMessageLogResponse;
import com.mannschaft.app.line.dto.TestMessageRequest;
import com.mannschaft.app.line.dto.UpdateLineBotConfigRequest;
import com.mannschaft.app.line.service.LineBotConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * LINE BOT設定管理コントローラー（チーム・組織共用）。
 */
@RestController
@RequiredArgsConstructor
public class LineBotConfigController {

    private final LineBotConfigService lineBotConfigService;

    // ─── チーム ───

    /**
     * チームのBOT設定を作成する。
     */
    @PostMapping("/api/v1/teams/{teamId}/line/config")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LineBotConfigResponse> createForTeam(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateLineBotConfigRequest request) {
        // TODO: 認証情報からuserIdを取得
        Long userId = 0L;
        return ApiResponse.of(lineBotConfigService.create(ScopeType.TEAM, teamId, userId, request));
    }

    /**
     * チームのBOT設定を更新する。
     */
    @PutMapping("/api/v1/teams/{teamId}/line/config")
    public ApiResponse<LineBotConfigResponse> updateForTeam(
            @PathVariable Long teamId,
            @Valid @RequestBody UpdateLineBotConfigRequest request) {
        return ApiResponse.of(lineBotConfigService.update(ScopeType.TEAM, teamId, request));
    }

    /**
     * チームのBOT設定を取得する。
     */
    @GetMapping("/api/v1/teams/{teamId}/line/config")
    public ApiResponse<LineBotConfigResponse> getForTeam(@PathVariable Long teamId) {
        return ApiResponse.of(lineBotConfigService.getConfig(ScopeType.TEAM, teamId));
    }

    /**
     * チームのBOT設定を削除する。
     */
    @DeleteMapping("/api/v1/teams/{teamId}/line/config")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteForTeam(@PathVariable Long teamId) {
        lineBotConfigService.delete(ScopeType.TEAM, teamId);
    }

    /**
     * チームへテストメッセージを送信する。
     */
    @PostMapping("/api/v1/teams/{teamId}/line/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendTestForTeam(
            @PathVariable Long teamId,
            @Valid @RequestBody TestMessageRequest request) {
        lineBotConfigService.sendTestMessage(ScopeType.TEAM, teamId, request);
    }

    /**
     * チームのメッセージ履歴を取得する。
     */
    @GetMapping("/api/v1/teams/{teamId}/line/logs")
    public PagedResponse<LineMessageLogResponse> logsForTeam(
            @PathVariable Long teamId, Pageable pageable) {
        Page<LineMessageLogResponse> page =
                lineBotConfigService.getMessageLogs(ScopeType.TEAM, teamId, pageable);
        return PagedResponse.of(
                page.getContent(),
                new PagedResponse.PageMeta(
                        page.getTotalElements(), page.getNumber(),
                        page.getSize(), page.getTotalPages()
                )
        );
    }

    // ─── 組織 ───

    /**
     * 組織のBOT設定を作成する。
     */
    @PostMapping("/api/v1/organizations/{orgId}/line/config")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LineBotConfigResponse> createForOrg(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateLineBotConfigRequest request) {
        Long userId = 0L;
        return ApiResponse.of(
                lineBotConfigService.create(ScopeType.ORGANIZATION, orgId, userId, request));
    }

    /**
     * 組織のBOT設定を更新する。
     */
    @PutMapping("/api/v1/organizations/{orgId}/line/config")
    public ApiResponse<LineBotConfigResponse> updateForOrg(
            @PathVariable Long orgId,
            @Valid @RequestBody UpdateLineBotConfigRequest request) {
        return ApiResponse.of(
                lineBotConfigService.update(ScopeType.ORGANIZATION, orgId, request));
    }

    /**
     * 組織のBOT設定を取得する。
     */
    @GetMapping("/api/v1/organizations/{orgId}/line/config")
    public ApiResponse<LineBotConfigResponse> getForOrg(@PathVariable Long orgId) {
        return ApiResponse.of(lineBotConfigService.getConfig(ScopeType.ORGANIZATION, orgId));
    }

    /**
     * 組織のBOT設定を削除する。
     */
    @DeleteMapping("/api/v1/organizations/{orgId}/line/config")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteForOrg(@PathVariable Long orgId) {
        lineBotConfigService.delete(ScopeType.ORGANIZATION, orgId);
    }

    /**
     * 組織へテストメッセージを送信する。
     */
    @PostMapping("/api/v1/organizations/{orgId}/line/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendTestForOrg(
            @PathVariable Long orgId,
            @Valid @RequestBody TestMessageRequest request) {
        lineBotConfigService.sendTestMessage(ScopeType.ORGANIZATION, orgId, request);
    }

    /**
     * 組織のメッセージ履歴を取得する。
     */
    @GetMapping("/api/v1/organizations/{orgId}/line/logs")
    public PagedResponse<LineMessageLogResponse> logsForOrg(
            @PathVariable Long orgId, Pageable pageable) {
        Page<LineMessageLogResponse> page =
                lineBotConfigService.getMessageLogs(ScopeType.ORGANIZATION, orgId, pageable);
        return PagedResponse.of(
                page.getContent(),
                new PagedResponse.PageMeta(
                        page.getTotalElements(), page.getNumber(),
                        page.getSize(), page.getTotalPages()
                )
        );
    }
}
