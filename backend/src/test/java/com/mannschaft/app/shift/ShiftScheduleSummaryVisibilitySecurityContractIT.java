package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.service.RoleClaimResolver;
import com.mannschaft.app.auth.service.StatusClaimResolver;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.service.ShiftScheduleService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.cache.CacheManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260905-1630: ShiftScheduleController#getScheduleSummary の未公開存在秘匿契約。
 *
 * <p>実JWT・実Securityフィルタ・実Facade/Service・Testcontainers MySQLを通す。
 * 共通基底の外部Redis mockだけを継承し、内部Beanや認可は置換しない。
 * 読取契約のためfixtureはテストTX内で準備し、終了時にrollbackする。
 * null状態はDBのNOT NULL制約外なのでFacade単体テストで固定する。</p>
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, addFilters = true)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260905-1630: 実Securityによる未公開シフトサマリ存在秘匿")
class ShiftScheduleSummaryVisibilitySecurityContractIT extends AbstractMySqlIntegrationTest {
    private static final String SCHEDULES = "/api/v1/shifts/schedules";
    private static final long ABSENT_ID = Long.MAX_VALUE;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthTokenService authTokenService;
    @Autowired private RoleClaimResolver roleClaimResolver;
    @Autowired private StatusClaimResolver statusClaimResolver;
    @Autowired private TeamRepository teamRepository;
    @Autowired private ShiftScheduleRepository scheduleRepository;
    @Autowired private ShiftScheduleService scheduleService;
    @Autowired private FeatureFlagRepository featureFlagRepository;
    @Autowired private CacheManager cacheManager;
    @PersistenceContext private EntityManager em;

    private final Map<Actor, Long> actors = new EnumMap<>(Actor.class);
    private final Map<Fixture, Long> schedules = new EnumMap<>(Fixture.class);
    private Long teamId;

    /** 許可主体と拒否主体を、所属とuser_rolesの両経路で区別する。 */
    enum Actor { ADMIN, DEPUTY, SYSTEM_ADMIN, USER_ROLES_ONLY_ADMIN, MEMBER, SUPPORTER, OTHER_ADMIN, OUTSIDER }

    /** 公開履歴なしPUBLISHEDも許可状態として扱う（publishedAtを必須化しない）。 */
    enum Fixture {
        DRAFT(ShiftScheduleStatus.DRAFT, false),
        ARCHIVED_UNPUBLISHED(ShiftScheduleStatus.ARCHIVED, false),
        COLLECTING(ShiftScheduleStatus.COLLECTING, false),
        ADJUSTING(ShiftScheduleStatus.ADJUSTING, false),
        PUBLISHED(ShiftScheduleStatus.PUBLISHED, true),
        PUBLISHED_WITHOUT_TIMESTAMP(ShiftScheduleStatus.PUBLISHED, false),
        ARCHIVED_PUBLISHED(ShiftScheduleStatus.ARCHIVED, true);

        final ShiftScheduleStatus status;
        final boolean published;

        Fixture(ShiftScheduleStatus status, boolean published) {
            this.status = status;
            this.published = published;
        }
    }

    @BeforeEach
    void setUp() {
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_SHIFT_ENABLED");
        teamId = saveTeam();
        Long otherTeamId = saveTeam();
        for (Actor actor : Actor.values()) {
            long id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
            MembershipTestHelper.insertActiveUser(em, id);
            actors.put(actor, id);
        }
        for (Actor actor : new Actor[]{Actor.ADMIN, Actor.DEPUTY, Actor.MEMBER}) {
            MembershipTestHelper.insertMembership(em, actors.get(actor), ScopeType.TEAM, teamId, RoleKind.MEMBER);
        }
        MembershipTestHelper.insertMembership(em, actors.get(Actor.SUPPORTER), ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
        MembershipTestHelper.insertMembership(em, actors.get(Actor.OTHER_ADMIN), ScopeType.TEAM, otherTeamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, actors.get(Actor.ADMIN), "ADMIN", teamId, null);
        MembershipTestHelper.insertUserRole(em, actors.get(Actor.DEPUTY), "DEPUTY_ADMIN", teamId, null);
        MembershipTestHelper.insertUserRole(em, actors.get(Actor.USER_ROLES_ONLY_ADMIN), "ADMIN", teamId, null);
        MembershipTestHelper.insertUserRole(em, actors.get(Actor.OTHER_ADMIN), "ADMIN", otherTeamId, null);
        MembershipTestHelper.insertUserRole(em, actors.get(Actor.SYSTEM_ADMIN), "SYSTEM_ADMIN", null, null);
        for (Fixture fixture : Fixture.values()) {
            ShiftScheduleEntity schedule = scheduleRepository.save(ShiftScheduleEntity.builder()
                    .teamId(teamId).title("サマリ存在秘匿 " + fixture)
                    .startDate(LocalDate.of(2030, 1, 1)).endDate(LocalDate.of(2030, 1, 7))
                    .status(fixture.status)
                    .publishedAt(fixture.published ? LocalDateTime.of(2030, 1, 1, 9, 0) : null)
                    .createdBy(actors.get(Actor.ADMIN)).build());
            schedules.put(fixture, schedule.getId());
        }
        em.flush();
        em.clear();
        assertThat(scheduleRepository.findById(ABSENT_ID)).isEmpty();
    }

    @AfterTransaction
    void 機能フラグのキャッシュをrollback後に戻す() {
        FeatureFlagTestSupport.clearFlagCaches(cacheManager);
    }

    @ParameterizedTest
    @EnumSource(value = Fixture.class, names = {"DRAFT", "ARCHIVED_UNPUBLISHED"})
    void MEMBERの未公開サマリは不在と同じ404と本文を返す(Fixture fixture) throws Exception {
        MvcResult actual = summary(Actor.MEMBER, schedules.get(fixture));
        assertError(actual, 404, "SHIFT_001");
        MvcResult absent = summary(Actor.MEMBER, ABSENT_ID);
        assertError(absent, 404, "SHIFT_001");
        assertThat(error(actual)).isEqualTo(error(absent));
    }

    @ParameterizedTest
    @EnumSource(value = Fixture.class, names = {"COLLECTING", "ADJUSTING", "PUBLISHED", "PUBLISHED_WITHOUT_TIMESTAMP", "ARCHIVED_PUBLISHED"})
    void MEMBERの閲覧可能状態でもサマリは従来どおり403(Fixture fixture) throws Exception {
        assertError(summary(Actor.MEMBER, schedules.get(fixture)), 403, "COMMON_002");
    }

    @ParameterizedTest
    @EnumSource(Fixture.class)
    void SUPPORTERはサマリも詳細も全状態403を維持する(Fixture fixture) throws Exception {
        assertError(summary(Actor.SUPPORTER, schedules.get(fixture)), 403, "COMMON_002");
        MvcResult detail = mockMvc.perform(get(SCHEDULES + "/{id}", schedules.get(fixture))
                .header("Authorization", bearer(Actor.SUPPORTER))).andReturn();
        assertError(detail, 403, "COMMON_002");
    }

    static Stream<Arguments> crossBorderCases() {
        return Stream.of(Actor.OTHER_ADMIN, Actor.OUTSIDER)
                .flatMap(actor -> Stream.of(Fixture.values()).map(fixture -> Arguments.of(actor, fixture)));
    }

    @ParameterizedTest
    @MethodSource("crossBorderCases")
    void 越境と無所属は全状態で不在と同じ404(Actor actor, Fixture fixture) throws Exception {
        MvcResult actual = summary(actor, schedules.get(fixture));
        assertError(actual, 404, "SHIFT_001");
        MvcResult absent = summary(actor, ABSENT_ID);
        assertError(absent, 404, "SHIFT_001");
        assertThat(error(actual)).isEqualTo(error(absent));
    }

    static Stream<Arguments> privilegedCases() {
        return Stream.of(Actor.ADMIN, Actor.DEPUTY, Actor.SYSTEM_ADMIN, Actor.USER_ROLES_ONLY_ADMIN)
                .flatMap(actor -> Stream.of(Fixture.values()).map(fixture -> Arguments.of(actor, fixture)));
    }

    @ParameterizedTest
    @MethodSource("privilegedCases")
    void 管理者は全状態で空枠の空サマリを取得できる(Actor actor, Fixture fixture) throws Exception {
        mockMvc.perform(get(SCHEDULES + "/{id}/summary", schedules.get(fixture))
                        .header("Authorization", bearer(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleId").value(schedules.get(fixture)))
                .andExpect(jsonPath("$.data.summaryByDate").isEmpty());
    }

    @Test
    void 未認証ではサマリを取得できない() throws Exception {
        mockMvc.perform(get(SCHEDULES + "/{id}/summary", schedules.get(Fixture.DRAFT)))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(Actor.class)
    void 対象スケジュール削除済みなら管理者を含め不在と同じ404(Actor actor) throws Exception {
        Long id = schedules.get(Fixture.DRAFT);
        scheduleRepository.findById(id).orElseThrow().softDelete();
        em.flush();
        em.clear();
        MvcResult actual = summary(actor, id);
        assertError(actual, 404, "SHIFT_001");
        MvcResult absent = summary(actor, ABSENT_ID);
        assertError(absent, 404, "SHIFT_001");
        assertThat(error(actual)).isEqualTo(error(absent));
    }

    @Test
    void scope取得後に削除されても実集計本体の再取得で404になる() {
        Long id = schedules.get(Fixture.DRAFT);
        assertThat(scheduleService.resolveScope(id).scheduleId()).isEqualTo(id);
        scheduleRepository.findById(id).orElseThrow().softDelete();
        em.flush();
        em.clear();

        Throwable thrown = catchThrowable(() -> scheduleService.getScheduleSummary(id));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        assertThat(scheduleRepository.findById(id)).isEmpty();
    }

    private Long saveTeam() {
        return teamRepository.saveAndFlush(TeamEntity.builder()
                .name("サマリ契約チーム").slug("summary-" + UUID.randomUUID().toString().substring(0, 20))
                .template("sports").visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(true).build()).getId();
    }

    private String bearer(Actor actor) {
        Long id = actors.get(actor);
        return "Bearer " + authTokenService.issueAccessToken(id, roleClaimResolver.resolveRoles(id),
                statusClaimResolver.isPendingParentalConsent(id));
    }

    private MvcResult summary(Actor actor, Long id) throws Exception {
        return mockMvc.perform(get(SCHEDULES + "/{id}/summary", id)
                .header("Authorization", bearer(actor))).andReturn();
    }

    private JsonNode error(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("error");
    }

    private void assertError(MvcResult result, int expectedStatus, String expectedCode) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        assertThat(error(result).get("code").asText()).isEqualTo(expectedCode);
        assertThat(error(result).get("message").asText()).isNotBlank();
    }
}
