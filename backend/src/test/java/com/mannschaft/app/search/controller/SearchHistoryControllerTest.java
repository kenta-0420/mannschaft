package com.mannschaft.app.search.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.search.dto.SaveQueryRequest;
import com.mannschaft.app.search.dto.SavedQueryResponse;
import com.mannschaft.app.search.service.SearchHistoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SearchHistoryController} の単体テスト。
 *
 * <p>SearchHistoryController#listHistory / #deleteAllHistory / #listSavedQueries / #saveQuery
 * の自己スコープ性、および #deleteHistory の所有者検証委譲を固定する契約テスト。
 * いずれも {@code SearchHistoryService} が {@code SecurityUtils.getCurrentUserId()} のみを
 * 主体として使用する（deleteHistory は historyId をパスから受け取るが、
 * Service 側 findByIdAndUserId で所有者一致を検証する）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SearchHistoryController 単体テスト")
class SearchHistoryControllerTest {

    @Mock
    private SearchHistoryService searchHistoryService;

    @InjectMocks
    private SearchHistoryController controller;

    private static final Long USER_ID = 100L;
    private static final Long HISTORY_ID = 500L;

    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUpSecurityUtils() {
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDownSecurityUtils() {
        securityUtils.close();
    }

    @Test
    @DisplayName("listHistory は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void listHistory_boundToCurrentUserOnly() {
        given(searchHistoryService.listHistory(USER_ID)).willReturn(List.of());

        controller.listHistory();

        verify(searchHistoryService).listHistory(USER_ID);
    }

    @Test
    @DisplayName("deleteAllHistory は SecurityUtils.getCurrentUserId() の履歴のみ削除する")
    void deleteAllHistory_boundToCurrentUserOnly() {
        controller.deleteAllHistory();

        verify(searchHistoryService).deleteAllHistory(USER_ID);
    }

    @Test
    @DisplayName("deleteHistory は historyId の所有者検証を SecurityUtils.getCurrentUserId() で行う")
    void deleteHistory_ownershipCheckedAgainstCurrentUser() {
        controller.deleteHistory(HISTORY_ID);

        // 他人の userId で所有者検証を行う経路が存在しないことの裏取り。
        verify(searchHistoryService).deleteHistory(USER_ID, HISTORY_ID);
    }

    @Test
    @DisplayName("listSavedQueries は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void listSavedQueries_boundToCurrentUserOnly() {
        given(searchHistoryService.listSavedQueries(USER_ID)).willReturn(List.of());

        controller.listSavedQueries();

        verify(searchHistoryService).listSavedQueries(USER_ID);
    }

    @Test
    @DisplayName("saveQuery は SecurityUtils.getCurrentUserId() を owner として保存する")
    void saveQuery_boundToCurrentUserOnly() {
        SaveQueryRequest request = new SaveQueryRequest("name", "q=1");
        given(searchHistoryService.saveQuery(USER_ID, request))
            .willReturn(new SavedQueryResponse(1L, "name", "q=1", null));

        controller.saveQuery(request);

        verify(searchHistoryService).saveQuery(USER_ID, request);
    }
}
