package com.mannschaft.app.member;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.event.AuditLogEventListener;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.member.entity.MemberProfileEntity;
import com.mannschaft.app.member.entity.MemberSubtabRoleVisibilityEntity;
import com.mannschaft.app.member.entity.TeamPageSectionEntity;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import com.mannschaft.app.member.repository.MemberSubtabRoleVisibilityRepository;
import com.mannschaft.app.member.repository.TeamPageRepository;
import com.mannschaft.app.member.repository.TeamPageSectionRepository;
import com.mannschaft.app.member.service.MemberSubtabVisibilityService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * PR #3387 D-3T 根治（軍議書 gungi-3387-d3t.md 第3版）の試練で共有する土台。
 *
 * <p><b>なぜ共通の基底にするか</b>: {@code @MockitoSpyBean} の組み合わせが違うと ApplicationContext が
 * テストクラスごとに分裂する（{@link AbstractMySqlIntegrationTest} の Javadoc 参照）。AC-5〜AC-9・AC-11 の
 * 3 クラスで spy の宣言をここに一本化し、増える Context を 1 つに抑える。</p>
 *
 * <p><b>テスト側の TX を張らない</b>: 本試練は「権限確認が member の TX の中で走らないこと」と「書き込みの
 * TX 境界」を実測する。テスト側に {@code @Transactional} があると、全ての TX がそこへ合流して測定対象が
 * 消える。フィクスチャは JdbcTemplate／Repository（各自の TX でコミット）で確定させ、{@link #cleanUpFixture()}
 * で行単位に消す。</p>
 *
 * <p>ロール: ADM=組織の ADMIN（memberships＋user_roles の両系統。MemberScopeContractIT:121 の地雷）、
 * MEM=組織とチームの MEMBER、OUT=どこにも所属しない利用者。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
abstract class MemberTxBoundaryITSupport extends AbstractMySqlIntegrationTest {

    /** 監査のイベント種別（現行 {@link MemberSubtabVisibilityService#AUDIT_EVENT_TYPE} と同じ値）。 */
    protected static final String AUDIT_EVENT_TYPE = "MEMBER_SUBTAB_VISIBILITY_UPDATED";

    /** 新設ハンドラ名（軍議書 §2.1(d)。circulation の {@code handleCirculationExportRequested} に倣う）。 */
    protected static final String NEW_AUDIT_HANDLER = "handleMemberSubtabVisibilityUpdated";

    protected static final String ACS_FQCN = "com.mannschaft.app.common.AccessControlService";
    protected static final String NRS_FQCN = "com.mannschaft.app.common.NameResolverService";
    protected static final String WRITER_TX_NAME =
            "com.mannschaft.app.member.service.MemberSubtabVisibilityWriter.applyUpdates";

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected TeamPageSectionRepository sectionRepository;
    @Autowired protected MemberProfileRepository profileRepository;
    @Autowired @Qualifier("event-pool") protected Executor eventPoolExecutor;
    @PersistenceContext protected EntityManager em;

    // ── spy（全派生クラスで同一。Context を分裂させないため派生側で増やさないこと） ──
    @MockitoSpyBean protected AccessControlService accessControlService;
    @MockitoSpyBean protected NameResolverService nameResolverService;
    @MockitoSpyBean protected AuditLogService auditLogService;
    @MockitoSpyBean protected AuditLogEventListener auditLogEventListener;
    @MockitoSpyBean protected MemberSubtabRoleVisibilityRepository subtabRepository;
    @MockitoSpyBean protected MemberSubtabVisibilityService memberSubtabVisibilityService;
    @MockitoSpyBean protected TeamPageRepository teamPageRepository;

    protected final String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    protected Long orgId;
    protected String orgSlug;
    protected Long teamId;
    protected Long admId;
    protected Long memId;
    protected Long outId;
    /** 組織: PUBLISHED + PUBLIC（セクション1件・表示中プロフィール1件） */
    protected Long pubPageId;
    /** チーム: PUBLISHED + PUBLIC */
    protected Long teamPageId;
    /** pubPage の表示中プロフィール */
    protected Long visibleProfileId;

    /** 権限確認・名前解決が呼ばれた時点の TX 状態の記録（AC-8）。 */
    protected final List<TxObservation> observations = new CopyOnWriteArrayList<>();

    /** 呼ばれた時点の TX 状態。{@code target} は呼ばれた Bean の完全修飾クラス名。 */
    protected record TxObservation(String target, String method, boolean active, String name) {
    }

    @BeforeEach
    void setUpFixture() {
        seedRole("ADMIN");
        seedRole("DEPUTY_ADMIN");
        seedRole("MEMBER");
        seedRole("SUPPORTER");

        jdbc.update("INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, slug, created_at, updated_at) "
                        + "VALUES (?, 'OTHER', 'PUBLIC', 'NONE', 1, 0, ?, NOW(), NOW())",
                "MTX組織" + nonce, "mtx-" + nonce);
        orgId = jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", Long.class, "MTX組織" + nonce);
        orgSlug = "mtx-" + nonce;

        jdbc.update("INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                        + "created_at, updated_at) VALUES (?, 'PUBLIC', 1, 0, 0, ?, NOW(), NOW())",
                "MTXチーム" + nonce, "mtxt-" + nonce);
        teamId = jdbc.queryForObject("SELECT id FROM teams WHERE name = ?", Long.class, "MTXチーム" + nonce);

        admId = insertUser("adm");
        memId = insertUser("mem");
        outId = insertUser("out");

        // MembershipTestHelper は EntityManager のネイティブ更新で能動的な TX を要するため、
        // フィクスチャ確定だけ明示的に包む（本体の実行とは独立。OrgMemberProfileCopyRollbackIT と同じ作法）。
        inNewTx(() -> {
            MembershipTestHelper.insertMembership(em, admId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, admId, "ADMIN", null, orgId);
            MembershipTestHelper.insertMembership(em, memId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, memId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        });

        pubPageId = insertPage(orgId, null, "MTX 公開ページ", "PUBLISHED", "PUBLIC");
        teamPageId = insertPage(null, teamId, "MTX チームページ", "PUBLISHED", "PUBLIC");
        sectionRepository.save(TeamPageSectionEntity.builder()
                .teamPageId(pubPageId).sectionType(SectionType.HEADING).title("MTX 見出し").build());
        visibleProfileId = profileRepository.save(MemberProfileEntity.builder()
                .teamPageId(pubPageId).displayName("MTX 選手 表示").isVisible(true).sortOrder(0).build()).getId();

        // spy の呼び出し履歴をフィクスチャ分だけ消す（stub は無いので reset でよい）
        Mockito.clearInvocations(accessControlService, nameResolverService, auditLogService,
                auditLogEventListener, subtabRepository, memberSubtabVisibilityService, teamPageRepository);
        observations.clear();
    }

    @AfterEach
    void cleanUpFixture() {
        SecurityContextHolder.clearContext();
        awaitEventPoolIdle();
        jdbc.update("DELETE FROM audit_logs WHERE organization_id = ?", orgId);
        jdbc.update("DELETE FROM member_subtab_role_visibility WHERE scope_type = 'ORGANIZATION' AND scope_id = ?",
                orgId);
        jdbc.update("DELETE FROM member_profiles WHERE team_page_id IN (?, ?)", pubPageId, teamPageId);
        jdbc.update("DELETE FROM team_page_sections WHERE team_page_id IN (?, ?)", pubPageId, teamPageId);
        jdbc.update("DELETE FROM team_pages WHERE id IN (?, ?)", pubPageId, teamPageId);
        for (Long uid : new Long[] {admId, memId, outId}) {
            jdbc.update("DELETE FROM memberships WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM users WHERE id = ?", uid);
        }
        jdbc.update("DELETE FROM teams WHERE id = ?", teamId);
        jdbc.update("DELETE FROM organizations WHERE id = ?", orgId);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 要求の組み立て
    // ═════════════════════════════════════════════════════════════════════

    protected RequestBuilder getPageRequest(Long pageId) {
        return get("/api/v1/team/pages/{id}", pageId);
    }

    protected RequestBuilder listSectionsRequest(Long pageId) {
        return get("/api/v1/team/pages/{pageId}/sections", pageId);
    }

    protected RequestBuilder listProfilesRequest(Long pageId) {
        return get("/api/v1/team/members").param("teamPageId", pageId.toString());
    }

    protected RequestBuilder getProfileRequest(Long profileId) {
        return get("/api/v1/team/members/{id}", profileId);
    }

    protected RequestBuilder lookupRequest(Long pageId) {
        return get("/api/v1/team/members/lookup")
                .param("q", "MTX").param("teamPageId", pageId.toString()).param("limit", "10");
    }

    protected RequestBuilder listOrgPagesRequest() {
        return get("/api/v1/team/pages").param("organizationId", orgId.toString());
    }

    protected RequestBuilder listTeamPagesRequest() {
        return get("/api/v1/team/pages").param("teamId", teamId.toString());
    }

    protected RequestBuilder getSettingsRequest() {
        return get("/api/v1/organizations/{slug}/member-subtab-visibility", orgSlug);
    }

    /** {@code subtabs} は [subtabKey, minRole] の組の並び。 */
    protected RequestBuilder putSettingsRequest(String... keyAndRole) throws Exception {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int i = 0; i < keyAndRole.length; i += 2) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("subtabKey", keyAndRole[i]);
            item.put("minRole", keyAndRole[i + 1]);
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subtabs", items);
        return put("/api/v1/organizations/{slug}/member-subtab-visibility", orgSlug)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    protected void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-8: TX 状態の記録と判定
    // ═════════════════════════════════════════════════════════════════════

    /**
     * spy の実体を呼ぶ。{@code inv.callRealMethod()} の代わりに必ずこれを使うこと。
     *
     * <p>Spring Data の Repository は JDK 動的プロキシなので、{@code @MockitoSpyBean} はインターフェース型の
     * mock を作り、既定の Answer を {@code AdditionalAnswers.delegatesTo(元の Bean)} にする
     * （spring-test 6.2 {@code MockitoSpyBeanOverrideHandler#createSpy} の {@code Proxy.isProxyClass} 分岐）。
     * この mock で {@code callRealMethod()} を呼ぶと、抽象メソッドの実体が無いため
     * {@code MockitoException("Cannot call abstract real method")} になる（前例:
     * {@code MonthlyShiftBudgetCloseTransactionIT}）。mock 生成時の既定 Answer に委ねれば、
     * Repository は元の Bean へ、クラスの spy は実メソッドへ、どちらも正しく届く。</p>
     */
    protected static Object callReal(InvocationOnMock inv) throws Throwable {
        return Mockito.mockingDetails(inv.getMock()).getMockCreationSettings().getDefaultAnswer().answer(inv);
    }

    /** 呼ばれた時点の TX 状態を {@link #observations} に積んでから実体を呼ぶ Answer。 */
    protected Answer<Object> observing(String targetFqcn) {
        return inv -> {
            observations.add(new TxObservation(targetFqcn, inv.getMethod().getName(),
                    TransactionSynchronizationManager.isActualTransactionActive(),
                    TransactionSynchronizationManager.getCurrentTransactionName()));
            return callReal(inv);
        };
    }

    /** 閲覧・更新経路が使う権限確認と名前解決の全メソッドに {@link #observing} を仕込む。 */
    protected void observeAccessControlAndNameResolver() {
        stubAccessControl(observing(ACS_FQCN));
        doAnswer(observing(NRS_FQCN)).when(nameResolverService).resolveUserDisplayNames(any());
    }

    /** 閲覧・更新経路が呼ぶ {@link AccessControlService} のメソッドすべてに同じ Answer を仕込む。 */
    protected void stubAccessControl(Answer<Object> answer) {
        doAnswer(answer).when(accessControlService).isSystemAdmin(any());
        doAnswer(answer).when(accessControlService).isAdminOrAbove(any(), any(), any());
        doAnswer(answer).when(accessControlService).isAdmin(any(), any(), any());
        doAnswer(answer).when(accessControlService).isMember(any(), any(), any());
        doAnswer(answer).when(accessControlService).hasRoleOrAbove(any(), any(), any(), any());
        doAnswer(answer).when(accessControlService).getRoleName(any(), any(), any());
        doAnswer(answer).when(accessControlService).checkMembership(any(), any(), any());
        doAnswer(answer).when(accessControlService).checkPermission(any(), any(), any(), any());
    }

    /**
     * AC-8 の判定（軍議書 第3版で厳格化）。記録が1件以上あり、かつ各記録が次のどちらかを満たすこと。
     * <ul>
     *   <li>{@code active == false}</li>
     *   <li>{@code active == true} かつ TX 名が許可リスト（common の {@link AccessControlService} /
     *       {@link NameResolverService} が自分で張った TX の名前 = {@code <FQCN>.<メソッド名>}）のどれかと完全一致</li>
     * </ul>
     * 名前の無い実 TX（null）や、許可リスト外の名前（member の TX 等）は失敗とする。
     */
    protected static void assertOutsideMemberTx(List<TxObservation> recorded) {
        if (recorded.isEmpty()) {
            throw new AssertionError("権限確認・名前解決の記録が0件（経路が権限確認を通っていない／spy が効いていない）");
        }
        Set<String> allowed = allowedCommonTxNames();
        List<TxObservation> violations = recorded.stream()
                .filter(TxObservation::active)
                .filter(o -> o.name() == null || !allowed.contains(o.name()))
                .toList();
        if (!violations.isEmpty()) {
            throw new AssertionError("権限確認・名前解決が member（または名前の無い）TX の中で走っている: " + violations);
        }
    }

    private static Set<String> allowedCommonTxNames() {
        return Stream.of(AccessControlService.class, NameResolverService.class)
                .flatMap(c -> Arrays.stream(c.getDeclaredMethods())
                        .filter(m -> Modifier.isPublic(m.getModifiers()))
                        .map(Method::getName)
                        .map(n -> c.getName() + "." + n))
                .collect(Collectors.toUnmodifiableSet());
    }

    // ═════════════════════════════════════════════════════════════════════
    // 監査・event-pool
    // ═════════════════════════════════════════════════════════════════════

    protected ThreadPoolTaskExecutor eventPool() {
        return (ThreadPoolTaskExecutor) eventPoolExecutor;
    }

    /** event-pool の active と queue が両方 0 になるまで待つ（非同期の監査の書き込みを取りこぼさない）。 */
    protected void awaitEventPoolIdle() {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(50)).until(() ->
                eventPool().getActiveCount() == 0
                        && eventPool().getThreadPoolExecutor().getQueue().isEmpty());
    }

    protected long countAuditRows() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE event_type = ? AND organization_id = ?",
                Long.class, AUDIT_EVENT_TYPE, orgId);
        return n == null ? 0 : n;
    }

    /** 新設ハンドラ（{@link #NEW_AUDIT_HANDLER}）が spy 上で呼ばれた回数。名前で数える。 */
    protected long newAuditHandlerInvocations() {
        return Mockito.mockingDetails(auditLogEventListener).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals(NEW_AUDIT_HANDLER))
                .count();
    }

    protected long recordSyncInvocations() {
        return Mockito.mockingDetails(auditLogService).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("recordSync"))
                .filter(inv -> AUDIT_EVENT_TYPE.equals(inv.getArguments()[0]))
                .count();
    }

    protected long countSubtabRows() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM member_subtab_role_visibility WHERE scope_type = 'ORGANIZATION' AND scope_id = ?",
                Long.class, orgId);
        return n == null ? 0 : n;
    }

    protected String subtabMinRole(String subtabKey) {
        List<String> rows = jdbc.queryForList(
                "SELECT min_role FROM member_subtab_role_visibility "
                        + "WHERE scope_type = 'ORGANIZATION' AND scope_id = ? AND subtab_key = ?",
                String.class, orgId, subtabKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 「紹介」サブタブの設定行を1件保存する（Repository 自身の TX でコミット）。 */
    protected void setProfilesSubtab(MinRole minRole) {
        subtabRepository.save(MemberSubtabRoleVisibilityEntity.builder()
                .scopeType(com.mannschaft.app.dashboard.ScopeType.ORGANIZATION)
                .scopeId(orgId)
                .subtabKey(MemberSubtabKey.MEMBER_PROFILES.getDbValue())
                .minRole(minRole)
                .updatedBy(admId)
                .build());
        Mockito.clearInvocations(subtabRepository);
    }

    // ═════════════════════════════════════════════════════════════════════
    // フィクスチャ
    // ═════════════════════════════════════════════════════════════════════

    protected void inNewTx(Runnable action) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> action.run());
    }

    private void seedRole(String name) {
        jdbc.update("INSERT IGNORE INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                + "VALUES (?, ?, ?, 1, NOW(), NOW())", name, name, RolePriority.priority(name));
    }

    private Long insertUser(String label) {
        String email = "mtx-" + label + "-" + nonce + "@example.com";
        jdbc.update("INSERT INTO users (email, last_name, first_name, display_name, status, "
                + "is_searchable, handle_searchable, contact_approval_required, "
                + "online_visibility, dm_receive_from, encryption_key_version, "
                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                + "care_notification_enabled, offline_only, created_at, updated_at) "
                + "VALUES (?, 'MTX', ?, ?, 'ACTIVE', 1, 1, 1, "
                + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())",
                email, label, "MTX " + label);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private Long insertPage(Long organizationId, Long pageTeamId, String title, String status, String visibility) {
        String slug = "mtx-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO team_pages (organization_id, team_id, title, slug, page_type, visibility, status, "
                        + "allow_self_edit, sort_order, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'YEARLY', ?, ?, 0, 0, NOW(), NOW())",
                organizationId, pageTeamId, title, slug, visibility, status);
        return jdbc.queryForObject("SELECT id FROM team_pages WHERE slug = ?", Long.class, slug);
    }
}
