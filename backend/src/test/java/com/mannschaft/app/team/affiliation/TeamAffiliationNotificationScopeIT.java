package com.mannschaft.app.team.affiliation;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.fanout.FanoutEnqueueCommand;
import com.mannschaft.app.notification.fanout.FanoutMessageKind;
import com.mannschaft.app.notification.fanout.FanoutMessageRenderer;
import com.mannschaft.app.notification.fanout.NotificationFanoutJob;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobStatus;
import com.mannschaft.app.notification.fanout.NotificationFanoutWorker;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderItemEntity;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderItemRepository;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderRepository;
import com.mannschaft.app.team.service.TeamAffiliationNotice;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 §6.7 / #3894: 9通知種別11方向を実outboxから本人フォルダまで通す保存契約。
 *
 * <p>業務通知の予約・relay・Worker・notifications・フォルダ検索はすべて実Bean/実MySQLを使う。
 * テスト全体を@Transactionalで囲むと予約commitと配送を観測できないため、既存supportのinTxで
 * fixtureと予約を明示commitする。生成したユーザー/スコープはsupportが、フォルダは本クラスが掃除する。</p>
 *
 * <p>修正前REDの対象はscope保存と本人folder取得。認可/空/通常一覧/parse失敗は既存契約の固定であり、
 * 初回からGREENでもよい。cursorはDBから再読し、0への巻戻しや新exactly-once保証は検査しない。</p>
 */
@AutoConfigureMockMvc
@DisplayName("#3894 加盟通知の保存scopeと本人フォルダ契約（9種11方向）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class TeamAffiliationNotificationScopeIT extends TeamNotificationOutboxItSupport {

    private static final String TEAM_STRATEGY = "TEAM_AFFILIATION_OPS";
    private static final String ORG_STRATEGY = "ORGANIZATION_ADMINS";
    private static final PageRequest PAGE = PageRequest.of(0, 100);

    @Autowired
    private NotificationFanoutWorker worker;
    @Autowired
    private NotificationFanoutJobRepository jobRepository;
    @Autowired
    private NotificationFanoutJobService jobService;
    @Autowired
    private FanoutMessageRenderer renderer;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private MyScopeFolderRepository folderRepository;
    @Autowired
    private MyScopeFolderItemRepository itemRepository;
    @Autowired
    private MockMvc mockMvc;

    private final List<Long> createdFolderIds = new ArrayList<>();

    /** §6.7の保存scope表から独立に期待方向を定める。受信者strategyから期待値を逆算しない。 */
    enum NoticeCase {
        APPLICATION_RECEIVED(NotificationScopeType.ORGANIZATION),
        APPLICATION_APPROVED(NotificationScopeType.TEAM),
        APPLICATION_REJECTED(NotificationScopeType.TEAM),
        INVITE_RECEIVED(NotificationScopeType.TEAM),
        INVITE_ACCEPTED(NotificationScopeType.ORGANIZATION),
        APPLICATION_EXPIRED(NotificationScopeType.TEAM),
        INVITE_EXPIRED(NotificationScopeType.ORGANIZATION),
        ORGANIZATION_CLOSED(NotificationScopeType.TEAM),
        TEAM_CLOSED(NotificationScopeType.ORGANIZATION),
        MEMBERSHIP_LEFT(NotificationScopeType.ORGANIZATION),
        MEMBERSHIP_REMOVED(NotificationScopeType.TEAM);

        private final NotificationScopeType storedScope;

        NoticeCase(NotificationScopeType storedScope) {
            this.storedScope = storedScope;
        }

        boolean teamSide() {
            return storedScope == NotificationScopeType.TEAM;
        }
    }

    private record Fixture(TeamFx team, OrgFx org, long actor, List<Long> teamRecipients,
                           List<Long> orgRecipients, long outsider) {
    }

    @AfterEach
    void 自作フォルダのみ削除する() {
        if (!createdFolderIds.isEmpty()) {
            itemRepository.deleteAll(itemRepository.findByFolderIdIn(createdFolderIds));
            folderRepository.deleteAllById(createdFolderIds);
        }
    }

    @ParameterizedTest(name = "AC01-03 {0}")
    @EnumSource(NoticeCase.class)
    void 加盟通知11方向_実配送の保存scopeと本人フォルダが仕様に一致する(NoticeCase direction) {
        Fixture f = fixture(true);
        TeamAffiliationNotice notice = notice(direction, f, nextFakeMembershipId());
        NotificationFanoutJob job = enqueueAndRelay(direction, f, notice, 1);

        worker.processOne(job);

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(NotificationFanoutJobStatus.DONE);
        List<Long> expectedRecipients = direction.teamSide() ? f.teamRecipients() : f.orgRecipients();
        List<Map<String, Object>> rows = notifications(notice);
        // 配送0件などのfixture失敗を、目的の保存scope REDと取り違えない。
        assertThat(rows).extracting(row -> number(row, "user_id"))
                .containsExactlyElementsOf(expectedRecipients);
        long expectedScopeId = direction.teamSide() ? f.team().id() : f.org().id();
        var message = renderer.renderAllLocales(notice.messageKind(), notice.messageArgs().toArray(String[]::new))
                .get("ja");

        SoftAssertions softly = new SoftAssertions();
        for (Map<String, Object> row : rows) {
            softly.assertThat(row.get("scope_type")).as(direction + " 保存scopeType")
                    .isEqualTo(direction.storedScope.name());
            softly.assertThat(row.get("scope_id")).as(direction + " 保存scopeId").isEqualTo(expectedScopeId);
            softly.assertThat(row.get("organization_id")).isEqualTo(f.org().id());
            softly.assertThat(row.get("actor_id")).isEqualTo(notice.actorUserId());
            softly.assertThat(row.get("source_type")).isEqualTo("TEAM_ORG_MEMBERSHIP");
            softly.assertThat(row.get("source_id")).isEqualTo(notice.membershipId());
            softly.assertThat(row.get("action_url")).isEqualTo(notice.actionUrl());
            softly.assertThat(row.get("title")).isEqualTo(message.title());
            softly.assertThat(row.get("body")).isEqualTo(message.body());
        }

        for (Map<String, Object> row : rows) {
            long recipient = number(row, "user_id");
            long folder = createFolder(recipient, direction.storedScope);
            addItem(folder, expectedScopeId);
            // 同じ数値でも別scopeType・folder外・他user・旧SYSTEMは混入してはならない。
            NotificationScopeType otherType = direction.teamSide()
                    ? NotificationScopeType.ORGANIZATION : NotificationScopeType.TEAM;
            saveContrast(recipient, otherType, expectedScopeId, "同ID別scope");
            saveContrast(recipient, direction.storedScope, Long.MAX_VALUE, "folder外");
            saveContrast(f.outsider(), direction.storedScope, expectedScopeId, "他user");
            saveContrast(recipient, NotificationScopeType.SYSTEM, null, "旧SYSTEM");

            var page = notificationService.listNotificationsByFolder(
                    recipient, folder, ScopeType.valueOf(direction.storedScope.name()), PAGE);
            softly.assertThat(page.getContent()).as(direction + " 本人folder")
                    .extracting(NotificationResponse::getId).containsExactly(number(row, "id"));
        }
        softly.assertAll();
    }

    @ParameterizedTest(name = "AC10 {0}")
    @EnumSource(value = NoticeCase.class, names = {"APPLICATION_RECEIVED", "APPLICATION_APPROVED"})
    void 二重予約と永続cursor再読の再実行_配送件数と保存scopeを維持する(NoticeCase direction) {
        Fixture f = fixture(true);
        TeamAffiliationNotice notice = notice(direction, f, nextFakeMembershipId());
        NotificationFanoutJob job = enqueueAndRelay(direction, f, notice, 2);
        worker.processOne(job);
        List<Map<String, Object>> first = notifications(notice);

        drain();
        // 別Repository呼出しでDBの終端cursorを再読する。古いjobのcursorを0へ戻さない。
        NotificationFanoutJob fresh = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(fresh.getCursorSubjectId()).isPositive();
        worker.processOne(fresh);

        assertThat(notifications(notice)).containsExactlyElementsOf(first);
        assertThat(jobCount(idempotencyKeyOf(notice.notificationType(), notice.membershipId()))).isEqualTo(1);
        assertThat(first).extracting(row -> row.get("scope_type")).containsOnly(direction.storedScope.name());
        assertThat(first).extracting(row -> row.get("scope_id"))
                .containsOnly(direction.teamSide() ? f.team().id() : f.org().id());
    }

    @Test
    void 本人通知一覧_他人所有folderIdは404で通知を返さない() throws Exception {
        Fixture f = fixture(true);
        long folder = createFolder(f.actor(), NotificationScopeType.ORGANIZATION);
        addItem(folder, f.org().id());

        mockMvc.perform(get("/api/v1/notifications").with(user(String.valueOf(f.outsider())))
                        .param("folderId", String.valueOf(folder)).param("scopeType", "ORGANIZATION"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SCOPE_FOLDER_NOT_FOUND"));
    }

    @Test
    void 本人空folderは200空配列で通常一覧の旧SYSTEM通知を維持する() throws Exception {
        Fixture f = fixture(true);
        long folder = createFolder(f.actor(), NotificationScopeType.ORGANIZATION);
        Long legacy = saveContrast(f.actor(), NotificationScopeType.SYSTEM, null, "旧SYSTEM");

        mockMvc.perform(get("/api/v1/notifications").with(user(String.valueOf(f.actor())))
                        .param("folderId", String.valueOf(folder)).param("scopeType", "ORGANIZATION"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
        assertThat(notificationService.listNotificationsByFolder(f.actor(), null, ScopeType.TEAM, PAGE)
                .getContent()).extracting(NotificationResponse::getId).containsExactly(legacy);
        assertThat(notificationService.listNotificationsByFolder(f.actor(), folder, null, PAGE)
                .getContent()).extracting(NotificationResponse::getId).containsExactly(legacy);
        assertThat(notificationRepository.findById(legacy).orElseThrow().getScopeType())
                .isEqualTo(NotificationScopeType.SYSTEM);
        assertThat(notificationRepository.findById(legacy).orElseThrow().getScopeId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {TEAM_STRATEGY, ORG_STRATEGY})
    void 不正scopeRefはSYSTEMへ落とさず既存retryへ記録する(String strategy) {
        Fixture f = fixture(false);
        UUID jobId = rawJob(strategy, "not-a-number", f);

        worker.processOne(jobRepository.findById(jobId).orElseThrow());

        NotificationFanoutJob failed = jobRepository.findById(jobId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(NotificationFanoutJobStatus.FAILED);
        assertThat(failed.getRetryCount()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("NumberFormatException");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE organization_id = ?",
                Long.class, f.org().id())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {TEAM_STRATEGY, ORG_STRATEGY})
    void 数値scopeRefが0で受信者0なら新positiveId規則を足さずDONEになる(String strategy) {
        Fixture f = fixture(false);
        UUID jobId = rawJob(strategy, "0", f);

        worker.processOne(jobRepository.findById(jobId).orElseThrow());

        NotificationFanoutJob done = jobRepository.findById(jobId).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(NotificationFanoutJobStatus.DONE);
        assertThat(done.getInsertedCount()).isZero();
        assertThat(done.getRetryCount()).isZero();
    }

    private Fixture fixture(boolean recipients) {
        return inTx(() -> {
            seedAffiliationPermission();
            TeamFx team = newTeam();
            OrgFx org = newOrg();
            long admin = newUser();
            long deputy = newUser();
            long grantedMember = newUser();
            long plainMember = newUser();
            long orgAdmin = newUser();
            long secondOrgAdmin = newUser();
            long outsider = newUser();
            if (recipients) {
                makeTeamAdmin(admin, team.id());
                makeTeamDeputy(deputy, team.id());
                grantAffiliationByPermissionGroup(deputy, team.id(), "DEPUTY_ADMIN");
                makeTeamMember(grantedMember, team.id());
                grantAffiliationByPermissionGroup(grantedMember, team.id(), "MEMBER");
                makeTeamMember(plainMember, team.id());
                makeOrgAdmin(orgAdmin, org.id());
                makeOrgAdmin(secondOrgAdmin, org.id());
                makeOrgAdmin(outsider, newOrg().id());
            }
            return new Fixture(team, org, orgAdmin, List.of(admin, deputy, grantedMember),
                    List.of(orgAdmin, secondOrgAdmin), outsider);
        });
    }

    private NotificationFanoutJob enqueueAndRelay(NoticeCase direction, Fixture f,
                                                  TeamAffiliationNotice notice, int copies) {
        inTx(() -> {
            for (int index = 0; index < copies; index++) {
                notifier.enqueue(notice);
            }
            return null;
        });
        drain();
        UUID key = idempotencyKeyOf(notice.notificationType(), notice.membershipId());
        assertThat(outboxRow(key).get("status")).isEqualTo("RELAYED");
        assertThat(jobMessageCount(key)).isEqualTo(6);
        return jobRepository.findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuid(
                direction.teamSide() ? TEAM_STRATEGY : ORG_STRATEGY,
                String.valueOf(direction.teamSide() ? f.team().id() : f.org().id()),
                notice.notificationType().name(), key).orElseThrow();
    }

    private UUID rawJob(String strategy, String scopeRef, Fixture f) {
        return inTx(() -> jobService.enqueueInCurrentTransaction(new FanoutEnqueueCommand(
                strategy, scopeRef, "I3894_INPUT", UUID.randomUUID(), f.org().id(), NotificationPriority.NORMAL,
                null, "TEAM_ORG_MEMBERSHIP", nextFakeMembershipId(), "/scope-test", false,
                FanoutMessageKind.TEAM_ORG_APPLICATION_RECEIVED, List.of(f.team().name(), f.org().name()),
                FanoutEnqueueCommand.ShardMode.FIXED_SINGLE)).jobId());
    }

    private List<Map<String, Object>> notifications(TeamAffiliationNotice notice) {
        return jdbc.queryForList("SELECT id, user_id, scope_type, scope_id, organization_id, actor_id, "
                        + "source_type, source_id, action_url, title, body FROM notifications "
                        + "WHERE notification_type = ? AND source_type = 'TEAM_ORG_MEMBERSHIP' AND source_id = ? "
                        + "ORDER BY user_id", notice.notificationType().name(), notice.membershipId());
    }

    private long createFolder(long userId, NotificationScopeType scope) {
        long id = folderRepository.saveAndFlush(MyScopeFolderEntity.builder().userId(userId)
                .scopeType(ScopeType.valueOf(scope.name())).name("通知scope試練-" + UUID.randomUUID())
                .sortOrder(0).isDefault(false).build()).getId();
        createdFolderIds.add(id);
        return id;
    }

    private void addItem(long folderId, long scopeId) {
        itemRepository.saveAndFlush(MyScopeFolderItemEntity.builder()
                .folderId(folderId).scopeId(scopeId).sortOrder(0).build());
    }

    private Long saveContrast(long userId, NotificationScopeType scope, Long scopeId, String title) {
        return notificationRepository.saveAndFlush(NotificationEntity.builder().userId(userId)
                .notificationType("SYSTEM_ANNOUNCEMENT").title(title).body("対照通知")
                .sourceType("SYSTEM").sourceId(1L).scopeType(scope).scopeId(scopeId).build()).getId();
    }

    private static long number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private TeamAffiliationNotice notice(NoticeCase direction, Fixture f, long membershipId) {
        TeamFx t = f.team();
        OrgFx o = f.org();
        return switch (direction) {
            case APPLICATION_RECEIVED -> TeamAffiliationNotice.applicationReceived(
                    o.id(), o.slug(), t.name(), o.name(), membershipId, f.actor());
            case APPLICATION_APPROVED -> TeamAffiliationNotice.applicationApproved(
                    t.id(), t.slug(), o.id(), o.name(), "未分類", membershipId, f.actor());
            case APPLICATION_REJECTED -> TeamAffiliationNotice.applicationRejected(
                    t.id(), t.slug(), o.id(), o.name(), null, membershipId, f.actor());
            case INVITE_RECEIVED -> new TeamAffiliationNotice(NotificationType.TEAM_ORG_INVITE_RECEIVED,
                    FanoutMessageKind.TEAM_ORG_INVITE_RECEIVED, List.of(o.name(), t.name()),
                    TeamAffiliationNotice.RecipientScope.TEAM_AFFILIATION_OPERATORS,
                    t.id(), o.id(), membershipId, f.actor(), "/teams/" + t.slug() + "/affiliations?view=invites");
            case INVITE_ACCEPTED -> new TeamAffiliationNotice(NotificationType.TEAM_ORG_INVITE_ACCEPTED,
                    FanoutMessageKind.TEAM_ORG_INVITE_ACCEPTED, List.of(t.name(), o.name()),
                    TeamAffiliationNotice.RecipientScope.ORGANIZATION_ADMINS,
                    o.id(), o.id(), membershipId, f.actor(), "/organizations/" + o.slug() + "/member-teams");
            case APPLICATION_EXPIRED -> TeamAffiliationNotice.applicationExpired(
                    t.id(), t.slug(), o.id(), t.name(), o.name(), membershipId);
            case INVITE_EXPIRED -> TeamAffiliationNotice.inviteExpired(
                    o.id(), o.slug(), t.name(), o.name(), membershipId);
            case ORGANIZATION_CLOSED -> TeamAffiliationNotice.pendingCancelledByOrganizationClosure(
                    t.id(), t.slug(), o.id(), o.name(), false, membershipId, f.actor());
            case TEAM_CLOSED -> TeamAffiliationNotice.pendingCancelledByTeamClosure(
                    o.id(), o.slug(), t.name(), false, membershipId, f.actor());
            case MEMBERSHIP_LEFT -> TeamAffiliationNotice.membershipLeft(
                    o.id(), o.slug(), t.name(), o.name(), membershipId, f.actor());
            case MEMBERSHIP_REMOVED -> TeamAffiliationNotice.membershipRemoved(
                    t.id(), t.slug(), o.id(), t.name(), o.name(), membershipId, f.actor());
        };
    }
}
