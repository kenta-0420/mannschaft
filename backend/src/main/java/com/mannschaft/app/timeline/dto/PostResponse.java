package com.mannschaft.app.timeline.dto;

import com.mannschaft.app.village.entity.enums.VillageEventNotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

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
     * F17.2 Wave2 ①: システム投稿（{@code systemPostType} 非 null）では常に {@code null}（設計書 §3.9(a)）。
     */
    private final PostPostedAsDto postedAs;

    /**
     * F17.2 Wave2 ①: システム自動投稿の種別（村行事の還流・設計書 §3.9(a)）。
     *
     * <p>非 null の場合、この投稿は「村の行事案内」名義のシステム自動投稿であり、
     * 投稿主体（{@code postedAs}）・投稿者（{@code user}）は存在しない（いずれも {@code null}）。
     * FE は「{@code systemPostType} があれば村の行事案内名義、無ければ {@code postedAs}/{@code user} を読む」
     * と単純分岐する。通常投稿は {@code null}。</p>
     */
    @Schema(description = "システム自動投稿の種別（村行事の還流）。非nullなら村の行事案内名義のシステム投稿。通常投稿はnull。",
            nullable = true)
    private final VillageEventNotificationType systemPostType;

    /**
     * 投稿の添付ファイル（画像・動画・リンクプレビュー）。
     *
     * <p>issue #2424 根治: 一覧（feed）でも投稿画像を表示できるよう、詳細（{@link PostDetailResponse}）
     * と同じ添付配列を feed でも返す。画像添付の {@code image.url}/{@code image.thumbnailUrl} は
     * {@code MediaUrlResolver} で解決した署名付き表示 URL。添付が無い投稿では空配列を返す
     * （{@code null} ではなく空配列とし FE の {@code attachments?.length} 分岐を安定させる）。
     * enrich しない経路（旧実装）では組み立てず、Service の添付付与を通した経路でのみ設定される。</p>
     */
    private final List<AttachmentResponse> attachments;

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
