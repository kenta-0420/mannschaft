package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.cms.CmsMapper;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.RevisionResponse;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.entity.BlogPostRevisionEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostRevisionRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ブログ記事リビジョンサービス。
 *
 * <p>リビジョン履歴の取得・復元、PUBLISHED 記事更新時の自動スナップショット保存を担当する。
 * リファクタリング第10弾で {@link BlogPostService} から分離。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlogPostRevisionService {

    private final BlogPostRepository postRepository;
    private final BlogPostRevisionRepository revisionRepository;
    private final CmsMapper cmsMapper;
    private final AccessControlService accessControlService;
    private final ContentVisibilityChecker contentVisibilityChecker;

    /**
     * リビジョン一覧を取得する。
     *
     * <p>認可根治戦役 Wave3-B7: 従来は認可判定が皆無だった（記事の実存確認のみ）ため、
     * 非メンバーでも他人の記事のリビジョン履歴（下書き含む編集履歴）を閲覧できた。
     * {@link ContentVisibilityChecker#assertCanView} で {@code getById} と同一の
     * 可視性判定を適用する。</p>
     */
    public List<RevisionResponse> listRevisions(Long postId) {
        findPostOrThrow(postId);
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        contentVisibilityChecker.assertCanView(ReferenceType.BLOG_POST, postId, viewerUserId);
        return cmsMapper.toRevisionResponseList(
                revisionRepository.findByBlogPostIdOrderByCreatedAtDesc(postId));
    }

    /**
     * リビジョンから復元する。
     *
     * <p>認可根治戦役 Wave3-B7: 2点の根治を行う。
     * ①書込認可: 投稿者本人 or スコープADMIN以外は403（{@link #checkWriteAccess}）。
     * ②BOLA是正: {@code revisionId} が {@code postId} 配下のリビジョンでない場合
     * （＝他記事のリビジョンIDを流用した越境アクセス）は、存在秘匿のため
     * 単純な不在と同一の {@link CmsErrorCode#REVISION_NOT_FOUND}（404）を返す。</p>
     */
    @Transactional
    public BlogPostResponse restoreRevision(Long postId, Long revisionId, Long userId) {
        BlogPostEntity entity = findPostOrThrow(postId);
        checkWriteAccess(entity, userId);
        BlogPostRevisionEntity revision = revisionRepository.findById(revisionId)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.REVISION_NOT_FOUND));
        if (!revision.getBlogPostId().equals(postId)) {
            // BOLA対策: revisionId が postId 配下でない越境アクセスは、存在秘匿のため不在と同一コードで404。
            throw new BusinessException(CmsErrorCode.REVISION_NOT_FOUND);
        }

        // 現在の状態をリビジョンとして保存
        saveRevision(entity, userId);

        // 復元
        entity.update(revision.getTitle(), entity.getSlug(), revision.getBody(),
                entity.getExcerpt(), entity.getCoverImageUrl(), entity.getVisibility(),
                entity.getPriority(), calculateReadingTime(revision.getBody()));
        entity.changeStatus(PostStatus.DRAFT);

        BlogPostEntity saved = postRepository.save(entity);
        log.info("リビジョン復元: postId={}, revisionId={}", postId, revisionId);
        return cmsMapper.toBlogPostResponse(saved);
    }

    /**
     * リビジョンを保存する。
     *
     * <p>10 版を超える場合は最古のリビジョンを物理削除する。
     * {@link BlogPostService#updatePost} からも呼び出されるため public で公開する
     * （他ドメインからの直接呼び出しは想定しない、ファサード越しに利用すること）。
     *
     * <p>このメソッドは {@link BlogPostService#updatePost} の {@code @Transactional} 内から
     * 呼ばれ、Spring の Self-Invocation 回避のために別 Bean を経由する。
     * 呼び出し元のトランザクションに参加するよう {@code Propagation.REQUIRED}（デフォルト）で動作する。
     */
    @Transactional
    public void saveRevision(BlogPostEntity entity, Long editorId) {
        long count = revisionRepository.countByBlogPostId(entity.getId());

        // 10版を超える場合は最古のリビジョンを削除
        if (count >= 10) {
            revisionRepository.findFirstByBlogPostIdOrderByRevisionNumberAsc(entity.getId())
                    .ifPresent(revisionRepository::delete);
        }

        BlogPostRevisionEntity revision = BlogPostRevisionEntity.builder()
                .blogPostId(entity.getId())
                .revisionNumber((int) count + 1)
                .title(entity.getTitle())
                .body(entity.getBody())
                .editorId(editorId)
                .build();
        revisionRepository.save(revision);
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

    /**
     * 推定読了時間を算出する（日本語: 500文字/分、最小1分）。
     */
    private short calculateReadingTime(String body) {
        if (body == null || body.isEmpty()) {
            return 1;
        }
        int charCount = body.length();
        int minutes = (int) Math.ceil((double) charCount / 500);
        return (short) Math.max(1, minutes);
    }
}
