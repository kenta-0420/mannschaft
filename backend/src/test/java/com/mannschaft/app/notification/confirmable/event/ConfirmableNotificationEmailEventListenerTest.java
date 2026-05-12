package com.mannschaft.app.notification.confirmable.event;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.EmailService;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.IContext;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * F04.9 確認通知メールリスナーのユニットテスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConfirmableNotificationEmailEventListener")
class ConfirmableNotificationEmailEventListenerTest {

    @Mock
    private EmailService emailService;

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
        ReflectionTestUtils.setField(listener, "frontendUrl", "http://localhost:3000");
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
        @DisplayName("正常系: 2名の受信者それぞれにsendEmailが呼ばれる")
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

            when(recipientRepository.findUserIdAndConfirmTokenByNotificationId(notificationId))
                    .thenReturn(List.of(row1, row2));
            when(userRepository.findByIdIn(List.of(userId1, userId2)))
                    .thenReturn(List.of(user1, user2));
            when(templateEngine.process(anyString(), any(IContext.class)))
                    .thenReturn("<html>テストメール</html>");

            // Act
            listener.handleConfirmableNotificationCreated(event);

            // Assert: 2名分のsendEmailが呼ばれること
            verify(emailService, times(2)).sendEmail(anyString(), anyString(), anyString());
            ArgumentCaptor<String> emailCaptor = ArgumentCaptor.forClass(String.class);
            verify(emailService, times(2)).sendEmail(emailCaptor.capture(), anyString(), anyString());
            assertThat(emailCaptor.getAllValues())
                    .containsExactlyInAnyOrder("user1@example.com", "user2@example.com");
        }

        @Test
        @DisplayName("正常系: 確認URLにfrontendUrlとconfirmTokenが含まれる")
        void confirmUrlContainsTokenAndFrontendUrl() {
            // Arrange
            Long notificationId = 2L;
            Long userId = 30L;
            String token = "aaaabbbb-cccc-dddd-eeee-ffffffffffff";

            ConfirmableNotificationCreatedEvent event = new ConfirmableNotificationCreatedEvent(
                    notificationId, ScopeType.ORGANIZATION, 5L, List.of(userId));

            UserEntity user = buildUser(userId, "member@example.com", "ja");

            Object[] row = new Object[]{userId, token};
            when(recipientRepository.findUserIdAndConfirmTokenByNotificationId(notificationId))
                    .thenReturn(List.of(row));
            when(userRepository.findByIdIn(List.of(userId)))
                    .thenReturn(List.of(user));
            when(templateEngine.process(anyString(), any(IContext.class)))
                    .thenReturn("<html>確認リンク</html>");

            // Act
            listener.handleConfirmableNotificationCreated(event);

            // Assert: Thymeleafのprocess呼び出し時にconfirmUrlが正しく設定されること
            verify(emailService).sendEmail(eq("member@example.com"), anyString(), anyString());
            ArgumentCaptor<IContext> contextCaptor = ArgumentCaptor.forClass(IContext.class);
            verify(templateEngine).process(anyString(), contextCaptor.capture());
            Object confirmUrl = contextCaptor.getValue().getVariable("confirmUrl");
            assertThat(confirmUrl).asString()
                    .isEqualTo("http://localhost:3000/notifications/confirm/" + token);
        }

        @Test
        @DisplayName("異常系: メールアドレスが空のユーザーはスキップされ、sendEmailが呼ばれない")
        void skipUserWithBlankEmail() {
            // Arrange
            Long notificationId = 3L;
            Long userId = 40L;

            ConfirmableNotificationCreatedEvent event = new ConfirmableNotificationCreatedEvent(
                    notificationId, ScopeType.TEAM, 1L, List.of(userId));

            // メールアドレスが空（匿名化済みユーザー等）
            UserEntity user = buildUser(userId, "", "ja");

            Object[] row = new Object[]{userId, "token-skip-empty-email"};
            when(recipientRepository.findUserIdAndConfirmTokenByNotificationId(notificationId))
                    .thenReturn(List.of(row));
            when(userRepository.findByIdIn(List.of(userId)))
                    .thenReturn(List.of(user));

            // Act
            listener.handleConfirmableNotificationCreated(event);

            // Assert: sendEmailが呼ばれないこと
            verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
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

            // Assert: sendEmailが呼ばれないこと
            verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("正常系: localeがjaのユーザーには日本語件名でメールが送られる")
        void jaLocaleUsesJapaneseSubject() {
            // Arrange
            Long notificationId = 5L;
            Long userId = 60L;
            String token = "ja-token-1234";

            ConfirmableNotificationCreatedEvent event = new ConfirmableNotificationCreatedEvent(
                    notificationId, ScopeType.TEAM, 1L, List.of(userId));

            UserEntity user = buildUser(userId, "user@example.com", "ja");

            Object[] row = new Object[]{userId, token};
            when(recipientRepository.findUserIdAndConfirmTokenByNotificationId(notificationId))
                    .thenReturn(List.of(row));
            when(userRepository.findByIdIn(List.of(userId)))
                    .thenReturn(List.of(user));
            when(templateEngine.process(anyString(), any(IContext.class)))
                    .thenReturn("<html>日本語メール</html>");

            // Act
            listener.handleConfirmableNotificationCreated(event);

            // Assert: getMessageがlocaleJAPANESEで日本語の件名を返すこと
            String subject = listener.getMessage("email.confirmableNotification.subject", Locale.JAPANESE);
            assertThat(subject).isEqualTo("確認が必要なお知らせ");
            verify(emailService).sendEmail(eq("user@example.com"), anyString(), anyString());
        }
    }
}
