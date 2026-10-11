package com.mannschaft.app.parking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.parking.entity.ParkingSettingsEntity;
import com.mannschaft.app.parking.entity.ParkingSpaceEntity;
import com.mannschaft.app.parking.entity.ParkingVisitorReservationEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260821-1027 P01/P03: 来場者予約・定期テンプレートの本文 spaceId を URL の親 scope に束縛する試練。
 *
 * <p>共通 MySQL 基盤、実 PasswordEncoder、実ログイン Cookie とフィルタ鎖を使用する。
 * Auth/認可/業務 Repository はモックしない。Redis のみ基底クラスの外部依存モックを利用する。
 * 個別 Space の非所属 403/404、SYSTEM_ADMIN の許可主体、テンプレートの非 VISITOR 制限は対象外。</p>
 *
 * <p>拒否対象の POST 後も flush/clear して JDBC で所有行の全列・全集合を読み直し、応答と別々に検証する。
 * flush が失敗した場合は試練自体を失敗させ、DB 不変を合格扱いにしない。
 * 外側 TX は各ケース後に rollback するため、通知の afterCommit 配送は本試練では検証しない。</p>
 *
 * <p>不正親 ID は修正前 RED を要求する。正常 201/null 400/同 scope の既存業務エラーは回帰対照であり、
 * 初回 GREEN が正常。Docker 未到達やコンパイル失敗は実 RED の代用にならない。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP1027 来場者予約・テンプレートの本文親 scope HTTP 契約")
class ParkingVisitorParentScopeHttpContractIT extends AbstractMySqlIntegrationTest {

    private static final String PASSWORD = "ParkingParent1!";
    private static final String RESERVATION = "visitor-reservations";
    private static final String TEMPLATE = "visitor-recurring";
    private static final String SPACE_NOT_FOUND_BODY =
            "{\"error\":{\"code\":\"PARKING_001\",\"message\":\"駐車区画が見つかりません\",\"fieldErrors\":[]}}";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("wallClock") private Clock wallClock;
    @PersistenceContext private EntityManager em;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void 共通外部Redisの値操作だけを用意する() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        // get/hasKey は未失効・未ロック、increment の null は実 AuthTokenService が初回 1 に補正する。
        // JWT 生成・検証、パスワード照合、所属判定には一切スタブを設けない。
    }

    @ParameterizedTest(name = "P01 {0}/{1}: 同一404本文かつ保存なし")
    @MethodSource("reservationInvalidParents")
    void 予約の不正親は混雑と種別を漏らさず不在へ畳む(ScopeType scope, InvalidParent parent) throws Exception {
        Fixture fixture = fixture(scope);
        long spaceId = parentId(fixture, parent);
        if (parent == InvalidParent.FOREIGN_OVERLAP) {
            insertReservation(fixture.foreignSpace(), fixture.foreignActor(), fixture.date());
        }
        assertRejected(fixture, RESERVATION, reservationBody(spaceId, fixture.date()),
                404, objectMapper.readTree(SPACE_NOT_FOUND_BODY));
    }

    @ParameterizedTest(name = "P03 {0}/{1}: 同一404本文かつ保存なし")
    @MethodSource("templateInvalidParents")
    void テンプレートの不正親は不在へ畳み保存しない(ScopeType scope, InvalidParent parent) throws Exception {
        Fixture fixture = fixture(scope);
        assertRejected(fixture, TEMPLATE, templateBody(parentId(fixture, parent), fixture.date()),
                404, objectMapper.readTree(SPACE_NOT_FOUND_BODY));
    }

    @ParameterizedTest(name = "P01/P03 別scopeType {0}/{1}: 同一404本文かつ保存なし")
    @MethodSource("endpointCases")
    void 別typeの実在区画も本文親に指定できない(ScopeType scope, String endpoint) throws Exception {
        Fixture fixture = fixture(scope);
        ScopeType otherType = scope == ScopeType.TEAM ? ScopeType.ORGANIZATION : ScopeType.TEAM;
        String slug = "x-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        long otherScopeId = insertScope(otherType, slug);
        MembershipTestHelper.insertMembership(em, fixture.foreignActor(), otherType, otherScopeId, RoleKind.MEMBER);
        ParkingSpaceEntity otherSpace = insertSpace(otherType, otherScopeId, fixture.foreignActor(),
                "other-type", SpaceType.VISITOR);
        flushAndClear();
        // 各tableの実採番を使う。未知scope借用・ID上書き・同数値化用paddingはしない。
        // scopeIdが異なる場合、この対照だけでscopeType条件「単独」の欠落を検出したとは言わない。
        // 正準findByIdAndScopeTypeAndScopeIdの全AND条件は別途source根拠と対応させる。
        Map<String, Object> request = RESERVATION.equals(endpoint)
                ? reservationBody(otherSpace.getId(), fixture.date()) : templateBody(otherSpace.getId(), fixture.date());
        assertRejected(fixture, endpoint, request, 404, objectMapper.readTree(SPACE_NOT_FOUND_BODY), otherSpace.getId());
    }

    @ParameterizedTest(name = "正常予約 {0}/承認必要={1}")
    @MethodSource("approvalCases")
    void 所属先の来場者区画は201で保存し承認初期状態を維持する(ScopeType scope, boolean approval) throws Exception {
        Fixture fixture = fixture(scope);
        ParkingSettingsEntity settings = em.find(ParkingSettingsEntity.class, fixture.settingsId());
        settings.update(1, 2, 30, approval);
        flushAndClear();
        MvcResult result = submit(fixture, RESERVATION, reservationBody(fixture.localSpace(), fixture.date()));
        flushAndClear();
        JsonNode data = body(result).path("data");
        String expectedStatus = approval ? "PENDING_APPROVAL" : "CONFIRMED";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, space_id, reserved_by, status FROM parking_visitor_reservations WHERE reserved_by = ?",
                fixture.actor());
        assertAll(
                () -> assertThat(result.getResponse().getStatus()).isEqualTo(201),
                () -> assertThat(data.path("spaceId").asLong()).isEqualTo(fixture.localSpace()),
                () -> assertThat(data.path("reservedBy").asLong()).isEqualTo(fixture.actor()),
                () -> assertThat(data.path("status").asText()).isEqualTo(expectedStatus),
                () -> assertThat(rows).hasSize(1).first().satisfies(row -> {
                    assertThat(((Number) row.get("id")).longValue()).isEqualTo(data.path("id").asLong());
                    assertThat(((Number) row.get("space_id")).longValue()).isEqualTo(fixture.localSpace());
                    assertThat(((Number) row.get("reserved_by")).longValue()).isEqualTo(fixture.actor());
                    assertThat(row.get("status")).isEqualTo(expectedStatus);
                }));
    }

    @ParameterizedTest(name = "正常テンプレート {0}")
    @EnumSource(value = ScopeType.class, names = {"TEAM", "ORGANIZATION"})
    void 所属先の来場者区画テンプレートは201で親と本人を保存する(ScopeType scope) throws Exception {
        Fixture fixture = fixture(scope);
        MvcResult result = submit(fixture, TEMPLATE, templateBody(fixture.localSpace(), fixture.date()));
        flushAndClear();
        JsonNode data = body(result).path("data");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, space_id, user_id, scope_type, scope_id, is_active FROM parking_visitor_recurring WHERE user_id = ?",
                fixture.actor());
        assertAll(
                () -> assertThat(result.getResponse().getStatus()).isEqualTo(201),
                () -> assertThat(data.path("spaceId").asLong()).isEqualTo(fixture.localSpace()),
                () -> assertThat(data.path("userId").asLong()).isEqualTo(fixture.actor()),
                () -> assertThat(data.path("isActive").asBoolean()).isTrue(),
                () -> assertThat(rows).hasSize(1).first().satisfies(row -> {
                    assertThat(((Number) row.get("id")).longValue()).isEqualTo(data.path("id").asLong());
                    assertThat(((Number) row.get("space_id")).longValue()).isEqualTo(fixture.localSpace());
                    assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(fixture.actor());
                    assertThat(row.get("scope_type")).isEqualTo(scope.name());
                    assertThat(((Number) row.get("scope_id")).longValue()).isEqualTo(fixture.scopeId());
                }));
    }

    @ParameterizedTest(name = "null親 {0}/{1}: 400かつ保存なし")
    @MethodSource("endpointCases")
    void null本文親は既存BeanValidation400で保存しない(ScopeType scope, String endpoint) throws Exception {
        Fixture fixture = fixture(scope);
        Map<String, Object> request = RESERVATION.equals(endpoint)
                ? reservationBody(null, fixture.date()) : templateBody(null, fixture.date());
        Map<String, List<Map<String, Object>>> before = snapshot(fixture);
        MvcResult result = submit(fixture, endpoint, request);
        Map<String, List<Map<String, Object>>> after = snapshot(fixture);
        JsonNode error = body(result).path("error");
        assertAll(
                () -> assertThat(result.getResponse().getStatus()).isEqualTo(400),
                () -> assertThat(error.path("code").asText()).isEqualTo("COMMON_001"),
                () -> assertThat(error.path("fieldErrors").findValuesAsText("field")).contains("spaceId"),
                () -> assertThat(after).as("null親拒否前後の所有DB行").isEqualTo(before));
    }

    @ParameterizedTest(name = "同scope既存規則 {0}/{1}")
    @MethodSource("businessErrorCases")
    void 同scopeの混雑日数時刻エラーは既存契約を維持する(ScopeType scope, BusinessError error) throws Exception {
        Fixture fixture = fixture(scope);
        Map<String, Object> request = reservationBody(fixture.localSpace(), fixture.date());
        if (error == BusinessError.OVERLAP) {
            insertReservation(fixture.localSpace(), fixture.actor(), fixture.date());
        } else if (error == BusinessError.DATE_LIMIT) {
            request.put("reservedDate", LocalDate.now(wallClock).plusDays(32).toString());
        } else {
            request.put("timeFrom", "09:15");
        }
        JsonNode expected = objectMapper.valueToTree(Map.of("error", Map.of(
                "code", error.code, "message", error.message, "fieldErrors", List.of())));
        assertRejected(fixture, RESERVATION, request, error.status, expected);
    }

    private void assertRejected(Fixture fixture, String endpoint, Map<String, Object> request,
                                int expectedStatus, JsonNode expectedBody) throws Exception {
        assertRejected(fixture, endpoint, request, expectedStatus, expectedBody, null);
    }

    private void assertRejected(Fixture fixture, String endpoint, Map<String, Object> request,
                                int expectedStatus, JsonNode expectedBody, Long otherTypeSpaceId) throws Exception {
        Map<String, List<Map<String, Object>>> before = snapshot(fixture, otherTypeSpaceId);
        MvcResult result = submit(fixture, endpoint, request);
        Map<String, List<Map<String, Object>>> after = snapshot(fixture, otherTypeSpaceId);
        JsonNode actualBody = body(result);
        // 先に実DB読取を完了し、応答 RED でも不変 assertion を未到達にしない。
        assertAll(
                () -> assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus),
                () -> assertThat(actualBody).as("コード・日本語本文・空fieldErrorsを含む絶対値/同値").isEqualTo(expectedBody),
                () -> assertThat(after).as("対象POST前後の所有DB行全集合・全列").isEqualTo(before));
    }

    private MvcResult submit(Fixture fixture, String endpoint, Map<String, Object> request) throws Exception {
        String url = fixture.url() + "/" + endpoint;
        return mockMvc.perform(post(url).servletPath(url).cookie(fixture.cookie())
                        .header("Accept-Language", "ja").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Fixture fixture(ScopeType scope) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UserEntity actor = insertUser("parking-parent-a-" + suffix + "@example.com");
        UserEntity foreignActor = insertUser("parking-parent-b-" + suffix + "@example.com");
        long scopeId = insertScope(scope, "a-" + suffix.substring(0, 20));
        long foreignScopeId = insertScope(scope, "b-" + suffix.substring(0, 20));
        MembershipTestHelper.insertMembership(em, actor.getId(), scope, scopeId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, foreignActor.getId(), scope, foreignScopeId, RoleKind.MEMBER);
        ParkingSpaceEntity local = insertSpace(scope, scopeId, actor.getId(), "local", SpaceType.VISITOR);
        ParkingSpaceEntity foreign = insertSpace(scope, foreignScopeId, foreignActor.getId(), "foreign", SpaceType.VISITOR);
        ParkingSpaceEntity foreignIndoor = insertSpace(scope, foreignScopeId, foreignActor.getId(), "indoor", SpaceType.INDOOR);
        ParkingSpaceEntity deleted = insertSpace(scope, scopeId, actor.getId(), "deleted", SpaceType.VISITOR);
        deleted.softDelete();
        ParkingSpaceEntity missing = insertSpace(scope, scopeId, actor.getId(), "missing", SpaceType.VISITOR);
        long missingId = missing.getId();
        em.remove(missing);
        ParkingSettingsEntity settings = ParkingSettingsEntity.builder().scopeType(scope.name()).scopeId(scopeId).build();
        em.persist(settings);
        long actorId = actor.getId();
        long foreignActorId = foreignActor.getId();
        flushAndClear();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM parking_spaces WHERE id = ?", Long.class, missingId))
                .as("不在検体は実採番後に物理削除済み").isZero();
        String loginUrl = "/api/v1/auth/login";
        MvcResult login = mockMvc.perform(post(loginUrl).servletPath(loginUrl)
                        .header("User-Agent", "ParkingParentContract/1.0").header("Accept-Language", "ja")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                                "email", actor.getEmail(), "password", PASSWORD, "rememberMe", false))))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = login.getResponse().getCookie("access_token");
        assertThat(cookie).as("実ログインが発行したaccess_token Cookie").isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(body(login).path("data").path("userId").asLong()).isEqualTo(actorId);
        flushAndClear();
        String collection = scope == ScopeType.TEAM ? "teams" : "organizations";
        return new Fixture(scope, scopeId, foreignScopeId, actorId, foreignActorId, local.getId(), foreign.getId(),
                foreignIndoor.getId(), deleted.getId(), missingId, settings.getId(),
                LocalDate.now(wallClock).plusDays(2), "/api/v1/" + collection + "/" + scopeId + "/parking", cookie);
    }

    private UserEntity insertUser(String email) {
        UserEntity user = UserEntity.builder().email(email).passwordHash(passwordEncoder.encode(PASSWORD))
                .lastName("親束縛").firstName("試練").displayName("駐車場親束縛試練")
                .status(UserEntity.UserStatus.ACTIVE).isSearchable(false).locale("ja").timezone("Asia/Tokyo").build();
        em.persist(user);
        return user;
    }

    private long insertScope(ScopeType scope, String slug) {
        if (scope == ScopeType.TEAM) {
            TeamEntity team = TeamEntity.builder().name("駐車場親束縛" + slug).slug(slug)
                    .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(false).build();
            em.persist(team);
            return team.getId();
        }
        OrganizationEntity org = OrganizationEntity.builder().name("駐車場親束縛" + slug).slug(slug)
                .orgType(OrganizationEntity.OrgType.COMMUNITY).visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build();
        em.persist(org);
        return org.getId();
    }

    private ParkingSpaceEntity insertSpace(ScopeType scope, long scopeId, long actor, String number, SpaceType type) {
        ParkingSpaceEntity space = ParkingSpaceEntity.builder().scopeType(scope.name()).scopeId(scopeId)
                .spaceNumber(number).spaceType(type).createdBy(actor).build();
        em.persist(space);
        return space;
    }

    private void insertReservation(long spaceId, long actor, LocalDate date) {
        em.persist(ParkingVisitorReservationEntity.builder().spaceId(spaceId).reservedBy(actor).reservedDate(date)
                .timeFrom(LocalTime.of(9, 0)).timeTo(LocalTime.of(9, 30)).status(VisitorReservationStatus.CONFIRMED).build());
        flushAndClear();
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private Map<String, List<Map<String, Object>>> snapshot(Fixture fixture) {
        return snapshot(fixture, null);
    }

    private Map<String, List<Map<String, Object>>> snapshot(Fixture fixture, Long otherTypeSpaceId) {
        flushAndClear();
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        rows.put("spaces", jdbcTemplate.queryForList(
                "SELECT * FROM parking_spaces WHERE (scope_type = ? AND scope_id IN (?, ?)) OR id = ? ORDER BY id",
                fixture.scope().name(), fixture.scopeId(), fixture.foreignScopeId(), otherTypeSpaceId));
        rows.put("settings", jdbcTemplate.queryForList(
                "SELECT * FROM parking_settings WHERE scope_type = ? AND scope_id IN (?, ?) ORDER BY id",
                fixture.scope().name(), fixture.scopeId(), fixture.foreignScopeId()));
        rows.put("reservations", jdbcTemplate.queryForList(
                "SELECT * FROM parking_visitor_reservations WHERE reserved_by IN (?, ?) "
                        + "OR space_id IN (?, ?, ?, ?, ?, ?) ORDER BY id",
                fixture.actor(), fixture.foreignActor(), fixture.localSpace(), fixture.foreignSpace(),
                fixture.foreignIndoor(), fixture.deletedSpace(), fixture.missingSpace(), otherTypeSpaceId));
        rows.put("templates", jdbcTemplate.queryForList(
                "SELECT * FROM parking_visitor_recurring WHERE user_id IN (?, ?) "
                        + "OR (scope_type = ? AND scope_id IN (?, ?)) OR space_id = ? ORDER BY id",
                fixture.actor(), fixture.foreignActor(), fixture.scope().name(), fixture.scopeId(),
                fixture.foreignScopeId(), otherTypeSpaceId));
        return rows;
    }

    private Map<String, Object> reservationBody(Long spaceId, LocalDate date) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("spaceId", spaceId);
        request.put("reservedDate", date.toString());
        request.put("timeFrom", "09:00");
        request.put("timeTo", "09:30");
        request.put("visitorName", "専用試練来場者");
        request.put("visitorPlateNumber", "試練1000");
        request.put("purpose", "本文親scope契約");
        return request;
    }

    private Map<String, Object> templateBody(Long spaceId, LocalDate date) {
        Map<String, Object> request = reservationBody(spaceId, date);
        request.remove("reservedDate");
        request.put("recurrenceType", "WEEKLY");
        request.put("dayOfWeek", date.getDayOfWeek().getValue());
        request.put("nextGenerateDate", date.toString());
        return request;
    }

    private static long parentId(Fixture fixture, InvalidParent parent) {
        return switch (parent) {
            case FOREIGN_EMPTY, FOREIGN_OVERLAP -> fixture.foreignSpace();
            case FOREIGN_NON_VISITOR -> fixture.foreignIndoor();
            case MISSING -> fixture.missingSpace();
            case DELETED -> fixture.deletedSpace();
        };
    }

    private static Stream<Arguments> reservationInvalidParents() {
        return scopes().flatMap(scope -> Stream.of(InvalidParent.values()).map(parent -> Arguments.of(scope, parent)));
    }

    private static Stream<Arguments> templateInvalidParents() {
        return scopes().flatMap(scope -> Stream.of(InvalidParent.FOREIGN_EMPTY, InvalidParent.MISSING, InvalidParent.DELETED)
                .map(parent -> Arguments.of(scope, parent)));
    }

    private static Stream<Arguments> approvalCases() {
        return scopes().flatMap(scope -> Stream.of(true, false).map(approval -> Arguments.of(scope, approval)));
    }

    private static Stream<Arguments> endpointCases() {
        return scopes().flatMap(scope -> Stream.of(RESERVATION, TEMPLATE).map(endpoint -> Arguments.of(scope, endpoint)));
    }

    private static Stream<Arguments> businessErrorCases() {
        return scopes().flatMap(scope -> Stream.of(BusinessError.values()).map(error -> Arguments.of(scope, error)));
    }

    private static Stream<ScopeType> scopes() {
        return Stream.of(ScopeType.TEAM, ScopeType.ORGANIZATION);
    }

    private enum InvalidParent { FOREIGN_EMPTY, FOREIGN_OVERLAP, FOREIGN_NON_VISITOR, MISSING, DELETED }

    private enum BusinessError {
        OVERLAP(409, "PARKING_018", "指定された時間帯は既に予約されています"),
        DATE_LIMIT(400, "PARKING_017", "予約可能な日数の範囲外です"),
        TIME_SLOT(400, "PARKING_034", "時刻は30分単位で指定してください");

        final int status;
        final String code;
        final String message;

        BusinessError(int status, String code, String message) {
            this.status = status;
            this.code = code;
            this.message = message;
        }
    }

    private record Fixture(ScopeType scope, long scopeId, long foreignScopeId, long actor, long foreignActor,
                           long localSpace, long foreignSpace, long foreignIndoor, long deletedSpace, long missingSpace,
                           long settingsId, LocalDate date, String url, Cookie cookie) { }
}
