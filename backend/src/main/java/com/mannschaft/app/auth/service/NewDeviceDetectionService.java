package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 新規デバイスログイン検知サービス。
 * ログイン時に未知のデバイスからのアクセスを検出し、通知を行う。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NewDeviceDetectionService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final MessageSource messageSource;

    /**
     * 新規デバイスからのログインかを判定し、必要に応じて通知・監査ログを記録する。
     * ログインレスポンスの遅延を防ぐため @Async で非同期実行する。
     * locale は引数で明示渡し（F11.3 §4.8 準拠: @Async メソッドでは LocaleContextHolder 使用不可）。
     */
    @Async
    public void checkAndNotify(Long userId, String ipAddress, String deviceFingerprint,
                               String deviceName, String locale) {
        try {
            // 1. 初回ログイン判定: トークン総数が1以下（今回分のみ）なら通知不要
            long tokenCount = refreshTokenRepository.countByUserId(userId);
            if (tokenCount <= 1) {
                return;
            }

            // 2. 直近30日以内に同一 IP + デバイスフィンガープリントの組み合わせが存在するか
            LocalDateTime since = LocalDateTime.now().minusDays(30);
            boolean knownDevice = refreshTokenRepository
                    .existsByUserIdAndIpAddressAndDeviceFingerprintAndCreatedAtAfter(
                            userId, ipAddress, deviceFingerprint, since);
            if (knownDevice) {
                return;
            }

            // 3. 新規デバイス検出 — ログ記録
            log.info("新規デバイスログイン検知: userId={}, device={}, ip={}", userId, deviceName, ipAddress);

            // 4. 通知メッセージ生成
            Locale userLocale = Locale.forLanguageTag(locale != null ? locale : "ja");
            String title = messageSource.getMessage(
                    "notification.new_device_login.title", null, userLocale);
            String body = messageSource.getMessage(
                    "notification.new_device_login.body", new Object[]{deviceName}, userLocale);

            // TODO: NotificationDispatchService 連携は通知基盤の実装状況に応じて追加
            // NotificationDispatchService.dispatch(NotificationEntity) を呼び出すには
            // NotificationEntity の生成が必要だが、エンティティクラスが未実装のため
            // 現時点ではログ出力で代替（通知基盤との結合は検分時に判断）
            log.info("新規デバイスログイン通知: userId={}, title={}, body={}", userId, title, body);

        } catch (Exception e) {
            // 非同期処理のため例外はログ記録のみ（ログインフローを阻害しない）
            log.error("新規デバイスログイン検知でエラー: userId={}", userId, e);
        }
    }
}
