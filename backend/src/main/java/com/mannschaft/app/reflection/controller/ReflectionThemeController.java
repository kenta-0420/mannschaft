package com.mannschaft.app.reflection.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.reflection.dto.CreateReflectionThemeRequest;
import com.mannschaft.app.reflection.dto.ReflectionThemeResponse;
import com.mannschaft.app.reflection.dto.UpdateReflectionThemeRequest;
import com.mannschaft.app.reflection.service.ReflectionArchiveService;
import com.mannschaft.app.reflection.service.ReflectionThemeService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F06.5 振り返りテーマコントローラー（§7 #1〜#5）。
 *
 * <p>認可は全エンドポイント「認証必須＋本人所有のみ」（theme.user_id==currentUserId・他人は 403）。</p>
 */
@RestController
@RequestMapping("/api/v1/me/reflections/themes")
@Tag(name = "振り返りテーマ", description = "F06.5 アクティブリコール学習機能 — メインテーマ")
@RequiredArgsConstructor
public class ReflectionThemeController {

    private final ReflectionThemeService reflectionThemeService;
    private final ReflectionArchiveService reflectionArchiveService;

    /** 自分のテーマ一覧（§7 #1）。 */
    @SelfScopedEndpoint("一覧のスコープは SecurityUtils.getCurrentUserId() で確定した認証主体固定"
            + "（ReflectionThemeService#listMyThemes）")
    @GetMapping
    @Operation(summary = "テーマ一覧取得")
    public ResponseEntity<ApiResponse<List<ReflectionThemeResponse>>> listThemes() {
        List<ReflectionThemeResponse> result =
                reflectionThemeService.listMyThemes(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /** テーマ作成（§7 #2）。 */
    @PostMapping
    @Operation(summary = "テーマ作成")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ApiResponse<ReflectionThemeResponse>> createTheme(
            @Valid @RequestBody CreateReflectionThemeRequest request) {
        ReflectionThemeResponse result =
                reflectionThemeService.createTheme(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    /** テーマ詳細（§7 #3）。 */
    @GetMapping("/{themeId}")
    @Operation(summary = "テーマ詳細取得")
    public ResponseEntity<ApiResponse<ReflectionThemeResponse>> getTheme(
            @PathVariable UUID themeId) {
        ReflectionThemeResponse result =
                reflectionThemeService.getTheme(SecurityUtils.getCurrentUserId(), themeId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /** テーマ更新（§7 #4・exam_date 設定含む）。 */
    @PatchMapping("/{themeId}")
    @Operation(summary = "テーマ更新")
    public ResponseEntity<ApiResponse<ReflectionThemeResponse>> updateTheme(
            @PathVariable UUID themeId,
            @Valid @RequestBody UpdateReflectionThemeRequest request) {
        ReflectionThemeResponse result =
                reflectionThemeService.updateTheme(SecurityUtils.getCurrentUserId(), themeId, request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /** テーマ論理削除（§7 #5・配下 entry も CASCADE 論理削除＋PENDING リマインダ CANCEL）。 */
    @DeleteMapping("/{themeId}")
    @Operation(summary = "テーマ論理削除")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteTheme(@PathVariable UUID themeId) {
        reflectionThemeService.deleteTheme(SecurityUtils.getCurrentUserId(), themeId);
        return ResponseEntity.noContent().build();
    }

    // ─── Phase 3: アーカイブ操作（EP #19/#20・§12.4）───────────────

    /**
     * EP #19: テーマをアーカイブする（archived_at = now・PENDING リマインダー SPACED+PRE_EXAM CANCEL）。
     */
    @PatchMapping("/{themeId}/archive")
    @Operation(summary = "テーマアーカイブ")
    public ResponseEntity<ApiResponse<ReflectionThemeResponse>> archiveTheme(
            @PathVariable UUID themeId) {
        ReflectionThemeResponse result =
                reflectionArchiveService.archiveTheme(SecurityUtils.getCurrentUserId(), themeId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * EP #20: アーカイブ済みテーマを復元する（archived_at = null・リマインダーは自動再生成しない）。
     */
    @PatchMapping("/{themeId}/restore")
    @Operation(summary = "テーマ復元")
    public ResponseEntity<ApiResponse<ReflectionThemeResponse>> restoreTheme(
            @PathVariable UUID themeId) {
        ReflectionThemeResponse result =
                reflectionArchiveService.restoreTheme(SecurityUtils.getCurrentUserId(), themeId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
