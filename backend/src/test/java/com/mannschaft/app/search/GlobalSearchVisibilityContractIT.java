package com.mannschaft.app.search;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.EventStatus;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.facility.entity.FacilityBookingEntity;
import com.mannschaft.app.facility.entity.SharedFacilityEntity;
import com.mannschaft.app.facility.repository.FacilityBookingRepository;
import com.mannschaft.app.facility.repository.SharedFacilityRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.queue.QueueScopeType;
import com.mannschaft.app.queue.TicketSource;
import com.mannschaft.app.queue.entity.QueueCategoryEntity;
import com.mannschaft.app.queue.entity.QueueTicketEntity;
import com.mannschaft.app.queue.repository.QueueCategoryRepository;
import com.mannschaft.app.queue.repository.QueueTicketRepository;
import com.mannschaft.app.safetycheck.SafetyCheckScopeType;
import com.mannschaft.app.safetycheck.SafetyCheckStatus;
import com.mannschaft.app.safetycheck.entity.SafetyCheckEntity;
import com.mannschaft.app.safetycheck.repository.SafetyCheckRepository;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.search.dto.SearchResultResponse;
import com.mannschaft.app.search.service.GlobalSearchService;
import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 認可根治 Wave6: 横断検索（{@code GET /api/v1/search}）が、閲覧者の可視スコープ外のデータを
 * 漏らさないことを検証する契約テスト。
 *
 * <p>金型: {@code TimelineSearchScopeContractIT}（Wave3-B7-timeline で敷設した
 * 閲覧者依存フィルタの契約テスト）。同テストと同じく「越境の拒否」と「正常系（自分のスコープの
 * データは出る）」の両面を固定する。認可を締めすぎて横断検索の価値を潰していないことを
 * 正常系テストが機械的に保証する。</p>
 *
 * <p>検証対象は本 PR でフィルタを敷設した 7 種別のうち、scope 構造を持つ 6 種別
 * （schedules / events / reservations / shifts / safetyChecks / queues）と users。
 * teams / organizations は #2406 の {@code ScopeSearchVisibilityContractIT} が担当する。</p>
 *
 * <p>クラスレベル {@code @Transactional} を使わず、{@code @BeforeEach}/{@code @AfterEach} の
 * native DELETE で後始末する（対象 ID を絞り込むため他テストのデータには触れない）。</p>
 */
@DisplayName("横断検索 可視性契約テスト（認可根治 Wave6）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class GlobalSearchVisibilityContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private GlobalSearchService globalSearchService;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private SharedFacilityRepository sharedFacilityRepository;

    @Autowired
    private FacilityBookingRepository facilityBookingRepository;

    @Autowired
    private ShiftScheduleRepository shiftScheduleRepository;

    @Autowired
    private SafetyCheckRepository safetyCheckRepository;

    @Autowired
    private QueueCategoryRepository queueCategoryRepository;

    @Autowired
    private QueueTicketRepository queueTicketRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // --- テスト用ユーザー（高位ID・seed と衝突しない） ---
    /** TEAM_A / ORG_A に所属する閲覧者。 */
    private static final Long USER_INSIDER = 92_601L;
    /** どのスコープにも所属しない第三者。 */
    private static final Long USER_OUTSIDER = 92_602L;

    // --- スコープ ---
    private static final Long TEAM_A = 70_601L;
    private static final Long ORG_A = 80_601L;

    /** 他テストの既存データと衝突しない一意キーワード。 */
    private static final String KW = "zw6globalsearchkw";

    @BeforeEach
    void setUp() {
        cleanUpTestData();
        membershipRepository.saveAndFlush(MembershipEntity.builder()
                .userId(USER_INSIDER)
                .scopeType(ScopeType.TEAM)
                .scopeId(TEAM_A)
                .roleKind(RoleKind.MEMBER)
                .joinedAt(LocalDateTime.now())
                .build());
        membershipRepository.saveAndFlush(MembershipEntity.builder()
                .userId(USER_INSIDER)
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(ORG_A)
                .roleKind(RoleKind.MEMBER)
                .joinedAt(LocalDateTime.now())
                .build());
    }

    @AfterEach
    void tearDown() {
        cleanUpTestData();
    }

    /** 本 IT が作成したデータのみを対象 ID で絞って掃除する（他テストのデータは触らない）。 */
    private void cleanUpTestData() {
        jdbcTemplate.update("DELETE FROM memberships WHERE user_id IN (?, ?)", USER_INSIDER, USER_OUTSIDER);
        jdbcTemplate.update("DELETE FROM schedules WHERE title LIKE ?", KW + "%");
        jdbcTemplate.update("DELETE FROM events WHERE subtitle LIKE ?", KW + "%");
        jdbcTemplate.update("DELETE FROM facility_bookings WHERE purpose LIKE ?", KW + "%");
        jdbcTemplate.update("DELETE FROM shared_facilities WHERE name LIKE ?", KW + "%");
        jdbcTemplate.update("DELETE FROM shift_schedules WHERE title LIKE ?", KW + "%");
        jdbcTemplate.update("DELETE FROM safety_checks WHERE title LIKE ?", KW + "%");
        jdbcTemplate.update("DELETE FROM queue_tickets WHERE ticket_number LIKE ?", KW + "%");
        jdbcTemplate.update("DELETE FROM queue_categories WHERE name LIKE ?", KW + "%");
        jdbcTemplate.update("DELETE FROM users WHERE display_name LIKE ?", KW + "%");
    }

    /** 指定利用者として横断検索を実行し、種別ごとのヒット ID 一覧を返す。 */
    private List<Object> searchIds(Long userId, String type) {
        SearchResultResponse response = globalSearchService.search(KW, userId);
        List<Map<String, Object>> rows = response.getResults().get(type);
        assertThat(rows).as("種別 %s が結果に含まれること", type).isNotNull();
        return rows.stream().map(r -> r.get("id")).toList();
    }

    // ================================================================
    // schedules
    // ================================================================
    @Nested
    @DisplayName("schedules")
    class Schedules {

        private Long saveTeamSchedule(Long teamId) {
            return scheduleRepository.saveAndFlush(ScheduleEntity.builder()
                    .teamId(teamId)
                    .title(KW + " チーム練習")
                    .startAt(LocalDateTime.of(2026, 5, 1, 10, 0))
                    .eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .build()).getId();
        }

        @Test
        @DisplayName("正常系: 所属チームのスケジュールは検索に出る")
        void 所属チームのスケジュールは出る() {
            Long id = saveTeamSchedule(TEAM_A);
            assertThat(searchIds(USER_INSIDER, "schedules")).contains(id);
        }

        @Test
        @DisplayName("[本丸] 非所属チームのスケジュールは第三者の検索に出ない")
        void 非所属チームのスケジュールは出ない() {
            Long id = saveTeamSchedule(TEAM_A);
            assertThat(searchIds(USER_OUTSIDER, "schedules")).doesNotContain(id);
        }

        @Test
        @DisplayName("正常系: 自分の個人スケジュールは検索に出る")
        void 自分の個人スケジュールは出る() {
            Long id = scheduleRepository.saveAndFlush(ScheduleEntity.builder()
                    .userId(USER_OUTSIDER)
                    .title(KW + " 個人予定")
                    .startAt(LocalDateTime.of(2026, 5, 2, 10, 0))
                    .eventType(EventType.OTHER)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .build()).getId();

            assertThat(searchIds(USER_OUTSIDER, "schedules")).contains(id);
        }

        @Test
        @DisplayName("[本丸] 他人の個人スケジュールは検索に出ない")
        void 他人の個人スケジュールは出ない() {
            Long id = scheduleRepository.saveAndFlush(ScheduleEntity.builder()
                    .userId(USER_INSIDER)
                    .title(KW + " 他人の個人予定")
                    .startAt(LocalDateTime.of(2026, 5, 3, 10, 0))
                    .eventType(EventType.OTHER)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .build()).getId();

            assertThat(searchIds(USER_OUTSIDER, "schedules")).doesNotContain(id);
        }
    }

    // ================================================================
    // events
    // ================================================================
    @Nested
    @DisplayName("events")
    class Events {

        private Long saveEvent(EventScopeType scopeType, Long scopeId, EventVisibility visibility, Long createdBy) {
            return eventRepository.saveAndFlush(EventEntity.builder()
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .slug(KW + "-event-" + System.nanoTime())
                    .subtitle(KW + " イベント")
                    .venueName(KW + " 会場")
                    .visibility(visibility)
                    .status(EventStatus.PUBLISHED)
                    .createdBy(createdBy)
                    .build()).getId();
        }

        @Test
        @DisplayName("正常系: 所属組織のイベントは検索に出る")
        void 所属組織のイベントは出る() {
            Long id = saveEvent(EventScopeType.ORGANIZATION, ORG_A, EventVisibility.MEMBERS_ONLY, USER_INSIDER);
            assertThat(searchIds(USER_INSIDER, "events")).contains(id);
        }

        @Test
        @DisplayName("[本丸] 非所属組織のメンバー限定イベントは第三者の検索に出ない")
        void 非所属組織のイベントは出ない() {
            Long id = saveEvent(EventScopeType.ORGANIZATION, ORG_A, EventVisibility.MEMBERS_ONLY, USER_INSIDER);
            assertThat(searchIds(USER_OUTSIDER, "events")).doesNotContain(id);
        }

        @Test
        @DisplayName("正常系: 一般公開イベントは所属を問わず検索に出る")
        void 一般公開イベントは所属不問で出る() {
            Long id = saveEvent(EventScopeType.TEAM, TEAM_A, EventVisibility.PUBLIC, USER_INSIDER);
            assertThat(searchIds(USER_OUTSIDER, "events")).contains(id);
        }
    }

    // ================================================================
    // reservations（施設予約）
    // ================================================================
    @Nested
    @DisplayName("reservations")
    class Reservations {

        private Long saveFacility(String scopeType, Long scopeId) {
            return sharedFacilityRepository.saveAndFlush(SharedFacilityEntity.builder()
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .name(KW + " 施設")
                    .build()).getId();
        }

        private Long saveBooking(Long facilityId, Long bookedBy) {
            return facilityBookingRepository.saveAndFlush(FacilityBookingEntity.builder()
                    .facilityId(facilityId)
                    .bookedBy(bookedBy)
                    .bookingDate(LocalDate.of(2026, 7, 1))
                    .timeFrom(LocalTime.of(9, 0))
                    .timeTo(LocalTime.of(11, 0))
                    .purpose(KW + " 会議のため")
                    .usageFee(BigDecimal.ZERO)
                    .equipmentFee(BigDecimal.ZERO)
                    .totalFee(BigDecimal.ZERO)
                    .build()).getId();
        }

        @Test
        @DisplayName("正常系: 所属チームが保有する施設の予約は検索に出る")
        void 所属スコープの施設予約は出る() {
            Long id = saveBooking(saveFacility("TEAM", TEAM_A), USER_INSIDER);
            assertThat(searchIds(USER_INSIDER, "reservations")).contains(id);
        }

        @Test
        @DisplayName("[本丸] 非所属スコープの施設予約は第三者の検索に出ない（利用目的の漏洩防止）")
        void 非所属スコープの施設予約は出ない() {
            Long id = saveBooking(saveFacility("TEAM", TEAM_A), USER_INSIDER);
            assertThat(searchIds(USER_OUTSIDER, "reservations")).doesNotContain(id);
        }

        @Test
        @DisplayName("正常系: 自分が予約したものは施設スコープ非所属でも検索に出る")
        void 自分の予約は出る() {
            Long id = saveBooking(saveFacility("TEAM", TEAM_A), USER_OUTSIDER);
            assertThat(searchIds(USER_OUTSIDER, "reservations")).contains(id);
        }
    }

    // ================================================================
    // shifts
    // ================================================================
    @Nested
    @DisplayName("shifts")
    class Shifts {

        private Long saveShift(Long teamId) {
            return shiftScheduleRepository.saveAndFlush(ShiftScheduleEntity.builder()
                    .teamId(teamId)
                    .title(KW + " 5月シフト")
                    .periodType(ShiftPeriodType.MONTHLY)
                    .startDate(LocalDate.of(2026, 5, 1))
                    .endDate(LocalDate.of(2026, 5, 31))
                    .build()).getId();
        }

        @Test
        @DisplayName("正常系: 所属チームのシフト表は検索に出る")
        void 所属チームのシフトは出る() {
            Long id = saveShift(TEAM_A);
            assertThat(searchIds(USER_INSIDER, "shifts")).contains(id);
        }

        @Test
        @DisplayName("[本丸] 非所属チームのシフト表は第三者の検索に出ない")
        void 非所属チームのシフトは出ない() {
            Long id = saveShift(TEAM_A);
            assertThat(searchIds(USER_OUTSIDER, "shifts")).doesNotContain(id);
        }
    }

    // ================================================================
    // safetyChecks
    // ================================================================
    @Nested
    @DisplayName("safetyChecks")
    class SafetyChecks {

        private Long saveSafetyCheck(SafetyCheckScopeType scopeType, Long scopeId) {
            return safetyCheckRepository.saveAndFlush(SafetyCheckEntity.builder()
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .title(KW + " 安否確認")
                    .message(KW + " 地震が発生しました")
                    .isDrill(false)
                    .status(SafetyCheckStatus.ACTIVE)
                    .build()).getId();
        }

        @Test
        @DisplayName("正常系: 所属チームの安否確認は検索に出る")
        void 所属チームの安否確認は出る() {
            Long id = saveSafetyCheck(SafetyCheckScopeType.TEAM, TEAM_A);
            assertThat(searchIds(USER_INSIDER, "safetyChecks")).contains(id);
        }

        @Test
        @DisplayName("[本丸] 非所属チームの安否確認は第三者の検索に出ない（本文の漏洩防止）")
        void 非所属チームの安否確認は出ない() {
            Long id = saveSafetyCheck(SafetyCheckScopeType.TEAM, TEAM_A);
            assertThat(searchIds(USER_OUTSIDER, "safetyChecks")).doesNotContain(id);
        }

        @Test
        @DisplayName("正常系: 所属組織の安否確認は検索に出る")
        void 所属組織の安否確認は出る() {
            Long id = saveSafetyCheck(SafetyCheckScopeType.ORGANIZATION, ORG_A);
            assertThat(searchIds(USER_INSIDER, "safetyChecks")).contains(id);
        }

        @Test
        @DisplayName("GROUP スコープの安否確認は所属解決ができないため検索対象外（fail-closed）")
        void GROUPスコープは検索対象外() {
            Long id = saveSafetyCheck(SafetyCheckScopeType.GROUP, TEAM_A);
            assertThat(searchIds(USER_INSIDER, "safetyChecks")).doesNotContain(id);
        }
    }

    // ================================================================
    // queues
    // ================================================================
    @Nested
    @DisplayName("queues")
    class Queues {

        private Long saveCategory(QueueScopeType scopeType, Long scopeId) {
            return queueCategoryRepository.saveAndFlush(QueueCategoryEntity.builder()
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .name(KW + " 受付")
                    .build()).getId();
        }

        private Long saveTicket(Long categoryId, Long userId) {
            return queueTicketRepository.saveAndFlush(QueueTicketEntity.builder()
                    .categoryId(categoryId)
                    .ticketNumber(KW + "A001")
                    .guestName("山田花子")
                    .source(TicketSource.ONLINE)
                    .userId(userId)
                    .build()).getId();
        }

        @Test
        @DisplayName("正常系: 所属チームが運営するカテゴリのチケットは検索に出る")
        void 所属スコープのチケットは出る() {
            Long id = saveTicket(saveCategory(QueueScopeType.TEAM, TEAM_A), USER_INSIDER);
            assertThat(searchIds(USER_INSIDER, "queues")).contains(id);
        }

        @Test
        @DisplayName("[本丸] 非所属スコープのチケットは第三者の検索に出ない（来場者氏名の漏洩防止）")
        void 非所属スコープのチケットは出ない() {
            Long id = saveTicket(saveCategory(QueueScopeType.TEAM, TEAM_A), USER_INSIDER);
            assertThat(searchIds(USER_OUTSIDER, "queues")).doesNotContain(id);
        }

        @Test
        @DisplayName("正常系: 自分が発券したチケットはスコープ非所属でも検索に出る")
        void 自分のチケットは出る() {
            Long id = saveTicket(saveCategory(QueueScopeType.TEAM, TEAM_A), USER_OUTSIDER);
            assertThat(searchIds(USER_OUTSIDER, "queues")).contains(id);
        }
    }

    // ================================================================
    // users（最も機微: 氏名の露出・登録有無の照会）
    // ================================================================
    @Nested
    @DisplayName("users")
    class Users {

        /** TEAM_A に在籍する被検索者。 */
        private static final Long USER_TARGET = 92_603L;

        private Long saveUser(Long id, boolean searchable, UserEntity.UserStatus status) {
            UserEntity user = userRepository.saveAndFlush(UserEntity.builder()
                    .email(KW + id + "@example.com")
                    .passwordHash("hash")
                    .lastName("検索")
                    .firstName("対象")
                    .displayName(KW + "taro" + id)
                    .locale("ja")
                    .timezone("Asia/Tokyo")
                    .status(status)
                    .isSearchable(searchable)
                    .build());
            return user.getId();
        }

        private void joinTeam(Long userId, Long teamId) {
            membershipRepository.saveAndFlush(MembershipEntity.builder()
                    .userId(userId)
                    .scopeType(ScopeType.TEAM)
                    .scopeId(teamId)
                    .roleKind(RoleKind.MEMBER)
                    .joinedAt(LocalDateTime.now())
                    .build());
        }

        @Test
        @DisplayName("正常系: 所属を共有する利用者は検索に出る")
        void 同一スコープの利用者は出る() {
            Long id = saveUser(USER_TARGET, true, UserEntity.UserStatus.ACTIVE);
            joinTeam(id, TEAM_A);

            assertThat(searchIds(USER_INSIDER, "users")).contains(id);
        }

        @Test
        @DisplayName("[本丸] 所属を共有しない利用者は検索に出ない")
        void 非同一スコープの利用者は出ない() {
            Long id = saveUser(USER_TARGET, true, UserEntity.UserStatus.ACTIVE);
            joinTeam(id, TEAM_A);

            assertThat(searchIds(USER_OUTSIDER, "users")).doesNotContain(id);
        }

        @Test
        @DisplayName("[本丸] 検索許可フラグが false の利用者は同一スコープでも検索に出ない")
        void 検索拒否の利用者は出ない() {
            Long id = saveUser(USER_TARGET, false, UserEntity.UserStatus.ACTIVE);
            joinTeam(id, TEAM_A);

            assertThat(searchIds(USER_INSIDER, "users")).doesNotContain(id);
        }

        @Test
        @DisplayName("[本丸] メールアドレスは検索述語に含まれない（登録有無の照会窓口にしない）")
        void メールアドレスでは引けない() {
            Long id = saveUser(USER_TARGET, true, UserEntity.UserStatus.ACTIVE);
            joinTeam(id, TEAM_A);

            // 表示名には一致しないがメールアドレスには一致する語で検索する
            SearchResultResponse response =
                    globalSearchService.search(KW + USER_TARGET + "@example.com", USER_INSIDER);
            List<Object> ids = response.getResults().get("users").stream().map(r -> r.get("id")).toList();

            assertThat(ids).doesNotContain(id);
        }
    }
}
