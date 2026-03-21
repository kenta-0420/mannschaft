package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.tournament.dto.CreatePromotionRequest;
import com.mannschaft.app.tournament.dto.PromotionPreviewResponse;
import com.mannschaft.app.tournament.dto.PromotionRecordResponse;
import com.mannschaft.app.tournament.service.PromotionService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 昇格・降格管理コントローラー。
 * 3 endpoints: POST execute, GET history, POST preview
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/promotions")
@Tag(name = "昇降格管理", description = "F08.7 昇格・降格管理")
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionService promotionService;

    private Long getCurrentUserId() {
        return 1L;
    }

    @PostMapping
    @Operation(summary = "昇降格実行")
    public ResponseEntity<ApiResponse<List<PromotionRecordResponse>>> executePromotions(
            @PathVariable Long orgId, @PathVariable Long tId,
            @Valid @RequestBody CreatePromotionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(promotionService.executePromotions(tId, getCurrentUserId(), request)));
    }

    @GetMapping
    @Operation(summary = "昇降格履歴")
    public ResponseEntity<ApiResponse<List<PromotionRecordResponse>>> getPromotionHistory(
            @PathVariable Long orgId, @PathVariable Long tId) {
        return ResponseEntity.ok(ApiResponse.of(promotionService.getPromotionHistory(tId)));
    }

    @PostMapping("/preview")
    @Operation(summary = "昇降格プレビュー")
    public ResponseEntity<ApiResponse<PromotionPreviewResponse>> getPromotionPreview(
            @PathVariable Long orgId, @PathVariable Long tId) {
        return ResponseEntity.ok(ApiResponse.of(promotionService.getPromotionPreview(tId)));
    }
}
