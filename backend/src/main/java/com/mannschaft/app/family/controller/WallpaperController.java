package com.mannschaft.app.family.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.family.dto.WallpaperResponse;
import com.mannschaft.app.family.service.WallpaperService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 壁紙コントローラー。チーム向けの壁紙一覧APIを提供する。
 */
@RestController
@Tag(name = "テンプレート壁紙", description = "F01.4 テンプレート壁紙")
@RequiredArgsConstructor
public class WallpaperController {

    private final WallpaperService wallpaperService;

    /**
     * チームで利用可能な壁紙一覧を取得する。
     */
    @GetMapping("/api/v1/teams/{teamId}/wallpapers")
    @Operation(summary = "利用可能な壁紙一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<WallpaperResponse>>> getWallpapers(@PathVariable Long teamId) {
        // テンプレートスラッグはチーム設定から取得（現時点ではデフォルト値を使用）
        return ResponseEntity.ok(wallpaperService.getAvailableWallpapers("family"));
    }
}
