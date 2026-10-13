package com.mannschaft.app.recruitment;

import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.recruitment.entity.RecruitmentDistributionTargetEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentFriendTargetEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentDistributionTargetRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentFriendTargetRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import com.mannschaft.app.recruitment.service.RecruitmentListingService;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;

/**
 * Issue #2997 / CMP-260827-1152 第1陣 G2（recruitment）— 付随通知の業務TX巻き込みの実DB検証。
 *
 * <p>対象: {@code RecruitmentListingService#publish}（→ {@code sendPublishedNotifications} /
 * {@code MarketFriendTargetService#distributeFriendListing}）と
 * {@code RecruitmentListingService#confirmApplication}。</p>
 *
 * <h2>現行実装で落ちる理由（試練 red）</h2>
 * <p>現行は業務TXの内側で {@code NotificationHelper#notifyAllLocalized} / {@code #notify}
 * （受信者ごとに {@code NotificationService#createNotification}）を同期実行している。
 * そのため業務TXがロールバックする場合でも、ロールバック前に通知生成が呼ばれてしまう
 * （AC-A: 通知生成呼び出しが 0 件、に反する）。また通知永続化の DB 例外が業務TXの
 * rollback-only になりうる欠陥を構造的に抱える。正規形ではコミット後（AFTER_COMMIT）にのみ発火する。</p>
 *
 * <h2>観測方法と限界</h2>
 * <p>{@link NotificationService} を {@code @MockitoSpyBean} で spy し、{@code createNotification} の
 * 呼び出し（受信者・種別）を観測する。AC-B は spy が入口で {@link DataIntegrityViolationException}
 * を投げる形の注入であり、実 MySQL 上で通知の永続化そのものを失敗させる形ではない
 * （spy 例外は {@code NotificationService} の {@code @Transactional} プロキシを通らないため、
 * rollback-only の伝播自体は再現しない）。業務状態は別接続（別TX）から読んで確認する。
 * AC-A2 / AC-B2 は呼び出し回数ではなく、別TXから読んだ通知行（期待受信者に有り、失敗させた受信者に無い）で固定する。
 * 受信者は memberships と ADMIN ロールを持たせ、visibility deny されない形で fixture を組む。</p>
 *
 * <p>クラスに {@code @Transactional} を付けない（付けるとコミットが起きず AFTER_COMMIT が発火しないため）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2997 recruitment 付随通知のTX分離（実DB）")
class RecruitmentNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    @Autowired private RecruitmentListingService listingService;
    @Autowired private RecruitmentListingRepository listingRepository;
    @Autowired private RecruitmentParticipantRepository participantRepository;
    @Autowired private RecruitmentDistributionTargetRepository distributionTargetRepository;
    @Autowired private RecruitmentFriendTargetRepository friendTargetRepository;
    @Autowired private TeamFriendRepository teamFriendRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager em;

    /** 通知生成を観測／失敗注入するための spy（実 Bean のまま、必要なときだけ例外を注入する）。 */
    @MockitoSpyBean private NotificationService notificationService;

    /** teamId, 札主ADMIN(=配信対象 MEMBERS として user_roles に載る), もう1人の ADMIN。 */
    private record TeamFixture(Long teamId, Long adminId, Long admin2Id) {
    }

    // ═════════════════════════════════════════════════════════════════════
    // publish（一般経路: sendPublishedNotifications）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("publish AC-A2: コミットされたら配信対象の各受信者に RECRUITMENT_PUBLISHED の通知生成が呼ばれる")
    void publish_コミット後に通知が作られる() {
        TeamFixture f = teamFixture("pub-a2");
        Long listingId = insertListing(f, RecruitmentListingStatus.DRAFT, RecruitmentVisibility.SCOPE_ONLY);
        insertMembersTarget(listingId);

        listingService.publish(listingId, f.adminId());

        awaitNotificationRow(f.adminId(), "RECRUITMENT_PUBLISHED", listingId);
        awaitNotificationRow(f.admin2Id(), "RECRUITMENT_PUBLISHED", listingId);
    }

    @Test
    @DisplayName("publish AC-A: 業務TXがロールバックしたら通知生成は一度も呼ばれず、募集は DRAFT のまま")
    void publish_ロールバック時は通知が作られない() {
        TeamFixture f = teamFixture("pub-a");
        Long listingId = insertListing(f, RecruitmentListingStatus.DRAFT, RecruitmentVisibility.SCOPE_ONLY);
        insertMembersTarget(listingId);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            listingService.publish(listingId, f.adminId());
            throw new RuntimeException("強制ロールバック（AC-A検証用）");
        })).isInstanceOf(RuntimeException.class);

        assertThat(listingStatus(listingId)).isEqualTo(RecruitmentListingStatus.DRAFT);
        assertThat(createNotificationCalls()).as("通知生成が呼ばれていないこと").isZero();
    }

    @Test
    @DisplayName("publish AC-B: 通知の永続化が失敗しても募集の OPEN 遷移はコミット済みで、呼び出しは正常に返る")
    void publish_通知失敗でも公開は残る() {
        TeamFixture f = teamFixture("pub-b");
        Long listingId = insertListing(f, RecruitmentListingStatus.DRAFT, RecruitmentVisibility.SCOPE_ONLY);
        insertMembersTarget(listingId);
        failNotificationPersistence();

        listingService.publish(listingId, f.adminId());

        assertThat(listingStatus(listingId)).isEqualTo(RecruitmentListingStatus.OPEN);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(createNotificationCalls()).isPositive());
    }

    @Test
    @DisplayName("publish AC-B2: 1人分の通知が失敗しても、他の受信者への通知生成は呼ばれる")
    void publish_1人失敗でも他の受信者には届く() {
        TeamFixture f = teamFixture("pub-b2");
        Long listingId = insertListing(f, RecruitmentListingStatus.DRAFT, RecruitmentVisibility.SCOPE_ONLY);
        insertMembersTarget(listingId);
        failNotificationPersistenceFor(f.adminId());

        listingService.publish(listingId, f.adminId());

        assertThat(listingStatus(listingId)).isEqualTo(RecruitmentListingStatus.OPEN);
        // 失敗させた受信者の配送が実際に試みられたことを待つ（待たないと「行が無い」が未配送のせいで緑になる）。
        awaitCreateNotification(f.adminId(), "RECRUITMENT_PUBLISHED");
        awaitNotificationRow(f.admin2Id(), "RECRUITMENT_PUBLISHED", listingId);
        assertThat(notificationsOf(f.adminId(), "RECRUITMENT_PUBLISHED"))
                .as("失敗させた受信者の通知行は無い").isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════
    // publish（フレンド宛経路: MarketFriendTargetService#distributeFriendListing）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("friend AC-A2: コミットされたらフレンドチームの各 ADMIN に MARKET_FRIEND_LISTING の通知生成が呼ばれる")
    void friend_コミット後に通知が作られる() {
        TeamFixture owner = teamFixture("fr-a2-own");
        TeamFixture friend = teamFixture("fr-a2-frd");
        Long listingId = insertFriendListing(owner, friend);

        listingService.publish(listingId, owner.adminId());

        awaitNotificationRow(friend.adminId(), "MARKET_FRIEND_LISTING", listingId);
        awaitNotificationRow(friend.admin2Id(), "MARKET_FRIEND_LISTING", listingId);
    }

    @Test
    @DisplayName("friend AC-A: 業務TXがロールバックしたら通知生成は一度も呼ばれず、札は DRAFT のまま")
    void friend_ロールバック時は通知が作られない() {
        TeamFixture owner = teamFixture("fr-a-own");
        TeamFixture friend = teamFixture("fr-a-frd");
        Long listingId = insertFriendListing(owner, friend);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            listingService.publish(listingId, owner.adminId());
            throw new RuntimeException("強制ロールバック（AC-A検証用）");
        })).isInstanceOf(RuntimeException.class);

        assertThat(listingStatus(listingId)).isEqualTo(RecruitmentListingStatus.DRAFT);
        assertThat(createNotificationCalls()).isZero();
    }

    @Test
    @DisplayName("friend AC-B: 通知の永続化が失敗しても札の OPEN 遷移はコミット済みで、呼び出しは正常に返る")
    void friend_通知失敗でも公開は残る() {
        TeamFixture owner = teamFixture("fr-b-own");
        TeamFixture friend = teamFixture("fr-b-frd");
        Long listingId = insertFriendListing(owner, friend);
        failNotificationPersistence();

        listingService.publish(listingId, owner.adminId());

        assertThat(listingStatus(listingId)).isEqualTo(RecruitmentListingStatus.OPEN);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(createNotificationCalls()).isPositive());
    }

    @Test
    @DisplayName("friend AC-B2: 1受信者の通知が失敗しても、他の受信者への通知生成は呼ばれ、札は公開される")
    void friend_1人失敗でも他の受信者には届く() {
        TeamFixture owner = teamFixture("fr-b2-own");
        TeamFixture friend = teamFixture("fr-b2-frd");
        Long listingId = insertFriendListing(owner, friend);
        failNotificationPersistenceFor(friend.adminId());

        listingService.publish(listingId, owner.adminId());

        assertThat(listingStatus(listingId)).isEqualTo(RecruitmentListingStatus.OPEN);
        awaitCreateNotification(friend.adminId(), "MARKET_FRIEND_LISTING");
        awaitNotificationRow(friend.admin2Id(), "MARKET_FRIEND_LISTING", listingId);
        assertThat(notificationsOf(friend.adminId(), "MARKET_FRIEND_LISTING"))
                .as("失敗させた受信者の通知行は無い").isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════
    // confirmApplication
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("confirm AC-A2: コミットされたら応募者に RECRUITMENT_CONFIRMED の通知生成が呼ばれる")
    void confirm_コミット後に通知が作られる() {
        TeamFixture f = teamFixture("cf-a2");
        Long listingId = insertListing(f, RecruitmentListingStatus.OPEN, RecruitmentVisibility.SCOPE_ONLY);
        Long participantId = insertApplied(listingId, f.admin2Id());

        listingService.confirmApplication(participantId, f.adminId());

        awaitNotificationRow(f.admin2Id(), "RECRUITMENT_CONFIRMED", listingId);
    }

    @Test
    @DisplayName("confirm AC-A: 業務TXがロールバックしたら通知生成は一度も呼ばれず、参加者は APPLIED のまま")
    void confirm_ロールバック時は通知が作られない() {
        TeamFixture f = teamFixture("cf-a");
        Long listingId = insertListing(f, RecruitmentListingStatus.OPEN, RecruitmentVisibility.SCOPE_ONLY);
        Long participantId = insertApplied(listingId, f.admin2Id());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            listingService.confirmApplication(participantId, f.adminId());
            throw new RuntimeException("強制ロールバック（AC-A検証用）");
        })).isInstanceOf(RuntimeException.class);

        assertThat(participantStatus(participantId)).isEqualTo(RecruitmentParticipantStatus.APPLIED);
        assertThat(createNotificationCalls()).isZero();
    }

    @Test
    @DisplayName("confirm AC-B: 通知の永続化が失敗しても参加者の CONFIRMED はコミット済みで、呼び出しは正常に返る")
    void confirm_通知失敗でも確定は残る() {
        TeamFixture f = teamFixture("cf-b");
        Long listingId = insertListing(f, RecruitmentListingStatus.OPEN, RecruitmentVisibility.SCOPE_ONLY);
        Long participantId = insertApplied(listingId, f.admin2Id());
        failNotificationPersistence();

        listingService.confirmApplication(participantId, f.adminId());

        assertThat(participantStatus(participantId)).isEqualTo(RecruitmentParticipantStatus.CONFIRMED);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(createNotificationCalls()).isPositive());
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void failNotificationPersistence() {
        willThrow(new DataIntegrityViolationException("模擬通知永続化失敗（AC-B検証用）"))
                .given(notificationService).createNotification(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** 指定受信者の永続化だけ失敗させる（他の受信者は実 Bean の処理を通す）。 */
    private void failNotificationPersistenceFor(Long userId) {
        willThrow(new DataIntegrityViolationException("模擬通知永続化失敗（AC-B2検証用）"))
                .given(notificationService).createNotification(
                        eq(userId), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private long createNotificationCalls() {
        return Mockito.mockingDetails(notificationService).getInvocations().stream()
                .filter(i -> "createNotification".equals(i.getMethod().getName()))
                .count();
    }

    private void awaitCreateNotification(Long userId, String type) {
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(Mockito.mockingDetails(notificationService).getInvocations().stream()
                        .filter(i -> "createNotification".equals(i.getMethod().getName()))
                        .filter(i -> userId.equals(i.getArgument(0)) && type.equals(i.getArgument(1)))
                        .count())
                        .as("%s の通知生成が userId=%s に対して呼ばれていること", type, userId)
                        .isPositive());
    }

    /** 別TXから、期待する受信者の通知行が実際に作られる（visibility deny でない）ことを確認する。 */
    private void awaitNotificationRow(Long userId, String type, Long sourceId) {
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationsOf(userId, type))
                        .as("%s の通知行が userId=%s に作られていること（deny されていない）", type, userId)
                        .anyMatch(n -> sourceId.equals(n.getSourceId())));
    }

    private List<NotificationEntity> notificationsOf(Long userId, String type) {
        List<NotificationEntity> all = transactionTemplate.execute(tx ->
                notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50)).getContent());
        return all.stream().filter(n -> type.equals(n.getNotificationType())).toList();
    }

    private RecruitmentListingStatus listingStatus(Long id) {
        return transactionTemplate.execute(tx -> listingRepository.findById(id).orElseThrow().getStatus());
    }

    private RecruitmentParticipantStatus participantStatus(Long id) {
        return transactionTemplate.execute(tx -> participantRepository.findById(id).orElseThrow().getStatus());
    }

    private void insertMembersTarget(Long listingId) {
        transactionTemplate.executeWithoutResult(tx -> distributionTargetRepository.save(
                RecruitmentDistributionTargetEntity.builder()
                        .listingId(listingId)
                        .targetType(RecruitmentDistributionTargetType.MEMBERS)
                        .createdAt(LocalDateTime.now())
                        .build()));
    }

    private Long insertApplied(Long listingId, Long applicantUserId) {
        return transactionTemplate.execute(tx -> participantRepository.save(
                RecruitmentParticipantEntity.builder()
                        .listingId(listingId)
                        .participantType(RecruitmentParticipantType.USER)
                        .userId(applicantUserId)
                        .appliedBy(applicantUserId)
                        .status(RecruitmentParticipantStatus.APPLIED)
                        .build()).getId());
    }

    /** 札主チームと友達チームを友達にし、ALL_FRIENDS 宛先を持つ FRIEND_TEAMS_ONLY の DRAFT 札を作る。 */
    private Long insertFriendListing(TeamFixture owner, TeamFixture friend) {
        return transactionTemplate.execute(tx -> {
            Long a = Math.min(owner.teamId(), friend.teamId());
            Long b = Math.max(owner.teamId(), friend.teamId());
            teamFriendRepository.save(TeamFriendEntity.builder()
                    .teamAId(a)
                    .teamBId(b)
                    .establishedAt(LocalDateTime.now())
                    .aFollowId(0L)
                    .bFollowId(0L)
                    .build());
            Long listingId = listingRepository.save(listingBuilder(owner, RecruitmentListingStatus.DRAFT,
                    RecruitmentVisibility.FRIEND_TEAMS_ONLY).build()).getId();
            friendTargetRepository.save(RecruitmentFriendTargetEntity.ofAllFriends(listingId));
            return listingId;
        });
    }

    private Long insertListing(TeamFixture f, RecruitmentListingStatus status, RecruitmentVisibility visibility) {
        return transactionTemplate.execute(tx ->
                listingRepository.save(listingBuilder(f, status, visibility).build()).getId());
    }

    private RecruitmentListingEntity.RecruitmentListingEntityBuilder<?, ?> listingBuilder(
            TeamFixture f, RecruitmentListingStatus status, RecruitmentVisibility visibility) {
        LocalDateTime start = LocalDateTime.now().plusDays(7);
        return RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(f.teamId())
                .categoryId(1L)
                .title("#2997 G2 recruitment 検証")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(start)
                .endAt(start.plusHours(2))
                .applicationDeadline(start.minusDays(1))
                .autoCancelAt(start.minusDays(2))
                .capacity(10)
                .minCapacity(1)
                .visibility(visibility)
                .status(status)
                .createdBy(f.adminId());
    }

    /** チームと、ADMIN 2名（user_roles 経由で配信対象 MEMBERS / フレンド宛 ADMIN の双方に載る）を作る。 */
    private TeamFixture teamFixture(String tag) {
        String nonce = tag + "-" + System.nanoTime();
        return transactionTemplate.execute(tx -> {
            Long teamId = insertTeam("RCNTF " + nonce);
            Long adminId = insertUser("rcntf-a1-" + nonce + "@example.com");
            Long admin2Id = insertUser("rcntf-a2-" + nonce + "@example.com");
            for (Long uid : new Long[]{adminId, admin2Id}) {
                MembershipTestHelper.insertMembership(em, uid,
                        com.mannschaft.app.membership.domain.ScopeType.TEAM, teamId,
                        com.mannschaft.app.membership.domain.RoleKind.MEMBER);
                MembershipTestHelper.insertUserRole(em, uid, "ADMIN", teamId, null);
            }
            return new TeamFixture(teamId, adminId, admin2Id);
        });
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'RCNTF', 'テスト', 'RCNTF テスト', 'ACTIVE', "
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

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
