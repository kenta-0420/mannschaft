package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.entity.UserEntity.UserStatus;
import com.mannschaft.app.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * JWT アクセストークンに載せる {@code ppc}（pending parental consent）クレームを、
 * ユーザーの実ステータスから解決するリゾルバ。{@link RoleClaimResolver} と対になる。
 *
 * <p>F01.9 年齢確認・保護者同意機能のサーバ側強制（{@code AUTH_070}）の中核。
 * トークン発行・更新の全 5 経路（ログイン / 2FA / OAuth / WebAuthn / リフレッシュ）は、
 * 本リゾルバ経由で {@code ppc} を判定し、{@link AuthTokenService#issueAccessToken(Long, java.util.List, boolean)}
 * に渡す。{@link com.mannschaft.app.config.JwtAuthenticationFilter} が details Map に格納した
 * {@code ppc} を、{@link com.mannschaft.app.config.ParentalConsentGateFilter} が読み取り、
 * 許可リスト外の保護 API を 403 {@code AUTH_070} で遮断する。</p>
 *
 * <p>失効: {@code ppc} 判定はトークン発行・リフレッシュ時のみ行う軽量な射影クエリ。
 * リフレッシュ時も再判定するため、保護者同意完了（ACTIVE 遷移）は最長 15 分
 * （アクセストークン寿命）で {@code ppc==false} のトークンに置き換わる。</p>
 */
@Component
@RequiredArgsConstructor
public class StatusClaimResolver {

    private final UserRepository userRepository;

    /**
     * 指定ユーザーが保護者同意待ち（{@code PENDING_PARENTAL_CONSENT}）かどうかを解決する。
     *
     * @param userId 対象ユーザー ID
     * @return 保護者同意待ちなら {@code true}。それ以外・ユーザー不在は {@code false}
     */
    @Transactional(readOnly = true)
    public boolean isPendingParentalConsent(Long userId) {
        return userRepository.findStatusById(userId)
                .map(status -> status == UserStatus.PENDING_PARENTAL_CONSENT)
                .orElse(false);
    }
}
