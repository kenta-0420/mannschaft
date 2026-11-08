package com.mannschaft.app.common.architecture.plaintexttoken;

import com.mannschaft.app.common.token.IssuedToken;
import com.mannschaft.app.common.token.PlaintextTokenDebt;
import com.mannschaft.app.common.token.SecretTokenVault;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * fixture（番人3）: 金庫を通さずに合言葉を発行して Entity に詰めるメソッド群。
 *
 * <p>陽性（違反）:</p>
 * <ul>
 *   <li>{@link #issueWithUuidSetter()}: {@code UUID.randomUUID()} と token 系 setter の同居</li>
 *   <li>{@link #issueWithUuidBuilder()}: {@code UUID.randomUUID()} と Entity の builder の token 系メソッドの同居</li>
 *   <li>{@link #issueWithSecureRandom()}: {@code SecureRandom#nextBytes} と token 系 setter の同居</li>
 *   <li>{@link #issueUnderForeignDebt()}: 債務注釈が他の戦役の cmp を名乗っている（免除にならない・AC-27）</li>
 * </ul>
 * <p>陰性（違反でない）:</p>
 * <ul>
 *   <li>{@link #issueViaVault(SecretTokenVault)}: 金庫の発行結果のハッシュだけを詰める</li>
 *   <li>{@link #stampCorrelation()}: {@code UUID.randomUUID()} を token 系でない列に詰める</li>
 *   <li>{@link #stampExpiry()}: {@code UUID.randomUUID()} と同居する token 系の名前の setter が
 *       String/char[] を受け取らない（期限の日時）</li>
 *   <li>{@link #issueUnderDebt()}: 正しい債務注釈で免除される（免除一覧には載る）</li>
 * </ul>
 */
public class SelfIssuingFixtureService {

    private static final int RANDOM_BYTE_LENGTH = 32;

    private static final long EXPIRY_DAYS = 7L;

    private final SecureRandom secureRandom = new SecureRandom();

    public PlaintextTokenFixtureEntity issueWithUuidSetter() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(UUID.randomUUID().toString());
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithUuidBuilder() {
        return PlaintextTokenFixtureEntity.builder().token(UUID.randomUUID().toString()).build();
    }

    public PlaintextTokenFixtureEntity issueWithSecureRandom() {
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(HexFormat.of().formatHex(bytes));
        return entity;
    }

    @PlaintextTokenDebt(cmp = "CMP-999999-0000")
    public PlaintextTokenFixtureEntity issueUnderForeignDebt() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(UUID.randomUUID().toString());
        return entity;
    }

    @PlaintextTokenDebt(cmp = "CMP-260826-1457")
    public PlaintextTokenFixtureEntity issueUnderDebt() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(UUID.randomUUID().toString());
        return entity;
    }

    public String issueViaVault(SecretTokenVault vault) {
        IssuedToken issued = vault.issueBase64Url();
        HashedTokenFixtureEntity entity = new HashedTokenFixtureEntity();
        entity.setTokenHash(issued.hash());
        return issued.plaintext();
    }

    public HashedTokenFixtureEntity stampCorrelation() {
        HashedTokenFixtureEntity entity = new HashedTokenFixtureEntity();
        entity.setCorrelationId(UUID.randomUUID().toString());
        return entity;
    }

    public PlaintextTokenFixtureEntity stampExpiry() {
        String correlation = UUID.randomUUID().toString();
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setTokenExpiresAt(LocalDateTime.now().plusDays(EXPIRY_DAYS));
        return correlation.isEmpty() ? null : entity;
    }

    /** 金庫外の発行: 自前乱数を vault.hash() に通して setTokenHash に詰める形（Hash を含む書込みでも金庫外は違反）。 */
    public HashedTokenFixtureEntity issueWithUuidThenHash(SecretTokenVault vault) {
        HashedTokenFixtureEntity entity = new HashedTokenFixtureEntity();
        entity.setTokenHash(vault.hash(UUID.randomUUID().toString()));
        return entity;
    }

    /** 他の戦役の cmp を名乗る債務注釈は、Entity に書かない乱数ヘルパでも違反（cmp の検査は形に関係なく常に効く）。 */
    @PlaintextTokenDebt(cmp = "CMP-999999-0000")
    public String randomHelperUnderForeignDebt() {
        return UUID.randomUUID().toString();
    }
}
