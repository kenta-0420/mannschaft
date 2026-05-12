package com.mannschaft.app.repairplan.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.repairplan.RepairPlanErrorCode;
import com.mannschaft.app.repairplan.dto.CreateRepairPlanItemRequest;
import com.mannschaft.app.repairplan.dto.RepairPlanItemDto;
import com.mannschaft.app.repairplan.dto.RepairPlanItemFilter;
import com.mannschaft.app.repairplan.dto.UpdateRepairPlanItemRequest;
import com.mannschaft.app.repairplan.entity.RepairPlanItem;
import com.mannschaft.app.repairplan.repository.RepairPlanItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link RepairPlanItemService} 単体テスト（F08.8 Phase 1 案5）。
 *
 * <p>CRUD・楽観ロック・認可拒否・他テナント参照のシナリオを網羅する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RepairPlanItemService 単体テスト")
class RepairPlanItemServiceTest {

    @Mock
    private RepairPlanItemRepository repository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private RepairPlanItemService service;

    private static final Long USER_ID = 100L;
    private static final Long SCOPE_ID = 200L;
    private static final String SCOPE_TYPE = "TEAM";
    private static final Long ORG_ID = 300L;
    private UUID itemId;

    @BeforeEach
    void setUp() {
        itemId = UUID.randomUUID();
    }

    // ─────────────────────────────────────────────
    // create
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("create")
    class CreateTests {

        @Test
        @DisplayName("正常系: ADMIN が PLANNED ステータスの項目を作成できる")
        void create_正常() {
            CreateRepairPlanItemRequest req = new CreateRepairPlanItemRequest(
                    null, "屋上防水", "防水工事", "ウレタン塗膜",
                    2030, 6, 5_000_000L, 2024,
                    "PLANNED", null, null);

            given(repository.save(any(RepairPlanItem.class))).willAnswer(inv -> {
                RepairPlanItem e = inv.getArgument(0);
                // save 後 id/version が設定される想定
                java.lang.reflect.Field idField;
                try {
                    idField = e.getClass().getSuperclass().getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(e, itemId);
                } catch (ReflectiveOperationException ex) {
                    throw new RuntimeException(ex);
                }
                e.setVersion(0L);
                return e;
            });

            RepairPlanItemDto dto = service.create(req, USER_ID, SCOPE_ID, SCOPE_TYPE, ORG_ID);

            assertThat(dto.getCategory()).isEqualTo("屋上防水");
            assertThat(dto.getTitle()).isEqualTo("防水工事");
            assertThat(dto.getStatus()).isEqualTo("PLANNED");
            assertThat(dto.getScopeType()).isEqualTo("TEAM");
            assertThat(dto.getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(dto.getCpiInflationBasisYear()).isEqualTo(2024);

            verify(accessControlService).checkAdminOrAbove(USER_ID, SCOPE_ID, SCOPE_TYPE);
            // TEAM スコープでは teamId=scopeId, orgId=organizationId（テナント突合の手がかりとして残す）
            verify(auditLogService).record(eq("PLAN_ITEM_CREATED"), eq(USER_ID), isNull(),
                    eq(SCOPE_ID), eq(ORG_ID), isNull(), isNull(), isNull(), anyString());
        }

        @Test
        @DisplayName("正常系: status 省略時は PLANNED が設定される")
        void create_status未指定でPLANNED() {
            CreateRepairPlanItemRequest req = new CreateRepairPlanItemRequest(
                    null, "給排水", "配管更新", null,
                    2031, null, 1_000_000L, null,
                    null, null, null);

            given(repository.save(any(RepairPlanItem.class))).willAnswer(inv -> {
                RepairPlanItem e = inv.getArgument(0);
                e.setVersion(0L);
                return e;
            });

            RepairPlanItemDto dto = service.create(req, USER_ID, SCOPE_ID, SCOPE_TYPE, ORG_ID);

            assertThat(dto.getStatus()).isEqualTo("PLANNED");
            // cpi 省略時は plannedYear と同値
            assertThat(dto.getCpiInflationBasisYear()).isEqualTo(2031);
        }

        @Test
        @DisplayName("異常系: ADMIN ではないユーザーは 403")
        void create_認可拒否() {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());

            CreateRepairPlanItemRequest req = new CreateRepairPlanItemRequest(
                    null, "屋上防水", "防水", null,
                    2030, null, 1_000_000L, null,
                    null, null, null);

            assertThatThrownBy(() -> service.create(req, USER_ID, SCOPE_ID, SCOPE_TYPE, ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: 不正なスコープ種別は INVALID_SCOPE")
        void create_スコープ不正() {
            CreateRepairPlanItemRequest req = new CreateRepairPlanItemRequest(
                    null, "屋上防水", "防水", null,
                    2030, null, 1_000_000L, null,
                    null, null, null);

            assertThatThrownBy(() -> service.create(req, USER_ID, SCOPE_ID, "USER", ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RepairPlanErrorCode.INVALID_SCOPE);
        }
    }

    // ─────────────────────────────────────────────
    // list
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("list")
    class ListTests {

        @Test
        @DisplayName("正常系: メンバーであれば年度・カテゴリ・ステータス絞り込みで取得できる")
        void list_正常() {
            RepairPlanItem item = sampleEntity();
            given(repository.searchByFilter(eq(ORG_ID), eq(SCOPE_TYPE), eq(SCOPE_ID),
                    eq(2030), eq("屋上防水"), eq("PLANNED"), any()))
                    .willReturn(new PageImpl<>(List.of(item)));

            RepairPlanItemFilter filter = RepairPlanItemFilter.builder()
                    .plannedYear(2030)
                    .category("屋上防水")
                    .status("PLANNED")
                    .build();

            var result = service.list(SCOPE_ID, SCOPE_TYPE, ORG_ID, filter,
                    PageRequest.of(0, 20), USER_ID);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getTitle()).isEqualTo(item.getTitle());
            verify(accessControlService).checkMembership(USER_ID, SCOPE_ID, SCOPE_TYPE);
        }

        @Test
        @DisplayName("異常系: 非メンバーは 403")
        void list_認可拒否() {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkMembership(anyLong(), anyLong(), anyString());

            assertThatThrownBy(() -> service.list(SCOPE_ID, SCOPE_TYPE, ORG_ID,
                    null, PageRequest.of(0, 20), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }
    }

    // ─────────────────────────────────────────────
    // get
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("get")
    class GetTests {

        @Test
        @DisplayName("正常系: 自テナント内の項目を取得できる")
        void get_正常() {
            RepairPlanItem item = sampleEntity();
            given(repository.findByIdAndOrganizationIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                    itemId, ORG_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(item));

            RepairPlanItemDto dto = service.get(itemId, ORG_ID, SCOPE_TYPE, SCOPE_ID, USER_ID);
            assertThat(dto.getTitle()).isEqualTo(item.getTitle());
        }

        @Test
        @DisplayName("異常系: 他テナントで参照すると 404 ITEM_NOT_FOUND")
        void get_他テナントは404() {
            given(repository.findByIdAndOrganizationIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                    itemId, 999L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.get(itemId, 999L, SCOPE_TYPE, SCOPE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RepairPlanErrorCode.ITEM_NOT_FOUND);
        }
    }

    // ─────────────────────────────────────────────
    // update
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("update")
    class UpdateTests {

        @Test
        @DisplayName("正常系: title と status を更新できる")
        void update_正常() {
            RepairPlanItem item = sampleEntity();
            given(repository.findByIdAndOrganizationIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                    itemId, ORG_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(item));
            given(repository.save(any(RepairPlanItem.class))).willAnswer(inv -> inv.getArgument(0));

            UpdateRepairPlanItemRequest req = new UpdateRepairPlanItemRequest(
                    null, null, "更新タイトル", null, null, null, null, null,
                    "IN_PROGRESS", null, null);

            RepairPlanItemDto dto = service.update(itemId, req, USER_ID, ORG_ID, SCOPE_TYPE, SCOPE_ID, 0L);

            assertThat(dto.getTitle()).isEqualTo("更新タイトル");
            assertThat(dto.getStatus()).isEqualTo("IN_PROGRESS");

            ArgumentCaptor<RepairPlanItem> captor = ArgumentCaptor.forClass(RepairPlanItem.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getUpdatedBy()).isEqualTo(USER_ID);
            verify(auditLogService).record(eq("PLAN_ITEM_UPDATED"), eq(USER_ID), isNull(),
                    eq(SCOPE_ID), eq(ORG_ID), isNull(), isNull(), isNull(), anyString());
        }

        @Test
        @DisplayName("異常系: version 不一致で 409 ObjectOptimisticLockingFailureException")
        void update_楽観ロック競合() {
            RepairPlanItem item = sampleEntity(); // version = 5
            item.setVersion(5L);
            given(repository.findByIdAndOrganizationIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                    itemId, ORG_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(item));

            UpdateRepairPlanItemRequest req = new UpdateRepairPlanItemRequest(
                    null, null, "競合タイトル", null, null, null, null, null,
                    null, null, null);

            assertThatThrownBy(() -> service.update(itemId, req, USER_ID, ORG_ID, SCOPE_TYPE, SCOPE_ID, 3L))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: 認可拒否で 403")
        void update_認可拒否() {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());

            UpdateRepairPlanItemRequest req = new UpdateRepairPlanItemRequest(
                    null, null, "x", null, null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.update(itemId, req, USER_ID, ORG_ID, SCOPE_TYPE, SCOPE_ID, 0L))
                    .isInstanceOf(BusinessException.class);
            verify(repository, never()).findByIdAndOrganizationIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                    any(), anyLong(), anyString(), anyLong());
        }

        @Test
        @DisplayName("異常系: 他テナントで更新しようとすると 404")
        void update_他テナント404() {
            given(repository.findByIdAndOrganizationIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                    itemId, 999L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            UpdateRepairPlanItemRequest req = new UpdateRepairPlanItemRequest(
                    null, null, "x", null, null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.update(itemId, req, USER_ID, 999L, SCOPE_TYPE, SCOPE_ID, 0L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RepairPlanErrorCode.ITEM_NOT_FOUND);
        }
    }

    // ─────────────────────────────────────────────
    // softDelete
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("softDelete")
    class DeleteTests {

        @Test
        @DisplayName("正常系: 論理削除して監査ログを記録する")
        void delete_正常() {
            RepairPlanItem item = sampleEntity();
            item.setVersion(2L);
            given(repository.findByIdAndOrganizationIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                    itemId, ORG_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(item));
            given(repository.save(any(RepairPlanItem.class))).willAnswer(inv -> inv.getArgument(0));

            service.softDelete(itemId, USER_ID, ORG_ID, SCOPE_TYPE, SCOPE_ID, 2L);

            ArgumentCaptor<RepairPlanItem> captor = ArgumentCaptor.forClass(RepairPlanItem.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getDeletedAt()).isNotNull();
            verify(auditLogService, times(1)).record(eq("PLAN_ITEM_DELETED"), eq(USER_ID), isNull(),
                    eq(SCOPE_ID), eq(ORG_ID), isNull(), isNull(), isNull(), anyString());
        }

        @Test
        @DisplayName("異常系: version 不一致で 409")
        void delete_楽観ロック競合() {
            RepairPlanItem item = sampleEntity();
            item.setVersion(7L);
            given(repository.findByIdAndOrganizationIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                    itemId, ORG_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(item));

            assertThatThrownBy(() -> service.softDelete(itemId, USER_ID, ORG_ID, SCOPE_TYPE, SCOPE_ID, 1L))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
            verify(repository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────
    // テスト fixture
    // ─────────────────────────────────────────────

    private RepairPlanItem sampleEntity() {
        RepairPlanItem entity = RepairPlanItem.builder()
                .organizationId(ORG_ID)
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .category("屋上防水")
                .title("防水工事")
                .description("ウレタン塗膜")
                .plannedYear(2030)
                .plannedMonth(6)
                .estimatedAmount(5_000_000L)
                .cpiInflationBasisYear(2024)
                .status("PLANNED")
                .createdBy(USER_ID)
                .updatedBy(USER_ID)
                .build();
        entity.setVersion(0L);
        return entity;
    }
}
