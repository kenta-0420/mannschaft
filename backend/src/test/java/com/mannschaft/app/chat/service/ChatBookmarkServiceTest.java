package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.AddBookmarkRequest;
import com.mannschaft.app.chat.dto.BookmarkResponse;
import com.mannschaft.app.chat.entity.ChatMessageBookmarkEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatMessageBookmarkRepository;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ChatBookmarkService} の単体テスト。
 * ブックマーク追加・一覧取得・削除を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatBookmarkService 単体テスト")
class ChatBookmarkServiceTest {

    @Mock
    private ChatMessageBookmarkRepository bookmarkRepository;

    @Mock
    private ChatMessageService messageService;

    @Mock
    private ChatMapper chatMapper;

    @InjectMocks
    private ChatBookmarkService chatBookmarkService;

    private static final Long MESSAGE_ID = 10L;
    private static final Long USER_ID = 100L;

    // ========================================
    // addBookmark
    // ========================================
    @Nested
    @DisplayName("addBookmark")
    class AddBookmark {

        @Test
        @DisplayName("正常系: ブックマークを追加できる")
        void ブックマークを追加できる() {
            // given
            AddBookmarkRequest req = new AddBookmarkRequest(MESSAGE_ID, "メモ");
            ChatMessageEntity message = ChatMessageEntity.builder()
                    .channelId(1L).senderId(USER_ID).body("テスト").build();
            ChatMessageBookmarkEntity saved = ChatMessageBookmarkEntity.builder()
                    .messageId(MESSAGE_ID).userId(USER_ID).note("メモ").build();
            BookmarkResponse expected = new BookmarkResponse(1L, MESSAGE_ID, USER_ID, "メモ", LocalDateTime.now());

            given(messageService.findMessageOrThrow(MESSAGE_ID)).willReturn(message);
            given(bookmarkRepository.existsByUserIdAndMessageId(USER_ID, MESSAGE_ID)).willReturn(false);
            given(bookmarkRepository.save(any(ChatMessageBookmarkEntity.class))).willReturn(saved);
            given(chatMapper.toBookmarkResponse(any(ChatMessageBookmarkEntity.class))).willReturn(expected);

            // when
            BookmarkResponse result = chatBookmarkService.addBookmark(req, USER_ID);

            // then
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("異常系: 既にブックマーク済みの場合はエラー")
        void 既にブックマーク済みの場合はエラー() {
            // given
            AddBookmarkRequest req = new AddBookmarkRequest(MESSAGE_ID, null);
            ChatMessageEntity message = ChatMessageEntity.builder()
                    .channelId(1L).senderId(USER_ID).body("テスト").build();

            given(messageService.findMessageOrThrow(MESSAGE_ID)).willReturn(message);
            given(bookmarkRepository.existsByUserIdAndMessageId(USER_ID, MESSAGE_ID)).willReturn(true);

            // when & then
            assertThatThrownBy(() -> chatBookmarkService.addBookmark(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.BOOKMARK_ALREADY_EXISTS));
        }
    }

    // ========================================
    // listBookmarks
    // ========================================
    @Nested
    @DisplayName("listBookmarks")
    class ListBookmarks {

        @Test
        @DisplayName("正常系: ブックマーク一覧を取得できる")
        void ブックマーク一覧を取得できる() {
            // given
            List<ChatMessageBookmarkEntity> bookmarks = List.of(
                    ChatMessageBookmarkEntity.builder().messageId(MESSAGE_ID).userId(USER_ID).build());
            List<BookmarkResponse> expected = List.of(
                    new BookmarkResponse(1L, MESSAGE_ID, USER_ID, null, LocalDateTime.now()));

            given(bookmarkRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(bookmarks);
            given(chatMapper.toBookmarkResponseList(bookmarks)).willReturn(expected);

            // when
            List<BookmarkResponse> result = chatBookmarkService.listBookmarks(USER_ID);

            // then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // removeBookmark
    // ========================================
    @Nested
    @DisplayName("removeBookmark")
    class RemoveBookmark {

        @Test
        @DisplayName("正常系: ブックマークを削除できる")
        void ブックマークを削除できる() {
            // when
            chatBookmarkService.removeBookmark(MESSAGE_ID, USER_ID);

            // then
            verify(bookmarkRepository).deleteByUserIdAndMessageId(USER_ID, MESSAGE_ID);
        }
    }
}
