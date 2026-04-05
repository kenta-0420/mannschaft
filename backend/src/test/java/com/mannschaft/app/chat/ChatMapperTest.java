package com.mannschaft.app.chat;

import com.mannschaft.app.chat.dto.AttachmentResponse;
import com.mannschaft.app.chat.dto.ChannelResponse;
import com.mannschaft.app.chat.dto.MessageResponse;
import com.mannschaft.app.chat.dto.ReactionResponse;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity;
import com.mannschaft.app.chat.entity.ChatMessageBookmarkEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.entity.ChatMessageReactionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChatMapper} (MapStruct生成実装) の単体テスト。
 */
@DisplayName("ChatMapper 単体テスト")
class ChatMapperTest {

    private final ChatMapper mapper = new ChatMapperImpl();

    @Nested
    @DisplayName("toChannelResponse")
    class ToChannelResponse {

        @Test
        @DisplayName("チャンネルエンティティ変換_正常_フィールドが正しくマップされる")
        void チャンネルエンティティ変換_正常_フィールドが正しくマップされる() {
            ChatChannelEntity entity = ChatChannelEntity.builder()
                    .channelType(ChannelType.TEAM_PUBLIC).teamId(10L).name("一般").build();
            ChannelResponse response = mapper.toChannelResponse(entity);
            assertThat(response).isNotNull();
            assertThat(response.getChannelType()).isEqualTo("TEAM_PUBLIC");
        }

        @Test
        @DisplayName("チャンネルエンティティ変換_null_nullを返す")
        void チャンネルエンティティ変換_null_nullを返す() {
            assertThat(mapper.toChannelResponse(null)).isNull();
        }

        @Test
        @DisplayName("チャンネルエンティティ変換_DM_正常変換")
        void チャンネルエンティティ変換_DM_正常変換() {
            ChatChannelEntity entity = ChatChannelEntity.builder()
                    .channelType(ChannelType.DM).isPrivate(true).build();
            assertThat(mapper.toChannelResponse(entity).getChannelType()).isEqualTo("DM");
        }
    }

    @Nested
    @DisplayName("toChannelResponseList")
    class ToChannelResponseList {

        @Test
        @DisplayName("チャンネルリスト変換_null_nullを返す")
        void チャンネルリスト変換_null_nullを返す() {
            assertThat(mapper.toChannelResponseList(null)).isNull();
        }

        @Test
        @DisplayName("チャンネルリスト変換_正常_全要素変換")
        void チャンネルリスト変換_正常_全要素変換() {
            ChatChannelEntity e = ChatChannelEntity.builder()
                    .channelType(ChannelType.ORG_PUBLIC).name("org").build();
            assertThat(mapper.toChannelResponseList(List.of(e))).hasSize(1);
        }

        @Test
        @DisplayName("チャンネルリスト変換_空リスト_空リストを返す")
        void チャンネルリスト変換_空リスト_空リストを返す() {
            assertThat(mapper.toChannelResponseList(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("toMessageResponse")
    class ToMessageResponse {

        @Test
        @DisplayName("メッセージエンティティ変換_正常_フィールドが正しくマップされる")
        void メッセージエンティティ変換_正常_フィールドが正しくマップされる() {
            ChatMessageEntity entity = ChatMessageEntity.builder()
                    .channelId(10L).senderId(1L).body("こんにちは！").build();
            MessageResponse response = mapper.toMessageResponse(entity);
            assertThat(response).isNotNull();
            assertThat(response.getBody()).isEqualTo("こんにちは！");
        }

        @Test
        @DisplayName("メッセージエンティティ変換_null_nullを返す")
        void メッセージエンティティ変換_null_nullを返す() {
            assertThat(mapper.toMessageResponse(null)).isNull();
        }
    }

    @Nested
    @DisplayName("toMessageResponseList")
    class ToMessageResponseList {

        @Test
        @DisplayName("メッセージリスト変換_null_nullを返す")
        void メッセージリスト変換_null_nullを返す() {
            assertThat(mapper.toMessageResponseList(null)).isNull();
        }

        @Test
        @DisplayName("メッセージリスト変換_正常_全要素変換")
        void メッセージリスト変換_正常_全要素変換() {
            ChatMessageEntity e = ChatMessageEntity.builder()
                    .channelId(1L).senderId(1L).body("本文").build();
            assertThat(mapper.toMessageResponseList(List.of(e))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("toMemberResponse")
    class ToMemberResponse {

        @Test
        @DisplayName("メンバーエンティティ変換_正常_フィールドが正しくマップされる")
        void メンバーエンティティ変換_正常_フィールドが正しくマップされる() {
            ChatChannelMemberEntity entity = ChatChannelMemberEntity.builder()
                    .channelId(10L).userId(1L).role(ChannelMemberRole.MEMBER).build();
            assertThat(mapper.toMemberResponse(entity).getRole()).isEqualTo("MEMBER");
        }

        @Test
        @DisplayName("メンバーエンティティ変換_null_nullを返す")
        void メンバーエンティティ変換_null_nullを返す() {
            assertThat(mapper.toMemberResponse(null)).isNull();
        }

        @Test
        @DisplayName("メンバーエンティティ変換_OWNER_正常変換")
        void メンバーエンティティ変換_OWNER_正常変換() {
            ChatChannelMemberEntity entity = ChatChannelMemberEntity.builder()
                    .channelId(1L).userId(1L).role(ChannelMemberRole.OWNER).build();
            assertThat(mapper.toMemberResponse(entity).getRole()).isEqualTo("OWNER");
        }
    }

    @Nested
    @DisplayName("toMemberResponseList")
    class ToMemberResponseList {

        @Test
        @DisplayName("メンバーリスト変換_null_nullを返す")
        void メンバーリスト変換_null_nullを返す() {
            assertThat(mapper.toMemberResponseList(null)).isNull();
        }

        @Test
        @DisplayName("メンバーリスト変換_正常_全要素変換")
        void メンバーリスト変換_正常_全要素変換() {
            ChatChannelMemberEntity e = ChatChannelMemberEntity.builder()
                    .channelId(1L).userId(2L).role(ChannelMemberRole.ADMIN).build();
            assertThat(mapper.toMemberResponseList(List.of(e))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("toReactionResponse")
    class ToReactionResponse {

        @Test
        @DisplayName("リアクションエンティティ変換_正常_フィールドが正しくマップされる")
        void リアクションエンティティ変換_正常_フィールドが正しくマップされる() {
            ChatMessageReactionEntity entity = ChatMessageReactionEntity.builder()
                    .messageId(50L).userId(1L).emoji("👍").build();
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
            ChatMessageReactionEntity e = ChatMessageReactionEntity.builder()
                    .messageId(1L).userId(2L).emoji("😊").build();
            assertThat(mapper.toReactionResponseList(List.of(e))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("toAttachmentResponse")
    class ToAttachmentResponse {

        @Test
        @DisplayName("添付ファイルエンティティ変換_正常_フィールドが正しくマップされる")
        void 添付ファイルエンティティ変換_正常_フィールドが正しくマップされる() {
            ChatMessageAttachmentEntity entity = ChatMessageAttachmentEntity.builder()
                    .messageId(10L).fileKey("files/doc.pdf").fileName("doc.pdf")
                    .fileSize(1024L).contentType("application/pdf").build();
            AttachmentResponse response = mapper.toAttachmentResponse(entity);
            assertThat(response.getContentType()).isEqualTo("application/pdf");
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
            ChatMessageAttachmentEntity e = ChatMessageAttachmentEntity.builder()
                    .messageId(1L).fileKey("img.jpg").fileName("img.jpg")
                    .fileSize(2048L).contentType("image/jpeg").build();
            assertThat(mapper.toAttachmentResponseList(List.of(e))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("toBookmarkResponse")
    class ToBookmarkResponse {

        @Test
        @DisplayName("ブックマークエンティティ変換_正常_フィールドが正しくマップされる")
        void ブックマークエンティティ変換_正常_フィールドが正しくマップされる() {
            ChatMessageBookmarkEntity entity = ChatMessageBookmarkEntity.builder()
                    .messageId(100L).userId(1L).note("重要").build();
            assertThat(mapper.toBookmarkResponse(entity).getNote()).isEqualTo("重要");
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
            ChatMessageBookmarkEntity e = ChatMessageBookmarkEntity.builder()
                    .messageId(1L).userId(1L).note("メモ").build();
            assertThat(mapper.toBookmarkResponseList(List.of(e))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("toMessageResponseWithDetails")
    class ToMessageResponseWithDetails {

        @Test
        @DisplayName("詳細付きメッセージ変換_添付ファイルとリアクションあり_正しく組み立て")
        void 詳細付きメッセージ変換_添付ファイルとリアクションあり_正しく組み立て() {
            ChatMessageEntity entity = ChatMessageEntity.builder()
                    .channelId(10L).senderId(1L).body("詳細").build();
            AttachmentResponse att = new AttachmentResponse(1L, 1L, "f.jpg", "f.jpg", 1024L, "image/jpeg", null);
            ReactionResponse rxn = new ReactionResponse(1L, null, 1L, "👍", null);
            MessageResponse response = mapper.toMessageResponseWithDetails(entity, List.of(att), List.of(rxn));
            assertThat(response.getAttachments()).hasSize(1);
            assertThat(response.getReactions()).hasSize(1);
        }
    }
}
