package com.mannschaft.app.family;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.family.dto.CreateWallpaperRequest;
import com.mannschaft.app.family.dto.WallpaperResponse;
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
 */
@RestController
@RequestMapping("/api/v1/system-admin/template-wallpapers")
@Tag(name = "壁紙管理（SYSTEM_ADMIN）", description = "F01.4 テンプレート壁紙管理")
@RequiredArgsConstructor
public class SystemAdminWallpaperController {

    private final WallpaperService wallpaperService;

    /**
     * 壁紙管理一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "壁紙管理一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<WallpaperResponse>>> getAllWallpapers() {
        return ResponseEntity.ok(wallpaperService.getAllWallpapers());
    }

    /**
     * 壁紙を追加する。
     */
    @PostMapping
    @Operation(summary = "壁紙追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<WallpaperResponse>> createWallpaper(
            @Valid @RequestBody CreateWallpaperRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(wallpaperService.createWallpaper(request));
    }

    /**
     * 壁紙を削除する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "壁紙削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteWallpaper(@PathVariable Long id) {
        wallpaperService.deleteWallpaper(id);
        return ResponseEntity.noContent().build();
    }
}
