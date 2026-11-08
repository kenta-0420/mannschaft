package com.mannschaft.app.role;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 試練（CMP-260826-1457 W1 P1・系統1 承諾型）: 宛先指定型の招待は平文なしで、invitationId と認証済み本人の照合で
 * 承諾・辞退する（御裁可 §6-2）。
 *
 * <p>AC-12（本人は ID で承諾・辞退でき、カード・発行応答に token が無い。非宛先・他テナントは承諾・辞退できない）、
 * AC-12b（共有リンク型の ID は存在しない ID と同じ応答）、AC-12c（期限・利用回数・失効の判定がトークン入口と同じ）。</p>
 *
 * <p>契約: {@code POST /api/v1/invite/invitations/{tokenId}/accept}（body 任意 {@code {"folderId": n}}）・
 * {@code POST /api/v1/invite/invitations/{tokenId}/decline}。宛先本人でない・共有リンク型・存在しない ID は、
 * いずれも <b>404 ROLE_002</b>（ID は推測できるので、宛先違いを 403 で区別しない＝存在オラクルを作らない）。</p>
 *
 * <p>red の理由: いまの main には ID 入口が無く、招待カードと発行応答が平文 token を返す。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260826-1457 W1 P1: 承諾型招待の ID 承諾・辞退")
class MembershipInviteIdAcceptIT extends InviteTokenHashItSupport {

    private static final Outcome NOT_FOUND = new Outcome(404, "ROLE_002");

    private record Issued(Scope scope, Long admin, Long target, Long channelId, long tokenId, JsonNode response) {
    }

    private Issued issueToTarget() throws Exception {
        Scope scope = scope("TEAM");
        Long admin = issuer(scope);
        Long target = user();
        Long channelId = createDm(admin, target);
        JsonNode data = issueMembershipInvite(channelId, scope, admin);
        return new Issued(scope, admin, target, channelId, data.path("tokenId").asLong(), data);
    }

    private MockHttpServletResponse accept(Long actor, long tokenId) throws Exception {
        return perform(actor, post("/api/v1/invite/invitations/{id}/accept", tokenId));
    }

    private MockHttpServletResponse decline(Long actor, long tokenId) throws Exception {
        return perform(actor, post("/api/v1/invite/invitations/{id}/decline", tokenId));
    }

    @Test
    @DisplayName("AC-12: 承諾型招待の発行応答（発行者へ返す）に token が無い")
    void 発行応答にtokenが無い() throws Exception {
        Issued issued = issueToTarget();
        assertThat(issued.tokenId()).isPositive();
        assertThat(keys(issued.response())).contains("tokenId").doesNotContain("token");
    }

    @Test
    @DisplayName("AC-12: 宛先本人が見る招待カード（inviteData）に token が無く、tokenId がある")
    void 宛先本人のカードにtokenが無い() throws Exception {
        Issued issued = issueToTarget();

        MockHttpServletResponse response = perform(issued.target(),
                get("/api/v1/chat/channels/{cid}/messages", issued.channelId()));
        assertThat(response.getStatus()).isEqualTo(200);
        List<JsonNode> cards = new ArrayList<>();
        collectInviteData(json(response), cards);
        assertThat(cards).as("招待カードの inviteData が返っていない").hasSize(1);
        assertThat(cards.getFirst().path("tokenId").asLong()).isEqualTo(issued.tokenId());
        assertThat(cards.getFirst().path("isTarget").asBoolean()).isTrue();
        assertThat(keys(cards.getFirst())).doesNotContain("token");
    }

    @Test
    @DisplayName("AC-12: 宛先本人は ID で承諾でき、参加して利用回数が 1 になる")
    void 宛先本人はIDで承諾できる() throws Exception {
        Issued issued = issueToTarget();

        assertThat(accept(issued.target(), issued.tokenId()).getStatus()).isEqualTo(200);
        assertThat(isMember(issued.scope(), issued.target())).isTrue();
        assertThat(usedCount(issued.tokenId())).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-12: 宛先本人は ID で辞退でき、失効して以後 ID でも承諾できない")
    void 宛先本人はIDで辞退できる() throws Exception {
        Issued issued = issueToTarget();

        assertThat(decline(issued.target(), issued.tokenId()).getStatus()).isEqualTo(200);
        assertThat(revokedAt(issued.tokenId())).isNotNull();
        assertThat(outcome(accept(issued.target(), issued.tokenId()))).isEqualTo(NOT_FOUND);
        assertThat(isMember(issued.scope(), issued.target())).isFalse();
    }

    @Test
    @DisplayName("AC-12: 非宛先（第三者・発行者・他テナントの ADMIN）は ID で承諾できず、存在しない ID と同じ 404 ROLE_002")
    void 非宛先はIDで承諾できない() throws Exception {
        Issued issued = issueToTarget();
        Long stranger = user();
        Long otherTenantAdmin = issuer(scope("TEAM"));

        for (Long actor : List.of(stranger, issued.admin(), otherTenantAdmin)) {
            assertThat(outcome(accept(actor, issued.tokenId()))).as("actor=%s", actor).isEqualTo(NOT_FOUND);
        }
        assertThat(usedCount(issued.tokenId())).isZero();
        assertThat(isMember(issued.scope(), stranger)).isFalse();
        assertThat(isMember(issued.scope(), otherTenantAdmin)).isFalse();
        assertThat(isMember(issued.scope(), issued.target())).isFalse();
    }

    @Test
    @DisplayName("AC-12: 非宛先は ID で辞退できず（404 ROLE_002）、招待は失効しない")
    void 非宛先はIDで辞退できない() throws Exception {
        Issued issued = issueToTarget();

        for (Long actor : List.of(user(), issued.admin(), issuer(scope("TEAM")))) {
            assertThat(outcome(decline(actor, issued.tokenId()))).as("actor=%s", actor).isEqualTo(NOT_FOUND);
        }
        assertThat(revokedAt(issued.tokenId())).isNull();
    }

    @Test
    @DisplayName("AC-12b: 共有リンク型の招待 ID を渡すと、存在しない ID と同じ 404 ROLE_002 で、消費されない")
    void 共有リンク型のIDは不在と同じ応答() throws Exception {
        Scope scope = scope("TEAM");
        JsonNode shared = issueShared(scope, issuer(scope));
        long sharedId = shared.path("id").asLong();
        Long actor = user();

        Outcome sharedOutcome = outcome(accept(actor, sharedId));
        Outcome absentOutcome = outcome(accept(actor, Long.MAX_VALUE));
        assertThat(absentOutcome).isEqualTo(NOT_FOUND);
        assertThat(sharedOutcome).isEqualTo(absentOutcome);
        assertThat(outcome(decline(actor, sharedId))).isEqualTo(absentOutcome);
        assertThat(usedCount(sharedId)).isZero();
        assertThat(revokedAt(sharedId)).isNull();
        assertThat(isMember(scope, actor)).isFalse();
    }

    /** AC-12c の状態表。期待値はトークン入口の現行判定（{@code InviteService#joinByInvite}）と同じ。 */
    enum InviteState {
        /** 期限の直前（実クロックでは「ちょうど」を固定できないため、+5 秒の境界近傍で inclusive を担保する）。 */
        JUST_BEFORE_EXPIRY(200, null),
        /** 期限の直後（-1 秒）。 */
        JUST_AFTER_EXPIRY(404, "ROLE_002"),
        /** 利用回数の上限に到達済み。 */
        USES_EXHAUSTED(400, "ROLE_003"),
        /** 失効済み。 */
        REVOKED(404, "ROLE_002");

        final int status;
        final String code;

        InviteState(int status, String code) {
            this.status = status;
            this.code = code;
        }
    }

    @ParameterizedTest
    @EnumSource(InviteState.class)
    @DisplayName("AC-12c: 同じ状態の招待に対し、トークン入口と ID 入口の結果（ステータス・エラーコード・参加）が一致する")
    void 両入口の判定が一致する(InviteState state) throws Exception {
        Long target = user();
        Scope viaToken = scope("TEAM");
        Scope viaId = scope("TEAM");
        String plaintext = vault.issueBase64Url().plaintext();
        insertInState(viaToken, plaintext, target, admin(viaToken), state);
        Long idInviteId = insertInState(viaId, vault.issueBase64Url().plaintext(), target, admin(viaId), state);

        Outcome tokenEntry = outcome(perform(target, post("/api/v1/invite/{token}/join", plaintext)));
        Outcome idEntry = outcome(accept(target, idInviteId));

        Outcome expected = new Outcome(state.status, state.code);
        assertThat(tokenEntry).as("トークン入口").isEqualTo(expected);
        assertThat(idEntry).as("ID 入口").isEqualTo(tokenEntry);
        assertThat(isMember(viaId, target)).isEqualTo(isMember(viaToken, target));
    }

    private Long insertInState(Scope scope, String plaintext, Long target, Long createdBy, InviteState state) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        return switch (state) {
            case JUST_BEFORE_EXPIRY -> insertTargetedInviteWithKnownPlaintext(scope, plaintext, target, createdBy,
                    now.plusSeconds(5), 1, 0, null);
            case JUST_AFTER_EXPIRY -> insertTargetedInviteWithKnownPlaintext(scope, plaintext, target, createdBy,
                    now.minusSeconds(1), 1, 0, null);
            case USES_EXHAUSTED -> insertTargetedInviteWithKnownPlaintext(scope, plaintext, target, createdBy,
                    now.plusDays(7), 1, 1, null);
            case REVOKED -> insertTargetedInviteWithKnownPlaintext(scope, plaintext, target, createdBy,
                    now.plusDays(7), 1, 0, now.minusMinutes(1));
        };
    }

    private void collectInviteData(JsonNode node, List<JsonNode> found) {
        if (node.isObject()) {
            JsonNode invite = node.get("inviteData");
            if (invite != null && invite.isObject()) {
                found.add(invite);
            }
            node.elements().forEachRemaining(child -> collectInviteData(child, found));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectInviteData(child, found));
        }
    }
}
