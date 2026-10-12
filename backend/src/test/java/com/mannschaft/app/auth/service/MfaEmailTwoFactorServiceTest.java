package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.MfaEmailOtpPurpose;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CMP-261012-0910: 永続 enum EMAIL の書込み門番（{@code mannschaft.auth.mfa.email-method-enabled=false}）。
 *
 * <p>フラグが false の間は、メール方式 2FA の設定開始・有効化が AUTH_082 で拒否され、
 * コード送信・照合・two_factor_auth への書込みが一切起きないこと（EMAIL を DB に書かせない）を確かめる。
 * フラグ true の振る舞いは IT（{@code MfaEmailSetupIT}。test プロファイルは true）が見る。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CMP-261012-0910 メール方式2FAの書込み門番（フラグfalse）")
class MfaEmailTwoFactorServiceTest {

    private static final Long USER_ID = 42L;

    @Mock
    private TwoFactorAuthRepository twoFactorAuthRepository;
    @Mock
    private MfaEmailOtpService mfaEmailOtpService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private DomainEventPublisher eventPublisher;

    private MfaEmailTwoFactorService service(boolean emailMethodEnabled) {
        return new MfaEmailTwoFactorService(
                twoFactorAuthRepository, mfaEmailOtpService, passwordEncoder, eventPublisher, emailMethodEnabled);
    }

    @Test
    @DisplayName("フラグfalseでは有効化をAUTH_082で拒否し照合・書込み・イベントが起きない")
    void フラグfalseでは有効化を拒否() {
        MfaEmailTwoFactorService sut = service(false);

        assertThatThrownBy(() -> sut.enable(USER_ID, "123456"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_082);
        verifyNoInteractions(twoFactorAuthRepository, mfaEmailOtpService, eventPublisher);
    }

    @Test
    @DisplayName("フラグfalseでは設定開始もAUTH_082で拒否しコードを送らない")
    void フラグfalseでは設定開始を拒否() {
        MfaEmailTwoFactorService sut = service(false);

        assertThatThrownBy(() -> sut.startSetup(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_082);
        verify(mfaEmailOtpService, never()).send(any(MfaEmailOtpPurpose.class), anyString(), any(), any());
    }

    @Test
    @DisplayName("本人確認用OTPの送信はフラグの対象外（2FA未有効ならAUTH_079）")
    void 本人確認用はフラグ対象外() {
        MfaEmailTwoFactorService sut = service(false);

        assertThatThrownBy(() -> sut.sendReauth(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_079);
    }
}
