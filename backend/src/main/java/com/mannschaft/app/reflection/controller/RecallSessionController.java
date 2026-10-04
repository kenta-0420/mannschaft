package com.mannschaft.app.reflection.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.common.security.PrivateSelfAccessGuard;
import com.mannschaft.app.reflection.dto.RecallSessionResponse;
import com.mannschaft.app.reflection.service.RecallSessionOperationFacade;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 本人の新想起だけを扱う入口。管理者変身を拒否し、現在ACTIVEと所有SQLをサービスで照合する。 */
@RestController
@RequestMapping("/api/v1/me/reflections")
@RequiredArgsConstructor
public class RecallSessionController {
    private final RecallSessionOperationFacade operations;
    private final PrivateSelfAccessGuard access;

    @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "本人の基礎的な私的想起開始を牧場公開状態から独立させる")
    @PostMapping("/entries/{entryId}/recall-sessions")
    public ResponseEntity<ApiResponse<RecallSessionResponse>> start(@PathVariable UUID entryId,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody JsonNode body,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = access.requireSelfAccess(request, response);
        return ResponseEntity.status(201).body(ApiResponse.of(operations.start(userId, entryId, key, body)));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "本人の私的想起再開と保存済み原文閲覧を牧場状態から独立させる")
    @GetMapping("/recall-sessions/{sessionId}")
    public ResponseEntity<ApiResponse<RecallSessionResponse>> get(@PathVariable UUID sessionId,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = access.requireSelfAccess(request, response);
        return ResponseEntity.ok(ApiResponse.of(operations.get(userId, sessionId)));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "本人の私的想起途中回答保存は牧場参加や公開状態を必要としない")
    @PutMapping("/recall-sessions/{sessionId}/answers")
    public ResponseEntity<ApiResponse<RecallSessionResponse>> answers(@PathVariable UUID sessionId,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody JsonNode body,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = access.requireSelfAccess(request, response);
        return ResponseEntity.ok(ApiResponse.of(operations.answers(userId, sessionId, key, body)));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "本人の基礎的な私的想起完了を報酬や牧場公開状態から独立させる")
    @PostMapping("/recall-sessions/{sessionId}/complete")
    public ResponseEntity<ApiResponse<RecallSessionResponse>> complete(@PathVariable UUID sessionId,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody JsonNode body,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = access.requireSelfAccess(request, response);
        return ResponseEntity.ok(ApiResponse.of(operations.complete(userId, sessionId, key, body)));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "本人の私的想起取消は牧場公開状態を必要としない")
    @PostMapping("/recall-sessions/{sessionId}/cancel")
    public ResponseEntity<ApiResponse<RecallSessionResponse>> cancel(@PathVariable UUID sessionId,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody JsonNode body,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = access.requireSelfAccess(request, response);
        return ResponseEntity.ok(ApiResponse.of(operations.cancel(userId, sessionId, key, body)));
    }
}
