package com.mannschaft.app.reservation;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationPolicyRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.service.ReservationPendingExpireBatchService;
import com.mannschaft.app.reservation.service.ReservationPendingExpireService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * CMP-260822-1730 AC-11: チーム壁時計・終了日を使う PENDING 失効の実 MySQL 試練。
 *
 * <p>既存 ReservationPendingExpirePersistenceIntegrationTest の実 Bean/保存金型を使う。
 * 業務 Bean/DB の mock、追加 context、外側テスト TX はない。ClockConfig が許す手動
 * Clock 差替えだけを実サービス対象に行い、必ず元へ戻す。JUnit の他テストと同時実行しない。
 * 初期 RED は終了日/TZ の誤った候補とその実キャンセル、上限前の候補選別で検出する。
 * 既存の時間数/null/group 契約の境界は初回 GREEN が正しい回帰防止柵である。</p>
 */
@DisplayName("CMP1730 PENDING失効の終了日・チームTZ実MySQL試練")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Isolated("同一contextの失効サービスClockを所有試練中だけ差し替えてfinally相当で復元する")
class ReservationPendingExpireTeamTimezoneIT extends AbstractMySqlIntegrationTest {

    // 旧PersistenceITの固定bookedAtは2026-06-01、他の実DB予約金型は2026/実時刻の相対日。
    // 同じcontextの既存行を変更せず、それらが未来・負の経過時間となる2000年を選ぶ。
    private static final Instant NOW = Instant.parse("2000-08-10T03:00:00Z");
    private static final AtomicLong USERS = new AtomicLong(7_300_000_000L);

    @Autowired private ReservationPendingExpireBatchService batch;
    @Autowired private ReservationPendingExpireService service;
    @Autowired private ReservationRepository reservations;
    @Autowired private ReservationSlotRepository slots;
    @Autowired private ReservationPolicyRepository policies;
    @Autowired private TeamRepository teams;
    @Autowired private NotificationRepository notifications;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("event-pool") private ThreadPoolTaskExecutor eventPool;

    private final List<Long> ownedTeams = new ArrayList<>();
    private final List<Long> ownedUsers = new ArrayList<>();
    private Object serviceTarget;
    private Clock originalClock;

    @BeforeEach
    void 固定Clockを所有試練へ差し替える() {
        serviceTarget = AopTestUtils.getUltimateTargetObject(service);
        originalClock = (Clock) ReflectionTestUtils.getField(serviceTarget, "clock");
        assertThat(originalClock).as("差替前Clockを復元できること").isNotNull();
        setNow(NOW);
        assertNoForeignCandidatesForLimitFixture();
    }

    @AfterEach
    void Clock復元と処理終了後に所有行だけ片付ける() {
        if (originalClock != null) {
            ReflectionTestUtils.setField(serviceTarget, "clock", originalClock);
        }
        // 完了確認が失敗すれば DELETE へ進まない。他試練の thread/pool は停止しない。
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() ->
                eventPool.getActiveCount() == 0 && eventPool.getThreadPoolExecutor().getQueue().isEmpty());
        for (Long user : ownedUsers) {
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", user);
        }
        for (Long team : ownedTeams) {
            jdbc.update("DELETE FROM reservation_reminders WHERE reservation_id IN "
                    + "(SELECT id FROM reservations WHERE team_id = ?)", team);
            jdbc.update("DELETE FROM reservations WHERE team_id = ?", team);
            jdbc.update("DELETE FROM reservation_slots WHERE team_id = ?", team);
            jdbc.update("DELETE FROM reservation_policies WHERE team_id = ?", team);
            jdbc.update("DELETE FROM teams WHERE id = ?", team);
        }
    }

    @Test
    @DisplayName("AC1730-1: JST開始日12時の23:30→翌00:30は候補0・通常batchでも副作用0")
    void JST日跨ぎ枠は開始日の昼には失効しない() {
        Long team = team("Asia/Tokyo", 24, true);
        ReservationSlotEntity slot = slot(team, LocalDate.of(2000, 8, 10),
                LocalDate.of(2000, 8, 11), LocalTime.of(23, 30), LocalTime.of(0, 30));
        ReservationEntity pending = pending(team, slot, NOW.minusSeconds(3600), null, true);

        List<Long> candidates = candidateIds();
        batch.expirePendingReservations();

        assertUnchanged(pending, slot, candidates);
    }

    @Test
    @DisplayName("AC1730-2: 同一InstantでNY前日の未来枠は候補0・通常batchでも副作用0")
    void 非JST未来枠はサーバ暦日が翌日でも失効しない() {
        Long team = team("America/New_York", 24, true);
        assertThat(NOW.atZone(ZoneId.of("America/New_York")).toLocalDate())
                .isEqualTo(LocalDate.of(2000, 8, 9));
        ReservationSlotEntity slot = slot(team, LocalDate.of(2000, 8, 9),
                LocalDate.of(2000, 8, 10), LocalTime.of(23, 30), LocalTime.of(0, 30));
        ReservationEntity pending = pending(team, slot, NOW.minusSeconds(3600), null, true);

        List<Long> candidates = candidateIds();
        batch.expirePendingReservations();

        assertUnchanged(pending, slot, candidates);
    }

    @ParameterizedTest(name = "終了Instant差{0}秒、失効={1}")
    @CsvSource({"-1,false", "0,true", "1,true"})
    @DisplayName("AC1730-3: JST日跨ぎ終了Instantの直前保持・一致/直後失効")
    void 終了Instantの境界で候補と実副作用が一致する(long seconds, boolean expired) {
        Instant end = Instant.parse("2000-08-09T15:30:00Z");
        setNow(end.plusSeconds(seconds));
        Long team = team("Asia/Tokyo", 24, true);
        ReservationSlotEntity slot = slot(team, LocalDate.of(2000, 8, 9),
                LocalDate.of(2000, 8, 10), LocalTime.of(23, 30), LocalTime.of(0, 30));
        ReservationEntity pending = pending(team, slot, end.minusSeconds(3600), null, true);

        boolean candidate = candidateIds().contains(pending.getId());
        batch.expirePendingReservations();

        assertOutcome(pending, slot, candidate, expired);
    }

    @ParameterizedTest(name = "24h到達差{0}秒、失効={1}")
    @CsvSource({"-1,false", "0,true", "1,true"})
    @DisplayName("AC1730-4a: NYチームでもbookedAtはSERVER_ZONE保存の24h境界を保つ")
    void bookedAtの経過時間はチーム壁時計として再解釈しない(long seconds, boolean expired) {
        Long team = team("America/New_York", 24, true);
        ReservationSlotEntity slot = futureSlot(team);
        ReservationEntity pending = pending(team, slot, NOW.minusSeconds(24 * 3600L + seconds), null, true);

        boolean candidate = candidateIds().contains(pending.getId());
        batch.expirePendingReservations();

        assertOutcome(pending, slot, candidate, expired);
    }

    @Test
    @DisplayName("AC1730-4b: hours=nullは終了済み/500h経過でも候補と副作用0")
    void 明示null無効化は両失効条件に優先する() {
        Long team = team("America/New_York", null, true);
        ReservationSlotEntity slot = slot(team, LocalDate.of(2000, 8, 8),
                LocalDate.of(2000, 8, 9), LocalTime.of(23, 30), LocalTime.of(0, 30));
        ReservationEntity pending = pending(team, slot, NOW.minusSeconds(500 * 3600L), null, true);

        List<Long> candidates = candidateIds();
        batch.expirePendingReservations();

        assertUnchanged(pending, slot, candidates);
    }

    @ParameterizedTest(name = "ポリシー無し24h到達差{0}秒、失効={1}")
    @CsvSource({"-1,false", "0,true", "1,true"})
    @DisplayName("AC1730-4c: ポリシー無しのNYチームも既定24h境界を守る")
    void ポリシー無しは既定二十四時間になる(long seconds, boolean expired) {
        Long team = team("America/New_York", null, false);
        ReservationSlotEntity slot = futureSlot(team);
        ReservationEntity pending = pending(team, slot, NOW.minusSeconds(24 * 3600L + seconds), null, true);

        boolean candidate = candidateIds().contains(pending.getId());
        batch.expirePendingReservations();

        assertOutcome(pending, slot, candidate, expired);
    }

    @Test
    @DisplayName("AC1730-5a: 小groupは通常batchで全行失効・全枠復帰・代表通知1件")
    void グループ失効は全行を一単位で扱う() {
        Long team = team("America/New_York", 24, true);
        ReservationSlotEntity first = futureSlot(team);
        ReservationSlotEntity second = futureSlot(team);
        UUID group = UUID.randomUUID();
        Long user = user();
        ReservationEntity primary = savePending(team, first, user, NOW.minusSeconds(25 * 3600L), group, true);
        ReservationEntity sibling = savePending(team, second, user, NOW.minusSeconds(25 * 3600L), group, false);

        List<ReservationPendingExpireService.PendingExpireUnit> units = ownedUnits();
        assertThat(units).hasSize(1);
        assertThat(units.get(0).rows()).extracting(ReservationEntity::getId)
                .containsExactlyInAnyOrder(primary.getId(), sibling.getId());
        batch.expirePendingReservations();

        assertSoftly(soft -> {
            soft.assertThat(reload(primary).getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            soft.assertThat(reload(sibling).getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            soft.assertThat(reload(first).getBookedCount()).isZero();
            soft.assertThat(reload(second).getBookedCount()).isZero();
            soft.assertThat(notificationTypes(user)).containsExactly("RESERVATION_PENDING_EXPIRED");
        });
    }

    @Test
    @DisplayName("AC1730-5b: 真の候補501単位は500に制限し最後のgroup兄弟を落とさない")
    void 五百上限は行数でなく失効単位を数える() {
        Long team = team("Asia/Tokyo", 24, true);
        ReservationSlotEntity slot = slot(team, LocalDate.of(2000, 8, 12), LocalDate.of(2000, 8, 12),
                LocalTime.of(10, 0), LocalTime.of(10, 30), 503, 502);
        Long user = user();
        List<ReservationEntity> rows = new ArrayList<>();
        for (int i = 0; i < 499; i++) {
            rows.add(pendingEntity(team, slot, user, NOW.minusSeconds(25 * 3600L), null, true));
        }
        UUID group = UUID.randomUUID();
        rows.add(pendingEntity(team, slot, user, NOW.minusSeconds(25 * 3600L), group, true));
        rows.add(pendingEntity(team, slot, user, NOW.minusSeconds(25 * 3600L), group, false));
        rows.add(pendingEntity(team, slot, user, NOW.minusSeconds(25 * 3600L), null, true));
        List<ReservationEntity> saved = reservations.saveAllAndFlush(rows);

        List<ReservationPendingExpireService.PendingExpireUnit> units = ownedUnits();

        assertThat(units).hasSize(500);
        assertThat(units).extracting(u -> u.primary().getId()).doesNotContain(saved.get(501).getId());
        assertThat(units.stream().filter(u -> group.equals(u.primary().getGroupId())).toList())
                .singleElement().satisfies(unit -> assertThat(unit.rows()).extracting(ReservationEntity::getId)
                        .containsExactlyInAnyOrder(saved.get(499).getId(), saved.get(500).getId()));
        // 500件の通知を作る必要はない。副作用/原子性は直前の小groupが本物batchで検証する。
    }

    @Test
    @DisplayName("AC1730-5c: false未来候補500行が先行しても真のexpired1件を上限で落とさない")
    void 未来枠が上限を占めて真の期限切れを飢餓にしない() {
        Long team = team("Asia/Tokyo", 24, true);
        ReservationSlotEntity future = slot(team, LocalDate.of(2000, 8, 10),
                LocalDate.of(2000, 8, 11), LocalTime.of(23, 30), LocalTime.of(0, 30), 501, 500);
        Long user = user();
        List<ReservationEntity> falseRows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            falseRows.add(pendingEntity(team, future, user, NOW.minusSeconds(3600), null, true));
        }
        reservations.saveAllAndFlush(falseRows);
        ReservationSlotEntity expiredSlot = futureSlot(team);
        ReservationEntity expired = pending(team, expiredSlot, NOW.minusSeconds(25 * 3600L), null, true);

        List<ReservationPendingExpireService.PendingExpireUnit> units = ownedUnits();

        // REDではここで止め、誤った500件の全通知を追加batchで大量生成しない。
        assertThat(units).extracting(u -> u.primary().getId()).containsExactly(expired.getId());
        batch.expirePendingReservations();
        assertThat(reload(expired).getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservations.findAllById(falseRows.stream().map(ReservationEntity::getId).toList()))
                .hasSize(500).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.PENDING));
        assertThat(reload(future).getBookedCount()).isEqualTo(500);
        assertThat(notificationTypes(user)).isEmpty();
    }

    private void setNow(Instant instant) {
        ReflectionTestUtils.setField(serviceTarget, "clock", Clock.fixed(instant, ZoneOffset.UTC));
    }

    private Long team(String timezone, Integer hours, boolean policyPresent) {
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        TeamEntity team = teams.saveAndFlush(TeamEntity.builder().name("CMP1730-" + suffix)
                .slug("cmp1730-" + suffix).timezone(timezone)
                .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build());
        ownedTeams.add(team.getId());
        if (policyPresent) {
            policies.saveAndFlush(ReservationPolicyEntity.builder().teamId(team.getId())
                    .pendingExpireHours(hours).build());
        }
        return team.getId();
    }

    private Long user() {
        Long id = USERS.incrementAndGet();
        ownedUsers.add(id);
        return id;
    }

    private ReservationSlotEntity futureSlot(Long team) {
        return slot(team, LocalDate.of(2000, 8, 12), LocalDate.of(2000, 8, 12),
                LocalTime.of(10, 0), LocalTime.of(10, 30));
    }

    private ReservationSlotEntity slot(Long team, LocalDate startDate, LocalDate endDate,
                                       LocalTime start, LocalTime end) {
        return slot(team, startDate, endDate, start, end, 2, 1);
    }

    private ReservationSlotEntity slot(Long team, LocalDate startDate, LocalDate endDate,
                                       LocalTime start, LocalTime end, int capacity, int booked) {
        // AVAILABLEのまま減算できる枠にし、この試練に無関係な待機通知の非同期連鎖を発生させない。
        return slots.saveAndFlush(ReservationSlotEntity.builder().teamId(team).title("CMP1730枠")
                .slotDate(startDate).endDate(endDate).startTime(start).endTime(end)
                .capacity(capacity).bookedCount(booked).slotStatus(SlotStatus.AVAILABLE).build());
    }

    private ReservationEntity pending(Long team, ReservationSlotEntity slot, Instant booked,
                                      UUID group, boolean primary) {
        return savePending(team, slot, user(), booked, group, primary);
    }

    private ReservationEntity savePending(Long team, ReservationSlotEntity slot, Long user, Instant booked,
                                          UUID group, boolean primary) {
        return reservations.saveAndFlush(pendingEntity(team, slot, user, booked, group, primary));
    }

    private ReservationEntity pendingEntity(Long team, ReservationSlotEntity slot, Long user, Instant booked,
                                            UUID group, boolean primary) {
        return ReservationEntity.builder().teamId(team).userId(user).lineId(1L)
                .reservationSlotId(slot.getId()).status(ReservationStatus.PENDING)
                .bookedAt(LocalDateTime.ofInstant(booked, UserZoneLocalDateTimeParser.SERVER_ZONE))
                .groupId(group).isGroupPrimary(primary).build();
    }

    private List<ReservationPendingExpireService.PendingExpireUnit> ownedUnits() {
        return service.findExpirableUnits().stream().filter(unit -> ownedTeams.contains(unit.primary().getTeamId()))
                .toList();
    }

    private List<Long> candidateIds() {
        return ownedUnits().stream().map(unit -> unit.primary().getId()).toList();
    }

    private void assertNoForeignCandidatesForLimitFixture() {
        // 共通contextの他試練が残した歴史PENDINGを消して試練を通さない。
        // 上限窓を汚す先行候補があれば契約REDとは区別できるfixture理由で止める。
        assertThat(service.findExpirableUnits()).as("500境界fixtureの前提: 他試練の候補がglobal窓を占有していない")
                .isEmpty();
    }

    private ReservationEntity reload(ReservationEntity row) {
        return reservations.findById(row.getId()).orElseThrow();
    }

    private ReservationSlotEntity reload(ReservationSlotEntity slot) {
        return slots.findById(slot.getId()).orElseThrow();
    }

    private List<String> notificationTypes(Long user) {
        return notifications.findAll().stream().filter(n -> user.equals(n.getUserId()))
                .map(NotificationEntity::getNotificationType).toList();
    }

    private void assertUnchanged(ReservationEntity row, ReservationSlotEntity slot, List<Long> candidates) {
        assertSoftly(soft -> {
            soft.assertThat(candidates).as("本人チームの未来枠は失効候補0").isEmpty();
            soft.assertThat(reload(row).getStatus()).as("通常batch後もPENDING").isEqualTo(ReservationStatus.PENDING);
            soft.assertThat(reload(slot).getBookedCount()).as("予約数を減らさない").isEqualTo(1);
            soft.assertThat(reload(slot).getSlotStatus()).isEqualTo(SlotStatus.AVAILABLE);
            soft.assertThat(notificationTypes(row.getUserId())).as("失効通知を作らない").isEmpty();
        });
    }

    private void assertOutcome(ReservationEntity row, ReservationSlotEntity slot, boolean candidate, boolean expired) {
        assertSoftly(soft -> {
            soft.assertThat(candidate).as("候補の失効境界").isEqualTo(expired);
            soft.assertThat(reload(row).getStatus()).isEqualTo(expired ? ReservationStatus.CANCELLED : ReservationStatus.PENDING);
            soft.assertThat(reload(slot).getBookedCount()).isEqualTo(expired ? 0 : 1);
            soft.assertThat(notificationTypes(row.getUserId()))
                    .containsExactlyElementsOf(expired ? List.of("RESERVATION_PENDING_EXPIRED") : List.of());
        });
    }
}
