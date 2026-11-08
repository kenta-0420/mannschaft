package com.mannschaft.app.role;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 試練（CMP-260826-1457 W1 P1・AC-6）: 不正・期限切れ・失効・使用済みの応答を<b>移行前の main で固定</b>する回帰試験。
 *
 * <p>照合をハッシュ検索へ変えるとき、分岐の順を変えて応答（ステータス・エラーコード）が変わると、新しい存在オラクルに
 * なりうる（陣立て書 §8）。この試験は移行前に green で、移行後も green であることを保証する。期待値は
 * 2026-11-07 の origin/main（W0 #4262 マージ後）で読んだ実装（{@code InviteService}・{@code GlobalExceptionHandler}：
 * ROLE_002=404、ROLE_003 は対応表に無く WARN の既定 400、ROLE_009 は 403 の上書き）から取った。</p>
 *
 * <p>fixture は発行 API で作り、状態は ID 指定の JDBC 更新で作る（平文列・ハッシュ列のどちらにも依存しない）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260826-1457 W1 P1 AC-6: 招待トークンの拒否応答（移行前と同じ）")
class InviteTokenRejectionRegressionIT extends InviteTokenHashItSupport {

    private static final Outcome NOT_FOUND = new Outcome(404, "ROLE_002");

    /** 存在しない平文: 旧形式（UUID）と新形式（Base64URL 43 字）のどちらでも同じ応答。 */
    @ParameterizedTest
    @ValueSource(strings = {"uuid", "base64url"})
    @DisplayName("存在しない平文は プレビュー・参加・辞退 のすべてで 404 ROLE_002")
    void 存在しない平文は不在応答(String form) throws Exception {
        String unknown = "uuid".equals(form) ? UUID.randomUUID().toString() : vault.issueBase64Url().plaintext();
        Long actor = user();

        assertThat(outcome(perform(actor, get("/api/v1/invite/{token}", unknown)))).isEqualTo(NOT_FOUND);
        assertThat(outcome(perform(actor, post("/api/v1/invite/{token}/join", unknown)))).isEqualTo(NOT_FOUND);
        assertThat(outcome(perform(actor, post("/api/v1/invite/{token}/decline", unknown)))).isEqualTo(NOT_FOUND);
    }

    @Test
    @DisplayName("期限切れの平文で参加すると 404 ROLE_002（未参加・利用回数は増えない）")
    void 期限切れは不在応答() throws Exception {
        Scope scope = scope("TEAM");
        JsonNode issued = issueShared(scope, issuer(scope));
        long id = issued.path("id").asLong();
        jdbc.update("UPDATE invite_tokens SET expires_at = ? WHERE id = ?", LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1), id);

        Long joiner = user();
        assertThat(outcome(perform(joiner, post("/api/v1/invite/{token}/join", issued.path("token").asText()))))
                .isEqualTo(NOT_FOUND);
        assertThat(isMember(scope, joiner)).isFalse();
        assertThat(usedCount(id)).isZero();
    }

    @Test
    @DisplayName("失効済みの平文で参加すると 404 ROLE_002")
    void 失効は不在応答() throws Exception {
        Scope scope = scope("TEAM");
        JsonNode issued = issueShared(scope, issuer(scope));
        long id = issued.path("id").asLong();
        jdbc.update("UPDATE invite_tokens SET revoked_at = ? WHERE id = ?", LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1), id);

        Long joiner = user();
        assertThat(outcome(perform(joiner, post("/api/v1/invite/{token}/join", issued.path("token").asText()))))
                .isEqualTo(NOT_FOUND);
        assertThat(isMember(scope, joiner)).isFalse();
    }

    @Test
    @DisplayName("使用回数の上限に達した平文で参加すると 400 ROLE_003")
    void 使用済みは上限応答() throws Exception {
        Scope scope = scope("TEAM");
        JsonNode issued = issueShared(scope, issuer(scope));
        long id = issued.path("id").asLong();
        jdbc.update("UPDATE invite_tokens SET max_uses = 1, used_count = 1 WHERE id = ?", id);

        Long joiner = user();
        assertThat(outcome(perform(joiner, post("/api/v1/invite/{token}/join", issued.path("token").asText()))))
                .isEqualTo(new Outcome(400, "ROLE_003"));
        assertThat(isMember(scope, joiner)).isFalse();
        assertThat(usedCount(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("共有リンク型を辞退すると 403 ROLE_009（辞退の概念を持たない）で失効しない")
    void 共有リンク型の辞退は宛先不一致応答() throws Exception {
        Scope scope = scope("TEAM");
        JsonNode issued = issueShared(scope, issuer(scope));

        assertThat(outcome(perform(user(), post("/api/v1/invite/{token}/decline", issued.path("token").asText()))))
                .isEqualTo(new Outcome(403, "ROLE_009"));
        assertThat(revokedAt(issued.path("id").asLong())).isNull();
    }

    @Test
    @DisplayName("期限切れ・失効・使用済みでもプレビューは 200（有効フラグで区別し、存在の有無は不在と同じ 404 だけ）")
    void 無効な招待のプレビューは200() throws Exception {
        Scope scope = scope("TEAM");
        Long admin = issuer(scope);
        JsonNode expired = issueShared(scope, admin);
        jdbc.update("UPDATE invite_tokens SET expires_at = ? WHERE id = ?", LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1),
                expired.path("id").asLong());
        JsonNode revoked = issueShared(scope, admin);
        jdbc.update("UPDATE invite_tokens SET revoked_at = ? WHERE id = ?", LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1),
                revoked.path("id").asLong());

        Long viewer = user();
        assertThat(perform(viewer, get("/api/v1/invite/{token}", expired.path("token").asText())).getStatus())
                .isEqualTo(200);
        assertThat(perform(viewer, get("/api/v1/invite/{token}", revoked.path("token").asText())).getStatus())
                .isEqualTo(200);
    }
}
