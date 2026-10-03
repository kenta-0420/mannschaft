package com.mannschaft.app.school;

import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.school.event.DailyRollCallRecordedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 学校出欠の認可是正 第1段 — 登録系の完全性（副作用ゼロ・所属検証・入力境界・性能）（試練・red）。
 *
 * <p>対応 AC: AC-8（拒否時に行・通知イベントが作られない）/ AC-9（recordId 越境は 404 で無変化）/
 * AC-10（時限登録の拒否で移動検知アラートが作られない・upsert による既存レコード書換の拒否）/
 * AC-12（entries の生徒がクラスの在籍メンバーでない・familyNoticeId が不整合）/
 * AC-17（periodNumber は 1〜15）/ AC-24（認可・所属判定のクエリ数が entries 件数に依存しない）。</p>
 *
 * <p>本クラスは {@code @Transactional}（テスト終了で巻き戻す）。<b>テスト内トランザクションは業務の
 * ロールバックを覆い隠す</b>ため、「途中で不正 ID が混ざったら全件巻き戻る（部分登録なし）」の実測は
 * 非トランザクションの {@code SchoolAttendanceRegistrationAtomicityIT} が受け持つ。ここでの AC-12 は
 * 「4xx で拒否され、拒否された要求の行が見えない」までを固定する。</p>
 *
 * <h2>試練が決めた契約</h2>
 * <ul>
 *   <li>entries の生徒が当該クラスの現役在籍メンバー（memberships・left_at IS NULL）でない場合は 4xx
 *       （他クラス専属・非在籍・退会済み・存在しない ID のいずれも）。</li>
 *   <li>familyNoticeId は teamId・studentUserId・attendanceDate が全て一致する連絡のみ受理。
 *       不在・別クラス・別生徒・別日は 4xx。null は従来どおり受理。</li>
 *   <li>periodNumber は仕様どおり 1〜15。0・負数・16 以上は 400、1・15 は通る。</li>
 *   <li>認可・所属判定のクエリ数: entries 件数を増やしても増えない（所属は集合取得。生徒ループ内で
 *       判定を繰り返さない）。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@RecordApplicationEvents
@Isolated
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("学校出欠 登録系の完全性（第1段）")
class SchoolAttendanceRegistrationIntegrityIT extends SchoolAttendanceAuthzFixture {

    @Autowired
    private ApplicationEvents events;

    @BeforeEach
    void setUp() {
        seedWorld();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-8 日次登録の拒否は副作用ゼロ
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "AC-8 roll-call 拒否の副作用ゼロ × {0}")
    @EnumSource(value = Actor.class, names = {"DELEGATE", "PLAIN_MEMBER", "STUDENT_SELF", "CLASSMATE",
            "FORMER_HOMEROOM", "FUTURE_HOMEROOM", "OTHER_CLASS_HOMEROOM", "OTHER_TENANT_ADMIN", "GUARDIAN",
            "GUARDIAN_MEMBER", "OUTSIDER", "SUPPORTER", "GUEST", "SYSTEM_ADMIN"})
    @DisplayName("AC-8: R でない者の点呼は 403・DB に 1 行も作られず・保護者通知イベントも発行されない")
    void 点呼の拒否は行もイベントも作らない(Actor actor) throws Exception {
        var day = date.plusDays(3);
        long before = count("SELECT COUNT(*) FROM daily_attendance_records WHERE team_id = ?1", teamAId);
        long eventsBefore = rollCallEvents();

        auth(actor);
        MvcResult result = mockMvc.perform(post("/api/v1/teams/{t}/attendance/daily/roll-call", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(entriesBody(day, entry(studentAId, "ABSENT")))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(count("SELECT COUNT(*) FROM daily_attendance_records WHERE team_id = ?1", teamAId))
                .as("拒否された点呼は行を作らない").isEqualTo(before);
        assertThat(rollCallEvents()).as("拒否された点呼は保護者通知イベントを発行しない").isEqualTo(eventsBefore);
    }

    @Test
    @DisplayName("AC-8 陽性対照: 担任の点呼は 201・行が作られ・通知イベントが 1 件発行される")
    void 担任の点呼は行とイベントを作る() throws Exception {
        var day = date.plusDays(3);
        long before = count("SELECT COUNT(*) FROM daily_attendance_records WHERE team_id = ?1", teamAId);
        long eventsBefore = rollCallEvents();

        auth(Actor.HOMEROOM);
        MvcResult result = mockMvc.perform(post("/api/v1/teams/{t}/attendance/daily/roll-call", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(entriesBody(day, entry(studentAId, "ABSENT")))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(count("SELECT COUNT(*) FROM daily_attendance_records WHERE team_id = ?1", teamAId))
                .isEqualTo(before + 1);
        assertThat(rollCallEvents()).isEqualTo(eventsBefore + 1);
    }

    private long rollCallEvents() {
        return events.stream(DailyRollCallRecordedEvent.class).count();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-9 / AC-11 recordId 越境は 404 で無変化
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-9: 自クラスのパスで他クラスの日次 recordId を指定すると 404・他クラスの記録は無変化")
    void 日次の越境修正は404で無変化() throws Exception {
        auth(Actor.HOMEROOM);

        MvcResult result = mockMvc.perform(patch("/api/v1/teams/{t}/attendance/daily/{r}", teamAId, dailyBId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "ABSENT", "comment", "SAZ越境改ざん"))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(text("SELECT status FROM daily_attendance_records WHERE id = ?1", dailyBId))
                .isEqualTo("ATTENDING");
        assertThat(text("SELECT comment FROM daily_attendance_records WHERE id = ?1", dailyBId)).isNull();
    }

    @Test
    @DisplayName("AC-6/AC-11: 自クラスのパスで他クラスの時限 recordId を指定すると 404・他クラスの記録は無変化")
    void 時限の越境修正は404で無変化() throws Exception {
        auth(Actor.HOMEROOM);

        MvcResult result = mockMvc.perform(patch("/api/v1/teams/{t}/attendance/periods/{r}", teamAId, periodBId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "ABSENT", "comment", "SAZ越境改ざん"))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(text("SELECT status FROM period_attendance_records WHERE id = ?1", periodBId))
                .isEqualTo("ATTENDING");
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-10 時限登録: 拒否の副作用ゼロ・upsert による書換の拒否
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "AC-10 時限登録の拒否は移動検知アラートを作らない × {0}")
    @EnumSource(value = Actor.class, names = {"DELEGATE", "PLAIN_MEMBER", "STUDENT_SELF", "CLASSMATE",
            "FORMER_HOMEROOM", "OTHER_CLASS_HOMEROOM", "OTHER_TENANT_ADMIN", "GUARDIAN_MEMBER", "OUTSIDER",
            "SYSTEM_ADMIN"})
    @DisplayName("AC-10: P でない者の時限登録は 403・時限行も移動検知アラート行も作られない")
    void 時限登録の拒否は移動検知を走らせない(Actor actor) throws Exception {
        // 1 時限目に出席(periodA)・2 時限目に欠席を登録しようとする＝移動検知の発火条件。
        long alertsBefore = count("SELECT COUNT(*) FROM attendance_transition_alerts WHERE team_id = ?1", teamAId);

        auth(actor);
        MvcResult result = mockMvc.perform(post("/api/v1/teams/{t}/attendance/periods/2", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(entriesBody(date, entry(studentAId, "ABSENT")))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(count("SELECT COUNT(*) FROM period_attendance_records WHERE team_id = ?1 AND period_number = 2",
                teamAId)).as("拒否された時限登録は行を作らない").isZero();
        assertThat(count("SELECT COUNT(*) FROM attendance_transition_alerts WHERE team_id = ?1", teamAId))
                .as("拒否された時限登録は移動検知を走らせない").isEqualTo(alertsBefore);
    }

    @ParameterizedTest(name = "AC-10 POST(upsert) による既存時限レコードの書換拒否 × {0}")
    @EnumSource(value = Actor.class, names = {"DELEGATE", "PLAIN_MEMBER", "STUDENT_SELF", "FORMER_HOMEROOM",
            "OTHER_CLASS_HOMEROOM", "GUARDIAN_MEMBER", "OUTSIDER"})
    @DisplayName("AC-10: 時限 POST は upsert。PATCH と同じ条件で、権限のない者による既存レコードの書換を拒否する")
    void 時限POSTによる既存レコードの書換は拒否される(Actor actor) throws Exception {
        auth(actor);

        MvcResult result = mockMvc.perform(post("/api/v1/teams/{t}/attendance/periods/1", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(entriesBody(date, entry(studentAId, "ABSENT")))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(text("SELECT status FROM period_attendance_records WHERE id = ?1", periodAId))
                .as("PATCH が拒否される者は POST でも既存レコードを書き換えられない").isEqualTo("ATTENDING");
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-12 entries の所属検証・familyNoticeId の整合
    // ═════════════════════════════════════════════════════════════════════

    /** クラス A の在籍メンバーでない生徒 ID の種類。 */
    enum Stranger { OTHER_CLASS_ONLY, NO_MEMBERSHIP, NONEXISTENT_ID, WITHDRAWN }

    private Long strangerId(Stranger kind) {
        return switch (kind) {
            case OTHER_CLASS_ONLY -> {
                Long u = newUser("b-only");
                addMember(u, teamBId);
                yield u;
            }
            case NO_MEMBERSHIP -> strayUserId;
            case NONEXISTENT_ID -> 999_999_999L;
            case WITHDRAWN -> {
                Long u = newUser("withdrawn");
                addMember(u, teamAId);
                em.createNativeQuery("UPDATE memberships SET left_at = NOW() WHERE user_id = ?1 AND scope_id = ?2")
                        .setParameter(1, u).setParameter(2, teamAId).executeUpdate();
                yield u;
            }
        };
    }

    @ParameterizedTest(name = "AC-12 点呼 entries に在籍メンバーでない生徒 × {0}")
    @EnumSource(Stranger.class)
    @DisplayName("AC-12: 点呼 entries にクラスの在籍メンバーでない生徒が含まれると 4xx・要求の行は残らない")
    void 点呼に非在籍生徒が混ざると拒否される(Stranger kind) throws Exception {
        var day = date.plusDays(5);
        Long stranger = strangerId(kind);
        auth(Actor.HOMEROOM);

        MvcResult result = mockMvc.perform(post("/api/v1/teams/{t}/attendance/daily/roll-call", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(entriesBody(day, entry(studentAId, "ATTENDING"), entry(stranger, "ABSENT")))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as("4xx で拒否").isBetween(400, 499);
        assertThat(count("SELECT COUNT(*) FROM daily_attendance_records WHERE team_id = ?1 AND attendance_date = ?2",
                teamAId, day)).as("部分登録なし").isZero();
    }

    @ParameterizedTest(name = "AC-12 時限 entries に在籍メンバーでない生徒 × {0}")
    @EnumSource(Stranger.class)
    @DisplayName("AC-12: 時限 entries にクラスの在籍メンバーでない生徒が含まれると 4xx・要求の行は残らない")
    void 時限に非在籍生徒が混ざると拒否される(Stranger kind) throws Exception {
        Long stranger = strangerId(kind);
        auth(Actor.HOMEROOM);

        MvcResult result = mockMvc.perform(post("/api/v1/teams/{t}/attendance/periods/3", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(entriesBody(date, entry(studentAId, "ATTENDING"), entry(stranger, "ABSENT")))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as("4xx で拒否").isBetween(400, 499);
        assertThat(count("SELECT COUNT(*) FROM period_attendance_records WHERE team_id = ?1 AND period_number = 3",
                teamAId)).as("部分登録なし").isZero();
    }

    /** familyNoticeId の不整合の種類。 */
    enum NoticeCase { OTHER_CLASS, OTHER_STUDENT, OTHER_DATE, NONEXISTENT, MATCHING, NONE }

    @ParameterizedTest(name = "AC-12 familyNoticeId の整合 × {0}")
    @EnumSource(NoticeCase.class)
    @DisplayName("AC-12: familyNoticeId は teamId・生徒・対象日が全て一致する場合のみ受理（null は従来どおり受理）")
    void familyNoticeIdは生徒と対象日の一致を要求する(NoticeCase kind) throws Exception {
        Long noticeId = switch (kind) {
            case OTHER_CLASS -> noticeBId;
            case OTHER_STUDENT -> insertNotice(teamAId, classmateId, date);
            case OTHER_DATE -> insertNotice(teamAId, studentAId, date.minusDays(1));
            case NONEXISTENT -> 999_999_999L;
            case MATCHING -> noticeAId;
            case NONE -> null;
        };
        Map<String, Object> e = entry(studentAId, "ABSENT");
        if (noticeId != null) {
            e.put("familyNoticeId", noticeId);
        }
        em.flush();
        em.clear();

        auth(Actor.HOMEROOM);
        MvcResult result = mockMvc.perform(post("/api/v1/teams/{t}/attendance/daily/roll-call", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(entriesBody(date, e))))
                .andReturn();

        boolean accepted = kind == NoticeCase.MATCHING || kind == NoticeCase.NONE;
        if (accepted) {
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
        } else {
            assertThat(result.getResponse().getStatus()).as("不整合な familyNoticeId は 4xx").isBetween(400, 499);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-17 入力境界（periodNumber は 1〜15）
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "AC-17 periodNumber={0} は 400")
    @ValueSource(ints = {0, -1, 16, 100})
    @DisplayName("AC-17: 時限登録の periodNumber が 1〜15 の外なら 400")
    void 時限番号の範囲外は400(int period) throws Exception {
        auth(Actor.HOMEROOM);
        MvcResult result = mockMvc.perform(post("/api/v1/teams/{t}/attendance/periods/{n}", teamAId, period)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(entriesBody(date, entry(studentAId, "ATTENDING")))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @ParameterizedTest(name = "AC-17 periodNumber={0} は受理")
    @ValueSource(ints = {1, 15})
    @DisplayName("AC-17: 時限登録の periodNumber の下限 1・上限 15 は通る")
    void 時限番号の境界値は受理される(int period) throws Exception {
        auth(Actor.HOMEROOM);
        MvcResult result = mockMvc.perform(post("/api/v1/teams/{t}/attendance/periods/{n}", teamAId, period)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(entriesBody(date, entry(studentAId, "ATTENDING")))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    @ParameterizedTest(name = "AC-17 閲覧系の periodNumber={0} は 400")
    @ValueSource(ints = {0, 16})
    @DisplayName("AC-17: 時限の一覧・候補取得も periodNumber が 1〜15 の外なら 400")
    void 閲覧系の時限番号も範囲外は400(int period) throws Exception {
        auth(Actor.HOMEROOM);
        MvcResult list = mockMvc.perform(get("/api/v1/teams/{t}/attendance/periods", teamAId)
                .param("date", date.toString()).param("periodNumber", String.valueOf(period))).andReturn();
        MvcResult candidates = mockMvc.perform(get("/api/v1/teams/{t}/attendance/periods/{n}/candidates",
                teamAId, period).param("date", date.toString())).andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(400);
        assertThat(candidates.getResponse().getStatus()).isEqualTo(400);
    }

    // AC-17 entries の件数上限（200）と同一生徒の重複拒否。
    // 件数上限は互いに異なる ID で検証し、重複拒否と独立に @Size(max = 200) の有無を判定する。
    // 200 件ちょうどは「entries のフィールドエラーが無い」ことで DTO 検証通過を判定する（在籍は不要）。

    private static final String[] ROUTES = {
            "/api/v1/teams/{t}/attendance/daily/roll-call", "/api/v1/teams/{t}/attendance/periods/3"};

    private long registrationRows() {
        return count("SELECT COUNT(*) FROM daily_attendance_records", new Object[0])
                + count("SELECT COUNT(*) FROM period_attendance_records", new Object[0]);
    }

    /** 互いに異なる studentUserId を n 個並べた entries 本文（在籍していなくてよい。Bean Validation は認可・在籍確認より前）。 */
    private Map<String, Object> distinctEntriesBody(int n) {
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            many.add(entry(900_000L + i, "ATTENDING"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attendanceDate", date.toString());
        body.put("entries", many);
        return body;
    }

    private com.fasterxml.jackson.databind.JsonNode errorOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("error");
    }

    private List<String> fieldErrorNames(com.fasterxml.jackson.databind.JsonNode error) {
        List<String> names = new ArrayList<>();
        error.path("fieldErrors").forEach(fe -> names.add(fe.path("field").asText()));
        return names;
    }

    @ParameterizedTest(name = "AC-17 entries 201 件は 400 route={0}")
    @ValueSource(ints = {0, 1})
    @DisplayName("AC-17: 互いに異なる 201 件は Bean Validation（COMMON_001・entries のフィールドエラー）で 400・DB に 1 行も作られない")
    void entriesが上限超過なら400(int route) throws Exception {
        long rowsBefore = registrationRows();
        long eventsBefore = rollCallEvents();

        auth(Actor.HOMEROOM);
        MvcResult result = mockMvc.perform(post(ROUTES[route], teamAId)
                        .contentType(MediaType.APPLICATION_JSON).content(json(distinctEntriesBody(201)))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        com.fasterxml.jackson.databind.JsonNode error = errorOf(result);
        assertThat(error.path("code").asText()).as("重複由来ではなく DTO 検証由来").isEqualTo("COMMON_001")
                .isNotEqualTo("SCHOOL_DUPLICATE_STUDENT_ENTRY");
        assertThat(fieldErrorNames(error)).as("@Size(max=200) の違反は entries に付く").contains("entries");
        assertThat(registrationRows()).as("上限超過は 1 行も作らない").isEqualTo(rowsBefore);
        assertThat(rollCallEvents()).isEqualTo(eventsBefore);
    }

    @ParameterizedTest(name = "AC-17 entries 200 件は DTO 検証と認可を通り在籍確認で落ちる route={0}")
    @ValueSource(ints = {0, 1})
    @DisplayName("AC-17: 互いに異なる 200 件ちょうどは DTO 検証・認可を通り、在籍確認（SCHOOL_STUDENT_NOT_ENROLLED・400）で落ちる")
    void entriesが200件ちょうどはDTO検証を通る(int route) throws Exception {
        long rowsBefore = registrationRows();
        long eventsBefore = rollCallEvents();

        auth(Actor.HOMEROOM);
        MvcResult result = mockMvc.perform(post(ROUTES[route], teamAId)
                        .contentType(MediaType.APPLICATION_JSON).content(json(distinctEntriesBody(200)))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        com.fasterxml.jackson.databind.JsonNode error = errorOf(result);
        assertThat(error.path("code").asText()).as("DTO 検証・認可は通過し、在籍確認で落ちた")
                .isEqualTo("SCHOOL_STUDENT_NOT_ENROLLED");
        assertThat(fieldErrorNames(error)).as("200 件は @Size(max=200) に違反しない")
                .noneMatch(f -> f.startsWith("entries"));
        assertThat(registrationRows()).as("在籍エラーでは 1 行も作らない").isEqualTo(rowsBefore);
        assertThat(rollCallEvents()).isEqualTo(eventsBefore);
    }

    @ParameterizedTest(name = "AC-17 同一生徒の重複は 400 route={0}")
    @ValueSource(ints = {0, 1})
    @DisplayName("AC-17: entries に同じ studentUserId が重複していれば両経路とも 400・DB に 1 行も作られない")
    void entriesの重複は400(int route) throws Exception {
        long rowsBefore = registrationRows();
        long eventsBefore = rollCallEvents();

        auth(Actor.HOMEROOM);
        MvcResult result = mockMvc.perform(post(ROUTES[route], teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(entriesBody(date.plusDays(5),
                                entry(studentAId, "ABSENT"), entry(studentAId, "ATTENDING")))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(registrationRows()).as("重複は 1 行も作らない").isEqualTo(rowsBefore);
        assertThat(rollCallEvents()).isEqualTo(eventsBefore);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-24 認可・所属判定のクエリ数は entries 件数に依存しない
    // ═════════════════════════════════════════════════════════════════════

    private static final String[] AUTH_TABLES = {
            "memberships", "class_homerooms", "user_roles", "users", "permission_groups", "user_permission_groups"};

    private int authQueries(Supplier<Void> action) {
        em.flush();
        em.clear();
        SqlIntentCounter.reset();
        action.get();
        int total = 0;
        for (String table : AUTH_TABLES) {
            total += SqlIntentCounter.intentCount(table);
        }
        return total;
    }

    private List<Long> enrolledStudents(int n) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Long u = newUser("bulk-" + i);
            addMember(u, teamAId);
            ids.add(u);
        }
        em.flush();
        em.clear();
        return ids;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(java.time.LocalDate day, List<Long> students) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attendanceDate", day.toString());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Long s : students) {
            entries.add(entry(s, "ATTENDING"));
        }
        body.put("entries", entries);
        return body;
    }

    private Void postJson(String url, Object body, Object... vars) {
        try {
            mockMvc.perform(post(url, vars).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andReturn();
            return null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("AC-24: 日次点呼の認可・所属判定クエリ数は entries が 1 件でも 30 件でも変わらない")
    void 点呼の認可クエリ数はentries件数に依存しない() {
        List<Long> students = enrolledStudents(30);
        auth(Actor.HOMEROOM);
        // 認可結果のキャッシュ（role-permissions）の初回構築を計測から外す。
        postJson("/api/v1/teams/{t}/attendance/daily/roll-call", bodyOf(date.plusDays(20), students.subList(0, 1)),
                teamAId);

        int small = authQueries(() -> postJson("/api/v1/teams/{t}/attendance/daily/roll-call",
                bodyOf(date.plusDays(21), students.subList(0, 1)), teamAId));
        int large = authQueries(() -> postJson("/api/v1/teams/{t}/attendance/daily/roll-call",
                bodyOf(date.plusDays(22), students), teamAId));

        assertThat(small).as("計測が空でないこと（認可・所属判定のクエリが実際に走る）").isPositive();
        assertThat(large).as("30 件でも認可・所属判定のクエリ数は 1 件のときと同じ").isEqualTo(small);
    }

    @Test
    @DisplayName("AC-24: 時限登録の認可・所属判定クエリ数は entries が 1 件でも 30 件でも変わらない")
    void 時限登録の認可クエリ数はentries件数に依存しない() {
        List<Long> students = enrolledStudents(30);
        auth(Actor.HOMEROOM);
        postJson("/api/v1/teams/{t}/attendance/periods/4", bodyOf(date, students.subList(0, 1)), teamAId);

        int small = authQueries(() -> postJson("/api/v1/teams/{t}/attendance/periods/5",
                bodyOf(date, students.subList(0, 1)), teamAId));
        int large = authQueries(() -> postJson("/api/v1/teams/{t}/attendance/periods/6",
                bodyOf(date, students), teamAId));

        assertThat(small).as("計測が空でないこと").isPositive();
        assertThat(large).as("30 件でも認可・所属判定のクエリ数は 1 件のときと同じ").isEqualTo(small);
    }
}
