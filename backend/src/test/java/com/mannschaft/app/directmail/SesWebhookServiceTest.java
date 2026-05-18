package com.mannschaft.app.directmail;

import com.mannschaft.app.advertising.campaign.entity.AdEmailDelivery;
import com.mannschaft.app.advertising.campaign.enums.AdBounceType;
import com.mannschaft.app.advertising.campaign.repository.AdEmailDeliveryRepository;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("SesWebhookService 単体テスト")
class SesWebhookServiceTest {

    @Mock private DirectMailRecipientRepository recipientRepository;
    @Mock private DirectMailLogRepository mailLogRepository;
    @Mock private AdEmailDeliveryRepository adEmailDeliveryRepository;
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
            given(adEmailDeliveryRepository.findByDirectMailRecipientId(any())).willReturn(Optional.empty());

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

    @Nested
    @DisplayName("F09.17 ε-C: ad_email_deliveries バウンス反映")
    class AdEmailDeliveryReflection {

        @Test
        @DisplayName("Permanent バウンスは AdEmailDelivery を HARD で更新")
        void permanentバウンス_HARD反映() {
            // Given
            DirectMailRecipientEntity recipient = givenRecipient(123L);
            given(mailLogRepository.findById(1L)).willReturn(Optional.empty());

            AdEmailDelivery delivery = AdEmailDelivery.builder()
                    .campaignId(UUID.randomUUID())
                    .directMailRecipientId(123L)
                    .sentAt(java.time.LocalDateTime.now())
                    .monthKey("2026-05")
                    .build();
            ReflectionTestUtils.setField(delivery, "id", UUID.randomUUID());
            given(adEmailDeliveryRepository.findByDirectMailRecipientId(123L))
                    .willReturn(Optional.of(delivery));
            given(recipientRepository.findBySesMessageId("msg-permanent"))
                    .willReturn(Optional.of(recipient));

            SesNotificationRequest req = new SesNotificationRequest(
                    "Notification", "msg-permanent", "Bounce", "Permanent", null, null, null, null);

            // When
            service.handleNotification(req);

            // Then
            ArgumentCaptor<AdEmailDelivery> captor = ArgumentCaptor.forClass(AdEmailDelivery.class);
            verify(adEmailDeliveryRepository).save(captor.capture());
            assertThat(captor.getValue().getBounceType()).isEqualTo(AdBounceType.HARD);
            assertThat(captor.getValue().getBouncedAt()).isNotNull();
        }

        @Test
        @DisplayName("Transient バウンスは AdEmailDelivery を SOFT で更新")
        void transientバウンス_SOFT反映() {
            DirectMailRecipientEntity recipient = givenRecipient(124L);
            given(mailLogRepository.findById(1L)).willReturn(Optional.empty());

            AdEmailDelivery delivery = AdEmailDelivery.builder()
                    .campaignId(UUID.randomUUID())
                    .directMailRecipientId(124L)
                    .sentAt(java.time.LocalDateTime.now())
                    .monthKey("2026-05")
                    .build();
            ReflectionTestUtils.setField(delivery, "id", UUID.randomUUID());
            given(adEmailDeliveryRepository.findByDirectMailRecipientId(124L))
                    .willReturn(Optional.of(delivery));
            given(recipientRepository.findBySesMessageId("msg-transient"))
                    .willReturn(Optional.of(recipient));

            SesNotificationRequest req = new SesNotificationRequest(
                    "Notification", "msg-transient", "Bounce", "Transient", null, null, null, null);

            service.handleNotification(req);

            ArgumentCaptor<AdEmailDelivery> captor = ArgumentCaptor.forClass(AdEmailDelivery.class);
            verify(adEmailDeliveryRepository).save(captor.capture());
            assertThat(captor.getValue().getBounceType()).isEqualTo(AdBounceType.SOFT);
        }

        @Test
        @DisplayName("Complaint は AdEmailDelivery を COMPLAINT で更新")
        void Complaint_COMPLAINT反映() {
            DirectMailRecipientEntity recipient = givenRecipient(125L);

            AdEmailDelivery delivery = AdEmailDelivery.builder()
                    .campaignId(UUID.randomUUID())
                    .directMailRecipientId(125L)
                    .sentAt(java.time.LocalDateTime.now())
                    .monthKey("2026-05")
                    .build();
            ReflectionTestUtils.setField(delivery, "id", UUID.randomUUID());
            given(adEmailDeliveryRepository.findByDirectMailRecipientId(125L))
                    .willReturn(Optional.of(delivery));
            given(recipientRepository.findBySesMessageId("msg-complaint"))
                    .willReturn(Optional.of(recipient));

            SesNotificationRequest req = new SesNotificationRequest(
                    "Notification", "msg-complaint", "Complaint", null, null, null, null, null);

            service.handleNotification(req);

            ArgumentCaptor<AdEmailDelivery> captor = ArgumentCaptor.forClass(AdEmailDelivery.class);
            verify(adEmailDeliveryRepository).save(captor.capture());
            assertThat(captor.getValue().getBounceType()).isEqualTo(AdBounceType.COMPLAINT);
        }

        @Test
        @DisplayName("F09.17 由来でない (AdEmailDelivery 行なし) 場合は ad_email_deliveries には反映しない")
        void F0917由来でない_反映なし() {
            DirectMailRecipientEntity recipient = givenRecipient(999L);
            given(mailLogRepository.findById(1L)).willReturn(Optional.empty());

            given(adEmailDeliveryRepository.findByDirectMailRecipientId(999L))
                    .willReturn(Optional.empty());
            given(recipientRepository.findBySesMessageId("msg-non-ad"))
                    .willReturn(Optional.of(recipient));

            SesNotificationRequest req = new SesNotificationRequest(
                    "Notification", "msg-non-ad", "Bounce", "Permanent", null, null, null, null);

            service.handleNotification(req);

            verify(adEmailDeliveryRepository, never()).save(any());
        }

        private DirectMailRecipientEntity givenRecipient(Long id) {
            DirectMailRecipientEntity recipient = DirectMailRecipientEntity.builder()
                    .mailLogId(1L).userId(100L).email("ad@example.com").build();
            ReflectionTestUtils.setField(recipient, "id", id);
            return recipient;
        }
    }
}
