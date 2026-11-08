package com.mannschaft.app.common.architecture.plaintexttoken;

import com.mannschaft.app.common.token.SecretTokenVault;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * fixture（番人3）: JTI 用の UUID と、別の乱数トークンが同じメソッドに居る形（Refresh Token の発行と同じ構図）。
 *
 * <p>陰性: {@link #issueWithJtiAndExternalToken(SecretTokenVault, String)}（UUID は JTI にだけ届き、トークンは外から受け取る）、
 * {@link #issueWithJtiAndHelperToken(SecretTokenVault)}（トークンは別クラスの乱数ヘルパが作る）。</p>
 * <p>陽性: {@link #uuidReachesTokenHash(SecretTokenVault)}（UUID が token_hash 列に届く）、
 * {@link #jtiAndLocalSecureRandomToken(SecretTokenVault)}（JTI の UUID があっても、同じメソッドの SecureRandom の値が token 系へ届く）。</p>
 */
public class JtiSessionFixtureService {

    private static final int RANDOM_BYTE_LENGTH = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    public JtiSessionFixtureEntity issueWithJtiAndExternalToken(SecretTokenVault vault, String externalToken) {
        String jti = UUID.randomUUID().toString();
        JtiSessionFixtureEntity entity = new JtiSessionFixtureEntity();
        entity.setTokenHash(vault.hash(externalToken));
        entity.setJti(jti);
        return entity;
    }

    public JtiSessionFixtureEntity issueWithJtiAndHelperToken(SecretTokenVault vault) {
        String raw = RandomTokenFixtureHelper.generate();
        String jti = UUID.randomUUID().toString();
        JtiSessionFixtureEntity entity = new JtiSessionFixtureEntity();
        entity.setTokenHash(vault.hash(raw));
        entity.setJti(jti);
        return entity;
    }

    public JtiSessionFixtureEntity uuidReachesTokenHash(SecretTokenVault vault) {
        String jti = UUID.randomUUID().toString();
        JtiSessionFixtureEntity entity = new JtiSessionFixtureEntity();
        entity.setTokenHash(vault.hash(jti));
        entity.setJti(jti);
        return entity;
    }

    public JtiSessionFixtureEntity jtiAndLocalSecureRandomToken(SecretTokenVault vault) {
        String jti = UUID.randomUUID().toString();
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        JtiSessionFixtureEntity entity = new JtiSessionFixtureEntity();
        entity.setTokenHash(vault.hash(HexFormat.of().formatHex(bytes)));
        entity.setJti(jti);
        return entity;
    }
}
