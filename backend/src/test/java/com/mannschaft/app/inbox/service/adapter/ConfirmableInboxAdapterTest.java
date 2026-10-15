package com.mannschaft.app.inbox.service.adapter;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.InboxState;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.service.InboxPriorityNormalizer;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * F04.11 {@link ConfirmableInboxAdapter} 単体テスト（Mockito）。
 *
 * <p>設計書 03_business_logic.md §2/§7・01_data_model.md §3.2 から、親 confirmable_notification の
 * title/body/priority/deadline/actionUrl/created_at の写像と、{@code normalizeConfirmable} による
 * 「未確認かつ締切 24h 以内は URGENT 昇格」、および {@code isVisibleTo} の本人＋未除外判定（IDOR）を
 * 受け入れ条件化する。sourceId は recipient.id（01 §3.2）。</p>
 *
 * <p><b>注</b>: エンティティ mock の構築（{@code given} を含む）を別の {@code given} の引数内でネストすると
 * Mockito の UnfinishedStubbingException になるため、ヘルパーで先にローカル変数へ組み立ててから
 * リポジトリ stub に渡す。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConfirmableInboxAdapter 単体テスト")
class ConfirmableInboxAdapterTest {

    private static final Long USER_ID = 1L;

    private final ConfirmableNotificationRecipientRepository recipientRepository =
            mock(ConfirmableNotificationRecipientRepository.class);

    // 実装と同じ正規化ロジックを使う（純粋関数のため実体を渡す）。
    private final InboxPriorityNormalizer normalizer = new InboxPriorityNormalizer();

    private final ConfirmableInboxAdapter adapter =
            new ConfirmableInboxAdapter(recipientRepository, normalizer);

    /** 親通知 mock を組み立てるヘルパー。 */
    private ConfirmableNotificationEntity parent(Long id, String title, String body,
                                                 ConfirmableNotificationPriority priority,
                                                 LocalDateTime deadline, String actionUrl,
                                                 LocalDateTime createdAt) {
        ConfirmableNotificationEntity parent = mock(ConfirmableNotificationEntity.class);
        given(parent.getId()).willReturn(id);
        given(parent.getTitle()).willReturn(title);
        given(parent.getBody()).willReturn(body);
        given(parent.getPriority()).willReturn(priority);
        given(parent.getDeadlineAt()).willReturn(deadline);
        given(parent.getActionUrl()).willReturn(actionUrl);
        given(parent.getCreatedAt()).willReturn(createdAt);
        return parent;
    }

    /** 受信者 mock を組み立てるヘルパー。 */
    private ConfirmableNotificationRecipientEntity recipient(Long id, Long userId,
                                                             ConfirmableNotificationEntity parent,
                                                             boolean confirmed, LocalDateTime excludedAt) {
        ConfirmableNotificationRecipientEntity r = mock(ConfirmableNotificationRecipientEntity.class);
        UserEntity user = mock(UserEntity.class);
        given(user.getId()).willReturn(userId);
        given(r.getId()).willReturn(id);
        given(r.getUser()).willReturn(user);
        given(r.getConfirmableNotification()).willReturn(parent);
        given(r.getIsConfirmed()).willReturn(confirmed);
        given(r.getExcludedAt()).willReturn(excludedAt);
        return r;
    }

    @Test
    @DisplayName("sourceType は CONFIRMABLE を返す")
    void sourceTypeIsConfirmable() {
        assertThat(adapter.sourceType()).isEqualTo(InboxSourceType.CONFIRMABLE);
    }

    @Nested
    @DisplayName("fetch のマッピング")
    class Fetch {

        @Test
        @DisplayName("親の title/body/actionUrl/created_at を写像し sourceId は recipient.id")
        void mapsFields() {
            LocalDateTime created = LocalDateTime.now().minusDays(5);
            ConfirmableNotificationEntity p = parent(
                    100L, "確認してください", "本文", ConfirmableNotificationPriority.NORMAL,
                    null, "/confirmations/100", created);
            ConfirmableNotificationRecipientEntity r = recipient(500L, USER_ID, p, false, null);
            given(recipientRepository.findByUserIdAndIsConfirmedFalseAndExcludedAtIsNullWithNotification(eq(USER_ID), any(Pageable.class)))
                    .willReturn(List.of(r));

            List<InboxItemDto> items = adapter.fetch(USER_ID, 50);

            assertThat(items).hasSize(1);
            InboxItemDto dto = items.get(0);
            assertThat(dto.id()).isEqualTo("CONFIRMABLE:500");
            assertThat(dto.sourceId()).isEqualTo(500L);
            assertThat(dto.title()).isEqualTo("確認してください");
            assertThat(dto.excerpt()).isEqualTo("本文");
            assertThat(dto.actionUrl()).isEqualTo("/confirmations/100");
            assertThat(dto.occurredAt()).isEqualTo(created);
            // 未確認の保留中通知は UNREAD（sourceRead=false）
            assertThat(dto.state()).isEqualTo(InboxState.UNREAD);
        }

        @Test
        @DisplayName("actionUrl が null のときは /confirmations/{親id} を導出する")
        void derivesActionUrlWhenNull() {
            ConfirmableNotificationEntity p = parent(
                    101L, "t", "b", ConfirmableNotificationPriority.NORMAL,
                    null, null, LocalDateTime.now().minusDays(5));
            ConfirmableNotificationRecipientEntity r = recipient(501L, USER_ID, p, false, null);
            given(recipientRepository.findByUserIdAndIsConfirmedFalseAndExcludedAtIsNullWithNotification(eq(USER_ID), any(Pageable.class)))
                    .willReturn(List.of(r));

            List<InboxItemDto> items = adapter.fetch(USER_ID, 50);

            assertThat(items).singleElement()
                    .extracting(InboxItemDto::actionUrl)
                    .isEqualTo("/confirmations/101");
        }

        @Test
        @DisplayName("締切が遠い未確認は親 priority を写像（HIGH→HIGH）")
        void mapsParentPriorityWhenDeadlineFar() {
            ConfirmableNotificationEntity p = parent(
                    102L, "t", "b", ConfirmableNotificationPriority.HIGH,
                    LocalDateTime.now().plusDays(10), "/x", LocalDateTime.now().minusDays(1));
            ConfirmableNotificationRecipientEntity r = recipient(502L, USER_ID, p, false, null);
            given(recipientRepository.findByUserIdAndIsConfirmedFalseAndExcludedAtIsNullWithNotification(eq(USER_ID), any(Pageable.class)))
                    .willReturn(List.of(r));

            List<InboxItemDto> items = adapter.fetch(USER_ID, 50);

            assertThat(items).singleElement()
                    .extracting(InboxItemDto::priority)
                    .isEqualTo(InboxPriority.HIGH);
        }

        @Test
        @DisplayName("未確認かつ締切 24h 以内は URGENT に昇格する")
        void escalatesToUrgentWithin24h() {
            ConfirmableNotificationEntity p = parent(
                    103L, "t", "b", ConfirmableNotificationPriority.NORMAL,
                    LocalDateTime.now().plusHours(6), "/x", LocalDateTime.now().minusDays(1));
            ConfirmableNotificationRecipientEntity r = recipient(503L, USER_ID, p, false, null);
            given(recipientRepository.findByUserIdAndIsConfirmedFalseAndExcludedAtIsNullWithNotification(eq(USER_ID), any(Pageable.class)))
                    .willReturn(List.of(r));

            List<InboxItemDto> items = adapter.fetch(USER_ID, 50);

            assertThat(items).singleElement()
                    .extracting(InboxItemDto::priority)
                    .isEqualTo(InboxPriority.URGENT);
        }
    }

    @Nested
    @DisplayName("isVisibleTo（IDOR）")
    class Visibility {

        @Test
        @DisplayName("本人かつ未除外の受信者は true")
        void ownAndNotExcludedVisible() {
            ConfirmableNotificationEntity p = parent(
                    104L, "t", "b", ConfirmableNotificationPriority.NORMAL, null, "/x",
                    LocalDateTime.now());
            ConfirmableNotificationRecipientEntity r = recipient(600L, USER_ID, p, false, null);
            given(recipientRepository.findById(600L)).willReturn(Optional.of(r));

            assertThat(adapter.isVisibleTo(USER_ID, 600L)).isTrue();
        }

        @Test
        @DisplayName("他人の受信者は false")
        void othersRecipientInvisible() {
            ConfirmableNotificationEntity p = parent(
                    105L, "t", "b", ConfirmableNotificationPriority.NORMAL, null, "/x",
                    LocalDateTime.now());
            ConfirmableNotificationRecipientEntity r = recipient(601L, 999L, p, false, null);
            given(recipientRepository.findById(601L)).willReturn(Optional.of(r));

            assertThat(adapter.isVisibleTo(USER_ID, 601L)).isFalse();
        }

        @Test
        @DisplayName("除外済み（excluded_at != null）の受信者は false")
        void excludedRecipientInvisible() {
            ConfirmableNotificationEntity p = parent(
                    106L, "t", "b", ConfirmableNotificationPriority.NORMAL, null, "/x",
                    LocalDateTime.now());
            ConfirmableNotificationRecipientEntity r = recipient(602L, USER_ID, p, false, LocalDateTime.now());
            given(recipientRepository.findById(602L)).willReturn(Optional.of(r));

            assertThat(adapter.isVisibleTo(USER_ID, 602L)).isFalse();
        }

        @Test
        @DisplayName("存在しない受信者は false")
        void missingRecipientInvisible() {
            given(recipientRepository.findById(603L)).willReturn(Optional.empty());

            assertThat(adapter.isVisibleTo(USER_ID, 603L)).isFalse();
        }
    }
}
