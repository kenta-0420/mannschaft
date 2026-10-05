package com.mannschaft.app.notification.confirmable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationTemplateEntity;
import com.mannschaft.app.notification.confirmable.entity.UnconfirmedVisibility;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationTemplateRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 確認通知（F04.9）の ID 付き管理・閲覧 EP の存在オラクル是正 契約テスト（CMP-260923-0954 W3b・試練 / red 先行）。
 *
 * <p>正本: {@code .claude/campaigns/2026-09-25-authz-softdelete-guard/ep_tables_w3b.md}（EP 別許可主体表・
 * 末尾「殿の判断」8件）、{@code plan1_oracle.md}（AC-1〜12・C4・SYSTEM_ADMIN 改訂 9/30）、
 * {@code plan4_tx_facade.md}（AC-18）。confirm（受信者本人の自己スコープ EP）とロック（K6）は
 * {@link ConfirmableNotificationConfirmOracleIT}、recipients/page のファサード型（AC-13/15）と
 * SCOPE_MISMATCH の投げ元0は {@code ConfirmableNotificationW3bArchTest} が持つ。</p>
 *
 * <p>対象 EP（Team = {@code /api/v1/teams/{teamId}/…}、Org = {@code /api/v1/organizations/{orgId}/…} の両方）:
 * 詳細 GET・cancel・resend-reminder・recipients・recipients/page（{@code …/confirmable-notifications/{id}…}）、
 * テンプレート PUT・DELETE（{@code …/confirmable-notification-templates/{id}}。新規発見・殿の判断1）。</p>
 *
 * <h2>EP × 主体の表（本クラスが固定する契約。NF=CONFIRMABLE_NOTIFICATION_NOT_FOUND / TNF=…_TEMPLATE_NOT_FOUND /
 * SM=…_SCOPE_MISMATCH / C002=COMMON_002。テンプレート EP は NF の代わりに TNF）</h2>
 * <pre>
 * 主体                                              | 是正前                         | 是正後
 * 部外者・他スコープ ADMIN（パス=実スコープ、実在ID）    | 403 C002                       | 404 NF/TNF（不在IDと status・code・message 完全一致）
 * 他スコープ ADMIN（パス=自スコープ、他スコープの実在ID）| 404 SM（テンプレートは既に TNF） | 404 NF/TNF（不在と完全一致）
 * 不在ID・0・-1・Long.MAX_VALUE                      | 404 NF/TNF                     | 404 NF/TNF（変えない）。非数値は 400（変えない）
 * 同スコープ MEMBER（cancel・resend・テンプレート）     | 403 C002                       | 403 C002（変えない・AC-2）
 * SEND_NOTIFICATION の無い DEPUTY_ADMIN（同上）       | 403 C002                       | 403 C002（Gate 流用の回帰検知）
 * SEND_NOTIFICATION を持つ DEPUTY_ADMIN（同上）       | 2xx                            | 2xx
 * user_roles のみの ADMIN                            | 管理系 2xx・詳細/page 403 C002   | 同じ（404 に化けない・AC-3）
 * 同スコープ MEMBER の詳細                            | 200                            | 200
 * 同スコープ非受信者 MEMBER の recipients・page        | 403 C002                       | 403 C002（変えない）
 * 受信者行を持つ元メンバー（ALL_MEMBERS の recipients） | 200                            | 200（据え置き・殿の判断4）
 * SYSTEM_ADMIN 非メンバー（パス=実スコープ、実在ID）     | 403 C002                       | 403 C002（新規許可も 404 化もしない・裁可 9/30）
 * SYSTEM_ADMIN 非メンバー（他スコープの実在ID）         | 404 SM（テンプレートは TNF）     | 404 NF/TNF
 * 部外者の cancel・resend（非 ACTIVE の実在通知）       | 403 C002                       | 404 NF（409 ALREADY_CANCELLED を出さない・AC-7）
 * 越境の書込（cancel・resend・テンプレート）            | DB 不変                        | DB 不変（AC-11）
 * 許可経路の認可 SQL 本数                              | 判定関数 1 回ぶん               | 同じ（isMember / isSystemAdmin を許可経路に足さない・AC-12/18）
 * 一覧型（recipients・page）の認可 SQL 本数             | 受信者 0/1/複数件で一定          | 同じ（C6）
 * </pre>
 *
 * <p>基盤は既存 {@code ConfirmableNotificationScopeContractIT} に揃える（{@code addFilters=false}＋実 MySQL＋
 * 手動 SecurityContext。メソッドセキュリティ・Controller・Service・Repository・GlobalExceptionHandler は実物）。
 * DB・認可・自前 Bean はモックしない。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("確認通知 ID 付き EP の存在オラクル是正 契約（W3b・試練）")
class ConfirmableNotificationExistenceOracleContractIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING_ID = 987_654_321L;
    private static final String SEND_NOTIFICATION = "SEND_NOTIFICATION";
    private static final String MIGRATION_RESOURCE =
            "db/migration/V216.20260918083734__add_send_notification_permission.sql";
    /** 認可判定が触るテーブル（memberships / user_roles / roles / role_permissions / permissions）。 */
    private static final Pattern AUTHZ_TABLES =
            Pattern.compile("\\b(memberships|user_roles|roles|role_permissions|permissions)\\b");
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ConfirmableNotificationRepository notificationRepository;
    @Autowired
    private ConfirmableNotificationTemplateRepository templateRepository;
    @Autowired
    private AccessControlService accessControlService;
    @PersistenceContext
    private EntityManager em;

    private Fx team;
    private Fx org;

    // ═════════════════════════════════════════════════════════════════════
    // スコープ種別と EP の定義
    // ═════════════════════════════════════════════════════════════════════

    enum Kind {
        TEAM("/api/v1/teams/", ScopeType.TEAM, com.mannschaft.app.membership.domain.ScopeType.TEAM),
        ORG("/api/v1/organizations/", ScopeType.ORGANIZATION,
                com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION);

        final String base;
        final ScopeType entityScope;
        final com.mannschaft.app.membership.domain.ScopeType membershipScope;

        Kind(String base, ScopeType entityScope, com.mannschaft.app.membership.domain.ScopeType membershipScope) {
            this.base = base;
            this.entityScope = entityScope;
            this.membershipScope = membershipScope;
        }

        String acsScope() {
            return entityScope.name();
        }
    }

    enum Op {
        DETAIL(false),
        CANCEL(false),
        RESEND(false),
        RECIPIENTS(false),
        RECIPIENTS_PAGE(false),
        TEMPLATE_PUT(true),
        TEMPLATE_DELETE(true);

        final boolean template;

        Op(boolean template) {
            this.template = template;
        }

        MockHttpServletRequestBuilder build(Kind kind, long scopeId, String id) {
            String n = kind.base + scopeId + "/confirmable-notifications/" + id;
            String t = kind.base + scopeId + "/confirmable-notification-templates/" + id;
            return switch (this) {
                case DETAIL -> get(n);
                case CANCEL -> patch(n + "/cancel");
                case RESEND -> post(n + "/resend-reminder");
                case RECIPIENTS -> get(n + "/recipients");
                case RECIPIENTS_PAGE -> get(n + "/recipients/page");
                case TEMPLATE_PUT -> put(t).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"W3B 更新後\",\"title\":\"W3B 更新後タイトル\"}");
                case TEMPLATE_DELETE -> delete(t);
            };
        }

        ConfirmableNotificationErrorCode notFound() {
            return template ? ConfirmableNotificationErrorCode.TEMPLATE_NOT_FOUND
                    : ConfirmableNotificationErrorCode.NOT_FOUND;
        }

        boolean isWrite() {
            return this == CANCEL || this == RESEND || this == TEMPLATE_PUT || this == TEMPLATE_DELETE;
        }
    }

    /** スコープ種別ごとのフィクスチャ。 */
    static final class Fx {
        final Kind kind;
        Long scopeA;
        Long scopeB;
        Long adminA;          // スコープA の ADMIN（memberships＋user_roles）
        Long adminB;          // スコープB の ADMIN（越境する側）
        Long memberA;         // スコープA の一般メンバー
        Long deputyA;         // スコープA の DEPUTY_ADMIN（memberships＋user_roles。SEND_NOTIFICATION は既定で無し）
        Long userRolesOnlyAdminA; // スコープA の user_roles だけの ADMIN（memberships なし）
        Long outsider;        // どこにも所属しない
        Long systemAdmin;     // SYSTEM_ADMIN（どこにも所属しない）
        Long formerRecipient; // スコープA に在籍していないが ALL_MEMBERS 通知の受信者行を持つ
        Long notifA;          // スコープA の ACTIVE 通知（CREATOR_AND_ADMIN）
        Long notifB;          // スコープB の ACTIVE 通知
        Long cancelledA;      // スコープA の CANCELLED 通知
        Long allMembersA;     // スコープA の ACTIVE・ALL_MEMBERS 通知（formerRecipient が受信者）
        Long templateA;
        Long templateB;

        Fx(Kind kind) {
            this.kind = kind;
        }

        Long realId(Op op) {
            return op.template ? templateA : notifA;
        }

        Long otherScopeId(Op op) {
            return op.template ? templateB : notifB;
        }
    }

    static Stream<Arguments> kindsAndOps() {
        return Arrays.stream(Kind.values())
                .flatMap(k -> Arrays.stream(Op.values()).map(op -> Arguments.of(k, op)));
    }

    static Stream<Arguments> kindsAndWriteOps() {
        return Arrays.stream(Kind.values())
                .flatMap(k -> Arrays.stream(Op.values()).filter(Op::isWrite).map(op -> Arguments.of(k, op)));
    }

    @BeforeEach
    void setUp() {
        team = createFixture(Kind.TEAM);
        org = createFixture(Kind.ORG);
        em.flush();
        em.clear();
    }

    private Fx fx(Kind kind) {
        return kind == Kind.TEAM ? team : org;
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1 / C4: 越境は不在IDと完全一致
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("kindsAndOps")
    @DisplayName("AC-1/C4: 部外者・他スコープADMINが実スコープと実在IDを付けた応答は、不在IDの応答と完全一致（404）")
    void 越境_実スコープと実在ID_不在IDと同一応答(Kind kind, Op op) throws Exception {
        Fx f = fx(kind);
        for (Long actor : List.of(f.outsider, f.adminB)) {
            setAuth(actor);
            assertSameAsMissing(op.build(kind, f.scopeA, String.valueOf(f.realId(op))),
                    op.build(kind, f.scopeA, String.valueOf(MISSING_ID)), op.notFound());
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("kindsAndOps")
    @DisplayName("AC-1: 自スコープのパスに他スコープの実在IDを付けた応答は、不在IDの応答と完全一致（SCOPE_MISMATCH を出さない）")
    void 越境_自スコープのパスに他スコープのID_不在IDと同一応答(Kind kind, Op op) throws Exception {
        Fx f = fx(kind);
        setAuth(f.adminB);
        assertSameAsMissing(op.build(kind, f.scopeB, String.valueOf(f.realId(op))),
                op.build(kind, f.scopeB, String.valueOf(MISSING_ID)), op.notFound());
        setAuth(f.adminA);
        assertSameAsMissing(op.build(kind, f.scopeA, String.valueOf(f.otherScopeId(op))),
                op.build(kind, f.scopeA, String.valueOf(MISSING_ID)), op.notFound());
    }

    @ParameterizedTest(name = "{0} {1}")
    @EnumSource(Kind.class)
    @DisplayName("AC-7: 部外者が非ACTIVEの実在通知を cancel・resend しても 409 ではなく不在と同一の404")
    void 越境_非ACTIVE通知のcancelとresend_状態を漏らさない(Kind kind) throws Exception {
        Fx f = fx(kind);
        for (Long actor : List.of(f.outsider, f.adminB)) {
            setAuth(actor);
            for (Op op : List.of(Op.CANCEL, Op.RESEND)) {
                assertSameAsMissing(op.build(kind, f.scopeA, String.valueOf(f.cancelledA)),
                        op.build(kind, f.scopeA, String.valueOf(MISSING_ID)), op.notFound());
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // SYSTEM_ADMIN（裁可 9/30: 新規許可しない・是正前の 403 を 404 に畳まない）
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("kindsAndOps")
    @DisplayName("SYSTEM_ADMIN（非メンバー）: 実スコープ＋実在IDは是正前どおり403 COMMON_002、他スコープの実在IDは不在と同一の404")
    void SYSTEM_ADMIN_是正前の応答を固定(Kind kind, Op op) throws Exception {
        Fx f = fx(kind);
        setAuth(f.systemAdmin);
        expectError(op.build(kind, f.scopeA, String.valueOf(f.realId(op))), 403, "COMMON_002");
        assertSameAsMissing(op.build(kind, f.scopeA, String.valueOf(f.otherScopeId(op))),
                op.build(kind, f.scopeA, String.valueOf(MISSING_ID)), op.notFound());
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-9 / AC-10: ID の境界
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("kindsAndOps")
    @DisplayName("AC-9/10: ID が 0・-1・Long.MAX_VALUE は不在と同一の404、非数値は400（管理者・部外者とも）")
    void ID境界(Kind kind, Op op) throws Exception {
        Fx f = fx(kind);
        for (Long actor : List.of(f.adminA, f.outsider)) {
            setAuth(actor);
            for (String id : List.of("0", "-1", String.valueOf(Long.MAX_VALUE))) {
                assertSameAsMissing(op.build(kind, f.scopeA, id),
                        op.build(kind, f.scopeA, String.valueOf(MISSING_ID)), op.notFound());
            }
            MvcResult result = mockMvc.perform(op.build(kind, f.scopeA, "abc")).andReturn();
            assertThat(result.getResponse().getStatus()).as("非数値のパスは 400").isEqualTo(400);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2 / AC-3: 同スコープの権限不足は 403 のまま・user_roles のみの管理者
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("kindsAndWriteOps")
    @DisplayName("AC-2: 同スコープのMEMBERとSEND_NOTIFICATIONの無いDEPUTY_ADMINは、書込系で403 COMMON_002のまま")
    void 同スコープ_書込権限不足は403のまま(Kind kind, Op op) throws Exception {
        Fx f = fx(kind);
        for (Long actor : List.of(f.memberA, f.deputyA)) {
            setAuth(actor);
            expectError(op.build(kind, f.scopeA, String.valueOf(f.realId(op))), 403, "COMMON_002");
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Kind.class)
    @DisplayName("AC-2: 同スコープの一般メンバーは詳細200、非受信者として recipients・page は403 COMMON_002のまま")
    void 同スコープ_閲覧系(Kind kind) throws Exception {
        Fx f = fx(kind);
        setAuth(f.memberA);
        assertThat(status(Op.DETAIL.build(kind, f.scopeA, String.valueOf(f.notifA)))).isEqualTo(200);
        expectError(Op.RECIPIENTS.build(kind, f.scopeA, String.valueOf(f.notifA)), 403, "COMMON_002");
        expectError(Op.RECIPIENTS_PAGE.build(kind, f.scopeA, String.valueOf(f.notifA)), 403, "COMMON_002");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Kind.class)
    @DisplayName("殿の判断4: ALL_MEMBERS 通知の recipients は、受信者行を持つ元メンバーに200のまま／受信者でない部外者は不在と同一の404")
    void ALL_MEMBERS受信者一覧_元メンバー受信者は通す(Kind kind) throws Exception {
        Fx f = fx(kind);
        setAuth(f.formerRecipient);
        assertThat(status(Op.RECIPIENTS.build(kind, f.scopeA, String.valueOf(f.allMembersA)))).isEqualTo(200);
        setAuth(f.outsider);
        assertSameAsMissing(Op.RECIPIENTS.build(kind, f.scopeA, String.valueOf(f.allMembersA)),
                Op.RECIPIENTS.build(kind, f.scopeA, String.valueOf(MISSING_ID)),
                ConfirmableNotificationErrorCode.NOT_FOUND);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Kind.class)
    @DisplayName("AC-3: user_rolesのみのADMINは管理系2xx・recipients 200、在籍判定の詳細・pageは403 COMMON_002のまま（404に化けない）")
    void userRolesのみのADMIN(Kind kind) throws Exception {
        Fx f = fx(kind);
        setAuth(f.userRolesOnlyAdminA);
        String n = String.valueOf(f.notifA);
        String t = String.valueOf(f.templateA);
        assertThat(status(Op.RESEND.build(kind, f.scopeA, n))).isEqualTo(204);
        assertThat(status(Op.RECIPIENTS.build(kind, f.scopeA, n))).isEqualTo(200);
        assertThat(status(Op.TEMPLATE_PUT.build(kind, f.scopeA, t))).isEqualTo(200);
        assertThat(status(Op.TEMPLATE_DELETE.build(kind, f.scopeA, t))).isEqualTo(204);
        expectError(Op.DETAIL.build(kind, f.scopeA, n), 403, "COMMON_002");
        expectError(Op.RECIPIENTS_PAGE.build(kind, f.scopeA, n), 403, "COMMON_002");
        assertThat(status(Op.CANCEL.build(kind, f.scopeA, n))).isEqualTo(204);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Kind.class)
    @DisplayName("SEND_NOTIFICATION を持つ DEPUTY_ADMIN は cancel・resend・テンプレート更新/削除が2xx（是正後も通る）")
    void SEND_NOTIFICATIONを持つDEPUTYは書込系2xx(Kind kind) throws Exception {
        seedSendNotificationFromMigration();
        Fx f = fx(kind);
        setAuth(f.deputyA);
        String n = String.valueOf(f.notifA);
        String t = String.valueOf(f.templateA);
        assertThat(status(Op.RESEND.build(kind, f.scopeA, n))).isEqualTo(204);
        assertThat(status(Op.TEMPLATE_PUT.build(kind, f.scopeA, t))).isEqualTo(200);
        assertThat(status(Op.TEMPLATE_DELETE.build(kind, f.scopeA, t))).isEqualTo(204);
        assertThat(status(Op.CANCEL.build(kind, f.scopeA, n))).isEqualTo(204);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Kind.class)
    @DisplayName("許可主体: スコープA の ADMIN は全 EP で 2xx")
    void ADMINは全EPで2xx(Kind kind) throws Exception {
        Fx f = fx(kind);
        setAuth(f.adminA);
        String n = String.valueOf(f.notifA);
        String t = String.valueOf(f.templateA);
        assertThat(status(Op.DETAIL.build(kind, f.scopeA, n))).isEqualTo(200);
        assertThat(status(Op.RECIPIENTS.build(kind, f.scopeA, n))).isEqualTo(200);
        assertThat(status(Op.RECIPIENTS_PAGE.build(kind, f.scopeA, n))).isEqualTo(200);
        assertThat(status(Op.RESEND.build(kind, f.scopeA, n))).isEqualTo(204);
        assertThat(status(Op.TEMPLATE_PUT.build(kind, f.scopeA, t))).isEqualTo(200);
        assertThat(status(Op.TEMPLATE_DELETE.build(kind, f.scopeA, t))).isEqualTo(204);
        assertThat(status(Op.CANCEL.build(kind, f.scopeA, n))).isEqualTo(204);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-11: 越境の書込で DB 不変
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("kindsAndWriteOps")
    @DisplayName("AC-11: 越境（部外者・他スコープADMIN・SYSTEM_ADMIN・取り違えパス）の cancel・resend・テンプレート書込で DB が一切変わらない")
    void 越境の書込でDB不変(Kind kind, Op op) throws Exception {
        Fx f = fx(kind);
        Long target = f.realId(op);
        List<Object> before = snapshot(op, target);
        record Attempt(Long actor, Long pathScope) { }
        for (Attempt a : List.of(new Attempt(f.outsider, f.scopeA), new Attempt(f.adminB, f.scopeA),
                new Attempt(f.systemAdmin, f.scopeA), new Attempt(f.adminB, f.scopeB))) {
            setAuth(a.actor());
            int st = status(op.build(kind, a.pathScope(), String.valueOf(target)));
            assertThat(st).as("越境の書込は 2xx にならない").isGreaterThanOrEqualTo(400);
        }
        assertThat(snapshot(op, target)).as("越境の書込で対象行が変わっていない").isEqualTo(before);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-12 / AC-18: 許可経路の認可 SQL 本数は是正前の判定関数 1 回ぶんと同じ
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("kindsAndOps")
    @DisplayName("AC-12/18: 許可経路の認可SQL本数は是正前の判定関数を単体で呼んだ本数と同じ（isMember・isSystemAdmin を許可経路に足さない）")
    void 許可経路の認可SQL本数は増えない(Kind kind, Op op) throws Exception {
        Fx f = fx(kind);
        Long actor = op == Op.DETAIL ? f.memberA : f.adminA;
        String scope = kind.acsScope();
        Runnable baseline = switch (op) {
            case DETAIL -> () -> accessControlService.checkMembership(actor, f.scopeA, scope);
            case CANCEL, RESEND, TEMPLATE_PUT, TEMPLATE_DELETE -> () ->
                    accessControlService.checkAdminOrHasPermissionInScope(actor, f.scopeA, scope, SEND_NOTIFICATION);
            case RECIPIENTS -> () -> accessControlService.isAdminOrAbove(actor, f.scopeA, scope);
            case RECIPIENTS_PAGE -> () -> {
                accessControlService.checkMembership(actor, f.scopeA, scope);
                accessControlService.isAdminOrAbove(actor, f.scopeA, scope);
            };
        };
        baseline.run(); // 初回コストを測定から外す
        long expected = countAuthzSql(baseline);
        assertThat(expected).as("検出器の自己検証: 判定関数は認可テーブルへ SQL を出す").isPositive();

        setAuth(actor);
        em.flush();
        em.clear();
        SqlIntentCounter.reset();
        int st = status(op.build(kind, f.scopeA, String.valueOf(f.realId(op))));
        long actual = authzSqlCount();
        assertThat(st).as("許可経路").isBetween(200, 299);
        assertThat(actual).as("許可経路の認可SQL本数（%s %s）", kind, op).isEqualTo(expected);
    }

    // ═════════════════════════════════════════════════════════════════════
    // C6: 一覧型（recipients・page）の認可 SQL 本数は件数に依存しない
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0}")
    @EnumSource(Kind.class)
    @DisplayName("C6: recipients・recipients/page の認可SQL本数は受信者 0件・1件・複数件で一定（ADMIN）")
    void 一覧型の認可SQL本数は件数に依存しない(Kind kind) throws Exception {
        Fx f = fx(kind);
        Long zero = saveNotification(kind, f.scopeA, ConfirmableNotificationStatus.ACTIVE,
                UnconfirmedVisibility.CREATOR_AND_ADMIN, "W3B C6 0件");
        Long one = saveNotification(kind, f.scopeA, ConfirmableNotificationStatus.ACTIVE,
                UnconfirmedVisibility.CREATOR_AND_ADMIN, "W3B C6 1件");
        Long many = saveNotification(kind, f.scopeA, ConfirmableNotificationStatus.ACTIVE,
                UnconfirmedVisibility.CREATOR_AND_ADMIN, "W3B C6 複数件");
        insertRecipient(one, f.memberA, false, false);
        for (int i = 0; i < 3; i++) {
            insertRecipient(many, newMemberOf(kind, f.scopeA), false, false);
        }
        em.flush();
        em.clear();
        setAuth(f.adminA);
        for (Op op : List.of(Op.RECIPIENTS, Op.RECIPIENTS_PAGE)) {
            List<Long> counts = new ArrayList<>();
            for (Long id : List.of(zero, one, many)) {
                em.clear();
                SqlIntentCounter.reset();
                assertThat(status(op.build(kind, f.scopeA, String.valueOf(id)))).isEqualTo(200);
                counts.add(authzSqlCount());
            }
            assertThat(counts.get(0)).as("検出器の自己検証（%s）", op).isPositive();
            assertThat(counts).as("%s の認可SQL本数（0件・1件・複数件）", op).containsOnly(counts.get(0));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void assertSameAsMissing(MockHttpServletRequestBuilder real, MockHttpServletRequestBuilder missing,
            ConfirmableNotificationErrorCode expected) throws Exception {
        ErrorView realView = perform(real);
        ErrorView missingView = perform(missing);
        assertThat(missingView.status()).as("不在IDは 404").isEqualTo(404);
        assertThat(missingView.code()).isEqualTo(expected.getCode());
        assertThat(missingView.message()).isEqualTo(expected.getMessage());
        assertThat(realView).as("実在IDへの応答と不在IDへの応答が status・code・message まで一致すること")
                .isEqualTo(missingView);
    }

    private void expectError(MockHttpServletRequestBuilder request, int expectedStatus, String expectedCode)
            throws Exception {
        ErrorView view = perform(request);
        assertThat(view.status()).as(String.valueOf(view)).isEqualTo(expectedStatus);
        assertThat(view.code()).isEqualTo(expectedCode);
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private ErrorView perform(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        JsonNode error = body.isBlank() ? null : objectMapper.readTree(body).path("error");
        return new ErrorView(result.getResponse().getStatus(),
                error == null ? null : error.path("code").asText(null),
                error == null ? null : error.path("message").asText(null));
    }

    private record ErrorView(int status, String code, String message) {
    }

    private long countAuthzSql(Runnable action) {
        em.flush();
        em.clear();
        SqlIntentCounter.reset();
        action.run();
        return authzSqlCount();
    }

    private static long authzSqlCount() {
        return SqlIntentCounter.capturedSqls().stream()
                .filter(sql -> AUTHZ_TABLES.matcher(sql.toLowerCase()).find())
                .count();
    }

    /** 対象行の業務列のスナップショット（通知: 状態・キャンセル情報・カウンタ・更新時刻／テンプレート: 名前・削除・更新時刻）。 */
    private List<Object> snapshot(Op op, Long id) {
        em.flush();
        em.clear();
        String sql = op.template
                ? "SELECT name, title, body, deleted_at, updated_at FROM confirmable_notification_templates WHERE id = :id"
                : "SELECT status, cancelled_at, cancelled_by, unconfirmed_count, updated_at "
                        + "FROM confirmable_notifications WHERE id = :id";
        Object[] row = (Object[]) em.createNativeQuery(sql).setParameter("id", id).getSingleResult();
        List<Object> values = new ArrayList<>(Arrays.asList(row));
        if (!op.template) {
            values.add(em.createNativeQuery(
                            "SELECT COUNT(*) FROM confirmable_notification_recipients "
                                    + "WHERE confirmable_notification_id = :id AND first_reminder_sent_at IS NULL")
                    .setParameter("id", id).getSingleResult());
        }
        return values;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Fx createFixture(Kind kind) {
        Fx f = new Fx(kind);
        String p = "w3b-" + kind.name().toLowerCase();
        f.scopeA = kind == Kind.TEAM ? insertTeam("W3B チームA") : insertOrganization("W3B 組織A");
        f.scopeB = kind == Kind.TEAM ? insertTeam("W3B チームB") : insertOrganization("W3B 組織B");

        f.adminA = insertUser(p + "-admin-a");
        f.adminB = insertUser(p + "-admin-b");
        f.memberA = insertUser(p + "-member-a");
        f.deputyA = insertUser(p + "-deputy-a");
        f.userRolesOnlyAdminA = insertUser(p + "-ur-admin-a");
        f.outsider = insertUser(p + "-outsider");
        f.systemAdmin = insertUser(p + "-sysadmin");
        f.formerRecipient = insertUser(p + "-former");

        membership(kind, f.adminA, f.scopeA);
        role(kind, f.adminA, "ADMIN", f.scopeA);
        membership(kind, f.adminB, f.scopeB);
        role(kind, f.adminB, "ADMIN", f.scopeB);
        membership(kind, f.memberA, f.scopeA);
        membership(kind, f.deputyA, f.scopeA);
        role(kind, f.deputyA, "DEPUTY_ADMIN", f.scopeA);
        role(kind, f.userRolesOnlyAdminA, "ADMIN", f.scopeA);
        MembershipTestHelper.insertUserRole(em, f.systemAdmin, "SYSTEM_ADMIN", null, null);

        f.notifA = saveNotification(kind, f.scopeA, ConfirmableNotificationStatus.ACTIVE,
                UnconfirmedVisibility.CREATOR_AND_ADMIN, "W3B 通知A");
        f.notifB = saveNotification(kind, f.scopeB, ConfirmableNotificationStatus.ACTIVE,
                UnconfirmedVisibility.CREATOR_AND_ADMIN, "W3B 通知B");
        f.cancelledA = saveNotification(kind, f.scopeA, ConfirmableNotificationStatus.CANCELLED,
                UnconfirmedVisibility.CREATOR_AND_ADMIN, "W3B キャンセル済みA");
        f.allMembersA = saveNotification(kind, f.scopeA, ConfirmableNotificationStatus.ACTIVE,
                UnconfirmedVisibility.ALL_MEMBERS, "W3B 全員公開A");
        // resend の DB 不変を見られるよう、通知A に未確認の受信者を置く（resend が走れば first_reminder 等が動く経路）。
        insertRecipient(f.notifA, f.memberA, false, false);
        insertRecipient(f.allMembersA, f.formerRecipient, false, false);
        insertRecipient(f.allMembersA, f.memberA, false, false);

        f.templateA = templateRepository.save(ConfirmableNotificationTemplateEntity.builder()
                .scopeType(kind.entityScope).scopeId(f.scopeA)
                .name("W3B テンプレートA").title("W3B テンプレートAタイトル").build()).getId();
        f.templateB = templateRepository.save(ConfirmableNotificationTemplateEntity.builder()
                .scopeType(kind.entityScope).scopeId(f.scopeB)
                .name("W3B テンプレートB").title("W3B テンプレートBタイトル").build()).getId();
        return f;
    }

    private Long newMemberOf(Kind kind, Long scopeId) {
        Long userId = insertUser("w3b-c6-" + kind.name().toLowerCase());
        membership(kind, userId, scopeId);
        return userId;
    }

    private void membership(Kind kind, Long userId, Long scopeId) {
        MembershipTestHelper.insertMembership(em, userId, kind.membershipScope, scopeId, RoleKind.MEMBER);
    }

    private void role(Kind kind, Long userId, String roleName, Long scopeId) {
        if (kind == Kind.TEAM) {
            MembershipTestHelper.insertUserRole(em, userId, roleName, scopeId, null);
        } else {
            MembershipTestHelper.insertUserRole(em, userId, roleName, null, scopeId);
        }
    }

    private Long saveNotification(Kind kind, Long scopeId, ConfirmableNotificationStatus status,
            UnconfirmedVisibility visibility, String title) {
        return notificationRepository.save(ConfirmableNotificationEntity.builder()
                .scopeType(kind.entityScope)
                .scopeId(scopeId)
                .title(title)
                .priority(ConfirmableNotificationPriority.NORMAL)
                .status(status)
                .unconfirmedVisibility(visibility)
                .totalRecipientCount(0)
                .build()).getId();
    }

    private void insertRecipient(Long notificationId, Long userId, boolean confirmed, boolean excluded) {
        em.persist(ConfirmableNotificationRecipientEntity.builder()
                .confirmableNotification(em.getReference(ConfirmableNotificationEntity.class, notificationId))
                .user(em.getReference(com.mannschaft.app.auth.entity.UserEntity.class, userId))
                .confirmToken(UUID.randomUUID().toString())
                .isConfirmed(confirmed)
                .excludedAt(excluded ? java.time.LocalDateTime.now() : null)
                .build());
    }

    /** CMP-260909-1141 の migration 本文を実行し、SEND_NOTIFICATION のカタログと DEPUTY_ADMIN への既定付与を作る。 */
    private void seedSendNotificationFromMigration() {
        String raw;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MIGRATION_RESOURCE)) {
            assertThat(in).as("migration ファイルが classpath 上に存在すること: " + MIGRATION_RESOURCE).isNotNull();
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("migration ファイルの読み出しに失敗した: " + MIGRATION_RESOURCE, e);
        }
        StringBuilder stripped = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                stripped.append(line).append('\n');
            }
        }
        for (String part : stripped.toString().split(";")) {
            String sql = part.trim();
            if (!sql.isEmpty()) {
                em.createNativeQuery(sql).executeUpdate();
            }
        }
        em.flush();
        em.clear();
    }

    private Long insertUser(String prefix) {
        String email = prefix + "-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com";
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'W3B', 'テスト', 'W3B テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        String unique = name + " " + UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('w3b-', LEFT(REPLACE(UUID(),'-',''),10)), NOW(), NOW())")
                .setParameter("name", unique)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", unique)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        String unique = name + " " + UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('w3b-', LEFT(REPLACE(UUID(),'-',''),10)), NOW(), NOW())")
                .setParameter("name", unique)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", unique)
                .getSingleResult()).longValue();
    }
}
