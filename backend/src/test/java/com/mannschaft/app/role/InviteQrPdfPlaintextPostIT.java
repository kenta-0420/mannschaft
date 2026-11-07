package com.mannschaft.app.role;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 試練（CMP-260826-1457 W1 P1・AC-11）: チームの招待 QR-PDF は、発行直後に平文を POST した場合だけ作れる。
 *
 * <p>契約（陣立て書 §2-2 の 6・10）: {@code POST /api/v1/teams/{teamId}/invite-tokens/pdf}、body
 * {@code {"token": "<平文>"}}。{@code teamId} は既存の {@code ScopeSlugIdConverter} により slug でも引ける。
 * {@code vault.hash(平文)} で引いた招待が {@code teamId} に属さなければ、存在しない平文と同じ応答で拒否する。
 * tokenId から作る旧 {@code GET .../invite-tokens/{tokenId}/pdf} は撤去する（平文を引けないため）。</p>
 *
 * <p>red の理由: いまの main には POST の入口が無く（404/405）、旧 GET が tokenId だけで PDF を作る。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260826-1457 W1 P1 AC-11: 招待 QR-PDF は平文の POST でだけ作る")
class InviteQrPdfPlaintextPostIT extends InviteTokenHashItSupport {

    private MockHttpServletResponse postPdf(Long actor, Scope team, String plaintext) throws Exception {
        return perform(actor, post("/api/v1/teams/{slug}/invite-tokens/pdf", slug(team))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("token", plaintext))));
    }

    @Test
    @DisplayName("自チームの発行直後の平文を POST すると PDF が返る")
    void 自チームの平文でPDFを作れる() throws Exception {
        Scope team = scope("TEAM");
        Long admin = issuer(team);
        String plaintext = issueShared(team, admin).path("token").asText();

        MockHttpServletResponse response = postPdf(admin, team, plaintext);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PDF_VALUE);
        byte[] pdf = response.getContentAsByteArray();
        assertThat(pdf.length).isGreaterThan(4);
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("他チームの有効な平文は、存在しない平文と同じ 404 ROLE_002 で拒否される")
    void 他チームの平文は不在と同じ応答() throws Exception {
        Scope team = scope("TEAM");
        Long admin = issuer(team);
        Scope otherTeam = scope("TEAM");
        String otherPlaintext = issueShared(otherTeam, issuer(otherTeam)).path("token").asText();

        Outcome otherTeams = outcome(postPdf(admin, team, otherPlaintext));
        Outcome unknown = outcome(postPdf(admin, team, vault.issueBase64Url().plaintext()));
        assertThat(unknown).isEqualTo(new Outcome(404, "ROLE_002"));
        assertThat(otherTeams).isEqualTo(unknown);
    }

    @Test
    @DisplayName("チームの ADMIN でなければ平文を持っていても 403")
    void 非管理者は作れない() throws Exception {
        Scope team = scope("TEAM");
        String plaintext = issueShared(team, issuer(team)).path("token").asText();

        assertThat(postPdf(user(), team, plaintext).getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("tokenId だけを渡す旧 GET .../invite-tokens/{tokenId}/pdf では PDF を作れない")
    void tokenIdだけではPDFを作れない() throws Exception {
        Scope team = scope("TEAM");
        Long admin = issuer(team);
        JsonNode issued = issueShared(team, admin);

        MockHttpServletResponse response = perform(admin, get("/api/v1/teams/{teamId}/invite-tokens/{tokenId}/pdf",
                team.id(), issued.path("id").asLong()));
        assertThat(response.getStatus()).as("旧 GET が残っていて PDF を返している").isIn(404, 405);
        assertThat(String.valueOf(response.getContentType())).doesNotStartWith(MediaType.APPLICATION_PDF_VALUE);
    }
}
