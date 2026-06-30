package com.mannschaft.app.publicview.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.publicview.dto.PublicUserPostSummaryResponse;
import com.mannschaft.app.publicview.dto.PublicUserProfileResponse;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F19.1 Phase 6: 公開ユーザープロフィール用クエリサービス。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.6 Phase 6</p>
 *
 * <p><strong>IDOR 対策</strong>: 存在しないユーザー・非公開ユーザー・削除済みユーザーを
 * 区別せず一律 {@link PublicViewErrorCode#PUBLIC_007}（404 へ正規化）を返す。</p>
 *
 * <p><strong>クロスドメイン参照について</strong>: 本サービスは publicview → cms / team の
 * クロスドメイン参照を行う。将来のマイクロサービス分割時はイベント駆動化を検討すること。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class PublicUserProfileQueryService {

    private final UserRepository userRepository;
    private final BlogPostRepository blogPostRepository;
    // TODO: publicview → team のクロスドメイン参照。将来はチーム名をスナップショットで保持する方式に移行予定。
    private final TeamRepository teamRepository;
    private final MediaUrlResolver mediaUrlResolver;

    /**
     * 公開プロフィールを取得する。
     *
     * <p>{@code public_profile_enabled = false} のユーザーや存在しないユーザーは
     * IDOR 対策のため一律 404 を返す。</p>
     *
     * @param userId ユーザー ID
     * @return 抑制版プロフィール DTO
     * @throws BusinessException ユーザー不在 / 非公開（{@link PublicViewErrorCode#PUBLIC_007}、404 へ正規化）
     */
    public PublicUserProfileResponse getPublicProfile(Long userId) {
        UserEntity user = userRepository.findById(userId)
                .filter(u -> u.isPublicProfileEnabled())
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_007));

        return new PublicUserProfileResponse(
                user.getId(),
                user.getDisplayName(),
                // 画像 URL 根治 Phase 2: 生 R2 キーを署名付き表示 URL へ解決
                mediaUrlResolver.resolve(user.getAvatarUrl()),
                user.getCreatedAt().toLocalDate()
        );
    }

    /**
     * 公開ユーザーの投稿一覧を取得する。
     *
     * <p>{@code visibility = PUBLIC} かつ {@code status = PUBLISHED} かつ
     * {@code public_visible = true} の投稿のみ返す。
     * 非公開ユーザーへのアクセスは一律 404 を返す（IDOR 対策）。</p>
     *
     * <p>TODO: publicview → cms/team のクロスドメイン参照。将来はイベント駆動化候補。</p>
     *
     * @param userId   著者ユーザー ID
     * @param pageable ページネーション
     * @return 公開投稿サマリーのページ
     * @throws BusinessException ユーザー不在 / 非公開（{@link PublicViewErrorCode#PUBLIC_007}、404 へ正規化）
     */
    public Page<PublicUserPostSummaryResponse> getPublicPosts(Long userId, Pageable pageable) {
        // まずユーザーが公開プロフィール有効かどうか確認（IDOR 対策：存在確認と公開確認を同時に行う）
        userRepository.findById(userId)
                .filter(u -> u.isPublicProfileEnabled())
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_007));

        return blogPostRepository.findPublicPostsByAuthorId(userId, pageable)
                .map(post -> toPostSummary(post));
    }

    /**
     * BlogPostEntity を PublicUserPostSummaryResponse に変換する。
     *
     * <p>チーム ID が存在する場合はチーム名を取得してスコープ情報に設定する。
     * 組織 ID が存在する場合は "ORGANIZATION" スコープとして設定する。</p>
     */
    private PublicUserPostSummaryResponse toPostSummary(BlogPostEntity post) {
        if (post.getTeamId() != null) {
            TeamEntity team = teamRepository.findById(post.getTeamId()).orElse(null);
            return new PublicUserPostSummaryResponse(
                    post.getId(),
                    post.getTitle(),
                    "TEAM",
                    team != null ? team.getName() : "",
                    post.getTeamId() != null ? String.valueOf(post.getTeamId()) : "",
                    post.getCreatedAt()
            );
        } else {
            return new PublicUserPostSummaryResponse(
                    post.getId(),
                    post.getTitle(),
                    "ORGANIZATION",
                    "",
                    post.getOrganizationId() != null ? String.valueOf(post.getOrganizationId()) : "",
                    post.getCreatedAt()
            );
        }
    }
}
