package com.mannschaft.app.publicview.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.publicview.dto.PublicPostCommentRequest;
import com.mannschaft.app.publicview.dto.PublicPostCommentResponse;
import com.mannschaft.app.publicview.entity.PublicPostCommentEntity;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.publicview.repository.PublicPostCommentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * F19.1 Phase 6-B: 公開投稿コメントサービス。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.7 Phase 6-B</p>
 *
 * <p><strong>アクセス制御:</strong></p>
 * <ul>
 *   <li>未ログインユーザー: コメント一覧の閲覧のみ可能</li>
 *   <li>ログイン済みユーザー: コメントの投稿・自分のコメントの削除が可能</li>
 *   <li>ADMIN: 全コメントの削除が可能</li>
 * </ul>
 *
 * <p><strong>クロスドメイン参照について:</strong>
 * 本サービスは publicview → cms / auth のクロスドメイン参照を行う。
 * 将来のマイクロサービス分割時はイベント駆動化を検討すること。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PublicPostCommentService {

    private final PublicPostCommentRepository commentRepository;
    // TODO: publicview → cms のクロスドメイン参照。将来はイベント駆動化候補。
    private final BlogPostRepository blogPostRepository;
    // TODO: publicview → auth のクロスドメイン参照。将来はイベント駆動化候補。
    private final UserRepository userRepository;

    /**
     * 指定した投稿のコメント一覧を取得する（未ログインでも取得可能）。
     *
     * <p>投稿が {@code public_visible=true} かつ {@code status=PUBLISHED} かつ
     * {@code visibility=PUBLIC} でない場合は {@link PublicViewErrorCode#PUBLIC_008}（404）を返す。</p>
     *
     * <p>TODO: publicview → cms クロスドメイン参照。将来はイベント駆動化候補。</p>
     *
     * @param postId   対象 BlogPost の ID
     * @param pageable ページネーション
     * @return 有効なコメントのページ（作成日時 ASC）
     * @throws BusinessException 投稿が存在しないか非公開（{@link PublicViewErrorCode#PUBLIC_008}、404 へ正規化）
     */
    @Transactional(readOnly = true)
    public Page<PublicPostCommentResponse> getComments(Long postId, Pageable pageable) {
        // 投稿の存在確認（public_visible=true かつ visibility=PUBLIC かつ status=PUBLISHED）
        validatePublicPost(postId);

        // TODO: N+1 問題。コメント件数が多い場合は author_id をバルク取得して UserRepository.findAllById で解決すること。
        return commentRepository.findActiveByPostId(postId, pageable)
                .map(this::toResponse);
    }

    /**
     * ログイン済みユーザーがコメントを投稿する。
     *
     * <p>TODO: publicview → cms / auth クロスドメイン参照。将来はイベント駆動化候補。</p>
     *
     * @param postId   対象 BlogPost の ID
     * @param authorId 投稿者ユーザー ID
     * @param request  コメント投稿リクエスト
     * @return 作成されたコメントのレスポンス
     * @throws BusinessException 投稿が存在しないか非公開（{@link PublicViewErrorCode#PUBLIC_008}）
     */
    @Transactional
    // TODO: publicview → cms / auth クロスドメイン参照。将来はイベント駆動化候補。
    public PublicPostCommentResponse postComment(Long postId, Long authorId,
            PublicPostCommentRequest request) {
        // 投稿の存在確認
        validatePublicPost(postId);

        // 投稿者の存在確認（論理削除済みユーザーは除外）
        UserEntity author = userRepository.findById(authorId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_008));

        // author_real_name_snapshot: 今フェーズでは null（将来 REAL_NAME モード対応時に設定）
        // TODO: チームの supporter_name_disclosure = REAL_NAME の場合、本名スナップショットを設定する
        String authorRealNameSnapshot = null;

        PublicPostCommentEntity entity = PublicPostCommentEntity.create(
                postId,
                authorId,
                request.content(),
                authorRealNameSnapshot
        );
        PublicPostCommentEntity saved = commentRepository.save(entity);
        log.debug("コメントを投稿しました: commentId={}, postId={}, authorId={}",
                saved.getId(), postId, authorId);

        return toResponse(saved, author.getDisplayName());
    }

    /**
     * コメントを論理削除する。
     *
     * <p>投稿者本人または ADMIN のみが削除できる。
     * それ以外のユーザーは {@link PublicViewErrorCode#PUBLIC_010}（403）が返る。</p>
     *
     * @param commentId     削除対象のコメント UUID
     * @param requestUserId 削除要求者のユーザー ID
     * @param isAdmin       ADMIN 権限を持つかどうか
     * @throws BusinessException コメントが存在しない（{@link PublicViewErrorCode#PUBLIC_009}）
     * @throws BusinessException 削除権限がない（{@link PublicViewErrorCode#PUBLIC_010}）
     */
    @Transactional
    public void deleteComment(UUID commentId, Long requestUserId, boolean isAdmin) {
        PublicPostCommentEntity comment = commentRepository.findById(commentId)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_009));

        // 投稿者本人または ADMIN のみ削除可
        if (!isAdmin && !comment.getAuthorId().equals(requestUserId)) {
            throw new BusinessException(PublicViewErrorCode.PUBLIC_010);
        }

        comment.softDelete();
        log.debug("コメントを削除しました: commentId={}, requestUserId={}, isAdmin={}",
                commentId, requestUserId, isAdmin);
    }

    /**
     * 投稿が公開条件を満たしているか検証する。
     *
     * <p>条件: {@code visibility=PUBLIC} かつ {@code status=PUBLISHED} かつ
     * {@code public_visible=true} かつ未削除</p>
     *
     * @param postId 対象 BlogPost の ID
     * @throws BusinessException 条件を満たさない場合（{@link PublicViewErrorCode#PUBLIC_008}）
     */
    private void validatePublicPost(Long postId) {
        // TODO: publicview → cms クロスドメイン参照
        BlogPostEntity post = blogPostRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_008));

        if (post.getVisibility() != Visibility.PUBLIC
                || post.getStatus() != PostStatus.PUBLISHED
                || !post.isPublicVisible()) {
            throw new BusinessException(PublicViewErrorCode.PUBLIC_008);
        }
    }

    /**
     * コメントエンティティをレスポンス DTO に変換する（著者名を別途取得）。
     *
     * <p>TODO: N+1 防止のため呼び出し元でバルク取得を検討すること。</p>
     */
    private PublicPostCommentResponse toResponse(PublicPostCommentEntity comment) {
        UserEntity author = userRepository.findById(comment.getAuthorId()).orElse(null);
        String displayName = author != null ? author.getDisplayName() : "退会済みユーザー";
        return toResponse(comment, displayName);
    }

    /** コメントエンティティをレスポンス DTO に変換する（著者名を引数で受取）。 */
    private PublicPostCommentResponse toResponse(PublicPostCommentEntity comment, String authorDisplayName) {
        return new PublicPostCommentResponse(
                comment.getId().toString(),
                comment.getAuthorId(),
                authorDisplayName,
                comment.getContent(),
                comment.getCreatedAt()
        );
    }
}
