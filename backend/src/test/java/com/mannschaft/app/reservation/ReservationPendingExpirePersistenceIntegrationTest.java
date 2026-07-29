package com.mannschaft.app.reservation;

import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.entity.ReservationWaitlistEntryEntity;
import com.mannschaft.app.reservation.repository.ReservationPolicyRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationWaitlistEntryRepository;
import com.mannschaft.app.reservation.service.ReservationPendingExpireBatchService;
import com.mannschaft.app.reservation.service.ReservationPendingExpireService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.awaitility.Awaitility;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仮押さえ(PENDING)自動失効の実 MySQL 結合テスト（F03.4.5 §6.3・W2-6）。
 *
 * <p>受け入れ条件の対応:</p>
 * <ul>
 *   <li><b>AC-6-3</b>: {@code pending_expire_hours=24} で {@code booked_at+24h+1分} は失効・
 *       {@code booked_at+23h59分} は不変（境界は「判定基準時刻」を明示的に渡して厳密に固定する）。
 *       失効の効果（CANCELLED / cancelledBy=SYSTEM / 枠復帰 / 申込者通知）も検証する</li>
 *   <li><b>AC-6-4</b>: {@code pending_expire_hours=NULL} のチームは (a)(b) とも失効しない</li>
 *   <li><b>AC-6-5</b>: 枠終了時刻を経過した PENDING は経過時間に関わらず失効する（殿の裁定）</li>
 *   <li><b>AC-6-6</b>: グループ PENDING は構成全行が一括 CANCELLED（部分失効ゼロ）</li>
 *   <li><b>AC-6-7</b>: FULL→AVAILABLE 遷移時<b>のみ</b>キャンセル待ちへ通知が飛ぶ</li>
 *   <li><b>AC-6-17</b>: 対象抽出が件数比例のクエリ（N+1）を出さない</li>
 * </ul>
 *
 * <p><b>時刻の扱い</b>: 本文の効果検証は実 {@code Clock} を使い、{@code booked_at} を過去へずらして
 * 十分なマージン（数時間）を取る。「+1 分」「-1 分」の分単位境界だけは実時間の経過で結果が揺れるため、
 * 判定基準時刻を引数で明示できる {@code ReservationRepository#findExpirablePendingPrimaryRows} を
 * 直接呼んで固定する（実時刻に依存させない）。</p>
 */
@DisplayName("仮押さえ自動失効 永続化結合テスト（実MySQL・F03.4.5 §6.3）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReservationPendingExpirePersistenceIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ReservationPendingExpireBatchService batchService;
    @Autowired
    private ReservationPendingExpireService pendingExpireService;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private ReservationSlotRepository slotRepository;
    @Autowired
    private ReservationPolicyRepository policyRepository;
    @Autowired
    private ReservationWaitlistEntryRepository waitlistRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /** チーム／ユーザーIDの衝突を避けるための採番（他テストのシードと混ざらないよう十分大きい値から）。 */
    private static final AtomicLong SEQ = new AtomicLong(980_000L);

    private static long nextId() {
        return SEQ.incrementAndGet();
    }

    private static final LocalDate FUTURE = LocalDate.now().plusMonths(1);

    // ────────────────────────────────────────────────────────────
    // シードヘルパー
    // ────────────────────────────────────────────────────────────

    private void seedPolicy(Long teamId, Integer pendingExpireHours) {
        policyRepository.save(ReservationPolicyEntity.builder()
                .teamId(teamId)
                .pendingExpireHours(pendingExpireHours)
                .build());
    }

    private ReservationSlotEntity seedSlot(Long teamId, LocalDate date, LocalTime start,
                                           SlotStatus status, int capacity, int booked) {
        return slotRepository.save(ReservationSlotEntity.builder()
                .teamId(teamId)
                .title("枠")
                .slotDate(date)
                .startTime(start)
                .endTime(start.plusMinutes(30))
                .capacity(capacity)
                .bookedCount(booked)
                .slotStatus(status)
                .build());
    }

    private ReservationEntity seedPending(Long teamId, Long userId, Long slotId,
                                          LocalDateTime bookedAt, UUID groupId, boolean primary) {
        return reservationRepository.save(ReservationEntity.builder()
                .teamId(teamId)
                .userId(userId)
                .lineId(1L)
                .reservationSlotId(slotId)
                .status(ReservationStatus.PENDING)
                .bookedAt(bookedAt)
                .groupId(groupId)
                .isGroupPrimary(primary)
                .build());
    }

    private ReservationEntity reload(Long reservationId) {
        return reservationRepository.findById(reservationId).orElseThrow();
    }

    // ────────────────────────────────────────────────────────────
    // AC-6-3: 24 時間境界（判定基準時刻を明示して厳密に固定）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-6-3(境界): booked_at+24h+1分 は失効対象・booked_at+23h59分 は対象外")
    void 二十四時間境界が厳密である() {
        Long teamId = nextId();
        Long userId = nextId();
        seedPolicy(teamId, 24);
        LocalDateTime bookedAt = LocalDateTime.of(2026, 6, 1, 9, 0);
        // 枠は十分未来（(b) 枠終了経過の条件を巻き込まないようにする）
        ReservationSlotEntity slot = seedSlot(teamId, FUTURE, LocalTime.of(10, 0), SlotStatus.FULL, 1, 1);
        ReservationEntity pending = seedPending(teamId, userId, slot.getId(), bookedAt, null, true);

        LocalDateTime justOver = bookedAt.plusHours(24).plusMinutes(1);
        LocalDateTime justUnder = bookedAt.plusHours(23).plusMinutes(59);

        assertThat(expirableIds(justOver))
                .as("24h+1分 経過は失効対象")
                .contains(pending.getId());
        assertThat(expirableIds(justUnder))
                .as("23h59分 経過は対象外（境界の手前）")
                .doesNotContain(pending.getId());
        assertThat(expirableIds(bookedAt.plusHours(24)))
                .as("ちょうど 24h 経過は失効対象（booked_at + H <= now）")
                .contains(pending.getId());
    }

    /** 判定基準時刻 {@code now} を明示して失効対象の予約 ID を引く（実時刻非依存）。 */
    private List<Long> expirableIds(LocalDateTime now) {
        return reservationRepository.findExpirablePendingPrimaryRows(
                        ReservationStatus.PENDING, now, now.toLocalDate(), now.toLocalTime(),
                        ReservationPolicyEntity.DEFAULT_PENDING_EXPIRE_HOURS,
                        org.springframework.data.domain.PageRequest.of(0, 500))
                .stream()
                .map(ReservationEntity::getId)
                .toList();
    }

    @Test
    @DisplayName("AC-6-3(効果): 失効で CANCELLED/SYSTEM・枠復帰・申込者へ通知が起きる")
    void 失効の効果がDBに現れる() {
        Long teamId = nextId();
        Long userId = nextId();
        seedPolicy(teamId, 24);
        // capacity 1 / booked 1 / FULL の枠を 25 時間前に仮押さえ（(a) のみで失効する状況）
        ReservationSlotEntity slot = seedSlot(teamId, FUTURE, LocalTime.of(11, 0), SlotStatus.FULL, 1, 1);
        ReservationEntity pending = seedPending(
                teamId, userId, slot.getId(), LocalDateTime.now().minusHours(25), null, true);

        int expired = batchService.expirePendingReservations();

        assertThat(expired).as("1 行が失効する").isGreaterThanOrEqualTo(1);

        ReservationEntity after = reload(pending.getId());
        assertThat(after.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(after.getCancelledBy())
                .as("システムによる自動キャンセルであることが監査可能であること")
                .isEqualTo(CancelledBy.SYSTEM);
        assertThat(after.getCancelReason()).as("定型文が保存されること").isNotBlank();
        assertThat(after.getCancelledAt()).isNotNull();

        ReservationSlotEntity slotAfter = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(slotAfter.getBookedCount()).as("枠の予約数が戻ること").isZero();
        assertThat(slotAfter.getSlotStatus()).as("FULL→AVAILABLE へ復帰すること").isEqualTo(SlotStatus.AVAILABLE);

        assertThat(notificationsOf(userId))
                .as("申込者へ RESERVATION_PENDING_EXPIRED が 1 件届くこと")
                .extracting(NotificationEntity::getNotificationType)
                .containsExactly("RESERVATION_PENDING_EXPIRED");
    }

    private List<NotificationEntity> notificationsOf(Long userId) {
        return notificationRepository.findAll().stream()
                .filter(n -> userId.equals(n.getUserId()))
                .toList();
    }

    // ────────────────────────────────────────────────────────────
    // AC-6-4: pending_expire_hours = NULL のチームは失効しない
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-6-4: pending_expire_hours=NULL のチームは経過時間・枠終了経過とも失効しない")
    void 無効化チームは失効しない() {
        Long teamId = nextId();
        Long userId = nextId();
        seedPolicy(teamId, null);
        // (a) 500 時間経過 かつ (b) 枠終了も経過 という二重に失効しそうな条件を作る
        ReservationSlotEntity pastSlot =
                seedSlot(teamId, LocalDate.now().minusDays(3), LocalTime.of(10, 0), SlotStatus.FULL, 1, 1);
        ReservationEntity pending = seedPending(
                teamId, userId, pastSlot.getId(), LocalDateTime.now().minusHours(500), null, true);

        batchService.expirePendingReservations();

        assertThat(reload(pending.getId()).getStatus())
                .as("明示的に無効化されたチームは何時間経っても PENDING のまま")
                .isEqualTo(ReservationStatus.PENDING);
        assertThat(notificationsOf(userId)).as("通知も飛ばない").isEmpty();
    }

    @Test
    @DisplayName("ポリシー行が無いチームは既定 24 時間で失効する（GET 応答の既定値と挙動を一致させる）")
    void ポリシー行なしは既定24時間で失効する() {
        Long teamId = nextId();
        Long userId = nextId();
        // seedPolicy を呼ばない ＝ reservation_policies に行が存在しないチーム（大多数の既存チーム）
        ReservationSlotEntity slot = seedSlot(teamId, FUTURE, LocalTime.of(12, 0), SlotStatus.FULL, 1, 1);
        ReservationEntity pending = seedPending(
                teamId, userId, slot.getId(), LocalDateTime.now().minusHours(25), null, true);

        batchService.expirePendingReservations();

        assertThat(reload(pending.getId()).getStatus())
                .as("行が無いチームも既定 24 時間で失効する（設計書 §6.3『新規・既存とも既定24時間』）")
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    // ────────────────────────────────────────────────────────────
    // AC-6-5: 枠終了時刻を経過した PENDING は経過時間に関わらず失効
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-6-5: 枠終了を過ぎた PENDING は booked_at が新しくても失効する")
    void 枠終了経過は経過時間に関わらず失効する() {
        Long teamId = nextId();
        Long userId = nextId();
        seedPolicy(teamId, 24);
        // 枠は昨日で終了済み。仮押さえは 1 時間前（(a) の 24 時間は全く経っていない）。
        ReservationSlotEntity pastSlot =
                seedSlot(teamId, LocalDate.now().minusDays(1), LocalTime.of(10, 0), SlotStatus.FULL, 1, 1);
        ReservationEntity pending = seedPending(
                teamId, userId, pastSlot.getId(), LocalDateTime.now().minusHours(1), null, true);

        batchService.expirePendingReservations();

        assertThat(reload(pending.getId()).getStatus())
                .as("承認されないまま予約日を過ぎた仮押さえは永久 PENDING で残さない（殿の裁定）")
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    @DisplayName("AC-6-5(境界): 枠が未来のうちは (a) を満たさない限り失効しない")
    void 未来枠は経過時間を満たすまで失効しない() {
        Long teamId = nextId();
        Long userId = nextId();
        seedPolicy(teamId, 24);
        ReservationSlotEntity futureSlot = seedSlot(teamId, FUTURE, LocalTime.of(13, 0), SlotStatus.FULL, 1, 1);
        ReservationEntity pending = seedPending(
                teamId, userId, futureSlot.getId(), LocalDateTime.now().minusHours(1), null, true);

        batchService.expirePendingReservations();

        assertThat(reload(pending.getId()).getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    // ────────────────────────────────────────────────────────────
    // AC-6-6: グループは一括失効（部分失効を作らない）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-6-6: グループ PENDING は構成全行が CANCELLED になり部分失効が発生しない")
    void グループは一括失効する() {
        Long teamId = nextId();
        Long userId = nextId();
        seedPolicy(teamId, 24);
        UUID groupId = UUID.randomUUID();
        LocalDateTime bookedAt = LocalDateTime.now().minusHours(25);
        ReservationSlotEntity s1 = seedSlot(teamId, FUTURE, LocalTime.of(14, 0), SlotStatus.FULL, 1, 1);
        ReservationSlotEntity s2 = seedSlot(teamId, FUTURE, LocalTime.of(14, 30), SlotStatus.FULL, 1, 1);
        ReservationSlotEntity s3 = seedSlot(teamId, FUTURE, LocalTime.of(15, 0), SlotStatus.FULL, 1, 1);
        ReservationEntity primary = seedPending(teamId, userId, s1.getId(), bookedAt, groupId, true);
        ReservationEntity sib2 = seedPending(teamId, userId, s2.getId(), bookedAt, groupId, false);
        ReservationEntity sib3 = seedPending(teamId, userId, s3.getId(), bookedAt, groupId, false);

        batchService.expirePendingReservations();

        assertThat(List.of(primary.getId(), sib2.getId(), sib3.getId()))
                .allSatisfy(id -> assertThat(reload(id).getStatus())
                        .as("グループ全行が CANCELLED（部分失効ゼロ）")
                        .isEqualTo(ReservationStatus.CANCELLED));
        // 全枠が復帰している（兄弟行の枠も取り残さない）
        assertThat(List.of(s1.getId(), s2.getId(), s3.getId()))
                .allSatisfy(id -> assertThat(slotRepository.findById(id).orElseThrow().getSlotStatus())
                        .isEqualTo(SlotStatus.AVAILABLE));
        // 通知はグループにつき 1 件（兄弟行ぶん重複送信しない）
        assertThat(notificationsOf(userId))
                .as("グループは代表行基準で 1 通のみ")
                .hasSize(1);
    }

    // ────────────────────────────────────────────────────────────
    // AC-6-7: FULL→AVAILABLE 遷移時のみキャンセル待ちへ通知
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-6-7: FULL→AVAILABLE 遷移でキャンセル待ちへ通知が飛ぶ")
    void 満席枠の失効でキャンセル待ちへ通知が飛ぶ() {
        Long teamId = nextId();
        Long applicant = nextId();
        Long waiter = nextId();
        seedPolicy(teamId, 24);
        ReservationSlotEntity slot = seedSlot(teamId, FUTURE, LocalTime.of(16, 0), SlotStatus.FULL, 1, 1);
        seedPending(teamId, applicant, slot.getId(), LocalDateTime.now().minusHours(25), null, true);
        waitlistRepository.save(ReservationWaitlistEntryEntity.builder()
                .teamId(teamId).slotId(slot.getId()).userId(waiter)
                .status(WaitlistStatus.WAITING).build());

        batchService.expirePendingReservations();

        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getSlotStatus())
                .isEqualTo(SlotStatus.AVAILABLE);

        // キャンセル待ち通知は ReservationWaitlistNotificationEventListener が
        // @Async("event-pool") + @TransactionalEventListener(AFTER_COMMIT) で送るため、
        // 失効処理の戻り値時点ではまだ書き込まれていないことがある（負荷時に顕在化する競合）。
        // 「届くこと」を弱めずに待つ（到達しなければタイムアウトで失敗する）。
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(notificationsOf(waiter))
                        .as("decrementAndReopen 経由なので §6.1 のキャンセル待ち通知が連鎖発火する")
                        .extracting(NotificationEntity::getNotificationType)
                        .containsExactly("RESERVATION_WAITLIST_OPENING"));
    }

    @Test
    @DisplayName("AC-6-7(境界): FULL でなかった枠では reopen イベントが出ずキャンセル待ちへ通知しない")
    void 満席でない枠では待ち通知が飛ばない() {
        Long teamId = nextId();
        Long applicant = nextId();
        Long waiter = nextId();
        seedPolicy(teamId, 24);
        // capacity 5 / booked 1 / AVAILABLE ＝ 失効しても FULL→AVAILABLE 遷移は起きない
        ReservationSlotEntity slot = seedSlot(teamId, FUTURE, LocalTime.of(17, 0), SlotStatus.AVAILABLE, 5, 1);
        seedPending(teamId, applicant, slot.getId(), LocalDateTime.now().minusHours(25), null, true);
        waitlistRepository.save(ReservationWaitlistEntryEntity.builder()
                .teamId(teamId).slotId(slot.getId()).userId(waiter)
                .status(WaitlistStatus.WAITING).build());

        batchService.expirePendingReservations();

        // 申込者への失効通知は失効 tx 内で同期送出されるため、この時点で確定している。
        assertThat(notificationsOf(applicant))
                .as("申込者本人への失効通知は届く")
                .hasSize(1);
        // 枠が FULL でなかったため reopenSlotIfFull が 0 行更新となり、そもそもイベントが発行されない
        // （＝非同期処理がキューされない）。待っても増えないことを一定時間保ち続けることで確認する。
        Awaitility.await()
                .during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(notificationsOf(waiter))
                        .as("遷移が起きていないので通知を撃たない（イベントの空撃ち禁止）")
                        .isEmpty());
    }

    // ────────────────────────────────────────────────────────────
    // AC-6-10: 対象ゼロで副作用ゼロ（実 DB 観測）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-6-10(実DB): 対象ゼロのチームでは通知が 1 件も作られない")
    void 対象ゼロのチームでは通知が作られない() {
        // UT 側は verify(never()) で「expireUnit が呼ばれない」ことを見るが、それは
        // 「expireUnit が唯一の通知入口」という現在の構造への依存であり、将来別経路が
        // 生えると素通りする。実 DB で「通知行が増えていない」ことを直接観測する。
        Long teamId = nextId();
        Long userId = nextId();
        seedPolicy(teamId, 24);
        // 失効条件を満たさない PENDING（未来枠・仮押さえ直後）だけを置く。
        ReservationSlotEntity futureSlot = seedSlot(teamId, FUTURE, LocalTime.of(18, 0), SlotStatus.FULL, 1, 1);
        ReservationEntity pending = seedPending(
                teamId, userId, futureSlot.getId(), LocalDateTime.now().minusMinutes(5), null, true);

        batchService.expirePendingReservations();

        assertThat(reload(pending.getId()).getStatus())
                .as("失効条件を満たさないので PENDING のまま")
                .isEqualTo(ReservationStatus.PENDING);
        assertThat(notificationsOf(userId))
                .as("対象ゼロなら通知は 1 件も作られない（実 DB 観測）")
                .isEmpty();
        assertThat(slotRepository.findById(futureSlot.getId()).orElseThrow().getBookedCount())
                .as("枠の予約数も動かない（空撃ちの decrement をしていない）")
                .isEqualTo(1);
        assertThat(slotRepository.findById(futureSlot.getId()).orElseThrow().getSlotStatus())
                .as("FULL のまま（reopen もしていない）")
                .isEqualTo(SlotStatus.FULL);
    }

    // ────────────────────────────────────────────────────────────
    // AC-6-17: 対象抽出が N+1 を出さない
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-6-17: 対象抽出のクエリ本数が対象件数に比例しない")
    void 対象抽出がN加1を出さない() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        // 1 件だけのチームで抽出したときのステートメント数
        Long teamA = nextId();
        seedPolicy(teamA, 24);
        seedExpirable(teamA, 1);
        statistics.clear();
        pendingExpireService.findExpirableUnits();
        long baseline = statistics.getPrepareStatementCount();

        // 対象を 5 件に増やしても、抽出のステートメント数は増えない
        Long teamB = nextId();
        seedPolicy(teamB, 24);
        seedExpirable(teamB, 5);
        statistics.clear();
        pendingExpireService.findExpirableUnits();
        long withMore = statistics.getPrepareStatementCount();

        assertThat(withMore)
                .as("件数が増えてもクエリ本数は一定（slot/policy を join・兄弟行と枠は一括取得）: "
                        + "baseline=%d, withMore=%d", baseline, withMore)
                .isEqualTo(baseline);
    }

    private void seedExpirable(Long teamId, int count) {
        for (int i = 0; i < count; i++) {
            ReservationSlotEntity slot = seedSlot(
                    teamId, FUTURE, LocalTime.of(8, 0).plusMinutes(30L * i), SlotStatus.FULL, 1, 1);
            seedPending(teamId, nextId(), slot.getId(), LocalDateTime.now().minusHours(25), null, true);
        }
    }
}
