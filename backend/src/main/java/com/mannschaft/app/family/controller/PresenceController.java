package com.mannschaft.app.family.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.family.dto.PresenceBulkResponse;
import com.mannschaft.app.family.dto.PresenceEventResponse;
import com.mannschaft.app.family.dto.PresenceGoingOutRequest;
import com.mannschaft.app.family.dto.PresenceHomeRequest;
import com.mannschaft.app.family.dto.PresenceStatsResponse;
import com.mannschaft.app.family.dto.PresenceStatusResponse;
import com.mannschaft.app.family.service.PresenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * プレゼンスコントローラー。帰ったよ通知・お出かけ連絡APIを提供する。
 */
@RestController
@Tag(name = "プレゼンス", description = "F01.4 帰ったよ通知・お出かけ連絡")
@RequiredArgsConstructor
public class PresenceController {

    private final PresenceService presenceService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 帰ったよ通知を送信する（チーム指定）。
     */
    @PostMapping("/api/v1/teams/{teamId}/presence/home")
    @Operation(summary = "帰ったよ通知送信（チーム指定）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "送信成功")
    public ResponseEntity<ApiResponse<PresenceEventResponse>> sendHome(
            @PathVariable Long teamId,
            @Valid @RequestBody(required = false) PresenceHomeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(presenceService.sendHome(teamId, getCurrentUserId(), request));
    }

    /**
     * 帰ったよ通知を一括送信する（全所属チーム）。
     */
    @PostMapping("/api/v1/users/me/presence/home")
    @Operation(summary = "帰ったよ通知一括送信（全チーム）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "送信成功")
    public ResponseEntity<ApiResponse<PresenceBulkResponse>> sendHomeBulk() {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(presenceService.sendHomeBulk(getCurrentUserId()));
    }

    /**
     * お出かけ連絡を送信する（チーム指定）。
     */
    @PostMapping("/api/v1/teams/{teamId}/presence/going-out")
    @Operation(summary = "お出かけ連絡送信（チーム指定）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "送信成功")
    public ResponseEntity<ApiResponse<PresenceEventResponse>> sendGoingOut(
            @PathVariable Long teamId,
            @Valid @RequestBody PresenceGoingOutRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(presenceService.sendGoingOut(teamId, getCurrentUserId(), request));
    }

    /**
     * お出かけ連絡を一括送信する（全所属チーム）。
     */
    @PostMapping("/api/v1/users/me/presence/going-out")
    @Operation(summary = "お出かけ連絡一括送信（全チーム）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "送信成功")
    public ResponseEntity<ApiResponse<PresenceBulkResponse>> sendGoingOutBulk(
            @Valid @RequestBody PresenceGoingOutRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(presenceService.sendGoingOutBulk(getCurrentUserId(), request));
    }

    /**
     * チームメンバーの最新プレゼンスステータスを取得する。
     */
    @GetMapping("/api/v1/teams/{teamId}/presence/status")
    @Operation(summary = "プレゼンスステータス一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PresenceStatusResponse>>> getStatus(@PathVariable Long teamId) {
        return ResponseEntity.ok(presenceService.getStatus(teamId));
    }

    /**
     * プレゼンスイベント履歴を取得する。
     */
    @GetMapping("/api/v1/teams/{teamId}/presence/history")
    @Operation(summary = "プレゼンスイベント履歴")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<CursorPagedResponse<PresenceEventResponse>> getHistory(
            @PathVariable Long teamId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(presenceService.getHistory(teamId, userId, cursor, limit));
    }

    /**
     * プレゼンス統計を取得する（ADMIN用）。
     */
    @GetMapping("/api/v1/teams/{teamId}/presence/stats")
    @Operation(summary = "プレゼンス統計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<PresenceStatsResponse>> getStats(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "30d") String period) {
        return ResponseEntity.ok(presenceService.getStats(teamId, period));
    }
}
