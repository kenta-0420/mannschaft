package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.AddReactionRequest;
import com.mannschaft.app.chat.dto.ReactionResponse;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.entity.ChatMessageReactionEntity;
import com.mannschaft.app.chat.repository.ChatMessageReactionRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ChatReactionService} の単体テスト。
 * リアクション追加・削除・一覧取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatReactionService 単体テスト")
class ChatReactionServiceTest {

    @Mock
    private ChatMessageReactionRepository reactionRepository;

    @Mock
    private ChatMessageRepository messageRepository;

    @Mock
    private ChatMessageService messageService;

    @Mock
    private ChatMapper chatMapper;

    @InjectMocks
    private ChatReactionService chatReactionService;

    private static final Long MESSAGE_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final String EMOJI = "heart";

    private ChatMessageEntity createMessage() {
        return ChatMessageEntity.builder()
                .channelId(1L)
                .senderId(USER_ID)
                .body("テスト")
                .build();
    }

    // ========================================
    // addReaction
    // ========================================
    @Nested
    @DisplayName("addReaction")
    class AddReaction {

        @Test
        @DisplayName("正常系: リアクションを追加できる")
        void リアクションを追加できる() {
            // given
            ChatMessageEntity message = createMessage();
            AddReactionRequest req = new AddReactionRequest(EMOJI);
            ChatMessageReactionEntity saved = ChatMessageReactionEntity.builder()
                    .messageId(MESSAGE_ID).userId(USER_ID).emoji(EMOJI).build();
            ReactionResponse expected = new ReactionResponse(1L, MESSAGE_ID, USER_ID, EMOJI, LocalDateTime.now());

            given(messageService.findMessageOrThrow(MESSAGE_ID)).willReturn(message);
            given(reactionRepository.existsByMessageIdAndUserIdAndEmoji(MESSAGE_ID, USER_ID, EMOJI))
                    .willReturn(false);
            given(reactionRepository.save(any(ChatMessageReactionEntity.class))).willReturn(saved);
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(message);
            given(chatMapper.toReactionResponse(any(ChatMessageReactionEntity.class))).willReturn(expected);

            // when
            ReactionResponse result = chatReactionService.addReaction(MESSAGE_ID, req, USER_ID);

            // then
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("異常系: 同じリアクションが既に存在する場合はエラー")
        void 同じリアクションが既に存在する場合はエラー() {
            // given
            ChatMessageEntity message = createMessage();
            AddReactionRequest req = new AddReactionRequest(EMOJI);

            given(messageService.findMessageOrThrow(MESSAGE_ID)).willReturn(message);
            given(reactionRepository.existsByMessageIdAndUserIdAndEmoji(MESSAGE_ID, USER_ID, EMOJI))
                    .willReturn(true);

            // when & then
            assertThatThrownBy(() -> chatReactionService.addReaction(MESSAGE_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.REACTION_ALREADY_EXISTS));
        }
    }

    // ========================================
    // removeReaction
    // ========================================
    @Nested
    @DisplayName("removeReaction")
    class RemoveReaction {

        @Test
        @DisplayName("正常系: リアクションを削除できる")
        void リアクションを削除できる() {
            // given
            ChatMessageEntity message = createMessage();

            given(messageService.findMessageOrThrow(MESSAGE_ID)).willReturn(message);
            given(reactionRepository.existsByMessageIdAndUserIdAndEmoji(MESSAGE_ID, USER_ID, EMOJI))
                    .willReturn(true);
            given(messageRepository.save(any(ChatMessageEntity.class))).willReturn(message);

            // when
            chatReactionService.removeReaction(MESSAGE_ID, EMOJI, USER_ID);

            // then
            verify(reactionRepository).deleteByMessageIdAndUserIdAndEmoji(MESSAGE_ID, USER_ID, EMOJI);
        }

        @Test
        @DisplayName("異常系: リアクションが見つからない場合はエラー")
        void リアクションが見つからない場合はエラー() {
            // given
            ChatMessageEntity message = createMessage();

            given(messageService.findMessageOrThrow(MESSAGE_ID)).willReturn(message);
            given(reactionRepository.existsByMessageIdAndUserIdAndEmoji(MESSAGE_ID, USER_ID, EMOJI))
                    .willReturn(false);

            // when & then
            assertThatThrownBy(() -> chatReactionService.removeReaction(MESSAGE_ID, EMOJI, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ChatErrorCode.REACTION_NOT_FOUND));
        }
    }

    // ========================================
    // listReactions
    // ========================================
    @Nested
    @DisplayName("listReactions")
    class ListReactions {

        @Test
        @DisplayName("正常系: リアクション一覧を取得できる")
        void リアクション一覧を取得できる() {
            // given
            ChatMessageEntity message = createMessage();
            List<ChatMessageReactionEntity> reactions = List.of(
                    ChatMessageReactionEntity.builder().messageId(MESSAGE_ID).userId(USER_ID).emoji(EMOJI).build());
            List<ReactionResponse> expected = List.of(
                    new ReactionResponse(1L, MESSAGE_ID, USER_ID, EMOJI, LocalDateTime.now()));

            given(messageService.findMessageOrThrow(MESSAGE_ID)).willReturn(message);
            given(reactionRepository.findByMessageId(MESSAGE_ID)).willReturn(reactions);
            given(chatMapper.toReactionResponseList(reactions)).willReturn(expected);

            // when
            List<ReactionResponse> result = chatReactionService.listReactions(MESSAGE_ID);

            // then
            assertThat(result).hasSize(1);
        }
    }
}
