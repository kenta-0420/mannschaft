package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.village.dto.VillageRecruitCategoryCreateRequest;
import com.mannschaft.app.village.dto.VillageRecruitCategoryOrderRequest;
import com.mannschaft.app.village.dto.VillageRecruitCategoryResponse;
import com.mannschaft.app.village.dto.VillageRecruitCategoryUpdateRequest;
import com.mannschaft.app.village.service.VillageRecruitCategoryService;
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
import java.util.UUID;

/**
 * 村ごと募集カテゴリマスタ コントローラー（F17.1 P2 / 設計書 §6.1）。
 *
 * <ul>
 *   <li>{@code GET  /api/v1/villages/{villageId}/recruit-categories}          一覧（村人）</li>
 *   <li>{@code POST /api/v1/villages/{villageId}/recruit-categories}          作成（村長/長老）</li>
 *   <li>{@code PUT  /api/v1/villages/{villageId}/recruit-categories/{id}}     更新（村長/長老）</li>
 *   <li>{@code DELETE /api/v1/villages/{villageId}/recruit-categories/{id}}   論理削除（村長/長老）</li>
 *   <li>{@code PUT  /api/v1/villages/{villageId}/recruit-categories/order}    一括並び替え（村長/長老）</li>
 * </ul>
 *
 * <p>審査権限（HEADMAN / ELDER・BAN 検査込み）の検証は Service 層が村ドメイン内の
 * membership を参照して行う。</p>
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "F17.1 村機能 - 募集カテゴリ", description = "村ごと募集カテゴリマスタの CRUD API")
@AuthorizedInService
public class VillageRecruitCategoryController {

    private final VillageRecruitCategoryService service;

    @GetMapping("/api/v1/villages/{villageId}/recruit-categories")
    @Operation(summary = "募集カテゴリ一覧（村人）")
    public ResponseEntity<ApiResponse<List<VillageRecruitCategoryResponse>>> list(
            @PathVariable("villageId") UUID villageId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        List<VillageRecruitCategoryResponse> result = service.list(villageId, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PostMapping("/api/v1/villages/{villageId}/recruit-categories")
    @Operation(summary = "募集カテゴリ作成（村長/長老）")
    public ResponseEntity<ApiResponse<VillageRecruitCategoryResponse>> create(
            @PathVariable("villageId") UUID villageId,
            @Valid @RequestBody VillageRecruitCategoryCreateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        VillageRecruitCategoryResponse response = service.create(villageId, actorUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @PutMapping("/api/v1/villages/{villageId}/recruit-categories/{categoryId}")
    @Operation(summary = "募集カテゴリ更新（村長/長老）")
    public ResponseEntity<ApiResponse<VillageRecruitCategoryResponse>> update(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("categoryId") UUID categoryId,
            @Valid @RequestBody VillageRecruitCategoryUpdateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        VillageRecruitCategoryResponse response = service.update(villageId, categoryId, actorUserId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @DeleteMapping("/api/v1/villages/{villageId}/recruit-categories/{categoryId}")
    @Operation(summary = "募集カテゴリ論理削除（村長/長老）")
    public ResponseEntity<Void> delete(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("categoryId") UUID categoryId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        service.delete(villageId, categoryId, actorUserId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/v1/villages/{villageId}/recruit-categories/order")
    @Operation(summary = "募集カテゴリ一括並び替え（村長/長老）")
    public ResponseEntity<ApiResponse<List<VillageRecruitCategoryResponse>>> reorder(
            @PathVariable("villageId") UUID villageId,
            @Valid @RequestBody VillageRecruitCategoryOrderRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        List<VillageRecruitCategoryResponse> response = service.reorder(villageId, actorUserId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
