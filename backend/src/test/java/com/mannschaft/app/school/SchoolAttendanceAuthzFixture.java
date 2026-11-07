package com.mannschaft.app.school;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.family.CareLinkInvitedBy;
import com.mannschaft.app.family.CareLinkStatus;
import com.mannschaft.app.family.entity.UserCareLinkEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.entity.AttendanceLocation;
import com.mannschaft.app.school.entity.AttendanceLocationChangeEntity;
import com.mannschaft.app.school.entity.AttendanceLocationChangeReason;
import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity;
import com.mannschaft.app.school.entity.AttendanceRequirementRuleEntity;
import com.mannschaft.app.school.entity.AttendanceTransitionAlertEntity;
import com.mannschaft.app.school.entity.ClassHomeroomEntity;
import com.mannschaft.app.school.entity.DailyAttendanceRecordEntity;
import com.mannschaft.app.school.entity.FamilyAttendanceNoticeEntity;
import com.mannschaft.app.school.entity.FamilyNoticeType;
import com.mannschaft.app.school.entity.PeriodAttendanceRecordEntity;
import com.mannschaft.app.school.entity.RequirementCategory;
import com.mannschaft.app.school.entity.StudentAttendanceSummaryEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 学校出欠の認可是正 第1段（CMP-261001-0630 閲覧 / CMP-260930-0230 登録）— 試練の共通フィクスチャ。
 *
 * <h2>試練が決めた判定（台帳「マスター御裁可」の機械可読な写し）</h2>
 * <ul>
 *   <li><b>V（閲覧）</b> = チーム ADMIN/DEPUTY_ADMIN、または class_homerooms の現役の担任・副担任、
 *       または VIEW_ATTENDANCE 権限の委任者。</li>
 *   <li><b>R（日次登録）</b> = 現役の担任・副担任、または ADMIN/DEPUTY_ADMIN。</li>
 *   <li><b>P（時限登録・修正）</b> = 第1段では R と同じ（教科担当の判定は第2段。権限新設・Flyway なし）。</li>
 *   <li>権限なしは 403（COMMON_002）。認可通過後の recordId 越境は 404。評価系 bare-id は 404。
 *       SYSTEM_ADMIN は見られない。統計・CSV は V のみ。</li>
 *   <li>保護者連絡の確認・反映、移動検知アラート解決（書込）は R と同じ規則（担任も可・一般 MEMBER は 403）。
 *       連絡一覧・アラート一覧（閲覧）は V と同じ規則。</li>
 * </ul>
 *
 * <h2>なぜ user_roles と memberships の両方に行を張るか</h2>
 * <p>{@code isAdminOrAbove} は user_roles、{@code isMember} は memberships と別系統を見る既知の地雷。
 * 手本は {@code SchoolAttendanceScopeContractIT}。教員役は全員 memberships にも行を張る
 * （現実の担任は担任名簿に載るだけで MEMBER であることが多く、
 * 「担任を RoleKind.MEMBER で作る」ことは欠陥の追認ではなく現実のフィクスチャである。
 * 追認だったのは「その MEMBER 全員に 200 を期待した」ほうである）。</p>
 *
 * <h2>日付の扱い</h2>
 * <p>担任の現役判定は「今日」に依存する。Clock を {@code @MockitoBean}/{@code @TestConfiguration} で固定すると
 * ApplicationContext が分裂して CI の OOM 要因になり（AbstractMySqlIntegrationTest の方針）、
 * かつ Policy がどの Clock Bean（utcClock/wallClock）を使うかは実装の裁量なので、
 * 本フィクスチャは学校の業務ゾーン（{@link UserZoneLocalDateTimeParser#SERVER_ZONE}）の今日を基準に
 * 相対日付（今日-1・今日・今日+1）を組み立てる。日付境界（JST 0 時）をまたぐ実行でのみ偽陽性の余地がある。</p>
 */
abstract class SchoolAttendanceAuthzFixture extends AbstractMySqlIntegrationTest {

    /** 学校の業務ゾーン（アプリ層の壁時計ゾーンの唯一の正）。 */
    protected static final ZoneId SCHOOL_ZONE = UserZoneLocalDateTimeParser.SERVER_ZONE;

    protected static final short ACADEMIC_YEAR = 2025;
    protected static final LocalDate STAT_FROM = LocalDate.of(2025, 4, 1);
    protected static final LocalDate STAT_TO = LocalDate.of(2025, 4, 30);

    /** ロール行列の行（AC 全体共通）。 */
    enum Actor {
        /** クラス A の現役の担任（class_homerooms。ADMIN ではない）。 */
        HOMEROOM,
        /** クラス A の現役の副担任（assistant_teacher_user_ids JSON に載る）。 */
        ASSISTANT,
        /** クラス A の ADMIN（user_roles）。 */
        ADMIN,
        /** クラス A の DEPUTY_ADMIN（user_roles）。 */
        DEPUTY_ADMIN,
        /** VIEW_ATTENDANCE を権限グループで委任された一般 MEMBER。 */
        DELEGATE,
        /** 教員でない同校の一般 MEMBER。 */
        PLAIN_MEMBER,
        /** 生徒本人（studentA）。 */
        STUDENT_SELF,
        /** 同級生徒。 */
        CLASSMATE,
        /** 過去の担任（effective_until が昨日）。 */
        FORMER_HOMEROOM,
        /** 予定担任（effective_from が明日）。 */
        FUTURE_HOMEROOM,
        /** 別クラス B の現役担任。 */
        OTHER_CLASS_HOMEROOM,
        /** 別テナントの ADMIN（別組織・別チームの ADMIN）。 */
        OTHER_TENANT_ADMIN,
        /** 保護者（ケアリンクのみ・クラス A に非所属）。 */
        GUARDIAN,
        /** 保護者かつクラス A の MEMBER。 */
        GUARDIAN_MEMBER,
        /** どこにも所属しない者。 */
        OUTSIDER,
        /** クラス A の SUPPORTER。 */
        SUPPORTER,
        /** クラス A の GUEST（user_roles）。 */
        GUEST,
        /** プラットフォームの SYSTEM_ADMIN（学校出欠は見られない）。 */
        SYSTEM_ADMIN
    }

    /** 閲覧 V を満たす者。 */
    protected static final Set<Actor> VIEW =
            EnumSet.of(Actor.HOMEROOM, Actor.ASSISTANT, Actor.ADMIN, Actor.DEPUTY_ADMIN, Actor.DELEGATE);

    /** 登録 R（＝第1段の P）を満たす者。 */
    protected static final Set<Actor> REGISTER =
            EnumSet.of(Actor.HOMEROOM, Actor.ASSISTANT, Actor.ADMIN, Actor.DEPUTY_ADMIN);

    /** 生徒単位の閲覧: V ＋ 本人 ＋ ケアリンクのある保護者。 */
    protected static final Set<Actor> STUDENT_VIEW = EnumSet.of(
            Actor.HOMEROOM, Actor.ASSISTANT, Actor.ADMIN, Actor.DEPUTY_ADMIN, Actor.DELEGATE,
            Actor.STUDENT_SELF, Actor.GUARDIAN, Actor.GUARDIAN_MEMBER);

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @PersistenceContext
    protected EntityManager em;

    // ---- 世界（seedWorld が埋める） ----
    protected LocalDate date;
    protected Long orgAId;
    protected Long orgBId;
    protected Long teamAId;
    protected Long teamBId;
    protected Long teamCId;
    protected final Map<Actor, Long> actors = new EnumMap<>(Actor.class);

    protected Long studentAId;
    protected Long classmateId;
    protected Long dualStudentId;
    protected Long strayUserId;

    protected Long dailyAId;
    protected Long dailyBId;
    protected Long periodAId;
    protected Long periodBId;
    protected Long alertAId;
    protected Long alertBId;
    protected Long noticeAId;
    protected Long noticeBId;
    protected Long ruleAId;
    protected Long ruleBId;
    protected Long evalAId;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdTeamIds = new ArrayList<>();
    private final List<Long> createdOrgIds = new ArrayList<>();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 世界の構築
    // ═════════════════════════════════════════════════════════════════════

    /**
     * ロール行列の全役とデータ一式を作る。{@code @Transactional} のテストでは {@code @BeforeEach} から、
     * 非トランザクションのテストでは TransactionTemplate の内側から呼ぶこと。
     */
    protected void seedWorld() {
        date = LocalDate.now(SCHOOL_ZONE);

        orgAId = insertOrganization("SAZ組織A");
        orgBId = insertOrganization("SAZ組織B");
        teamAId = insertTeam("SAZクラスA");
        teamBId = insertTeam("SAZクラスB");
        teamCId = insertTeam("SAZ別テナントC");

        for (Actor actor : Actor.values()) {
            actors.put(actor, insertUser(actor.name().toLowerCase().replace('_', '-')));
        }
        studentAId = actors.get(Actor.STUDENT_SELF);
        classmateId = actors.get(Actor.CLASSMATE);
        dualStudentId = insertUser("dual-student");
        strayUserId = insertUser("stray");

        // ---- 所属（memberships） ----
        for (Actor a : EnumSet.of(Actor.HOMEROOM, Actor.ASSISTANT, Actor.ADMIN, Actor.DEPUTY_ADMIN, Actor.DELEGATE,
                Actor.PLAIN_MEMBER, Actor.STUDENT_SELF, Actor.CLASSMATE, Actor.FORMER_HOMEROOM,
                Actor.FUTURE_HOMEROOM, Actor.GUARDIAN_MEMBER)) {
            addMember(actors.get(a), teamAId);
        }
        addMember(dualStudentId, teamAId);
        addMember(dualStudentId, teamBId);
        addMember(actors.get(Actor.OTHER_CLASS_HOMEROOM), teamBId);
        addMember(actors.get(Actor.OTHER_TENANT_ADMIN), teamCId);
        MembershipTestHelper.insertMembership(em, actors.get(Actor.OTHER_TENANT_ADMIN),
                ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, actors.get(Actor.SUPPORTER), ScopeType.TEAM, teamAId,
                RoleKind.SUPPORTER);

        // ---- 権限ロール（user_roles。memberships とは別系統） ----
        MembershipTestHelper.insertUserRole(em, actors.get(Actor.ADMIN), "ADMIN", teamAId, null);
        MembershipTestHelper.insertUserRole(em, actors.get(Actor.DEPUTY_ADMIN), "DEPUTY_ADMIN", teamAId, null);
        MembershipTestHelper.insertUserRole(em, actors.get(Actor.GUEST), "GUEST", teamAId, null);
        MembershipTestHelper.insertUserRole(em, actors.get(Actor.OTHER_TENANT_ADMIN), "ADMIN", teamCId, null);
        MembershipTestHelper.insertUserRole(em, actors.get(Actor.OTHER_TENANT_ADMIN), "ADMIN", null, orgBId);
        MembershipTestHelper.insertUserRole(em, actors.get(Actor.SYSTEM_ADMIN), "SYSTEM_ADMIN", null, null);

        // ---- VIEW_ATTENDANCE の委任（権限グループ。一般 MEMBER 向け） ----
        grantViewAttendance(actors.get(Actor.DELEGATE), teamAId);

        // ---- 担任名簿（class_homerooms） ----
        addHomeroom(teamAId, actors.get(Actor.HOMEROOM),
                "[" + actors.get(Actor.ASSISTANT) + "]", date.minusDays(30), null);
        addHomeroom(teamAId, actors.get(Actor.FORMER_HOMEROOM), null, date.minusDays(100), date.minusDays(1));
        addHomeroom(teamAId, actors.get(Actor.FUTURE_HOMEROOM), null, date.plusDays(1), null);
        addHomeroom(teamBId, actors.get(Actor.OTHER_CLASS_HOMEROOM), null, date.minusDays(30), null);

        // ---- 保護者（ケアリンク） ----
        addCareLink(studentAId, actors.get(Actor.GUARDIAN));
        addCareLink(studentAId, actors.get(Actor.GUARDIAN_MEMBER));

        // ---- 出欠データ ----
        Long homeroom = actors.get(Actor.HOMEROOM);
        Long homeroomB = actors.get(Actor.OTHER_CLASS_HOMEROOM);
        dailyAId = insertDaily(teamAId, studentAId, date, AttendanceStatus.ATTENDING, homeroom);
        insertDaily(teamAId, classmateId, date, AttendanceStatus.ABSENT, homeroom);
        dailyBId = insertDaily(teamBId, dualStudentId, date, AttendanceStatus.ATTENDING, homeroomB);
        periodAId = insertPeriod(teamAId, studentAId, date, 1, AttendanceStatus.ATTENDING, homeroom);
        periodBId = insertPeriod(teamBId, dualStudentId, date, 1, AttendanceStatus.ATTENDING, homeroomB);
        alertAId = insertAlert(teamAId, studentAId, date);
        alertBId = insertAlert(teamBId, dualStudentId, date);
        noticeAId = insertNotice(teamAId, studentAId, date);
        noticeBId = insertNotice(teamBId, dualStudentId, date);

        // ---- 集計・規程・評価（チームA） ----
        Long summaryAId = insertSummary(teamAId, studentAId);
        ruleAId = insertRule(teamAId, null);
        ruleBId = insertRule(teamBId, null);
        evalAId = insertEvaluation(ruleAId, studentAId, summaryAId);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 認証・リクエスト補助
    // ═════════════════════════════════════════════════════════════════════

    protected void auth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    protected void auth(Actor actor) {
        auth(actors.get(actor));
    }

    protected String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 日次点呼の 1 エントリ。 */
    protected Map<String, Object> entry(Long studentUserId, String status) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("studentUserId", studentUserId);
        e.put("status", status);
        return e;
    }

    /** 日次点呼・時限登録の共通ボディ（attendanceDate ＋ entries）。 */
    @SafeVarargs
    protected final Map<String, Object> entriesBody(LocalDate day, Map<String, Object>... entries) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attendanceDate", day.toString());
        body.put("entries", List.of(entries));
        return body;
    }

    // ═════════════════════════════════════════════════════════════════════
    // 行の投入（EntityManager.persist。IDENTITY 採番で即 INSERT される）
    // ═════════════════════════════════════════════════════════════════════

    protected void addMember(Long userId, Long teamId) {
        MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
    }

    protected Long addHomeroom(Long teamId, Long teacherUserId, String assistantJson,
                               LocalDate from, LocalDate until) {
        ClassHomeroomEntity e = ClassHomeroomEntity.builder()
                .teamId(teamId)
                .homeroomTeacherUserId(teacherUserId)
                .assistantTeacherUserIds(assistantJson)
                .academicYear(date.getYear())
                .effectiveFrom(from)
                .effectiveUntil(until)
                .createdBy(teacherUserId)
                .build();
        em.persist(e);
        em.flush();
        return e.getId();
    }

    protected void addCareLink(Long careRecipientUserId, Long watcherUserId) {
        em.persist(UserCareLinkEntity.builder()
                .careRecipientUserId(careRecipientUserId)
                .watcherUserId(watcherUserId)
                .careCategory(CareCategory.MINOR)
                .status(CareLinkStatus.ACTIVE)
                .invitedBy(CareLinkInvitedBy.WATCHER)
                .notifyOnRsvp(true)
                .notifyOnCheckin(true)
                .createdBy(watcherUserId)
                .build());
        em.flush();
    }

    /** 一般 MEMBER 向けの権限グループ（targetRole=MEMBER）に VIEW_ATTENDANCE を載せて委任する。 */
    protected void grantViewAttendance(Long userId, Long teamId) {
        Long permissionId = permissionId("VIEW_ATTENDANCE");
        PermissionGroupEntity group = PermissionGroupEntity.builder()
                .teamId(teamId)
                .targetRole(PermissionGroupEntity.TargetRole.MEMBER)
                .name("SAZ-view-attendance-" + SEQ.incrementAndGet())
                .build();
        em.persist(group);
        em.flush();
        em.persist(PermissionGroupPermissionEntity.builder()
                .groupId(group.getId()).permissionId(permissionId).build());
        em.persist(UserPermissionGroupEntity.builder()
                .userId(userId).groupId(group.getId()).build());
        em.flush();
    }

    private Long permissionId(String name) {
        List<?> ids = em.createNativeQuery("SELECT id FROM permissions WHERE name = :name")
                .setParameter("name", name).getResultList();
        if (!ids.isEmpty()) {
            return ((Number) ids.get(0)).longValue();
        }
        PermissionEntity entity = PermissionEntity.builder()
                .name(name).displayName(name).scope(PermissionEntity.Scope.TEAM).build();
        em.persist(entity);
        em.flush();
        return entity.getId();
    }

    protected Long insertDaily(Long teamId, Long studentId, LocalDate day, AttendanceStatus status, Long by) {
        DailyAttendanceRecordEntity e = DailyAttendanceRecordEntity.builder()
                .teamId(teamId).studentUserId(studentId).attendanceDate(day)
                .status(status).recordedBy(by).build();
        em.persist(e);
        em.flush();
        return e.getId();
    }

    protected Long insertPeriod(Long teamId, Long studentId, LocalDate day, int period,
                                AttendanceStatus status, Long teacher) {
        PeriodAttendanceRecordEntity e = PeriodAttendanceRecordEntity.builder()
                .teamId(teamId).studentUserId(studentId).attendanceDate(day).periodNumber(period)
                .subjectName("SAZ科目").teacherUserId(teacher)
                .status(status).recordedBy(teacher).build();
        em.persist(e);
        em.flush();
        return e.getId();
    }

    protected Long insertAlert(Long teamId, Long studentId, LocalDate day) {
        AttendanceTransitionAlertEntity e = AttendanceTransitionAlertEntity.builder()
                .teamId(teamId).studentUserId(studentId).attendanceDate(day)
                .previousPeriodNumber(1).currentPeriodNumber(2)
                .previousPeriodStatus(AttendanceStatus.ATTENDING)
                .currentPeriodStatus(AttendanceStatus.ABSENT)
                .notifiedUsers("[]")
                .build();
        em.persist(e);
        em.flush();
        return e.getId();
    }

    protected Long insertNotice(Long teamId, Long studentId, LocalDate day) {
        FamilyAttendanceNoticeEntity e = FamilyAttendanceNoticeEntity.builder()
                .teamId(teamId).studentUserId(studentId)
                .submitterUserId(actors.get(Actor.GUARDIAN))
                .attendanceDate(day).noticeType(FamilyNoticeType.ABSENCE)
                .build();
        em.persist(e);
        em.flush();
        return e.getId();
    }

    protected Long insertLocationChange(Long teamId, Long studentId, LocalDate day, Long by) {
        AttendanceLocationChangeEntity e = AttendanceLocationChangeEntity.builder()
                .teamId(teamId).studentUserId(studentId).attendanceDate(day)
                .fromLocation(AttendanceLocation.CLASSROOM).toLocation(AttendanceLocation.SICK_BAY)
                .reason(AttendanceLocationChangeReason.FELT_SICK).recordedBy(by).build();
        em.persist(e);
        em.flush();
        return e.getId();
    }

    protected Long insertSummary(Long teamId, Long studentId) {
        StudentAttendanceSummaryEntity e = StudentAttendanceSummaryEntity.builder()
                .teamId(teamId).studentUserId(studentId).termId(null)
                .academicYear(ACADEMIC_YEAR).periodFrom(STAT_FROM).periodTo(STAT_TO)
                .totalSchoolDays((short) 100).presentDays((short) 90).absentDays((short) 10)
                .attendanceRate(new BigDecimal("90.00"))
                .build();
        em.persist(e);
        em.flush();
        return e.getId();
    }

    /** チームスコープ（organizationId=null）または組織スコープ（teamId=null）の規程を作る。 */
    protected Long insertRule(Long teamId, Long organizationId) {
        AttendanceRequirementRuleEntity e = AttendanceRequirementRuleEntity.builder()
                .teamId(teamId).organizationId(organizationId).academicYear(ACADEMIC_YEAR)
                .category(RequirementCategory.GRADE_PROMOTION).name("SAZ規程" + SEQ.incrementAndGet())
                .minAttendanceRate(new BigDecimal("80.00"))
                .effectiveFrom(date.minusDays(1)).build();
        em.persist(e);
        em.flush();
        return e.getId();
    }

    protected Long insertEvaluation(Long ruleId, Long studentId, Long summaryId) {
        AttendanceRequirementEvaluationEntity e = AttendanceRequirementEvaluationEntity.builder()
                .requirementRuleId(ruleId).studentUserId(studentId).summaryId(summaryId)
                .status(AttendanceRequirementEvaluationEntity.EvaluationStatus.VIOLATION)
                .currentAttendanceRate(new BigDecimal("75.00"))
                .remainingAllowedAbsences(0)
                .evaluatedAt(LocalDateTime.now().minusDays(1))
                .build();
        em.persist(e);
        em.flush();
        return e.getId();
    }

    // ═════════════════════════════════════════════════════════════════════
    // users / teams / organizations（既存 IT と同じ native INSERT）
    // ═════════════════════════════════════════════════════════════════════

    protected Long insertUser(String label) {
        String email = "saz-" + label + "-" + SEQ.incrementAndGet() + "-" + System.nanoTime() + "@example.com";
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'SAZ', 'テスト', :display, 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("display", "SAZ" + label)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
        createdUserIds.add(id);
        return id;
    }

    protected Long insertTeam(String label) {
        String name = label + "-" + SEQ.incrementAndGet() + "-" + System.nanoTime();
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
        createdTeamIds.add(id);
        return id;
    }

    protected Long insertOrganization(String label) {
        String name = label + "-" + SEQ.incrementAndGet() + "-" + System.nanoTime();
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
        createdOrgIds.add(id);
        return id;
    }

    /** 追加で必要になった一般ユーザー（テスト固有の役）を作り、purge 対象に登録する。 */
    protected Long newUser(String label) {
        return insertUser(label);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 非トランザクションのテスト用: 投入した行の掃除
    // ═════════════════════════════════════════════════════════════════════

    /** TransactionTemplate の内側から呼ぶこと。seedWorld と newUser/insertTeam 等で作った行を全て消す。 */
    protected void purgeWorld() {
        String users = csv(createdUserIds);
        String teams = csv(createdTeamIds);
        String orgs = csv(createdOrgIds);
        if (!createdTeamIds.isEmpty()) {
            for (String table : List.of("daily_attendance_records", "period_attendance_records",
                    "attendance_transition_alerts", "family_attendance_notices", "attendance_location_changes",
                    "student_attendance_summaries", "class_homerooms")) {
                exec("DELETE FROM " + table + " WHERE team_id IN (" + teams + ")");
            }
            exec("DELETE FROM permission_group_permissions WHERE group_id IN "
                    + "(SELECT id FROM permission_groups WHERE team_id IN (" + teams + "))");
            exec("DELETE FROM permission_groups WHERE team_id IN (" + teams + ")");
        }
        if (!createdUserIds.isEmpty()) {
            exec("DELETE FROM attendance_requirement_evaluations WHERE student_user_id IN (" + users + ")");
            exec("DELETE FROM user_permission_groups WHERE user_id IN (" + users + ")");
            exec("DELETE FROM user_care_links WHERE care_recipient_user_id IN (" + users
                    + ") OR watcher_user_id IN (" + users + ")");
            exec("DELETE FROM user_roles WHERE user_id IN (" + users + ")");
            exec("DELETE FROM memberships WHERE user_id IN (" + users + ")");
        }
        if (!createdTeamIds.isEmpty()) {
            exec("DELETE FROM attendance_requirement_rules WHERE team_id IN (" + teams + ")");
        }
        if (!createdOrgIds.isEmpty()) {
            exec("DELETE FROM attendance_requirement_rules WHERE organization_id IN (" + orgs + ")");
        }
        if (!createdUserIds.isEmpty()) {
            exec("DELETE FROM users WHERE id IN (" + users + ")");
        }
        if (!createdTeamIds.isEmpty()) {
            exec("DELETE FROM teams WHERE id IN (" + teams + ")");
        }
        if (!createdOrgIds.isEmpty()) {
            exec("DELETE FROM organizations WHERE id IN (" + orgs + ")");
        }
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }

    private static String csv(List<Long> ids) {
        return ids.isEmpty() ? "NULL" : ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    // ═════════════════════════════════════════════════════════════════════
    // 読み出し補助（native。assert 前に flush/clear する）
    // ═════════════════════════════════════════════════════════════════════

    protected long count(String sql, Object... params) {
        em.flush();
        em.clear();
        var q = em.createNativeQuery(sql);
        for (int i = 0; i < params.length; i++) {
            q.setParameter(i + 1, params[i]);
        }
        return ((Number) q.getSingleResult()).longValue();
    }

    protected String text(String sql, Object... params) {
        em.flush();
        em.clear();
        var q = em.createNativeQuery(sql);
        for (int i = 0; i < params.length; i++) {
            q.setParameter(i + 1, params[i]);
        }
        Object r = q.getSingleResult();
        return r == null ? null : r.toString();
    }
}
