package com.mannschaft.app.timetable;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.CommitSaboteurConfig;
import com.mannschaft.app.support.test.CommitSaboteurConfig.CommitSaboteur;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import com.mannschaft.app.timetable.entity.TimetableTermEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSettingsEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSettingsRepository;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableSlotRepository;
import com.mannschaft.app.timetable.repository.TimetableTermRepository;
import com.mannschaft.app.timetable.service.TimetableSlotService;
import com.mannschaft.app.timetable.service.TimetableSlotService.SlotData;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Issue #2997 / CMP-260827-1152 G7-a（timetable）— チーム時間割コマ共通メモ更新通知の業務TX切り離しの実DB検証。
 *
 * <p>対象: {@code TimetableSlotService#replaceSlots} → {@code TimetableSlotNoteUpdatedEvent} →
 * {@code TeamSlotNoteNotifyListener}。</p>
 *
 * <h2>現行実装で落ちる理由（試練 red）</h2>
 * <ul>
 *   <li>リスナーは同期 {@code @EventListener} で、業務TXの commit を待たずに通知を作る。業務TXが rollback しても
 *       通知は別TX（{@code REQUIRES_NEW}）で確定済みになる（AC-A red）。</li>
 *   <li>デバウンスキーが {@code slotId}。{@code replaceSlots} は毎回スロットを削除再作成して ID が変わるため、
 *       同じコマを 5 分内に 2 回更新してもデバウンスが効かず 2 通届く（M8 red）。</li>
 *   <li>{@code readOnly = true} の {@code REQUIRES_NEW} 配下で通知を INSERT している疑い。
 *       {@link #commit後は設定有効なメンバー全員に通知行が実在する} の旧実装での結果を PR に記録する。</li>
 * </ul>
 *
 * <p>Valkey は基底クラスで Mock 化されているため、{@code SET NX EX} と Lua 解放は本テスト内のメモリ上のフェイクで
 * 再現する（Lua スクリプト自体の実行は検証できない。トークン一致時のみ削除の契約をフェイク側で実装している）。
 * クラスに {@code @Transactional} を付けない（commit が起きず AFTER_COMMIT が発火しないため）。</p>
 */
@Import(CommitSaboteurConfig.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2997 G7-a timetable コマ共通メモ通知のTX分離（実DB）")
class TeamSlotNoteNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    private static final String TYPE = "TEAM_SLOT_NOTE_UPDATED";

    @Autowired private TimetableSlotService slotService;
    @Autowired private TimetableRepository timetableRepository;
    @Autowired private TimetableTermRepository termRepository;
    @Autowired private TimetableSlotRepository slotRepository;
    @Autowired private PersonalTimetableSettingsRepository settingsRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private CommitSaboteur saboteur;
    @Autowired @Qualifier("event-pool") private java.util.concurrent.Executor eventPool;

    @MockitoSpyBean private NotificationService notificationService;

    @PersistenceContext
    private EntityManager em;

    /** Valkey のメモリ上フェイク（キー → 値）。 */
    private final Map<String, String> valkey = new ConcurrentHashMap<>();
    /** 通知永続化を失敗させる受信者。null なら失敗させない。{@code Set.of(-1L)} は番兵ではなく ALL 指定用に別フラグを使う。 */
    private final AtomicReference<Set<Long>> failingUsers = new AtomicReference<>(Set.of());
    private volatile boolean failAll = false;

    private record Fixture(Long teamId, Long timetableId, Long adminId, List<Long> memberIds) {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        valkey.clear();
        failAll = false;
        failingUsers.set(Set.of());

        ValueOperations<String, String> ops = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(ops);
        given(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).willAnswer(inv ->
                valkey.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
        // 旧実装（hasKey + set）でもデバウンスの有無を実測できるよう、旧 API も同じフェイクへ寄せる。
        given(redisTemplate.hasKey(anyString())).willAnswer(inv -> valkey.containsKey(inv.<String>getArgument(0)));
        // Lua 解放: KEYS[1] の値が ARGV[1] と一致するときだけ削除する（契約をフェイクで再現）。
        given(redisTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), any())).willAnswer(inv -> {
            List<String> keys = inv.getArgument(1);
            Object token = inv.getArgument(2);
            String key = keys.get(0);
            if (token != null && token.toString().equals(valkey.get(key))) {
                valkey.remove(key);
                return 1L;
            }
            return 0L;
        });

        doAnswer(inv -> {
            Long userId = inv.getArgument(0);
            if (failAll || failingUsers.get().contains(userId)) {
                throw new DataIntegrityViolationException("模擬通知永続化失敗（AC-B検証用）");
            }
            return inv.callRealMethod();
        }).when(notificationService).createNotification(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @AfterEach
    void tearDown() {
        saboteur.disarm();
    }

    @Test
    @DisplayName("AC-A2（readOnly 疑いの実測）: commit 後は設定有効なメンバー全員に通知行が実在し、OFF のメンバーには無い")
    void commit後は設定有効なメンバー全員に通知行が実在する() {
        Fixture f = fixture("a2", 3);
        disableNotify(f.memberIds().get(2));

        update(f, "MON", 1, "持参物A");

        awaitRow(f.adminId());
        awaitRow(f.memberIds().get(0));
        awaitRow(f.memberIds().get(1));
        drainEventPool();
        assertThat(rows(f.memberIds().get(2))).as("通知OFFのメンバーには届かない").isEmpty();
    }

    @Test
    @DisplayName("AC-A: commit 直前に rollback したら通知生成は一度も呼ばれず、メモも更新されない（注入点への到達も確認）")
    void rollbackされたら通知は作られない() {
        Fixture f = fixture("a", 2);
        saboteur.arm("TimetableSlotNoteUpdatedEvent");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> update(f, "MON", 1, "持参物A"))
                .isInstanceOf(RuntimeException.class);

        assertThat(saboteur.tripped()).as("BEFORE_COMMIT 注入点への到達").isPositive();
        drainEventPool();
        assertThat(createNotificationCalls()).as("rollback されたので通知生成は呼ばれていない").isZero();
        assertThat(slotRepository.findByTimetableIdOrderByDayOfWeekAscPeriodNumberAsc(f.timetableId()))
                .as("メモの更新も巻き戻っている").isEmpty();
    }

    @Test
    @DisplayName("AC-B: 通知の永続化が全員失敗しても、コマの更新はコミット済みで呼び出しは正常に返る")
    void 通知失敗でもコマ更新は残る() {
        Fixture f = fixture("b", 2);
        failAll = true;

        update(f, "MON", 1, "持参物A");

        assertThat(storedNotes(f)).isEqualTo("持参物A");
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(createNotificationCalls()).as("通知生成の注入点への到達").isGreaterThanOrEqualTo(3));
        drainEventPool();
        assertThat(rows(f.adminId())).isEmpty();
    }

    @Test
    @DisplayName("AC-B2: 1人分の通知が失敗しても、他のメンバーには届く")
    void 一人失敗でも他のメンバーには届く() {
        Fixture f = fixture("b2", 2);
        failingUsers.set(Set.of(f.memberIds().get(0)));

        update(f, "MON", 1, "持参物A");

        awaitRow(f.memberIds().get(1));
        awaitRow(f.adminId());
        drainEventPool();
        assertThat(attempted(f.memberIds().get(0))).as("失敗させた受信者への配送が試みられている").isTrue();
        assertThat(rows(f.memberIds().get(0))).isEmpty();
    }

    @Test
    @DisplayName("M8: 同じコマを5分内に2回実更新しても1人1件（スロットは毎回再作成されるが位置キーで抑止される）")
    void 同じコマの連続更新は1人1件() {
        Fixture f = fixture("deb", 1);

        update(f, "MON", 1, "持参物A");
        awaitRow(f.adminId());
        drainEventPool();
        update(f, "MON", 1, "持参物B");
        drainEventPool();

        assertThat(rows(f.adminId())).as("2回目はデバウンスで抑止").hasSize(1);
        assertThat(rows(f.memberIds().get(0))).hasSize(1);
        assertThat(valkey.keySet()).anyMatch(k -> k.contains("slot_note_debounce:" + f.timetableId() + ":MON:1:EVERY"));
    }

    @Test
    @DisplayName("M8: 別のコマ（別の時限）の更新は独立してデバウンスされ、それぞれ通知される")
    void 別のコマは独立して通知される() {
        Fixture f = fixture("deb2", 1);

        update(f, "MON", 1, "持参物A");
        awaitRow(f.adminId());
        drainEventPool();
        update(f, "MON", 2, "持参物B");

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rows(f.adminId())).as("別コマなので2件").hasSize(2));
    }

    @Test
    @DisplayName("M8: 全員の配送が例外で失敗したらデバウンスの印を解放し、次の更新で通知される")
    void 全員失敗なら印を解放して次の更新で通知される() {
        Fixture f = fixture("rel", 1);
        failAll = true;

        update(f, "MON", 1, "持参物A");
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(createNotificationCalls()).as("配送の試行").isGreaterThanOrEqualTo(2));
        drainEventPool();
        assertThat(valkey).as("全員失敗なので印は解放されている").isEmpty();

        failAll = false;
        update(f, "MON", 1, "持参物B");

        awaitRow(f.adminId());
        awaitRow(f.memberIds().get(0));
    }

    @Test
    @DisplayName("M8: Valkey 障害時はデバウンスを諦めて毎回通知する")
    void Valkey障害時は毎回通知する() {
        Fixture f = fixture("vk", 1);
        given(redisTemplate.opsForValue()).willThrow(new IllegalStateException("Valkey 障害（検証用）"));
        given(redisTemplate.hasKey(anyString())).willThrow(new IllegalStateException("Valkey 障害（検証用）"));

        update(f, "MON", 1, "持参物A");
        awaitRow(f.adminId());
        drainEventPool();
        update(f, "MON", 1, "持参物B");

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rows(f.adminId())).as("毎回通知").hasSize(2));
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void update(Fixture f, String day, int period, String notes) {
        slotService.replaceSlots(f.timetableId(),
                List.of(new SlotData(day, period, WeekPattern.EVERY, "数学", null, null, null, notes)),
                null, f.adminId());
    }

    private String storedNotes(Fixture f) {
        return transactionTemplate.execute(tx ->
                slotRepository.findByTimetableIdOrderByDayOfWeekAscPeriodNumberAsc(f.timetableId())
                        .get(0).getNotes());
    }

    private long createNotificationCalls() {
        return Mockito.mockingDetails(notificationService).getInvocations().stream()
                .filter(i -> "createNotification".equals(i.getMethod().getName()))
                .filter(i -> TYPE.equals(i.getArgument(1)))
                .count();
    }

    private boolean attempted(Long userId) {
        return Mockito.mockingDetails(notificationService).getInvocations().stream()
                .filter(i -> "createNotification".equals(i.getMethod().getName()))
                .anyMatch(i -> userId.equals(i.getArgument(0)) && TYPE.equals(i.getArgument(1)));
    }

    /** event-pool が空（実行中0・待ち行列0）で安定するまで待つ。「通知が来ないこと」を見る前に必ず通す。 */
    private void drainEventPool() {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) eventPool;
        await().pollDelay(300, TimeUnit.MILLISECONDS).atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(pool.getActiveCount()).isZero();
            assertThat(pool.getThreadPoolExecutor().getQueue()).isEmpty();
        });
    }

    private void awaitRow(Long userId) {
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rows(userId)).as("%s の通知行（userId=%s）", TYPE, userId).isNotEmpty());
    }

    private List<NotificationEntity> rows(Long userId) {
        List<NotificationEntity> all = transactionTemplate.execute(tx ->
                notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50)).getContent());
        return all.stream().filter(n -> TYPE.equals(n.getNotificationType())).toList();
    }

    private void disableNotify(Long userId) {
        transactionTemplate.executeWithoutResult(tx -> settingsRepository.save(
                PersonalTimetableSettingsEntity.builder().userId(userId).notifyTeamSlotNoteUpdates(false).build()));
    }

    /** チーム・DRAFT 時間割・ADMIN 1名＋一般メンバー memberCount 名（全員 memberships 所属）を作る。 */
    private Fixture fixture(String tag, int memberCount) {
        String nonce = tag + "-" + System.nanoTime();
        return transactionTemplate.execute(tx -> {
            Long teamId = insertTeam("TTNOTE " + nonce);
            Long adminId = insertUser("ttnote-adm-" + nonce + "@example.com");
            MembershipTestHelper.insertMembership(em, adminId,
                    com.mannschaft.app.membership.domain.ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
            List<Long> members = new java.util.ArrayList<>();
            for (int i = 0; i < memberCount; i++) {
                Long uid = insertUser("ttnote-m" + i + "-" + nonce + "@example.com");
                MembershipTestHelper.insertMembership(em, uid,
                        com.mannschaft.app.membership.domain.ScopeType.TEAM, teamId, RoleKind.MEMBER);
                members.add(uid);
            }
            Long termId = termRepository.save(TimetableTermEntity.builder()
                    .teamId(teamId).academicYear(2026).name("1学期 " + nonce)
                    .startDate(LocalDate.of(2026, 4, 1)).endDate(LocalDate.of(2026, 7, 31))
                    .sortOrder(1).build()).getId();
            Long timetableId = timetableRepository.save(TimetableEntity.builder()
                    .teamId(teamId).termId(termId).name("時間割 " + nonce)
                    .status(TimetableStatus.DRAFT).visibility(TimetableVisibility.MEMBERS_ONLY)
                    .effectiveFrom(LocalDate.of(2026, 4, 1)).createdBy(adminId).build()).getId();
            return new Fixture(teamId, timetableId, adminId, members);
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
                                + "VALUES (:email, 'TTNOTE', 'テスト', 'TTNOTE テスト', 'ACTIVE', "
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
