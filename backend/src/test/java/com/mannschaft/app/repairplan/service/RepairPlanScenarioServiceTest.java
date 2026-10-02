package com.mannschaft.app.repairplan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.repairplan.RepairPlanErrorCode;
import com.mannschaft.app.repairplan.dto.PublishAsAnnouncementRequest;
import com.mannschaft.app.repairplan.dto.SaveScenarioRequest;
import com.mannschaft.app.repairplan.dto.ScenarioDto;
import com.mannschaft.app.repairplan.dto.SimulateRepairPlanRequest;
import com.mannschaft.app.repairplan.dto.SimulateRepairPlanResponse;
import com.mannschaft.app.repairplan.engine.GenerationMeter;
import com.mannschaft.app.repairplan.engine.GenerationSeverity;
import com.mannschaft.app.repairplan.engine.RepairPlanSimulationEngine;
import com.mannschaft.app.repairplan.engine.SimulationParams;
import com.mannschaft.app.repairplan.engine.SimulationResult;
import com.mannschaft.app.repairplan.engine.YearlyBalance;
import com.mannschaft.app.repairplan.entity.RepairSimulationScenario;
import com.mannschaft.app.repairplan.entity.RepairSimulationScenarioVersion;
import com.mannschaft.app.repairplan.repository.RepairFundBalanceRepository;
import com.mannschaft.app.repairplan.repository.RepairPlanItemRepository;
import com.mannschaft.app.repairplan.repository.RepairSimulationScenarioRepository;
import com.mannschaft.app.repairplan.repository.RepairSimulationScenarioVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link RepairPlanScenarioService} 単体テスト（F08.8 Phase 2）。
 *
 * <p>シミュレーション・シナリオ保存・上限超過・IDOR・議案変換・ロック済み検出の 8 ケースを網羅。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RepairPlanScenarioService 単体テスト")
class RepairPlanScenarioServiceTest {

    @Mock
    private RepairPlanSimulationEngine engine;
    @Mock
    private RepairPlanItemRepository itemRepository;
    @Mock
    private RepairFundBalanceRepository fundBalanceRepository;
    @Mock
    private RepairSimulationScenarioRepository scenarioRepository;
    @Mock
    private RepairSimulationScenarioVersionRepository versionRepository;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @InjectMocks
    private RepairPlanScenarioService service;

    private static final Long USER_ID = 100L;
    private static final Long SCOPE_ID = 200L;
    private static final String SCOPE_TYPE = "TEAM";
    private static final Long ORG_ID = 300L;

    private SimulateRepairPlanRequest baseRequest;
    private SimulationResult baseResult;

    @BeforeEach
    void setUp() {
        // SecurityContext にユーザーを設定（SecurityUtils.getCurrentUserId() 用）
        var auth = new UsernamePasswordAuthenticationToken(
                USER_ID.toString(), null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);

        baseRequest = new SimulateRepairPlanRequest(
                new BigDecimal("15000"),
                50,
                new BigDecimal("0.01"),
                new BigDecimal("0.015"),
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                new BigDecimal("1200000"),
                30,
                LocalDateTime.of(2026, 1, 1, 0, 0)
        );

        YearlyBalance yb = new YearlyBalance(2026,
                new BigDecimal("10000000"),
                new BigDecimal("9000000"),
                new BigDecimal("8000000"),
                BigDecimal.ZERO);

        baseResult = new SimulationResult(
                "v1.0.0",
                "abc123def456abc123def456abc123def456abc123def456abc123def456abc1",
                List.of(yb),
                null,
                Map.of("20s", new GenerationMeter(25, 99, GenerationSeverity.SAFE)),
                List.of()
        );

        // redisTemplate.opsForValue() のデフォルトモック設定
        given(redisTemplate.opsForValue()).willReturn(valueOps);
    }

    // =========================================================================
    // simulate
    // =========================================================================

    @Nested
    @DisplayName("simulate")
    class SimulateTests {

        @Test
        @DisplayName("正常系: 初期残高取得 + 修繕費集計 + エンジン呼び出しが行われる")
        void simulate_正常() {
            // given
            given(fundBalanceRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty()); // 残高なし → 0 扱い
            given(itemRepository.sumEstimatedAmountByYear(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Collections.emptyList());
            given(valueOps.get(anyString())).willReturn(null); // キャッシュなし
            given(engine.simulate(any(SimulationParams.class), eq(2026)))
                    .willReturn(baseResult);

            // when
            SimulateRepairPlanResponse response = service.simulate(SCOPE_TYPE, SCOPE_ID, ORG_ID, baseRequest);

            // then
            assertThat(response.engineVersion()).isEqualTo("v1.0.0");
            assertThat(response.depletionYear()).isNull();
            verify(engine, times(1)).simulate(any(), eq(2026));
        }

        @Test
        @DisplayName("正常系: Valkey キャッシュヒット時はエンジンを呼び出さない")
        void simulate_キャッシュヒット() throws Exception {
            // given: キャッシュにシミュレーション結果が入っている
            SimulateRepairPlanResponse cachedResponse = new SimulateRepairPlanResponse(
                    "v1.0.0", "sha256cached",
                    List.of(), null, Map.of(), List.of());
            String cachedJson = objectMapper.writeValueAsString(cachedResponse);

            given(valueOps.get(anyString())).willReturn(cachedJson);

            // when
            SimulateRepairPlanResponse response = service.simulate(SCOPE_TYPE, SCOPE_ID, ORG_ID, baseRequest);

            // then: エンジンの simulate は呼ばれない
            assertThat(response.contentSha256()).isEqualTo("sha256cached");
            verify(engine, never()).simulate(any(), anyInt());
        }
    }

    // =========================================================================
    // saveScenario
    // =========================================================================

    @Nested
    @DisplayName("saveScenario")
    class SaveScenarioTests {

        @Test
        @DisplayName("正常系: シナリオ保存と自動採番名が正しく動作する")
        void saveScenario_正常_自動採番() {
            // given
            given(fundBalanceRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());
            given(itemRepository.sumEstimatedAmountByYear(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Collections.emptyList());
            given(engine.simulate(any(), anyInt())).willReturn(baseResult);
            given(scenarioRepository.countByScopeTypeAndScopeIdAndDeletedAtIsNull(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(3L);
            given(scenarioRepository.findByScopeTypeAndScopeIdAndContentSha256AndDeletedAtIsNull(
                    anyString(), anyLong(), anyString())).willReturn(Optional.empty());
            given(scenarioRepository.save(any(RepairSimulationScenario.class)))
                    .willAnswer(inv -> {
                        RepairSimulationScenario s = inv.getArgument(0);
                        try {
                            var f = s.getClass().getSuperclass().getDeclaredField("id");
                            f.setAccessible(true);
                            f.set(s, UUID.randomUUID());
                        } catch (ReflectiveOperationException ex) {
                            throw new RuntimeException(ex);
                        }
                        s.setVersion(0L);
                        return s;
                    });

            SaveScenarioRequest req = new SaveScenarioRequest(null, "テスト用シナリオ", baseRequest);

            // when
            ScenarioDto dto = service.saveScenario(SCOPE_TYPE, SCOPE_ID, ORG_ID, req, USER_ID);

            // then: 自動採番（count+1=4 → "シナリオ#4"）
            assertThat(dto.name()).isEqualTo("シナリオ#4");
            verify(scenarioRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("異常系: スコープあたり 50 件上限超過で SCENARIO_LIMIT_EXCEEDED")
        void saveScenario_上限超過() {
            // given
            given(fundBalanceRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());
            given(itemRepository.sumEstimatedAmountByYear(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Collections.emptyList());
            given(engine.simulate(any(), anyInt())).willReturn(baseResult);
            given(scenarioRepository.countByScopeTypeAndScopeIdAndDeletedAtIsNull(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(50L); // 上限到達

            SaveScenarioRequest req = new SaveScenarioRequest("名前あり", null, baseRequest);

            // when / then
            assertThatThrownBy(() -> service.saveScenario(SCOPE_TYPE, SCOPE_ID, ORG_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RepairPlanErrorCode.SCENARIO_LIMIT_EXCEEDED);

            verify(scenarioRepository, never()).save(any());
        }
    }

    // =========================================================================
    // listScenarios
    // =========================================================================

    @Nested
    @DisplayName("listScenarios")
    class ListScenariosTests {

        @Test
        @DisplayName("正常系: スコープのシナリオ一覧を返す")
        void listScenarios_正常() {
            // given: computed_summary_json に最小限の SimulateRepairPlanResponse JSON を持つシナリオ
            String summaryJson = "{\"engineVersion\":\"v1.0.0\",\"contentSha256\":\"abc\"," +
                    "\"yearlyBalances\":[],\"depletionYear\":null," +
                    "\"generationMeters\":{},\"warnings\":[]}";
            RepairSimulationScenario s = buildScenario(ORG_ID, SCOPE_TYPE, SCOPE_ID, summaryJson);

            given(scenarioRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                    SCOPE_TYPE, SCOPE_ID)).willReturn(List.of(s));

            // when
            List<ScenarioDto> list = service.listScenarios(SCOPE_TYPE, SCOPE_ID, ORG_ID);

            // then
            assertThat(list).hasSize(1);
        }
    }

    // =========================================================================
    // getScenario (IDOR 防止)
    // =========================================================================

    @Nested
    @DisplayName("getScenario")
    class GetScenarioTests {

        @Test
        @DisplayName("異常系: 他テナントの organizationId では NotFound（IDOR 防止）")
        void getScenario_他テナント_NotFound() {
            // given: scenarioId は存在するが org が異なる
            UUID scenarioId = UUID.randomUUID();
            Long otherOrgId = 999L;
            given(scenarioRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(scenarioId, otherOrgId))
                    .willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> service.getScenario(scenarioId, otherOrgId, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RepairPlanErrorCode.ITEM_NOT_FOUND);
        }

        @Test
        @DisplayName("正常系: 会員は取得できる（checkMembership通過）")
        void getScenario_会員は取得できる() {
            String summaryJson = "{\"engineVersion\":\"v1.0.0\",\"contentSha256\":\"abc\"," +
                    "\"yearlyBalances\":[],\"depletionYear\":null," +
                    "\"generationMeters\":{},\"warnings\":[]}";
            RepairSimulationScenario s = buildScenario(ORG_ID, SCOPE_TYPE, SCOPE_ID, summaryJson);
            given(scenarioRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(s.getId(), ORG_ID))
                    .willReturn(Optional.of(s));

            ScenarioDto dto = service.getScenario(s.getId(), ORG_ID, USER_ID);

            assertThat(dto).isNotNull();
        }
    }

    // =========================================================================
    // publishAsAnnouncement
    // =========================================================================

    @Nested
    @DisplayName("publishAsAnnouncement")
    class PublishAsAnnouncementTests {

        @Test
        @DisplayName("正常系: version_no が採番され locked_at がセットされる")
        void publishAsAnnouncement_正常() {
            // given
            UUID scenarioId = UUID.randomUUID();
            String summaryJson = "{\"engineVersion\":\"v1.0.0\",\"contentSha256\":\"abc\"," +
                    "\"yearlyBalances\":[],\"depletionYear\":null,\"generationMeters\":{},\"warnings\":[]}";
            RepairSimulationScenario scenario = buildScenario(ORG_ID, SCOPE_TYPE, SCOPE_ID, summaryJson);
            scenario.setVersion(1L);

            given(scenarioRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(scenarioId, ORG_ID))
                    .willReturn(Optional.of(scenario));
            given(versionRepository.findFirstByScenarioIdOrderByVersionNoDesc(scenarioId))
                    .willReturn(Optional.empty()); // バージョン 1 から開始
            given(versionRepository.save(any(RepairSimulationScenarioVersion.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(scenarioRepository.save(any(RepairSimulationScenario.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            PublishAsAnnouncementRequest req = new PublishAsAnnouncementRequest(
                    "修繕積立金枯渇対策の議案", "第5号議案", 1L);

            // when
            var response = service.publishAsAnnouncement(scenarioId, ORG_ID, req, USER_ID);

            // then
            assertThat(response.versionNo()).isEqualTo(1);
            assertThat(response.lockedAt()).isNotNull();
            assertThat(scenario.getLockedAt()).isNotNull();

            ArgumentCaptor<RepairSimulationScenarioVersion> versionCaptor =
                    ArgumentCaptor.forClass(RepairSimulationScenarioVersion.class);
            verify(versionRepository, times(1)).save(versionCaptor.capture());
            assertThat(versionCaptor.getValue().getVersionNo()).isEqualTo(1);
            assertThat(versionCaptor.getValue().getProposedResolutionNo()).isEqualTo("第5号議案");
        }

        @Test
        @DisplayName("異常系: locked_at 設定済みのシナリオは SCENARIO_ALREADY_LOCKED")
        void publishAsAnnouncement_既にロック済み() {
            // given
            UUID scenarioId = UUID.randomUUID();
            String summaryJson = "{\"engineVersion\":\"v1.0.0\",\"contentSha256\":\"abc\"," +
                    "\"yearlyBalances\":[],\"depletionYear\":null,\"generationMeters\":{},\"warnings\":[]}";
            RepairSimulationScenario scenario = buildScenario(ORG_ID, SCOPE_TYPE, SCOPE_ID, summaryJson);
            scenario.setLockedAt(LocalDateTime.now().minusDays(1)); // 既にロック済み
            scenario.setVersion(1L);

            given(scenarioRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(scenarioId, ORG_ID))
                    .willReturn(Optional.of(scenario));

            PublishAsAnnouncementRequest req = new PublishAsAnnouncementRequest(
                    "再ロック試行", null, 1L);

            // when / then
            assertThatThrownBy(() -> service.publishAsAnnouncement(scenarioId, ORG_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RepairPlanErrorCode.SCENARIO_ALREADY_LOCKED);

            verify(versionRepository, never()).save(any());
        }
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    private RepairSimulationScenario buildScenario(Long orgId, String scopeType, Long scopeId,
                                                     String computedSummaryJson) {
        RepairSimulationScenario s = RepairSimulationScenario.builder()
                .organizationId(orgId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name("テストシナリオ")
                .description(null)
                .paramsJson("{}")
                .computedSummaryJson(computedSummaryJson)
                .engineVersion("v1.0.0")
                .contentSha256("abc123")
                .baselineAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .createdBy(USER_ID)
                .build();
        try {
            var f = s.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(s, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        s.setVersion(0L);
        return s;
    }
}
