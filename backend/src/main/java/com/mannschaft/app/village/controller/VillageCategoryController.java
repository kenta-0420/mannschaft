package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.village.dto.VillageCategoryRequest;
import com.mannschaft.app.village.dto.VillageCategoryResponse;
import com.mannschaft.app.village.service.VillageCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 村カテゴリコントローラー。
 *
 * <ul>
 *   <li>{@code GET /api/v1/village-categories} — ログインユーザー向け一覧（ツリー構造）</li>
 *   <li>{@code GET /api/v1/system-admin/village-categories} — SYSTEM_ADMIN 一覧</li>
 *   <li>{@code POST /api/v1/system-admin/village-categories} — SYSTEM_ADMIN 作成</li>
 *   <li>{@code PUT /api/v1/system-admin/village-categories/{id}} — SYSTEM_ADMIN 更新</li>
 *   <li>{@code DELETE /api/v1/system-admin/village-categories/{id}} — SYSTEM_ADMIN 論理削除</li>
 * </ul>
 *
 * <p>{@code /api/v1/system-admin/**} は SecurityConfig で SYSTEM_ADMIN ロールに制限済みのため
 * 当該エンドポイントへの @PreAuthorize 重複付与は行わない。</p>
 */
@RestController
@Tag(name = "村カテゴリ", description = "村カテゴリ階層マスタ CRUD")
@RequiredArgsConstructor
public class VillageCategoryController {

    private final VillageCategoryService villageCategoryService;

    // ─────────────────────────────────────────────────────────
    // 公開一覧（ログイン済みユーザー向け）
    // ─────────────────────────────────────────────────────────

    @GetMapping("/api/v1/village-categories")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "村カテゴリ一覧（ツリー）")
    public ResponseEntity<ApiResponse<List<VillageCategoryResponse>>> listCategories() {
        List<VillageCategoryResponse> categories = villageCategoryService.findAll();
        return ResponseEntity.ok(ApiResponse.of(categories));
    }

    // ─────────────────────────────────────────────────────────
    // SYSTEM_ADMIN 向け CRUD
    // ─────────────────────────────────────────────────────────

    /**
     * <p><b>認可根拠（{@link AuthorizedByPathConfig} メソッド付与）</b>:
     * 本メソッドは {@code /api/v1/system-admin/village-categories} 配下のため
     * {@code SecurityConfig.java:419 — requestMatchers("/api/v1/system-admin/**").hasRole("SYSTEM_ADMIN")}
     * で SYSTEM_ADMIN に予約済み。<b>クラス付与は不可</b>: 同クラスの {@code listCategories}（{@code
     * GET /api/v1/village-categories}）は保護 prefix 外で {@code .anyRequest().authenticated()}
     * にしか掛からないため、クラスへ貼ると誤った証跡になる。
     * 認可根治戦役 Wave5 監査済。</p>
     */
    @AuthorizedByPathConfig
    @GetMapping("/api/v1/system-admin/village-categories")
    @Operation(summary = "村カテゴリ一覧（SYSTEM_ADMIN）")
    public ResponseEntity<ApiResponse<List<VillageCategoryResponse>>> adminListCategories() {
        List<VillageCategoryResponse> categories = villageCategoryService.findAll();
        return ResponseEntity.ok(ApiResponse.of(categories));
    }

    /**
     * <p><b>認可根拠（{@link AuthorizedByPathConfig} メソッド付与）</b>:
     * 本メソッドは {@code /api/v1/system-admin/village-categories} 配下のため
     * {@code SecurityConfig.java:419 — requestMatchers("/api/v1/system-admin/**").hasRole("SYSTEM_ADMIN")}
     * で SYSTEM_ADMIN に予約済み。<b>クラス付与は不可</b>: 同クラスの {@code listCategories}（{@code
     * GET /api/v1/village-categories}）は保護 prefix 外で {@code .anyRequest().authenticated()}
     * にしか掛からないため、クラスへ貼ると誤った証跡になる。
     * 認可根治戦役 Wave5 監査済。</p>
     */
    @AuthorizedByPathConfig
    @PostMapping("/api/v1/system-admin/village-categories")
    @Operation(summary = "村カテゴリ作成（SYSTEM_ADMIN）")
    public ResponseEntity<ApiResponse<VillageCategoryResponse>> createCategory(
            @Valid @RequestBody VillageCategoryRequest request) {
        VillageCategoryResponse response = villageCategoryService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * <p><b>認可根拠（{@link AuthorizedByPathConfig} メソッド付与）</b>:
     * 本メソッドは {@code /api/v1/system-admin/village-categories} 配下のため
     * {@code SecurityConfig.java:419 — requestMatchers("/api/v1/system-admin/**").hasRole("SYSTEM_ADMIN")}
     * で SYSTEM_ADMIN に予約済み。<b>クラス付与は不可</b>: 同クラスの {@code listCategories}（{@code
     * GET /api/v1/village-categories}）は保護 prefix 外で {@code .anyRequest().authenticated()}
     * にしか掛からないため、クラスへ貼ると誤った証跡になる。
     * 認可根治戦役 Wave5 監査済。</p>
     */
    @AuthorizedByPathConfig
    @PutMapping("/api/v1/system-admin/village-categories/{id}")
    @Operation(summary = "村カテゴリ更新（SYSTEM_ADMIN）")
    public ResponseEntity<ApiResponse<VillageCategoryResponse>> updateCategory(
            @PathVariable UUID id,
            @Valid @RequestBody VillageCategoryRequest request) {
        VillageCategoryResponse response = villageCategoryService.update(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * <p><b>認可根拠（{@link AuthorizedByPathConfig} メソッド付与）</b>:
     * 本メソッドは {@code /api/v1/system-admin/village-categories} 配下のため
     * {@code SecurityConfig.java:419 — requestMatchers("/api/v1/system-admin/**").hasRole("SYSTEM_ADMIN")}
     * で SYSTEM_ADMIN に予約済み。<b>クラス付与は不可</b>: 同クラスの {@code listCategories}（{@code
     * GET /api/v1/village-categories}）は保護 prefix 外で {@code .anyRequest().authenticated()}
     * にしか掛からないため、クラスへ貼ると誤った証跡になる。
     * 認可根治戦役 Wave5 監査済。</p>
     */
    @AuthorizedByPathConfig
    @DeleteMapping("/api/v1/system-admin/village-categories/{id}")
    @Operation(summary = "村カテゴリ論理削除（SYSTEM_ADMIN）")
    public ResponseEntity<Void> deleteCategory(@PathVariable UUID id) {
        villageCategoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
