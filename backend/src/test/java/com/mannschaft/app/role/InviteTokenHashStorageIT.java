package com.mannschaft.app.role;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.admin.service.BetaRestrictionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 試練（CMP-260826-1457 W1 P1・系統1 共有リンク型）: 招待トークンをハッシュで保存し、平文は発行応答にだけ出す。
 *
 * <p>AC-1（DB に平文が無い・{@code hash(平文)} と一致）、AC-2（Base64URL 43 字＝金庫の乱数）、
 * AC-3（発行応答にだけ平文）、AC-4（一覧応答のキー集合の完全一致）、AC-5（発行時の平文で参加・プレビュー・
 * ベータ判定が通り、DB のハッシュ値そのものでは通らない）。</p>
 *
 * <p>red の理由: いまの main は {@code invite_tokens.token} に UUID の平文を保存し、一覧 API も平文を返す。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260826-1457 W1 P1: 共有招待トークンのハッシュ保存")
class InviteTokenHashStorageIT extends InviteTokenHashItSupport {

    @Autowired private BetaRestrictionService betaRestrictionService;

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC-1: 発行した招待の DB には token_hash だけがあり、値は hash(発行時の平文) で平文と異なる")
    void DBにはハッシュだけが残る(String type) throws Exception {
        Scope scope = scope(type);
        JsonNode issued = issueShared(scope, issuer(scope));
        String plaintext = issued.path("token").asText();
        long id = issued.path("id").asLong();

        assertHashSchema();
        String stored = jdbc.queryForObject("SELECT token_hash FROM invite_tokens WHERE id = ?", String.class, id);
        assertThat(stored).matches(SHA256_HEX).isEqualTo(vault.hash(plaintext)).isNotEqualTo(plaintext);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC-2・AC-3: 発行応答は平文 token（Base64URL 43 字）を含み、キー集合は発行応答の許可フィールドと一致する")
    void 発行応答にだけ平文が載る(String type) throws Exception {
        Scope scope = scope(type);
        JsonNode issued = issueShared(scope, issuer(scope));

        assertThat(keys(issued)).containsExactlyInAnyOrder(
                "id", "token", "roleName", "expiresAt", "maxUses", "usedCount", "revokedAt", "createdAt");
        assertThat(issued.path("token").asText()).matches(BASE64URL_43);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC-4: 一覧応答の各行は許可フィールドと完全一致し、平文（の一部も）を含まない")
    void 一覧応答は平文を返さない(String type) throws Exception {
        Scope scope = scope(type);
        Long admin = issuer(scope);
        String plaintext = issueShared(scope, admin).path("token").asText();

        MockHttpServletResponse response = perform(admin,
                get("/api/v1/" + collection(scope) + "/{slug}/invite-tokens", slug(scope)));
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode rows = json(response).path("data");
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.size()).isEqualTo(1);
        assertThat(keys(rows.get(0))).containsExactlyInAnyOrder(
                "id", "roleName", "expiresAt", "maxUses", "usedCount", "revokedAt", "createdAt");
        // 先頭・末尾の数文字も出さない（部分表示・伏字の禁止）
        String body = response.getContentAsString();
        assertThat(body).doesNotContain(plaintext)
                .doesNotContain(plaintext.substring(0, 8))
                .doesNotContain(plaintext.substring(plaintext.length() - 8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC-5: 発行時の平文でプレビュー・参加でき、DB のハッシュ値そのものでは参加できない（不在と同じ 404 ROLE_002）")
    void 発行時の平文で参加でき_ハッシュ値では参加できない(String type) throws Exception {
        Scope scope = scope(type);
        JsonNode issued = issueShared(scope, issuer(scope));
        String plaintext = issued.path("token").asText();
        long id = issued.path("id").asLong();

        assertHashSchema();
        String stored = jdbc.queryForObject("SELECT token_hash FROM invite_tokens WHERE id = ?", String.class, id);
        Long outsider = user();
        assertThat(outcome(perform(outsider, post("/api/v1/invite/{token}/join", stored))))
                .isEqualTo(new Outcome(404, "ROLE_002"));
        assertThat(isMember(scope, outsider)).isFalse();

        Long joiner = user();
        assertThat(perform(joiner, get("/api/v1/invite/{token}", plaintext)).getStatus()).isEqualTo(200);
        assertThat(perform(joiner, post("/api/v1/invite/{token}/join", plaintext)).getStatus()).isEqualTo(200);
        assertThat(isMember(scope, joiner)).isTrue();
        assertThat(usedCount(id)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC-5（越境 admin）: ベータ招待の判定は発行時の平文で通り、DB のハッシュ値では通らない")
    void ベータ判定は平文で通りハッシュ値では通らない(String type) throws Exception {
        Scope scope = scope(type);
        JsonNode issued = issueShared(scope, issuer(scope));
        String plaintext = issued.path("token").asText();

        assertHashSchema();
        String stored = jdbc.queryForObject("SELECT token_hash FROM invite_tokens WHERE id = ?", String.class,
                issued.path("id").asLong());
        assertThat(betaRestrictionService.isBetaTokenValid(plaintext)).isTrue();
        assertThat(betaRestrictionService.isBetaTokenValid(stored)).isFalse();
    }
}
