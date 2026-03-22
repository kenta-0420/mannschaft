package com.mannschaft.app.receipt.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.receipt.ReceiptQueueStatus;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.dto.ApproveQueueRequest;
import com.mannschaft.app.receipt.dto.BulkApproveQueueRequest;
import com.mannschaft.app.receipt.dto.BulkResultResponse;
import com.mannschaft.app.receipt.dto.QueueItemResponse;
import com.mannschaft.app.receipt.dto.ReceiptResponse;
import com.mannschaft.app.receipt.service.ReceiptQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 領収書キューコントローラー。発行待ちキューの管理APIを提供する。
 * <p>
 * エンドポイント数: 4
 * <ul>
 *   <li>GET   /api/v1/admin/receipt-queue                — キュー一覧</li>
 *   <li>POST  /api/v1/admin/receipt-queue/{id}/approve   — キューアイテム承認</li>
 *   <li>POST  /api/v1/admin/receipt-queue/bulk-approve   — キュー一括承認</li>
 *   <li>PATCH /api/v1/admin/receipt-queue/{id}/skip      — キューアイテムスキップ</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/receipt-queue")
@Tag(name = "領収書キュー", description = "F08.4 領収書発行待ちキュー管理")
@RequiredArgsConstructor
public class ReceiptQueueController {

    private final ReceiptQueueService queueService;


    /**
     * 発行待ちキュー一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "キュー一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<QueueItemResponse>> listQueue(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        ReceiptQueueStatus queueStatus = status != null
                ? ReceiptQueueStatus.valueOf(status.toUpperCase()) : null;
        PagedResponse<QueueItemResponse> response = queueService.listQueue(type, scopeId, queueStatus, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * キューアイテムを承認して領収書を発行する。
     */
    @PostMapping("/{id}/approve")
    @Operation(summary = "キューアイテム承認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "承認・発行成功")
    public ResponseEntity<ApiResponse<ReceiptResponse>> approveQueueItem(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @PathVariable Long id,
            @Valid @RequestBody ApproveQueueRequest request) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        ReceiptResponse response = queueService.approveQueueItem(type, scopeId, id, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * キューアイテムを一括承認する。
     */
    @PostMapping("/bulk-approve")
    @Operation(summary = "キュー一括承認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "一括承認成功")
    public ResponseEntity<ApiResponse<BulkResultResponse>> bulkApproveQueue(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @Valid @RequestBody BulkApproveQueueRequest request) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        BulkResultResponse response = queueService.bulkApproveQueue(type, scopeId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * キューアイテムをスキップする。
     */
    @PatchMapping("/{id}/skip")
    @Operation(summary = "キューアイテムスキップ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "スキップ成功")
    public ResponseEntity<Void> skipQueueItem(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @PathVariable Long id) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        queueService.skipQueueItem(type, scopeId, id);
        return ResponseEntity.noContent().build();
    }
}
