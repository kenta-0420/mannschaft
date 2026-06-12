package com.mannschaft.app.notification.confirmable.event;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.IContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * F04.9 確認通知メールリスナーのユニットテスト。
 * F09.18 Phase 18-c: EmailOutboxService.enqueue() 移行 + MessageSource DI 置換。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConfirmableNotificationEmailEventListener")
class ConfirmableNotificationEmailEventListenerTest {

    @Mock
    private EmailOutboxService emailOutboxService;

    @Mock
    private MessageSource messageSource;

    @Mock
    private ConfirmableNotificationRecipientRepository recipientRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TemplateEngine templateEngine;

    @InjectMocks
    private ConfirmableNotificationEmailEventListener listener;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(listener, "baseUrl", "http://localhost:3000");
        // MessageSource: キーをそのまま返すデフォルトスタブ
        // lenient: スキップ系テスト(skipUserWithBlankEmail等)では renderEmailTemplate が呼ばれないため
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    // -------------------------------------------------------------------------
    // テストヘルパー
    // -------------------------------------------------------------------------

    private UserEntity buildUser(Long id, String email, String locale) {
        UserEntity user = UserEntity.builder()
                .email(email)
                .passwordHash(null)
                .lastName("テスト")
                .firstName("ユーザー")
                .displayName("テストユーザー" + id)
                .isSearchable(true)
                .locale(locale)
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    // -------------------------------------------------------------------------
    // テストケース
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("handleConfirmableNotificationCreated")
    class HandleConfirmableNotificationCreated {

        @Test
        @DisplayName("正常系: 2名の受信者それぞれにenqueueが呼ばれる")
        void sendEmailToAllRecipients() {
            // Arrange
            Long notificationId = 1L;
            Long userId1 = 10L;
            Long userId2 = 20L;
            String token1 = "token-uuid-1111-1111-1111";
            String token2 = "token-uuid-2222-2222-2222";

            ConfirmableNotificationCreatedEvent event = new ConfirmableNotificationCreatedEvent(
                    notificationId, ScopeType.TEAM, 1L, List.of(userId1, userId2));

            UserEntity user1 = buildUser(userId1, "user1@example.com", "ja");
            UserEntity user2 = buildUser(userId2, "user2@example.com", "en");

            // findUserIdAndConfirmTokenByNotificationId は Object[] のリストを返す
            Object[] row1 = new Object[]{userId1, token1};
            Object[] row2 = new Object[]{userId2, token2};
            List<Object[]> tokenRows1 = new ArrayList<>();
            tokenRows1.add(row1);
            tokenRows1.add(row2);

            when(recipientRepository.findUserIdAndConfirmTokenByNotificationId(notificationId))
                    .thenReturn(tokenRows1);
            when(userRepository.findByIdIn(List.of(userId1, userId2)))
                    .thenReturn(List.of(user1, user2));
            when(templateEngine.process(anyString(), any(IContext.class)))
                    .thenReturn("<html>テストメール</html>");

            // Act
            listener.handleConfirmableNotificationCreated(event);

            // Assert: 2名分のenqueueが呼ばれ、templateKind=NOTIFICATION_CONFIRMであること
            ArgumentCaptor<EmailOutboxRequest> requestCaptor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
            verify(emailOutboxService, times(2)).enqueue(requestCaptor.capture());
            assertThat(requestCaptor.getAllValues())
                    .extracting(EmailOutboxRequest::toAddress)
                    .containsExactlyInAnyOrder("user1@example.com", "user2@example.com");
            assertThat(requestCaptor.getAllValues())
                    .extracting(EmailOutboxRequest::templateKind)
                    .containsOnly("NOTIFICATION_CONFIRM");
        }

        @Test
        @DisplayName("正常系: 確認URLにbaseUrlとconfirmTokenが含まれる")
        void confirmUrlContainsTokenAndFrontendUrl() {
            // Arrange
            Long notificationId = 2L;
            Long userId = 30L;
            String token = "aaaabbbb-cccc-dddd-eeee-ffffffffffff";

            ConfirmableNotificationCreatedEvent event = new ConfirmableNotificationCreatedEvent(
                    notificationId, ScopeType.ORGANIZATION, 5L, List.of(userId));

            UserEntity user = buildUser(userId, "member@example.com", "ja");

            Object[] row = new Object[]{userId, token};
            List<Object[]> tokenRows2 = new ArrayList<>();
            tokenRows2.add(row);
            when(recipientRepository.findUserIdAndConfirmTokenByNotificationId(notificationId))
                    .thenReturn(tokenRows2);
            when(userRepository.findByIdIn(List.of(userId)))
                    .thenReturn(List.of(user));
            when(templateEngine.process(anyString(), any(IContext.class)))
                    .thenReturn("<html>確認リンク</html>");

            // Act
            listener.handleConfirmableNotificationCreated(event);

            // Assert: enqueueが正しい宛先・templateKindで呼ばれ、confirmUrlがThymeleafコンテキストに設定されること
            ArgumentCaptor<EmailOutboxRequest> requestCaptor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
            verify(emailOutboxService).enqueue(requestCaptor.capture());
            assertThat(requestCaptor.getValue().toAddress()).isEqualTo("member@example.com");
            assertThat(requestCaptor.getValue().templateKind()).isEqualTo("NOTIFICATION_CONFIRM");

            ArgumentCaptor<IContext> contextCaptor = ArgumentCaptor.forClass(IContext.class);
            verify(templateEngine).process(anyString(), contextCaptor.capture());
            Object confirmUrl = contextCaptor.getValue().getVariable("confirmUrl");
            assertThat(confirmUrl).asString()
                    .isEqualTo("http://localhost:3000/notifications/confirm/" + token);
        }

        @Test
        @DisplayName("異常系: メールアドレスが空のユーザーはスキップされ、enqueueが呼ばれない")
        void skipUserWithBlankEmail() {
            // Arrange
            Long notificationId = 3L;
            Long userId = 40L;

            ConfirmableNotificationCreatedEvent event = new ConfirmableNotificationCreatedEvent(
                    notificationId, ScopeType.TEAM, 1L, List.of(userId));

            // メールアドレスが空（匿名化済みユーザー等）
            UserEntity user = buildUser(userId, "", "ja");

            Object[] row = new Object[]{userId, "token-skip-empty-email"};
            List<Object[]> tokenRows3 = new ArrayList<>();
            tokenRows3.add(row);
            when(recipientRepository.findUserIdAndConfirmTokenByNotificationId(notificationId))
                    .thenReturn(tokenRows3);
            when(userRepository.findByIdIn(List.of(userId)))
                    .thenReturn(List.of(user));

            // Act
            listener.handleConfirmableNotificationCreated(event);

            // Assert: enqueueが呼ばれないこと
            verify(emailOutboxService, never()).enqueue(any());
        }

        @Test
        @DisplayName("異常系: confirmTokenが見つからない受信者はスキップされる")
        void skipRecipientWithoutToken() {
            // Arrange
            Long notificationId = 4L;
            Long userId = 50L;

            ConfirmableNotificationCreatedEvent event = new ConfirmableNotificationCreatedEvent(
                    notificationId, ScopeType.TEAM, 1L, List.of(userId));

            UserEntity user = buildUser(userId, "user@example.com", "ja");

            // tokenRowsは空（DBにレコードがない状態）
            when(recipientRepository.findUserIdAndConfirmTokenByNotificationId(notificationId))
                    .thenReturn(List.of());
            when(userRepository.findByIdIn(List.of(userId)))
                    .thenReturn(List.of(user));

            // Act
            listener.handleConfirmableNotificationCreated(event);

            // Assert: enqueueが呼ばれないこと
            verify(emailOutboxService, never()).enqueue(any());
        }

        @Test
        @DisplayName("正常系: localeがjaのユーザーにはMessageSourceからja件名でenqueueが呼ばれる")
        void jaLocaleUsesJapaneseSubject() {
            // Arrange
            Long notificationId = 5L;
            Long userId = 60L;
            String token = "ja-token-1234";
            String jaSubject = "確認が必要なお知らせ";

            ConfirmableNotificationCreatedEvent event = new ConfirmableNotificationCreatedEvent(
                    notificationId, ScopeType.TEAM, 1L, List.of(userId));

            UserEntity user = buildUser(userId, "user@example.com", "ja");

            Object[] row = new Object[]{userId, token};
            List<Object[]> tokenRows5 = new ArrayList<>();
            tokenRows5.add(row);
            when(recipientRepository.findUserIdAndConfirmTokenByNotificationId(notificationId))
                    .thenReturn(tokenRows5);
            when(userRepository.findByIdIn(List.of(userId)))
                    .thenReturn(List.of(user));
            when(templateEngine.process(anyString(), any(IContext.class)))
                    .thenReturn("<html>日本語メール</html>");
            // ja ロケール向けに件名を返すようスタブ上書き
            when(messageSource.getMessage(
                    eq("email.confirmableNotification.subject"), any(), anyString(), eq(Locale.JAPANESE)))
                    .thenReturn(jaSubject);

            // Act
            listener.handleConfirmableNotificationCreated(event);

            // Assert: MessageSource 経由で取得した件名がpayloadVarsに含まれ、enqueueが呼ばれること
            ArgumentCaptor<EmailOutboxRequest> requestCaptor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
            verify(emailOutboxService).enqueue(requestCaptor.capture());
            assertThat(requestCaptor.getValue().toAddress()).isEqualTo("user@example.com");
            assertThat(requestCaptor.getValue().templateKind()).isEqualTo("NOTIFICATION_CONFIRM");
            assertThat(requestCaptor.getValue().payloadVars()).containsEntry("subject", jaSubject);
        }
    }
}
