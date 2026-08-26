package com.mannschaft.app.dashboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.dashboard.DashboardScopeTabErrorCode;
import com.mannschaft.app.dashboard.dto.ScopeTabItemResponse;
import com.mannschaft.app.dashboard.dto.ScopeTabOrderUpdateRequest;
import com.mannschaft.app.dashboard.dto.ScopeTabPageResponse;
import com.mannschaft.app.dashboard.service.DashboardScopeTabService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link DashboardScopeTabController} の単体テスト。
 *
 * <p>本アプリは {@code @EnableMethodSecurity} 未有効のため、コントローラを直接呼び出す方式
 * （{@code ChatFolderControllerTest} 等と同パターン）で契約を検証する。
 * GET 200 形状 / PUT 204 / 未所属 403 を確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardScopeTabController 単体テスト")
class DashboardScopeTabControllerTest {

    @Mock
    private DashboardScopeTabService scopeTabService;

    @InjectMocks
    private DashboardScopeTabController controller;

    @Test
    @DisplayName("GET: 200 でページレスポンス形状を返す")
    void getScopeTabs_returnsPage() {
        ScopeTabItemResponse item = ScopeTabItemResponse.builder()
                .scopeId(12L).scopeType("TEAM").name("開発チーム")
                .avatarUrl(null).unreadCount(0).sortOrder(0).build();
        ScopeTabPageResponse page = ScopeTabPageResponse.builder()
                .items(List.of(item)).page(0).pageSize(6)
                .totalPages(1).totalCount(1).hasNext(false).hasPrev(false).build();
        given(scopeTabService.getScopeTabs("TEAM", 0, null)).willReturn(page);

        ResponseEntity<ApiResponse<ScopeTabPageResponse>> res =
                controller.getScopeTabs("TEAM", 0, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getData().pageSize()).isEqualTo(6);
        assertThat(res.getBody().getData().items()).hasSize(1);
        assertThat(res.getBody().getData().items().get(0).scopeId()).isEqualTo(12L);
        verify(scopeTabService).getScopeTabs("TEAM", 0, null);
    }

    @Test
    @DisplayName("PUT: 204 No Content を返す")
    void updateOrder_returns204() {
        ScopeTabOrderUpdateRequest req = new ScopeTabOrderUpdateRequest();
        req.setScopeType("TEAM");

        ResponseEntity<Void> res = controller.updateOrder(req);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(scopeTabService).updateOrder(req);
    }

    @Test
    @DisplayName("PUT: 非所属混入時はサービスの SCOPE_TAB_001 が伝播する（403相当）")
    void updateOrder_nonMemberPropagates403() {
        ScopeTabOrderUpdateRequest req = new ScopeTabOrderUpdateRequest();
        req.setScopeType("TEAM");
        doThrow(new BusinessException(DashboardScopeTabErrorCode.SCOPE_TAB_001))
                .when(scopeTabService).updateOrder(any());

        assertThatThrownBy(() -> controller.updateOrder(req))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", DashboardScopeTabErrorCode.SCOPE_TAB_001);
    }
}
