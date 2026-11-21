package com.mannschaft.app.tournament;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static com.mannschaft.app.tournament.TournamentContactOracleFixture.ABSENT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * CMP-261116-0726 大会連絡スペースの存在オラクル是正 — 公開設定 API の受け入れテスト（試練・red）。
 *
 * <p>対象 EP（{@code TournamentContactSpaceController}）:</p>
 * <ul>
 *   <li>{@code GET /api/v1/tournaments/{t}/contact-spaces}</li>
 *   <li>{@code PATCH /api/v1/tournaments/{t}/contact-spaces/{spaceId}/visibility}</li>
 *   <li>{@code GET /api/v1/tournaments/{t}/divisions/{d}/contact-spaces}</li>
 *   <li>{@code PATCH /api/v1/tournaments/{t}/divisions/{d}/contact-spaces/{spaceId}/visibility}</li>
 * </ul>
 *
 * <p><b>期待（設計書 §3 フォルダ・公開設定表・AC-11/12/13/18）</b>: 存在を知り得ない者（いずれのスペースも
 * 閲覧できない者）は不在の大会／ディビジョンと status・error.code・error.message・本文全体まで同一の
 * 404 TOUR_029。知り得るが管理権のない者（参加チームの active member・公開スペースの部外者）は 403 TOUR_032。
 * 正ディビジョンの主催 ADMIN でもパスの大会 ID が帰属と食い違えば 404 TOUR_029 で不在と一致（AC-18）。
 * 拒否時は is_public・監査ログを一切変えない。</p>
 *
 * <p><b>red の根拠（origin/main 33066499df）</b>: {@code TournamentContactAccessService#checkVisibilityManage} は
 * 大会／ディビジョンが実在すれば知り得るか否かを問わず 403 TOUR_032 を返し（存在オラクル）、ディビジョン系は
 * パスの {@code tournamentId} を一切検証しない（AC-18 で 200・設定変更が通る）。</p>
 *
 * <p>実 JWT・実 Security フィルタ・実 MySQL（TEST_CONVENTION §3.1.2）。fixture は別 TX で commit し所有行だけ後始末する。
 * 本番側の新設クラス（{@code TournamentContactSpaceAccessGate} 等）は参照しない。</p>
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261116-0726 公開設定 API の存在オラクル是正（実JWT/filter）")
class TournamentContactSpaceVisibilityOracleContractIT extends AbstractMySqlIntegrationTest {

    private static final String AUDIT_EVENT = "TOURNAMENT_CONTACT_SPACE_VISIBILITY_UPDATED";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @PersistenceContext private EntityManager em;

    private TournamentContactOracleFixture f;

    @BeforeEach
    void fixtureを別TXでcommitする() {
        f = new TournamentContactOracleFixture(em, jdbc, txManager, users, organizations, teams, "CMP0726VIS");
        f.setUp();
    }

    @AfterEach
    void 所有行だけ片付ける() throws Exception {
        try {
            // 監査ログは @Async 書き込み。成功系テストの書き込みを待ってから消す。
            Thread.sleep(300);
            f.cleanup();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ========================================================================
    // AC-11: 知り得ない者は不在と同一の 404 TOUR_029、知り得るが管理権なしは 403 TOUR_032
    // ========================================================================

    @Test
    @DisplayName("AC-11: 閲覧権なし（部外者・主催組織一般・他テナントADMIN・辞退/失格チーム代表）の大会 contact-spaces 一覧は不在大会と同一の 404 TOUR_029")
    void ac11_知り得ない者の大会一覧は不在と同一() throws Exception {
        for (Long actor : f.nonViewers()) {
            assertSameAsMissing(listTournament(f.t1), listTournament(ABSENT_ID), actor, "TOUR_029");
        }
    }

    @Test
    @DisplayName("AC-11: 閲覧権なしのディビジョン contact-spaces 一覧は不在ディビジョンと同一の 404 TOUR_029")
    void ac11_知り得ない者のディビジョン一覧は不在と同一() throws Exception {
        for (Long actor : f.nonViewers()) {
            assertSameAsMissing(listDivision(f.t1, f.d1), listDivision(f.t1, ABSENT_ID), actor, "TOUR_029");
        }
    }

    @Test
    @DisplayName("AC-11: 閲覧権なしの大会 visibility PATCH は不在大会と同一の 404 TOUR_029 で、is_public・監査ログは不変")
    void ac11_知り得ない者の大会PATCHは不在と同一で不変() throws Exception {
        UUID space = f.spaceId(ContactSpaceScopeType.TOURNAMENT, f.t1, ContactSpaceKind.BULLETIN);
        long auditBefore = countAudit(space);
        for (Long actor : f.nonViewers()) {
            assertSameAsMissing(patchTournament(f.t1, space, true), patchTournament(ABSENT_ID, space, true),
                    actor, "TOUR_029");
        }
        assertUnchanged(space, false, auditBefore);
    }

    @Test
    @DisplayName("AC-11: 閲覧権なしのディビジョン visibility PATCH は不在ディビジョンと同一の 404 TOUR_029 で、is_public・監査ログは不変")
    void ac11_知り得ない者のディビジョンPATCHは不在と同一で不変() throws Exception {
        UUID space = f.spaceId(ContactSpaceScopeType.TOURNAMENT_DIVISION, f.d1, ContactSpaceKind.BULLETIN);
        long auditBefore = countAudit(space);
        for (Long actor : f.nonViewers()) {
            assertSameAsMissing(patchDivision(f.t1, f.d1, space, true), patchDivision(f.t1, ABSENT_ID, space, true),
                    actor, "TOUR_029");
        }
        assertUnchanged(space, false, auditBefore);
    }

    @Test
    @DisplayName("AC-11: 知り得るが管理権なし（参加チームの代表・副代表・一般）の一覧・PATCH は 403 TOUR_032 で、is_public・監査ログは不変")
    void ac11_知り得るが管理権なしは403_TOUR_032() throws Exception {
        UUID tSpace = f.spaceId(ContactSpaceScopeType.TOURNAMENT, f.t1, ContactSpaceKind.BULLETIN);
        UUID dSpace = f.spaceId(ContactSpaceScopeType.TOURNAMENT_DIVISION, f.d1, ContactSpaceKind.BULLETIN);
        long tAudit = countAudit(tSpace);
        long dAudit = countAudit(dSpace);
        for (Long actor : List.of(f.pAdminId, f.pDeputyId, f.pMemberId)) {
            assertRejected(listTournament(f.t1), actor, 403, "TOUR_032");
            assertRejected(listDivision(f.t1, f.d1), actor, 403, "TOUR_032");
            assertRejected(patchTournament(f.t1, tSpace, true), actor, 403, "TOUR_032");
            assertRejected(patchDivision(f.t1, f.d1, dSpace, true), actor, 403, "TOUR_032");
        }
        assertUnchanged(tSpace, false, tAudit);
        assertUnchanged(dSpace, false, dAudit);
    }

    @Test
    @DisplayName("AC-11/AC-17: 公開スペースを持つ大会（T2=BULLETIN公開・T3=CHAT公開）の部外者は知り得るため 403 TOUR_032、設定は不変")
    void ac11_公開スペースの部外者は403_TOUR_032() throws Exception {
        UUID t2Chat = f.spaceId(ContactSpaceScopeType.TOURNAMENT, f.t2, ContactSpaceKind.CHAT);
        UUID t3Bulletin = f.spaceId(ContactSpaceScopeType.TOURNAMENT, f.t3, ContactSpaceKind.BULLETIN);
        long a2 = countAudit(t2Chat);
        long a3 = countAudit(t3Bulletin);

        assertRejected(listTournament(f.t2), f.outsiderId, 403, "TOUR_032");
        assertRejected(patchTournament(f.t2, t2Chat, true), f.outsiderId, 403, "TOUR_032");
        assertRejected(listTournament(f.t3), f.outsiderId, 403, "TOUR_032");
        assertRejected(patchTournament(f.t3, t3Bulletin, true), f.outsiderId, 403, "TOUR_032");

        assertUnchanged(t2Chat, false, a2);
        assertUnchanged(t3Bulletin, false, a3);
    }

    // ========================================================================
    // AC-12: 正常系回帰
    // ========================================================================

    @Test
    @DisplayName("AC-12: 主催組織ADMIN・SYSTEM_ADMIN は大会／ディビジョンの contact-spaces 一覧を 200 で取得できる")
    void ac12_主催ADMINとSYSTEM_ADMINは一覧200() throws Exception {
        for (Long actor : List.of(f.orgAdminId, f.sysAdminId)) {
            MvcResult t = http(listTournament(f.t1), actor);
            assertThat(t.getResponse().getStatus()).as(body(t)).isEqualTo(200);
            assertThat(json(t).path("data").size()).as(body(t)).isEqualTo(2);

            MvcResult d = http(listDivision(f.t1, f.d1), actor);
            assertThat(d.getResponse().getStatus()).as(body(d)).isEqualTo(200);
            assertThat(json(d).path("data").size()).as(body(d)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("AC-12: 主催組織ADMIN の大会 visibility PATCH は 200 で is_public が切り替わり、監査ログが 1 件残る")
    void ac12_主催ADMINの大会PATCHは200() throws Exception {
        UUID space = f.spaceId(ContactSpaceScopeType.TOURNAMENT, f.t1, ContactSpaceKind.BULLETIN);
        long before = countAudit(space);

        MvcResult r = http(patchTournament(f.t1, space, true), f.orgAdminId);

        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        assertThat(f.isPublic(space)).isTrue();
        assertThat(awaitAuditCount(space, before + 1)).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("AC-12: SYSTEM_ADMIN の正しい大会配下でのディビジョン visibility PATCH は 200 で is_public が切り替わる")
    void ac12_SYSTEM_ADMINのディビジョンPATCHは200() throws Exception {
        UUID space = f.spaceId(ContactSpaceScopeType.TOURNAMENT_DIVISION, f.d1, ContactSpaceKind.CHAT);

        MvcResult r = http(patchDivision(f.t1, f.d1, space, true), f.sysAdminId);

        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        assertThat(f.isPublic(space)).isTrue();
    }

    // ========================================================================
    // CMP-261118-0900 AC-P22: 参加者向け一覧の新設後も、主催者向け一覧の認可は緩めない（回帰の柵）
    // ========================================================================

    @Test
    @DisplayName("P22: 主催者向け一覧 GET /tournaments/{t}/contact-spaces は、閲覧できる参加チーム代表に 403 TOUR_032、閲覧できない部外者に不在と同一の 404 TOUR_029、主催組織 ADMIN に 200（大会・ディビジョンとも）")
    void P22_主催者向け一覧の認可は不変() throws Exception {
        assertRejected(listTournament(f.t1), f.pAdminId, 403, "TOUR_032");
        assertRejected(listDivision(f.t1, f.d1), f.pAdminId, 403, "TOUR_032");
        assertSameAsMissing(listTournament(f.t1), listTournament(ABSENT_ID), f.outsiderId, "TOUR_029");
        assertSameAsMissing(listDivision(f.t1, f.d1), listDivision(f.t1, ABSENT_ID), f.outsiderId, "TOUR_029");

        MvcResult t = http(listTournament(f.t1), f.orgAdminId);
        assertThat(t.getResponse().getStatus()).as(body(t)).isEqualTo(200);
        MvcResult d = http(listDivision(f.t1, f.d1), f.orgAdminId);
        assertThat(d.getResponse().getStatus()).as(body(d)).isEqualTo(200);
    }

    // ========================================================================
    // AC-13: 未認証は実在・不在とも同一 401
    // ========================================================================

    @Test
    @DisplayName("AC-13: 未認証の contact-spaces 一覧・visibility PATCH は実在・不在とも同一の 401")
    void ac13_未認証は実在不在とも同一の401() throws Exception {
        UUID tSpace = f.spaceId(ContactSpaceScopeType.TOURNAMENT, f.t1, ContactSpaceKind.BULLETIN);
        UUID dSpace = f.spaceId(ContactSpaceScopeType.TOURNAMENT_DIVISION, f.d1, ContactSpaceKind.BULLETIN);
        assertSameUnauthenticated(listTournament(f.t1), listTournament(ABSENT_ID));
        assertSameUnauthenticated(listDivision(f.t1, f.d1), listDivision(f.t1, ABSENT_ID));
        assertSameUnauthenticated(patchTournament(f.t1, tSpace, true), patchTournament(ABSENT_ID, tSpace, true));
        assertSameUnauthenticated(patchDivision(f.t1, f.d1, dSpace, true), patchDivision(f.t1, ABSENT_ID, dSpace, true));
        assertThat(f.isPublic(tSpace)).isFalse();
        assertThat(f.isPublic(dSpace)).isFalse();
    }

    // ========================================================================
    // AC-18: パスの大会 ID とディビジョンの帰属が食い違えば主催ADMINでも不在と同一の 404
    // ========================================================================

    @Test
    @DisplayName("AC-18: 主催ADMINが別大会T5配下のパスで D1 の contact-spaces 一覧を叩くと、不在ディビジョンと同一の 404 TOUR_029")
    void ac18_帰属違いのディビジョン一覧は不在と同一() throws Exception {
        for (Long actor : List.of(f.orgAdminId, f.sysAdminId)) {
            assertSameAsMissing(listDivision(f.t5, f.d1), listDivision(f.t5, ABSENT_ID), actor, "TOUR_029");
            assertSameAsMissing(listDivision(f.tf, f.d1), listDivision(f.tf, ABSENT_ID), actor, "TOUR_029");
            assertSameAsMissing(listDivision(ABSENT_ID - 1, f.d1), listDivision(ABSENT_ID - 1, ABSENT_ID), actor,
                    "TOUR_029");
        }
    }

    @Test
    @DisplayName("AC-18: 主催ADMINが別大会T5配下のパスで D1 のスペースを PATCH すると不在と同一の 404 TOUR_029、is_public・監査ログは不変")
    void ac18_帰属違いのディビジョンPATCHは不在と同一で不変() throws Exception {
        UUID space = f.spaceId(ContactSpaceScopeType.TOURNAMENT_DIVISION, f.d1, ContactSpaceKind.BULLETIN);
        long auditBefore = countAudit(space);
        for (Long actor : List.of(f.orgAdminId, f.sysAdminId)) {
            assertSameAsMissing(patchDivision(f.t5, f.d1, space, true), patchDivision(f.t5, ABSENT_ID, space, true),
                    actor, "TOUR_029");
            assertSameAsMissing(patchDivision(f.tf, f.d1, space, true), patchDivision(f.tf, ABSENT_ID, space, true),
                    actor, "TOUR_029");
        }
        assertUnchanged(space, false, auditBefore);
    }

    // ========================================================================
    // 補助
    // ========================================================================

    private MockHttpServletRequestBuilder listTournament(Long tid) {
        return get("/api/v1/tournaments/{t}/contact-spaces", tid);
    }

    private MockHttpServletRequestBuilder listDivision(Long tid, Long did) {
        return get("/api/v1/tournaments/{t}/divisions/{d}/contact-spaces", tid, did);
    }

    private MockHttpServletRequestBuilder patchTournament(Long tid, UUID spaceId, boolean isPublic) {
        return patch("/api/v1/tournaments/{t}/contact-spaces/{s}/visibility", tid, spaceId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"isPublic\":" + isPublic + "}");
    }

    private MockHttpServletRequestBuilder patchDivision(Long tid, Long did, UUID spaceId, boolean isPublic) {
        return patch("/api/v1/tournaments/{t}/divisions/{d}/contact-spaces/{s}/visibility", tid, did, spaceId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"isPublic\":" + isPublic + "}");
    }

    /**
     * 秘匿対象への応答が、不在 ID への応答と status・error.code・error.message・本文全体まで一致し、
     * 期待の絶対値（404 + expectedCode）であることを検査する（{@code FriendContentForwardExistenceOracleContractIT} の型）。
     */
    private void assertSameAsMissing(MockHttpServletRequestBuilder existing, MockHttpServletRequestBuilder missing,
                                     Long actor, String expectedCode) throws Exception {
        MvcResult m = http(missing, actor);
        MvcResult e = http(existing, actor);
        String label = "actor=" + actor + " existing=" + body(e) + " missing=" + body(m);
        assertThat(m.getResponse().getStatus()).as("不在側の絶対値 " + label).isEqualTo(404);
        assertThat(json(m).path("error").path("code").asText()).as("不在側の絶対値 " + label).isEqualTo(expectedCode);
        assertThat(e.getResponse().getStatus()).as("status が不在と一致 " + label).isEqualTo(m.getResponse().getStatus());
        assertThat(json(e).path("error").path("code").asText()).as("error.code が不在と一致 " + label)
                .isEqualTo(expectedCode);
        assertThat(json(e).path("error").path("message").asText()).as("error.message が不在と一致 " + label)
                .isEqualTo(json(m).path("error").path("message").asText());
        assertThat(json(e)).as("本文全体が不在と一致 " + label).isEqualTo(json(m));
    }

    private void assertRejected(MockHttpServletRequestBuilder request, Long actor, int status, String code)
            throws Exception {
        MvcResult r = http(request, actor);
        assertThat(r.getResponse().getStatus()).as("actor=" + actor + " " + body(r)).isEqualTo(status);
        assertThat(json(r).path("error").path("code").asText()).as("actor=" + actor + " " + body(r)).isEqualTo(code);
    }

    private void assertSameUnauthenticated(MockHttpServletRequestBuilder existing, MockHttpServletRequestBuilder missing)
            throws Exception {
        MvcResult m = http(missing, null);
        MvcResult e = http(existing, null);
        String label = "existing=" + body(e) + " missing=" + body(m);
        assertThat(m.getResponse().getStatus()).as(label).isEqualTo(401);
        assertThat(e.getResponse().getStatus()).as(label).isEqualTo(401);
        assertThat(json(e).path("error").path("code").asText()).as(label)
                .isEqualTo(json(m).path("error").path("code").asText());
    }

    private void assertUnchanged(UUID space, boolean expectedPublic, long auditBefore) throws Exception {
        assertThat(f.isPublic(space)).as("is_public は不変").isEqualTo(expectedPublic);
        Thread.sleep(300); // 監査ログは @Async。拒否後に遅れて書かれないことを確かめる。
        assertThat(countAudit(space)).as("監査ログは不変").isEqualTo(auditBefore);
    }

    private long countAudit(UUID space) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE event_type = ? AND metadata LIKE ?",
                Long.class, AUDIT_EVENT, "%" + space + "%");
        return n == null ? 0 : n;
    }

    /** @Async の監査書き込みを最大 5 秒待つ。 */
    private long awaitAuditCount(UUID space, long expected) throws Exception {
        long n = countAudit(space);
        for (int i = 0; i < 50 && n < expected; i++) {
            Thread.sleep(100);
            n = countAudit(space);
        }
        return n;
    }

    private MvcResult http(MockHttpServletRequestBuilder request, Long actor) throws Exception {
        if (actor != null) {
            request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("MEMBER")));
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        String s = body(result);
        return s.isBlank() ? mapper.createObjectNode() : mapper.readTree(s);
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
