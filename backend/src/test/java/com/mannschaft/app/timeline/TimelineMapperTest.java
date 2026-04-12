package com.mannschaft.app.timeline;

import com.mannschaft.app.timeline.dto.BookmarkResponse;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.entity.TimelineBookmarkEntity;
import com.mannschaft.app.timeline.entity.TimelinePollOptionEntity;
import com.mannschaft.app.timeline.entity.TimelinePostAttachmentEntity;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.entity.TimelinePostReactionEntity;
import com.mannschaft.app.timeline.entity.UserMuteEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TimelineMapper} (MapStruct生成実装) の単体テスト。
 */
@DisplayName("TimelineMapper 単体テスト")
class TimelineMapperTest {

    private final TimelineMapper mapper = new TimelineMapperImpl();

    @Nested
    @DisplayName("toPostResponse")
    class ToPostResponse {

        @Test
        @DisplayName("投稿エンティティ変換_正常_フィールドが正しくマップされる")
        void 投稿エンティティ変換_正常_フィールドが正しくマップされる() {
            TimelinePostEntity entity = TimelinePostEntity.builder()
                    .scopeType(PostScopeType.TEAM).scopeId(10L).userId(1L)
                    .postedAsType(PostedAsType.USER).content("テスト").status(PostStatus.PUBLISHED).build();
            PostResponse response = mapper.toPostResponse(entity);
            assertThat(response).isNotNull();
            assertThat(response.getScopeType()).isEqualTo("TEAM");
            assertThat(response.getStatus()).isEqualTo("PUBLISHED");
        }

        @Test
        @DisplayName("投稿エンティティ変換_null_nullを返す")
        void 投稿エンティティ変換_null_nullを返す() {
            assertThat(mapper.toPostResponse(null)).isNull();
        }

        @Test
        @DisplayName("投稿エンティティ変換_ORGANIZATION_正常変換")
        void 投稿エンティティ変換_ORGANIZATION_正常変換() {
            TimelinePostEntity entity = TimelinePostEntity.builder()
                    .scopeType(PostScopeType.ORGANIZATION).scopeId(5L).userId(2L)
                    .postedAsType(PostedAsType.TEAM).status(PostStatus.DRAFT).build();
            assertThat(mapper.toPostResponse(entity).getPostedAsType()).isEqualTo("TEAM");
        }
    }

    @Nested
    @DisplayName("toPostResponseList")
    class ToPostResponseList {

        @Test
        @DisplayName("投稿リスト変換_null_nullを返す")
        void 投稿リスト変換_null_nullを返す() {
            assertThat(mapper.toPostResponseList(null)).isNull();
        }

        @Test
        @DisplayName("投稿リスト変換_正常_全要素変換")
        void 投稿リスト変換_正常_全要素変換() {
            TimelinePostEntity e = TimelinePostEntity.builder()
                    .scopeType(PostScopeType.PUBLIC).scopeId(0L).userId(1L)
                    .postedAsType(PostedAsType.USER).status(PostStatus.PUBLISHED).build();
            assertThat(mapper.toPostResponseList(List.of(e))).hasSize(1);
        }

        @Test
        @DisplayName("投稿リスト変換_空リスト_空リストを返す")
        void 投稿リスト変換_空リスト_空リストを返す() {
            assertThat(mapper.toPostResponseList(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("toAttachmentResponse")
    class ToAttachmentResponse {

        @Test
        @DisplayName("添付ファイルエンティティ変換_正常_フィールドが正しくマップされる")
        void 添付ファイルエンティティ変換_正常_フィールドが正しくマップされる() {
            TimelinePostAttachmentEntity entity = TimelinePostAttachmentEntity.builder()
                    .timelinePostId(100L).attachmentType(AttachmentType.IMAGE)
                    .fileKey("images/test.jpg").build();
            assertThat(mapper.toAttachmentResponse(entity).getAttachmentType()).isEqualTo("IMAGE");
        }

        @Test
        @DisplayName("添付ファイルエンティティ変換_null_nullを返す")
        void 添付ファイルエンティティ変換_null_nullを返す() {
            assertThat(mapper.toAttachmentResponse(null)).isNull();
        }
    }

    @Nested
    @DisplayName("toAttachmentResponseList")
    class ToAttachmentResponseList {

        @Test
        @DisplayName("添付ファイルリスト変換_null_nullを返す")
        void 添付ファイルリスト変換_null_nullを返す() {
            assertThat(mapper.toAttachmentResponseList(null)).isNull();
        }

        @Test
        @DisplayName("添付ファイルリスト変換_正常_全要素変換")
        void 添付ファイルリスト変換_正常_全要素変換() {
            TimelinePostAttachmentEntity e = TimelinePostAttachmentEntity.builder()
                    .timelinePostId(1L).attachmentType(AttachmentType.VIDEO_FILE).fileKey("video.mp4").build();
            assertThat(mapper.toAttachmentResponseList(List.of(e))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("toReactionResponse")
    class ToReactionResponse {

        @Test
        @DisplayName("リアクションエンティティ変換_正常_フィールドが正しくマップされる")
        void リアクションエンティティ変換_正常_フィールドが正しくマップされる() {
            TimelinePostReactionEntity entity = TimelinePostReactionEntity.builder()
                    .timelinePostId(50L).userId(1L).emoji("👍").build();
            assertThat(mapper.toReactionResponse(entity).getEmoji()).isEqualTo("👍");
        }

        @Test
        @DisplayName("リアクションエンティティ変換_null_nullを返す")
        void リアクションエンティティ変換_null_nullを返す() {
            assertThat(mapper.toReactionResponse(null)).isNull();
        }
    }

    @Nested
    @DisplayName("toReactionResponseList")
    class ToReactionResponseList {

        @Test
        @DisplayName("リアクションリスト変換_null_nullを返す")
        void リアクションリスト変換_null_nullを返す() {
            assertThat(mapper.toReactionResponseList(null)).isNull();
        }

        @Test
        @DisplayName("リアクションリスト変換_正常_全要素変換")
        void リアクションリスト変換_正常_全要素変換() {
            TimelinePostReactionEntity e = TimelinePostReactionEntity.builder()
                    .timelinePostId(1L).userId(1L).emoji("❤️").build();
            assertThat(mapper.toReactionResponseList(List.of(e))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("toBookmarkResponse")
    class ToBookmarkResponse {

        @Test
        @DisplayName("ブックマークエンティティ変換_正常_フィールドが正しくマップされる")
        void ブックマークエンティティ変換_正常_フィールドが正しくマップされる() {
            TimelineBookmarkEntity entity = TimelineBookmarkEntity.builder()
                    .userId(1L).timelinePostId(100L).build();
            BookmarkResponse response = mapper.toBookmarkResponse(entity);
            assertThat(response.getTimelinePostId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("ブックマークエンティティ変換_null_nullを返す")
        void ブックマークエンティティ変換_null_nullを返す() {
            assertThat(mapper.toBookmarkResponse(null)).isNull();
        }
    }

    @Nested
    @DisplayName("toBookmarkResponseList")
    class ToBookmarkResponseList {

        @Test
        @DisplayName("ブックマークリスト変換_null_nullを返す")
        void ブックマークリスト変換_null_nullを返す() {
            assertThat(mapper.toBookmarkResponseList(null)).isNull();
        }

        @Test
        @DisplayName("ブックマークリスト変換_正常_全要素変換")
        void ブックマークリスト変換_正常_全要素変換() {
            TimelineBookmarkEntity e = TimelineBookmarkEntity.builder().userId(1L).timelinePostId(10L).build();
            assertThat(mapper.toBookmarkResponseList(List.of(e))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("toPollOptionResponse")
    class ToPollOptionResponse {

        @Test
        @DisplayName("投票選択肢エンティティ変換_正常_フィールドが正しくマップされる")
        void 投票選択肢エンティティ変換_正常_フィールドが正しくマップされる() {
            TimelinePollOptionEntity entity = TimelinePollOptionEntity.builder()
                    .timelinePollId(5L).optionText("選択肢A").voteCount(10).sortOrder((short) 0).build();
            assertThat(mapper.toPollOptionResponse(entity).getOptionText()).isEqualTo("選択肢A");
        }

        @Test
        @DisplayName("投票選択肢エンティティ変換_null_nullを返す")
        void 投票選択肢エンティティ変換_null_nullを返す() {
            assertThat(mapper.toPollOptionResponse(null)).isNull();
        }
    }

    @Nested
    @DisplayName("toPollOptionResponseList")
    class ToPollOptionResponseList {

        @Test
        @DisplayName("投票選択肢リスト変換_null_nullを返す")
        void 投票選択肢リスト変換_null_nullを返す() {
            assertThat(mapper.toPollOptionResponseList(null)).isNull();
        }

        @Test
        @DisplayName("投票選択肢リスト変換_正常_全要素変換")
        void 投票選択肢リスト変換_正常_全要素変換() {
            TimelinePollOptionEntity e = TimelinePollOptionEntity.builder()
                    .timelinePollId(1L).optionText("はい").voteCount(5).sortOrder((short) 0).build();
            assertThat(mapper.toPollOptionResponseList(List.of(e))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("toMuteResponse")
    class ToMuteResponse {

        @Test
        @DisplayName("ミュートエンティティ変換_正常_フィールドが正しくマップされる")
        void ミュートエンティティ変換_正常_フィールドが正しくマップされる() {
            UserMuteEntity entity = UserMuteEntity.builder()
                    .userId(1L).mutedType("USER").mutedId(99L).build();
            assertThat(mapper.toMuteResponse(entity).getMutedType()).isEqualTo("USER");
        }

        @Test
        @DisplayName("ミュートエンティティ変換_null_nullを返す")
        void ミュートエンティティ変換_null_nullを返す() {
            assertThat(mapper.toMuteResponse(null)).isNull();
        }
    }

    @Nested
    @DisplayName("toMuteResponseList")
    class ToMuteResponseList {

        @Test
        @DisplayName("ミュートリスト変換_null_nullを返す")
        void ミュートリスト変換_null_nullを返す() {
            assertThat(mapper.toMuteResponseList(null)).isNull();
        }

        @Test
        @DisplayName("ミュートリスト変換_正常_全要素変換")
        void ミュートリスト変換_正常_全要素変換() {
            UserMuteEntity e = UserMuteEntity.builder().userId(1L).mutedType("TEAM").mutedId(20L).build();
            assertThat(mapper.toMuteResponseList(List.of(e))).hasSize(1);
        }
    }
}
