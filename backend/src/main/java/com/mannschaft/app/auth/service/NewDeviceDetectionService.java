package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.event.NewDeviceLoginEvent;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.common.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 新規デバイスログインを検知するサービス。
 */
@Service
@RequiredArgsConstructor
public class NewDeviceDetectionService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final DomainEventPublisher eventPublisher;

    /**
     * 今回の refresh token を保存する前に新規端末かを判定し、必要な場合だけイベントを発行する。
     *
     * <p>保存後に照会すると今回の token 自身を既知端末として読んでしまうため、ここではイベントを
     * 発行するだけに留める。監査・通知は {@code AFTER_COMMIT} リスナーで実行される。</p>
     */
    public void publishIfNewDeviceBeforeTokenSave(Long userId, String ipAddress, String deviceFingerprint,
                                                   String deviceName, String locale) {
        if (refreshTokenRepository.countByUserId(userId) == 0) {
            return;
        }

        boolean knownDevice = refreshTokenRepository
                .existsByUserIdAndIpAddressAndDeviceFingerprintAndCreatedAtAfter(
                        userId, ipAddress, deviceFingerprint, LocalDateTime.now().minusDays(30));
        if (!knownDevice) {
            eventPublisher.publish(new NewDeviceLoginEvent(
                    userId, ipAddress, deviceFingerprint, deviceName, locale));
        }
    }
}
