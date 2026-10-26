package com.mannschaft.app.publicview.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.dto.PublicScopeRef;
import com.mannschaft.app.publicview.dto.PublicTimelinePostResponse;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * F19.1 Phase 7 公開タイムライン投稿クエリサービス。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.2 Phase 7</p>
 *
 * <p>チーム / 組織の {@code timeline_posts_public} フラグが {@code true} の場合のみ
 * 未ログインユーザーへのタイムライン投稿一覧を返す。
 * フラグが {@code false}、またはチーム / 組織が PRIVATE / archived / 削除済 / 不在の場合は
 * {@link PublicViewErrorCode#PUBLIC_001}（404 へ正規化）を返す（IDOR 対策）。</p>
 *
 * <p><strong>クロスドメイン注意</strong>: publicview ドメインから timeline ドメインの
 * Repository を直接参照しているため、CLAUDE.md アーキテクチャ原則 §5 に従いコメントを付与する。
 * TODO: publicview→timeline クロスドメイン。将来は TimelinePostPublishedEvent で分離予定。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class PublicTimelinePostQueryService {

    // TODO: クロスドメイン参照(publicview→team)。将来はイベント駆動で分離予定
    private final TeamRepository teamRepository;

    // TODO: クロスドメイン参照(publicview→organization)。将来はイベント駆動で分離予定
    private final OrganizationRepository organizationRepository;

    // TODO: クロスドメイン参照(publicview→timeline)。将来はイベント駆動で分離予定
    private final TimelinePostRepository timelinePostRepository;

    // ────────────────────────────────────────────────────────────
    // チームスコープ
    // ────────────────────────────────────────────────────────────

    /**
     * チームの公開タイムライン投稿一覧を取得する。
     *
     * <p>チームが PUBLIC かつ {@code timeline_posts_public=true} の場合のみ返す。
     * そうでない場合は {@link PublicViewErrorCode#PUBLIC_001}（404）を投げる。</p>
     *
     * @param teamId   対象チーム ID
     * @param pageable ページネーション
     * @return 公開タイムライン投稿のページ
     * @throws BusinessException チームが PUBLIC でないか timeline_posts_public=false の場合
     */
    public Page<PublicTimelinePostResponse> getTeamTimelinePosts(Long teamId, Pageable pageable) {
        // チームが PUBLIC か確認（PRIVATE / 不在 は 404 で隠蔽）
        TeamEntity team = teamRepository.findPublicTeamById(teamId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_001));

        // timeline_posts_public フラグを確認（false の場合は 404 で隠蔽）
        if (!team.isTimelinePostsPublic()) {
            throw new BusinessException(PublicViewErrorCode.PUBLIC_001);
        }

        PublicScopeRef scopeRef = PublicScopeRef.ofTeam(teamId, team.getName());
        return timelinePostRepository.findPublicByTeamId(teamId, pageable)
                .map(post -> toResponse(post, scopeRef));
    }

    // ────────────────────────────────────────────────────────────
    // 組織スコープ
    // ────────────────────────────────────────────────────────────

    /**
     * 組織の公開タイムライン投稿一覧を取得する。
     *
     * <p>組織が PUBLIC かつ {@code timeline_posts_public=true} の場合のみ返す。
     * そうでない場合は {@link PublicViewErrorCode#PUBLIC_001}（404）を投げる。</p>
     *
     * @param orgId    対象組織 ID
     * @param pageable ページネーション
     * @return 公開タイムライン投稿のページ
     * @throws BusinessException 組織が PUBLIC でないか timeline_posts_public=false の場合
     */
    public Page<PublicTimelinePostResponse> getOrganizationTimelinePosts(Long orgId, Pageable pageable) {
        // 組織が PUBLIC か確認（PRIVATE / 不在 は 404 で隠蔽）
        OrganizationEntity org = organizationRepository.findPublicOrganizationById(orgId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_001));

        // timeline_posts_public フラグを確認（false の場合は 404 で隠蔽）
        if (!org.isTimelinePostsPublic()) {
            throw new BusinessException(PublicViewErrorCode.PUBLIC_001);
        }

        PublicScopeRef scopeRef = PublicScopeRef.ofOrganization(orgId, org.getName());
        return timelinePostRepository.findPublicByOrganizationId(orgId, pageable)
                .map(post -> toResponse(post, scopeRef));
    }

    // ────────────────────────────────────────────────────────────
    // 内部ヘルパ
    // ────────────────────────────────────────────────────────────

    /**
     * TimelinePostEntity を PublicTimelinePostResponse に変換する。
     *
     * <p>本文は 200 文字でトリミングする（PII 漏洩防止・パフォーマンス）。</p>
     */
    private PublicTimelinePostResponse toResponse(TimelinePostEntity post, PublicScopeRef scopeRef) {
        return new PublicTimelinePostResponse(
                post.getId(),
                truncate(post.getContent(), 200),
                scopeRef,
                toOffsetDateTime(post.getCreatedAt())
        );
    }

    /**
     * 文字列を指定の最大文字数でトリミングする。
     */
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * LocalDateTime を OffsetDateTime に変換する（アプリ層の基準ゾーン
     * {@link UserZoneLocalDateTimeParser#SERVER_ZONE} を使用）。
     */
    private static OffsetDateTime toOffsetDateTime(LocalDateTime ldt) {
        if (ldt == null) {
            return null;
        }
        return ldt.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toOffsetDateTime();
    }
}
