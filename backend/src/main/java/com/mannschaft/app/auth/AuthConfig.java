package com.mannschaft.app.auth;

import java.util.HashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 認証関連のBean定義。
 */
@Configuration
public class AuthConfig {

    /** 既存ユーザーの BCrypt ハッシュ生成に使用していた強度。検証時はハッシュ内のコストが優先されるため互換性に影響しない。 */
    private static final int BCRYPT_STRENGTH = 12;

    /** 新規エンコードの既定アルゴリズム ID。{@code {argon2}} プレフィックスが付与される。 */
    private static final String ENCODING_ID_ARGON2 = "argon2";
    private static final String ENCODING_ID_BCRYPT = "bcrypt";

    /**
     * パスワードエンコーダー。
     *
     * <p>Argon2id を既定とした {@link DelegatingPasswordEncoder} による段階移行を行う。
     * 既存ユーザーをログイン不能にしないため、以下の方針で構成する。</p>
     *
     * <ul>
     *   <li><b>新規エンコード</b>（登録・パスワードリセット・変更）は既定 ID {@code argon2} で
     *       {@code {argon2}} プレフィックス付きのハッシュを生成する。</li>
     *   <li><b>検証</b>は {@code {bcrypt}} / {@code {argon2}} プレフィックスに応じて委譲する。</li>
     *   <li><b>{@code {id}} プレフィックスのない既存ハッシュ</b>（生 BCrypt）は
     *       {@link DelegatingPasswordEncoder#setDefaultPasswordEncoderForMatches(PasswordEncoder)}
     *       により BCrypt として検証される。これにより既存ハッシュへの DB 変更は一切不要。</li>
     * </ul>
     *
     * <p>Argon2 パラメータは OWASP 準拠の Spring Security 推奨値
     * （{@link Argon2PasswordEncoder#defaultsForSpringSecurity_v5_8()}）を使用する。
     * BouncyCastle 依存（bcprov-jdk18on）が必要だが build.gradle.kts に既に存在する。</p>
     *
     * <p>ログイン成功時の透過的な再ハッシュ（BCrypt → Argon2id）は、
     * {@code PasswordEncoder#upgradeEncoding(CharSequence)} を用いて
     * {@code AuthService} のログイン成功パスで実施する。</p>
     *
     * 設計書: docs/security/02_cookie_and_session.md §5 / docs/features/F01.1_auth.md
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        BCryptPasswordEncoder bcryptEncoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);
        Argon2PasswordEncoder argon2Encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put(ENCODING_ID_ARGON2, argon2Encoder);
        encoders.put(ENCODING_ID_BCRYPT, bcryptEncoder);

        DelegatingPasswordEncoder delegatingEncoder =
                new DelegatingPasswordEncoder(ENCODING_ID_ARGON2, encoders);
        // {id} プレフィックスのない既存ハッシュ（生 BCrypt）は BCrypt として検証する。
        // これにより DB の既存ハッシュを変更せずに段階移行できる。
        delegatingEncoder.setDefaultPasswordEncoderForMatches(bcryptEncoder);

        return delegatingEncoder;
    }

}
