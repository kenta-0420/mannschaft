package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.event.NewDeviceLoginEvent;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.common.DomainEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NewDeviceDetectionServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private DomainEventPublisher eventPublisher;
    @InjectMocks
    private NewDeviceDetectionService service;

    @Test
    void 初回ログインではイベントを発行しない() {
        when(refreshTokenRepository.countByUserId(10L)).thenReturn(0L);

        service.publishIfNewDeviceBeforeTokenSave(10L, "192.0.2.1", "fp", "Chrome", "ja");

        verify(refreshTokenRepository, never())
                .existsByUserIdAndIpAddressAndDeviceFingerprintAndCreatedAtAfter(any(), any(), any(), any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void 既知端末ではイベントを発行しない() {
        when(refreshTokenRepository.countByUserId(10L)).thenReturn(1L);
        when(refreshTokenRepository.existsByUserIdAndIpAddressAndDeviceFingerprintAndCreatedAtAfter(
                eq(10L), eq("192.0.2.1"), eq("fp"), any())).thenReturn(true);

        service.publishIfNewDeviceBeforeTokenSave(10L, "192.0.2.1", "fp", "Chrome", "ja");

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void 未知端末ではcommit後副作用用イベントを発行する() {
        when(refreshTokenRepository.countByUserId(10L)).thenReturn(1L);
        when(refreshTokenRepository.existsByUserIdAndIpAddressAndDeviceFingerprintAndCreatedAtAfter(
                eq(10L), eq("192.0.2.1"), eq("fp"), any())).thenReturn(false);

        service.publishIfNewDeviceBeforeTokenSave(10L, "192.0.2.1", "fp", "Chrome", "en");

        ArgumentCaptor<NewDeviceLoginEvent> event = ArgumentCaptor.forClass(NewDeviceLoginEvent.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().getUserId()).isEqualTo(10L);
        assertThat(event.getValue().getLocale()).isEqualTo("en");
    }
}
