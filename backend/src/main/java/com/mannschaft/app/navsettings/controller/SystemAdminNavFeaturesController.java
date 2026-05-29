package com.mannschaft.app.navsettings.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.navsettings.dto.NavFeatureAdminResponse;
import com.mannschaft.app.navsettings.dto.NavFeatureCreateRequest;
import com.mannschaft.app.navsettings.dto.NavFeatureUpdateRequest;
import com.mannschaft.app.navsettings.service.SystemAdminNavFeaturesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/system-admin/nav-features")
@Tag(name = "システム管理 - ナビ機能管理", description = "F20.1 ナビゲーション項目マスタ管理（SYSTEM_ADMIN専用）")
@RequiredArgsConstructor
public class SystemAdminNavFeaturesController {

    private final SystemAdminNavFeaturesService service;

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "ナビ項目一覧", description = "全ナビ項目マスタを返す（is_enabled問わず全件）。sort_order昇順。")
    public ResponseEntity<ApiResponse<List<NavFeatureAdminResponse>>> listAll() {
        return ResponseEntity.ok(ApiResponse.of(service.listAll()));
    }

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "ナビ項目追加", description = "新しいナビ項目を追加する。key は ^[a-z0-9\\-]+$ のみ可。")
    public ResponseEntity<ApiResponse<NavFeatureAdminResponse>> create(
            @Valid @RequestBody NavFeatureCreateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(service.create(request, actorUserId)));
    }

    @PutMapping("/{key}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "ナビ項目更新", description = "ナビ項目を更新する。is_fixed=TRUEの項目のfixedをFALSEには変更不可。")
    public ResponseEntity<ApiResponse<NavFeatureAdminResponse>> update(
            @PathVariable String key,
            @Valid @RequestBody NavFeatureUpdateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(service.update(key, request, actorUserId)));
    }

    @DeleteMapping("/{key}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "ナビ項目削除", description = "ナビ項目を削除する。is_fixed=TRUEの項目は削除不可。")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        service.delete(key, actorUserId);
        return ResponseEntity.noContent().build();
    }
}
