package com.mannschaft.app.cms.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ブログ記事レスポンスDTO（ネスト設計版）。
 *
 * <p>リファクタリング Wave 1 第三陣: 26フィールドをトップレベル8個のサブDTOに整理した。
 * {@code @Builder(toBuilder=true)} により {@link #withReaction(boolean, int)} を
 * イミュータブルに実装している。
 */
@Getter
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BlogPostResponse {

    private Long id;
    private BlogPostScopeDto scope;
    private BlogPostContentDto content;
    private BlogPostMetaDto meta;
    private BlogPostSeriesDto series;
    private BlogPostStatisticsDto stats;
    private List<TagSummary> tags;
    private BlogPostAuditDto audit;
    /** 課金合成状態（FULL/LOCKED）。HIDDEN は404または一覧除外のため返さない。 */
    private String accessState;

    /** 投稿スコープ（チーム/組織/ユーザー/投稿者ID）。 */
    public record BlogPostScopeDto(
            Long teamId,
            Long organizationId,
            Long userId,
            Long authorId
    ) {}

    /** 投稿コンテンツ（タイトル/スラッグ/本文/要約/サムネイル）。 */
    public record BlogPostContentDto(
            String title,
            String slug,
            String body,
            String excerpt,
            String coverImageUrl
    ) {}

    /** 投稿メタ情報（種別/公開範囲/優先度/ステータス/ピン留め/コメント許可）。 */
    public record BlogPostMetaDto(
            String postType,
            String visibility,
            String priority,
            String status,
            Boolean pinned,
            Boolean allowComments
    ) {}

    /** シリーズ情報（シリーズID/順序）。 */
    public record BlogPostSeriesDto(
            Long seriesId,
            Short seriesOrder
    ) {}

    /** 統計情報（閲覧数/読了時間/みたよ状態・件数）。 */
    public record BlogPostStatisticsDto(
            Integer viewCount,
            Short readingTimeMinutes,
            boolean mitayo,
            int mitayoCount
    ) {}

    /** 監査情報（公開日時/バージョン/作成日時/更新日時）。 */
    public record BlogPostAuditDto(
            LocalDateTime publishedAt,
            Integer version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    /** タグの要約情報。 */
    public record TagSummary(Long id, String name, String color) {}

    /**
     * リアクション情報（みたよ！状態・件数）を付与した新しいインスタンスを返す。
     *
     * <p>{@code toBuilder()} を使ったイミュータブル更新。stats フィールドのみ差し替える。
     */
    public BlogPostResponse withReaction(boolean mitayo, int mitayoCount) {
        return this.toBuilder()
                .stats(new BlogPostStatisticsDto(
                        this.stats != null ? this.stats.viewCount() : null,
                        this.stats != null ? this.stats.readingTimeMinutes() : null,
                        mitayo,
                        mitayoCount
                ))
                .build();
    }

    public BlogPostResponse withAccessState(String state) {
        return this.toBuilder().accessState(state).build();
    }
}
