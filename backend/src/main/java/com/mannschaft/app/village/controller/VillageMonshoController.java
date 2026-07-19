package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.village.dto.MonshoUploadUrlRequest;
import com.mannschaft.app.village.dto.MonshoUploadUrlResponse;
import com.mannschaft.app.village.dto.VillageMonshoResponse;
import com.mannschaft.app.village.dto.VillageMonshoUpdateRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.service.VillageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F17 Phase 2 U7 — 村紋（Monsho）Controller（設計書 §5.4 / §11.1 / §13.2）。
 *
 * <p>「紋」は村の象徴的シンボル（家紋・ロゴ）であり、{@code villages.monsho_r2_key} に
 * R2 オブジェクトキーを保持する。Phase 2 シンプル版では本 Controller は r2Key の DB 更新のみ担い、
 * R2 への実体アップロードは別経路（既存のプリサインド URL 発行 API 等）に委ねる。</p>
 *
 * <h2>エンドポイント一覧</h2>
 * <ul>
 *   <li>{@code PUT    /api/v1/villages/{villageId}/monsho} — 村紋設定（HEADMAN/SYSTEM_ADMIN）</li>
 *   <li>{@code DELETE /api/v1/villages/{villageId}/monsho} — 村紋削除（HEADMAN/SYSTEM_ADMIN）</li>
 * </ul>
 *
 * <p>権限検証は {@link VillageService#updateMonsho} / {@link VillageService#deleteMonsho} に集約。
 * 不足時は VILLAGE_024 MODERATION_FORBIDDEN を投げる。</p>
 */
@RestController
@RequestMapping("/api/v1/villages/{villageId}/monsho")
@Tag(name = "村紋 (F17.1 Phase 2)", description = "村の紋（家紋・ロゴ）の設定／削除")
@RequiredArgsConstructor
public class VillageMonshoController {

    private final VillageService villageService;

    @PostMapping("/upload-url")
    @Operation(summary = "村紋アップロード用 presigned PUT URL 発行（HEADMAN/SYSTEM_ADMIN）— #2355")
    public ResponseEntity<ApiResponse<MonshoUploadUrlResponse>> generateUploadUrl(
            @PathVariable UUID villageId,
            @Valid @RequestBody MonshoUploadUrlRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        MonshoUploadUrlResponse response = villageService.generateMonshoUploadUrl(
                villageId, request.contentType(), request.fileSize(), userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PutMapping
    @Operation(summary = "村紋設定（HEADMAN/SYSTEM_ADMIN）— 既に R2 にアップロード済みの r2Key を登録")
    public ResponseEntity<ApiResponse<VillageMonshoResponse>> update(
            @PathVariable UUID villageId,
            @Valid @RequestBody VillageMonshoUpdateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        VillageEntity updated = villageService.updateMonsho(villageId, request.r2Key(), userId);
        return ResponseEntity.ok(ApiResponse.of(VillageMonshoResponse.from(updated)));
    }

    @DeleteMapping
    @Operation(summary = "村紋削除（HEADMAN/SYSTEM_ADMIN）")
    public ResponseEntity<ApiResponse<VillageMonshoResponse>> delete(@PathVariable UUID villageId) {
        Long userId = SecurityUtils.getCurrentUserId();
        VillageEntity updated = villageService.deleteMonsho(villageId, userId);
        return ResponseEntity.ok(ApiResponse.of(VillageMonshoResponse.from(updated)));
    }
}
