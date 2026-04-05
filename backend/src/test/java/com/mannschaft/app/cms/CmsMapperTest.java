package com.mannschaft.app.cms;

import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.BlogSeriesResponse;
import com.mannschaft.app.cms.dto.BlogSettingsResponse;
import com.mannschaft.app.cms.dto.BlogTagResponse;
import com.mannschaft.app.cms.dto.RevisionResponse;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.entity.BlogPostRevisionEntity;
import com.mannschaft.app.cms.entity.BlogPostSeriesEntity;
import com.mannschaft.app.cms.entity.BlogTagEntity;
import com.mannschaft.app.cms.entity.UserBlogSettingsEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CmsMapper} の単体テスト。MapStruct生成実装を直接インスタンス化して検証する。
 */
@DisplayName("CmsMapper 単体テスト")
class CmsMapperTest {

    private final CmsMapper mapper = new CmsMapperImpl();

    // ========================================
    // toBlogPostResponse
    // ========================================

    @Nested
    @DisplayName("toBlogPostResponse")
    class ToBlogPostResponse {

        @Test
        @DisplayName("正常系: BlogPostEntity → BlogPostResponse に変換される")
        void ブログ記事エンティティ_DTO変換_正常() {
            BlogPostEntity entity = BlogPostEntity.builder()
                    .teamId(1L)
                    .authorId(10L)
                    .title("テスト記事")
                    .slug("test-post")
                    .body("本文テスト")
                    .excerpt("抜粋")
                    .postType(PostType.BLOG)
                    .visibility(Visibility.MEMBERS_ONLY)
                    .priority(PostPriority.NORMAL)
                    .status(PostStatus.DRAFT)
                    .readingTimeMinutes((short) 2)
                    .build();

            BlogPostResponse result = mapper.toBlogPostResponse(entity);

            assertThat(result).isNotNull();
            assertThat(result.getTitle()).isEqualTo("テスト記事");
            assertThat(result.getSlug()).isEqualTo("test-post");
            assertThat(result.getPostType()).isEqualTo("BLOG");
            assertThat(result.getVisibility()).isEqualTo("MEMBERS_ONLY");
            assertThat(result.getPriority()).isEqualTo("NORMAL");
            assertThat(result.getStatus()).isEqualTo("DRAFT");
            assertThat(result.getTags()).isEmpty();
        }

        @Test
        @DisplayName("正常系: ANNOUNCEMENT postType → 文字列 ANNOUNCEMENT")
        void アナウンスメント記事_postType変換_正常() {
            BlogPostEntity entity = BlogPostEntity.builder()
                    .teamId(1L)
                    .authorId(10L)
                    .title("お知らせ")
                    .slug("announcement")
                    .body("本文")
                    .postType(PostType.ANNOUNCEMENT)
                    .visibility(Visibility.PUBLIC)
                    .priority(PostPriority.IMPORTANT)
                    .status(PostStatus.PUBLISHED)
                    .build();

            BlogPostResponse result = mapper.toBlogPostResponse(entity);

            assertThat(result.getPostType()).isEqualTo("ANNOUNCEMENT");
            assertThat(result.getVisibility()).isEqualTo("PUBLIC");
            assertThat(result.getPriority()).isEqualTo("IMPORTANT");
            assertThat(result.getStatus()).isEqualTo("PUBLISHED");
        }
    }

    // ========================================
    // toBlogPostResponseList
    // ========================================

    @Nested
    @DisplayName("toBlogPostResponseList")
    class ToBlogPostResponseList {

        @Test
        @DisplayName("正常系: エンティティリスト → DTOリストに変換される")
        void エンティティリスト_DTOリスト変換_正常() {
            BlogPostEntity e1 = BlogPostEntity.builder()
                    .teamId(1L).authorId(10L).title("記事1").slug("post-1").body("本文1")
                    .postType(PostType.BLOG).visibility(Visibility.MEMBERS_ONLY)
                    .priority(PostPriority.NORMAL).status(PostStatus.DRAFT).build();
            BlogPostEntity e2 = BlogPostEntity.builder()
                    .teamId(1L).authorId(10L).title("記事2").slug("post-2").body("本文2")
                    .postType(PostType.BLOG).visibility(Visibility.PUBLIC)
                    .priority(PostPriority.NORMAL).status(PostStatus.PUBLISHED).build();

            List<BlogPostResponse> result = mapper.toBlogPostResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getTitle()).isEqualTo("記事1");
            assertThat(result.get(1).getTitle()).isEqualTo("記事2");
        }

        @Test
        @DisplayName("正常系: 空リスト → 空リストに変換される")
        void 空リスト_空リスト変換_正常() {
            List<BlogPostResponse> result = mapper.toBlogPostResponseList(List.of());

            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // toBlogTagResponse
    // ========================================

    @Nested
    @DisplayName("toBlogTagResponse")
    class ToBlogTagResponse {

        @Test
        @DisplayName("正常系: BlogTagEntity → BlogTagResponse に変換される")
        void タグエンティティ_DTO変換_正常() {
            BlogTagEntity entity = BlogTagEntity.builder()
                    .teamId(1L)
                    .name("タグ名")
                    .color("#FF0000")
                    .postCount(5)
                    .sortOrder(1)
                    .build();

            BlogTagResponse result = mapper.toBlogTagResponse(entity);

            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("タグ名");
            assertThat(result.getColor()).isEqualTo("#FF0000");
            assertThat(result.getPostCount()).isEqualTo(5);
            assertThat(result.getSortOrder()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系: タグリスト変換")
        void タグリスト_DTO変換_正常() {
            BlogTagEntity e1 = BlogTagEntity.builder().teamId(1L).name("タグA").build();
            BlogTagEntity e2 = BlogTagEntity.builder().teamId(1L).name("タグB").build();

            List<BlogTagResponse> result = mapper.toBlogTagResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
        }
    }

    // ========================================
    // toBlogSeriesResponse
    // ========================================

    @Nested
    @DisplayName("toBlogSeriesResponse")
    class ToBlogSeriesResponse {

        @Test
        @DisplayName("正常系: BlogPostSeriesEntity → BlogSeriesResponse に変換される（postCount=0）")
        void シリーズエンティティ_DTO変換_正常_postCount0() {
            BlogPostSeriesEntity entity = BlogPostSeriesEntity.builder()
                    .teamId(1L)
                    .name("シリーズ名")
                    .description("シリーズ説明")
                    .createdBy(10L)
                    .build();

            BlogSeriesResponse result = mapper.toBlogSeriesResponse(entity);

            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("シリーズ名");
            assertThat(result.getDescription()).isEqualTo("シリーズ説明");
            assertThat(result.getPostCount()).isEqualTo(0L);
        }

        @Test
        @DisplayName("正常系: シリーズリスト変換")
        void シリーズリスト_DTO変換_正常() {
            BlogPostSeriesEntity e1 = BlogPostSeriesEntity.builder().teamId(1L).name("シリーズ1").build();
            BlogPostSeriesEntity e2 = BlogPostSeriesEntity.builder().teamId(1L).name("シリーズ2").build();

            List<BlogSeriesResponse> result = mapper.toBlogSeriesResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getPostCount()).isEqualTo(0L);
        }
    }

    // ========================================
    // toRevisionResponse
    // ========================================

    @Nested
    @DisplayName("toRevisionResponse")
    class ToRevisionResponse {

        @Test
        @DisplayName("正常系: BlogPostRevisionEntity → RevisionResponse に変換される")
        void リビジョンエンティティ_DTO変換_正常() {
            BlogPostRevisionEntity entity = BlogPostRevisionEntity.builder()
                    .blogPostId(1L)
                    .revisionNumber(3)
                    .title("リビジョンタイトル")
                    .body("リビジョン本文")
                    .editorId(100L)
                    .changeSummary("変更概要")
                    .build();

            RevisionResponse result = mapper.toRevisionResponse(entity);

            assertThat(result).isNotNull();
            assertThat(result.getRevisionNumber()).isEqualTo(3);
            assertThat(result.getTitle()).isEqualTo("リビジョンタイトル");
            assertThat(result.getEditorId()).isEqualTo(100L);
            assertThat(result.getChangeSummary()).isEqualTo("変更概要");
        }

        @Test
        @DisplayName("正常系: リビジョンリスト変換")
        void リビジョンリスト_DTO変換_正常() {
            BlogPostRevisionEntity e1 = BlogPostRevisionEntity.builder()
                    .blogPostId(1L).revisionNumber(1).title("Rev1").body("本文1").build();
            BlogPostRevisionEntity e2 = BlogPostRevisionEntity.builder()
                    .blogPostId(1L).revisionNumber(2).title("Rev2").body("本文2").build();

            List<RevisionResponse> result = mapper.toRevisionResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
        }
    }

    // ========================================
    // toBlogSettingsResponse
    // ========================================

    @Nested
    @DisplayName("toBlogSettingsResponse")
    class ToBlogSettingsResponse {

        @Test
        @DisplayName("正常系: UserBlogSettingsEntity → BlogSettingsResponse に変換される")
        void ブログ設定エンティティ_DTO変換_正常() {
            UserBlogSettingsEntity entity = UserBlogSettingsEntity.builder()
                    .userId(10L)
                    .selfReviewEnabled(true)
                    .selfReviewStart(LocalTime.of(22, 0))
                    .selfReviewEnd(LocalTime.of(7, 0))
                    .build();

            BlogSettingsResponse result = mapper.toBlogSettingsResponse(entity);

            assertThat(result).isNotNull();
            assertThat(result.getSelfReviewEnabled()).isTrue();
            assertThat(result.getSelfReviewStart()).isEqualTo(LocalTime.of(22, 0));
            assertThat(result.getSelfReviewEnd()).isEqualTo(LocalTime.of(7, 0));
        }
    }
}
