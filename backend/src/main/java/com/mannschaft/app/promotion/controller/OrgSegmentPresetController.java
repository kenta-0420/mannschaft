package com.mannschaft.app.promotion.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.promotion.dto.CreateSegmentPresetRequest;
import com.mannschaft.app.promotion.dto.SegmentPresetResponse;
import com.mannschaft.app.promotion.service.SegmentPresetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 組織セグメントプリセットコントローラー。
 */
@RestController
@Tag(name = "セグメントプリセット（組織）", description = "F09.2 組織セグメントプリセットCRUD")
@RequiredArgsConstructor
public class OrgSegmentPresetController {

    private final SegmentPresetService presetService;

    @GetMapping("/api/v1/organizations/{orgId}/segment-presets")
    @Operation(summary = "プリセット一覧")
    public ResponseEntity<ApiResponse<List<SegmentPresetResponse>>> list(@PathVariable Long orgId) {
        return ResponseEntity.ok(ApiResponse.of(presetService.list("ORGANIZATION", orgId)));
    }

    @PostMapping("/api/v1/organizations/{orgId}/segment-presets")
    @Operation(summary = "プリセット作成")
    public ResponseEntity<ApiResponse<SegmentPresetResponse>> create(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateSegmentPresetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.of(presetService.create("ORGANIZATION", orgId, SecurityUtils.getCurrentUserId(), request)));
    }

    @PutMapping("/api/v1/organizations/{orgId}/segment-presets/{id}")
    @Operation(summary = "プリセット更新")
    public ResponseEntity<ApiResponse<SegmentPresetResponse>> update(
            @PathVariable Long orgId, @PathVariable Long id,
            @Valid @RequestBody CreateSegmentPresetRequest request) {
        return ResponseEntity.ok(ApiResponse.of(presetService.update("ORGANIZATION", orgId, id, request)));
    }

    @DeleteMapping("/api/v1/organizations/{orgId}/segment-presets/{id}")
    @Operation(summary = "プリセット削除")
    public ResponseEntity<Void> delete(@PathVariable Long orgId, @PathVariable Long id) {
        presetService.delete("ORGANIZATION", orgId, id);
        return ResponseEntity.noContent().build();
    }
}
