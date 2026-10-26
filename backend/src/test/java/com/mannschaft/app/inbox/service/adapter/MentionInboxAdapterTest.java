package com.mannschaft.app.inbox.service.adapter;

import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.InboxState;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.service.InboxPriorityNormalizer;
import com.mannschaft.app.mention.entity.MentionEntity;
import com.mannschaft.app.mention.repository.MentionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

/**
 * F04.11 {@link MentionInboxAdapter} 単体テスト（Mockito）。
 *
 * <p>設計書 03_business_logic.md §2・01_data_model.md §3.2・04_security_operations.md §1.2 から、
 * fetch のマッピング（title/excerpt=content_snippet・occurredAt=created_at・MENTION は一律 HIGH・
 * 既読/未読のソース状態）と {@code isVisibleTo} の本人判定（他人宛ては false＝IDOR）を受け入れ条件化する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MentionInboxAdapter 単体テスト")
class MentionInboxAdapterTest {

    private static final Long USER_ID = 1L;

    private final MentionRepository mentionRepository = mock(MentionRepository.class);

    // 実装と同じ正規化ロジックを使う（純粋関数のため実体を渡す）。
    private final InboxPriorityNormalizer normalizer = new InboxPriorityNormalizer();

    private final MentionInboxAdapter adapter =
            new MentionInboxAdapter(mentionRepository, normalizer,
                    new com.mannschaft.app.inbox.service.InboxDedupeKeyResolver());

    /** メンションエンティティを組み立てるヘルパー。 */
    private MentionEntity mention(Long id, Long mentionedUserId, String targetType, Long targetId,
                                  String snippet, boolean isRead, LocalDateTime createdAt) {
        return MentionEntity.builder()
                .id(id)
                .mentionedUserId(mentionedUserId)
                .mentionedById(99L)
                .targetType(targetType)
                .targetId(targetId)
                .contentSnippet(snippet)
                .isRead(isRead)
                .createdAt(createdAt)
                .build();
    }

    @Test
    @DisplayName("sourceType は MENTION を返す")
    void sourceTypeIsMention() {
        assertThat(adapter.sourceType()).isEqualTo(InboxSourceType.MENTION);
    }

    @Nested
    @DisplayName("fetch のマッピング")
    class Fetch {

        @Test
        @DisplayName("content_snippet を title/excerpt に、created_at を occurredAt に写像する")
        void mapsFields() {
            LocalDateTime now = LocalDateTime.now();
            given(mentionRepository.findByMentionedUserIdOrderByCreatedAtDesc(eq(USER_ID), any(Pageable.class)))
                    .willReturn(List.of(mention(
                            10L, USER_ID, "TIMELINE_POST", 5L, "あなたへのメンション", false, now)));

            List<InboxItemDto> items = adapter.fetch(USER_ID, 50);

            assertThat(items).hasSize(1);
            InboxItemDto dto = items.get(0);
            assertThat(dto.id()).isEqualTo("MENTION:10");
            assertThat(dto.sourceType()).isEqualTo(InboxSourceType.MENTION);
            assertThat(dto.sourceId()).isEqualTo(10L);
            assertThat(dto.title()).isEqualTo("あなたへのメンション");
            assertThat(dto.excerpt()).isEqualTo("あなたへのメンション");
            assertThat(dto.occurredAt()).isEqualTo(now);
            assertThat(dto.actionUrl()).isEqualTo("/timeline/5");
        }

        @Test
        @DisplayName("MENTION の priority は一律 HIGH")
        void priorityAlwaysHigh() {
            given(mentionRepository.findByMentionedUserIdOrderByCreatedAtDesc(eq(USER_ID), any(Pageable.class)))
                    .willReturn(List.of(mention(
                            11L, USER_ID, "CHAT_MESSAGE", 7L, "snippet", false, LocalDateTime.now())));

            List<InboxItemDto> items = adapter.fetch(USER_ID, 50);

            assertThat(items).singleElement()
                    .extracting(InboxItemDto::priority)
                    .isEqualTo(InboxPriority.HIGH);
        }

        @Test
        @DisplayName("is_read=true は READ、false は UNREAD として state 源に載せる")
        void mapsSourceReadState() {
            given(mentionRepository.findByMentionedUserIdOrderByCreatedAtDesc(eq(USER_ID), any(Pageable.class)))
                    .willReturn(List.of(
                            mention(12L, USER_ID, "TIMELINE_POST", 1L, "a", true, LocalDateTime.now()),
                            mention(13L, USER_ID, "TIMELINE_POST", 2L, "b", false, LocalDateTime.now())));

            List<InboxItemDto> items = adapter.fetch(USER_ID, 50);

            assertThat(items).extracting(InboxItemDto::sourceId, InboxItemDto::state)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(12L, InboxState.READ),
                            org.assertj.core.groups.Tuple.tuple(13L, InboxState.UNREAD));
        }

        @Test
        @DisplayName("fetch は window 件を超えて取得しない（PageRequest size <= window）")
        void boundsByWindow() {
            given(mentionRepository.findByMentionedUserIdOrderByCreatedAtDesc(eq(USER_ID), any(Pageable.class)))
                    .willReturn(List.of());

            adapter.fetch(USER_ID, 30);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(mentionRepository).findByMentionedUserIdOrderByCreatedAtDesc(eq(USER_ID), captor.capture());
            assertThat(captor.getValue().getPageSize()).isLessThanOrEqualTo(30);
        }

        @Test
        @DisplayName("window <= 0 は DB を引かず空を返す")
        void zeroWindowReturnsEmpty() {
            assertThat(adapter.fetch(USER_ID, 0)).isEmpty();
            org.mockito.Mockito.verifyNoInteractions(mentionRepository);
        }
    }

    @Nested
    @DisplayName("isVisibleTo（IDOR）")
    class Visibility {

        @Test
        @DisplayName("本人宛てメンションは true")
        void ownMentionVisible() {
            given(mentionRepository.findById(20L))
                    .willReturn(Optional.of(mention(
                            20L, USER_ID, "TIMELINE_POST", 1L, "s", false, LocalDateTime.now())));

            assertThat(adapter.isVisibleTo(USER_ID, 20L)).isTrue();
        }

        @Test
        @DisplayName("他人宛てメンションは false（mentioned_user_id 不一致）")
        void othersMentionInvisible() {
            given(mentionRepository.findById(21L))
                    .willReturn(Optional.of(mention(
                            21L, 999L, "TIMELINE_POST", 1L, "s", false, LocalDateTime.now())));

            assertThat(adapter.isVisibleTo(USER_ID, 21L)).isFalse();
        }

        @Test
        @DisplayName("存在しないメンションは false")
        void missingMentionInvisible() {
            given(mentionRepository.findById(22L)).willReturn(Optional.empty());

            assertThat(adapter.isVisibleTo(USER_ID, 22L)).isFalse();
        }
    }
}
