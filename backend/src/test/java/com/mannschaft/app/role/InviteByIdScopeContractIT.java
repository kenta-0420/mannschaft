package com.mannschaft.app.role;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 招待 ID 指定の承諾・辞退（{@code InviteController#acceptInvitation} / {@code InviteController#declineInvitation}）の
 * 自己スコープ契約。宛先本人でない利用者からは、招待の状態を一切変えられず、不在と同じ 404 になることを固定する。
 * 実 controller → facade → writer の配線を Bean の置換なしで踏む。
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class InviteByIdScopeContractIT extends InviteGenerationItSupport {

    @Autowired private MockMvc mvc;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("InviteController#acceptInvitation: 宛先本人でない利用者は 404 で、使用回数も失効状態も変わらない")
    void acceptInvitationByNonTargetIsNotFoundAndUnchanged() throws Exception {
        Scope scope = scope("TEAM");
        Long target = user();
        Long other = user();
        Long tokenId = inTx(() -> tokens.saveAndFlush(token(scope, 0, null, 3, 0, target, null).toBuilder()
                .createdBy(admin(scope)).build())).getId();
        var before = jdbc.queryForMap("SELECT * FROM invite_tokens WHERE id = ?", tokenId);

        assertThat(post(other, "/api/v1/invitations/" + tokenId + "/accept")).isEqualTo(404);

        assertThat(jdbc.queryForMap("SELECT * FROM invite_tokens WHERE id = ?", tokenId)).isEqualTo(before);
    }

    @Test
    @DisplayName("InviteController#declineInvitation: 宛先本人でない利用者は 404 で、招待を失効させられない")
    void declineInvitationByNonTargetIsNotFoundAndUnchanged() throws Exception {
        Scope scope = scope("TEAM");
        Long target = user();
        Long other = user();
        Long tokenId = inTx(() -> tokens.saveAndFlush(token(scope, 0, null, 3, 0, target, null).toBuilder()
                .createdBy(admin(scope)).build())).getId();
        var before = jdbc.queryForMap("SELECT * FROM invite_tokens WHERE id = ?", tokenId);

        assertThat(post(other, "/api/v1/invitations/" + tokenId + "/decline")).isEqualTo(404);

        assertThat(jdbc.queryForMap("SELECT * FROM invite_tokens WHERE id = ?", tokenId)).isEqualTo(before);
    }

    private int post(Long actor, String path) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor.toString(), null, List.of()));
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path))
                .andReturn().getResponse().getStatus();
    }
}
