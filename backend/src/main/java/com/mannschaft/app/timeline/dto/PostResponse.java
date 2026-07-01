package com.mannschaft.app.timeline.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * タイムライン投稿レスポンスDTO（一覧用）。
 *
 * <p>個人集約タイムライン（{@code GET /api/v1/timeline/my}）では、投稿元スコープ名/slug・
 * 著者表示名/アバター・代理投稿主体（team/org 名・ロゴ）をバッチ enrich して付与する
 * （{@code scope.name}/{@code scope.slug}・{@code user}・{@code postedAs}）。
 * enrich しない経路（スコープ別 feed 等）ではこれらは {@code null} のまま返る（非劣化）。</p>
 */
@Builder(toBuilder = true)
@Getter
public class PostResponse {

    private final Long id;
    private final PostScopeDto scope;
    private final PostAuthorDto author;
    private final PostContentDto content;
    private final PostStatsDto stats;
    private final PostAuditDto audit;

    /**
     * 著者ユーザー（表示名・アバター）。個人集約タイムラインで enrich される。
     * 代理投稿（{@code postedAs} が非 null）でない場合に FE がヘッダー表示に使う。
     * enrich しない経路では {@code null}。
     */
    private final PostUserDto user;

    /**
     * 代理投稿主体（team/org として投稿した場合の名前・ロゴ）。
     * {@code postedAsType} が TEAM/ORGANIZATION のときのみ enrich され、USER の場合は {@code null}。
     */
    private final PostPostedAsDto postedAs;

    /**
     * 投稿スコープ。
     *
     * <p>{@code name}/{@code slug} は個人集約タイムラインで投稿元（TEAM/ORGANIZATION）を
     * 表示・遷移させるために enrich される。それ以外の経路では {@code null}。</p>
     */
    public record PostScopeDto(String scopeType, Long scopeId, String name, String slug) {
        /** 後方互換: name/slug を持たない従来の 2 引数コンストラクタ（enrich 前の既定経路用）。 */
        public PostScopeDto(String scopeType, Long scopeId) {
            this(scopeType, scopeId, null, null);
        }
    }

    public record PostAuthorDto(Long userId, Long socialProfileId, String postedAsType, Long postedAsId) {}

    public record PostContentDto(String content, Long parentId, Long repostOfId, String status,
                                 LocalDateTime scheduledAt, Boolean isPinned) {}

    public record PostStatsDto(Integer repostCount, Integer reactionCount, Integer replyCount,
                               Short attachmentCount, Short editCount) {}

    public record PostAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt) {}

    /** 著者ユーザーの表示情報。FE の {@code TimelineUser} と 1:1 対応する。 */
    public record PostUserDto(Long id, String displayName, String avatarUrl) {}

    /** 代理投稿主体（team/org）の表示情報。FE の {@code PostedAs} と 1:1 対応する。 */
    public record PostPostedAsDto(String type, Long id, String name, String displayName,
                                  String logoUrl, String handle, String avatarUrl) {}
}
