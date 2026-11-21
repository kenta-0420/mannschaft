package com.mannschaft.app.tournament;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.tournament.entity.TournamentContactSpaceEntity;
import com.mannschaft.app.tournament.repository.TournamentContactSpaceRepository;
import com.mannschaft.app.tournament.service.ContactAccessLevel;
import com.mannschaft.app.tournament.service.TournamentContactSpaceAccessGate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.mannschaft.app.tournament.ParticipantContactSpaceFixture.ABSENT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261118-0900 参加者向け連絡スペース一覧 API の受け入れテスト（試練・red）。
 *
 * <p>対象 EP: {@code GET /api/v1/teams/{teamSlug}/tournaments/{tournamentId}/contact-spaces}
 * （{@code ParticipantContactSpaceController} → {@code ParticipantContactSpaceFacade}）。
 * 正本は設計書 {@code docs/features/F08.7.1_tournament_extensions/01_communication.md} §10.3・§10.4
 * （AC-P01〜P19・P21・P33〜P35）と、戦役台帳の追加 AC（X2・X9・X10）。</p>
 *
 * <p><b>red の根拠</b>: 骨格 828a65bf7d の {@code ParticipantContactSpaceFacade#listForParticipant} と
 * {@code TournamentContactSpaceAccessGate#resolveLevelsForSpaces} は {@code UnsupportedOperationException}
 * を投げるため、200 を期待する AC は 500 で、404 TOUR_029 を期待する AC も 500 で落ちる。
 * AC-P06 の投稿 API 側（既存）と AC-P10（未認証 401）は実装前から成立する。</p>
 *
 * <p>実 JWT・実 Security フィルタ・実 MySQL（TEST_CONVENTION §3.1.2）。fixture は別 TX で commit し、
 * クラス単位で 1 回だけ作って所有行だけ後始末する（テストは fixture を書き換えない。投稿 API が作る子行は
 * {@link ParticipantContactSpaceFixture#cleanup()} が消す）。</p>
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261118-0900 参加者向け連絡スペース一覧 API（実JWT/filter・試練）")
class ParticipantContactSpaceListContractIT extends AbstractMySqlIntegrationTest {

    private static final String TOUR_029 = "TOUR_029";
    private static final String TOUR_031 = "TOUR_031";
    private static final ContactSpaceScopeType T = ContactSpaceScopeType.TOURNAMENT;
    private static final ContactSpaceScopeType D = ContactSpaceScopeType.TOURNAMENT_DIVISION;
    private static final ContactSpaceKind BULLETIN = ContactSpaceKind.BULLETIN;
    private static final ContactSpaceKind CHAT = ContactSpaceKind.CHAT;
    /** 不在応答の比較から除くフィールド（AC-P11）。 */
    private static final Set<String> VOLATILE_FIELDS = Set.of("timestamp", "path", "traceId", "requestId");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private ChatChannelRepository channels;
    @Autowired private TournamentContactSpaceRepository contactSpaceRepository;
    @Autowired private TournamentContactSpaceAccessGate gate;
    @PersistenceContext private EntityManager em;

    private ParticipantContactSpaceFixture f;

    @BeforeAll
    void fixtureを別TXでcommitする() {
        f = new ParticipantContactSpaceFixture(em, jdbc, txManager, users, organizations, teams, channels, "CMP0900");
        f.setUp();
    }

    @AfterAll
    void 所有行だけ片付ける() throws Exception {
        Thread.sleep(300); // 投稿 API の @Async 監査・通知を待ってから消す
        f.cleanup();
    }

    @AfterEach
    void 認証文脈を消す() {
        SecurityContextHolder.clearContext();
    }

    // ========================================================================
    // 正常系・権限別（AC-P01〜P05・X2・X9）
    // ========================================================================

    @Test
    @DisplayName("P01/P02: A の代表 a1・副代表 a2 は 200。divisions は D1 のみ、spaces は大会全体と D1 の掲示板・チャット計4件ですべて POST、D2・D3 は含まない")
    void P01_P02_代表と副代表は4件すべてPOST() throws Exception {
        for (Long actor : List.of(f.a1, f.a2)) {
            JsonNode data = okData(list(f.slugA, f.t), actor);
            assertThat(divisionIds(data)).as("actor=%s", actor).containsExactly(f.d1);
            assertThat(levels(data)).as("actor=%s", actor).isEqualTo(expect(
                    f.spaceId(T, f.t, BULLETIN), ContactAccessLevel.POST,
                    f.spaceId(T, f.t, CHAT), ContactAccessLevel.POST,
                    f.spaceId(D, f.d1, BULLETIN), ContactAccessLevel.POST,
                    f.spaceId(D, f.d1, CHAT), ContactAccessLevel.POST));
            assertThat(scopeIds(data)).as("他チームの部門は含まない").doesNotContain(f.d2, f.d3);
        }
    }

    @Test
    @DisplayName("P03/P04: A の一般 a3・SUPPORTER a4 は 200 で同じ4件、すべて VIEW")
    void P03_P04_一般とサポーターは4件すべてVIEW() throws Exception {
        for (Long actor : List.of(f.a3, f.a4)) {
            JsonNode data = okData(list(f.slugA, f.t), actor);
            assertThat(divisionIds(data)).as("actor=%s", actor).containsExactly(f.d1);
            assertThat(levels(data)).as("actor=%s", actor).isEqualTo(expect(
                    f.spaceId(T, f.t, BULLETIN), ContactAccessLevel.VIEW,
                    f.spaceId(T, f.t, CHAT), ContactAccessLevel.VIEW,
                    f.spaceId(D, f.d1, BULLETIN), ContactAccessLevel.VIEW,
                    f.spaceId(D, f.d1, CHAT), ContactAccessLevel.VIEW));
        }
    }

    @Test
    @DisplayName("P05: tournamentId・tournamentName（非 null）、部門の name、divisionName は大会全体で null・部門で部門名、refId はチャットでチャンネル ID")
    void P05_応答の項目() throws Exception {
        JsonNode data = okData(list(f.slugA, f.t), f.a3);

        assertThat(data.path("tournamentId").asLong()).isEqualTo(f.t);
        assertThat(data.path("tournamentName").isTextual()).as(data.toString()).isTrue();
        assertThat(data.path("tournamentName").asText()).isEqualTo(f.tName);
        assertThat(data.path("divisions")).hasSize(1);
        assertThat(data.path("divisions").get(0).path("divisionId").asLong()).isEqualTo(f.d1);
        assertThat(data.path("divisions").get(0).path("name").asText()).isEqualTo(f.d1Name);

        for (JsonNode s : data.path("spaces")) {
            String scopeType = s.path("scopeType").asText();
            String label = s.toString();
            assertThat(s.path("id").asText()).as(label).isNotBlank();
            assertThat(s.path("isPublic").isBoolean()).as("isPublic は boolean " + label).isTrue();
            assertThat(s.path("isPublic").asBoolean()).as(label).isFalse();
            if (T.name().equals(scopeType)) {
                assertThat(s.has("divisionName")).as("divisionName は null で出す " + label).isTrue();
                assertThat(s.path("divisionName").isNull()).as("大会全体は divisionName=null " + label).isTrue();
                assertThat(s.path("scopeId").asLong()).as(label).isEqualTo(f.t);
            } else {
                assertThat(scopeType).as(label).isEqualTo(D.name());
                assertThat(s.path("divisionName").asText()).as(label).isEqualTo(f.d1Name);
                assertThat(s.path("scopeId").asLong()).as(label).isEqualTo(f.d1);
            }
            if (CHAT.name().equals(s.path("spaceKind").asText())) {
                ContactSpaceScopeType st = ContactSpaceScopeType.valueOf(scopeType);
                assertThat(s.path("refId").asLong()).as("CHAT の refId はチャンネル ID " + label)
                        .isEqualTo(f.channelId(st, s.path("scopeId").asLong()));
            }
        }
    }

    @Test
    @DisplayName("X2: spaces の並びは 大会全体（BULLETIN→CHAT）→ 部門 ID 昇順（各 BULLETIN→CHAT）。挿入順に依らない")
    void X2_並び順() throws Exception {
        JsonNode one = okData(list(f.slugA, f.t), f.a1);
        assertThat(order(one)).containsExactly(
                f.spaceId(T, f.t, BULLETIN), f.spaceId(T, f.t, CHAT),
                f.spaceId(D, f.d1, BULLETIN), f.spaceId(D, f.d1, CHAT));

        JsonNode two = okData(list(f.slugA, f.tTwo), f.a1);
        assertThat(f.dTwoFirst).as("前提: 先に作った部門の ID が小さい").isLessThan(f.dTwoSecond);
        assertThat(order(two)).containsExactly(
                f.spaceId(T, f.tTwo, BULLETIN), f.spaceId(T, f.tTwo, CHAT),
                f.spaceId(D, f.dTwoFirst, BULLETIN), f.spaceId(D, f.dTwoFirst, CHAT),
                f.spaceId(D, f.dTwoSecond, BULLETIN), f.spaceId(D, f.dTwoSecond, CHAT));
        assertThat(divisionIds(two)).containsExactly(f.dTwoFirst, f.dTwoSecond);
    }

    @Test
    @DisplayName("X9: 別組織 F の ADMIN が A の一般会員を兼ねても、O 主催の大会では 4 件すべて VIEW")
    void X9_別組織ADMINの兼任はVIEW() throws Exception {
        JsonNode data = okData(list(f.slugA, f.t), f.fAdminA);
        assertThat(levels(data)).isEqualTo(expect(
                f.spaceId(T, f.t, BULLETIN), ContactAccessLevel.VIEW,
                f.spaceId(T, f.t, CHAT), ContactAccessLevel.VIEW,
                f.spaceId(D, f.d1, BULLETIN), ContactAccessLevel.VIEW,
                f.spaceId(D, f.d1, CHAT), ContactAccessLevel.VIEW));
    }

    // ========================================================================
    // AC-P06: accessLevel と投稿 API の実判定の一致
    // ========================================================================

    @Test
    @DisplayName("P06: accessLevel は同一ユーザー・同一スペースの投稿 API（スレッド作成・チャット送信）の成否と一致する（POST↔2xx、VIEW↔403 TOUR_031）")
    void P06_accessLevelと投稿APIの成否が一致() throws Exception {
        List<Long> actors = List.of(f.a1, f.a2, f.a3, f.a4, f.oMemberA, f.fAdminA);
        // 先に投稿 API の実判定を採る（一覧 API が未実装でも投稿側の前提が壊れていないことを確かめる）。
        Map<Long, Map<UUID, MvcResult>> posted = new LinkedHashMap<>();
        for (Long actor : actors) {
            Map<UUID, MvcResult> byspace = new LinkedHashMap<>();
            byspace.put(f.spaceId(T, f.t, BULLETIN), http(createThread(T, f.t), actor));
            byspace.put(f.spaceId(T, f.t, CHAT), http(sendChat(f.channelId(T, f.t)), actor));
            byspace.put(f.spaceId(D, f.d1, BULLETIN), http(createThread(D, f.d1), actor));
            byspace.put(f.spaceId(D, f.d1, CHAT), http(sendChat(f.channelId(D, f.d1)), actor));
            posted.put(actor, byspace);
        }
        // 前提（既存の投稿 API の契約）: 結果は 2xx か 403 TOUR_031 のどちらか。代表 a1 は全 2xx、一般 a3 は全 403。
        for (Map.Entry<Long, Map<UUID, MvcResult>> e : posted.entrySet()) {
            for (MvcResult r : e.getValue().values()) {
                int status = r.getResponse().getStatus();
                assertThat(status >= 200 && status < 300 || (status == 403 && TOUR_031.equals(code(r))))
                        .as("前提: 投稿 API は 2xx か 403 TOUR_031 actor=%s %s", e.getKey(), body(r)).isTrue();
            }
        }
        for (MvcResult r : posted.get(f.a1).values()) {
            assertThat(r.getResponse().getStatus()).as("前提: a1 は投稿できる " + body(r)).isBetween(200, 299);
        }
        for (MvcResult r : posted.get(f.a3).values()) {
            assertThat(r.getResponse().getStatus()).as("前提: a3 は投稿できない " + body(r)).isEqualTo(403);
        }
        boolean sawPost = false;
        boolean sawView = false;
        for (Long actor : actors) {
            Map<UUID, ContactAccessLevel> levels = levels(okData(list(f.slugA, f.t), actor));
            assertThat(levels.keySet()).as("actor=%s", actor).isEqualTo(posted.get(actor).keySet());
            for (Map.Entry<UUID, MvcResult> e : posted.get(actor).entrySet()) {
                MvcResult r = e.getValue();
                int status = r.getResponse().getStatus();
                String label = "actor=" + actor + " space=" + e.getKey() + " post=" + body(r);
                if (levels.get(e.getKey()) == ContactAccessLevel.POST) {
                    sawPost = true;
                    assertThat(status).as("POST なら投稿 API は 2xx " + label).isBetween(200, 299);
                } else {
                    sawView = true;
                    assertThat(status).as("VIEW なら投稿 API は 403 " + label).isEqualTo(403);
                    assertThat(code(r)).as(label).isEqualTo(TOUR_031);
                }
            }
        }
        assertThat(sawPost).as("前提: POST の利用者を含む").isTrue();
        assertThat(sawView).as("前提: VIEW の利用者を含む").isTrue();
    }

    // ========================================================================
    // 異常系・存在オラクル（AC-P07〜P13・P21・P35）
    // ========================================================================

    @Test
    @DisplayName("P07: T に不参加のチーム C の会員 c1 が C の slug で呼ぶと、不在大会と同一の 404 TOUR_029")
    void P07_不参加チームは不在と同一() throws Exception {
        assertSameAsMissing(list(f.slugC, f.t), f.c1, "P07 c1/C");
    }

    @Test
    @DisplayName("P08: a1 が所属でないチーム B の slug で呼ぶと、不在大会と同一の 404 TOUR_029")
    void P08_非所属チームのslugは不在と同一() throws Exception {
        assertSameAsMissing(list(f.slugB, f.t), f.a1, "P08 a1/B");
    }

    @Test
    @DisplayName("P09: 部外者 z が A の slug で呼ぶと、不在大会と同一の 404 TOUR_029")
    void P09_部外者は不在と同一() throws Exception {
        assertSameAsMissing(list(f.slugA, f.t), f.z, "P09 z/A");
    }

    @Test
    @DisplayName("P10: 未ログインは実在・不在とも同一の 401")
    void P10_未認証は401() throws Exception {
        MvcResult existing = http(list(f.slugA, f.t), null);
        MvcResult missing = http(list("cmp0900-absent-slug", ABSENT_ID), null);
        String label = "existing=" + body(existing) + " missing=" + body(missing);
        assertThat(existing.getResponse().getStatus()).as(label).isEqualTo(401);
        assertThat(missing.getResponse().getStatus()).as(label).isEqualTo(401);
        assertThat(code(existing)).as(label).isEqualTo(code(missing));
    }

    @Test
    @DisplayName("P11: 不在大会・不在 slug・アーカイブ済み・PROVISIONED・数値だけの slug（実在チーム ID と同じ数字／該当なしの数字）・退会済みメンバー・論理削除済み大会は、P07〜P09 と status・code・message・本文構造まで同一")
    void P11_拒否はすべて同一応答() throws Exception {
        Map<String, Req> inputs = new LinkedHashMap<>();
        inputs.put("P07 不参加チーム c1/C", new Req(list(f.slugC, f.t), f.c1));
        inputs.put("P08 非所属 a1/B", new Req(list(f.slugB, f.t), f.a1));
        inputs.put("P09 部外者 z/A", new Req(list(f.slugA, f.t), f.z));
        inputs.put("不在大会 a1/A", new Req(list(f.slugA, ABSENT_ID), f.a1));
        inputs.put("不在 slug a1", new Req(list("cmp0900-absent-" + UUID.randomUUID().toString().substring(0, 8), f.t), f.a1));
        inputs.put("アーカイブ済みチーム", new Req(list(f.slugArchived, f.t), f.archMember));
        inputs.put("PROVISIONED チーム", new Req(list(f.slugProvisioned, f.t), f.provMember));
        inputs.put("数値 slug=A の内部 ID", new Req(list(String.valueOf(f.teamA), f.t), f.a1));
        inputs.put("数値 slug=該当なし", new Req(list(String.valueOf(ABSENT_ID - 1), f.t), f.a1));
        inputs.put("退会済みメンバー", new Req(list(f.slugA, f.t), f.aLeft));
        inputs.put("論理削除済み大会", new Req(list(f.slugA, f.tDeleted), f.a1));

        MvcResult reference = http(list(f.slugA, ABSENT_ID), f.a1);
        assertThat(reference.getResponse().getStatus()).as("基準（不在大会）の絶対値 " + body(reference)).isEqualTo(404);
        assertThat(code(reference)).as("基準（不在大会）の絶対値 " + body(reference)).isEqualTo(TOUR_029);
        for (Map.Entry<String, Req> e : inputs.entrySet()) {
            MvcResult r = http(e.getValue().request(), e.getValue().actor());
            assertSameResponse(r, reference, e.getKey());
        }
    }

    @Test
    @DisplayName("P12: 論理削除済みの大会は、参加チームの代表が呼んでも 404 TOUR_029（不在と同一）")
    void P12_論理削除済み大会は404() throws Exception {
        assertSameAsMissing(list(f.slugA, f.tDeleted), f.a1, "P12 a1/tDeleted");
        assertSameAsMissing(list(f.slugA, f.tDeleted), f.a3, "P12 a3/tDeleted");
    }

    @Test
    @DisplayName("P21: 公開スペースのある大会でも、A の非会員 z は本 API では 404（会員 a3 には公開スペースが VIEW で見える）")
    void P21_公開スペースでも部外者は404() throws Exception {
        assertSameAsMissing(list(f.slugA, f.tPub), f.z, "P21 z/tPub");

        JsonNode data = okData(list(f.slugA, f.tPub), f.a3);
        Map<UUID, ContactAccessLevel> levels = levels(data);
        assertThat(levels).containsEntry(f.spaceId(T, f.tPub, BULLETIN), ContactAccessLevel.VIEW);
        for (JsonNode s : data.path("spaces")) {
            boolean expectedPublic = s.path("id").asText().equals(f.spaceId(T, f.tPub, BULLETIN).toString());
            assertThat(s.path("isPublic").asBoolean()).as(s.toString()).isEqualTo(expectedPublic);
        }
    }

    @Test
    @DisplayName("P35: アーカイブ済みチームの slug は、その会員（代表）が呼んでも 404 TOUR_029")
    void P35_アーカイブ済みチームは404() throws Exception {
        assertSameAsMissing(list(f.slugArchived, f.t), f.archMember, "P35 archMember/Arch");
    }

    // ========================================================================
    // 参加状態・部門・件数の境界（AC-P14〜P19・X10）
    // ========================================================================

    @Test
    @DisplayName("P14: 参加が WITHDRAWN・DISQUALIFIED なら a1・a3 とも 404 TOUR_029、REGISTERED なら 200（4件・a1 は全 POST）")
    void P14_参加状態の境界() throws Exception {
        for (Long actor : List.of(f.a1, f.a3)) {
            assertSameAsMissing(list(f.slugA, f.tWithdrawn), actor, "P14 WITHDRAWN actor=" + actor);
            assertSameAsMissing(list(f.slugA, f.tDisq), actor, "P14 DISQUALIFIED actor=" + actor);
        }
        JsonNode data = okData(list(f.slugA, f.tReg), f.a1);
        assertThat(divisionIds(data)).containsExactly(f.dReg);
        assertThat(levels(data)).isEqualTo(expect(
                f.spaceId(T, f.tReg, BULLETIN), ContactAccessLevel.POST,
                f.spaceId(T, f.tReg, CHAT), ContactAccessLevel.POST,
                f.spaceId(D, f.dReg, BULLETIN), ContactAccessLevel.POST,
                f.spaceId(D, f.dReg, CHAT), ContactAccessLevel.POST));
    }

    @Test
    @DisplayName("P15: D1 は ACTIVE・D2 は WITHDRAWN のとき、divisions は ACTIVE 側のみで WITHDRAWN 側のスペースは含まない")
    void P15_辞退した部門は含まない() throws Exception {
        JsonNode data = okData(list(f.slugA, f.tMix), f.a1);
        assertThat(divisionIds(data)).containsExactly(f.dMixActive);
        assertThat(scopeIds(data)).doesNotContain(f.dMixWithdrawn);
        assertThat(levels(data).keySet()).containsExactlyInAnyOrder(
                f.spaceId(T, f.tMix, BULLETIN), f.spaceId(T, f.tMix, CHAT),
                f.spaceId(D, f.dMixActive, BULLETIN), f.spaceId(D, f.dMixActive, CHAT));
    }

    @Test
    @DisplayName("P16: 2 部門とも ACTIVE なら divisions は 2 件、spaces は 6 件")
    void P16_2部門なら6件() throws Exception {
        JsonNode data = okData(list(f.slugA, f.tTwo), f.a3);
        assertThat(divisionIds(data)).containsExactly(f.dTwoFirst, f.dTwoSecond);
        assertThat(data.path("spaces")).hasSize(6);
    }

    @Test
    @DisplayName("P17: 出ている部門が 0 件（全部門が参加外）は 404、他チームの部門のスペースは A の誰の応答にも含まれない")
    void P17_出場部門0件は404_他チーム部門は含まない() throws Exception {
        assertSameAsMissing(list(f.slugA, f.tWithdrawn), f.a2, "P17 a2/tWithdrawn");
        for (Long actor : List.of(f.a1, f.a2, f.a3, f.a4)) {
            assertThat(scopeIds(okData(list(f.slugA, f.t), actor))).as("actor=%s", actor)
                    .doesNotContain(f.d2, f.d3);
        }
    }

    @Test
    @DisplayName("P18: スペースが 0 件なら 200 + spaces=[]、divisions は存在する")
    void P18_スペース0件は200空配列() throws Exception {
        JsonNode data = okData(list(f.slugA, f.tEmpty), f.a1);
        assertThat(data.path("spaces").isArray()).as(data.toString()).isTrue();
        assertThat(data.path("spaces")).isEmpty();
        assertThat(divisionIds(data)).containsExactly(f.dEmpty);
    }

    @Test
    @DisplayName("P19: x は A 文脈で D1 の 4 件のみ、B 文脈で D2 の 4 件のみ（和集合にならない）。A 代表かつ B 一般の xa は B 文脈で大会全体 2 件 POST・D2 の 2 件 VIEW・D1 不在")
    void P19_チーム文脈で部門が決まる() throws Exception {
        JsonNode inA = okData(list(f.slugA, f.t), f.x);
        assertThat(divisionIds(inA)).containsExactly(f.d1);
        assertThat(levels(inA)).isEqualTo(expect(
                f.spaceId(T, f.t, BULLETIN), ContactAccessLevel.VIEW,
                f.spaceId(T, f.t, CHAT), ContactAccessLevel.VIEW,
                f.spaceId(D, f.d1, BULLETIN), ContactAccessLevel.VIEW,
                f.spaceId(D, f.d1, CHAT), ContactAccessLevel.VIEW));

        JsonNode inB = okData(list(f.slugB, f.t), f.x);
        assertThat(divisionIds(inB)).containsExactly(f.d2);
        assertThat(levels(inB)).isEqualTo(expect(
                f.spaceId(T, f.t, BULLETIN), ContactAccessLevel.VIEW,
                f.spaceId(T, f.t, CHAT), ContactAccessLevel.VIEW,
                f.spaceId(D, f.d2, BULLETIN), ContactAccessLevel.VIEW,
                f.spaceId(D, f.d2, CHAT), ContactAccessLevel.VIEW));

        JsonNode xaInB = okData(list(f.slugB, f.t), f.xa);
        assertThat(divisionIds(xaInB)).containsExactly(f.d2);
        assertThat(levels(xaInB)).isEqualTo(expect(
                f.spaceId(T, f.t, BULLETIN), ContactAccessLevel.POST,
                f.spaceId(T, f.t, CHAT), ContactAccessLevel.POST,
                f.spaceId(D, f.d2, BULLETIN), ContactAccessLevel.VIEW,
                f.spaceId(D, f.d2, CHAT), ContactAccessLevel.VIEW));
    }

    @Test
    @DisplayName("X10: 一部のスペースだけ論理削除されていれば、その行だけ除外する（残り 3 件）")
    void X10_論理削除スペースだけ除外() throws Exception {
        JsonNode data = okData(list(f.slugA, f.tPartDel), f.a1);
        assertThat(levels(data).keySet()).containsExactlyInAnyOrder(
                f.spaceId(T, f.tPartDel, CHAT),
                f.spaceId(D, f.dPartDel, BULLETIN), f.spaceId(D, f.dPartDel, CHAT));
    }

    // ========================================================================
    // 主催者の兼任（AC-P33）
    // ========================================================================

    @Test
    @DisplayName("P33: 主催組織 ADMIN が A の一般会員を兼ねれば全 POST。兼ねない主催 ADMIN o1・SYSTEM_ADMIN は 404 TOUR_029")
    void P33_主催者の兼任() throws Exception {
        JsonNode data = okData(list(f.slugA, f.t), f.oMemberA);
        assertThat(levels(data)).isEqualTo(expect(
                f.spaceId(T, f.t, BULLETIN), ContactAccessLevel.POST,
                f.spaceId(T, f.t, CHAT), ContactAccessLevel.POST,
                f.spaceId(D, f.d1, BULLETIN), ContactAccessLevel.POST,
                f.spaceId(D, f.d1, CHAT), ContactAccessLevel.POST));

        assertSameAsMissing(list(f.slugA, f.t), f.o1, "P33 o1/A");
        assertSameAsMissing(list(f.slugA, f.t), f.sysAdmin, "P33 sysAdmin/A");
    }

    // ========================================================================
    // Gate 一括解決の不変条件（AC-P34・実 DB）
    // ========================================================================

    @Test
    @DisplayName("P34: 実 DB の全 (scope, kind) で resolveLevelsForSpaces は requireViewResolvingLevel と一致（例外＝マップ不在）。非所属部門 D2 の非公開スペース・部外者 z を含む。全非公開の T では同一スコープの掲示板とチャットの水準が同じ")
    void P34_一括解決は単発判定と一致() {
        List<Long> actors = List.of(f.a1, f.a2, f.a3, f.a4, f.aLeft, f.b1, f.x, f.xa, f.c1, f.z,
                f.o1, f.oMemberA, f.fAdminA, f.sysAdmin, f.archMember);
        Map<Long, List<TournamentContactSpaceEntity>> spacesByTournament = new LinkedHashMap<>();
        spacesByTournament.put(f.t, spacesOf(f.t, List.of(f.d1, f.d2, f.d3)));
        spacesByTournament.put(f.tPub, spacesOf(f.tPub, List.of(f.dPub)));
        assertThat(spacesByTournament.get(f.t)).as("前提: T のスペース 8 件").hasSize(8);
        assertThat(spacesByTournament.get(f.tPub)).as("前提: tPub のスペース 4 件").hasSize(4);

        for (Map.Entry<Long, List<TournamentContactSpaceEntity>> e : spacesByTournament.entrySet()) {
            for (Long actor : actors) {
                Map<UUID, ContactAccessLevel> expected = new HashMap<>();
                for (TournamentContactSpaceEntity s : e.getValue()) {
                    try {
                        expected.put(s.getId(), gate.requireViewResolvingLevel(s.getScopeType(), s.getScopeId(),
                                s.getSpaceKind(), actor, TournamentErrorCode.CONTACT_SPACE_NOT_FOUND));
                    } catch (BusinessException notViewable) {
                        // 既存 Gate で閲覧不可＝一括解決のマップにも不在であるべき
                    }
                }
                Map<UUID, ContactAccessLevel> actual = gate.resolveLevelsForSpaces(actor, e.getKey(), e.getValue());
                assertThat(actual).as("tournament=%s actor=%s", e.getKey(), actor).isEqualTo(expected);

                if (e.getKey().equals(f.t)) {
                    for (Long scopeId : List.of(f.t, f.d1, f.d2, f.d3)) {
                        ContactSpaceScopeType st = scopeId.equals(f.t) ? T : D;
                        assertThat(actual.get(f.spaceId(st, scopeId, BULLETIN)))
                                .as("全非公開なら掲示板とチャットの水準は同じ scope=%s actor=%s", scopeId, actor)
                                .isEqualTo(actual.get(f.spaceId(st, scopeId, CHAT)));
                    }
                }
            }
        }
        // 非所属部門の非公開スペース・大会全体の部外者が「不在」で固定されていること（AC-P34 後段）
        assertThat(gate.resolveLevelsForSpaces(f.a3, f.t, spacesByTournament.get(f.t)))
                .doesNotContainKeys(f.spaceId(D, f.d2, BULLETIN), f.spaceId(D, f.d2, CHAT));
        assertThat(gate.resolveLevelsForSpaces(f.z, f.t, spacesByTournament.get(f.t))).isEmpty();
    }

    // ========================================================================
    // 補助
    // ========================================================================

    private record Req(MockHttpServletRequestBuilder request, Long actor) {
    }

    private MockHttpServletRequestBuilder list(String teamSlug, Long tournamentId) {
        return get("/api/v1/teams/{teamSlug}/tournaments/{tournamentId}/contact-spaces", teamSlug, tournamentId);
    }

    private MockHttpServletRequestBuilder createThread(ContactSpaceScopeType scopeType, Long scopeId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", scopeType.name());
        body.put("scopeId", String.valueOf(scopeId));
        body.put("title", "CMP0900 投稿判定 " + UUID.randomUUID().toString().substring(0, 8));
        body.put("body", "CMP0900 本文");
        return post("/api/v1/bulletin/threads").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body));
    }

    private MockHttpServletRequestBuilder sendChat(Long channelId) throws Exception {
        return post("/api/v1/chat/channels/{id}/messages", channelId).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("body", "CMP0900 投稿判定")));
    }

    private List<TournamentContactSpaceEntity> spacesOf(Long tournamentId, List<Long> divisionIds) {
        List<TournamentContactSpaceEntity> all = new ArrayList<>(
                contactSpaceRepository.findByScopeTypeAndScopeId(T, tournamentId));
        for (Long d : divisionIds) {
            all.addAll(contactSpaceRepository.findByScopeTypeAndScopeId(D, d));
        }
        return all;
    }

    private static Map<UUID, ContactAccessLevel> expect(Object... pairs) {
        Map<UUID, ContactAccessLevel> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((UUID) pairs[i], (ContactAccessLevel) pairs[i + 1]);
        }
        return m;
    }

    /** 200 を検査して data を返す。 */
    private JsonNode okData(MockHttpServletRequestBuilder request, Long actor) throws Exception {
        MvcResult r = http(request, actor);
        assertThat(r.getResponse().getStatus()).as("actor=" + actor + " " + body(r)).isEqualTo(200);
        JsonNode data = json(r).path("data");
        assertThat(data.isObject()).as(body(r)).isTrue();
        return data;
    }

    private Map<UUID, ContactAccessLevel> levels(JsonNode data) {
        Map<UUID, ContactAccessLevel> m = new HashMap<>();
        for (JsonNode s : data.path("spaces")) {
            UUID id = UUID.fromString(s.path("id").asText());
            assertThat(m).as("スペースの重複 " + data).doesNotContainKey(id);
            m.put(id, ContactAccessLevel.valueOf(s.path("accessLevel").asText()));
        }
        return m;
    }

    private List<UUID> order(JsonNode data) {
        List<UUID> ids = new ArrayList<>();
        data.path("spaces").forEach(s -> ids.add(UUID.fromString(s.path("id").asText())));
        return ids;
    }

    private List<Long> divisionIds(JsonNode data) {
        List<Long> ids = new ArrayList<>();
        data.path("divisions").forEach(d -> ids.add(d.path("divisionId").asLong()));
        return ids;
    }

    private List<Long> scopeIds(JsonNode data) {
        List<Long> ids = new ArrayList<>();
        data.path("spaces").forEach(s -> {
            if (D.name().equals(s.path("scopeType").asText())) {
                ids.add(s.path("scopeId").asLong());
            }
        });
        return ids;
    }

    /** 拒否応答が、同じ操作者による不在大会への応答と同一で、404 TOUR_029 であることを検査する。 */
    private void assertSameAsMissing(MockHttpServletRequestBuilder existing, Long actor, String label) throws Exception {
        MvcResult missing = http(list(f.slugA, ABSENT_ID), actor);
        MvcResult e = http(existing, actor);
        assertThat(missing.getResponse().getStatus()).as("不在側の絶対値 " + label + " " + body(missing)).isEqualTo(404);
        assertThat(code(missing)).as("不在側の絶対値 " + label + " " + body(missing)).isEqualTo(TOUR_029);
        assertSameResponse(e, missing, label);
    }

    /** status・error.code・error.message・本文の構造が一致する（timestamp・path・トレース ID は除く）。 */
    private void assertSameResponse(MvcResult actual, MvcResult reference, String label) throws Exception {
        String l = label + " actual=" + body(actual) + " reference=" + body(reference);
        assertThat(actual.getResponse().getStatus()).as("status " + l).isEqualTo(reference.getResponse().getStatus());
        assertThat(code(actual)).as("error.code " + l).isEqualTo(code(reference));
        assertThat(json(actual).path("error").path("message").asText()).as("error.message " + l)
                .isEqualTo(json(reference).path("error").path("message").asText());
        assertThat(normalized(actual)).as("本文 " + l).isEqualTo(normalized(reference));
    }

    private JsonNode normalized(MvcResult r) throws Exception {
        JsonNode node = json(r).deepCopy();
        strip(node);
        return node;
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            obj.remove(VOLATILE_FIELDS);
            for (Iterator<JsonNode> it = obj.elements(); it.hasNext(); ) {
                strip(it.next());
            }
        } else if (node.isArray()) {
            node.forEach(ParticipantContactSpaceListContractIT::strip);
        }
    }

    private MvcResult http(MockHttpServletRequestBuilder request, Long actor) throws Exception {
        if (actor != null) {
            List<String> roles = actor.equals(f.sysAdmin) ? List.of("MEMBER", "SYSTEM_ADMIN") : List.of("MEMBER");
            request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, roles));
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

    private String code(MvcResult result) throws Exception {
        return json(result).path("error").path("code").asText();
    }
}
