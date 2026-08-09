package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageCreateRequest;
import com.mannschaft.app.village.dto.VillageResponse;
import com.mannschaft.app.village.dto.VillageSearchResponse;
import com.mannschaft.app.village.dto.VillageUpdateRequest;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.service.VillageService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 村本体 CRUD・検索コントローラ（F17.1 Phase 1 §4.1 / §4.2）。
 *
 * <p>認証は全 API で必須（SecurityConfig の {@code anyRequest().authenticated()} フォールバックに加え、
 * SecurityUtils.getCurrentUserId() が未認証なら 401 を返す）。</p>
 *
 * <h2>エンドポイント一覧</h2>
 * <ul>
 *   <li>{@code POST   /api/v1/villages}                       — 村作成（SYSTEM_ADMIN）</li>
 *   <li>{@code GET    /api/v1/villages/{id}}                  — 村詳細取得</li>
 *   <li>{@code PATCH  /api/v1/villages/{id}}                  — 村更新（HEADMAN / SYSTEM_ADMIN）</li>
 *   <li>{@code DELETE /api/v1/villages/{id}}                  — 村論理削除（HEADMAN / SYSTEM_ADMIN）</li>
 *   <li>{@code POST   /api/v1/villages/{id}/archive}          — 村凍結（SYSTEM_ADMIN）</li>
 *   <li>{@code GET    /api/v1/villages/search}                — 村検索（PUBLIC のみ）</li>
 * </ul>
 *
 * <h2>楽観ロック</h2>
 * <p>PATCH は {@code If-Match} ヘッダで {@code version} 値を渡せる（任意）。
 * サーバ側 version と不一致なら {@link VillageErrorCode#VERSION_CONFLICT}（409）。</p>
 */
@RestController
@RequestMapping("/api/v1/villages")
@Tag(name = "村機能", description = "F17.1 Phase 1 — 村本体 CRUD と検索")
@RequiredArgsConstructor
public class VillageController {

    private final VillageService villageService;

    @PostMapping
    @Operation(summary = "村作成（SYSTEM_ADMIN）")
    public ResponseEntity<ApiResponse<VillageResponse>> create(
            @Valid @RequestBody VillageCreateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        VillageResponse dto = villageService.create(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(dto));
    }

    @GetMapping("/{villageId}")
    @Operation(summary = "村詳細取得")
    public ResponseEntity<ApiResponse<VillageResponse>> get(@PathVariable UUID villageId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(villageService.get(villageId, userId)));
    }

    @PatchMapping("/{villageId}")
    @Operation(summary = "村更新（HEADMAN / SYSTEM_ADMIN）")
    public ResponseEntity<ApiResponse<VillageResponse>> update(
            @PathVariable UUID villageId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody VillageUpdateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long version = parseIfMatch(ifMatch);
        VillageResponse dto = villageService.update(villageId, request, userId, version);
        return ResponseEntity.ok(ApiResponse.of(dto));
    }

    @DeleteMapping("/{villageId}")
    @Operation(summary = "村論理削除（HEADMAN / SYSTEM_ADMIN）")
    public ResponseEntity<Void> delete(@PathVariable UUID villageId) {
        Long userId = SecurityUtils.getCurrentUserId();
        villageService.softDelete(villageId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{villageId}/archive")
    @Operation(summary = "村凍結（SYSTEM_ADMIN）")
    public ResponseEntity<Void> archive(
            @PathVariable UUID villageId,
            @RequestBody(required = false) Map<String, String> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        String reason = body != null ? body.get("reason") : null;
        villageService.archive(villageId, userId, reason);
        return ResponseEntity.ok().build();
    }

    /**
     * 村を検索する。
     *
     * <p>可視性の絞り込みは {@link VillageService#search} 内で行い、
     * {@code VillageSearchSpecifications.searchable()} が結果を可視性 PUBLIC かつ
     * 未削除・未凍結の村に限定する。UNLISTED / 削除・凍結済みの村は結果に現れず、
     * 村内のコンテンツも返さない（返すのは公開プロフィール相当の要約のみ）。</p>
     */
    @AuthorizedInService
    @GetMapping("/search")
    @Operation(summary = "村検索（PUBLIC のみ）")
    public ResponseEntity<VillageSearchResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        VillageType typeEnum = parseTypeOrNull(type);
        return ResponseEntity.ok(villageService.search(q, category, typeEnum, page, size, userId));
    }

    /**
     * {@code If-Match} ヘッダ文字列から {@code version} 値を取り出す。
     * RFC 7232 の弱検証子・クォートを許容。書式不正は {@link VillageErrorCode#VILLAGE_FIELD_INVALID} で 400。
     */
    private static Long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        String trimmed = ifMatch.trim();
        if (trimmed.startsWith("W/")) {
            trimmed = trimmed.substring(2);
        }
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            throw new BusinessException(VillageErrorCode.VILLAGE_FIELD_INVALID);
        }
    }

    private static VillageType parseTypeOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return VillageType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            // 無効な type は検索結果空相当として扱うが、設計上「不正値は 400」が一般的なので 400 を返す
            throw new BusinessException(VillageErrorCode.VILLAGE_FIELD_INVALID);
        }
    }
}
