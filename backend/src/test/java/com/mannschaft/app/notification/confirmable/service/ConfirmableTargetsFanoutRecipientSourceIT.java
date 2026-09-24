package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationTargetEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableTargetType;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationTargetRepository;
import com.mannschaft.app.notification.fanout.FanoutPageRequest;
import com.mannschaft.app.notification.fanout.FanoutRecipient;
import com.mannschaft.app.notification.fanout.FanoutRecipientSource;
import com.mannschaft.app.notification.fanout.FanoutRecipientSourceRegistry;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260920-1040 F04.9「宛先指定」戦役 — 試練A補完（AC-1〜7 宛先の展開）。
 *
 * <p>軍議第8版確定稿 §3.2「受信者の展開（件数に依存しない）」・§4「宛先の既定と展開」を対象とする。
 * {@link ConfirmableTargetsFanoutRecipientSource} は骨格段階では
 * {@code UnsupportedOperationException} を投げるスタブのため、本クラスの全テストは
 * 出陣（green化）まで red のまま失敗する。</p>
 *
 * <h2>AC ↔ テスト対応</h2>
 * <ul>
 *   <li>AC-1 組織スコープ既定（自組織＋子孫組織＋配下ACTIVEチーム。孫組織配下も含む）
 *       → {@link #ac1_orgDefaultExpandsFullTree()}</li>
 *   <li>AC-2 PENDING所属チーム・離脱チーム・退会者・status≠ACTIVE・left_atあり・純SUPPORTERは除外
 *       （MEMBER兼SUPPORTERは含む） → {@link #ac2_excludesNonQualifyingMembers()}</li>
 *   <li>AC-3 送信者本人は受信者にならない → {@link #ac3_senderExcluded()}</li>
 *   <li>AC-4 重複所属者はちょうど1行 → {@link #ac4_overlappingMembershipDeduped()}</li>
 *   <li>AC-5 targets=[TEAM(a), ORGANIZATION(child)] の和集合 → {@link #ac5_mixedTargetsUnion()}</li>
 *   <li>AC-6 チームスコープ既定は自チーム在籍メンバー（送信者除く） → {@link #ac6_teamDefaultScope()}</li>
 *   <li>AC-7 宛先グループ経由の展開はグループ登録後の加入者も含む
 *       → {@link #ac7_groupExpandsAtSendTime()}（グループ解決の骨格未実装のため
 *       ターゲット直接展開の等価シナリオとして固定する）</li>
 * </ul>
 */
@DisplayName("ConfirmableTargetsFanoutRecipientSource 試練（AC-1〜7 宛先の展開）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ConfirmableTargetsFanoutRecipientSourceIT extends AbstractMySqlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConfirmableTargetsFanoutRecipientSourceIT.class);

    private static final int LARGE_LIMIT = 1_000;
    private static final AtomicInteger SLUG_SEQ = new AtomicInteger(0);

    @Autowired
    private ConfirmableTargetsFanoutRecipientSource source;
    @Autowired
    private FanoutRecipientSourceRegistry registry;
    @Autowired
    private ConfirmableNotificationTargetRepository targetRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private TeamOrgMembershipRepository teamOrgMembershipRepository;
    @Autowired
    private UserRoleRepository userRoleRepository;
    @Autowired
    private MembershipRepository membershipRepository;
    @Autowired
    private JdbcTemplate jdbc;

    // =====================================================================
    // Registry 解決の土台（AC-1 の前提）
    // =====================================================================
    @Test
    @DisplayName("Registry は scope_type=\"CONFIRMABLE_TARGETS\" で ConfirmableTargetsFanoutRecipientSource を解決する")
    void registryResolvesConfirmableTargetsSource() {
        Optional<FanoutRecipientSource> resolved = registry.resolve(ConfirmableTargetsFanoutRecipientSource.SCOPE_TYPE);

        assertThat(resolved).as("Registry は \"CONFIRMABLE_TARGETS\" を解決できる").isPresent();
        assertThat(resolved.get()).isInstanceOf(ConfirmableTargetsFanoutRecipientSource.class);
    }

    // =====================================================================
    // AC-1 組織スコープ既定: 自組織＋子孫組織（孫まで）＋配下ACTIVEチームの直属メンバー
    // =====================================================================
    @Test
    @DisplayName("AC-1 ORGANIZATION(id) は自組織・子孫組織（孫まで）・配下ACTIVEチームの全員を展開する")
    void ac1_orgDefaultExpandsFullTree() {
        long seed = 41_001L;
        long root = createOrg(null);
        long child = createOrg(root);
        long grandchild = createOrg(child);
        long teamUnderGrandchild = 88_001L;
        seedTeamOrgMembership(teamUnderGrandchild, grandchild, TeamOrgMembershipEntity.Status.ACTIVE);

        long uRoot = base(seed) + 1;
        long uChild = base(seed) + 2;
        long uGrandchild = base(seed) + 3;
        long uTeam = base(seed) + 4;
        long sender = base(seed) + 99;
        seedOrgDirectMember(root, uRoot);
        seedOrgDirectMember(child, uChild);
        seedOrgDirectMember(grandchild, uGrandchild);
        seedTeamMember(teamUnderGrandchild, uTeam);
        seedOrgDirectMember(root, sender);

        long notificationId = seedConfirmableNotification(sender);
        seedTarget(notificationId, ConfirmableTargetType.ORGANIZATION, root);

        List<Long> collected = collectAll(notificationId, sender);

        log.info("[AC-1] collected={}", collected);
        assertThat(collected)
                .as("AC-1: 自組織・子孫組織（孫まで）・配下ACTIVEチームの全員を含む")
                .containsExactlyInAnyOrder(uRoot, uChild, uGrandchild, uTeam);
    }

    // =====================================================================
    // AC-2 除外系: PENDING所属チーム・離脱チーム・退会者・status≠ACTIVE・left_atあり・純SUPPORTER
    // =====================================================================
    @Test
    @DisplayName("AC-2 PENDING/離脱チーム・退会者・非ACTIVE・left_atあり・純SUPPORTERは除外し、MEMBER兼SUPPORTERは含む")
    void ac2_excludesNonQualifyingMembers() {
        long seed = 41_002L;
        long org = createOrg(null);
        long activeTeam = 88_002L;
        long pendingTeam = 88_003L;
        seedTeamOrgMembership(activeTeam, org, TeamOrgMembershipEntity.Status.ACTIVE);
        seedTeamOrgMembership(pendingTeam, org, TeamOrgMembershipEntity.Status.PENDING);

        long uActiveTeam = base(seed) + 1;      // 含まれるべき
        long uPendingTeam = base(seed) + 2;     // 除外: PENDING所属チーム
        long uWithdrawn = base(seed) + 3;       // 除外: 退会者（deleted_at）
        long uFrozen = base(seed) + 4;          // 除外: status≠ACTIVE
        long uLeft = base(seed) + 5;            // 除外: memberships.left_at あり
        long uPureSupporter = base(seed) + 6;   // 除外: 純SUPPORTER
        long uMemberAndSupporter = base(seed) + 7; // 含む: MEMBER兼SUPPORTER
        long sender = base(seed) + 99;

        seedTeamMember(activeTeam, uActiveTeam);
        seedTeamMember(pendingTeam, uPendingTeam);
        seedOrgDirectMember(org, uWithdrawn, "ACTIVE", true);
        seedOrgDirectMember(org, uFrozen, "FROZEN", false);
        seedOrgDirectMember(org, uLeft, "ACTIVE", false);
        seedOrgDirectMember(org, uPureSupporter, "ACTIVE", false);
        seedOrgDirectMember(org, uMemberAndSupporter, "ACTIVE", false);
        seedOrgDirectMember(org, sender, "ACTIVE", false);

        seedMembership(uLeft, ScopeType.ORGANIZATION, org, RoleKind.MEMBER, LocalDateTime.now().minusDays(1));
        seedMembership(uPureSupporter, ScopeType.ORGANIZATION, org, RoleKind.SUPPORTER, null);
        seedMembership(uMemberAndSupporter, ScopeType.ORGANIZATION, org, RoleKind.SUPPORTER, null);
        seedMembership(uMemberAndSupporter, ScopeType.ORGANIZATION, org, RoleKind.MEMBER, null);

        long notificationId = seedConfirmableNotification(sender);
        seedTarget(notificationId, ConfirmableTargetType.ORGANIZATION, org);

        List<Long> collected = collectAll(notificationId, sender);

        log.info("[AC-2] collected={}", collected);
        assertThat(collected)
                .as("AC-2: 適格な受信者のみを展開する")
                .containsExactlyInAnyOrder(uActiveTeam, uMemberAndSupporter)
                .doesNotContain(uPendingTeam, uWithdrawn, uFrozen, uLeft, uPureSupporter);
    }

    // =====================================================================
    // AC-3 送信者本人は受信者にならない
    // =====================================================================
    @Test
    @DisplayName("AC-3 送信者本人は展開結果から除外される")
    void ac3_senderExcluded() {
        long seed = 41_003L;
        long org = createOrg(null);
        long other = base(seed) + 1;
        long sender = base(seed) + 2;
        seedOrgDirectMember(org, other);
        seedOrgDirectMember(org, sender);

        long notificationId = seedConfirmableNotification(sender);
        seedTarget(notificationId, ConfirmableTargetType.ORGANIZATION, org);

        List<Long> collected = collectAll(notificationId, sender);

        log.info("[AC-3] collected={}（送信者={}）", collected, sender);
        assertThat(collected)
                .as("AC-3: 送信者本人は含まれない")
                .containsExactly(other)
                .doesNotContain(sender);
    }

    // =====================================================================
    // AC-4 重複所属者の受信者行はちょうど1行
    // =====================================================================
    @Test
    @DisplayName("AC-4 複数チーム・組織に重なって所属する利用者の受信者行はちょうど1行")
    void ac4_overlappingMembershipDeduped() {
        long seed = 41_004L;
        long org = createOrg(null);
        long teamA = 88_004L;
        long teamB = 88_005L;
        seedTeamOrgMembership(teamA, org, TeamOrgMembershipEntity.Status.ACTIVE);
        seedTeamOrgMembership(teamB, org, TeamOrgMembershipEntity.Status.ACTIVE);

        long overlapping = base(seed) + 1; // teamA・teamB・org直属の3経路で重複所属
        long sender = base(seed) + 99;
        seedTeamMember(teamA, overlapping);
        seedTeamMember(teamB, overlapping);
        seedOrgDirectMember(org, overlapping);
        seedOrgDirectMember(org, sender);

        long notificationId = seedConfirmableNotification(sender);
        seedTarget(notificationId, ConfirmableTargetType.ORGANIZATION, org);

        List<Long> collected = collectAll(notificationId, sender);
        long occurrences = collected.stream().filter(id -> id == overlapping).count();

        log.info("[AC-4] collected={} occurrences={}", collected, occurrences);
        assertThat(occurrences).as("AC-4: 重複所属者はちょうど1行").isEqualTo(1);
    }

    // =====================================================================
    // AC-5 targets=[TEAM(a), ORGANIZATION(child)] の和集合のみ展開
    // =====================================================================
    @Test
    @DisplayName("AC-5 targets=[TEAM(a), ORGANIZATION(child)] はチームaの在籍メンバー∪child配下全員のみ")
    void ac5_mixedTargetsUnion() {
        long seed = 41_005L;
        long unrelatedOrg = createOrg(null);
        long child = createOrg(null);
        long teamA = 88_006L;
        seedTeamOrgMembership(teamA, unrelatedOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        long uTeamA = base(seed) + 1;
        long uChild = base(seed) + 2;
        long uUnrelatedOrgDirect = base(seed) + 3; // teamA所属チームの親組織直属者（対象外のはず）
        long sender = base(seed) + 99;
        seedTeamMember(teamA, uTeamA);
        seedOrgDirectMember(child, uChild);
        seedOrgDirectMember(unrelatedOrg, uUnrelatedOrgDirect);
        seedOrgDirectMember(child, sender);

        long notificationId = seedConfirmableNotification(sender);
        seedTarget(notificationId, ConfirmableTargetType.TEAM, teamA);
        seedTarget(notificationId, ConfirmableTargetType.ORGANIZATION, child);

        List<Long> collected = collectAll(notificationId, sender);

        log.info("[AC-5] collected={}", collected);
        assertThat(collected)
                .as("AC-5: チームaの在籍メンバー∪child配下全員のみ")
                .containsExactlyInAnyOrder(uTeamA, uChild)
                .doesNotContain(uUnrelatedOrgDirect);
    }

    // =====================================================================
    // AC-6 チームスコープ既定は自チーム在籍メンバー（送信者除く）
    // =====================================================================
    @Test
    @DisplayName("AC-6 TEAM(id) はそのチームの在籍メンバー（送信者を除く）のみを展開する")
    void ac6_teamDefaultScope() {
        long seed = 41_006L;
        long team = 88_007L;
        long otherTeam = 88_008L;

        long uInTeam = base(seed) + 1;
        long uOtherTeam = base(seed) + 2; // 別チーム所属（対象外）
        long sender = base(seed) + 99;
        seedTeamMember(team, uInTeam);
        seedTeamMember(otherTeam, uOtherTeam);
        seedTeamMember(team, sender);

        long notificationId = seedConfirmableNotification(sender);
        seedTarget(notificationId, ConfirmableTargetType.TEAM, team);

        List<Long> collected = collectAll(notificationId, sender);

        log.info("[AC-6] collected={}", collected);
        assertThat(collected)
                .as("AC-6: 自チームの在籍メンバーのみ（送信者除く・別チームは含まない）")
                .containsExactly(uInTeam)
                .doesNotContain(uOtherTeam, sender);
    }

    // =====================================================================
    // AC-7 宛先グループ経由の展開は「送信時点」で行う（グループ登録後の加入者も含む）
    // =====================================================================
    @Test
    @DisplayName("AC-7 recipientGroupIdによる展開はターゲットを送信の時点で展開する（登録後加入者も含む・ターゲット等価シナリオ）")
    void ac7_groupExpandsAtSendTime() {
        // 骨格段階では ConfirmableRecipientGroupService によるグループ→targets解決が未実装のため、
        // 「グループ登録後にターゲットのチームへ加わった人も受信者になる」という AC-7 の本質
        // （展開は送信時点の所属で動的に行う。固定リストのスナップショットではない）を、
        // targets 直接展開（ConfirmableTargetsFanoutRecipientSource が担う実体）で固定する。
        // グループ→targets の解決自体（AC-31相当）は ConfirmableRecipientGroupServiceTest が別途担保する。
        long seed = 41_007L;
        long team = 88_009L;
        long uBeforeRegistration = base(seed) + 1;
        long sender = base(seed) + 99;
        seedTeamMember(team, uBeforeRegistration);
        seedTeamMember(team, sender);

        long notificationId = seedConfirmableNotification(sender);
        seedTarget(notificationId, ConfirmableTargetType.TEAM, team);

        // 「グループ登録後にチームへ加わった人」を模してターゲット保存後に加入させる。
        long uAfterRegistration = base(seed) + 2;
        seedTeamMember(team, uAfterRegistration);

        List<Long> collected = collectAll(notificationId, sender);

        log.info("[AC-7] collected={}", collected);
        assertThat(collected)
                .as("AC-7: 展開は送信時点の所属で動的に行う（登録後加入者も含む）")
                .containsExactlyInAnyOrder(uBeforeRegistration, uAfterRegistration);
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    private static long base(long seed) {
        return seed * 1000L;
    }

    private long createOrg(Long parentOrganizationId) {
        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .slug("confirmable-targets-it-" + SLUG_SEQ.incrementAndGet())
                .name("confirmable targets IT org")
                .orgType(OrganizationEntity.OrgType.COMMUNITY)
                .parentOrganizationId(parentOrganizationId)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.FULL)
                .supporterEnabled(Boolean.TRUE)
                .build());
        return org.getId();
    }

    private void seedTeamOrgMembership(long teamId, long orgId, TeamOrgMembershipEntity.Status status) {
        teamOrgMembershipRepository.save(TeamOrgMembershipEntity.builder()
                .teamId(teamId)
                .organizationId(orgId)
                .status(status)
                .invitedAt(LocalDateTime.now())
                .build());
    }

    private void seedOrgDirectMember(long orgId, long userId) {
        seedOrgDirectMember(orgId, userId, "ACTIVE", false);
    }

    private void seedOrgDirectMember(long orgId, long userId, String userStatus, boolean deleted) {
        insertUser(userId, userStatus, deleted ? LocalDateTime.now().minusHours(1) : null);
        userRoleRepository.save(UserRoleEntity.builder()
                .userId(userId)
                .roleId(3L)
                .organizationId(orgId)
                .build());
    }

    private void seedTeamMember(long teamId, long userId) {
        insertUser(userId, "ACTIVE", null);
        userRoleRepository.save(UserRoleEntity.builder()
                .userId(userId)
                .roleId(3L)
                .teamId(teamId)
                .build());
    }

    private void seedMembership(long userId, ScopeType scopeType, long scopeId, RoleKind roleKind, LocalDateTime leftAt) {
        membershipRepository.save(MembershipEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .roleKind(roleKind)
                .joinedAt(LocalDateTime.now().minusDays(2))
                .leftAt(leftAt)
                .build());
    }

    /** test profile は ddl-auto:create で users 表を UserEntity から生成するため、必須列をすべて埋める。 */
    private void insertUser(long userId, String status, LocalDateTime deletedAt) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO users ("
                        + "id, email, last_name, first_name, display_name, status, deleted_at, created_at, updated_at, "
                        + "handle_searchable, contact_approval_required, online_visibility, is_searchable, dm_receive_from, "
                        + "encryption_key_version, locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only"
                        + ") VALUES ("
                        + "?, ?, 'L', 'F', ?, ?, ?, ?, ?, "
                        + "1, 1, 'NOBODY', 1, 'ANYONE', "
                        + "1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0) ON DUPLICATE KEY UPDATE id = id",
                userId, "confirmable-targets-it-" + userId + "@example.test", "U" + userId, status, deletedAt, now, now);
    }

    /** confirmable_notifications 最小行を1件 INSERT し、生成IDを返す（FK非依存の直接SQL）。 */
    private long seedConfirmableNotification(long senderUserId) {
        jdbc.update("INSERT INTO confirmable_notifications "
                        + "(scope_type, scope_id, title, created_by, status, created_at, updated_at) "
                        + "VALUES ('ORGANIZATION', 1, 'IT title', ?, 'ACTIVE', NOW(), NOW())",
                senderUserId);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    private void seedTarget(long notificationId, ConfirmableTargetType type, long targetId) {
        targetRepository.save(ConfirmableNotificationTargetEntity.builder()
                .confirmableNotificationId(notificationId)
                .targetType(type)
                .targetId(targetId)
                .build());
    }

    /** cursor 反復で全ページを収集する（本番のワーカー同様、全件 List 化しないページング走査）。 */
    private List<Long> collectAll(long notificationId, long senderUserIdToExclude) {
        List<Long> collected = new ArrayList<>();
        long cursor = 0L;
        while (true) {
            List<FanoutRecipient> page = source.nextPage(new FanoutPageRequest(
                    String.valueOf(notificationId), cursor, LARGE_LIMIT, false, 0, 1));
            if (page.isEmpty()) {
                break;
            }
            collected.addAll(page.stream().map(FanoutRecipient::userId).collect(Collectors.toList()));
            cursor = page.get(page.size() - 1).userId();
            if (page.size() < LARGE_LIMIT) {
                break;
            }
        }
        return collected;
    }
}
