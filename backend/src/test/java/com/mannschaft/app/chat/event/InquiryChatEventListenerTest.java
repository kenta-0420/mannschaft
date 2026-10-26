package com.mannschaft.app.chat.event;

import com.mannschaft.app.admin.service.AdminBusinessAlertService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F10.7 {@link InquiryChatEventListener} 単体テスト（実機 E2E 障害の回帰防止）。
 *
 * <p>実機で「問い合わせ通知が一件も作成されない」障害を捕捉した。真因は 2 点:</p>
 * <ol>
 *   <li><b>sourceType/sourceId 不整合</b>: {@code sourceType="CHAT_MESSAGE"} なのに sourceId に
 *       <b>チャンネル ID</b> を渡していた。CHAT_MESSAGE の Resolver はメッセージ ID を期待するため、
 *       正しくは <b>メッセージ ID</b> を渡さねばならない。</li>
 *   <li><b>リアルタイム配信の未結線</b>: {@code createNotification} で DB 作成した通知を
 *       {@code NotificationDispatchService.dispatch} に渡しておらず、WS/Push 配信が起きなかった。</li>
 * </ol>
 *
 * <p>本テストは両欠陥を実証する（修正前 red / 修正後 green）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InquiryChatEventListener — 問い合わせ通知の生成と配信")
class InquiryChatEventListenerTest {

    private static final long TEAM_ID = 100L;
    private static final long CHANNEL_ID = 7001L;
    private static final long MESSAGE_ID = 5001L;
    private static final long SENDER_ID = 42L;
    private static final long ADMIN_ID = 10L;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private AdminBusinessAlertService adminBusinessAlertService;

    @Mock
    private NotificationDispatchService dispatchService;

    @Mock
    private MessageSource messageSource;

    @Mock
    private UserLocaleCache userLocaleCache;

    private InquiryChatEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new InquiryChatEventListener(
                userRoleRepository,
                notificationService,
                redisTemplate,
                adminBusinessAlertService,
                dispatchService,
                messageSource,
                userLocaleCache);
        lenient().when(userLocaleCache.getLocales(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(Map.of());
        lenient().when(messageSource.getMessage(
                        anyString(), any(), anyString(), org.mockito.ArgumentMatchers.any(Locale.class)))
                .thenReturn("stub-message");
    }

    private void stubDedupPass() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
    }

    private InquiryReceivedEvent event() {
        return new InquiryReceivedEvent(
                TEAM_ID, CHANNEL_ID, "問い合わせ", SENDER_ID, "山田太郎", MESSAGE_ID);
    }

    @Test
    @DisplayName("sourceType=CHAT_MESSAGE に対し sourceId は【メッセージ ID】を渡す（チャンネル ID ではない）")
    void createNotification_uses_messageId_as_sourceId() {
        stubDedupPass();
        when(userRoleRepository.findAdminUserIdsByTeamId(TEAM_ID)).thenReturn(List.of(ADMIN_ID));
        when(userRoleRepository.findAllDeputyAdminUserIdsByTeamId(TEAM_ID)).thenReturn(List.of());
        lenient().when(notificationService.createNotification(
                anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyString(), any()))
                .thenReturn(mock(NotificationEntity.class));

        listener.onInquiryReceived(event());

        ArgumentCaptor<String> sourceType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> sourceId = ArgumentCaptor.forClass(Long.class);
        verify(notificationService).createNotification(
                eq(ADMIN_ID), eq("INQUIRY_RECEIVED"), eq(NotificationPriority.HIGH),
                anyString(), anyString(),
                sourceType.capture(), sourceId.capture(),
                eq(NotificationScopeType.TEAM), eq(TEAM_ID), anyString(), eq(SENDER_ID));

        assertThat(sourceType.getValue()).isEqualTo("CHAT_MESSAGE");
        assertThat(sourceId.getValue())
                .as("sourceId は sourceType=CHAT_MESSAGE の実体（メッセージ ID）でなければならない")
                .isEqualTo(MESSAGE_ID)
                .isNotEqualTo(CHANNEL_ID);
    }

    @Test
    @DisplayName("作成した通知は NotificationDispatchService.dispatch でリアルタイム配信される")
    void created_notification_is_dispatched() {
        stubDedupPass();
        when(userRoleRepository.findAdminUserIdsByTeamId(TEAM_ID)).thenReturn(List.of(ADMIN_ID));
        when(userRoleRepository.findAllDeputyAdminUserIdsByTeamId(TEAM_ID)).thenReturn(List.of());
        NotificationEntity created = mock(NotificationEntity.class);
        when(notificationService.createNotification(
                anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyString(), any()))
                .thenReturn(created);

        listener.onInquiryReceived(event());

        verify(dispatchService, times(1)).dispatch(created);
    }

    @Test
    @DisplayName("visibility deny 等で通知が null の場合は dispatch を呼ばない")
    void null_notification_is_not_dispatched() {
        stubDedupPass();
        when(userRoleRepository.findAdminUserIdsByTeamId(TEAM_ID)).thenReturn(List.of(ADMIN_ID));
        when(userRoleRepository.findAllDeputyAdminUserIdsByTeamId(TEAM_ID)).thenReturn(List.of());
        when(notificationService.createNotification(
                anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyString(), any()))
                .thenReturn(null);

        listener.onInquiryReceived(event());

        verify(dispatchService, org.mockito.Mockito.never()).dispatch(any());
    }

    private static <T> T mock(Class<T> c) {
        return org.mockito.Mockito.mock(c);
    }
}
