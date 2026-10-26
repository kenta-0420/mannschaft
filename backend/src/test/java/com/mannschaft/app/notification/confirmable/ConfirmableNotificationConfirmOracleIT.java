package com.mannschaft.app.notification.confirmable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationQueryService;
import com.mannschaft.app.support.test.MembershipTestHelper;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.concurrent.atomic.AtomicBoolean;
import com.mannschaft.app.notification.confirmable.support.ConfirmableFanoutFixture;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 確認通知の confirm（Team・Org・Me の 3 本）の存在オラクル是正と、拒否経路でロックを取らないこと（K6）の契約テスト
 * （CMP-260923-0954 W3b・試練 / red 先行）。
 *
 * <p>正本: {@code ep_tables_w3b.md} EP6/7・EP8・§6 K5-2・殿の判断5〜6・補足（confirm の順序）、
 * {@code plan1_oracle.md} C1（AC-7b）・AC-7、{@code plan4_tx_facade.md} K6。</p>
 *
 * <h2>EP × 主体の表（NF=CONFIRMABLE_NOTIFICATION_NOT_FOUND / RNF=…_RECIPIENT_NOT_FOUND /
 * AC=…_ALREADY_CANCELLED / ACF=…_ALREADY_CONFIRMED / IT=…_INVALID_TOKEN）</h2>
 * <pre>
 * 主体 × 通知の状態                          | 是正前                  | 是正後
 * 不在ID（状態なし）                          | 404 NF                  | 404 NF
 * 非受信者 × ACTIVE                          | 404 RNF                 | 404 NF（不在と status・code・message 完全一致）
 * 非受信者 × CANCELLED/COMPLETED/EXPIRED     | 409 AC（状態が漏れる）    | 404 NF（AC-7）
 * 除外済み受信者 × ACTIVE                     | 404 RNF                 | 404 NF
 * 除外済み受信者 × 非 ACTIVE                  | 409 AC                  | 404 NF
 * 受信者本人 × ACTIVE（未確認）                | 204                     | 204（パスの teamId/orgId は検証しない・殿の判断6）
 * 受信者本人 × 非 ACTIVE                      | 409 AC                  | 409 AC（本人にだけ見える状態）
 * 確認済み受信者 × ACTIVE                     | 409 ACF                 | 409 ACF
 * 確認済み受信者 × 非 ACTIVE                  | 409 AC                  | 409 AC
 * 非受信者・不在の拒否経路の FOR UPDATE          | 1 回（認可より先に親行）  | 0 回（K6。受信者行をロックなしで先に特定）
 * 別 tx が親行をロック中の非受信者              | ロック待ちで塞がる        | 待たずに 404 NF
 * 受信者本人の許可経路の FOR UPDATE             | 取る                     | 取る（親 → 受信者の順序は維持）
 * POST /public/confirm/{token}（EP8・是正不要）  | 不在/除外 404 IT・非ACTIVE 409 AC・確認済み 409 ACF・成功 200 | 同じ（doConfirm 共有の回帰固定）
 * </pre>
 * <p>パス: Me={@code /api/v1/me/confirmable-notifications/{id}/confirm}、Team（通知の所属チーム・無関係なチーム）、
 * Org（無関係な組織。通知はチームスコープ）。どのパスでも応答は同じであること。</p>
 *
 * <p>ロックと FOR UPDATE の回数を実際に観測するため、<b>{@code @Transactional} を付けず</b>データをコミットする
 * （{@code ShiftTxFacadeRaceAndQueryIT} と同じ方式）。終了時に自分で作った行を消す。FOR UPDATE の回数は
 * Hibernate の {@code StatementInspector}（{@link SqlIntentCounter}・application-test.yml で常時登録）が捕捉した SQL で数える。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("確認通知 confirm の存在オラクル是正・拒否経路のロック（W3b・試練）")
class ConfirmableNotificationConfirmOracleIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING_ID = 987_654_321L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ConfirmableNotificationRepository notificationRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    /** K1: tx 本体（Query サービス）の直前に割り込むための spy（実処理は呼ぶ）。 */
    @MockitoSpyBean
    private ConfirmableNotificationQueryService queryService;
    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx;
    private ExecutorService executor;
    private String emailPrefix;
    private Long recipientId;     // 受信者本人（未確認）
    private Long nonRecipientId;  // 受信者行を持たない
    private Long excludedId;      // 除外済みの受信者行
    private Long confirmedId;     // 確認済みの受信者行
    private Long teamId;          // 通知の所属チーム
    private Long otherTeamId;     // 通知と無関係なチーム
    private Long orgId;           // 通知と無関係な組織
    private final List<Long> notificationIds = new ArrayList<>();
    private final List<Long> extraAdminIds = new ArrayList<>();
    private String adminEmailPrefix;

    enum Path { ME, TEAM_OWN, TEAM_OTHER, ORG_UNRELATED }

    static final List<ConfirmableNotificationStatus> NON_ACTIVE = List.of(
            ConfirmableNotificationStatus.CANCELLED, ConfirmableNotificationStatus.COMPLETED,
            ConfirmableNotificationStatus.EXPIRED);

    static Stream<Arguments> pathsAndStatuses() {
        return Arrays.stream(Path.values()).flatMap(p ->
                Arrays.stream(ConfirmableNotificationStatus.values()).map(s -> Arguments.of(p, s)));
    }

    static Stream<Arguments> pathsAndNonActiveStatuses() {
        return Arrays.stream(Path.values()).flatMap(p -> NON_ACTIVE.stream().map(s -> Arguments.of(p, s)));
    }

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        executor = Executors.newCachedThreadPool();
        emailPrefix = "w3b-confirm-" + UUID.randomUUID().toString().substring(0, 12);
        List<Long> users = ConfirmableFanoutFixture.insertUsers(transactionManager, em, 4, emailPrefix);
        recipientId = users.get(0);
        nonRecipientId = users.get(1);
        excludedId = users.get(2);
        confirmedId = users.get(3);
        tx.executeWithoutResult(s -> {
            teamId = insertTeam();
            otherTeamId = insertTeam();
            orgId = insertOrganization();
        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        SecurityContextHolder.clearContext();
        Mockito.reset(queryService);
        tx.executeWithoutResult(s -> {
            for (Long adminId : extraAdminIds) {
                em.createNativeQuery("DELETE FROM user_roles WHERE user_id = :u").setParameter("u", adminId).executeUpdate();
                em.createNativeQuery("DELETE FROM memberships WHERE user_id = :u").setParameter("u", adminId).executeUpdate();
            }
            for (Long id : notificationIds) {
                em.createNativeQuery("DELETE FROM confirmable_notification_recipients WHERE confirmable_notification_id = :id")
                        .setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM confirmable_notifications WHERE id = :id")
                        .setParameter("id", id).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM teams WHERE id IN (:a, :b)")
                    .setParameter("a", teamId).setParameter("b", otherTeamId).executeUpdate();
            em.createNativeQuery("DELETE FROM organizations WHERE id = :id").setParameter("id", orgId).executeUpdate();
        });
        ConfirmableFanoutFixture.deleteUsers(transactionManager, em, emailPrefix);
        if (adminEmailPrefix != null) {
            ConfirmableFanoutFixture.deleteUsers(transactionManager, em, adminEmailPrefix);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // C1 / AC-7b / AC-7: 非受信者・除外済み受信者は状態を問わず不在と完全一致
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0} × {1}")
    @MethodSource("pathsAndStatuses")
    @DisplayName("C1/AC-7: 非受信者と除外済み受信者の confirm は、通知の状態によらず不在IDと完全一致の404 NOT_FOUND")
    void 非受信者と除外済み受信者は状態によらず不在と同一の404(Path path, ConfirmableNotificationStatus status)
            throws Exception {
        Long notificationId = newNotification(status);
        for (Long actor : List.of(nonRecipientId, excludedId)) {
            setAuth(actor);
            ErrorView real = perform(confirm(path, String.valueOf(notificationId)));
            ErrorView missing = perform(confirm(path, String.valueOf(MISSING_ID)));
            assertThat(missing).isEqualTo(new ErrorView(404, ConfirmableNotificationErrorCode.NOT_FOUND.getCode(),
                    ConfirmableNotificationErrorCode.NOT_FOUND.getMessage()));
            assertThat(real).as("actor=%d の実在通知への応答が不在IDと一致すること（RECIPIENT_NOT_FOUND・409 を出さない）", actor)
                    .isEqualTo(missing);
        }
        assertUnchanged(notificationId, status);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 受信者本人は従来どおり（パスの teamId/orgId は検証しない）
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0}")
    @EnumSource(Path.class)
    @DisplayName("受信者本人 × ACTIVE は、どのパス（無関係なチーム・組織を含む）でも従来どおり204で確認済みになる")
    void 受信者本人はどのパスでも204(Path path) throws Exception {
        Long notificationId = newNotification(ConfirmableNotificationStatus.ACTIVE);
        setAuth(recipientId);
        MvcResult result = mockMvc.perform(confirm(path, String.valueOf(notificationId))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(204);
        assertThat(isConfirmed(notificationId, recipientId)).isTrue();
    }

    @ParameterizedTest(name = "{0} × {1}")
    @MethodSource("pathsAndNonActiveStatuses")
    @DisplayName("受信者本人 × 非ACTIVE は従来どおり409 ALREADY_CANCELLED（本人にだけ見える状態）")
    void 受信者本人の非ACTIVEは409(Path path, ConfirmableNotificationStatus status) throws Exception {
        Long notificationId = newNotification(status);
        setAuth(recipientId);
        assertThat(perform(confirm(path, String.valueOf(notificationId))).code())
                .isEqualTo(ConfirmableNotificationErrorCode.ALREADY_CANCELLED.getCode());
        assertThat(isConfirmed(notificationId, recipientId)).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ConfirmableNotificationStatus.class)
    @DisplayName("確認済み受信者は ACTIVE なら409 ALREADY_CONFIRMED、非ACTIVE なら409 ALREADY_CANCELLED（従来どおり）")
    void 確認済み受信者の応答(ConfirmableNotificationStatus status) throws Exception {
        Long notificationId = newNotification(status);
        setAuth(confirmedId);
        ErrorView view = perform(confirm(Path.ME, String.valueOf(notificationId)));
        assertThat(view.status()).isEqualTo(409);
        assertThat(view.code()).isEqualTo(status == ConfirmableNotificationStatus.ACTIVE
                ? ConfirmableNotificationErrorCode.ALREADY_CONFIRMED.getCode()
                : ConfirmableNotificationErrorCode.ALREADY_CANCELLED.getCode());
    }

    // ═════════════════════════════════════════════════════════════════════
    // K6: 拒否経路で FOR UPDATE を取らない
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ConfirmableNotificationStatus.class, names = {"ACTIVE", "CANCELLED"})
    @DisplayName("K6: 非受信者の confirm と不在IDの confirm では FOR UPDATE が1回も発行されない")
    void 拒否経路でFORUPDATEが0回(ConfirmableNotificationStatus status) throws Exception {
        Long notificationId = newNotification(status);
        setAuth(nonRecipientId);
        for (String id : List.of(String.valueOf(notificationId), String.valueOf(MISSING_ID))) {
            SqlIntentCounter.reset();
            int st = mockMvc.perform(confirm(Path.ME, id)).andReturn().getResponse().getStatus();
            assertThat(st).isGreaterThanOrEqualTo(400);
            assertThat(forUpdateCount()).as("id=%s の拒否経路の FOR UPDATE 回数", id).isZero();
        }
    }

    @Test
    @DisplayName("K6（検出器の自己検証）: 受信者本人の許可経路では FOR UPDATE が発行される（親 → 受信者のロック順は維持）")
    void 許可経路ではFORUPDATEを取る() throws Exception {
        Long notificationId = newNotification(ConfirmableNotificationStatus.ACTIVE);
        setAuth(recipientId);
        SqlIntentCounter.reset();
        assertThat(mockMvc.perform(confirm(Path.ME, String.valueOf(notificationId))).andReturn()
                .getResponse().getStatus()).isEqualTo(204);
        List<String> locks = SqlIntentCounter.capturedSqls().stream()
                .map(String::toLowerCase).filter(s -> s.contains("for update")).toList();
        assertThat(locks).as("許可経路は親と受信者の行を FOR UPDATE する").hasSizeGreaterThanOrEqualTo(2);
        assertThat(locks.get(0)).as("最初のロックは親（confirmable_notifications）")
                .contains("confirmable_notifications").doesNotContain("confirmable_notification_recipients");
    }

    @Test
    @DisplayName("K6: 別 tx が親の行を FOR UPDATE で握っていても、非受信者は待たずに不在と同一の404")
    void 親行ロック中でも非受信者は待たずに404() throws Exception {
        Long notificationId = newNotification(ConfirmableNotificationStatus.ACTIVE);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            notificationRepository.findByIdForUpdate(notificationId).orElseThrow();
            locked.countDown();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }), executor);
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            // 非受信者が FOR UPDATE を取りに行けば、保持中の行ロックに塞がれて 8 秒以内に返らない。
            ErrorView view = assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                setAuth(nonRecipientId);
                return perform(confirm(Path.ME, String.valueOf(notificationId)));
            });
            assertThat(view).isEqualTo(new ErrorView(404, ConfirmableNotificationErrorCode.NOT_FOUND.getCode(),
                    ConfirmableNotificationErrorCode.NOT_FOUND.getMessage()));
        } finally {
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
    }


    // ═════════════════════════════════════════════════════════════════════
    // K1: 認可の後・tx 本体の前に通知が消えても、不在IDと同一の404（recipients/page・ファサード化）
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Path.class, names = {"TEAM_OWN", "ORG_UNRELATED"})
    @DisplayName("K1: recipients/page で認可の後・tx 本体の前に通知が削除されても、不在IDと status・code・message 完全一致の404")
    void 認可後tx前に通知が消えても不在と同一の404(Path path) throws Exception {
        boolean org = path == Path.ORG_UNRELATED;
        adminEmailPrefix = "w3b-k1-" + UUID.randomUUID().toString().substring(0, 12);
        Long adminId = ConfirmableFanoutFixture.insertUsers(transactionManager, em, 1, adminEmailPrefix).get(0);
        extraAdminIds.add(adminId);
        Long scopeId = org ? orgId : teamId;
        com.mannschaft.app.membership.domain.ScopeType scope = org
                ? com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION
                : com.mannschaft.app.membership.domain.ScopeType.TEAM;
        tx.executeWithoutResult(s -> {
            MembershipTestHelper.insertMembership(em, adminId, scope, scopeId,
                    com.mannschaft.app.membership.domain.RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", org ? null : teamId, org ? orgId : null);
        });
        Long notificationId = newScopedNotification(org ? ScopeType.ORGANIZATION : ScopeType.TEAM, scopeId);
        String base = org ? "/api/v1/organizations/" + orgId : "/api/v1/teams/" + teamId;
        setAuth(adminId);

        ErrorView missing = perform(get(base + "/confirmable-notifications/" + MISSING_ID + "/recipients/page"));
        assertThat(missing).isEqualTo(new ErrorView(404, ConfirmableNotificationErrorCode.NOT_FOUND.getCode(),
                ConfirmableNotificationErrorCode.NOT_FOUND.getMessage()));

        // 認可（ファサード）を通過した直後 = tx 本体（Query サービス）へ入る直前に、通知を物理削除する。
        AtomicBoolean hookRan = new AtomicBoolean();
        Mockito.doAnswer(inv -> {
            hookRan.set(true);
            deleteNotification(notificationId);
            return inv.callRealMethod();
        }).when(queryService).getRecipientsPage(
                Mockito.eq(notificationId), Mockito.any(), Mockito.anyBoolean(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyBoolean());

        ErrorView raced = perform(get(base + "/confirmable-notifications/" + notificationId + "/recipients/page"));
        assertThat(hookRan).as("認可の後・tx 本体の前の割り込みが実際に走ったこと（空振りでない）").isTrue();
        assertThat(raced).as("認可後に消えた通知は不在IDと完全一致").isEqualTo(missing);
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP8: トークン経由（是正不要・doConfirm 共有の回帰固定）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("EP8: トークン経由は従来どおり（不在・除外は404 INVALID_TOKEN、非ACTIVEは409 ALREADY_CANCELLED、確認済みは409 ALREADY_CONFIRMED、成功は200）")
    void トークン経由の応答は変えない() throws Exception {
        Long active = newNotification(ConfirmableNotificationStatus.ACTIVE);
        Long cancelled = newNotification(ConfirmableNotificationStatus.CANCELLED);
        SecurityContextHolder.clearContext();
        String invalid = ConfirmableNotificationErrorCode.INVALID_TOKEN.getCode();
        assertThat(perform(token(UUID.randomUUID().toString()))).extracting(ErrorView::status, ErrorView::code)
                .containsExactly(404, invalid);
        assertThat(perform(token(tokenOf(active, excludedId)))).extracting(ErrorView::status, ErrorView::code)
                .containsExactly(404, invalid);
        assertThat(perform(token(tokenOf(cancelled, recipientId)))).extracting(ErrorView::status, ErrorView::code)
                .containsExactly(409, ConfirmableNotificationErrorCode.ALREADY_CANCELLED.getCode());
        assertThat(perform(token(tokenOf(active, confirmedId)))).extracting(ErrorView::status, ErrorView::code)
                .containsExactly(409, ConfirmableNotificationErrorCode.ALREADY_CONFIRMED.getCode());
        assertThat(mockMvc.perform(token(tokenOf(active, recipientId))).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(isConfirmed(active, recipientId)).isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private MockHttpServletRequestBuilder confirm(Path path, String id) {
        return switch (path) {
            case ME -> post("/api/v1/me/confirmable-notifications/" + id + "/confirm");
            case TEAM_OWN -> post("/api/v1/teams/" + teamId + "/confirmable-notifications/" + id + "/confirm");
            case TEAM_OTHER -> post("/api/v1/teams/" + otherTeamId + "/confirmable-notifications/" + id + "/confirm");
            case ORG_UNRELATED -> post("/api/v1/organizations/" + orgId + "/confirmable-notifications/" + id + "/confirm");
        };
    }

    private MockHttpServletRequestBuilder token(String token) {
        return post("/api/v1/public/confirm/" + token);
    }

    private static long forUpdateCount() {
        return SqlIntentCounter.capturedSqls().stream()
                .filter(s -> s.toLowerCase().contains("for update"))
                .count();
    }

    /** チームスコープの通知を作り、受信者本人（未確認）・除外済み・確認済みの 3 行を付けてコミットする。 */
    private Long newNotification(ConfirmableNotificationStatus status) {
        Long id = tx.execute(s -> {
            ConfirmableNotificationEntity n = notificationRepository.save(ConfirmableNotificationEntity.builder()
                    .scopeType(ScopeType.TEAM)
                    .scopeId(teamId)
                    .title("W3B confirm " + status)
                    .priority(ConfirmableNotificationPriority.NORMAL)
                    .status(status)
                    .totalRecipientCount(3)
                    .unconfirmedCount(1)
                    .build());
            persistRecipient(n.getId(), recipientId, false, false);
            persistRecipient(n.getId(), excludedId, false, true);
            persistRecipient(n.getId(), confirmedId, true, false);
            return n.getId();
        });
        notificationIds.add(id);
        return id;
    }

    private Long newScopedNotification(ScopeType scopeType, Long scopeId) {
        Long id = tx.execute(s -> notificationRepository.save(ConfirmableNotificationEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .title("W3B K1 " + scopeType)
                .priority(ConfirmableNotificationPriority.NORMAL)
                .status(ConfirmableNotificationStatus.ACTIVE)
                .totalRecipientCount(0)
                .unconfirmedCount(0)
                .build()).getId());
        notificationIds.add(id);
        return id;
    }

    private void deleteNotification(Long id) {
        // spy は tx プロキシの内側で呼ばれるため、呼び出し元は readOnly tx の中にいる。
        // 削除は別 tx（REQUIRES_NEW）で確実にコミットする。
        TransactionTemplate independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        independent.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM confirmable_notification_recipients WHERE confirmable_notification_id = :id")
                    .setParameter("id", id).executeUpdate();
            em.createNativeQuery("DELETE FROM confirmable_notifications WHERE id = :id")
                    .setParameter("id", id).executeUpdate();
        });
    }

    private void persistRecipient(Long notificationId, Long userId, boolean confirmed, boolean excluded) {
        em.persist(ConfirmableNotificationRecipientEntity.builder()
                .confirmableNotification(em.getReference(ConfirmableNotificationEntity.class, notificationId))
                .user(em.getReference(com.mannschaft.app.auth.entity.UserEntity.class, userId))
                .confirmToken(UUID.randomUUID().toString())
                .isConfirmed(confirmed)
                .excludedAt(excluded ? java.time.LocalDateTime.now() : null)
                .build());
    }

    private String tokenOf(Long notificationId, Long userId) {
        return tx.execute(s -> (String) em.createNativeQuery(
                        "SELECT confirm_token FROM confirmable_notification_recipients "
                                + "WHERE confirmable_notification_id = :n AND user_id = :u")
                .setParameter("n", notificationId).setParameter("u", userId).getSingleResult());
    }

    private boolean isConfirmed(Long notificationId, Long userId) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            Object v = em.createNativeQuery(
                            "SELECT is_confirmed FROM confirmable_notification_recipients "
                                    + "WHERE confirmable_notification_id = :n AND user_id = :u")
                    .setParameter("n", notificationId).setParameter("u", userId).getSingleResult();
            return v instanceof Boolean b ? b : ((Number) v).intValue() == 1;
        }));
    }

    /** 拒否された confirm で、通知の状態・未確認カウンタ・受信者の確認状態が変わっていないこと。 */
    private void assertUnchanged(Long notificationId, ConfirmableNotificationStatus status) {
        Object[] row = tx.execute(s -> (Object[]) em.createNativeQuery(
                        "SELECT status, unconfirmed_count, "
                                + "(SELECT COUNT(*) FROM confirmable_notification_recipients r "
                                + " WHERE r.confirmable_notification_id = n.id AND r.is_confirmed = 1) "
                                + "FROM confirmable_notifications n WHERE n.id = :id")
                .setParameter("id", notificationId).getSingleResult());
        assertThat(row[0]).isEqualTo(status.name());
        assertThat(((Number) row[1]).intValue()).isEqualTo(1);
        assertThat(((Number) row[2]).intValue()).as("確認済みは元の 1 行のまま").isEqualTo(1);
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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

    private Long insertTeam() {
        String name = "W3B confirm team " + UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('w3bc-', LEFT(REPLACE(UUID(),'-',''),10)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private Long insertOrganization() {
        String name = "W3B confirm org " + UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('w3bc-', LEFT(REPLACE(UUID(),'-',''),10)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }
}
