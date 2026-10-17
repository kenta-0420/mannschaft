package com.mannschaft.app.role;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.dto.CreateInviteTokenRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** G19: 共有発行の実controller→facade配線とscope認可を固定する（Beanの置換なし）。 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class InviteGenerationHttpAuthorizationIT extends InviteGenerationItSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private MembershipRepository memberships;

    @ParameterizedTest
    @CsvSource({"TEAM,ADMIN", "TEAM,DEPUTY_ADMIN", "ORGANIZATION,ADMIN", "ORGANIZATION,DEPUTY_ADMIN"})
    @DisplayName("G19: 対象scope ADMIN/DEPUTY_ADMINはHTTPから現世代tokenを発行できる")
    void properScopeAdminOrDeputyCanIssue(String type, String role) throws Exception {
        Scope scope = scope(type);
        Long actor = actorWithRole(scope, role, "ADMIN".equals(role) ? 2 : 3);
        jdbc.update("UPDATE " + scope.table() + " SET closure_generation = 2 WHERE id = ?", scope.id());

        assertThat(issue(scope, actor)).isEqualTo(201);
        assertThat(jdbc.queryForList("SELECT scope_closure_generation FROM invite_tokens WHERE " + scopeColumn(type) + " = ?",
                Long.class, scope.id())).containsExactly(2L);
    }

    @ParameterizedTest
    @CsvSource({"TEAM,MEMBER", "TEAM,OTHER_SCOPE_ADMIN", "ORGANIZATION,MEMBER", "ORGANIZATION,OTHER_SCOPE_ADMIN"})
    @DisplayName("G19: 一般ユーザー/他scope ADMINはHTTP発行403でtokenを変えない")
    void unauthorizedHttpIssueLeavesTokensUnchanged(String type, String actorKind) throws Exception {
        Scope scope = scope(type);
        Long actor = "OTHER_SCOPE_ADMIN".equals(actorKind) ? admin(scope(type)) : user();
        if ("MEMBER".equals(actorKind)) {
            inTx(() -> memberships.saveAndFlush(MembershipEntity.builder().userId(actor)
                    .scopeType(ScopeType.valueOf(type)).scopeId(scope.id()).roleKind(RoleKind.MEMBER)
                    .joinedAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build()));
        }
        var existing = token(scope, 0);
        var before = jdbc.queryForMap("SELECT * FROM invite_tokens WHERE id = ?", existing.getId());

        assertThat(issue(scope, actor)).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invite_tokens WHERE " + scopeColumn(type) + " = ?",
                Long.class, scope.id())).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM invite_tokens WHERE id = ?", existing.getId())).isEqualTo(before);
    }

    private int issue(Scope scope, Long actor) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor.toString(), null, List.of()));
        String slug = jdbc.queryForObject("SELECT slug FROM " + scope.table() + " WHERE id = ?", String.class, scope.id());
        String collection = "TEAM".equals(scope.type()) ? "teams" : "organizations";
        return mvc.perform(post("/api/v1/" + collection + "/{slug}/invite-tokens", slug)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new CreateInviteTokenRequest(scope.memberRoleId(), null, null))))
                .andReturn().getResponse().getStatus();
    }

    private String scopeColumn(String type) {
        return "TEAM".equals(type) ? "team_id" : "organization_id";
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }
}
