package com.mannschaft.app.favorite.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.FavoriteErrorCode;
import com.mannschaft.app.favorite.dto.FavoriteCheckResultDto;
import com.mannschaft.app.favorite.dto.FavoriteItemDto;
import com.mannschaft.app.favorite.dto.request.AddFavoriteRequest;
import com.mannschaft.app.favorite.dto.request.ReorderFavoritesRequest;
import com.mannschaft.app.favorite.dto.response.FavoriteCheckResponse;
import com.mannschaft.app.favorite.dto.response.FavoriteResponse;
import com.mannschaft.app.favorite.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
 * F02.9 個人ダッシュボード お気に入りウィジェット — CRUD コントローラー。
 *
 * <p>設計書: {@code docs/features/F02.9_favorites_widget.md}
 *
 * <p>認証必須・{@code FavoriteRateLimitFilter} でユーザー別レート制限を適用する:
 * <ul>
 *   <li>GET    /api/v1/me/favorites         ─ 120 req/分</li>
 *   <li>GET    /api/v1/me/favorites/check   ─ 240 req/分</li>
 *   <li>POST   /api/v1/me/favorites         ─ 30 req/時</li>
 *   <li>DELETE /api/v1/me/favorites/{id}    ─ 60 req/時</li>
 *   <li>PATCH  /api/v1/me/favorites/reorder ─ 30 req/時</li>
 * </ul>
 *
 * <p>認可は {@code FavoriteAccessGuard} に集約する:
 * <ul>
 *   <li>お気に入り行の参照・削除は<b>登録した本人のみ</b>。他人のお気に入り ID には
 *       {@code FAV_004}（403）を返し、不存在は {@code FAV_003}（404）。</li>
 *   <li>登録対象（チーム／組織）は F00 共通可視性ラダーで<b>閲覧できる対象のみ</b>登録できる。
 *       閲覧できない対象は {@code FAV_003}（404）で存在を秘匿する。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/me/favorites")
@Tag(name = "お気に入り", description = "個人ダッシュボード お気に入りウィジェット API")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    // ─────────────────────────────────────────────
    // 一覧取得
    // ─────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "お気に入り一覧取得",
            description = "認証ユーザーのお気に入りを表示順（displayOrder昇順）で返す。"
                    + "削除済みエンティティは available=false として含まれる。")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未認証")
    })
    @SelfScopedEndpoint("一覧の対象は SecurityUtils.getCurrentUserId() 固定で、"
            + "リクエストに他ユーザーの識別子を指定する項目が無い（listFavorites メソッド本体）")
    public ResponseEntity<ApiResponse<List<FavoriteResponse>>> listFavorites() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<FavoriteResponse> responses = favoriteService.getFavorites(userId).stream()
                .map(FavoriteResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    // ─────────────────────────────────────────────
    // 登録状態チェック
    // ─────────────────────────────────────────────

    @GetMapping("/check")
    @Operation(summary = "お気に入り登録状態チェック",
            description = "指定エンティティが当該ユーザーのお気に入りに登録されているか確認する。"
                    + "FavoriteToggleButton.vue のマウント時に呼び出される。")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "entityType 不正"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未認証")
    })
    // 認可の所在: FavoriteService.checkFavorite（favorite/service/FavoriteService.java:193）が
    // userFavoriteRepository.findByUserIdAndEntityTypeAndEntityId で常に userId を検索条件に
    // AND 結合するため、entityId が他人に属していても自分の登録有無としてのみ結果が返る。
    @AuthorizedInService
    public ResponseEntity<ApiResponse<FavoriteCheckResponse>> checkFavorite(
            @RequestParam("entityType") String entityTypeStr,
            @RequestParam("entityId") String entityId) {
        Long userId = SecurityUtils.getCurrentUserId();

        // entityType 文字列をenumに変換。不正な値は FAV_005 をスロー（POST と同様の挙動）
        FavoriteEntityType entityType;
        try {
            entityType = FavoriteEntityType.valueOf(entityTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(FavoriteErrorCode.FAV_005);
        }

        FavoriteCheckResultDto result = favoriteService.checkFavorite(userId, entityType, entityId);
        FavoriteCheckResponse response = new FavoriteCheckResponse(
                result.isFavorited(),
                result.favoriteId() != null ? result.favoriteId().toString() : null
        );
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ─────────────────────────────────────────────
    // 追加
    // ─────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "お気に入り追加",
            description = "指定エンティティをお気に入りに追加する。先頭（displayOrder=0）に挿入される。"
                    + "20件上限・重複チェック・エンティティ存在確認を行う。")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "バリデーションエラー"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未認証"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "重複または上限超過")
    })
    public ResponseEntity<ApiResponse<FavoriteResponse>> addFavorite(
            @Valid @RequestBody AddFavoriteRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        // entityType 文字列をenumに変換。不正な値は FAV_005 をスロー
        FavoriteEntityType entityType;
        try {
            entityType = FavoriteEntityType.valueOf(request.getEntityType().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(FavoriteErrorCode.FAV_005);
        }
        FavoriteItemDto dto = favoriteService.addFavorite(userId, entityType, request.getEntityId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(FavoriteResponse.from(dto)));
    }

    // ─────────────────────────────────────────────
    // 1件取得
    // ─────────────────────────────────────────────

    @GetMapping("/{favoriteId}")
    @Operation(summary = "お気に入り1件取得",
            description = "追加後の再取得やウィジェット更新に使用する。")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未認証"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "他ユーザーのお気に入りへのアクセス"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "見つからない")
    })
    public ResponseEntity<ApiResponse<FavoriteResponse>> getFavorite(
            @PathVariable UUID favoriteId) {
        Long userId = SecurityUtils.getCurrentUserId();
        FavoriteItemDto dto = favoriteService.getFavoriteById(userId, favoriteId);
        return ResponseEntity.ok(ApiResponse.of(FavoriteResponse.from(dto)));
    }

    // ─────────────────────────────────────────────
    // 削除
    // ─────────────────────────────────────────────

    @DeleteMapping("/{favoriteId}")
    @Operation(summary = "お気に入り削除",
            description = "指定IDのお気に入りを削除する。他ユーザーのお気に入りは削除できない（IDOR対策）。")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未認証"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "他ユーザーのお気に入りへのアクセス"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "見つからない")
    })
    public ResponseEntity<Void> removeFavorite(@PathVariable UUID favoriteId) {
        Long userId = SecurityUtils.getCurrentUserId();
        favoriteService.removeFavorite(userId, favoriteId);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────
    // 並び替え
    // ─────────────────────────────────────────────

    @PatchMapping("/reorder")
    @Operation(summary = "お気に入り並び替え",
            description = "orderedIds の順序でお気に入りの displayOrder を一括更新する。"
                    + "リストに含まれていないIDは変更されない。"
                    + "並び替え対象は認証ユーザー自身のお気に入りに限られ、自分の登録に無いIDは404。")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "更新成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "バリデーションエラー"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未認証"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "自分のお気に入りに存在しないIDが含まれている")
    })
    // 認可の所在: FavoriteService.reorderFavorites（favorite/service/FavoriteService.java:134）が
    // userId 所有分のみを対象にロードし、orderedIds が自分の所有物に無ければ FAV_003（404）で
    // 秘匿する。並び替えは引き当てた自分の Entity にのみ適用される。
    @AuthorizedInService
    public ResponseEntity<Void> reorderFavorites(
            @Valid @RequestBody ReorderFavoritesRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        favoriteService.reorderFavorites(userId, request.getOrderedIds());
        return ResponseEntity.noContent().build();
    }
}
