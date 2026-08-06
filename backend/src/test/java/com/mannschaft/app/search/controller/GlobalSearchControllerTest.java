package com.mannschaft.app.search.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.search.dto.SearchResultResponse;
import com.mannschaft.app.search.dto.SearchSuggestionResponse;
import com.mannschaft.app.search.service.GlobalSearchService;
import com.mannschaft.app.search.service.SearchHistoryService;
import com.mannschaft.app.search.service.SearchSuggestionService;
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

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link GlobalSearchController} の単体テスト。
 *
 * <p>GlobalSearchController#suggest の自己スコープ性を固定する契約テスト。
 * {@code SearchSuggestionService#suggest} は {@code SecurityUtils.getCurrentUserId()} を
 * 検索条件に使うのみで、他ユーザーの検索履歴を候補として混入させる経路が構造的に無い。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GlobalSearchController 単体テスト")
class GlobalSearchControllerTest {

    @Mock
    private GlobalSearchService globalSearchService;
    @Mock
    private SearchHistoryService searchHistoryService;
    @Mock
    private SearchSuggestionService searchSuggestionService;

    @InjectMocks
    private GlobalSearchController controller;

    private static final Long USER_ID = 100L;

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
    @DisplayName("suggest は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void suggest_boundToCurrentUserOnly() {
        given(searchSuggestionService.suggest("q", USER_ID))
            .willReturn(new SearchSuggestionResponse(java.util.List.of(), java.util.List.of()));

        controller.suggest("q");

        // 他人の userId を検索条件に渡す経路が存在しないことの裏取り。
        verify(searchSuggestionService).suggest("q", USER_ID);
    }

    @Test
    @DisplayName("search は SecurityUtils.getCurrentUserId() を可視スコープ解決に渡す")
    void search_boundToCurrentUserOnly() {
        given(globalSearchService.search("q", USER_ID))
            .willReturn(new SearchResultResponse("q", java.util.Map.of(), java.util.Map.of(), 0L));

        controller.search("q");

        verify(searchHistoryService).recordHistory(USER_ID, "q");
        verify(globalSearchService).search("q", USER_ID);
    }
}
