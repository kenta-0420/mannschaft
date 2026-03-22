package com.mannschaft.app.promotion.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.promotion.dto.AudienceEstimateResponse;
import com.mannschaft.app.promotion.dto.CreatePromotionRequest;
import com.mannschaft.app.promotion.dto.EstimateAudienceRequest;
import com.mannschaft.app.promotion.dto.PromotionResponse;
import com.mannschaft.app.promotion.dto.PromotionStatsResponse;
import com.mannschaft.app.promotion.dto.SchedulePromotionRequest;
import com.mannschaft.app.promotion.dto.UpdatePromotionRequest;
import com.mannschaft.app.promotion.service.PromotionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 組織プロモーション管理コントローラー。
 */
@RestController
@Tag(name = "プロモーション管理（組織）", description = "F09.2 組織プロモーションCRUD・配信管理")
@RequiredArgsConstructor
public class OrgPromotionController {

    private final PromotionService promotionService;

    private Long getCurrentUserId() {
        return 1L;
    }

    @GetMapping("/api/v1/organizations/{orgId}/promotions")
    @Operation(summary = "プロモーション一覧")
    public ResponseEntity<PagedResponse<PromotionResponse>> list(
            @PathVariable Long orgId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PromotionResponse> result = promotionService.list("ORGANIZATION", orgId, status, PageRequest.of(page, Math.min(size, 50)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping("/api/v1/organizations/{orgId}/promotions")
    @Operation(summary = "プロモーション作成")
    public ResponseEntity<ApiResponse<PromotionResponse>> create(
            @PathVariable Long orgId,
            @Valid @RequestBody CreatePromotionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.of(promotionService.create("ORGANIZATION", orgId, getCurrentUserId(), request)));
    }

    @GetMapping("/api/v1/organizations/{orgId}/promotions/{id}")
    @Operation(summary = "プロモーション詳細")
    public ResponseEntity<ApiResponse<PromotionResponse>> get(
            @PathVariable Long orgId, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(promotionService.get("ORGANIZATION", orgId, id)));
    }

    @PutMapping("/api/v1/organizations/{orgId}/promotions/{id}")
    @Operation(summary = "プロモーション更新")
    public ResponseEntity<ApiResponse<PromotionResponse>> update(
            @PathVariable Long orgId, @PathVariable Long id,
            @Valid @RequestBody UpdatePromotionRequest request) {
        return ResponseEntity.ok(ApiResponse.of(promotionService.update("ORGANIZATION", orgId, id, request)));
    }

    @DeleteMapping("/api/v1/organizations/{orgId}/promotions/{id}")
    @Operation(summary = "プロモーション削除")
    public ResponseEntity<Void> delete(@PathVariable Long orgId, @PathVariable Long id) {
        promotionService.delete("ORGANIZATION", orgId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/organizations/{orgId}/promotions/{id}/publish")
    @Operation(summary = "即時配信")
    public ResponseEntity<ApiResponse<PromotionResponse>> publish(
            @PathVariable Long orgId, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(promotionService.publish("ORGANIZATION", orgId, id)));
    }

    @PostMapping("/api/v1/organizations/{orgId}/promotions/{id}/schedule")
    @Operation(summary = "予約配信")
    public ResponseEntity<ApiResponse<PromotionResponse>> schedule(
            @PathVariable Long orgId, @PathVariable Long id,
            @Valid @RequestBody SchedulePromotionRequest request) {
        return ResponseEntity.ok(ApiResponse.of(promotionService.schedule("ORGANIZATION", orgId, id, request)));
    }

    @PostMapping("/api/v1/organizations/{orgId}/promotions/{id}/cancel")
    @Operation(summary = "配信キャンセル")
    public ResponseEntity<ApiResponse<PromotionResponse>> cancel(
            @PathVariable Long orgId, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(promotionService.cancel("ORGANIZATION", orgId, id)));
    }

    @PostMapping("/api/v1/organizations/{orgId}/promotions/{id}/approve")
    @Operation(summary = "承認")
    public ResponseEntity<ApiResponse<PromotionResponse>> approve(
            @PathVariable Long orgId, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(promotionService.approve("ORGANIZATION", orgId, id, getCurrentUserId())));
    }

    @GetMapping("/api/v1/organizations/{orgId}/promotions/{id}/stats")
    @Operation(summary = "効果測定")
    public ResponseEntity<ApiResponse<PromotionStatsResponse>> getStats(
            @PathVariable Long orgId, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(promotionService.getStats("ORGANIZATION", orgId, id)));
    }

    @PostMapping("/api/v1/organizations/{orgId}/promotions/estimate-audience")
    @Operation(summary = "配信対象見積")
    public ResponseEntity<ApiResponse<AudienceEstimateResponse>> estimateAudience(
            @PathVariable Long orgId,
            @RequestBody EstimateAudienceRequest request) {
        return ResponseEntity.ok(ApiResponse.of(promotionService.estimateAudience("ORGANIZATION", orgId, request)));
    }
}
