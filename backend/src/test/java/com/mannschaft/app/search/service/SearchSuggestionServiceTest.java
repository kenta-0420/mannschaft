package com.mannschaft.app.search.service;

import com.mannschaft.app.search.dto.SearchSuggestionResponse;
import com.mannschaft.app.search.entity.SearchHistoryEntity;
import com.mannschaft.app.search.repository.SearchHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link SearchSuggestionService} の単体テスト。
 * 検索サジェスト取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SearchSuggestionService 単体テスト")
class SearchSuggestionServiceTest {

    @Mock
    private SearchHistoryRepository searchHistoryRepository;

    @InjectMocks
    private SearchSuggestionService searchSuggestionService;

    private static final Long USER_ID = 100L;

    // ========================================
    // suggest
    // ========================================
    @Nested
    @DisplayName("suggest")
    class Suggest {

        @Test
        @DisplayName("正常系: 前方一致でサジェストを取得できる")
        void 前方一致でサジェストを取得できる() {
            // given
            List<SearchHistoryEntity> history = List.of(
                    SearchHistoryEntity.builder().userId(USER_ID).query("テスト検索").build(),
                    SearchHistoryEntity.builder().userId(USER_ID).query("テスト投稿").build(),
                    SearchHistoryEntity.builder().userId(USER_ID).query("別のクエリ").build());

            given(searchHistoryRepository.findByUserIdOrderBySearchedAtDesc(USER_ID)).willReturn(history);

            // when
            SearchSuggestionResponse result = searchSuggestionService.suggest("テスト", USER_ID);

            // then
            assertThat(result.getSuggestions()).hasSize(2);
            assertThat(result.getSuggestions()).containsExactly("テスト検索", "テスト投稿");
        }

        @Test
        @DisplayName("正常系: 最近のクエリが返される")
        void 最近のクエリが返される() {
            // given
            List<SearchHistoryEntity> history = List.of(
                    SearchHistoryEntity.builder().userId(USER_ID).query("最新1").build(),
                    SearchHistoryEntity.builder().userId(USER_ID).query("最新2").build(),
                    SearchHistoryEntity.builder().userId(USER_ID).query("最新3").build(),
                    SearchHistoryEntity.builder().userId(USER_ID).query("最新4").build(),
                    SearchHistoryEntity.builder().userId(USER_ID).query("最新5").build(),
                    SearchHistoryEntity.builder().userId(USER_ID).query("最新6").build());

            given(searchHistoryRepository.findByUserIdOrderBySearchedAtDesc(USER_ID)).willReturn(history);

            // when
            SearchSuggestionResponse result = searchSuggestionService.suggest("xxx", USER_ID);

            // then
            assertThat(result.getRecentQueries()).hasSize(5);
        }

        @Test
        @DisplayName("正常系: 履歴がない場合は空リストを返す")
        void 履歴がない場合は空リストを返す() {
            // given
            given(searchHistoryRepository.findByUserIdOrderBySearchedAtDesc(USER_ID)).willReturn(List.of());

            // when
            SearchSuggestionResponse result = searchSuggestionService.suggest("テスト", USER_ID);

            // then
            assertThat(result.getSuggestions()).isEmpty();
            assertThat(result.getRecentQueries()).isEmpty();
        }
    }
}
