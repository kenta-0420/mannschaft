package com.mannschaft.app.search.service;

import com.mannschaft.app.search.SearchMapper;
import com.mannschaft.app.search.SearchMapperImpl;
import com.mannschaft.app.search.dto.SavedQueryResponse;
import com.mannschaft.app.search.dto.SearchHistoryResponse;
import com.mannschaft.app.search.entity.SearchHistoryEntity;
import com.mannschaft.app.search.entity.SearchSavedQueryEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SearchMapper}（MapStruct実装）の単体テスト。
 * Entity → DTO の変換ロジックを検証する。
 */
@DisplayName("SearchMapper 単体テスト")
class SearchMapperTest {

    private SearchMapper searchMapper;

    @BeforeEach
    void setUp() {
        searchMapper = new SearchMapperImpl();
    }

    // ========================================
    // toHistoryResponse
    // ========================================

    @Nested
    @DisplayName("toHistoryResponse")
    class ToHistoryResponse {

        @Test
        @DisplayName("正常系: SearchHistoryEntityがDTOに変換される")
        void toHistoryResponse_正常_DTOに変換() {
            // Given
            SearchHistoryEntity entity = SearchHistoryEntity.builder()
                    .userId(1L)
                    .query("テスト検索")
                    .build();
            ReflectionTestUtils.setField(entity, "id", 10L);
            ReflectionTestUtils.setField(entity, "searchedAt", LocalDateTime.of(2026, 3, 1, 10, 0));

            // When
            SearchHistoryResponse result = searchMapper.toHistoryResponse(entity);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(10L);
            assertThat(result.getQuery()).isEqualTo("テスト検索");
        }

        @Test
        @DisplayName("境界値: nullエンティティはnullを返す")
        void toHistoryResponse_null_nullを返す() {
            // When
            SearchHistoryResponse result = searchMapper.toHistoryResponse(null);

            // Then
            assertThat(result).isNull();
        }
    }

    // ========================================
    // toHistoryResponseList
    // ========================================

    @Nested
    @DisplayName("toHistoryResponseList")
    class ToHistoryResponseList {

        @Test
        @DisplayName("正常系: 複数エンティティがリストDTOに変換される")
        void toHistoryResponseList_複数エンティティ_リストに変換() {
            // Given
            SearchHistoryEntity entity1 = SearchHistoryEntity.builder()
                    .userId(1L)
                    .query("検索1")
                    .build();
            SearchHistoryEntity entity2 = SearchHistoryEntity.builder()
                    .userId(1L)
                    .query("検索2")
                    .build();

            // When
            List<SearchHistoryResponse> result = searchMapper.toHistoryResponseList(List.of(entity1, entity2));

            // Then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getQuery()).isEqualTo("検索1");
            assertThat(result.get(1).getQuery()).isEqualTo("検索2");
        }

        @Test
        @DisplayName("境界値: 空リストは空リストを返す")
        void toHistoryResponseList_空リスト_空リストを返す() {
            // When
            List<SearchHistoryResponse> result = searchMapper.toHistoryResponseList(List.of());

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("境界値: nullは空リストを返す")
        void toHistoryResponseList_null_nullを返す() {
            // When
            List<SearchHistoryResponse> result = searchMapper.toHistoryResponseList(null);

            // Then
            assertThat(result).isNull();
        }
    }

    // ========================================
    // toSavedQueryResponse
    // ========================================

    @Nested
    @DisplayName("toSavedQueryResponse")
    class ToSavedQueryResponse {

        @Test
        @DisplayName("正常系: SearchSavedQueryEntityがDTOに変換される")
        void toSavedQueryResponse_正常_DTOに変換() {
            // Given
            SearchSavedQueryEntity entity = SearchSavedQueryEntity.builder()
                    .userId(1L)
                    .name("マイ検索")
                    .queryParams("{\"q\":\"テスト\"}")
                    .build();
            ReflectionTestUtils.setField(entity, "id", 5L);

            // When
            SavedQueryResponse result = searchMapper.toSavedQueryResponse(entity);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(5L);
            assertThat(result.getName()).isEqualTo("マイ検索");
            assertThat(result.getQueryParams()).isEqualTo("{\"q\":\"テスト\"}");
        }

        @Test
        @DisplayName("境界値: nullエンティティはnullを返す")
        void toSavedQueryResponse_null_nullを返す() {
            // When
            SavedQueryResponse result = searchMapper.toSavedQueryResponse(null);

            // Then
            assertThat(result).isNull();
        }
    }

    // ========================================
    // toSavedQueryResponseList
    // ========================================

    @Nested
    @DisplayName("toSavedQueryResponseList")
    class ToSavedQueryResponseList {

        @Test
        @DisplayName("正常系: 複数エンティティがリストDTOに変換される")
        void toSavedQueryResponseList_複数エンティティ_リストに変換() {
            // Given
            SearchSavedQueryEntity entity1 = SearchSavedQueryEntity.builder()
                    .userId(1L)
                    .name("検索A")
                    .queryParams("{}")
                    .build();
            SearchSavedQueryEntity entity2 = SearchSavedQueryEntity.builder()
                    .userId(1L)
                    .name("検索B")
                    .queryParams("{\"q\":\"test\"}")
                    .build();

            // When
            List<SavedQueryResponse> result = searchMapper.toSavedQueryResponseList(List.of(entity1, entity2));

            // Then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getName()).isEqualTo("検索A");
            assertThat(result.get(1).getName()).isEqualTo("検索B");
        }

        @Test
        @DisplayName("境界値: 空リストは空リストを返す")
        void toSavedQueryResponseList_空リスト_空リストを返す() {
            // When
            List<SavedQueryResponse> result = searchMapper.toSavedQueryResponseList(List.of());

            // Then
            assertThat(result).isEmpty();
        }
    }
}
