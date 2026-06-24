package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.OAuthProperties;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 設定画面（{@code /settings/linked-accounts}）からの Google OAuth 連携フローを担うサービス。
 * <p>
 * <b>試練(red)用スタブ。</b> 受け入れ条件 AC-5〜AC-10 に対応するテストを先行作成するための
 * 骨組みであり、ロジックは未実装（全メソッドが {@link UnsupportedOperationException} を投げる）。
 * 出陣（実装）フェーズでロジックを充填し、テストを green 化する。
 */
@Service
@RequiredArgsConstructor
public class AuthOAuthLinkService {

    private final OAuthAccountRepository oauthAccountRepository;
    private final StringRedisTemplate redisTemplate;
    private final OAuthProperties oAuthProperties;
    private final AuthOAuthService authOAuthService;

    /**
     * 指定プロバイダの OAuth 認可 URL を生成し、state を Redis に保存する。
     *
     * @param userId   連携を要求するユーザーID
     * @param provider プロバイダ識別子（例: {@code "GOOGLE"}）
     * @return 認可 URL
     */
    public String generateAuthUrl(Long userId, String provider) {
        throw new UnsupportedOperationException("未実装");
    }

    /**
     * OAuth プロバイダからのコールバックを処理し、連携を確定する。
     *
     * @param provider プロバイダ識別子（例: {@code "GOOGLE"}）
     * @param state    認可時に発行した state
     * @param code     認可コード
     * @return フロントエンドへのリダイレクト先 URL
     */
    public String processCallback(String provider, String state, String code) {
        throw new UnsupportedOperationException("未実装");
    }
}
