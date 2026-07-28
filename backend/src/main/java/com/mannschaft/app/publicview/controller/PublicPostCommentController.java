package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.IntentionallyPublic;
import com.mannschaft.app.publicview.dto.PublicPostCommentRequest;
import com.mannschaft.app.publicview.dto.PublicPostCommentResponse;
import com.mannschaft.app.publicview.service.PublicPostCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F19.1 Phase 6-B: 公開投稿コメント Controller。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.7 Phase 6-B</p>
 *
 * <p><strong>アクセス制御:</strong></p>
 * <ul>
 *   <li>GET（一覧）: 未ログインでも閲覧可能（SecurityConfig で permitAll 設定済み）</li>
 *   <li>POST（投稿）: ログイン済みユーザーのみ（Spring Security のデフォルト認証要求）</li>
 *   <li>DELETE（削除）: ログイン済みユーザーのみ（投稿者本人 or ADMIN はサービス層で判定）</li>
 * </ul>
 *
 * <p>レート制限は {@link com.mannschaft.app.publicview.filter.PublicApiRateLimitFilter} が担う。</p>
 */
@RestController
@RequestMapping("/api/v1/public/blog-posts/{postId}/comments")
@Tag(name = "公開投稿コメント API (F19.1 Phase 6-B)")
@RequiredArgsConstructor
public class PublicPostCommentController {

    private final PublicPostCommentService commentService;

    /**
     * 指定した公開投稿のコメント一覧を取得する（未ログインでも閲覧可能）。
     *
     * <p>SecurityConfig で {@code /api/v1/public/blog-posts/{postId}/comments} が permitAll に設定されている。</p>
     *
     *
     * <p><b>公開根拠（{@link IntentionallyPublic} メソッド付与）</b>:
     * 本エンドポイントは {@code SecurityConfig} で {@code permitAll()} 済み。</p>
     *
     * <p><b>根拠</b>:
     * SecurityConfig.java:341 — requestMatchers(GET, "/api/v1/public/blog-posts/&#42;/comments").permitAll()
     * </p>
     *
     * <p><b>公開してよいと判断した理由</b>:
     * F19.1 Phase 6-B 公開投稿のコメント<b>一覧取得のみ</b>。公開ブログ記事に紐づく公開コメントを未ログイン訪問者にも見せることが公開ページの要件。
     * <b>クラス付与は不可</b>: 同クラスの {@code postComment}（POST）/ {@code deleteComment}（DELETE）
     * は<b>認証必須の書込</b>であり、クラスへ貼ると無認可書込を承認したことになる。
     * </p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     * @param postId   対象 BlogPost の ID
     * @param pageable ページネーション（デフォルト 20 件）
     * @return コメントのページ
     */
    @IntentionallyPublic
    @GetMapping
    @Operation(
            summary = "公開投稿コメント一覧取得（未ログイン公開）",
            description = "未ログインでも実行可能。public_visible=true かつ visibility=PUBLIC かつ"
                    + " status=PUBLISHED の BlogPost のコメント一覧を返す。"
                    + " 対象投稿が存在しないか非公開の場合は 404。")
    public ResponseEntity<Page<PublicPostCommentResponse>> getComments(
            @PathVariable Long postId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(commentService.getComments(postId, pageable));
    }

    /**
     * ログイン済みユーザーがコメントを投稿する。
     *
     * <p>認証は Spring Security のデフォルト挙動（未認証時は 401）に委ねる。</p>
     *
     * @param postId         対象 BlogPost の ID
     * @param request        コメント投稿リクエスト
     * @param authentication Spring Security の認証情報
     * @return 作成されたコメント（201 Created）
     */
    @PostMapping
    @Operation(
            summary = "公開投稿コメント投稿（ログイン必須）",
            description = "ログイン済みユーザーのみ実行可能。"
                    + " 対象投稿が存在しないか非公開の場合は 404（PUBLIC_008）。"
                    + " 未ログインの場合は 401。")
    public ResponseEntity<PublicPostCommentResponse> postComment(
            @PathVariable Long postId,
            @RequestBody @Valid PublicPostCommentRequest request,
            Authentication authentication) {
        Long authorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commentService.postComment(postId, authorId, request));
    }

    /**
     * コメントを論理削除する（投稿者本人または ADMIN のみ）。
     *
     * <p>認証は Spring Security のデフォルト挙動（未認証時は 401）に委ねる。
     * 権限チェック（本人 or ADMIN）はサービス層で実施する。</p>
     *
     * @param postId         対象 BlogPost の ID（URL の一貫性のために含めるが、サービス層では未使用）
     * @param commentId      削除対象コメントの UUID
     * @param authentication Spring Security の認証情報
     * @return 204 No Content
     */
    @DeleteMapping("/{commentId}")
    @Operation(
            summary = "公開投稿コメント削除（ログイン必須）",
            description = "投稿者本人または ADMIN（hasRole('ADMIN') or hasRole('SYSTEM_ADMIN')）のみ実行可能。"
                    + " コメントが存在しない場合は 404（PUBLIC_009）。"
                    + " 権限がない場合は 403（PUBLIC_010）。"
                    + " 未ログインの場合は 401。")
    public ResponseEntity<Void> deleteComment(
            @PathVariable Long postId,
            @PathVariable UUID commentId,
            Authentication authentication) {
        Long requestUserId = SecurityUtils.getCurrentUserId();
        boolean isAdmin = isAdminOrSystemAdmin(authentication);
        commentService.deleteComment(commentId, requestUserId, isAdmin);
        return ResponseEntity.noContent().build();
    }

    /**
     * 認証情報から ADMIN または SYSTEM_ADMIN ロールを保持しているか判定する。
     *
     * @param authentication Spring Security の認証情報
     * @return ADMIN / SYSTEM_ADMIN の場合 true
     */
    private boolean isAdminOrSystemAdmin(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_SYSTEM_ADMIN"));
    }
}
