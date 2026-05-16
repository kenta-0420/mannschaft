package com.mannschaft.app.pointcard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.pointcard.dto.CreateSynonymRequest;
import com.mannschaft.app.pointcard.dto.SynonymResponse;
import com.mannschaft.app.pointcard.dto.UpdateSynonymRequest;
import com.mannschaft.app.pointcard.service.AdminPointCardSynonymService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F18 Phase 4 第三陣 S3 — SystemAdmin 専用 同義語管理 API コントローラー。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §7.6
 *
 * <p>ベースパス: {@code /api/v1/admin/point-cards/synonyms}
 *
 * <h2>エンドポイント一覧</h2>
 * <ul>
 *   <li>GET    {@code /} — 同義語一覧（{@code ?providerId=...} 絞り込み可）</li>
 *   <li>POST   {@code /} — 新規登録</li>
 *   <li>PATCH  {@code /{id}} — 部分更新</li>
 *   <li>DELETE {@code /{id}} — 物理削除（204）</li>
 * </ul>
 *
 * <h2>認可</h2>
 * <p>全エンドポイントが {@code AccessControlService.checkSystemAdmin} を Service 層で実行する。
 * 組織 ADMIN 以下は触れない。
 */
@RestController
@RequestMapping("/api/v1/admin/point-cards/synonyms")
@Tag(name = "ポイントカード 同義語管理（SystemAdmin）",
        description = "F18 Phase 4 第三陣 S3 — 運営マスタの同義語 CRUD")
@RequiredArgsConstructor
public class AdminPointCardSynonymController {

    private final AdminPointCardSynonymService service;

    /**
     * 同義語一覧を取得する。{@code providerId} クエリで絞り込み可能。
     */
    @GetMapping
    @Operation(summary = "同義語一覧取得",
            description = "providerId 指定でそのプロバイダーに紐付く同義語のみを返す。")
    public ResponseEntity<ApiResponse<List<SynonymResponse>>> listAll(
            @RequestParam(name = "providerId", required = false) UUID providerId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(service.listAll(userId, providerId)));
    }

    /**
     * 同義語を新規登録する。
     */
    @PostMapping
    @Operation(summary = "同義語新規登録",
            description = "synonymDisplay をサーバー側で正規化して保存。"
                    + "正規化キー UNIQUE 違反時は 409 SYNONYM_DUPLICATE。")
    public ResponseEntity<ApiResponse<SynonymResponse>> create(
            @Valid @RequestBody CreateSynonymRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        SynonymResponse response = service.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 同義語を部分更新する。{@code synonymDisplay} / {@code memo} を差分適用。
     */
    @PatchMapping("/{id}")
    @Operation(summary = "同義語編集",
            description = "synonymDisplay 指定時は再正規化と重複チェックを行う。")
    public ResponseEntity<ApiResponse<SynonymResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSynonymRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(service.update(userId, id, request)));
    }

    /**
     * 同義語を物理削除する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "同義語削除", description = "物理削除。削除後にキャッシュリビルド。")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        Long userId = SecurityUtils.getCurrentUserId();
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }
}
