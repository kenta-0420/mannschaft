package com.mannschaft.app.shift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 認可ファサード型 W6a（shift の schedules 9 本・slots 6 本・remind・PDF）の<b>EP×主体の応答契約</b>（試練 / red 先行）。
 *
 * <p>正本: {@code .claude/campaigns/2026-09-25-authz-softdelete-guard/ep_tables_w6.md} の EP 表（§1）と
 * 「殿の判断（2026-10-03）」、plan4 の K4・K5、plan1 の AC-1〜AC-11。行＝主体、列＝status・error.code・error.message。
 * 既存の {@code ShiftScheduleScopeContractIT}・{@code ShiftSlotScopeContractIT} は status 中心で主体が欠けるため、
 * ここで全 EP×全主体を status・code・message まで固定する（是正前と同じ応答＝AC-16 の正本）。</p>
 *
 * <h3>red になる（是正で直す）もの</h3>
 * <ul>
 *   <li>remind（S8）の越境: 是正前は 403 COMMON_002、不在は 404 SHIFT_001 で割れる（存在オラクル）。
 *       越境も 404 SHIFT_001 に揃える（殿の判断 1）。</li>
 *   <li>remind の Valkey ロックが認可より前: 部外者がロックを取り、直後の管理者が 429 になる（殿の判断 1）。</li>
 *   <li>slots の update・assignments・delete で、枠は生きていて親スケジュールだけ論理削除済みのとき
 *       是正前は SHIFT_001。対象リソース（枠）の不在コード SHIFT_002 に揃える（K5・殿の判断 6）。</li>
 * </ul>
 *
 * <h3>緑のまま固定する（退行させない）もの</h3>
 * <ul>
 *   <li>同じチームの権限不足（一般メンバーの管理操作・SUPPORTER の参照）は 403 COMMON_002（AC-2）。</li>
 *   <li>teamId 直接指定（S1・S3）の越境・不在は 403 COMMON_002（AC-6）。</li>
 *   <li>SYSTEM_ADMIN（非メンバー・メンバーの両方）は全 EP で是正前どおり通る（殿の判断 5。新規許可なし）。
 *       存在しない teamId に SA が作成できる件は別課題（W6a では現行維持）。</li>
 *   <li>PDF の越境は 404 SHIFT_001 のまま（殿の判断 2）。</li>
 *   <li>user_roles だけを持つ ADMIN（memberships の在籍行なし）: 詳細（S2）・枠一覧（L1）・管理系は通し、
 *       一覧（S1）と PDF は是正前どおり 403 COMMON_002（殿の判断 4＝退行させない。許可は広げない）。</li>
 *   <li>ID 境界（0・負数・Long.MAX_VALUE は不在と同じ、非数値・Long 超過は 400）（AC-9・AC-10）。</li>
 * </ul>
 *
 * <p>Valkey は基底クラスで {@code StringRedisTemplate} がモックなので、{@code setIfAbsent} を
 * メモリ上の Map で模す（キーの有無で「部外者がロックを取らない」を確かめる）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift W6a（schedules・slots・remind・PDF）EP×主体の応答契約（試練）")
class ShiftScheduleSlotFacadeContractIT extends AbstractMySqlIntegrationTest {

    private static final String SCHEDULES = "/api/v1/shifts/schedules";
    private static final String SLOTS = "/api/v1/shifts/slots";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ShiftScheduleRepository scheduleRepository;
    @Autowired
    private ShiftSlotRepository slotRepository;
    @Autowired
    private FeatureFlagRepository featureFlagRepository;
    @Autowired
    private CacheManager cacheManager;
    @PersistenceContext
    private EntityManager em;

    /** 手動リマインドの Valkey ロック（SET NX EX）を模した Map。キー → 値。 */
    private final Map<String, String> valkey = new ConcurrentHashMap<>();

    private Long teamAId;
    private Long teamBId;
    private final Map<Actor, Long> actorIds = new LinkedHashMap<>();
    private Long scheduleAId;
    private Long collectingScheduleId;
    private Long slotAId;

    // ═════════════════════════════════════════════════════════════════════
    // 主体と EP
    // ═════════════════════════════════════════════════════════════════════

    /** 主体（行）。 */
    enum Actor {
        /** チーム A の ADMIN（memberships の MEMBER ＋ user_roles の ADMIN）。 */
        ADMIN_A,
        /** チーム A の一般メンバー。 */
        MEMBER_A,
        /** チーム A の SUPPORTER。 */
        SUPPORTER_A,
        /** user_roles にだけチーム A の ADMIN を持つ（memberships の在籍行なし）。 */
        USER_ROLES_ONLY_ADMIN_A,
        /** チーム B の ADMIN（越境）。 */
        ADMIN_B,
        /** どこにも属さない認証済み利用者（越境）。 */
        OUTSIDER,
        /** 非メンバーの SYSTEM_ADMIN。 */
        SYSTEM_ADMIN,
        /** チーム A の一般メンバーでもある SYSTEM_ADMIN。 */
        SYSTEM_ADMIN_MEMBER_A;

        boolean crossBorder() {
            return this == ADMIN_B || this == OUTSIDER;
        }

        boolean systemAdmin() {
            return this == SYSTEM_ADMIN || this == SYSTEM_ADMIN_MEMBER_A;
        }
    }

    /** ID の起点。 */
    enum Kind { TEAM, SCHEDULE, COLLECTING_SCHEDULE, SLOT }

    /** 認可の種類。 */
    enum Access { READ, ADMIN }

    /** 対象 EP（列）。成功時の status と、ID 起点の不在コード。 */
    enum Ep {
        S1_LIST(Kind.TEAM, Access.READ, 200, null),
        S1_LIST_PERIOD(Kind.TEAM, Access.READ, 200, null),
        S2_GET(Kind.SCHEDULE, Access.READ, 200, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND),
        S3_CREATE(Kind.TEAM, Access.ADMIN, 201, null),
        S4_UPDATE(Kind.SCHEDULE, Access.ADMIN, 200, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND),
        S5_DELETE(Kind.SCHEDULE, Access.ADMIN, 204, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND),
        S6_TRANSITION(Kind.SCHEDULE, Access.ADMIN, 200, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND),
        S7_SUMMARY(Kind.SCHEDULE, Access.ADMIN, 200, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND),
        S8_REMIND(Kind.COLLECTING_SCHEDULE, Access.ADMIN, 200, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND),
        S9_DUPLICATE(Kind.SCHEDULE, Access.ADMIN, 201, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND),
        L1_LIST(Kind.SCHEDULE, Access.READ, 200, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND),
        L2_CREATE(Kind.SCHEDULE, Access.ADMIN, 201, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND),
        L3_BULK(Kind.SCHEDULE, Access.ADMIN, 201, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND),
        L4_UPDATE(Kind.SLOT, Access.ADMIN, 200, ShiftErrorCode.SHIFT_SLOT_NOT_FOUND),
        L5_ASSIGN(Kind.SLOT, Access.ADMIN, 200, ShiftErrorCode.SHIFT_SLOT_NOT_FOUND),
        L6_DELETE(Kind.SLOT, Access.ADMIN, 204, ShiftErrorCode.SHIFT_SLOT_NOT_FOUND),
        /** PDF は描画の成否（テンプレート・フォント）が関心外なので、許可は「403/404 でない」で判定する。 */
        P1_PDF_TEAM(Kind.SCHEDULE, Access.READ, -1, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND),
        P2_PDF_PERSONAL(Kind.SCHEDULE, Access.READ, -1, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);

        final Kind kind;
        final Access access;
        final int successStatus;
        final ShiftErrorCode notFound;

        Ep(Kind kind, Access access, int successStatus, ShiftErrorCode notFound) {
            this.kind = kind;
            this.access = access;
            this.successStatus = successStatus;
            this.notFound = notFound;
        }

        boolean write() {
            return access == Access.ADMIN;
        }

        boolean pdf() {
            return this == P1_PDF_TEAM || this == P2_PDF_PERSONAL;
        }
    }

    /** 期待応答。{@code code == null} は許可（成功）。 */
    private record Expect(int status, ErrorCode code) {
        static Expect ok(int status) {
            return new Expect(status, null);
        }

        static Expect error(int status, ErrorCode code) {
            return new Expect(status, code);
        }

        @Override
        public String toString() {
            return code == null ? "許可(" + status + ")" : status + " " + code.getCode();
        }
    }

    /** ep_tables_w6.md §1.2・§1.3 と殿の判断（2026-10-03）から導く期待値。 */
    static Expect expected(Ep ep, Actor actor) {
        Expect forbidden = Expect.error(403, CommonErrorCode.COMMON_002);
        if (actor.systemAdmin()) {
            // 殿の判断 5: SA は是正前から全 EP で通っている（新規許可なし）。
            return Expect.ok(ep.successStatus);
        }
        if (actor.crossBorder()) {
            // teamId 直接指定（S1・S3）は隠さない（AC-6）。それ以外は ID 起点の不在コードへ畳む（remind も・殿の判断 1）。
            return ep.kind == Kind.TEAM ? forbidden : Expect.error(404, ep.notFound);
        }
        switch (actor) {
            case ADMIN_A:
                return Expect.ok(ep.successStatus);
            case USER_ROLES_ONLY_ADMIN_A:
                // 殿の判断 4（退行させない＝是正前の挙動を保つ）: 一覧（S1）と PDF は是正前から在籍を要求して 403。
                // 許可を広げないので 403 のまま固定する（AC-16）。それ以外（S2・L1・管理系）は是正前どおり通す。
                if (ep == Ep.S1_LIST || ep == Ep.S1_LIST_PERIOD || ep.pdf()) {
                    return forbidden;
                }
                return Expect.ok(ep.successStatus);
            case MEMBER_A:
                return ep.access == Access.READ ? Expect.ok(ep.successStatus) : forbidden;
            case SUPPORTER_A:
                return forbidden;
            default:
                throw new IllegalStateException(actor.name());
        }
    }

    static Stream<Arguments> matrix() {
        return Arrays.stream(Ep.values())
                .flatMap(ep -> Arrays.stream(Actor.values()).map(actor -> Arguments.of(ep, actor)));
    }

    // ═════════════════════════════════════════════════════════════════════
    // セットアップ
    // ═════════════════════════════════════════════════════════════════════

    @BeforeEach
    void setUp() {
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_SHIFT_ENABLED");
        stubValkey();

        teamAId = insertTeam("W6A 契約 チームA");
        teamBId = insertTeam("W6A 契約 チームB");
        for (Actor actor : Actor.values()) {
            actorIds.put(actor, insertUser("w6a-contract-" + actor.name().toLowerCase() + "@example.com"));
        }
        // 親の行（チーム・利用者・所属・ロール）を実在させる（W4 の教訓）。
        MembershipTestHelper.insertMembership(em, id(Actor.ADMIN_A), ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, id(Actor.ADMIN_A), "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, id(Actor.MEMBER_A), ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, id(Actor.SUPPORTER_A), ScopeType.TEAM, teamAId, RoleKind.SUPPORTER);
        MembershipTestHelper.insertUserRole(em, id(Actor.USER_ROLES_ONLY_ADMIN_A), "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, id(Actor.ADMIN_B), ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, id(Actor.ADMIN_B), "ADMIN", teamBId, null);
        MembershipTestHelper.insertUserRole(em, id(Actor.SYSTEM_ADMIN), "SYSTEM_ADMIN", null, null);
        MembershipTestHelper.insertUserRole(em, id(Actor.SYSTEM_ADMIN_MEMBER_A), "SYSTEM_ADMIN", null, null);
        MembershipTestHelper.insertMembership(em, id(Actor.SYSTEM_ADMIN_MEMBER_A), ScopeType.TEAM, teamAId,
                RoleKind.MEMBER);

        scheduleAId = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamAId)
                .title("W6A 契約 公開済み")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                // 認可契約を固定するので公開済み（未公開の 404 は ShiftUnpublishedScheduleVisibilityContractIT）。
                .status(ShiftScheduleStatus.PUBLISHED)
                .publishedAt(LocalDateTime.of(2026, 2, 20, 10, 0))
                .createdBy(id(Actor.ADMIN_A))
                .build()).getId();
        // remind は COLLECTING のときだけ成功する（それ以外は認可の後に 409 SHIFT_012）。
        collectingScheduleId = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamAId)
                .title("W6A 契約 希望収集中")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 4, 1))
                .endDate(LocalDate.of(2026, 4, 7))
                .status(ShiftScheduleStatus.COLLECTING)
                .createdBy(id(Actor.ADMIN_A))
                .build()).getId();
        slotAId = slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(scheduleAId)
                .slotDate(LocalDate.of(2026, 3, 2))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .requiredCount(2)
                .build()).getId();
        em.flush();
        em.clear();
    }

    @SuppressWarnings("unchecked")
    private void stubValkey() {
        valkey.clear();
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(ops);
        Mockito.when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> valkey.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. EP×主体の表（status・code・message）
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0} × {1}")
    @MethodSource("matrix")
    @DisplayName("EP×主体: status・error.code・error.message が表どおり（K4・AC-1〜AC-5）")
    void EP主体の表どおりの応答(Ep ep, Actor actor) throws Exception {
        Expect expect = expected(ep, actor);
        MvcResult result = perform(actor, ep, targetId(ep));
        assertResponse(result, ep, expect, ep + " × " + actor);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 越境と不在が同じ応答（存在オラクル。AC-1）
    // ═════════════════════════════════════════════════════════════════════

    static Stream<Arguments> idEndpointsAndCrossBorderActors() {
        return Arrays.stream(Ep.values()).filter(ep -> ep.kind != Kind.TEAM)
                .flatMap(ep -> Stream.of(Actor.ADMIN_B, Actor.OUTSIDER).map(a -> Arguments.of(ep, a)));
    }

    @ParameterizedTest(name = "{0} × {1}")
    @MethodSource("idEndpointsAndCrossBorderActors")
    @DisplayName("AC-1: 越境（実在 ID）と不在 ID の応答が status・code・message・本文（timestamp 除く）まで一致（remind は red）")
    void 越境と不在の応答が一致する(Ep ep, Actor actor) throws Exception {
        MvcResult cross = perform(actor, ep, targetId(ep));
        MvcResult missing = perform(actor, ep, String.valueOf(missingId(ep)));
        assertResponse(missing, ep, Expect.error(404, ep.notFound), ep + " 不在");
        assertResponse(cross, ep, Expect.error(404, ep.notFound), ep + " 越境");
        assertThat(stripTimestamp(cross)).isEqualTo(stripTimestamp(missing));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Ep.class, names = {"S1_LIST", "S1_LIST_PERIOD", "S3_CREATE"})
    @DisplayName("AC-6: teamId 直接指定は越境・不在とも同一の 403 COMMON_002（隠さない・応答は一致）")
    void teamId直接指定は越境も不在も同一の403(Ep ep) throws Exception {
        MvcResult cross = perform(Actor.OUTSIDER, ep, String.valueOf(teamAId));
        MvcResult missing = perform(Actor.OUTSIDER, ep, String.valueOf(teamAId + 999_999L));
        assertResponse(cross, ep, Expect.error(403, CommonErrorCode.COMMON_002), "越境");
        assertResponse(missing, ep, Expect.error(403, CommonErrorCode.COMMON_002), "不在");
        assertThat(stripTimestamp(cross)).isEqualTo(stripTimestamp(missing));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Ep.class)
    @DisplayName("不在 ID: 管理者・SYSTEM_ADMIN とも ID 起点の不在コード（teamId 起点は是正前どおり）")
    void 不在IDは不在コード(Ep ep) throws Exception {
        String missing = String.valueOf(missingId(ep));
        if (ep.kind == Kind.TEAM) {
            assertResponse(perform(Actor.ADMIN_A, ep, missing), ep, Expect.error(403, CommonErrorCode.COMMON_002),
                    "管理者・不在 teamId");
            // 殿の判断 5: SA は是正前どおり（一覧は空で 200、作成は不在 teamId でも 201 ＝別課題で台帳起票候補）。
            assertResponse(perform(Actor.SYSTEM_ADMIN, ep, missing), ep, Expect.ok(ep.successStatus),
                    "SA・不在 teamId");
            return;
        }
        assertResponse(perform(Actor.ADMIN_A, ep, missing), ep, Expect.error(404, ep.notFound), "管理者・不在");
        assertResponse(perform(Actor.SYSTEM_ADMIN, ep, missing), ep, Expect.error(404, ep.notFound), "SA・不在");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. ID 境界（AC-9・AC-10）
    // ═════════════════════════════════════════════════════════════════════

    static Stream<Arguments> boundaries() {
        List<Arguments> list = new ArrayList<>();
        for (Ep ep : Ep.values()) {
            for (String id : List.of("0", "-1", String.valueOf(Long.MAX_VALUE))) {
                for (Actor actor : List.of(Actor.ADMIN_A, Actor.OUTSIDER)) {
                    list.add(Arguments.of(ep, id, actor));
                }
            }
        }
        return list.stream();
    }

    @ParameterizedTest(name = "{0} id={1} × {2}")
    @MethodSource("boundaries")
    @DisplayName("AC-10: ID が 0・負数・Long.MAX_VALUE は不在と同じ応答（teamId 起点は 403 COMMON_002）")
    void ID境界は不在と同じ(Ep ep, String id, Actor actor) throws Exception {
        Expect expect = ep.kind == Kind.TEAM
                ? Expect.error(403, CommonErrorCode.COMMON_002)
                : Expect.error(404, ep.notFound);
        assertResponse(perform(actor, ep, id), ep, expect, ep + " id=" + id);
    }

    static Stream<Arguments> malformed() {
        return Arrays.stream(Ep.values()).flatMap(ep -> Stream.of("abc", "9223372036854775808")
                .map(id -> Arguments.of(ep, id)));
    }

    @ParameterizedTest(name = "{0} id={1}")
    @MethodSource("malformed")
    @DisplayName("AC-10: 非数値・Long 超過の ID は 400（teamId 起点は 404。どの判定より前で止まり 500 にならない）")
    void 非数値IDは400(Ep ep, String id) throws Exception {
        // teamId は team スコープ識別子（slug）として扱う共通規約で、非数値は GlobalExceptionHandler#handleTypeMismatch
        // （isUnresolvedScopeSlug）が 404 COMMON_005 に写像する。本 PR はこのハンドラを変えておらず、
        // 他の team スコープ EP と揃えるのが正（W6a 以前から同じ挙動）。ID 起点（パス変数）は 400。
        int expected = ep.kind == Kind.TEAM ? 404 : 400;
        for (Actor actor : List.of(Actor.ADMIN_A, Actor.OUTSIDER)) {
            assertThat(perform(actor, ep, id).getResponse().getStatus()).as(ep + " × " + actor).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("AC-9: 必須の teamId・status の欠落は 400")
    void 必須パラメータ欠落は400() throws Exception {
        setAuth(id(Actor.ADMIN_A));
        assertThat(mockMvc.perform(get(SCHEDULES)).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mockMvc.perform(json(post(SCHEDULES), createScheduleBody())).andReturn().getResponse()
                .getStatus()).isEqualTo(400);
        assertThat(mockMvc.perform(post(SCHEDULES + "/" + scheduleAId + "/transition")).andReturn().getResponse()
                .getStatus()).isEqualTo(400);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 親だけが論理削除済み（K5）・自身が論理削除済み
    // ═════════════════════════════════════════════════════════════════════

    static Stream<Arguments> parentDeleted() {
        return Arrays.stream(Ep.values()).filter(ep -> ep.kind == Kind.SCHEDULE || ep.kind == Kind.SLOT
                        || ep.kind == Kind.COLLECTING_SCHEDULE)
                .flatMap(ep -> Stream.of(Actor.ADMIN_A, Actor.SYSTEM_ADMIN, Actor.OUTSIDER)
                        .map(a -> Arguments.of(ep, a)));
    }

    @ParameterizedTest(name = "{0} × {1}")
    @MethodSource("parentDeleted")
    @DisplayName("K5: スケジュールだけ論理削除済み（枠は生存）→ EP の対象リソースの不在コード（slots の更新・割当・削除は SHIFT_002＝red）")
    void 親スケジュールだけ削除済みは対象の不在コード(Ep ep, Actor actor) throws Exception {
        Long scheduleId = ep.kind == Kind.COLLECTING_SCHEDULE ? collectingScheduleId : scheduleAId;
        em.createNativeQuery("UPDATE shift_schedules SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", scheduleId).executeUpdate();
        em.flush();
        em.clear();
        String before = snapshot();
        assertResponse(perform(actor, ep, targetId(ep)), ep, Expect.error(404, ep.notFound), ep + " × " + actor);
        if (ep.write()) {
            assertThat(snapshot()).as("拒否後も DB 不変").isEqualTo(before);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Ep.class, names = {"L4_UPDATE", "L5_ASSIGN", "L6_DELETE"})
    @DisplayName("枠自身が論理削除済み → 404 SHIFT_002（管理者・SA）")
    void 枠自身が削除済みはSHIFT_002(Ep ep) throws Exception {
        em.createNativeQuery("UPDATE shift_slots SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", slotAId).executeUpdate();
        em.flush();
        em.clear();
        for (Actor actor : List.of(Actor.ADMIN_A, Actor.SYSTEM_ADMIN)) {
            assertResponse(perform(actor, ep, targetId(ep)), ep, Expect.error(404, ShiftErrorCode.SHIFT_SLOT_NOT_FOUND),
                    ep + " × " + actor);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. 越境した書き込みで DB が変わらない（AC-11）
    // ═════════════════════════════════════════════════════════════════════

    static Stream<Arguments> crossBorderWrites() {
        return Arrays.stream(Ep.values()).filter(Ep::write)
                .flatMap(ep -> Stream.of(Actor.ADMIN_B, Actor.OUTSIDER).map(a -> Arguments.of(ep, a)));
    }

    @ParameterizedTest(name = "{0} × {1}")
    @MethodSource("crossBorderWrites")
    @DisplayName("AC-11: 越境の書き込みは DB（スケジュール・枠・割当・希望）を 1 列も変えず、remind の Valkey ロックも取らない")
    void 越境の書き込みはDBを変えない(Ep ep, Actor actor) throws Exception {
        String before = snapshot();
        MvcResult result = perform(actor, ep, targetId(ep));
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isIn(403, 404);
        assertThat(snapshot()).as("越境の拒否後も DB 不変").isEqualTo(before);
        assertThat(valkey).as("部外者は Valkey のロックを取らない（殿の判断 1）").isEmpty();
    }

    @Test
    @DisplayName("同じチームの権限不足（一般メンバー）の書き込みも DB を変えない")
    void 権限不足の書き込みはDBを変えない() throws Exception {
        for (Ep ep : Ep.values()) {
            if (!ep.write()) {
                continue;
            }
            String before = snapshot();
            assertResponse(perform(Actor.MEMBER_A, ep, targetId(ep)), ep,
                    Expect.error(403, CommonErrorCode.COMMON_002), ep.name());
            assertThat(snapshot()).as(ep + " 拒否後も DB 不変").isEqualTo(before);
        }
        assertThat(valkey).as("権限不足の利用者も Valkey のロックを取らない").isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. remind の Valkey ロック（殿の判断 1）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("殿の判断 1: 部外者が remind を叩いた直後でも、管理者の remind は 429 にならず 200（ロックは認可の後）")
    void 部外者のremindは管理者の手動リマインドを塞がない() throws Exception {
        MvcResult outsider = perform(Actor.OUTSIDER, Ep.S8_REMIND, targetId(Ep.S8_REMIND));
        assertResponse(outsider, Ep.S8_REMIND, Expect.error(404, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND), "部外者");
        assertThat(valkey).as("部外者はロックを取らない").isEmpty();
        MvcResult admin = perform(Actor.ADMIN_A, Ep.S8_REMIND, targetId(Ep.S8_REMIND));
        assertResponse(admin, Ep.S8_REMIND, Expect.ok(200), "直後の管理者");
        assertThat(valkey).as("許可された管理者はロックを取る").hasSize(1);
    }

    @Test
    @DisplayName("remind の連打防止は維持（管理者の 2 回目は 429 SHIFT_036）")
    void 管理者の連打は429() throws Exception {
        assertResponse(perform(Actor.ADMIN_A, Ep.S8_REMIND, targetId(Ep.S8_REMIND)), Ep.S8_REMIND, Expect.ok(200),
                "1 回目");
        MvcResult second = perform(Actor.ADMIN_A, Ep.S8_REMIND, targetId(Ep.S8_REMIND));
        assertResponse(second, Ep.S8_REMIND, Expect.error(429, ShiftErrorCode.MANUAL_REMINDER_THROTTLED), "2 回目");
    }

    @Test
    @DisplayName("remind の状態判定は認可の後（管理者が公開済みに叩くと 409 SHIFT_012、部外者は 404）")
    void remindの状態判定は認可の後() throws Exception {
        assertResponse(perform(Actor.ADMIN_A, Ep.S8_REMIND, String.valueOf(scheduleAId)), Ep.S8_REMIND,
                Expect.error(409, ShiftErrorCode.INVALID_SCHEDULE_STATUS), "管理者・公開済み");
        valkey.clear();
        assertResponse(perform(Actor.OUTSIDER, Ep.S8_REMIND, String.valueOf(scheduleAId)), Ep.S8_REMIND,
                Expect.error(404, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND), "部外者・公開済み");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. 読取系の退行防止（殿の判断 4）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("殿の判断 4: user_roles のみの ADMIN は詳細・枠一覧で 403 にならず未公開も見える。一覧・PDF は是正前どおり 403")
    void userRolesのみのADMINは閲覧系で403にならない() throws Exception {
        em.createNativeQuery("UPDATE shift_schedules SET status = 'DRAFT', published_at = NULL WHERE id = :id")
                .setParameter("id", scheduleAId).executeUpdate();
        em.flush();
        em.clear();
        // 是正前に通していた閲覧（退行させない）。未公開でも管理者として見える（是正前の isPrivilegedViewer と同じ）。
        for (Ep ep : List.of(Ep.S2_GET, Ep.L1_LIST)) {
            assertResponse(perform(Actor.USER_ROLES_ONLY_ADMIN_A, ep, targetId(ep)), ep, Expect.ok(200),
                    ep + " user_roles のみの ADMIN・未公開");
        }
        // 是正前から在籍を要求していた閲覧（許可を広げない＝AC-16）。
        for (Ep ep : List.of(Ep.S1_LIST, Ep.P1_PDF_TEAM)) {
            assertResponse(perform(Actor.USER_ROLES_ONLY_ADMIN_A, ep, targetId(ep)), ep,
                    Expect.error(403, CommonErrorCode.COMMON_002), ep + " user_roles のみの ADMIN");
        }
    }

    @Test
    @DisplayName("一般メンバーは未公開（DRAFT）を 404 SHIFT_001 で見られない（可視性は認可と同じ順序で維持）")
    void 一般メンバーは未公開を見られない() throws Exception {
        em.createNativeQuery("UPDATE shift_schedules SET status = 'DRAFT', published_at = NULL WHERE id = :id")
                .setParameter("id", scheduleAId).executeUpdate();
        em.flush();
        em.clear();
        for (Ep ep : List.of(Ep.S2_GET, Ep.L1_LIST, Ep.P1_PDF_TEAM)) {
            assertResponse(perform(Actor.MEMBER_A, ep, targetId(ep)), ep,
                    Expect.error(404, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND), "一般メンバー・未公開");
            // SUPPORTER も未公開は 404（未公開の判定が SUPPORTER の 403 より先）。
            assertResponse(perform(Actor.SUPPORTER_A, ep, targetId(ep)), ep,
                    Expect.error(404, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND), "SUPPORTER・未公開");
        }
    }

    @Test
    @DisplayName("希望収集中（COLLECTING）の枠一覧は、一般メンバーには割当を伏せ、管理者には伏せない")
    void 希望収集中の割当は一般メンバーに伏せる() throws Exception {
        em.createNativeQuery("UPDATE shift_schedules SET status = 'COLLECTING' WHERE id = :id")
                .setParameter("id", scheduleAId).executeUpdate();
        em.createNativeQuery("UPDATE shift_slots SET assigned_user_ids = :ids WHERE id = :id")
                .setParameter("ids", "[" + id(Actor.MEMBER_A) + "]")
                .setParameter("id", slotAId).executeUpdate();
        em.flush();
        em.clear();
        MvcResult member = perform(Actor.MEMBER_A, Ep.L1_LIST, targetId(Ep.L1_LIST));
        assertThat(member.getResponse().getStatus()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(member.getResponse().getContentAsString(), "$.data[0].assignmentMasked"))
                .isTrue();
        for (Actor actor : List.of(Actor.ADMIN_A, Actor.USER_ROLES_ONLY_ADMIN_A, Actor.SYSTEM_ADMIN)) {
            MvcResult admin = perform(actor, Ep.L1_LIST, targetId(Ep.L1_LIST));
            assertThat(admin.getResponse().getStatus()).as(actor.name()).isEqualTo(200);
            List<Object> assigned = JsonPath.read(admin.getResponse().getContentAsString(),
                    "$.data[0].assignedUserIds");
            assertThat(assigned).as(actor + " には割当を伏せない").hasSize(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Long id(Actor actor) {
        return actorIds.get(actor);
    }

    private String targetId(Ep ep) {
        return String.valueOf(switch (ep.kind) {
            case TEAM -> teamAId;
            case SCHEDULE -> scheduleAId;
            case COLLECTING_SCHEDULE -> collectingScheduleId;
            case SLOT -> slotAId;
        });
    }

    private long missingId(Ep ep) {
        return switch (ep.kind) {
            case TEAM -> teamAId + 999_999L;
            case SCHEDULE, COLLECTING_SCHEDULE -> Math.max(scheduleAId, collectingScheduleId) + 999_999L;
            case SLOT -> slotAId + 999_999L;
        };
    }

    private MvcResult perform(Actor actor, Ep ep, String id) throws Exception {
        setAuth(id(actor));
        MvcResult result = mockMvc.perform(request(ep, id)).andReturn();
        em.clear();
        return result;
    }

    private MockHttpServletRequestBuilder request(Ep ep, String id) {
        return switch (ep) {
            case S1_LIST -> get(SCHEDULES).param("teamId", id);
            case S1_LIST_PERIOD -> get(SCHEDULES).param("teamId", id).param("from", "2026-01-01")
                    .param("to", "2026-12-31");
            case S2_GET -> get(SCHEDULES + "/" + id);
            case S3_CREATE -> json(post(SCHEDULES).param("teamId", id), createScheduleBody());
            case S4_UPDATE -> json(patch(SCHEDULES + "/" + id), Map.of("title", "W6A 更新後"));
            case S5_DELETE -> delete(SCHEDULES + "/" + id);
            case S6_TRANSITION -> post(SCHEDULES + "/" + id + "/transition").param("status", "PUBLISHED");
            case S7_SUMMARY -> get(SCHEDULES + "/" + id + "/summary");
            case S8_REMIND -> post(SCHEDULES + "/" + id + "/remind");
            case S9_DUPLICATE -> post(SCHEDULES + "/" + id + "/duplicate");
            case L1_LIST -> get(SCHEDULES + "/" + id + "/slots");
            case L2_CREATE -> json(post(SCHEDULES + "/" + id + "/slots"), slotBody());
            case L3_BULK -> json(post(SCHEDULES + "/" + id + "/slots/bulk"), Map.of("slots", List.of(slotBody())));
            case L4_UPDATE -> json(patch(SLOTS + "/" + id), Map.of("note", "W6A 更新"));
            case L5_ASSIGN -> json(patch(SLOTS + "/" + id + "/assignments"), assignmentBody());
            case L6_DELETE -> delete(SLOTS + "/" + id);
            case P1_PDF_TEAM -> get(SCHEDULES + "/" + id + "/pdf").param("layout", "team");
            case P2_PDF_PERSONAL -> get(SCHEDULES + "/" + id + "/pdf").param("layout", "personal");
        };
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Object body) {
        try {
            return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> createScheduleBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "W6A 新規");
        body.put("startDate", "2026-05-01");
        body.put("endDate", "2026-05-07");
        return body;
    }

    private Map<String, Object> slotBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slotDate", "2026-03-03");
        body.put("startTime", "09:00:00");
        body.put("endTime", "17:00:00");
        body.put("requiredCount", 1);
        return body;
    }

    private Map<String, Object> assignmentBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("addUserIds", List.of(id(Actor.MEMBER_A)));
        body.put("removeUserIds", List.of());
        body.put("slotVersion", 0);
        return body;
    }

    /**
     * status・error.code・error.message を表と照合する。PDF の許可は描画の成否が関心外なので「403/404 でない」。
     */
    private void assertResponse(MvcResult result, Ep ep, Expect expect, String label) throws Exception {
        String content = result.getResponse().getContentAsString();
        int status = result.getResponse().getStatus();
        if (expect.code() == null) {
            if (ep.pdf() || expect.status() < 0) {
                assertThat(status).as(label + " は認可・可視性で弾かれない: " + content).isNotIn(403, 404, 400);
            } else {
                assertThat(status).as(label + ": " + content).isEqualTo(expect.status());
            }
            return;
        }
        assertThat(status).as(label + ": " + content).isEqualTo(expect.status());
        assertThat((String) JsonPath.read(content, "$.error.code")).as(label).isEqualTo(expect.code().getCode());
        assertThat((String) JsonPath.read(content, "$.error.message")).as(label)
                .isEqualTo(expect.code().getMessage());
    }

    /** 応答本文から timestamp（リクエストごとに変わる）を除いて正規化する。 */
    @SuppressWarnings("unchecked")
    private String stripTimestamp(MvcResult result) throws Exception {
        Map<String, Object> parsed = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        parsed.remove("timestamp");
        if (parsed.get("error") instanceof Map<?, ?> error) {
            ((Map<String, Object>) error).remove("timestamp");
        }
        return objectMapper.writeValueAsString(parsed);
    }

    /**
     * チーム A・B のスケジュール（論理削除済みを含む）と、その配下の枠・割当・希望を全列で連結する。
     * 1 列（version・更新時刻・deleted_at）でも変われば差が出る。
     */
    private String snapshot() {
        em.flush();
        em.clear();
        Set<Long> teams = Set.of(teamAId, teamBId);
        return Stream.of(
                        "SELECT * FROM shift_schedules WHERE team_id IN (:t) ORDER BY id",
                        "SELECT * FROM shift_slots WHERE schedule_id IN "
                                + "(SELECT id FROM shift_schedules WHERE team_id IN (:t)) ORDER BY id",
                        "SELECT * FROM shift_assignments WHERE slot_id IN (SELECT id FROM shift_slots WHERE schedule_id IN "
                                + "(SELECT id FROM shift_schedules WHERE team_id IN (:t))) ORDER BY id",
                        "SELECT * FROM shift_requests WHERE schedule_id IN "
                                + "(SELECT id FROM shift_schedules WHERE team_id IN (:t)) ORDER BY id")
                .map(sql -> {
                    @SuppressWarnings("unchecked")
                    List<Object> rows = em.createNativeQuery(sql).setParameter("t", teams).getResultList();
                    return rows.stream()
                            .map(r -> r instanceof Object[] cols ? Arrays.deepToString(cols) : String.valueOf(r))
                            .collect(Collectors.joining(",", "[", "]"));
                })
                .collect(Collectors.joining("\n"));
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'W6A', 'テスト', 'W6A テスト', 'ACTIVE', "
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
