package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.dto.UserProfileResponse;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link UserService#getUserProfile(Long)} の郵便番号レスポンス検証（単体）。
 *
 * <p>背景: アカウント設定で郵便番号を保存しても再読込で入力欄が空白に戻る不具合。
 * 原因は取得レスポンス（{@link UserProfileResponse}）が郵便番号を返していなかったこと。
 * 本テストは {@code UserEntity.postalCode}（{@code EncryptedStringConverter} で復号済みの平文）が
 * {@code UserProfileResponse.postalCode} に乗ることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserService プロフィール取得 郵便番号 単体テスト")
class UserServiceProfilePostalCodeTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private TwoFactorAuthRepository twoFactorAuthRepository;
    @Mock
    private WebAuthnCredentialRepository webauthnCredentialRepository;
    @Mock
    private OAuthAccountRepository oauthAccountRepository;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private MediaUrlResolver mediaUrlResolver;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("getUserProfile は UserEntity の復号済み郵便番号を postalCode として返す")
    void getUserProfile_returnsPostalCode() {
        Long userId = 100L;
        UserEntity user = org.mockito.Mockito.mock(UserEntity.class);
        given(user.getId()).willReturn(userId);
        given(user.getPostalCode()).willReturn("150-0001");
        given(user.getPhoneNumber()).willReturn("090-1234-5678");

        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(twoFactorAuthRepository.findByUserId(userId)).willReturn(Optional.empty());
        given(webauthnCredentialRepository.findByUserId(userId)).willReturn(Collections.emptyList());
        given(oauthAccountRepository.findByUserId(userId)).willReturn(Collections.emptyList());
        given(userRoleRepository.isSystemAdmin(userId)).willReturn(0L);

        ApiResponse<UserProfileResponse> result = userService.getUserProfile(userId);

        assertThat(result.getData().getPostalCode()).isEqualTo("150-0001");
        // 既存の phoneNumber と並んで返ることを併せて担保（対称性）
        assertThat(result.getData().getPhoneNumber()).isEqualTo("090-1234-5678");
    }
}
