package com.mannschaft.app.bulletin.controller;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.ReadStatusResponse;
import com.mannschaft.app.bulletin.service.BulletinReadStatusService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 掲示板既読ステータスコントローラー。既読マーク・既読者一覧APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/read-status")
@Tag(name = "掲示板既読", description = "F05.1 掲示板既読管理")
@RequiredArgsConstructor
public class BulletinReadStatusController {

    private final BulletinReadStatusService readStatusService;


    /**
     * スレッドを既読にする。
     */
    @PostMapping
    @Operation(summary = "既読マーク")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "既読成功")
    public ResponseEntity<Void> markAsRead(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long threadId) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        readStatusService.markAsRead(type, scopeId, threadId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * 既読者一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "既読者一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ReadStatusResponse>>> listReadUsers(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long threadId) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        List<ReadStatusResponse> responses = readStatusService.listReadUsers(type, scopeId, threadId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }
}
