package com.mannschaft.app.search.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.search.SearchErrorCode;
import com.mannschaft.app.search.SearchMapper;
import com.mannschaft.app.search.dto.SaveQueryRequest;
import com.mannschaft.app.search.dto.SavedQueryResponse;
import com.mannschaft.app.search.dto.SearchHistoryResponse;
import com.mannschaft.app.search.entity.SearchHistoryEntity;
import com.mannschaft.app.search.entity.SearchSavedQueryEntity;
import com.mannschaft.app.search.repository.SearchHistoryRepository;
import com.mannschaft.app.search.repository.SearchSavedQueryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SearchHistoryService} の単体テスト。
 * 検索履歴CRUD・保存済みクエリ管理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SearchHistoryService 単体テスト")
class SearchHistoryServiceTest {

    @Mock
    private SearchHistoryRepository searchHistoryRepository;

    @Mock
    private SearchSavedQueryRepository searchSavedQueryRepository;

    @Mock
    private SearchMapper searchMapper;

    @InjectMocks
    private SearchHistoryService searchHistoryService;

    private static final Long USER_ID = 100L;
    private static final Long HISTORY_ID = 1L;

    // ========================================
    // recordHistory
    // ========================================
    @Nested
    @DisplayName("recordHistory")
    class RecordHistory {

        @Test
        @DisplayName("正常系: 新規クエリの場合は新規作成される")
        void 新規クエリの場合は新規作成される() {
            // given
            given(searchHistoryRepository.findByUserIdAndQuery(USER_ID, "テスト"))
                    .willReturn(Optional.empty());

            // when
            searchHistoryService.recordHistory(USER_ID, "テスト");

            // then
            verify(searchHistoryRepository).save(any(SearchHistoryEntity.class));
        }

        @Test
        @DisplayName("正常系: 既存クエリの場合は検索日時が更新される")
        void 既存クエリの場合は検索日時が更新される() {
            // given
            SearchHistoryEntity existing = SearchHistoryEntity.builder()
                    .userId(USER_ID).query("テスト").build();
            given(searchHistoryRepository.findByUserIdAndQuery(USER_ID, "テスト"))
                    .willReturn(Optional.of(existing));

            // when
            searchHistoryService.recordHistory(USER_ID, "テスト");

            // then
            verify(searchHistoryRepository, never()).save(any(SearchHistoryEntity.class));
        }
    }

    // ========================================
    // listHistory
    // ========================================
    @Nested
    @DisplayName("listHistory")
    class ListHistory {

        @Test
        @DisplayName("正常系: 検索履歴一覧を取得できる")
        void 検索履歴一覧を取得できる() {
            // given
            List<SearchHistoryEntity> entities = List.of(
                    SearchHistoryEntity.builder().userId(USER_ID).query("テスト").build());
            List<SearchHistoryResponse> expected = List.of(
                    new SearchHistoryResponse(1L, "テスト", LocalDateTime.now()));

            given(searchHistoryRepository.findByUserIdOrderBySearchedAtDesc(USER_ID)).willReturn(entities);
            given(searchMapper.toHistoryResponseList(entities)).willReturn(expected);

            // when
            List<SearchHistoryResponse> result = searchHistoryService.listHistory(USER_ID);

            // then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // deleteAllHistory
    // ========================================
    @Nested
    @DisplayName("deleteAllHistory")
    class DeleteAllHistory {

        @Test
        @DisplayName("正常系: 検索履歴を全削除できる")
        void 検索履歴を全削除できる() {
            // when
            searchHistoryService.deleteAllHistory(USER_ID);

            // then
            verify(searchHistoryRepository).deleteByUserId(USER_ID);
        }
    }

    // ========================================
    // deleteHistory
    // ========================================
    @Nested
    @DisplayName("deleteHistory")
    class DeleteHistory {

        @Test
        @DisplayName("正常系: 検索履歴を個別削除できる")
        void 検索履歴を個別削除できる() {
            // given
            SearchHistoryEntity entity = SearchHistoryEntity.builder()
                    .userId(USER_ID).query("テスト").build();
            given(searchHistoryRepository.findByIdAndUserId(HISTORY_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            // when
            searchHistoryService.deleteHistory(USER_ID, HISTORY_ID);

            // then
            verify(searchHistoryRepository).delete(entity);
        }

        @Test
        @DisplayName("異常系: 検索履歴が見つからない場合はエラー")
        void 検索履歴が見つからない場合はエラー() {
            // given
            given(searchHistoryRepository.findByIdAndUserId(HISTORY_ID, USER_ID))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> searchHistoryService.deleteHistory(USER_ID, HISTORY_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SearchErrorCode.HISTORY_NOT_FOUND));
        }
    }

    // ========================================
    // saveQuery
    // ========================================
    @Nested
    @DisplayName("saveQuery")
    class SaveQuery {

        @Test
        @DisplayName("正常系: 検索クエリを保存できる")
        void 検索クエリを保存できる() {
            // given
            SaveQueryRequest req = new SaveQueryRequest("マイ検索", "{\"q\":\"テスト\"}");
            SearchSavedQueryEntity saved = SearchSavedQueryEntity.builder()
                    .userId(USER_ID).name("マイ検索").queryParams("{\"q\":\"テスト\"}").build();
            SavedQueryResponse expected = new SavedQueryResponse(1L, "マイ検索", "{\"q\":\"テスト\"}", LocalDateTime.now());

            given(searchSavedQueryRepository.countByUserId(USER_ID)).willReturn(5L);
            given(searchSavedQueryRepository.save(any(SearchSavedQueryEntity.class))).willReturn(saved);
            given(searchMapper.toSavedQueryResponse(any(SearchSavedQueryEntity.class))).willReturn(expected);

            // when
            SavedQueryResponse result = searchHistoryService.saveQuery(USER_ID, req);

            // then
            assertThat(result.getName()).isEqualTo("マイ検索");
        }

        @Test
        @DisplayName("異常系: 保存済みクエリが上限の場合はエラー")
        void 保存済みクエリが上限の場合はエラー() {
            // given
            SaveQueryRequest req = new SaveQueryRequest("マイ検索", "{}");
            given(searchSavedQueryRepository.countByUserId(USER_ID)).willReturn(20L);

            // when & then
            assertThatThrownBy(() -> searchHistoryService.saveQuery(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SearchErrorCode.MAX_SAVED_QUERIES_EXCEEDED));
        }
    }

    // ========================================
    // listSavedQueries
    // ========================================
    @Nested
    @DisplayName("listSavedQueries")
    class ListSavedQueries {

        @Test
        @DisplayName("正常系: 保存済みクエリ一覧を取得できる")
        void 保存済みクエリ一覧を取得できる() {
            // given
            List<SearchSavedQueryEntity> entities = List.of(
                    SearchSavedQueryEntity.builder().userId(USER_ID).name("テスト").queryParams("{}").build());
            List<SavedQueryResponse> expected = List.of(
                    new SavedQueryResponse(1L, "テスト", "{}", LocalDateTime.now()));

            given(searchSavedQueryRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(entities);
            given(searchMapper.toSavedQueryResponseList(entities)).willReturn(expected);

            // when
            List<SavedQueryResponse> result = searchHistoryService.listSavedQueries(USER_ID);

            // then
            assertThat(result).hasSize(1);
        }
    }
}
