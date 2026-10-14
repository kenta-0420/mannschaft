package com.mannschaft.app.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.MfaEmailOtpPurpose;
import com.mannschaft.app.auth.TwoFactorMethod;
import com.mannschaft.app.auth.dto.BackupCodesResponse;
import com.mannschaft.app.auth.dto.MfaEmailSendResponse;
import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import com.mannschaft.app.auth.event.MfaEnabledEvent;
import com.mannschaft.app.auth.repository.MfaRecoveryTokenRepository;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * メール 2FA の設定・有効化と、無効化・再生成用の本人確認メールOTP（CMP-261012-0910 AC-25・AC-45・AC-51）。
 *
 * <p>{@link Auth2faService} の同名 API から委譲される（並行部隊との衝突を避けるため本体は別クラスに置く）。
 * コードの発行・照合・送信枠は {@link MfaEmailOtpService} が担い、本クラスは 2FA 行の状態判定と書込みだけを持つ。</p>
 *
 * <h2>永続 enum EMAIL の門番（段階リリース）</h2>
 * <p>{@code two_factor_auth.method = 'EMAIL'} を書き込む経路は {@link #enable} だけである。
 * {@code mannschaft.auth.mfa.email-method-enabled} が false の間（既定・本番）は設定開始・有効化を AUTH_082 で拒否し、
 * EMAIL 値を DB に書かせない（読取り側を先に全環境へ行き渡らせてから書込みを解禁する。
 * persisted_enum_deployment の第 2 段階で true にする）。ログイン時のメールOTP（TOTP 利用者の代替手段）と
 * 本人確認用メールOTP は EMAIL 値を書かないため門番の対象外。</p>
 */
@Service
@Transactional(readOnly = true)
public class MfaEmailTwoFactorService {

    private static final int BACKUP_CODE_COUNT = 8;
    private static final int BACKUP_CODE_LENGTH = 8;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TwoFactorAuthRepository twoFactorAuthRepository;
    private final MfaRecoveryTokenRepository mfaRecoveryTokenRepository;
    private final MfaEmailOtpService mfaEmailOtpService;
    private final PasswordEncoder passwordEncoder;
    private final DomainEventPublisher eventPublisher;
    private final boolean emailMethodEnabled;
    /** LocalDateTime 列（used_at）と突き合わせる現在時刻の源（壁時計。datetime_policy_utc_instant_vs_wallclock.md）。 */
    private final Clock wallClock;

    public MfaEmailTwoFactorService(
            TwoFactorAuthRepository twoFactorAuthRepository,
            MfaRecoveryTokenRepository mfaRecoveryTokenRepository,
            MfaEmailOtpService mfaEmailOtpService,
            PasswordEncoder passwordEncoder,
            DomainEventPublisher eventPublisher,
            @Value("${mannschaft.auth.mfa.email-method-enabled:false}") boolean emailMethodEnabled,
            @Qualifier("wallClock") Clock wallClock) {
        this.twoFactorAuthRepository = twoFactorAuthRepository;
        this.mfaRecoveryTokenRepository = mfaRecoveryTokenRepository;
        this.mfaEmailOtpService = mfaEmailOtpService;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
        this.emailMethodEnabled = emailMethodEnabled;
        this.wallClock = wallClock;
    }

    /**
     * メール 2FA の設定を開始する（設定用コードを本人の登録アドレスへ送る。AC-25）。
     *
     * @param userId 認証済みユーザー ID
     * @return マスク済みアドレス・有効期限・再送待ち秒数
     */
    @Transactional
    public MfaEmailSendResponse startSetup(Long userId) {
        assertEmailMethodEnabled();
        if (findEnabled(userId).isPresent()) {
            throw new BusinessException(AuthErrorCode.AUTH_078);
        }
        return mfaEmailOtpService.send(MfaEmailOtpPurpose.SETUP, MfaEmailOtpKeys.subjectOf(userId), userId, null);
    }

    /**
     * 設定用コードを照合し、method=EMAIL で 2FA を有効化する（AC-25 / AC-45）。
     *
     * <p>TOTP 設定途中の未有効行が残っていれば上書きし、TOTP 秘密鍵を残さない（方式を混在させない）。
     * 誤りは AUTH_074、5 回目の誤り以降は 429 AUTH_075（設定用コードは失効。再送・設定開始の再実行では解除されない）。</p>
     *
     * @param userId 認証済みユーザー ID
     * @param code   設定用メールOTP（形式は DTO で検証済み）
     * @return 平文バックアップコード 8 件
     */
    @Transactional
    public BackupCodesResponse enable(Long userId, String code) {
        assertEmailMethodEnabled();
        // 同一ユーザーの並列有効化で行が二重に書かれないよう、最初の読取りで行ロックを取る
        Optional<TwoFactorAuthEntity> existing = twoFactorAuthRepository.findByUserIdForUpdate(userId);
        if (existing.map(tfa -> Boolean.TRUE.equals(tfa.getIsEnabled())).orElse(false)) {
            throw new BusinessException(AuthErrorCode.AUTH_078);
        }

        switch (mfaEmailOtpService.verify(MfaEmailOtpPurpose.SETUP, MfaEmailOtpKeys.subjectOf(userId), code)) {
            case MATCHED -> {
                // 続行
            }
            case EXHAUSTED -> throw new BusinessException(AuthErrorCode.AUTH_075);
            default -> throw new BusinessException(AuthErrorCode.AUTH_074);
        }

        List<String> plainCodes = generateBackupCodes();
        String hashedJson = toJson(plainCodes.stream().map(passwordEncoder::encode).toList());
        TwoFactorAuthEntity base = existing.orElseGet(() -> TwoFactorAuthEntity.builder()
                .userId(userId)
                .backupCodes("[]")
                .isEnabled(false)
                .build());
        TwoFactorAuthEntity enabled = base.toBuilder()
                .method(TwoFactorMethod.EMAIL)
                .totpSecret(null)
                .backupCodes(hashedJson)
                .build();
        enabled.enable();
        twoFactorAuthRepository.save(enabled);

        // AC-48: 再設定前に発行された回復トークンで新しい 2FA を外させない（TOTP 有効化と同じ扱い）
        revokeUnusedRecoveryTokens(userId);

        eventPublisher.publish(new MfaEnabledEvent(userId, TwoFactorMethod.EMAIL));
        return new BackupCodesResponse(plainCodes);
    }

    /**
     * 無効化・バックアップコード再生成用の本人確認メールOTP を送る（AC-51）。
     *
     * <p>送信先は認証ユーザー本人の登録アドレスに固定する。方式は問わない（TOTP 利用者も使える。殿裁定）。
     * 2FA 未有効なら本人確認の対象が無いので 409 AUTH_079。</p>
     *
     * @param userId 認証済みユーザー ID
     * @return マスク済みアドレス・有効期限・再送待ち秒数
     */
    @Transactional
    public MfaEmailSendResponse sendReauth(Long userId) {
        if (findEnabled(userId).isEmpty()) {
            throw new BusinessException(AuthErrorCode.AUTH_079);
        }
        return mfaEmailOtpService.send(MfaEmailOtpPurpose.REAUTH, MfaEmailOtpKeys.subjectOf(userId), userId, null);
    }

    private Optional<TwoFactorAuthEntity> findEnabled(Long userId) {
        return twoFactorAuthRepository.findByUserId(userId)
                .filter(tfa -> Boolean.TRUE.equals(tfa.getIsEnabled()));
    }

    /**
     * 未使用の回復トークンをすべて使用済みにする（AC-48。{@link Auth2faService} の TOTP 有効化・無効化・回復再要求と同じ規則）。
     * 本クラスは {@link Auth2faService} から委譲される側のため、逆向きに呼ぶと循環依存になる。よって同ドメインの
     * Repository を直接使う。
     */
    private void revokeUnusedRecoveryTokens(Long userId) {
        // 一括 UPDATE（current read）で最新の未使用行を失効させる（読取りビュー固定後に commit された行も漏らさない）
        mfaRecoveryTokenRepository.revokeAllUnusedByUserId(userId, LocalDateTime.now(wallClock));
    }

    private void assertEmailMethodEnabled() {
        if (!emailMethodEnabled) {
            throw new BusinessException(AuthErrorCode.AUTH_082);
        }
    }

    /** バックアップコード 8 件（8 桁・重複なし）。{@link Auth2faService} の TOTP 有効化と同じ諸元。 */
    private static List<String> generateBackupCodes() {
        Set<String> codes = new LinkedHashSet<>();
        while (codes.size() < BACKUP_CODE_COUNT) {
            StringBuilder code = new StringBuilder(BACKUP_CODE_LENGTH);
            for (int j = 0; j < BACKUP_CODE_LENGTH; j++) {
                code.append(SECURE_RANDOM.nextInt(10));
            }
            codes.add(code.toString());
        }
        return List.copyOf(codes);
    }

    private static String toJson(List<String> list) {
        try {
            return JSON.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("バックアップコードの JSON 変換に失敗しました", e);
        }
    }
}
