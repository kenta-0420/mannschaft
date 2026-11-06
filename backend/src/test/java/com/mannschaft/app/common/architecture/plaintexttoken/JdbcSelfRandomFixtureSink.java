package com.mannschaft.app.common.architecture.plaintexttoken;

import com.mannschaft.app.common.token.IssuedToken;
import com.mannschaft.app.common.token.PlaintextTokenDebt;
import com.mannschaft.app.common.token.SecretTokenVault;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * fixture（番人6 JDBC 側）: Entity の setter を通らず、JDBC で直接 INSERT/UPDATE する経路。
 *
 * <p>本番の {@code ConfirmableFanoutChunkSink#insertRecipients}（{@code StringBuilder} で
 * {@code INSERT INTO ...} を組み、行ごとに {@code UUID.randomUUID()} を詰めて {@code jdbcTemplate.update}）と
 * 同じ形にしてある。</p>
 *
 * <p>陽性: {@link #insertWithUuid(JdbcTemplate, List)}、{@link #updateWithSecureRandom(JdbcTemplate, long)}。
 * 陰性: {@link #insertWithVault(JdbcTemplate, SecretTokenVault, long)}（金庫のハッシュを入れる）、
 * {@link #selectWithCorrelation(JdbcTemplate)}（SELECT と相関 ID の UUID）。
 * 免除: {@link #insertUnderDebt(JdbcTemplate, long)}。</p>
 */
public class JdbcSelfRandomFixtureSink {

    private static final int RANDOM_BYTE_LENGTH = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    public void insertWithUuid(JdbcTemplate jdbcTemplate, List<Long> ownerIds) {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO plaintext_guard_fixture_rows (owner_id, row_token) VALUES ");
        Object[] args = new Object[ownerIds.size() * 2];
        int a = 0;
        for (int i = 0; i < ownerIds.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append("(?,?)");
            args[a++] = ownerIds.get(i);
            args[a++] = UUID.randomUUID().toString();
        }
        jdbcTemplate.update(sql.toString(), args);
    }

    public void updateWithSecureRandom(JdbcTemplate jdbcTemplate, long rowId) {
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        jdbcTemplate.update("UPDATE plaintext_guard_fixture_rows SET row_token = ? WHERE id = ?",
                HexFormat.of().formatHex(bytes), rowId);
    }

    @PlaintextTokenDebt(cmp = "CMP-260826-1457")
    public void insertUnderDebt(JdbcTemplate jdbcTemplate, long ownerId) {
        jdbcTemplate.update("INSERT INTO plaintext_guard_fixture_rows (owner_id, row_token) VALUES (?,?)",
                ownerId, UUID.randomUUID().toString());
    }

    public String insertWithVault(JdbcTemplate jdbcTemplate, SecretTokenVault vault, long ownerId) {
        IssuedToken issued = vault.issueBase64Url();
        jdbcTemplate.update("INSERT INTO plaintext_guard_fixture_rows (owner_id, row_token_hash) VALUES (?,?)",
                ownerId, issued.hash());
        return issued.plaintext();
    }

    public List<Long> selectWithCorrelation(JdbcTemplate jdbcTemplate) {
        String correlationId = UUID.randomUUID().toString();
        return jdbcTemplate.query("SELECT owner_id FROM plaintext_guard_fixture_rows WHERE correlation_id = ?",
                (rs, rowNum) -> rs.getLong("owner_id"), correlationId);
    }
}
