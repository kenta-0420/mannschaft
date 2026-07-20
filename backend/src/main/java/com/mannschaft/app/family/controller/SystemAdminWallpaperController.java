package com.mannschaft.app.family.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.family.dto.CreateWallpaperRequest;
import com.mannschaft.app.family.dto.WallpaperResponse;
import com.mannschaft.app.family.service.WallpaperService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SYSTEM_ADMIN用壁紙管理コントローラー。壁紙の追加・削除APIを提供する。
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 3 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは、{@code SecurityConfig} のパス単位認可により
 * SYSTEM_ADMIN ロール保持者のみへ宣言的に予約されている。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig.java:419 — requestMatchers("/api/v1/system-admin/**").hasRole("SYSTEM_ADMIN")
 * </p>
 *
 * <p>Controller / Service 側に認可コードは存在しないが、フィルタチェーンで強制されるため
 * 無認可ではない。認可根治戦役 Wave5 監査済。パス定義を変更・削除する際は本注釈の根拠が
 * 失効するため、必ず併せて見直すこと。</p>
 */
@AuthorizedByPathConfig
@RestController
@RequestMapping("/api/v1/system-admin/template-wallpapers")
@Tag(name = "壁紙管理（SYSTEM_ADMIN）", description = "F01.4 テンプレート壁紙管理")
@RequiredArgsConstructor
public class SystemAdminWallpaperController {

    private final WallpaperService wallpaperService;

    @GetMapping
    @Operation(summary = "壁紙管理一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<WallpaperResponse>>> getAllWallpapers() {
        return ResponseEntity.ok(wallpaperService.getAllWallpapers());
    }

    @PostMapping
    @Operation(summary = "壁紙追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<WallpaperResponse>> createWallpaper(
            @Valid @RequestBody CreateWallpaperRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(wallpaperService.createWallpaper(request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "壁紙削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteWallpaper(@PathVariable Long id) {
        wallpaperService.deleteWallpaper(id);
        return ResponseEntity.noContent().build();
    }
}
