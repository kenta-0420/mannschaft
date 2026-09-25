package com.mannschaft.app.notification.confirmable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutWorker;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-260920-1040是正（差し戻し・⚔️足軽15） F04.9「宛先指定」戦役の一気通貫（送信API→配信ジョブ→
 * ワーカー→受信者）試練。
 *
 * <p>CI は確認通知のテストが全件 green になったが、試練の red テストが部品（chunk sink・展開の
 * ソース）を直接呼ぶものばかりで、送信APIからワーカーまでを実際に通すテストが1本も無かった。
 * 本クラスは、実際の送信 API（MockMvc・実の Security/認可）で送信し、そのあと実際の
 * {@link NotificationFanoutWorker#processReady()} を呼んで配信を最後まで走らせ、DBを数えて検証する。</p>
 *
 * <h2>E2E ↔ 検出済み欠陥の対応</h2>
 * <ul>
 *   <li>E2E-1: 組織スコープ既定送信・純SUPPORTER除外・MEMBER兼SUPPORTERは含む
 *       → ジョブの sourceId 欠落（P1-1）・includeSupporters=true 誤り（殿の検出）で red</li>
 *   <li>E2E-2: 501人（チャンク500を跨ぐ）でも全員に届く → 同上の理由で red</li>
 *   <li>E2E-3: 宛先グループ登録後にチームが組織を離脱すると、そのチームには届かない
 *       → §8.1 の送信組織ツリー未検証（P1-2）で red</li>
 *   <li>E2E-4: 子組織ターゲット自身が受付後に別の親へ移ると、その配下には届かない
 *       → §8.1 の送信組織ツリー未検証（P1-2）で red</li>
 *   <li>E2E-5: 退会者を含む受信者一覧が500ではなく200を返す
 *       → user.* のLAZY読み取りが退会者でEntityNotFoundExceptionになり500化（家老の検出）で red</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("確認通知 一気通貫試練（送信API→配信ジョブ→ワーカー→受信者・CMP-260920-1040是正）")
class ConfirmableNotificationEndToEndDeliveryIT extends AbstractMySqlIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private NotificationFanoutWorker worker;
    @Autowired
    private ConfirmableNotificationRepository notificationRepository;
    @Autowired
    private ConfirmableNotificationRecipientRepository recipientRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private TeamOrgMembershipRepository teamOrgMembershipRepository;
    @Autowired
    private MembershipRepository membershipRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRoleRepository userRoleRepository;
    @Autowired
    private JdbcTemplate jdbc;

    // =====================================================================
    // E2E-1: 組織スコープ既定送信。純SUPPORTERは除外、MEMBER兼SUPPORTERは含む。
    // =====================================================================
    @Test
    @DisplayName("E2E-1: 組織スコープ既定送信は配下全員に届き、純SUPPORTERは除外・MEMBER兼SUPPORTERは含む")
    void e2e1_orgDefaultSendDeliversToDescendantsExcludingPureSupporters() throws Exception {
        long org = insertOrganization(null);
        long child = insertOrganization(org);
        long grandchild = insertOrganization(child);
        long team = insertTeam();
        seedTeamOrgMembership(team, grandchild);

        long sender = insertUser();
        grantOrgAdmin(sender, org);
        long uRoot = insertUser();
        insertMembership(uRoot, "ORGANIZATION", org, "MEMBER");
        long uChild = insertUser();
        insertMembership(uChild, "ORGANIZATION", child, "MEMBER");
        long uTeam = insertUser();
        insertMembership(uTeam, "TEAM", team, "MEMBER");
        long uPureSupporter = insertUser();
        insertMembership(uPureSupporter, "ORGANIZATION", org, "SUPPORTER");
        long uMemberAndSupporter = insertUser();
        insertMembership(uMemberAndSupporter, "ORGANIZATION", org, "SUPPORTER");
        insertMembership(uMemberAndSupporter, "ORGANIZATION", org, "MEMBER");

        setAuth(sender);
        Long notificationId = sendAndAccept(org, Map.of("title", "E2E-1 一気通貫"));

        runWorkerUntilDone(notificationId);

        List<Long> recipients = jdbc.queryForList(
                "SELECT user_id FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?",
                Long.class, notificationId);
        assertThat(recipients)
                .as("E2E-1: 配下全員に届き、純SUPPORTERは除外、MEMBER兼SUPPORTERは含む")
                .containsExactlyInAnyOrder(uRoot, uChild, uTeam, uMemberAndSupporter)
                .doesNotContain(sender, uPureSupporter);

        int notifCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE source_type = 'CONFIRMABLE_NOTIFICATION' AND source_id = ?",
                Integer.class, notificationId);
        int emailCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM email_outbox WHERE source_domain = 'CONFIRMABLE_NOTIFICATION' "
                        + "AND source_event_id = ?",
                Integer.class, String.valueOf(notificationId));
        assertThat(notifCount).as("E2E-1: notifications行がちょうど受信者数ぶん").isEqualTo(4);
        assertThat(emailCount).as("E2E-1: email outboxがちょうど受信者数ぶん").isEqualTo(4);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT delivery_status, total_recipient_count, unconfirmed_count "
                        + "FROM confirmable_notifications WHERE id = ?", notificationId);
        assertThat(row.get("delivery_status")).as("E2E-1: DELIVEREDになる").isEqualTo("DELIVERED");
        assertThat(((Number) row.get("total_recipient_count")).intValue())
                .as("E2E-1: total_recipient_countが受信者数に一致").isEqualTo(4);
        assertThat(((Number) row.get("unconfirmed_count")).intValue())
                .as("E2E-1: unconfirmed_countが受信者数に一致").isEqualTo(4);
    }

    // =====================================================================
    // E2E-2: 501人（チャンク500を跨ぐ）でも全員に届く。
    // =====================================================================
    @Test
    @DisplayName("E2E-2: 受信者が501人でもチャンク境界を跨いで全員に届く")
    void e2e2_deliversToAllRecipientsAcrossChunkBoundary() throws Exception {
        long org = insertOrganization(null);
        long sender = insertUser();
        grantOrgAdmin(sender, org);

        List<Long> members = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            long u = insertUser();
            insertMembership(u, "ORGANIZATION", org, "MEMBER");
            members.add(u);
        }

        setAuth(sender);
        Long notificationId = sendAndAccept(org, Map.of("title", "E2E-2 501人チャンク境界"));

        runWorkerUntilDone(notificationId);

        Integer recipientCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?",
                Integer.class, notificationId);
        assertThat(recipientCount).as("E2E-2: 501人全員に届く（送信者は除く）").isEqualTo(501);

        List<Long> recipientUserIds = jdbc.queryForList(
                "SELECT user_id FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?",
                Long.class, notificationId);
        assertThat(recipientUserIds).as("E2E-2: 501人全員が実際に含まれる")
                .containsExactlyInAnyOrderElementsOf(members);
    }

    // =====================================================================
    // E2E-3: 宛先グループ（TEAMターゲット）登録後、そのチームが別の組織へ移ると届かない。
    // =====================================================================
    @Test
    @DisplayName("E2E-3: 受付後にターゲットのチームが送信組織のツリーから離脱すると、そのチームには届かない")
    void e2e3_teamTargetLeavesSendOrgTreeBeforeProcessingIsExcluded() throws Exception {
        long org = insertOrganization(null);
        long otherOrg = insertOrganization(null);
        long team = insertTeam();
        seedTeamOrgMembership(team, org);

        long sender = insertUser();
        grantOrgAdmin(sender, org);
        long uOrgDirect = insertUser();
        insertMembership(uOrgDirect, "ORGANIZATION", org, "MEMBER");
        long uTeamMember = insertUser();
        insertMembership(uTeamMember, "TEAM", team, "MEMBER");

        setAuth(sender);
        Long notificationId = sendAndAccept(org, Map.of(
                "title", "E2E-3 チーム離脱",
                "targets", List.of(target("ORGANIZATION", org), target("TEAM", team))));

        // 受付後・ワーカー処理前にチームが送信組織のツリーから離脱する（別組織へACTIVE所属を切り替え）。
        jdbc.update("DELETE FROM team_org_memberships WHERE team_id = ? AND organization_id = ?", team, org);
        jdbc.update("INSERT INTO team_org_memberships (team_id, organization_id, status, created_at, updated_at) "
                + "VALUES (?, ?, 'ACTIVE', NOW(), NOW())", team, otherOrg);

        runWorkerUntilDone(notificationId);

        List<Long> recipients = jdbc.queryForList(
                "SELECT user_id FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?",
                Long.class, notificationId);
        assertThat(recipients)
                .as("E2E-3: 離脱したチームのメンバーには届かないが、組織直属のメンバーには届く")
                .containsExactly(uOrgDirect)
                .doesNotContain(uTeamMember);
    }

    // =====================================================================
    // E2E-4: ORGANIZATION(子組織)ターゲット自身が受付後に別の親組織へ移ると届かない。
    // =====================================================================
    @Test
    @DisplayName("E2E-4: 受付後に子組織ターゲット自身が別の親組織へ移ると、その配下には届かない")
    void e2e4_organizationTargetReparentedBeforeProcessingIsExcluded() throws Exception {
        long root = insertOrganization(null);
        long otherRoot = insertOrganization(null);
        long child = insertOrganization(root);

        long sender = insertUser();
        grantOrgAdmin(sender, root);
        long uRoot = insertUser();
        insertMembership(uRoot, "ORGANIZATION", root, "MEMBER");
        long uChild = insertUser();
        insertMembership(uChild, "ORGANIZATION", child, "MEMBER");

        setAuth(sender);
        Long notificationId = sendAndAccept(root, Map.of(
                "title", "E2E-4 子組織ターゲット移動",
                "targets", List.of(target("ORGANIZATION", root), target("ORGANIZATION", child))));

        // 受付後・ワーカー処理前に child 自身が別の親（otherRoot）へ移る。
        jdbc.update("UPDATE organizations SET parent_organization_id = ? WHERE id = ?", otherRoot, child);

        runWorkerUntilDone(notificationId);

        List<Long> recipients = jdbc.queryForList(
                "SELECT user_id FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?",
                Long.class, notificationId);
        assertThat(recipients)
                .as("E2E-4: 親が変わったchildの配下には届かない。root直属は届く")
                .containsExactly(uRoot)
                .doesNotContain(uChild);
    }

    // =====================================================================
    // E2E-5: 退会者を含む受信者一覧は500ではなく200で返り、退会者は「退会した」ことが分かる形で返る。
    // =====================================================================
    @Test
    @DisplayName("E2E-5: 受信者に退会者が含まれても /recipients と /recipients/page は500ではなく200を返す")
    void e2e5_withdrawnRecipientDoesNotCause500() throws Exception {
        long org = insertOrganization(null);
        long sender = insertUser();
        grantOrgAdmin(sender, org);
        long uStaying = insertUser();
        insertMembership(uStaying, "ORGANIZATION", org, "MEMBER");
        long uWithdrawing = insertUser();
        insertMembership(uWithdrawing, "ORGANIZATION", org, "MEMBER");

        setAuth(sender);
        Long notificationId = sendAndAccept(org, Map.of("title", "E2E-5 退会者混在"));

        runWorkerUntilDone(notificationId);

        // 配信完了後に受信者の1人が退会する（論理削除）。
        jdbc.update("UPDATE users SET deleted_at = NOW() WHERE id = ?", uWithdrawing);

        MvcResult fullResult = mockMvc.perform(get(
                        "/api/v1/organizations/{orgId}/confirmable-notifications/{id}/recipients", org, notificationId))
                .andReturn();
        assertThat(fullResult.getResponse().getStatus())
                .as("E2E-5: 全件版 /recipients は退会者を含んでも200（旧実装は500化していた）")
                .isEqualTo(200);
        assertThat(fullResult.getResponse().getContentAsString())
                .as("E2E-5: 退会者の行はwithdrawn:trueで返る")
                .contains("\"withdrawn\":true");

        MvcResult pageResult = mockMvc.perform(get(
                        "/api/v1/organizations/{orgId}/confirmable-notifications/{id}/recipients/page",
                        org, notificationId)
                        .param("page", "0")
                        .param("size", "20"))
                .andReturn();
        assertThat(pageResult.getResponse().getStatus())
                .as("E2E-5: ページング版 /recipients/page も退会者を含んでも200")
                .isEqualTo(200);
        assertThat(pageResult.getResponse().getContentAsString())
                .as("E2E-5: ページング版も退会者の行はwithdrawn:trueで返る")
                .contains("\"withdrawn\":true");

        // 生存している受信者の表示名は引き続き見える（退会者だけをマスクする）。
        List<Long> recipients = jdbc.queryForList(
                "SELECT user_id FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?",
                Long.class, notificationId);
        assertThat(recipients).containsExactlyInAnyOrder(uStaying, uWithdrawing);

        // CMP-260920-1040是正: 確認済み行（confirmed_at あり）を作り、Entity経由で読んだ confirmedAt と
        // ネイティブ投影経由（/recipients のJSON）の値が一致することを検証する（Timestamp→LocalDateTime
        // 変換のタイムゾーンずれが無いことの直接検証。native の列は hibernate.jdbc.time_zone: UTC で
        // UTC として読まれ、toLocalDateTime()でJSTの壁時計になるため、Entity経由の値と一致するはず）。
        jdbc.update(
                "UPDATE confirmable_notification_recipients SET is_confirmed = 1, confirmed_at = NOW(), "
                        + "confirmed_via = 'APP' WHERE confirmable_notification_id = ? AND user_id = ?",
                notificationId, uStaying);

        var confirmedEntity = recipientRepository
                .findByConfirmableNotificationIdAndUserId(notificationId, uStaying)
                .orElseThrow();
        java.time.LocalDateTime entityConfirmedAt = confirmedEntity.getConfirmedAt();
        assertThat(entityConfirmedAt).as("E2E-5: フィクスチャ上、確認済み行のconfirmedAtがEntity経由で読める").isNotNull();

        MvcResult confirmedRowResult = mockMvc.perform(get(
                        "/api/v1/organizations/{orgId}/confirmable-notifications/{id}/recipients", org, notificationId))
                .andReturn();
        assertThat(confirmedRowResult.getResponse().getStatus()).isEqualTo(200);
        var recipientsNode = objectMapper.readTree(confirmedRowResult.getResponse().getContentAsString())
                .path("data");
        String projectionConfirmedAt = null;
        for (var item : recipientsNode) {
            if (item.path("userId").asLong() == uStaying) {
                projectionConfirmedAt = item.path("confirmedAt").asText();
            }
        }
        assertThat(projectionConfirmedAt)
                .as("E2E-5: 射影経由のconfirmedAtがJSONに含まれる")
                .isNotNull();
        // CMP-260920-1040是正（⚔️足軽20）: 送信APIはJSONを瞬間（末尾Zの ISO-8601 UTC instant）で返す。
        // 一方 Entity 経由の confirmedAt は JVM既定タイムゾーン（Asia/Tokyo）の壁時計 LocalDateTime。
        // 両者を「時差9時間のずれ無く同じ瞬間を指しているか」で比較するには、Entity側もInstantへ変換
        // してから比べる必要がある（LocalDateTime同士の比較は文字列のズレを検出できず検証力が落ちる）。
        // 秒未満の精度差（DB列の精度とJSONシリアライズの精度の違い）を吸収するため秒単位に揃える。
        java.time.Instant projectionInstant = java.time.Instant.parse(projectionConfirmedAt)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        java.time.Instant entityInstant = entityConfirmedAt
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        assertThat(projectionInstant)
                .as("E2E-5: ネイティブ投影（API JSON・UTC瞬間）とEntity経由の値（JST壁時計→瞬間変換）が"
                        + "タイムゾーンずれ無く一致する")
                .isEqualTo(entityInstant);
    }

    // =====================================================================
    // E2E-6: MEMBER 視点（getRecipientsForMember）でも退会者を含んで500化せず、
    // withdrawn:true として返る（⚔️足軽16是正）。
    // =====================================================================
    @Test
    @DisplayName("E2E-6: MEMBER視点の/recipientsも退会者を含んで500化せず、200でwithdrawn:trueが返る")
    void e2e6_memberViewWithdrawnRecipientDoesNotCause500() throws Exception {
        long org = insertOrganization(null);
        long sender = insertUser();
        grantOrgAdmin(sender, org);
        long uViewer = insertUser();
        insertMembership(uViewer, "ORGANIZATION", org, "MEMBER");
        long uWithdrawing = insertUser();
        insertMembership(uWithdrawing, "ORGANIZATION", org, "MEMBER");

        setAuth(sender);
        Long notificationId = sendAndAccept(org, Map.of(
                "title", "E2E-6 MEMBER視点退会者混在",
                "unconfirmedVisibility", "ALL_MEMBERS"));

        runWorkerUntilDone(notificationId);

        // 配信完了後に受信者の1人が退会する（論理削除）。uViewer自身は未確認のまま生存させる。
        jdbc.update("UPDATE users SET deleted_at = NOW() WHERE id = ?", uWithdrawing);

        // MEMBER視点（非ADMIN・受信者本人）で /recipients を叩く。
        setAuth(uViewer);
        MvcResult memberResult = mockMvc.perform(get(
                        "/api/v1/organizations/{orgId}/confirmable-notifications/{id}/recipients", org, notificationId))
                .andReturn();
        assertThat(memberResult.getResponse().getStatus())
                .as("E2E-6: MEMBER視点の/recipientsは退会者を含んでも200（是正前は500化していた）")
                .isEqualTo(200);
        assertThat(memberResult.getResponse().getContentAsString())
                .as("E2E-6: MEMBER視点でも退会者の行はwithdrawn:trueで返る")
                .contains("\"withdrawn\":true");

        var memberNode = objectMapper.readTree(memberResult.getResponse().getContentAsString()).path("data");
        assertThat(memberNode.isArray()).as("E2E-6: dataは配列").isTrue();
        for (var item : memberNode) {
            assertThat(item.path("confirmedAt").isNull())
                    .as("E2E-6: MEMBER視点はconfirmedAtを常にNULLマスクする").isTrue();
            assertThat(item.path("confirmedVia").isNull())
                    .as("E2E-6: MEMBER視点はconfirmedViaを常にNULLマスクする").isTrue();
            assertThat(item.path("excludedAt").isNull())
                    .as("E2E-6: MEMBER視点はexcludedAtを常にNULLマスクする").isTrue();
        }
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    /** ワーカーを最大10周回、対象ジョブがDONEになるまで（またはこれ以上進捗しなくなるまで）実行する。 */
    private void runWorkerUntilDone(Long notificationId) {
        for (int i = 0; i < 10; i++) {
            worker.processReady();
            String deliveryStatus = jdbc.queryForObject(
                    "SELECT delivery_status FROM confirmable_notifications WHERE id = ?", String.class,
                    notificationId);
            if ("DELIVERED".equals(deliveryStatus) || "PARTIALLY_FAILED".equals(deliveryStatus)) {
                return;
            }
        }
    }

    private Long sendAndAccept(long orgId, Map<String, Object> body) throws Exception {
        Map<String, Object> fullBody = new LinkedHashMap<>(body);
        fullBody.putIfAbsent("title", "E2E試練-" + SEQ.incrementAndGet());
        MvcResult result = mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fullBody)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("送信APIは202を返す想定（本文=" + result.getResponse().getContentAsString() + "）")
                .isEqualTo(202);
        var node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.path("data").path("id").asLong();
    }

    private Map<String, Object> target(String type, long id) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", type);
        t.put("id", id);
        return t;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /**
     * CMP-260920-1040是正: フィクスチャの投入は素の {@code JdbcTemplate} で行う（{@code EntityManager}
     * のネイティブ更新はアクティブなトランザクションを要求するが、本クラスはクラス単位
     * {@code @Transactional} を持たない。{@code ConfirmableFanoutFixture} と同じ理由で、
     * ワーカーの {@code REQUIRES_NEW} 独立コミットと物理コネクションの奪い合い・自己ロック待ちを
     * 避けるため、テスト本体はトランザクションで包まない）。
     */
    private Long insertUser() {
        int n = SEQ.incrementAndGet();
        String email = "cne2e-" + n + "-" + System.nanoTime() + "@example.com";
        jdbc.update(
                "INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (?, 'E2E', ?, ?, 'ACTIVE', 1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())",
                email, "利用者" + n, "E2E利用者" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    /**
     * CMP-260920-1040是正（⚔️足軽21）: 前例（{@code ConfirmableTargetsFanoutRecipientSourceIT}）に倣い、
     * 生の SQL ではなく {@link OrganizationRepository#save} で作る。生の SQL は Entity の列と
     * 1列でもずれると {@code BadSqlGrammarException} で落ちる（team_org_memberships で実際に発生した）
     * ため、Entity 経由に揃えて同じ穴を塞ぐ。
     */
    private long insertOrganization(Long parentOrgId) {
        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .slug("cne2e-o-" + SEQ.incrementAndGet())
                .name("E2E組織-" + SEQ.get() + "-" + System.nanoTime())
                .orgType(OrganizationEntity.OrgType.OTHER)
                .parentOrganizationId(parentOrgId)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(Boolean.TRUE)
                .build());
        return org.getId();
    }

    /**
     * CMP-260920-1040是正（⚔️足軽21）: 前例に倣い {@link TeamRepository#save} で作る（理由は
     * {@link #insertOrganization(Long)} と同じ）。
     */
    private long insertTeam() {
        TeamEntity team = teamRepository.save(TeamEntity.builder()
                .slug("cne2e-t-" + SEQ.incrementAndGet())
                .name("E2Eチーム-" + SEQ.get() + "-" + System.nanoTime())
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(Boolean.TRUE)
                .build());
        return team.getId();
    }

    /**
     * CMP-260920-1040是正（⚔️足軽21）: {@code team_org_memberships} への生 SQL は
     * {@link TeamOrgMembershipEntity} に無い {@code updated_at} 列を指定しており
     * {@code BadSqlGrammarException} で落ちていた（E2E-1・E2E-3 red の根本原因）。前例
     * （{@code ConfirmableTargetsFanoutRecipientSourceIT#seedTeamOrgMembership}）と同じく
     * {@link TeamOrgMembershipRepository#save} に揃える。
     */
    private void seedTeamOrgMembership(long teamId, long orgId) {
        teamOrgMembershipRepository.save(TeamOrgMembershipEntity.builder()
                .teamId(teamId)
                .organizationId(orgId)
                .status(TeamOrgMembershipEntity.Status.ACTIVE)
                .invitedAt(LocalDateTime.now())
                .build());
    }

    /** CMP-260920-1040是正（⚔️足軽21）: 前例に倣い {@link MembershipRepository#save} で作る。 */
    private void insertMembership(long userId, String scopeType, long scopeId, String roleKind) {
        membershipRepository.save(MembershipEntity.builder()
                .userId(userId)
                .scopeType(ScopeType.valueOf(scopeType))
                .scopeId(scopeId)
                .roleKind(RoleKind.valueOf(roleKind))
                .joinedAt(LocalDateTime.now())
                .build());
    }

    /**
     * CMP-260920-1040是正（⚔️足軽21）: roles/user_roles も {@link RoleRepository}/{@link UserRoleRepository}
     * 経由に揃える。roles は固定ロール名でのべき等シード（前例には roles シードの用例が無いため、
     * {@code findByName}→無ければ {@code save} の find-or-create で「INSERT IGNORE」と同じべき等性を
     * Entity 経由で再現する）。
     */
    private void grantOrgAdmin(long userId, long orgId) {
        RoleEntity adminRole = roleRepository.findByName("ADMIN")
                .orElseGet(() -> roleRepository.save(RoleEntity.builder()
                        .name("ADMIN")
                        .displayName("ADMIN")
                        .priority(2)
                        .isSystem(Boolean.TRUE)
                        .build()));
        userRoleRepository.save(UserRoleEntity.builder()
                .userId(userId)
                .roleId(adminRole.getId())
                .organizationId(orgId)
                .build());
        insertMembership(userId, "ORGANIZATION", orgId, "MEMBER");
    }
}
