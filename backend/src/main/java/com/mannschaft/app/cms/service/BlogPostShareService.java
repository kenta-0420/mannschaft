package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.cms.CmsMapper;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.SharePostRequest;
import com.mannschaft.app.cms.dto.SharePostResponse;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.entity.BlogPostShareEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostShareRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * ブログ記事の共有・プレビュートークンサービス。
 *
 * <p>外部公開系の操作（チーム/組織への共有、プレビュートークン発行/失効）を担当する。
 * リファクタリング第10弾で {@link BlogPostService} から分離。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlogPostShareService {

    private final BlogPostRepository postRepository;
    private final BlogPostShareRepository shareRepository;
    private final CmsMapper cmsMapper;
    private final AccessControlService accessControlService;

    /**
     * 個人ブログ記事をチーム/組織に共有する。
     *
     * <p>認可根治戦役 Wave3-B7: 従来は認可判定が皆無だったため、任意のユーザーが他人の記事IDを
     * 指定して自分が所属するチーム/組織へ共有できた（IDOR）。{@link #checkWriteAccess} で
     * 投稿者本人（または記事のスコープADMIN）のみに制限する。</p>
     */
    @Transactional
    public SharePostResponse sharePost(Long postId, Long userId, SharePostRequest request) {
        BlogPostEntity entity = findPostOrThrow(postId);
        checkWriteAccess(entity, userId);

        // 予約公開待ちの記事は共有不可（issue #2616・AC-6）。
        // 予約中の記事は「公開時刻まで DRAFT に留め置く」ことで公開系クエリから隔離しているが、
        // 共有は記事を他スコープへ配る別経路であり、ここを塞がないと予約時刻より前に露出する
        // （予約の意味が失われる）。共有したい場合は公開時刻の到来を待つか、予約を解除する。
        //
        // 素の下書き（published_at IS NULL の DRAFT）は従来どおり共有できる。こちらは
        // 「公開前の記事を共有先スコープに見せて意見をもらう」既存の運用であり、
        // 認可契約テスト CmsBlogPostWriteScopeContractIT が 201 を固定している既定の振る舞い。
        if (isScheduled(entity)) {
            throw new BusinessException(CmsErrorCode.SCHEDULED_POST_SHARE_NOT_ALLOWED);
        }

        // ソーシャルプロフィール名義の記事は共有不可
        if (entity.getSocialProfileId() != null) {
            throw new BusinessException(CmsErrorCode.SOCIAL_PROFILE_SHARE_NOT_ALLOWED);
        }

        // 重複チェック
        if (request.getTeamId() != null) {
            shareRepository.findByBlogPostIdAndTeamId(postId, request.getTeamId())
                    .ifPresent(s -> { throw new BusinessException(CmsErrorCode.DUPLICATE_SHARE); });
        } else if (request.getOrganizationId() != null) {
            shareRepository.findByBlogPostIdAndOrganizationId(postId, request.getOrganizationId())
                    .ifPresent(s -> { throw new BusinessException(CmsErrorCode.DUPLICATE_SHARE); });
        }

        BlogPostShareEntity share = BlogPostShareEntity.builder()
                .blogPostId(postId)
                .teamId(request.getTeamId())
                .organizationId(request.getOrganizationId())
                .sharedBy(userId)
                .build();
        BlogPostShareEntity saved = shareRepository.save(share);

        log.info("記事共有: postId={}, shareId={}", postId, saved.getId());
        return new SharePostResponse(saved.getId(), postId, saved.getTeamId(), saved.getOrganizationId());
    }

    /**
     * 共有を取り消す。
     *
     * <p>認可根治戦役 Wave3-B7: 記事所有者/スコープADMIN以外は403に加え、
     * {@code shareId} が {@code postId} 配下の共有でない越境アクセス（BOLA、既存是正済）は
     * 存在秘匿のため不在と同一の {@link CmsErrorCode#SHARE_NOT_FOUND}（404）を返す。</p>
     */
    @Transactional
    public void revokeShare(Long postId, Long shareId, Long userId) {
        BlogPostEntity entity = findPostOrThrow(postId);
        checkWriteAccess(entity, userId);

        BlogPostShareEntity share = shareRepository.findById(shareId)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.SHARE_NOT_FOUND));

        if (!share.getBlogPostId().equals(postId)) {
            throw new BusinessException(CmsErrorCode.SHARE_NOT_FOUND);
        }

        shareRepository.delete(share);
        log.info("共有取消: postId={}, shareId={}", postId, shareId);
    }

    /**
     * プレビュートークンを発行する。
     *
     * <p>認可根治戦役 Wave3-B7: 従来は認可判定が皆無だったため、任意のユーザーが他人の記事に
     * プレビュートークンを発行・URL共有できた（IDOR）。{@link #checkWriteAccess} で
     * 投稿者本人（または記事のスコープADMIN）のみに制限する。</p>
     */
    @Transactional
    public BlogPostResponse issuePreviewToken(Long id, Long userId) {
        BlogPostEntity entity = findPostOrThrow(id);
        checkWriteAccess(entity, userId);
        if (entity.getStatus() == PostStatus.PUBLISHED) {
            throw new BusinessException(CmsErrorCode.ALREADY_PUBLISHED);
        }

        String token = java.util.UUID.randomUUID().toString().replace("-", "") +
                java.util.UUID.randomUUID().toString().replace("-", "");
        entity.setPreviewToken(token, LocalDateTime.now().plusHours(24));
        BlogPostEntity saved = postRepository.save(entity);
        log.info("プレビュートークン発行: postId={}", id);
        return cmsMapper.toBlogPostResponse(saved);
    }

    /**
     * プレビュートークンを無効化する。
     */
    @Transactional
    public void revokePreviewToken(Long id, Long userId) {
        BlogPostEntity entity = findPostOrThrow(id);
        checkWriteAccess(entity, userId);
        entity.setPreviewToken(null, null);
        postRepository.save(entity);
        log.info("プレビュートークン無効化: postId={}", id);
    }

    /**
     * 記事が「予約公開待ち」かを判定する（issue #2616）。
     *
     * <p>予約中は {@code status = DRAFT} のまま {@code published_at} に未来時刻を持つ
     * （{@code PostStatus.SCHEDULED} は新設しない。{@code BlogPostEntity#publish} 参照）。</p>
     */
    private boolean isScheduled(BlogPostEntity entity) {
        return entity.getStatus() == PostStatus.DRAFT
                && entity.getPublishedAt() != null
                && entity.getPublishedAt().isAfter(LocalDateTime.now());
    }

    /**
     * 記事エンティティを取得する。存在しない場合は例外をスローする。
     */
    private BlogPostEntity findPostOrThrow(Long id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.POST_NOT_FOUND));
    }

    /**
     * 記事書込操作の認可を検証する（{@link BlogPostService#checkWriteAccess} と同一方式。
     * 認可根治戦役 Wave3-B7）。投稿者本人はスコープ種別を問わず許可。それ以外はスコープ
     * （teamId優先→organizationId）の ADMIN/DEPUTY_ADMIN のみ許可。個人ブログで非所有者は403。
     */
    private void checkWriteAccess(BlogPostEntity entity, Long actorUserId) {
        if (actorUserId != null && actorUserId.equals(entity.getAuthorId())) {
            return;
        }
        if (entity.getTeamId() != null) {
            accessControlService.checkAdminOrAbove(actorUserId, entity.getTeamId(), "TEAM");
        } else if (entity.getOrganizationId() != null) {
            accessControlService.checkAdminOrAbove(actorUserId, entity.getOrganizationId(), "ORGANIZATION");
        } else {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }
}
