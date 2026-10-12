package com.mannschaft.app.cms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.payment.PayerRelationship;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.PaymentMethod;
import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.entity.ContentPaymentGateEntity;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.social.FollowerType;
import com.mannschaft.app.social.entity.FollowEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261007-2052 非所属者のチームブログ一覧（試練）。
 *
 * <p>実 Security フィルタ（{@code @AutoConfigureMockMvc} 既定 = addFilters=true）・実 F00 Resolver・
 * 実 PaymentGateService・実 MySQL を通す。DB・認可・自前 Bean はモックしない（課金判定の例外注入だけは
 * {@link BlogPostListPaywallFailureIT} に分離し、本クラスは基底の共有コンテキストを使う）。</p>
 *
 * <p>正本: 受け入れ条件 AC-1〜AC-16（御裁可 2026-10-07）。HTTP の件数は {@code meta.total} で検証する。
 * 一覧 {@code GET /api/v1/blog/posts?teamId|organizationId=} と詳細
 * {@code GET /api/v1/blog/posts/{slug}?teamId|organizationId=} を対象とする。</p>
 *
 * <p>存在秘匿の期待値（AC-8/AC-9）: 一覧は「不存在 slug の現行応答」＝チーム {@code 404 CMS_024}、
 * 組織 {@code 404 CMS_025} に揃える前提。詳細は殿の裁定により 404 CMS_001 に統一し、
 * 「不存在チームを数値で指定した詳細の応答」との一致を検査する。Codex 検分後の裁定（2026-10-08）で、
 * 詳細の {@code teamId}/{@code organizationId} に slug を渡す経路も同じ応答に揃える
 * （グローバルの slug 変換器に任せると不在 slug だけ COMMON_005 になる存在オラクルだったため）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261007-2052 非所属者のブログ一覧 可視性契約（試練）")
class BlogPostListScopeVisibilityIT extends AbstractMySqlIntegrationTest {

    private static final String LIST = "/api/v1/blog/posts";
    private static final String DETAIL = "/api/v1/blog/posts/{slug}";
    private static final long MISSING_ID = 999_999_999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private String key;
    private Long teamId;
    private String teamSlug;
    private Long orgId;
    private String orgSlug;
    private Long otherTeamId;
    private Long otherOrgId;

    /** チーム・組織の MEMBER で記事の作者。 */
    private Long authorId;
    /** チーム・組織の MEMBER（作者でも管理者でもない）。 */
    private Long memberId;
    /** どこにも所属しない・作者でも フォロワーでも 購入者でも SYSTEM_ADMIN でもないログイン済みユーザー。 */
    private Long outsiderId;
    /** 別チーム・別組織（他テナント）に所属するユーザー。 */
    private Long foreignId;

    @BeforeEach
    void setUp() {
        key = UUID.randomUUID().toString().substring(0, 8);
        teamSlug = "blog-list-team-" + key;
        orgSlug = "blog-list-org-" + key;
        teamId = TeamOrgFixtureHelper.insertTeam(em, "ブログ一覧のチーム", teamSlug);
        orgId = TeamOrgFixtureHelper.insertOrganization(em, "ブログ一覧の組織", orgSlug);
        otherTeamId = TeamOrgFixtureHelper.insertTeam(em, "別テナントのチーム", "blog-list-other-team-" + key);
        otherOrgId = TeamOrgFixtureHelper.insertOrganization(em, "別テナントの組織", "blog-list-other-org-" + key);

        authorId = saveUser("author");
        memberId = saveUser("member");
        outsiderId = saveUser("outsider");
        foreignId = saveUser("foreign");

        for (Long user : List.of(authorId, memberId)) {
            MembershipTestHelper.insertMembership(em, user, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, user, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        }
        MembershipTestHelper.insertMembership(em, foreignId, ScopeType.TEAM, otherTeamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, foreignId, ScopeType.ORGANIZATION, otherOrgId, RoleKind.MEMBER);
        em.flush();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1〜AC-3: 非所属者の一覧（チーム）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("非所属者のチーム一覧")
    class OutsiderTeamList {

        @Test
        @DisplayName("AC-1: 非所属ユーザーは PUBLIC チームの一覧で 200・PUBLISHED×PUBLIC 記事のみを受け取る")
        void ac1_非所属者は公開記事のみ受け取る() throws Exception {
            String first = teamPost(Visibility.PUBLIC, PostStatus.PUBLISHED).getSlug();
            String second = teamPost(Visibility.PUBLIC, PostStatus.PUBLISHED).getSlug();
            teamPost(Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED);
            teamPost(Visibility.PUBLIC, PostStatus.DRAFT);
            em.flush();

            JsonNode body = okList(outsiderId, "teamId", teamId.toString(), null, null);

            assertThat(body.path("meta").path("total").asLong()).isEqualTo(2);
            assertThat(slugs(body)).containsExactlyInAnyOrder(first, second);
            for (JsonNode item : body.path("data")) {
                assertThat(item.path("meta").path("visibility").asText()).isEqualTo("PUBLIC");
                assertThat(item.path("meta").path("status").asText()).isEqualTo("PUBLISHED");
                // stripBody は body を null にするため JSON には "body": null が出うる。欠落または null を許し、
                // 本文の中身（"本文 ..."）が返らないことを検証する。
                assertAbsentOrNull(item.path("content"), "body", "一覧は本文を返さない");
                assertThat(item.toString()).doesNotContain("本文 ");
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Visibility.class, names = {
                "MEMBERS_ONLY", "SUPPORTERS_AND_ABOVE", "MEMBERS_AND_ABOVE", "ADMINS_AND_ABOVE",
                "SCOPE_AFFILIATED", "PRIVATE", "FOLLOWERS_ONLY", "CUSTOM_TEMPLATE", "CUSTOM"})
        @DisplayName("AC-2: 非所属者には PUBLIC 以外の公開範囲の記事が返らず meta.total にも数えない")
        void ac2_非公開範囲は返らず数えない(Visibility visibility) throws Exception {
            String visible = teamPost(Visibility.PUBLIC, PostStatus.PUBLISHED).getSlug();
            BlogPostEntity hidden = teamPost(visibility, PostStatus.PUBLISHED);
            if (visibility == Visibility.CUSTOM) {
                // ゲートなしの CUSTOM は誰でも解錠になる（PaymentGateService: ゲートなし=accessible）。
                // 「購入者でない非所属者」を成立させるため、未払いの課金ゲートを付ける。
                addGate(hidden.getId(), teamId, null, false);
            }
            em.flush();

            JsonNode body = okList(outsiderId, "teamId", teamId.toString(), null, null);

            assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
            assertThat(slugs(body)).containsExactly(visible);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PostStatus.class, names = {
                "DRAFT", "PENDING_REVIEW", "PENDING_SELF_REVIEW", "REJECTED", "ARCHIVED"})
        @DisplayName("AC-3: 非所属者には PUBLISHED 以外の状態の記事が visibility=PUBLIC でも返らず数えない")
        void ac3_非公開状態は返らず数えない(PostStatus status) throws Exception {
            String visible = teamPost(Visibility.PUBLIC, PostStatus.PUBLISHED).getSlug();
            teamPost(Visibility.PUBLIC, status);
            em.flush();

            JsonNode body = okList(outsiderId, "teamId", teamId.toString(), null, null);

            assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
            assertThat(slugs(body)).containsExactly(visible);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-4: 所属者・作者の従来挙動
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("所属者・作者の従来挙動")
    class MemberAndAuthor {

        @Test
        @DisplayName("AC-4a: チームメンバーには従来どおり MEMBERS_ONLY を含む可視記事が返る")
        void ac4a_メンバーにはMEMBERS_ONLYが返る() throws Exception {
            String pub = teamPost(Visibility.PUBLIC, PostStatus.PUBLISHED).getSlug();
            String membersOnly = teamPost(Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED).getSlug();
            teamPost(Visibility.PUBLIC, PostStatus.DRAFT); // 作者の下書きはメンバーにも見えない
            em.flush();

            JsonNode body = okList(memberId, "teamId", teamId.toString(), null, null);

            assertThat(body.path("meta").path("total").asLong()).isEqualTo(2);
            assertThat(slugs(body)).containsExactlyInAnyOrder(pub, membersOnly);
        }

        @Test
        @DisplayName("AC-4b: 作者本人には自分の DRAFT が返る")
        void ac4b_作者には自分の下書きが返る() throws Exception {
            String pub = teamPost(Visibility.PUBLIC, PostStatus.PUBLISHED).getSlug();
            String draft = teamPost(Visibility.MEMBERS_ONLY, PostStatus.DRAFT).getSlug();
            em.flush();

            JsonNode body = okList(authorId, "teamId", teamId.toString(), null, null);

            assertThat(body.path("meta").path("total").asLong()).isEqualTo(2);
            assertThat(slugs(body)).containsExactlyInAnyOrder(pub, draft);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-5 / AC-6: 他テナント・組織経路
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-5: 別組織配下（他テナント）のユーザーでも PUBLISHED×PUBLIC 記事のみが返り数えられる")
    void ac5_他テナントのユーザーも公開記事のみ() throws Exception {
        String visible = seedMatrix(teamId, null);
        em.flush();

        JsonNode body = okList(foreignId, "teamId", teamId.toString(), null, null);

        assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
        assertThat(slugs(body)).containsExactly(visible);
    }

    @Test
    @DisplayName("AC-6: organizationId 経路でも 非所属者=公開のみ・メンバー=MEMBERS_ONLY可・作者=自分のDRAFT可")
    void ac6_組織経路でも同じ() throws Exception {
        String visible = seedMatrix(null, orgId);
        String membersOnly = slugOf(Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED);
        String draft = slugOf(Visibility.PUBLIC, PostStatus.DRAFT);
        em.flush();

        // AC-1〜3 相当: 非所属者（slug 指定・数値指定の双方）
        for (String orgParam : List.of(orgId.toString(), orgSlug)) {
            JsonNode outsider = okList(outsiderId, "organizationId", orgParam, null, null);
            assertThat(outsider.path("meta").path("total").asLong()).as(orgParam).isEqualTo(1);
            assertThat(slugs(outsider)).as(orgParam).containsExactly(visible);
        }

        // AC-4a 相当: 組織メンバーには MEMBERS_ONLY が返り、他人の下書きは返らない
        Set<String> member = slugs(okList(memberId, "organizationId", orgId.toString(), null, null));
        assertThat(member).contains(visible, membersOnly).doesNotContain(draft);

        // AC-4b 相当: 作者本人には自分の DRAFT が返る
        Set<String> author = slugs(okList(authorId, "organizationId", orgId.toString(), null, null));
        assertThat(author).contains(visible, draft);
    }

    @Test
    @DisplayName("AC-6: organizationId 経路でも メンバー=MEMBERS_ONLY可・他人の下書き不可、作者=自分のDRAFT可 を件数（meta.total）まで検証する")
    void ac6_組織経路のメンバーと作者も件数まで同じ() throws Exception {
        String pub = orgPost(Visibility.PUBLIC, PostStatus.PUBLISHED).getSlug();
        String membersOnly = orgPost(Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED).getSlug();
        String draft = orgPost(Visibility.PUBLIC, PostStatus.DRAFT).getSlug(); // 作者（authorId）の下書き
        em.flush();

        for (String orgParam : List.of(orgId.toString(), orgSlug)) {
            // AC-4a 相当: メンバーには PUBLIC と MEMBERS_ONLY の 2 件。作者の下書きは見えず数えない
            JsonNode member = okList(memberId, "organizationId", orgParam, null, null);
            assertThat(member.path("meta").path("total").asLong()).as("member " + orgParam).isEqualTo(2);
            assertThat(slugs(member)).as("member " + orgParam).containsExactlyInAnyOrder(pub, membersOnly);

            // AC-4b 相当: 作者（メンバーでもある）には自分の下書きを含む 3 件
            JsonNode author = okList(authorId, "organizationId", orgParam, null, null);
            assertThat(author.path("meta").path("total").asLong()).as("author " + orgParam).isEqualTo(3);
            assertThat(slugs(author)).as("author " + orgParam).containsExactlyInAnyOrder(pub, membersOnly, draft);

            // 対照: 非所属者には公開の 1 件のみ
            JsonNode outsider = okList(outsiderId, "organizationId", orgParam, null, null);
            assertThat(outsider.path("meta").path("total").asLong()).as("outsider " + orgParam).isEqualTo(1);
            assertThat(slugs(outsider)).as("outsider " + orgParam).containsExactly(pub);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-7: フォロワー・購入者（現行 Resolver どおり）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-7a: 非所属のフォロワーにも FOLLOWERS_ONLY は返らない（FollowBatchService の実装 Bean 不在のため現行 Resolver では誰にも不可視）")
    void ac7a_非所属フォロワーにもFOLLOWERS_ONLYは返らない() throws Exception {
        String visible = teamPost(Visibility.PUBLIC, PostStatus.PUBLISHED).getSlug();
        String followersOnly = teamPost(Visibility.FOLLOWERS_ONLY, PostStatus.PUBLISHED).getSlug();
        em.persist(FollowEntity.builder()
                .followerType(FollowerType.USER).followerId(outsiderId)
                .followedType(FollowerType.USER).followedId(authorId)
                .build());
        em.flush();

        JsonNode body = okList(outsiderId, "teamId", teamId.toString(), null, null);

        assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
        assertThat(slugs(body)).containsExactly(visible).doesNotContain(followersOnly);
    }

    @Test
    @DisplayName("AC-7b: 課金条件を満たす非所属者には CUSTOM が返る（現行どおり）")
    void ac7b_購入済み非所属者にはCUSTOMが返る() throws Exception {
        BlogPostEntity custom = teamPost(Visibility.CUSTOM, PostStatus.PUBLISHED);
        PaymentItemEntity item = addGate(custom.getId(), teamId, null, false);
        pay(outsiderId, item);
        em.flush();

        JsonNode body = okList(outsiderId, "teamId", teamId.toString(), null, null);

        assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
        JsonNode node = bySlug(body, custom.getSlug());
        assertThat(node).as("購入済みの CUSTOM 記事").isNotNull();
        assertThat(node.path("accessState").asText()).isEqualTo("FULL");
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-8: スコープ可視性の門（一覧・詳細）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-8 スコープ可視性の門")
    class ScopeVisibilityGate {

        @ParameterizedTest(name = "team.visibility={0}")
        @ValueSource(strings = {"GUESTS_AND_ABOVE", "SUPPORTERS_AND_ABOVE", "MEMBERS_AND_ABOVE"})
        @DisplayName("AC-8: チーム可視性を満たさない閲覧者は 一覧でも詳細でも 記事が PUBLIC でも 不存在チームと同一応答")
        void ac8_チーム可視性を満たさない閲覧者は不存在と同一(String teamVisibility) throws Exception {
            String slug = teamPost(Visibility.PUBLIC, PostStatus.PUBLISHED).getSlug();
            em.createNativeQuery("UPDATE teams SET visibility = :v WHERE id = :id")
                    .setParameter("v", teamVisibility).setParameter("id", teamId).executeUpdate();
            em.flush();
            em.clear();

            // 一覧（数値・slug の双方）: 不存在チームの現行応答 404 CMS_024 と同一
            Outcome missingList = outcome(list(outsiderId, "teamId", Long.toString(MISSING_ID)));
            for (String param : List.of(teamId.toString(), teamSlug)) {
                Outcome hidden = outcome(list(outsiderId, "teamId", param));
                assertThat(hidden).as("一覧 teamId=" + param).isEqualTo(new Outcome(404, "CMS_024"));
                assertThat(hidden).as("一覧 teamId=" + param).isEqualTo(missingList);
                assertThat(hidden.body()).doesNotContain(slug);
            }

            // 詳細: 不存在チームを数値で指定した詳細と同一応答
            Outcome missingDetail = outcome(detail(outsiderId, slug, "teamId", MISSING_ID));
            Outcome hiddenDetail = outcome(detail(outsiderId, slug, "teamId", teamId));
            assertThat(hiddenDetail).isEqualTo(new Outcome(404, "CMS_001"));
            assertThat(hiddenDetail).isEqualTo(missingDetail);
            assertThat(hiddenDetail.body()).doesNotContain("本文");
        }

        @Test
        @DisplayName("AC-8: 組織可視性（PRIVATE）を満たさない閲覧者は 一覧でも詳細でも 不存在組織と同一応答")
        void ac8_組織可視性を満たさない閲覧者は不存在と同一() throws Exception {
            String slug = orgPost(Visibility.PUBLIC, PostStatus.PUBLISHED).getSlug();
            em.createNativeQuery("UPDATE organizations SET visibility = 'PRIVATE' WHERE id = :id")
                    .setParameter("id", orgId).executeUpdate();
            em.flush();
            em.clear();

            Outcome missingList = outcome(list(outsiderId, "organizationId", Long.toString(MISSING_ID)));
            for (String param : List.of(orgId.toString(), orgSlug)) {
                Outcome hidden = outcome(list(outsiderId, "organizationId", param));
                assertThat(hidden).as("一覧 organizationId=" + param).isEqualTo(new Outcome(404, "CMS_025"));
                assertThat(hidden).as("一覧 organizationId=" + param).isEqualTo(missingList);
                assertThat(hidden.body()).doesNotContain(slug);
            }

            Outcome missingDetail = outcome(detail(outsiderId, slug, "organizationId", MISSING_ID));
            Outcome hiddenDetail = outcome(detail(outsiderId, slug, "organizationId", orgId));
            assertThat(hiddenDetail).isEqualTo(new Outcome(404, "CMS_001"));
            assertThat(hiddenDetail).isEqualTo(missingDetail);
            assertThat(hiddenDetail.body()).doesNotContain("本文");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-9: 不存在／削除済み／PROVISIONED の応答一致
    // ═════════════════════════════════════════════════════════════════════

    enum ScopeState { MISSING, DELETED, PROVISIONED }

    static Stream<Arguments> ac9Cases() {
        List<Arguments> cases = new ArrayList<>();
        for (String scope : List.of("TEAM", "ORGANIZATION")) {
            for (ScopeState state : ScopeState.values()) {
                for (boolean bySlug : List.of(true, false)) {
                    cases.add(Arguments.of(scope, state, bySlug));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1} bySlug={2}")
    @MethodSource("ac9Cases")
    @DisplayName("AC-9: 不存在／削除済み／PROVISIONED のチーム・組織を slug・数値で指定した一覧の応答は全て一致（チーム 404 CMS_024 / 組織 404 CMS_025）")
    void ac9_一覧の存在秘匿応答は一致する(String scope, ScopeState state, boolean bySlug) throws Exception {
        boolean team = "TEAM".equals(scope);
        String param = team ? "teamId" : "organizationId";
        String value = scopeValueIn(team, state, bySlug);

        Outcome actual = outcome(list(outsiderId, param, value));

        assertThat(actual).isEqualTo(new Outcome(404, team ? "CMS_024" : "CMS_025"));
        assertThat(actual.body()).doesNotContain("ac9-");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("ac9DetailCases")
    @DisplayName("AC-9: 削除済み／PROVISIONED のチーム・組織を数値で指定した詳細の応答は 不存在スコープの詳細と一致する（404 CMS_001）")
    void ac9_詳細の存在秘匿応答は一致する(String scope, ScopeState state) throws Exception {
        boolean team = "TEAM".equals(scope);
        String param = team ? "teamId" : "organizationId";
        String slug = "ac9-detail-" + key;
        Long scopeId = Long.valueOf(scopeValueIn(team, state, false, slug));

        Outcome missing = outcome(detail(outsiderId, slug, param, MISSING_ID));
        Outcome actual = outcome(detail(outsiderId, slug, param, scopeId));

        assertThat(actual).isEqualTo(new Outcome(404, "CMS_001"));
        assertThat(actual).isEqualTo(missing);
        assertThat(actual.body()).doesNotContain("本文");
    }

    static Stream<Arguments> ac9DetailCases() {
        return Stream.of(
                Arguments.of("TEAM", ScopeState.DELETED),
                Arguments.of("TEAM", ScopeState.PROVISIONED),
                Arguments.of("ORGANIZATION", ScopeState.DELETED),
                Arguments.of("ORGANIZATION", ScopeState.PROVISIONED));
    }

    /** 詳細の slug 経路で検査するスコープの状態（AC-9 の3状態＋閲覧不可）。 */
    enum DetailScopeState { MISSING, DELETED, PROVISIONED, INVISIBLE }

    static Stream<Arguments> ac9DetailSlugCases() {
        List<Arguments> cases = new ArrayList<>();
        for (String scope : List.of("TEAM", "ORGANIZATION")) {
            for (DetailScopeState state : DetailScopeState.values()) {
                cases.add(Arguments.of(scope, state));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("ac9DetailSlugCases")
    @DisplayName("AC-9: 詳細の teamId/organizationId に slug を渡しても 不存在／削除済み／PROVISIONED／閲覧不可は 不存在スコープを数値で指定した詳細と同一応答（404 CMS_001）")
    void ac9_詳細のslug経路も存在秘匿応答は一致する(String scope, DetailScopeState state) throws Exception {
        boolean team = "TEAM".equals(scope);
        String param = team ? "teamId" : "organizationId";
        String postSlug = "ac9-detail-slug-" + state.name().toLowerCase() + "-" + key;
        String value = state == DetailScopeState.INVISIBLE
                ? invisibleScopeSlug(team, postSlug)
                : scopeValueIn(team, ScopeState.valueOf(state.name()), true, postSlug);

        Outcome missing = outcome(detail(outsiderId, postSlug, param, MISSING_ID));
        Outcome actual = outcome(detail(outsiderId, postSlug, param, value));

        assertThat(actual).as(param + "=" + value).isEqualTo(new Outcome(404, "CMS_001"));
        assertThat(actual).as(param + "=" + value).isEqualTo(missing);
        assertThat(actual.body()).doesNotContain("本文").doesNotContain("AC-9 の記事");
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-20 / AC-21: スコープ未指定・個人経路で親スコープの門を迂回させない（Codex 2巡目）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-20: スコープ指定がすべて未指定・空白なら 404 CMS_001（不可視チームの公開記事も 見えるチームの公開記事も 読めない・previewToken 付きも同じ）")
    void ac20_スコープ未指定と空白は404() throws Exception {
        String hiddenSlug = "ac20-hidden-" + key;
        invisibleScopeSlug(true, hiddenSlug);
        BlogPostEntity visibleArticle = teamPost(Visibility.PUBLIC, PostStatus.PUBLISHED);
        em.flush();

        Outcome missing = outcome(detail(outsiderId, hiddenSlug, "teamId", MISSING_ID));
        assertThat(missing).isEqualTo(new Outcome(404, "CMS_001"));

        for (String slug : List.of(hiddenSlug, visibleArticle.getSlug())) {
            List<MockHttpServletRequestBuilder> requests = List.of(
                    get(DETAIL, slug),
                    get(DETAIL, slug).param("teamId", " "),
                    get(DETAIL, slug).param("teamId", ""),
                    get(DETAIL, slug).param("organizationId", " "),
                    get(DETAIL, slug).param("teamId", " ").param("organizationId", " "),
                    get(DETAIL, slug).param("userId", " "),
                    get(DETAIL, slug).param("userId", ""),
                    get(DETAIL, slug).param("teamId", " ").param("organizationId", " ").param("userId", " "),
                    get(DETAIL, slug).param("userId", " ").param("previewToken", "any-token"),
                    get(DETAIL, slug).param("previewToken", "any-token"),
                    get(DETAIL, slug).param("teamId", " ").param("previewToken", "any-token"));
            for (MockHttpServletRequestBuilder request : requests) {
                Outcome actual = outcome(mockMvc.perform(request.with(user(outsiderId.toString()))).andReturn());
                assertThat(actual).as(slug + " " + actual.body()).isEqualTo(missing);
                assertThat(actual.body()).doesNotContain("本文").doesNotContain("AC-9 の記事");
            }
        }
    }

    @Test
    @DisplayName("AC-21: userId 指定の個人経路にチーム記事の slug を渡しても 404 CMS_001（作者本人の userId でも・previewToken 付きでも）")
    void ac21_個人経路でチーム記事は読めない() throws Exception {
        String hiddenSlug = "ac21-hidden-" + key;
        invisibleScopeSlug(true, hiddenSlug);
        BlogPostEntity visibleArticle = teamPost(Visibility.PUBLIC, PostStatus.PUBLISHED);
        // user_id=作者 を併せ持つチーム記事・組織記事。userId 指定の検索（user_id 一致）には一致するため、
        // 「個人スコープ（team_id・organization_id とも null）か」の判定だけが拒否の根拠になる。
        // 本番の DDL には chk_bp_scope（team/org/user の XOR）があり作れない行だが、試験の DB は
        // ddl-auto=create で CHECK を持たないため作れる。判定を外した実装を落とすための検体として置く。
        String userAndTeamSlug = "ac21-user-team-" + key;
        String userAndOrgSlug = "ac21-user-org-" + key;
        for (String[] fixture : List.of(new String[] {"team", userAndTeamSlug}, new String[] {"org", userAndOrgSlug})) {
            boolean team = "team".equals(fixture[0]);
            em.persist(BlogPostEntity.builder()
                    .teamId(team ? teamId : null).organizationId(team ? null : orgId).userId(authorId)
                    .authorId(authorId).title("AC-9 の記事 " + fixture[1]).slug(fixture[1]).body("AC-9 の本文")
                    .postType(PostType.BLOG).visibility(Visibility.PUBLIC).status(PostStatus.PUBLISHED)
                    .readingTimeMinutes((short) 1)
                    .build());
        }
        em.flush();

        for (String slug : List.of(hiddenSlug, visibleArticle.getSlug(), userAndTeamSlug, userAndOrgSlug)) {
            for (Long viewer : List.of(outsiderId, authorId)) {
                Outcome byUserId = outcome(mockMvc.perform(get(DETAIL, slug).param("userId", authorId.toString())
                        .with(user(viewer.toString()))).andReturn());
                Outcome withPreview = outcome(mockMvc.perform(get(DETAIL, slug).param("userId", authorId.toString())
                        .param("previewToken", "any-token").with(user(viewer.toString()))).andReturn());
                Outcome personalPath = outcome(mockMvc.perform(
                        get("/api/v1/users/{userId}/blog/posts/{slug}", authorId, slug)
                                .with(user(viewer.toString()))).andReturn());
                for (Outcome actual : List.of(byUserId, withPreview, personalPath)) {
                    assertThat(actual).as(slug + " viewer=" + viewer).isEqualTo(new Outcome(404, "CMS_001"));
                    assertThat(actual.body()).doesNotContain("本文").doesNotContain("AC-9 の記事");
                }
            }
        }
    }

    @Test
    @DisplayName("AC-21 対照: 個人記事（team_id・organization_id とも null・user_id 一致）の正常な閲覧は 200（userId 指定・previewToken 付き・個人ブログの経路）")
    void ac21_個人記事の正常閲覧は200() throws Exception {
        String slug = "ac21-personal-" + key;
        em.persist(BlogPostEntity.builder()
                .userId(authorId).authorId(authorId)
                .title("個人記事 " + slug).slug(slug).body("個人の本文 " + slug)
                .postType(PostType.BLOG).visibility(Visibility.PUBLIC).status(PostStatus.PUBLISHED)
                .readingTimeMinutes((short) 1)
                .build());
        em.flush();

        List<MvcResult> results = List.of(
                mockMvc.perform(get(DETAIL, slug).param("userId", authorId.toString())
                        .with(user(outsiderId.toString()))).andReturn(),
                mockMvc.perform(get(DETAIL, slug).param("userId", authorId.toString()).param("previewToken", "any-token")
                        .with(user(outsiderId.toString()))).andReturn(),
                mockMvc.perform(get("/api/v1/users/{userId}/blog/posts/{slug}", authorId, slug)
                        .with(user(outsiderId.toString()))).andReturn());
        for (MvcResult result : results) {
            assertThat(result.getResponse().getStatus()).as(result.getRequest().getRequestURI()).isEqualTo(200);
            JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
            assertThat(data.path("content").path("slug").asText()).isEqualTo(slug);
        }

        // 別人の userId を指定しても他人の個人記事は引けない（user_id 一致が条件）
        Outcome otherUser = outcome(mockMvc.perform(get(DETAIL, slug).param("userId", memberId.toString())
                .with(user(outsiderId.toString()))).andReturn());
        assertThat(otherUser).isEqualTo(new Outcome(404, "CMS_001"));
    }

    @Test
    @DisplayName("AC-9 対照: 見えるチーム・組織の公開記事は、詳細を slug・数値のどちらで指定しても 200（組織の slug は組織として解決される）")
    void ac9_見えるスコープの公開記事は詳細が200() throws Exception {
        BlogPostEntity teamArticle = teamPost(Visibility.PUBLIC, PostStatus.PUBLISHED);
        BlogPostEntity orgArticle = orgPost(Visibility.PUBLIC, PostStatus.PUBLISHED);
        em.flush();

        for (String value : List.of(teamId.toString(), teamSlug)) {
            MvcResult result = detail(outsiderId, teamArticle.getSlug(), "teamId", value);
            assertThat(result.getResponse().getStatus()).as("teamId=" + value).isEqualTo(200);
            JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
            assertThat(data.path("content").path("slug").asText()).isEqualTo(teamArticle.getSlug());
        }
        for (String value : List.of(orgId.toString(), orgSlug)) {
            MvcResult result = detail(outsiderId, orgArticle.getSlug(), "organizationId", value);
            assertThat(result.getResponse().getStatus()).as("organizationId=" + value).isEqualTo(200);
            JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
            assertThat(data.path("content").path("slug").asText()).isEqualTo(orgArticle.getSlug());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-10: 有料記事
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-10: 有料記事は 支払い済み=FULL／未払い・タイトル公開=LOCKED（本文・要約・カバーなし）／未払い・タイトル秘匿=一覧と件数から除外")
    void ac10_有料記事の一覧表示() throws Exception {
        BlogPostEntity paid = teamPost(Visibility.PUBLIC, PostStatus.PUBLISHED);
        BlogPostEntity locked = teamPost(Visibility.PUBLIC, PostStatus.PUBLISHED);
        BlogPostEntity titleHidden = teamPost(Visibility.PUBLIC, PostStatus.PUBLISHED);
        pay(outsiderId, addGate(paid.getId(), teamId, null, false));
        addGate(locked.getId(), teamId, null, false);
        addGate(titleHidden.getId(), teamId, null, true);
        em.flush();

        JsonNode body = okList(outsiderId, "teamId", teamId.toString(), null, null);

        assertThat(body.path("meta").path("total").asLong()).isEqualTo(2);
        assertThat(slugs(body)).containsExactlyInAnyOrder(paid.getSlug(), locked.getSlug());
        assertThat(body.toString()).doesNotContain(titleHidden.getSlug()).doesNotContain(titleHidden.getTitle());

        JsonNode full = bySlug(body, paid.getSlug());
        assertThat(full.path("accessState").asText()).isEqualTo("FULL");

        JsonNode lockedNode = bySlug(body, locked.getSlug());
        assertThat(lockedNode.path("accessState").asText()).isEqualTo("LOCKED");
        assertThat(lockedNode.path("content").path("title").asText()).isEqualTo(locked.getTitle());
        // マスクは各値を null にする（JSON に null として出うる）。欠落または null を許し、中身が返らないことを検証する。
        assertAbsentOrNull(lockedNode.path("content"), "body", "LOCKED は本文を返さない");
        assertAbsentOrNull(lockedNode.path("content"), "excerpt", "LOCKED は要約を返さない");
        assertAbsentOrNull(lockedNode.path("content"), "coverImageUrl", "LOCKED はカバーを返さない");
        assertThat(lockedNode.toString()).doesNotContain("本文 ").doesNotContain("要約 ").doesNotContain("example.com/cover/");
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-12: 空一覧
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-12a: 記事0件のチームは 200・空・total=0")
    void ac12a_記事0件は空() throws Exception {
        JsonNode body = okList(outsiderId, "teamId", teamId.toString(), null, null);

        assertThat(body.path("data").size()).isZero();
        assertThat(body.path("meta").path("total").asLong()).isZero();
    }

    @Test
    @DisplayName("AC-12b: 全記事が閲覧不可のチームは 200・空・total=0（件数で存在が漏れない）")
    void ac12b_全記事閲覧不可は空() throws Exception {
        teamPost(Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED);
        teamPost(Visibility.PRIVATE, PostStatus.PUBLISHED);
        teamPost(Visibility.PUBLIC, PostStatus.DRAFT);
        teamPost(Visibility.PUBLIC, PostStatus.ARCHIVED);
        em.flush();

        JsonNode body = okList(outsiderId, "teamId", teamId.toString(), null, null);

        assertThat(body.path("data").size()).isZero();
        assertThat(body.path("meta").path("total").asLong()).isZero();
        assertThat(body.path("meta").path("totalPages").asInt()).isZero();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-13: ページング
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-13: 非可視記事を飛ばした2ページ目・100件走査の境目（101件目）・最終ページの先（空）")
    void ac13_非可視を飛ばしたページ送り() throws Exception {
        // 130件を新しい順に並べ、10件ごとに1件を MEMBERS_ONLY（非所属者に不可視）にする → 可視 117 件。
        // サービスは 100 行単位で走査するため、可視の 101 件目（index 100）は 2 回目の走査に入る。
        List<String> visible = new ArrayList<>();
        List<Long> idsInOrder = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            boolean hidden = i % 10 == 9;
            BlogPostEntity post = teamPost(hidden ? Visibility.MEMBERS_ONLY : Visibility.PUBLIC,
                    PostStatus.PUBLISHED, String.format("ac13-%03d-%s", i, key));
            idsInOrder.add(post.getId());
            if (!hidden) {
                visible.add(post.getSlug());
            }
        }
        em.flush();
        // 並び順（pinned DESC, created_at DESC）を作成順で決定的にする。
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
        for (int i = 0; i < idsInOrder.size(); i++) {
            em.createNativeQuery("UPDATE blog_posts SET created_at = :at WHERE id = :id")
                    .setParameter("at", base.minusMinutes(i)).setParameter("id", idsInOrder.get(i))
                    .executeUpdate();
        }
        em.flush();
        em.clear();
        assertThat(visible).hasSize(117);

        JsonNode page0 = okList(outsiderId, "teamId", teamId.toString(), 0, 20);
        assertThat(page0.path("meta").path("total").asLong()).isEqualTo(117);
        assertThat(orderedSlugs(page0)).containsExactlyElementsOf(visible.subList(0, 20));

        JsonNode page1 = okList(outsiderId, "teamId", teamId.toString(), 1, 20);
        assertThat(orderedSlugs(page1)).as("非可視を飛ばした後の2ページ目")
                .containsExactlyElementsOf(visible.subList(20, 40));

        JsonNode page5 = okList(outsiderId, "teamId", teamId.toString(), 5, 20);
        assertThat(orderedSlugs(page5)).as("100件走査の境目をまたぐページ（可視101件目を含む）")
                .containsExactlyElementsOf(visible.subList(100, 117));

        JsonNode big = okList(outsiderId, "teamId", teamId.toString(), 1, 100);
        assertThat(orderedSlugs(big)).as("size=100 の2ページ目は可視101件目から")
                .containsExactlyElementsOf(visible.subList(100, 117));

        JsonNode beyond = okList(outsiderId, "teamId", teamId.toString(), 6, 20);
        assertThat(beyond.path("data").size()).as("最終ページの先は空").isZero();
        assertThat(beyond.path("meta").path("total").asLong()).isEqualTo(117);
    }

    /**
     * AC-13 size/page の範囲外。
     *
     * <p>根拠: 既存一覧 API は size 上限を {@code Math.min(size, 上限)} 等で丸める実装が約80ファイル、
     * {@code @Max} で 400 を返すのは 3 コントローラ（Billing/Recruitment/ReturnStay）のみのため、多数派の「丸め」
     * （size は 1〜100 へ、page 負値は 0 へ）に合わせる。ページ規則は閲覧者に依らないため、可視性の門と
     * 切り離してメンバーで検査する。</p>
     */
    @ParameterizedTest(name = "page={0} size={1}")
    @MethodSource("ac13SizeCases")
    @DisplayName("AC-13: size=0/1/100/101・page=-1 は 1〜100・page 0 へ丸めて 200")
    void ac13_範囲外のsizeとpageは丸める(int page, int size, int expectedSize, int expectedPage,
                                    int expectedCount) throws Exception {
        for (int i = 0; i < 3; i++) {
            teamPost(Visibility.PUBLIC, PostStatus.PUBLISHED);
        }
        em.flush();

        JsonNode body = okList(memberId, "teamId", teamId.toString(), page, size);

        assertThat(body.path("meta").path("total").asLong()).isEqualTo(3);
        assertThat(body.path("meta").path("size").asInt()).isEqualTo(expectedSize);
        assertThat(body.path("meta").path("page").asInt()).isEqualTo(expectedPage);
        assertThat(body.path("data").size()).isEqualTo(expectedCount);
    }

    static Stream<Arguments> ac13SizeCases() {
        return Stream.of(
                Arguments.of(0, 0, 1, 0, 1),
                Arguments.of(0, 1, 1, 0, 1),
                Arguments.of(0, 100, 100, 0, 3),
                Arguments.of(0, 101, 100, 0, 3),
                Arguments.of(-1, 20, 20, 0, 3));
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-14: 未ログイン
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-14: 未ログインは一覧 401（現状維持）")
    void ac14_未ログインは401() throws Exception {
        teamPost(Visibility.PUBLIC, PostStatus.PUBLISHED);
        orgPost(Visibility.PUBLIC, PostStatus.PUBLISHED);
        em.flush();

        mockMvc.perform(get(LIST).param("teamId", teamId.toString()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(LIST).param("organizationId", orgId.toString()))
                .andExpect(status().isUnauthorized());
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-15: 書き込み系
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-15 書き込み系")
    class Writes {

        @Test
        @DisplayName("AC-15a: 非所属者の POST /api/v1/blog/posts（対象チーム・組織指定）は 403 で記事は増えない")
        void ac15a_非所属者の作成は拒否() throws Exception {
            long before = countPosts();

            for (Map.Entry<String, String> scope : Map.of("teamId", teamId.toString(),
                    "organizationId", orgId.toString()).entrySet()) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put(scope.getKey(), scope.getValue());
                body.put("title", "乗っ取り記事");
                body.put("body", "本文");
                body.put("visibility", "PUBLIC");
                mockMvc.perform(post(LIST).with(user(outsiderId.toString()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value("COMMON_002"));
            }
            assertThat(countPosts()).isEqualTo(before);
        }

        @Test
        @DisplayName("AC-15b: 作者でも管理者でもない人（メンバー・非所属者）の PUT/DELETE は 403")
        void ac15b_非作者非管理者の更新削除は拒否() throws Exception {
            BlogPostEntity target = teamPost(Visibility.PUBLIC, PostStatus.PUBLISHED);
            em.flush();

            for (Long actor : List.of(memberId, outsiderId)) {
                mockMvc.perform(put(LIST + "/{id}", target.getId()).with(user(actor.toString()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("title", "改竄", "body", "改竄本文"))))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value("COMMON_002"));
                mockMvc.perform(delete(LIST + "/{id}", target.getId()).with(user(actor.toString())))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value("COMMON_002"));
            }
            em.clear();
            Object deletedAt = em.createNativeQuery("SELECT deleted_at FROM blog_posts WHERE id = :id")
                    .setParameter("id", target.getId()).getSingleResult();
            assertThat(deletedAt).isNull();
        }

        @Test
        @DisplayName("AC-15c: 作者本人の PUT=200・DELETE=204 は維持")
        void ac15c_作者本人の更新削除は維持() throws Exception {
            BlogPostEntity target = teamPost(Visibility.PUBLIC, PostStatus.PUBLISHED);
            em.flush();

            mockMvc.perform(put(LIST + "/{id}", target.getId()).with(user(authorId.toString()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "作者の改題", "body", "作者の本文"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.title").value("作者の改題"));
            mockMvc.perform(delete(LIST + "/{id}", target.getId()).with(user(authorId.toString())))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    record Outcome(int status, String code, String body) {
        Outcome(int status, String code) {
            this(status, code, null);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Outcome other && status == other.status
                    && java.util.Objects.equals(code, other.code);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(status, code);
        }

        @Override
        public String toString() {
            return status + " " + code;
        }
    }

    private Outcome outcome(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        String code = null;
        if (!body.isBlank()) {
            JsonNode node = objectMapper.readTree(body).path("error").path("code");
            code = node.isMissingNode() || node.isNull() ? null : node.asText();
        }
        return new Outcome(result.getResponse().getStatus(), code, body);
    }

    private MvcResult list(Long viewer, String param, String value) throws Exception {
        return mockMvc.perform(get(LIST).param(param, value).with(user(viewer.toString()))).andReturn();
    }

    private MvcResult detail(Long viewer, String slug, String param, Long scopeId) throws Exception {
        return detail(viewer, slug, param, scopeId.toString());
    }

    private MvcResult detail(Long viewer, String slug, String param, String scopeValue) throws Exception {
        return mockMvc.perform(get(DETAIL, slug).param(param, scopeValue)
                .with(user(viewer.toString()))).andReturn();
    }

    /**
     * 非所属者からは見えない（チーム: MEMBERS_AND_ABOVE、組織: PRIVATE）実在スコープに PUBLIC×PUBLISHED 記事を置き、
     * スコープの slug を返す。
     */
    private String invisibleScopeSlug(boolean team, String postSlug) {
        String scopeSlug = "ac9-scope-invisible-" + key;
        Long scopeId = team
                ? TeamOrgFixtureHelper.insertTeam(em, "AC-9 不可視チーム", scopeSlug)
                : TeamOrgFixtureHelper.insertOrganization(em, "AC-9 不可視組織", scopeSlug);
        em.persist(BlogPostEntity.builder()
                .teamId(team ? scopeId : null).organizationId(team ? null : scopeId)
                .authorId(authorId).title("AC-9 の記事").slug(postSlug).body("AC-9 の本文")
                .visibility(Visibility.PUBLIC).status(PostStatus.PUBLISHED).postType(PostType.BLOG)
                .build());
        em.flush();
        String update = team
                ? "UPDATE teams SET visibility = 'MEMBERS_AND_ABOVE' WHERE id = :id"
                : "UPDATE organizations SET visibility = 'PRIVATE' WHERE id = :id";
        em.createNativeQuery(update).setParameter("id", scopeId).executeUpdate();
        em.flush();
        em.clear();
        return scopeSlug;
    }

    private JsonNode okList(Long viewer, String param, String value, Integer page, Integer size) throws Exception {
        MockHttpServletRequestBuilder request = get(LIST).param(param, value).with(user(viewer.toString()));
        if (page != null) {
            request.param("page", page.toString());
        }
        if (size != null) {
            request.param("size", size.toString());
        }
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** フィールドが欠落しているか JSON null であること（値が入っていないこと）を検証する。 */
    private static void assertAbsentOrNull(JsonNode node, String field, String description) {
        JsonNode value = node.get(field);
        assertThat(value == null || value.isNull()).as(description + " (" + field + "=" + value + ")").isTrue();
    }

    private static Set<String> slugs(JsonNode body) {
        return new LinkedHashSet<>(orderedSlugs(body));
    }

    private static List<String> orderedSlugs(JsonNode body) {
        List<String> slugs = new ArrayList<>();
        for (JsonNode item : body.path("data")) {
            slugs.add(item.path("content").path("slug").asText());
        }
        return slugs;
    }

    private static JsonNode bySlug(JsonNode body, String slug) {
        for (JsonNode item : body.path("data")) {
            if (slug.equals(item.path("content").path("slug").asText())) {
                return item;
            }
        }
        return null;
    }

    /** 状態ごとの不存在／削除済み／PROVISIONED スコープを用意し、指定方法（slug か数値）の値を返す。 */
    private String scopeValueIn(boolean team, ScopeState state, boolean bySlug) {
        return scopeValueIn(team, state, bySlug, "ac9-" + state.name().toLowerCase() + "-" + key);
    }

    private String scopeValueIn(boolean team, ScopeState state, boolean bySlug, String postSlug) {
        if (state == ScopeState.MISSING) {
            return bySlug ? "no-such-scope-" + key : Long.toString(MISSING_ID);
        }
        String scopeSlug = "ac9-scope-" + state.name().toLowerCase() + "-" + key;
        Long scopeId = team
                ? TeamOrgFixtureHelper.insertTeam(em, "AC-9 チーム", scopeSlug)
                : TeamOrgFixtureHelper.insertOrganization(em, "AC-9 組織", scopeSlug);
        // スコープ内には PUBLIC×PUBLISHED 記事を置き、応答から存在が漏れないかを見る。
        em.persist(BlogPostEntity.builder()
                .teamId(team ? scopeId : null).organizationId(team ? null : scopeId)
                .authorId(authorId).title("AC-9 の記事").slug(postSlug).body("AC-9 の本文")
                .visibility(Visibility.PUBLIC).status(PostStatus.PUBLISHED).postType(PostType.BLOG)
                .build());
        em.flush();
        String table = team ? "teams" : "organizations";
        String update = state == ScopeState.DELETED
                ? "UPDATE " + table + " SET deleted_at = NOW() WHERE id = :id"
                : "UPDATE " + table + " SET lifecycle_status = 'PROVISIONED' WHERE id = :id";
        em.createNativeQuery(update).setParameter("id", scopeId).executeUpdate();
        em.flush();
        em.clear();
        return bySlug ? scopeSlug : scopeId.toString();
    }

    /**
     * 全公開範囲（PUBLISHED）と PUBLISHED 以外の全状態（PUBLIC）の記事を1件ずつ作り、
     * 非所属者に見えるべき唯一の slug（PUBLIC×PUBLISHED）を返す。CUSTOM には未払いゲートを付ける。
     */
    private String seedMatrix(Long team, Long org) {
        String visible = null;
        for (Visibility visibility : Visibility.values()) {
            BlogPostEntity post = savePost(team, org, visibility, PostStatus.PUBLISHED,
                    slugOf(visibility, PostStatus.PUBLISHED));
            if (visibility == Visibility.PUBLIC) {
                visible = post.getSlug();
            }
            if (visibility == Visibility.CUSTOM) {
                addGate(post.getId(), team, org, false);
            }
        }
        for (PostStatus status : PostStatus.values()) {
            if (status != PostStatus.PUBLISHED) {
                savePost(team, org, Visibility.PUBLIC, status, slugOf(Visibility.PUBLIC, status));
            }
        }
        return visible;
    }

    private String slugOf(Visibility visibility, PostStatus status) {
        return ("m-" + visibility.name() + "-" + status.name() + "-" + key).toLowerCase().replace('_', '-');
    }

    private BlogPostEntity teamPost(Visibility visibility, PostStatus status) {
        return teamPost(visibility, status, "p-" + UUID.randomUUID());
    }

    private BlogPostEntity teamPost(Visibility visibility, PostStatus status, String slug) {
        return savePost(teamId, null, visibility, status, slug);
    }

    private BlogPostEntity orgPost(Visibility visibility, PostStatus status) {
        return savePost(null, orgId, visibility, status, "p-" + UUID.randomUUID());
    }

    private BlogPostEntity savePost(Long team, Long org, Visibility visibility, PostStatus status, String slug) {
        BlogPostEntity post = BlogPostEntity.builder()
                .teamId(team).organizationId(org).authorId(authorId)
                .title("記事 " + slug).slug(slug).body("本文 " + slug)
                .excerpt("要約 " + slug).coverImageUrl("https://example.com/cover/" + slug + ".png")
                .postType(PostType.BLOG).visibility(visibility).status(status)
                .readingTimeMinutes((short) 1)
                .build();
        em.persist(post);
        return post;
    }

    private PaymentItemEntity addGate(Long postId, Long team, Long org, boolean titleHidden) {
        PaymentItemEntity item = PaymentItemEntity.builder().teamId(team).organizationId(org)
                .name("記事購読 " + postId).type(PaymentItemType.MONTHLY_FEE)
                .amount(BigDecimal.valueOf(100)).build();
        em.persist(item);
        em.persist(ContentPaymentGateEntity.builder().paymentItemId(item.getId()).contentType("POST")
                .contentId(postId).isTitleHidden(titleHidden).createdBy(authorId).build());
        return item;
    }

    private void pay(Long userId, PaymentItemEntity item) {
        em.persist(MemberPaymentEntity.builder().userId(userId).paymentItemId(item.getId())
                .amountPaid(BigDecimal.valueOf(100)).paymentMethod(PaymentMethod.CASH)
                .status(PaymentStatus.PAID).payerUserId(userId).payerRelationship(PayerRelationship.SELF)
                .validFrom(LocalDate.now().minusYears(1)).validUntil(LocalDate.now().plusYears(1))
                .paidAt(LocalDateTime.now()).recordedBy(authorId).build());
    }

    private long countPosts() {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM blog_posts WHERE team_id = :t OR organization_id = :o")
                .setParameter("t", teamId).setParameter("o", orgId).getSingleResult()).longValue();
    }

    private Long saveUser(String role) {
        UserEntity entity = UserEntity.builder().email("blog-list-" + role + "-" + key + "@example.com")
                .lastName("試練").firstName(role).displayName("ブログ一覧 " + role).isSearchable(true)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build();
        em.persist(entity);
        return entity.getId();
    }
}
