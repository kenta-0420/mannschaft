package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.TodoBudgetLinkCreateRequest;
import com.mannschaft.app.shiftbudget.dto.TodoBudgetLinkResponse;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.TodoBudgetLinkEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import com.mannschaft.app.shiftbudget.repository.TodoBudgetLinkRepository;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.entity.ProjectEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.ProjectRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TodoBudgetLinkService} 単体テスト（Phase 9-γ / API #7-#8）。
 *
 * <p>設計書 F08.7 (v1.2) §4.3 / §5.4 / §6.2.4 / §9.1 / §9.5 のテストケースをカバー:</p>
 * <ul>
 *   <li>排他バリデーション（target/parameter XOR）</li>
 *   <li>多テナント分離（別組織の allocation/project/todo は 404）</li>
 *   <li>権限（ADMIN_OR_ABOVE + BUDGET_VIEW）</li>
 *   <li>重複検知 (LINK_ALREADY_EXISTS)</li>
 *   <li>監査ログ記録</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TodoBudgetLinkService 単体テスト")
class TodoBudgetLinkServiceTest {

    private static final Long USER_ID = 100L;
    private static final Long ORG_ID = 1L;
    private static final Long TEAM_ID = 12L;
    private static final Long PROJECT_ID = 88L;
    private static final Long TODO_ID = 1024L;
    private static final Long ALLOCATION_ID = 42L;
    private static final Long LINK_ID = 7L;

    @Mock private TodoBudgetLinkRepository linkRepository;
    @Mock private ShiftBudgetAllocationRepository allocationRepository;
    @Mock private ShiftBudgetRateQueryRepository rateQueryRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private ShiftBudgetFeatureService featureService;
    @Mock private AccessControlService accessControlService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private TodoBudgetLinkService service;

    /** sampleAllocation() を呼び出すと内部で given(...) するため、各テスト前に事前生成しておく */
    private ShiftBudgetAllocationEntity allocationFixture;

    @BeforeEach
    void setUpSecurity() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
        allocationFixture = sampleAllocation();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private ShiftBudgetAllocationEntity sampleAllocation() {
        ShiftBudgetAllocationEntity allocation = org.mockito.Mockito.mock(
                ShiftBudgetAllocationEntity.class);
        given(allocation.getId()).willReturn(ALLOCATION_ID);
        given(allocation.getOrganizationId()).willReturn(ORG_ID);
        given(allocation.getTeamId()).willReturn(TEAM_ID);
        return allocation;
    }

    private ProjectEntity mockProject(TodoScopeType scopeType, Long scopeId) {
        ProjectEntity project = org.mockito.Mockito.mock(ProjectEntity.class);
        given(project.getScopeType()).willReturn(scopeType);
        given(project.getScopeId()).willReturn(scopeId);
        return project;
    }

    private TodoEntity mockTodo(TodoScopeType scopeType, Long scopeId) {
        TodoEntity todo = org.mockito.Mockito.mock(TodoEntity.class);
        given(todo.getScopeType()).willReturn(scopeType);
        given(todo.getScopeId()).willReturn(scopeId);
        return todo;
    }

    private void givenAuthorizedForOrgScope() {
        // SYSTEM_ADMIN ではない
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        // ORGANIZATION スコープのメンバー
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        // BUDGET_VIEW 権限あり = checkPermission で例外なし
    }

    private TodoBudgetLinkCreateRequest projectLinkRequest() {
        return new TodoBudgetLinkCreateRequest(
                PROJECT_ID, null, ALLOCATION_ID,
                new BigDecimal("50000"), null, "JPY");
    }

    private TodoBudgetLinkCreateRequest todoLinkRequest() {
        return new TodoBudgetLinkCreateRequest(
                null, TODO_ID, ALLOCATION_ID,
                null, new BigDecimal("10.0"), null);
    }

    @Nested
    @DisplayName("createLink: バリデーション系")
    class CreateValidation {

        @Test
        @DisplayName("project_id と todo_id 両方 NULL → INVALID_LINK_TARGET (400)")
        void target両方NULL_400() {
            TodoBudgetLinkCreateRequest req = new TodoBudgetLinkCreateRequest(
                    null, null, ALLOCATION_ID, null, null, null);
            assertThatThrownBy(() -> service.createLink(ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.INVALID_LINK_TARGET);
        }

        @Test
        @DisplayName("project_id と todo_id 両方指定 → INVALID_LINK_TARGET (400)")
        void target両方指定_400() {
            TodoBudgetLinkCreateRequest req = new TodoBudgetLinkCreateRequest(
                    PROJECT_ID, TODO_ID, ALLOCATION_ID, null, null, null);
            assertThatThrownBy(() -> service.createLink(ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.INVALID_LINK_TARGET);
        }

        @Test
        @DisplayName("link_amount と link_percentage 同時指定 → INVALID_LINK_PARAMETER (400)")
        void linkAmount_Percentage_両方指定_400() {
            TodoBudgetLinkCreateRequest req = new TodoBudgetLinkCreateRequest(
                    PROJECT_ID, null, ALLOCATION_ID,
                    new BigDecimal("50000"), new BigDecimal("10.0"), null);
            assertThatThrownBy(() -> service.createLink(ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.INVALID_LINK_PARAMETER);
        }

        @Test
        @DisplayName("link_amount 負数 → INVALID_LINK_PARAMETER (400)")
        void linkAmount_負数_400() {
            TodoBudgetLinkCreateRequest req = new TodoBudgetLinkCreateRequest(
                    PROJECT_ID, null, ALLOCATION_ID,
                    new BigDecimal("-1"), null, null);
            assertThatThrownBy(() -> service.createLink(ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.INVALID_LINK_PARAMETER);
        }
    }

    @Nested
    @DisplayName("createLink: 多テナント / 認可")
    class CreateAuth {

        @Test
        @DisplayName("別組織の allocation → ALLOCATION_NOT_FOUND (404)")
        void 別組織allocation_404() {
            given(allocationRepository
                    .findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.createLink(ORG_ID, projectLinkRequest()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND);
        }

        @Test
        @DisplayName("project が存在しない → PROJECT_NOT_FOUND (404)")
        void project存在しない_404() {
            given(allocationRepository
                    .findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(allocationFixture));
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.createLink(ORG_ID, projectLinkRequest()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.PROJECT_NOT_FOUND);
        }

        @Test
        @DisplayName("project が別組織 → PROJECT_NOT_FOUND (404 IDOR 対策)")
        void project別組織_404() {
            given(allocationRepository
                    .findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(allocationFixture));
            // ORGANIZATION スコープだが scopeId が ORG_ID と異なる
            ProjectEntity p = mockProject(TodoScopeType.ORGANIZATION, 9999L);
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID))
                    .willReturn(Optional.of(p));

            assertThatThrownBy(() -> service.createLink(ORG_ID, projectLinkRequest()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.PROJECT_NOT_FOUND);
        }

        @Test
        @DisplayName("project が PERSONAL スコープ → PROJECT_NOT_FOUND (404 サポート対象外)")
        void projectPERSONAL_404() {
            given(allocationRepository
                    .findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(allocationFixture));
            ProjectEntity p = mockProject(TodoScopeType.PERSONAL, USER_ID);
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID))
                    .willReturn(Optional.of(p));

            assertThatThrownBy(() -> service.createLink(ORG_ID, projectLinkRequest()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.PROJECT_NOT_FOUND);
        }

        @Test
        @DisplayName("ADMIN_OR_ABOVE 権限なし → LINK_PERMISSION_REQUIRED (403)")
        void admin権限なし_403() {
            given(allocationRepository
                    .findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(allocationFixture));
            ProjectEntity p = mockProject(TodoScopeType.ORGANIZATION, ORG_ID);
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID))
                    .willReturn(Optional.of(p));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION"))
                    .willReturn(false);

            assertThatThrownBy(() -> service.createLink(ORG_ID, projectLinkRequest()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.LINK_PERMISSION_REQUIRED);
        }
    }

    @Nested
    @DisplayName("createLink: 正常系 / 重複検知")
    class CreateNormal {

        @Test
        @DisplayName("正常系（プロジェクト紐付）: INSERT + 監査ログ")
        void 正常系_project紐付() {
            given(allocationRepository
                    .findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(allocationFixture));
            ProjectEntity p = mockProject(TodoScopeType.ORGANIZATION, ORG_ID);
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID))
                    .willReturn(Optional.of(p));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION"))
                    .willReturn(true);
            givenAuthorizedForOrgScope();
            given(linkRepository.findByProjectIdAndAllocationId(PROJECT_ID, ALLOCATION_ID))
                    .willReturn(Optional.empty());
            given(linkRepository.save(any(TodoBudgetLinkEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            TodoBudgetLinkResponse response = service.createLink(ORG_ID, projectLinkRequest());

            assertThat(response.projectId()).isEqualTo(PROJECT_ID);
            assertThat(response.todoId()).isNull();
            assertThat(response.allocationId()).isEqualTo(ALLOCATION_ID);
            assertThat(response.linkAmount()).isEqualByComparingTo("50000");
            verify(linkRepository).save(any(TodoBudgetLinkEntity.class));
            verify(auditLogService).record(eq("TODO_BUDGET_LINK_CREATED"),
                    eq(USER_ID), eq(null), eq(TEAM_ID), eq(ORG_ID),
                    any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("正常系（TODO 紐付）: TEAM スコープ TODO + percentage 指定")
        void 正常系_todo紐付_team() {
            given(allocationRepository
                    .findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(allocationFixture));
            TodoEntity t = mockTodo(TodoScopeType.TEAM, TEAM_ID);
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID))
                    .willReturn(Optional.of(t));
            given(rateQueryRepository.findOrganizationIdByTeamId(TEAM_ID))
                    .willReturn(Optional.of(ORG_ID));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM"))
                    .willReturn(true);
            givenAuthorizedForOrgScope();
            given(linkRepository.findByTodoIdAndAllocationId(TODO_ID, ALLOCATION_ID))
                    .willReturn(Optional.empty());
            given(linkRepository.save(any(TodoBudgetLinkEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            TodoBudgetLinkResponse response = service.createLink(ORG_ID, todoLinkRequest());

            assertThat(response.todoId()).isEqualTo(TODO_ID);
            assertThat(response.linkPercentage()).isEqualByComparingTo("10.0");
            verify(auditLogService).record(eq("TODO_BUDGET_LINK_CREATED"),
                    anyLong(), any(), any(), any(), any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("重複あり (project, allocation) → LINK_ALREADY_EXISTS (409)")
        void 重複_409() {
            given(allocationRepository
                    .findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(allocationFixture));
            ProjectEntity p = mockProject(TodoScopeType.ORGANIZATION, ORG_ID);
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID))
                    .willReturn(Optional.of(p));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION"))
                    .willReturn(true);
            givenAuthorizedForOrgScope();
            given(linkRepository.findByProjectIdAndAllocationId(PROJECT_ID, ALLOCATION_ID))
                    .willReturn(Optional.of(TodoBudgetLinkEntity.builder().build()));

            assertThatThrownBy(() -> service.createLink(ORG_ID, projectLinkRequest()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.LINK_ALREADY_EXISTS);

            verify(linkRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("deleteLink")
    class DeleteLink {

        @Test
        @DisplayName("正常系: 削除 + 監査ログ")
        void 正常系_削除() {
            TodoBudgetLinkEntity link = TodoBudgetLinkEntity.builder()
                    .projectId(PROJECT_ID)
                    .allocationId(ALLOCATION_ID)
                    .build();
            given(linkRepository.findByIdAndOrganizationId(LINK_ID, ORG_ID))
                    .willReturn(Optional.of(link));
            ProjectEntity p = mockProject(TodoScopeType.ORGANIZATION, ORG_ID);
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID))
                    .willReturn(Optional.of(p));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION"))
                    .willReturn(true);
            givenAuthorizedForOrgScope();
            given(allocationRepository
                    .findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(allocationFixture));

            service.deleteLink(ORG_ID, LINK_ID);

            verify(linkRepository).delete(link);
            verify(auditLogService).record(eq("TODO_BUDGET_LINK_DELETED"),
                    eq(USER_ID), eq(null), eq(TEAM_ID), eq(ORG_ID),
                    any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("別組織の link → LINK_NOT_FOUND (404)")
        void 別組織_404() {
            given(linkRepository.findByIdAndOrganizationId(LINK_ID, ORG_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteLink(ORG_ID, LINK_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.LINK_NOT_FOUND);
        }
    }
}
