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

/**
 * チームセグメントプリセットコントローラー。
 */
@RestController
@Tag(name = "セグメントプリセット（チーム）", description = "F09.2 チームセグメントプリセットCRUD")
@RequiredArgsConstructor
public class TeamSegmentPresetController {

    private final SegmentPresetService presetService;

    private Long getCurrentUserId() {
        return 1L;
    }

    @GetMapping("/api/v1/teams/{teamId}/segment-presets")
    @Operation(summary = "プリセット一覧")
    public ResponseEntity<ApiResponse<List<SegmentPresetResponse>>> list(@PathVariable Long teamId) {
        return ResponseEntity.ok(ApiResponse.of(presetService.list("TEAM", teamId)));
    }

    @PostMapping("/api/v1/teams/{teamId}/segment-presets")
    @Operation(summary = "プリセット作成")
    public ResponseEntity<ApiResponse<SegmentPresetResponse>> create(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateSegmentPresetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.of(presetService.create("TEAM", teamId, getCurrentUserId(), request)));
    }

    @PutMapping("/api/v1/teams/{teamId}/segment-presets/{id}")
    @Operation(summary = "プリセット更新")
    public ResponseEntity<ApiResponse<SegmentPresetResponse>> update(
            @PathVariable Long teamId, @PathVariable Long id,
            @Valid @RequestBody CreateSegmentPresetRequest request) {
        return ResponseEntity.ok(ApiResponse.of(presetService.update("TEAM", teamId, id, request)));
    }

    @DeleteMapping("/api/v1/teams/{teamId}/segment-presets/{id}")
    @Operation(summary = "プリセット削除")
    public ResponseEntity<Void> delete(@PathVariable Long teamId, @PathVariable Long id) {
        presetService.delete("TEAM", teamId, id);
        return ResponseEntity.noContent().build();
    }
}
