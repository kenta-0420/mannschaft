package com.mannschaft.app.family.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.family.dto.CoinTossRequest;
import com.mannschaft.app.family.dto.CoinTossResponse;
import com.mannschaft.app.family.service.CoinTossService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * コイントスコントローラー。ランダム決定機能のAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/coin-toss")
@Tag(name = "コイントス", description = "F01.4 困ったらコイントス")
@RequiredArgsConstructor
public class CoinTossController {

    private final CoinTossService coinTossService;


    /**
     * コイントスを実行する。
     */
    @PostMapping
    @Operation(summary = "コイントス実行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "実行成功")
    public ResponseEntity<ApiResponse<CoinTossResponse>> toss(
            @PathVariable Long teamId,
            @Valid @RequestBody CoinTossRequest request) {
        return ResponseEntity.ok(coinTossService.toss(teamId, SecurityUtils.getCurrentUserId(), request));
    }

    /**
     * コイントス結果をチャットに共有する。
     */
    @PostMapping("/{id}/share")
    @Operation(summary = "コイントス結果をチャットに共有")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "共有成功")
    public ResponseEntity<ApiResponse<CoinTossResponse>> share(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        return ResponseEntity.ok(coinTossService.share(teamId, id, SecurityUtils.getCurrentUserId()));
    }

    /**
     * コイントス履歴を取得する。
     */
    @GetMapping("/history")
    @Operation(summary = "コイントス履歴")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<CursorPagedResponse<CoinTossResponse>> getHistory(
            @PathVariable Long teamId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(coinTossService.getHistory(teamId, cursor, limit));
    }
}
