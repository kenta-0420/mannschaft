package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.common.security.IntentionallyPublic;
import com.mannschaft.app.publicview.dto.PublicUserPostSummaryResponse;
import com.mannschaft.app.publicview.dto.PublicUserProfileResponse;
import com.mannschaft.app.publicview.service.PublicUserProfileQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F19.1 Phase 6: 公開ユーザープロフィール Controller。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.6 Phase 6</p>
 *
 * <p>このコントローラは<strong>認証不要</strong>（permitAll）であり、
 * SecurityConfig で {@code /api/v1/public/users/**} が permitAll に設定されている。</p>
 *
 * <p>レート制限は {@link com.mannschaft.app.publicview.filter.PublicApiRateLimitFilter} が担う。</p>
 *
 * <p><strong>IDOR / エニュメレーション対策</strong>: 存在しない・非公開・削除済み
 * ユーザーはすべて {@link com.mannschaft.app.publicview.error.PublicViewErrorCode#PUBLIC_007}
 * (404) を返し状態を区別しない。</p>
 *
 * <p><b>公開根拠（{@link IntentionallyPublic} クラス付与・凍結ストア該当 2 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは {@code SecurityConfig} で
 * {@code permitAll()} 済み。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig — requestMatchers(GET, "/api/v1/public/users/*"
 * / "/api/v1/public/users/&#42;/posts").permitAll()
 * </p>
 *
 * <p><b>公開してよいと判断した理由</b>:
 * F19.1 Phase 6 公開ユーザープロフィール。<b>本人が {@code public_profile_enabled=true}
 * を明示設定したユーザーのみ</b> 200 を返し、投稿は {@code visibility=PUBLIC} かつ{@code
 * status=PUBLISHED} かつ {@code public_visible=true} のものに限る。不在／非公開／削除済みは一律
 * 404 で状態を区別しない（IDOR・エニュメレーション対策）。
 * </p>
 *
 * <p>認可根治戦役 Wave5 監査済。レスポンス項目が将来増えた場合は公開の妥当性が崩れうるため、
 * 当該 DTO の変更時は本注釈の妥当性を再評価すること。</p>
 */
@IntentionallyPublic({
        "/api/v1/public/users/*",
        "/api/v1/public/users/*/posts"
})
@RestController
@RequestMapping("/api/v1/public/users")
@Tag(name = "公開ユーザープロフィール API (F19.1 Phase 6)")
@RequiredArgsConstructor
public class PublicUserProfileController {

    private final PublicUserProfileQueryService publicUserProfileQueryService;

    /**
     * 公開ユーザープロフィールを取得する。
     *
     * <p>{@code public_profile_enabled = true} のユーザーのみ 200 を返す。
     * 不在 / 非公開 / 削除済みは一律 404（IDOR 対策）。</p>
     */
    @GetMapping("/{userId}")
    @Operation(
            summary = "ユーザープロフィール（未ログイン公開）",
            description = "未ログインでも実行可能。public_profile_enabled=true かつ未削除のユーザーのみ 200。"
                    + " それ以外は 404（IDOR 対策で状態を区別しない）。")
    public ResponseEntity<PublicUserProfileResponse> getProfile(@PathVariable Long userId) {
        return ResponseEntity.ok(publicUserProfileQueryService.getPublicProfile(userId));
    }

    /**
     * 公開ユーザーの投稿一覧を取得する。
     *
     * <p>visibility=PUBLIC かつ status=PUBLISHED かつ public_visible=true の投稿のみ返す。
     * ユーザー自体が非公開の場合は 404（IDOR 対策）。</p>
     */
    @GetMapping("/{userId}/posts")
    @Operation(
            summary = "ユーザーの公開投稿一覧（未ログイン公開）",
            description = "未ログインでも実行可能。public_profile_enabled=true のユーザーの"
                    + " PUBLIC+PUBLISHED+public_visible=true 投稿のみ返す。"
                    + " ユーザーが非公開の場合は 404（IDOR 対策）。")
    public ResponseEntity<Page<PublicUserPostSummaryResponse>> getPosts(
            @PathVariable Long userId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(publicUserProfileQueryService.getPublicPosts(userId, pageable));
    }
}
