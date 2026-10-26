package com.mannschaft.app.common.storage.quota.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.storage.quota.StorageUsageQueryService;
import com.mannschaft.app.common.storage.quota.dto.StorageScopeUsage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F13 ストレージ使用量参照コントローラー。
 *
 * <p>ログインユーザー本人が所属する各スコープ（個人・チーム・組織）のストレージ使用量を返す。
 * <b>クライアントから {@code scopeId} を一切受け取らない</b>（サーバーが本人の所属を列挙する）ため、
 * 恣意的 ID 注入による他スコープ使用量の参照を構造的に排除している。</p>
 */
@RestController
@RequestMapping("/api/v1/me/storage")
@Tag(name = "ストレージ使用量")
@RequiredArgsConstructor
public class StorageUsageController {

    private final StorageUsageQueryService storageUsageQueryService;

    /**
     * 自分のストレージ使用量一覧を取得する。
     *
     * @return スコープ別使用量のリスト（PERSONAL + 所属チーム + 所属組織）
     */
    @GetMapping("/usage")
    @Operation(summary = "自分のストレージ使用量",
            description = "本人が所属する個人・チーム・組織の各スコープのストレージ使用量を返す。"
                    + "scopeId はサーバーが本人の所属から列挙するため、クライアントは指定しない。")
    @ApiResponse(responseCode = "200", description = "取得成功")
    @ApiResponse(responseCode = "401", description = "未認証")
    public ResponseEntity<List<StorageScopeUsage>> getMyStorageUsage() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(storageUsageQueryService.getMyStorageUsage(userId));
    }
}
