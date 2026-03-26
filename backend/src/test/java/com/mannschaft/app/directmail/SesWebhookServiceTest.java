package com.mannschaft.app.directmail;

import com.mannschaft.app.directmail.dto.SesNotificationRequest;
import com.mannschaft.app.directmail.entity.DirectMailLogEntity;
import com.mannschaft.app.directmail.entity.DirectMailRecipientEntity;
import com.mannschaft.app.directmail.repository.DirectMailLogRepository;
import com.mannschaft.app.directmail.repository.DirectMailRecipientRepository;
import com.mannschaft.app.directmail.service.SesWebhookService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("SesWebhookService 単体テスト")
class SesWebhookServiceTest {

    @Mock private DirectMailRecipientRepository recipientRepository;
    @Mock private DirectMailLogRepository mailLogRepository;
    @InjectMocks private SesWebhookService service;

    @Nested
    @DisplayName("handleNotification")
    class HandleNotification {

        @Test
        @DisplayName("正常系: Bounceタイプの場合受信者がバウンス済みになる")
        void 処理_バウンス_マーク() {
            // Given
            DirectMailRecipientEntity recipient = DirectMailRecipientEntity.builder()
                    .mailLogId(1L).userId(100L).email("test@example.com").build();
            given(recipientRepository.findBySesMessageId("msg-123")).willReturn(Optional.of(recipient));
            DirectMailLogEntity mailLog = DirectMailLogEntity.builder()
                    .scopeType("TEAM").scopeId(1L).senderId(100L).subject("件名").build();
            given(mailLogRepository.findById(1L)).willReturn(Optional.of(mailLog));

            SesNotificationRequest req = new SesNotificationRequest(
                    "Notification", "msg-123", "Bounce", "Permanent", null, null, null, null);

            // When
            service.handleNotification(req);

            // Then
            verify(recipientRepository).save(any(DirectMailRecipientEntity.class));
        }

        @Test
        @DisplayName("正常系: messageIdがnullの場合処理をスキップ")
        void 処理_messageIdなし_スキップ() {
            // Given
            SesNotificationRequest req = new SesNotificationRequest(
                    "Notification", null, null, null, null, null, null, null);

            // When
            service.handleNotification(req);

            // Then
            verify(recipientRepository, never()).save(any());
        }
    }
}
