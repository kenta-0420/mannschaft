package com.mannschaft.app.incident.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.incident.entity.IncidentEntity;
import com.mannschaft.app.incident.repository.IncidentAssignmentRepository;
import com.mannschaft.app.incident.repository.IncidentCategoryRepository;
import com.mannschaft.app.incident.repository.IncidentRepository;
import com.mannschaft.app.incident.repository.IncidentStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link IncidentService#listIncidents} の単体テスト（CMP-028 Phase D）。
 *
 * <p>
 * 旧実装（スコープ配下の全件ロード → メモリで status フィルタ → 手動ページング）を撤去し、
 * DB へ {@link Pageable} を渡してページング・status絞り込みを行う形へ是正したことを検証する。
 * </p>
 *
 * <p>
 * 単体では「status/pageable が正しく Repository へ渡ること」と「返却されたページの
 * 内容・総件数がそのまま透過されること」を検証する。行が実際に除外され歯抜けが無いことは
 * 実 DB を用いる {@code IncidentRepositoryPagingInTest} が担保する（モックの戻り値を
 * そのまま assert しても同語反復にしかならないため）。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentService#listIncidents 単体テスト（CMP-028 Phase D）")
class IncidentServiceListIncidentsTest {

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private IncidentCategoryRepository categoryRepository;

    @Mock
    private IncidentAssignmentRepository assignmentRepository;

    @Mock
    private IncidentStatusHistoryRepository statusHistoryRepository;

    @Mock
    private DomainEventPublisher eventPublisher;

    @Mock
    private AccessControlService accessControlService;

    private IncidentService incidentService;

    private static final Long USER_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final String SCOPE_TYPE = "TEAM";

    @BeforeEach
    void setUp() {
        incidentService = new IncidentService(
                incidentRepository, categoryRepository, assignmentRepository,
                statusHistoryRepository, eventPublisher, accessControlService);
    }

    /**
     * AC-D1: DB Pageable を渡す実装になっており、全件ロード用の旧メソッド
     * （{@code findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc}）へは
     * 到達しないこと（旧経路へ戻っていないことの実証）。
     */
    @Test
    @DisplayName("AC-D1: DBへPageableを渡すRepositoryメソッドが呼ばれ、全件ロード系メソッドは呼ばれない")
    void DBへPageableを渡す() {
        Pageable pageable = PageRequest.of(0, 20);
        IncidentEntity entity = buildIncident(1L, SCOPE_ID, "REPORTED");
        given(incidentRepository.findByScopeTypeAndScopeIdAndStatus(
                eq(SCOPE_TYPE), eq(SCOPE_ID), isNull(), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(entity), pageable, 1));

        Page<IncidentService.IncidentSummaryResponse> result =
                incidentService.listIncidents(SCOPE_TYPE, SCOPE_ID, null, pageable, USER_ID);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(incidentRepository).findByScopeTypeAndScopeIdAndStatus(
                SCOPE_TYPE, SCOPE_ID, null, pageable);
        verify(incidentRepository, org.mockito.Mockito.never())
                .findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(any(), any());
    }

    /**
     * AC-D1: status が空でない場合、正規化した status がそのまま Repository へ渡る
     * （旧実装のメモリフィルタに代わる SQL WHERE 絞り込みへの引数受け渡し）。
     */
    @Test
    @DisplayName("status指定時はstatusがそのままRepositoryへ渡る")
    void statusがそのまま渡る() {
        Pageable pageable = PageRequest.of(0, 20);
        given(incidentRepository.findByScopeTypeAndScopeIdAndStatus(
                eq(SCOPE_TYPE), eq(SCOPE_ID), eq("RESOLVED"), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(), pageable, 0));

        incidentService.listIncidents(SCOPE_TYPE, SCOPE_ID, "RESOLVED", pageable, USER_ID);

        verify(incidentRepository).findByScopeTypeAndScopeIdAndStatus(
                SCOPE_TYPE, SCOPE_ID, "RESOLVED", pageable);
    }

    /**
     * status が空文字・空白のみの場合は null 正規化される（「絞り込みなし」として
     * SQL 側の {@code :status IS NULL} 分岐に渡る）。
     */
    @Test
    @DisplayName("statusが空白のみの場合はnullに正規化されてRepositoryへ渡る")
    void 空白statusはnullに正規化される() {
        Pageable pageable = PageRequest.of(0, 20);
        given(incidentRepository.findByScopeTypeAndScopeIdAndStatus(
                eq(SCOPE_TYPE), eq(SCOPE_ID), isNull(), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(), pageable, 0));

        incidentService.listIncidents(SCOPE_TYPE, SCOPE_ID, "  ", pageable, USER_ID);

        verify(incidentRepository).findByScopeTypeAndScopeIdAndStatus(
                SCOPE_TYPE, SCOPE_ID, null, pageable);
    }

    /**
     * AC-D6（拒否側）: 非会員は 403 になり、DB へは一切到達しない。
     */
    @Test
    @DisplayName("AC-D6（拒否側）: 非会員はDBを引く前に403で弾かれる")
    void 非会員は弾かれる() {
        Pageable pageable = PageRequest.of(0, 20);
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkMembership(USER_ID, SCOPE_ID, SCOPE_TYPE);

        assertThatThrownBy(() ->
                incidentService.listIncidents(SCOPE_TYPE, SCOPE_ID, null, pageable, USER_ID))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(incidentRepository);
    }

    /**
     * AC-D6（許可側）: 会員は checkMembership を通過し、一覧が取得できる。
     */
    @Test
    @DisplayName("AC-D6（許可側）: 会員には一覧が返る")
    void 会員には一覧が返る() {
        Pageable pageable = PageRequest.of(0, 20);
        IncidentEntity entity = buildIncident(2L, SCOPE_ID, "REPORTED");
        given(incidentRepository.findByScopeTypeAndScopeIdAndStatus(
                eq(SCOPE_TYPE), eq(SCOPE_ID), isNull(), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(entity), pageable, 1));

        Page<IncidentService.IncidentSummaryResponse> result =
                incidentService.listIncidents(SCOPE_TYPE, SCOPE_ID, null, pageable, USER_ID);

        assertThat(result.getContent()).hasSize(1);
        verify(accessControlService).checkMembership(USER_ID, SCOPE_ID, SCOPE_TYPE);
    }

    private IncidentEntity buildIncident(Long id, Long scopeId, String status) {
        IncidentEntity entity = IncidentEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(scopeId)
                .title("インシデント")
                .status(status)
                .priority("MEDIUM")
                .isSlaBreached(false)
                .reportedBy(99L)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
