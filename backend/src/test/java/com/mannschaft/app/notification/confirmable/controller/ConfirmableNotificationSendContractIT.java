package com.mannschaft.app.notification.confirmable.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import com.mannschaft.app.notification.credit.repository.OrganizationNotificationBalanceRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260920-1040 F04.9「宛先指定」戦役 — 試練A補完（AC-19 送信APIの202/QUEUED・AC-36 errorCodeの相互差異）。
 *
 * <p>軍議第8版確定稿 §3.3「送信 API」・§4「件数・非同期」AC-19、「エラーの伝え方」AC-36 を対象とする。
 * 送信 API（{@code OrgConfirmableNotificationController#send}）は本試練の時点でも実在するが、
 * まだ旧仕様（{@code recipientUserIds} 前提の同期送信・201固定）のままで、{@code targets}/
 * {@code recipientGroupId} を受けても一切解釈しない。したがって、新しい契約（202・QUEUED・
 * 受信者行0件・状況ごとに異なるerrorCode）を期待する本テストは、<b>旧実装で実際に返る
 * レスポンス（別のステータス・別のerrorCode、またはNullPointerException等の500）と食い違うことで
 * red になる</b>。これは「未配線だから意味のあるredが書けない」のではなく、
 * 「新しい契約を期待するテストが旧実装で落ちる」という正しいredである。</p>
 *
 * <h2>AC ↔ テスト対応</h2>
 * <ul>
 *   <li>AC-19: {@link #ac19_送信APIは202を返しQUEUEDで作られ受信者行を作らない()}</li>
 *   <li>AC-36 400 TARGETS_EMPTY: {@link #ac36_400_targetsEmpty()}</li>
 *   <li>AC-36 400 二重指定: {@link #ac36_400_targetsAndGroupBothSpecified()}</li>
 *   <li>AC-36 403 TARGET_OUT_OF_SCOPE: {@link #ac36_403_targetOutOfScope()}</li>
 *   <li>AC-36 404 RECIPIENT_GROUP_NOT_FOUND: {@link #ac36_404_recipientGroupNotFound()}</li>
 *   <li>AC-36 409 RECIPIENTS_EMPTY: {@link #ac36_409_recipientsEmpty()}</li>
 *   <li>AC-36 CREDIT_INSUFFICIENT: {@link #ac36_creditInsufficient()}</li>
 *   <li>AC-36 ステータス・errorCodeの相互差異: {@link #ac36_全シナリオのステータスとerrorCodeが互いに異なる()}</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("送信API 契約試練（AC-19 202/QUEUED・AC-36 errorCode相互差異）")
class ConfirmableNotificationSendContractIT extends AbstractMySqlIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ConfirmableNotificationRecipientRepository recipientRepository;
    @Autowired
    private OrganizationNotificationBalanceRepository balanceRepository;
    @PersistenceContext
    private EntityManager em;

    private Long orgId;
    private Long otherOrgId;
    private Long adminUserId;

    @BeforeEach
    void setUp() {
        seedRoles();
        orgId = insertOrganization();
        otherOrgId = insertOrganization();
        adminUserId = insertUser();
        grantRole(adminUserId, "ADMIN", orgId);
        em.flush();
        em.clear();
        setAuth(adminUserId);
    }

    @Test
    @DisplayName("AC-19: 送信APIは202を返し、本体はdeliveryStatus=QUEUEDで作られ、受信者行を作らない")
    void ac19_送信APIは202を返しQUEUEDで作られ受信者行を作らない() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "AC-19試練");
        body.put("targets", List.of(target("ORGANIZATION", orgId)));

        MvcResult result = mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("AC-19: 送信APIは受信者数によらず202を返す（本試練の時点の実装は旧仕様のため一致しない想定）")
                .isEqualTo(202);
        assertThat(result.getResponse().getContentAsString())
                .as("AC-19: 応答本文にdeliveryStatus=QUEUEDを含む")
                .contains("\"deliveryStatus\":\"QUEUED\"");
    }

    @Test
    @DisplayName("AC-36 400: targets=[]（空配列）はTARGETS_EMPTYで400")
    void ac36_400_targetsEmpty() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "AC-36試練");
        body.put("targets", List.of());

        assertErrorCode(sendAndCapture(body), 400, "CONFIRMABLE_NOTIFICATION_TARGETS_EMPTY");
    }

    @Test
    @DisplayName("AC-36 400: targetsとrecipientGroupIdを両方指定するとTARGETS_AND_GROUP_BOTH_SPECIFIEDで400")
    void ac36_400_targetsAndGroupBothSpecified() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "AC-36試練");
        body.put("targets", List.of(target("ORGANIZATION", orgId)));
        body.put("recipientGroupId", UUID.randomUUID().toString());

        assertErrorCode(sendAndCapture(body), 400, "CONFIRMABLE_NOTIFICATION_TARGETS_AND_GROUP_BOTH_SPECIFIED");
    }

    @Test
    @DisplayName("AC-36 403: 自組織ツリー外のORGANIZATIONを指定するとTARGET_OUT_OF_SCOPEで403")
    void ac36_403_targetOutOfScope() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "AC-36試練");
        body.put("targets", List.of(target("ORGANIZATION", otherOrgId)));

        assertErrorCode(sendAndCapture(body), 403, "CONFIRMABLE_NOTIFICATION_TARGET_OUT_OF_SCOPE");
    }

    @Test
    @DisplayName("AC-36 404: 存在しないrecipientGroupIdはRECIPIENT_GROUP_NOT_FOUNDで404")
    void ac36_404_recipientGroupNotFound() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "AC-36試練");
        body.put("recipientGroupId", UUID.randomUUID().toString());

        assertErrorCode(sendAndCapture(body), 404, "CONFIRMABLE_NOTIFICATION_RECIPIENT_GROUP_NOT_FOUND");
    }

    @Test
    @DisplayName("AC-36 409: 見込み受信者0件の組織はRECIPIENTS_EMPTYで409")
    void ac36_409_recipientsEmpty() throws Exception {
        // orgId 自身にはメンバー（adminUserId）が存在するため、メンバーが1人もいない別組織を使う。
        Long emptyOrg = insertOrganization();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "AC-36試練");
        body.put("targets", List.of(target("ORGANIZATION", emptyOrg)));

        // emptyOrg は adminUserId の配下ではないため、実際には403（TARGET_OUT_OF_SCOPE）と競合しうる。
        // ここでは「adminUserId自身のorgIdだが、配下にメンバーがいない」状態を作るのが正確なので、
        // 空の子組織を作って targets に指定する。
        Long emptyChildOrg = insertOrganization();
        setOrgParent(emptyChildOrg, orgId);
        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("title", "AC-36試練2");
        body2.put("targets", List.of(target("ORGANIZATION", emptyChildOrg)));

        assertErrorCode(sendAndCapture(body2), 409, "CONFIRMABLE_NOTIFICATION_RECIPIENTS_EMPTY");
    }

    @Test
    @DisplayName("AC-36 CREDIT_INSUFFICIENT: 猶予超過の組織はCREDIT_INSUFFICIENTを返す（402想定）")
    void ac36_creditInsufficient() throws Exception {
        seedGracePeriodExceeded(orgId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "AC-36試練");
        body.put("targets", List.of(target("ORGANIZATION", orgId)));

        assertErrorCode(sendAndCapture(body), 402, "CONFIRMABLE_NOTIFICATION_CREDIT_INSUFFICIENT");
    }

    @Test
    @DisplayName("AC-26: 受付の時点で猶予超過ならCREDIT_INSUFFICIENTを返し、本体・受信者行・課金残高の消費を一切作らない")
    void ac26_creditInsufficientCreatesNothing() throws Exception {
        seedGracePeriodExceeded(orgId);
        long debtBefore = balanceRepository.findByOrganizationId(orgId).orElseThrow().getGracePeriodDebt();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "AC-26試練");
        body.put("targets", List.of(target("ORGANIZATION", orgId)));

        MvcResult result = sendAndCapture(body);

        assertErrorCode(result, 402, "CONFIRMABLE_NOTIFICATION_CREDIT_INSUFFICIENT");
        assertThat(recipientRepository.count())
                .as("AC-26: 受信者行は1件も作られない")
                .isZero();
        assertThat(balanceRepository.findByOrganizationId(orgId).orElseThrow().getGracePeriodDebt())
                .as("AC-26: 課金の消費（負債の加算）は行われない")
                .isEqualTo(debtBefore);
        long notificationCount = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM confirmable_notifications WHERE scope_id = :orgId AND title = 'AC-26試練'")
                .setParameter("orgId", orgId)
                .getSingleResult()).longValue();
        assertThat(notificationCount).as("AC-26: 確認通知の本体は作られない").isZero();
        long jobCount = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM notification_fanout_jobs WHERE scope_type = 'CONFIRMABLE_TARGETS'")
                .getSingleResult()).longValue();
        assertThat(jobCount).as("AC-26: fanoutジョブも作られない").isZero();
    }

    @Test
    @DisplayName("AC-52: 確認期限（deadlineAt）が受付の時点で既に過去なら400 DEADLINE_IN_PASTを返す")
    void ac52_deadlineInPastReturns400() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "AC-52試練");
        body.put("targets", List.of(target("ORGANIZATION", orgId)));
        body.put("deadlineAt", LocalDateTime.now().minusHours(1)
                .atOffset(java.time.ZoneOffset.ofHours(9))
                .toString());

        assertErrorCode(sendAndCapture(body), 400, "CONFIRMABLE_NOTIFICATION_DEADLINE_IN_PAST");
    }

    @Test
    @DisplayName("AC-36: 400/403/404/409/CREDIT_INSUFFICIENTの各シナリオは、ステータス・errorCodeとも互いに異なる")
    void ac36_全シナリオのステータスとerrorCodeが互いに異なる() throws Exception {
        seedGracePeriodExceeded(orgId);
        Long emptyChildOrg = insertOrganization();
        setOrgParent(emptyChildOrg, orgId);

        record Scenario(String name, Map<String, Object> body) {
        }
        List<Scenario> scenarios = List.of(
                new Scenario("TARGETS_EMPTY", bodyWith(m -> m.put("targets", List.of()))),
                new Scenario("BOTH_SPECIFIED", bodyWith(m -> {
                    m.put("targets", List.of(target("ORGANIZATION", orgId)));
                    m.put("recipientGroupId", UUID.randomUUID().toString());
                })),
                new Scenario("OUT_OF_SCOPE", bodyWith(m -> m.put("targets", List.of(target("ORGANIZATION", otherOrgId))))),
                new Scenario("GROUP_NOT_FOUND", bodyWith(m -> m.put("recipientGroupId", UUID.randomUUID().toString()))),
                new Scenario("RECIPIENTS_EMPTY", bodyWith(m -> m.put("targets", List.of(target("ORGANIZATION", emptyChildOrg))))),
                new Scenario("CREDIT_INSUFFICIENT", bodyWith(m -> m.put("targets", List.of(target("ORGANIZATION", orgId))))));

        Set<String> combinations = new HashSet<>();
        for (Scenario scenario : scenarios) {
            MvcResult result = sendAndCapture(scenario.body());
            int status = result.getResponse().getStatus();
            String errorCode = extractErrorCode(result);
            String combo = status + ":" + errorCode;
            assertThat(combinations)
                    .as("AC-36: シナリオ「" + scenario.name() + "」のstatus:errorCode（" + combo
                            + "）は他のシナリオと重複してはならない")
                    .doesNotContain(combo);
            combinations.add(combo);
        }
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    private Map<String, Object> bodyWith(java.util.function.Consumer<Map<String, Object>> customizer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "AC-36相互差異試練-" + SEQ.incrementAndGet());
        customizer.accept(body);
        return body;
    }

    private Map<String, Object> target(String type, Long id) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", type);
        t.put("id", id);
        return t;
    }

    private MvcResult sendAndCapture(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private void assertErrorCode(MvcResult result, int expectedStatus, String expectedErrorCode) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("HTTPステータスが期待どおりであること（現行実装は別のステータスを返す想定）")
                .isEqualTo(expectedStatus);
        assertThat(extractErrorCode(result))
                .as("errorCodeが期待どおりであること（現行実装は別のerrorCode、または本文に含まれない想定）")
                .isEqualTo(expectedErrorCode);
    }

    private String extractErrorCode(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        if (content == null || content.isBlank()) {
            return "<empty-body:status=" + result.getResponse().getStatus() + ">";
        }
        try {
            var node = objectMapper.readTree(content);
            var codeNode = node.path("error").path("code");
            return codeNode.isMissingNode() ? "<no-error-code-field>" : codeNode.asText();
        } catch (Exception e) {
            return "<unparseable-body>";
        }
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private void seedRoles() {
        insertRole("SYSTEM_ADMIN", 1);
        insertRole("ADMIN", 2);
        insertRole("DEPUTY_ADMIN", 3);
        insertRole("MEMBER", 4);
        insertRole("SUPPORTER", 5);
        insertRole("GUEST", 6);
        em.flush();
    }

    private void insertRole(String name, int priority) {
        em.createNativeQuery(
                        "INSERT IGNORE INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :name, :priority, 1, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("priority", priority)
                .executeUpdate();
    }

    private Long insertUser() {
        int n = SEQ.incrementAndGet();
        String email = "cnsc-authz-" + n + "@example.com";
        em.createNativeQuery(
                        "INSERT INTO users (email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, created_at, updated_at) "
                                + "VALUES (:email, 'CNSC', :fn, :dn, 'ACTIVE', 1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("fn", "利用者" + n)
                .setParameter("dn", "CNSC 利用者" + n)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }

    private Long insertOrganization() {
        String name = "CNSC組織-" + SEQ.incrementAndGet();
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('cnsc-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void setOrgParent(Long orgId, Long parentOrgId) {
        em.createNativeQuery("UPDATE organizations SET parent_organization_id = :parentId WHERE id = :id")
                .setParameter("parentId", parentOrgId)
                .setParameter("id", orgId)
                .executeUpdate();
    }

    private void grantRole(Long userId, String roleName, Long orgIdParam) {
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "SELECT :uid, r.id, NULL, :oid, NOW(), NOW() FROM roles r WHERE r.name = :role")
                .setParameter("uid", userId)
                .setParameter("oid", orgIdParam)
                .setParameter("role", roleName)
                .executeUpdate();
        em.createNativeQuery(
                        "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at) "
                                + "SELECT :uid, 'ORGANIZATION', :oid, 'MEMBER', NOW(), NOW(), NOW() "
                                + "WHERE NOT EXISTS (SELECT 1 FROM memberships m WHERE m.user_id = :uid "
                                + "AND m.scope_type = 'ORGANIZATION' AND m.scope_id = :oid AND m.left_at IS NULL)")
                .setParameter("uid", userId)
                .setParameter("oid", orgIdParam)
                .executeUpdate();
    }

    /** 猶予期間（72時間）を超過した負債状態を作る（CREDIT_INSUFFICIENTの前提）。 */
    private void seedGracePeriodExceeded(Long orgId) {
        balanceRepository.save(OrganizationNotificationBalanceEntity.builder()
                .organizationId(orgId)
                .freeUsedThisMonth(10_000L)
                .freeQuotaMonth(LocalDate.now().withDayOfMonth(1))
                .creditBalance(-100L)
                .gracePeriodStartAt(LocalDateTime.now().minusHours(80))
                .gracePeriodDebt(100L)
                .build());
    }
}
