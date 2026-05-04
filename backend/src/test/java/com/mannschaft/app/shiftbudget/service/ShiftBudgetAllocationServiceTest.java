package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.AllocationCreateRequest;
import com.mannschaft.app.shiftbudget.dto.AllocationResponse;
import com.mannschaft.app.shiftbudget.dto.AllocationUpdateRequest;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import com.mannschaft.app.todo.repository.ProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftBudgetAllocationService} 単体テスト（Phase 9-β）。
 *
 * <p>設計書 F08.7 §5.2 / §6.2.1 / §9.2 / §9.5 / §11 のテストケースをカバー:</p>
 * <ul>
 *   <li>CRUD #1〜#5</li>
 *   <li>findLiveByScope による重複防御 (ALLOCATION_ALREADY_EXISTS / 409)</li>
 *   <li>HAS_CONSUMPTIONS 制約 (PLANNED / CONFIRMED で 409 区別)</li>
 *   <li>楽観ロック (OPTIMISTIC_LOCK_CONFLICT / 409)</li>
 *   <li>多テナント分離 (別組織 → ALLOCATION_NOT_FOUND / 404)</li>
 *   <li>バリデーション (INVALID_PERIOD / INVALID_ALLOCATED_AMOUNT / 400)</li>
 *   <li>権限 (BUDGET_VIEW / BUDGET_MANAGE)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftBudgetAllocationService 単体テスト")
class ShiftBudgetAllocationServiceTest {

    private static final Long USER_ID = 100L;
    private static final Long ORG_ID = 1L;
    private static final Long TEAM_ID = 12L;
    private static final Long ALLOCATION_ID = 42L;

    @Mock
    private ShiftBudgetAllocationRepository allocationRepository;
    @Mock
    private ShiftBudgetConsumptionRepository consumptionRepository;
    @Mock
    private ShiftBudgetRateQueryRepository rateQueryRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ShiftBudgetFeatureService featureService;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ShiftBudgetAllocationService service;

    @BeforeEach
    void setUpSecurity() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private void givenBudgetManageAllowed() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        // checkPermission は void。何もしない = 権限あり
    }

    private void givenBudgetViewAllowed() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
    }

    private ShiftBudgetAllocationEntity sampleEntity() {
        return ShiftBudgetAllocationEntity.builder()
                .organizationId(ORG_ID)
                .teamId(TEAM_ID)
                .fiscalYearId(3L)
                .budgetCategoryId(17L)
                .periodStart(LocalDate.of(2026, 6, 1))
                .periodEnd(LocalDate.of(2026, 6, 30))
                .allocatedAmount(new BigDecimal("300000"))
                .consumedAmount(BigDecimal.ZERO)
                .confirmedAmount(BigDecimal.ZERO)
                .currency("JPY")
                .createdBy(USER_ID)
                .version(0L)
                .build();
    }

    private AllocationCreateRequest sampleCreateRequest() {
        return new AllocationCreateRequest(
                TEAM_ID, null, 3L, 17L,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                new BigDecimal("300000"), "JPY", "6 月分人件費");
    }

    @Nested
    @DisplayName("createAllocation")
    class CreateAllocation {

        @Test
        @DisplayName("正常系: 重複なし → INSERT + 監査ログ")
        void 正常系_作成成功() {
            givenBudgetManageAllowed();
            given(rateQueryRepository.countTeamInOrganization(TEAM_ID, ORG_ID)).willReturn(1L);
            given(allocationRepository.findLiveByScope(eq(ORG_ID), eq(TEAM_ID), eq(null),
                    eq(17L), any(LocalDate.class), any(LocalDate.class)))
                    .willReturn(Optional.empty());
            ShiftBudgetAllocationEntity saved = sampleEntity();
            // id は通常 save 時に DB が採番するが、テストではビルダ後そのまま返却
            given(allocationRepository.save(any(ShiftBudgetAllocationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            AllocationResponse response = service.createAllocation(ORG_ID, sampleCreateRequest());

            assertThat(response.organizationId()).isEqualTo(ORG_ID);
            assertThat(response.teamId()).isEqualTo(TEAM_ID);
            assertThat(response.allocatedAmount()).isEqualByComparingTo("300000");
            assertThat(response.currency()).isEqualTo("JPY");
            verify(allocationRepository).save(any(ShiftBudgetAllocationEntity.class));
            verify(auditLogService).record(eq("SHIFT_BUDGET_ALLOCATION_CREATED"),
                    eq(USER_ID), eq(null), eq(TEAM_ID), eq(ORG_ID),
                    any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("重複あり → ALLOCATION_ALREADY_EXISTS (409)")
        void 重複_409() {
            givenBudgetManageAllowed();
            given(rateQueryRepository.countTeamInOrganization(TEAM_ID, ORG_ID)).willReturn(1L);
            given(allocationRepository.findLiveByScope(any(), any(), any(), any(), any(), any()))
                    .willReturn(Optional.of(sampleEntity()));

            assertThatThrownBy(() -> service.createAllocation(ORG_ID, sampleCreateRequest()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.ALLOCATION_ALREADY_EXISTS);

            verify(allocationRepository, never()).save(any());
        }

        @Test
        @DisplayName("period_start > period_end → INVALID_PERIOD (400)")
        void 期間不正_400() {
            givenBudgetManageAllowed();
            AllocationCreateRequest bad = new AllocationCreateRequest(
                    TEAM_ID, null, 3L, 17L,
                    LocalDate.of(2026, 7, 1), LocalDate.of(2026, 6, 30),
                    new BigDecimal("100"), "JPY", null);

            assertThatThrownBy(() -> service.createAllocation(ORG_ID, bad))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.INVALID_PERIOD);
        }

        @Test
        @DisplayName("allocated_amount < 0 → INVALID_ALLOCATED_AMOUNT (400)")
        void 金額負数_400() {
            givenBudgetManageAllowed();
            AllocationCreateRequest bad = new AllocationCreateRequest(
                    TEAM_ID, null, 3L, 17L,
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                    new BigDecimal("-1"), "JPY", null);

            assertThatThrownBy(() -> service.createAllocation(ORG_ID, bad))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.INVALID_ALLOCATED_AMOUNT);
        }

        @Test
        @DisplayName("team_id 別組織 → ALLOCATION_NOT_FOUND (404 IDOR 対策)")
        void 別組織のteam_404() {
            givenBudgetManageAllowed();
            given(rateQueryRepository.countTeamInOrganization(TEAM_ID, ORG_ID)).willReturn(0L);

            assertThatThrownBy(() -> service.createAllocation(ORG_ID, sampleCreateRequest()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND);
        }

        @Test
        @DisplayName("BUDGET_MANAGE 権限なし → BUDGET_MANAGE_REQUIRED (403)")
        void 権限なし_403() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.createAllocation(ORG_ID, sampleCreateRequest()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.BUDGET_MANAGE_REQUIRED);
        }
    }

    @Nested
    @DisplayName("updateAllocation")
    class UpdateAllocation {

        @Test
        @DisplayName("正常系: version 一致 → UPDATE")
        void 正常系_更新成功() {
            givenBudgetManageAllowed();
            ShiftBudgetAllocationEntity entity = sampleEntity();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(entity));
            given(allocationRepository.saveAndFlush(any())).willAnswer(inv -> inv.getArgument(0));

            AllocationUpdateRequest req = new AllocationUpdateRequest(
                    new BigDecimal("400000"), "増額", 0L);
            AllocationResponse res = service.updateAllocation(ORG_ID, ALLOCATION_ID, req);

            assertThat(res.allocatedAmount()).isEqualByComparingTo("400000");
            assertThat(res.note()).isEqualTo("増額");
            verify(auditLogService).record(eq("SHIFT_BUDGET_ALLOCATION_UPDATED"),
                    eq(USER_ID), eq(null), eq(TEAM_ID), eq(ORG_ID),
                    any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("version 不一致 → OPTIMISTIC_LOCK_CONFLICT (409)")
        void 楽観ロック_409() {
            givenBudgetManageAllowed();
            ShiftBudgetAllocationEntity entity = sampleEntity();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(entity));

            AllocationUpdateRequest stale = new AllocationUpdateRequest(
                    new BigDecimal("100"), null, 99L);  // 不一致 version

            assertThatThrownBy(() -> service.updateAllocation(ORG_ID, ALLOCATION_ID, stale))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        }

        @Test
        @DisplayName("save 時に Spring の OptimisticLockingFailureException → 409 にラップ")
        void 保存時楽観ロック_409() {
            givenBudgetManageAllowed();
            ShiftBudgetAllocationEntity entity = sampleEntity();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(entity));
            given(allocationRepository.saveAndFlush(any()))
                    .willThrow(new OptimisticLockingFailureException("conflict"));

            AllocationUpdateRequest req = new AllocationUpdateRequest(
                    new BigDecimal("100"), null, 0L);

            assertThatThrownBy(() -> service.updateAllocation(ORG_ID, ALLOCATION_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        }

        @Test
        @DisplayName("別組織の allocation → ALLOCATION_NOT_FOUND (404)")
        void 別組織_404() {
            givenBudgetManageAllowed();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.empty());

            AllocationUpdateRequest req = new AllocationUpdateRequest(
                    new BigDecimal("100"), null, 0L);
            assertThatThrownBy(() -> service.updateAllocation(ORG_ID, ALLOCATION_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("deleteAllocation")
    class DeleteAllocation {

        @Test
        @DisplayName("正常系: 消化なし → 論理削除 + 監査ログ")
        void 正常系_削除成功() {
            givenBudgetManageAllowed();
            ShiftBudgetAllocationEntity entity = sampleEntity();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(entity));
            given(consumptionRepository.existsByAllocationIdAndStatusInAndDeletedAtIsNull(
                    eq(ALLOCATION_ID), eq(List.of(ShiftBudgetConsumptionStatus.PLANNED))))
                    .willReturn(false);
            given(consumptionRepository.existsByAllocationIdAndStatusInAndDeletedAtIsNull(
                    eq(ALLOCATION_ID), eq(List.of(ShiftBudgetConsumptionStatus.CONFIRMED))))
                    .willReturn(false);

            service.deleteAllocation(ORG_ID, ALLOCATION_ID);

            assertThat(entity.getDeletedAt()).isNotNull();
            verify(allocationRepository).save(entity);
            verify(auditLogService).record(eq("SHIFT_BUDGET_ALLOCATION_DELETED"),
                    eq(USER_ID), eq(null), eq(TEAM_ID), eq(ORG_ID),
                    any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("PLANNED 残存 → HAS_CONSUMPTIONS_PLANNED (409)")
        void planned残存_409() {
            givenBudgetManageAllowed();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(sampleEntity()));
            given(consumptionRepository.existsByAllocationIdAndStatusInAndDeletedAtIsNull(
                    eq(ALLOCATION_ID), eq(List.of(ShiftBudgetConsumptionStatus.PLANNED))))
                    .willReturn(true);

            assertThatThrownBy(() -> service.deleteAllocation(ORG_ID, ALLOCATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.HAS_CONSUMPTIONS_PLANNED);
        }

        @Test
        @DisplayName("CONFIRMED 残存 → HAS_CONSUMPTIONS_CONFIRMED (409)")
        void confirmed残存_409() {
            givenBudgetManageAllowed();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(sampleEntity()));
            given(consumptionRepository.existsByAllocationIdAndStatusInAndDeletedAtIsNull(
                    eq(ALLOCATION_ID), eq(List.of(ShiftBudgetConsumptionStatus.PLANNED))))
                    .willReturn(false);
            given(consumptionRepository.existsByAllocationIdAndStatusInAndDeletedAtIsNull(
                    eq(ALLOCATION_ID), eq(List.of(ShiftBudgetConsumptionStatus.CONFIRMED))))
                    .willReturn(true);

            assertThatThrownBy(() -> service.deleteAllocation(ORG_ID, ALLOCATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.HAS_CONSUMPTIONS_CONFIRMED);
        }
    }

    @Nested
    @DisplayName("getAllocation / listAllocations (BUDGET_VIEW)")
    class ReadOperations {

        @Test
        @DisplayName("詳細: 別組織 → ALLOCATION_NOT_FOUND (404)")
        void 別組織詳細_404() {
            givenBudgetViewAllowed();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAllocation(ORG_ID, ALLOCATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND);
        }

        @Test
        @DisplayName("BUDGET_VIEW 権限なし → BUDGET_VIEW_REQUIRED (403)")
        void view権限なし_403() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.listAllocations(ORG_ID, 0, 20))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.BUDGET_VIEW_REQUIRED);
        }

        @Test
        @DisplayName("正常系: 一覧をページングで返す")
        void 一覧_正常() {
            givenBudgetViewAllowed();
            given(allocationRepository
                    .findByOrganizationIdAndDeletedAtIsNullOrderByPeriodStartDesc(eq(ORG_ID), any()))
                    .willReturn(List.of(sampleEntity()));

            var res = service.listAllocations(ORG_ID, 0, 20);

            assertThat(res.items()).hasSize(1);
            assertThat(res.page()).isZero();
            assertThat(res.size()).isEqualTo(20);
        }
    }

    /**
     * Phase 9-γ で追加された代替テスト群。
     *
     * <p>マスター御裁可 Q3 により、Repository UNIQUE による NULL 含有制約は機能しないため、
     * 防衛線は本 Service の {@code findLiveByScope} SELECT FOR UPDATE 重複チェックである。
     * 9-β リポジトリテストで {@code @Disabled} 化された 2 件の代替として、
     * 本 ServiceTest が真の防衛線が機能することを検証する。</p>
     */
    @Nested
    @DisplayName("Phase 9-γ 代替テスト: findLiveByScope による重複防御")
    class Phase9GammaAlternativeTests {

        /**
         * 同一スコープで連続して create を呼び出した場合、2 回目で
         * {@code findLiveByScope} が既存生存レコードを検出して
         * {@code ALLOCATION_ALREADY_EXISTS} (409) を返すことを検証する。
         *
         * <p>並行性そのものを検証するのは難しいので、Mock の挙動で
         * 「2回目の呼び出しでは findLiveByScope が既存レコードを返す」状態を再現することで
         * SELECT FOR UPDATE による重複検知ロジックの妥当性を担保する。</p>
         */
        @Test
        @DisplayName("同一スコープ並行Create_findLiveByScope の SELECT FOR UPDATE で重複検知される")
        void 同一スコープ並行Create_例外() {
            givenBudgetManageAllowed();
            given(rateQueryRepository.countTeamInOrganization(TEAM_ID, ORG_ID)).willReturn(1L);

            // 1回目: 重複なし → 成功
            given(allocationRepository.findLiveByScope(eq(ORG_ID), eq(TEAM_ID), eq(null),
                    eq(17L), any(LocalDate.class), any(LocalDate.class)))
                    .willReturn(Optional.empty())
                    .willReturn(Optional.of(sampleEntity()));  // 2回目以降: 既存検出
            given(allocationRepository.save(any(ShiftBudgetAllocationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // 1回目 — 成功
            AllocationResponse first = service.createAllocation(ORG_ID, sampleCreateRequest());
            assertThat(first).isNotNull();

            // 2回目（同一スコープ） — findLiveByScope が既存検出 → 409
            assertThatThrownBy(() -> service.createAllocation(ORG_ID, sampleCreateRequest()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.ALLOCATION_ALREADY_EXISTS);
        }

        /**
         * project_id を指定した割当の作成が正常動作することを検証する。
         */
        @Test
        @DisplayName("project_id 指定_プロジェクト存在時_正常作成")
        void project_id指定_正常作成() {
            givenBudgetManageAllowed();
            given(rateQueryRepository.countTeamInOrganization(TEAM_ID, ORG_ID)).willReturn(1L);
            // ProjectRepository は実体を返す必要はなく、存在することのみを確認する
            given(projectRepository.findByIdAndDeletedAtIsNull(eq(99L)))
                    .willReturn(Optional.of(org.mockito.Mockito.mock(
                            com.mannschaft.app.todo.entity.ProjectEntity.class)));
            given(allocationRepository.findLiveByScope(eq(ORG_ID), eq(TEAM_ID), eq(99L),
                    eq(17L), any(LocalDate.class), any(LocalDate.class)))
                    .willReturn(Optional.empty());
            given(allocationRepository.save(any(ShiftBudgetAllocationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            AllocationCreateRequest req = new AllocationCreateRequest(
                    TEAM_ID, 99L, 3L, 17L,
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                    new BigDecimal("300000"), "JPY", "プロジェクト専用割当");
            AllocationResponse response = service.createAllocation(ORG_ID, req);

            assertThat(response.projectId()).isEqualTo(99L);
            verify(allocationRepository).save(any(ShiftBudgetAllocationEntity.class));
        }

        /**
         * project_id を指定したが存在しない場合 → PROJECT_NOT_FOUND (404)。
         */
        @Test
        @DisplayName("project_id 指定_存在しないプロジェクト_PROJECT_NOT_FOUND (404)")
        void project_id指定_存在しない_404() {
            givenBudgetManageAllowed();
            given(rateQueryRepository.countTeamInOrganization(TEAM_ID, ORG_ID)).willReturn(1L);
            given(projectRepository.findByIdAndDeletedAtIsNull(eq(99L)))
                    .willReturn(Optional.empty());

            AllocationCreateRequest req = new AllocationCreateRequest(
                    TEAM_ID, 99L, 3L, 17L,
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                    new BigDecimal("300000"), "JPY", null);
            assertThatThrownBy(() -> service.createAllocation(ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.PROJECT_NOT_FOUND);
        }
    }
}
