package com.mannschaft.app.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ApiResponse}・{@link PagedResponse}・{@link CursorPagedResponse} の単体テスト。
 * レスポンスラッパークラスの生成と属性を検証する。
 */
@DisplayName("ApiResponse / PagedResponse / CursorPagedResponse 単体テスト")
class ApiResponseTest {

    // ========================================
    // ApiResponse
    // ========================================

    @Nested
    @DisplayName("ApiResponse")
    class ApiResponseTests {

        @Test
        @DisplayName("正常系: of()でデータをラップできる")
        void apiResponse_of_データをラップ() {
            // Given
            String data = "テストデータ";

            // When
            ApiResponse<String> response = ApiResponse.of(data);

            // Then
            assertThat(response.getData()).isEqualTo("テストデータ");
        }

        @Test
        @DisplayName("境界値: nullデータでも生成できる")
        void apiResponse_nullデータ_生成できる() {
            // When
            ApiResponse<String> response = ApiResponse.of(null);

            // Then
            assertThat(response.getData()).isNull();
        }

        @Test
        @DisplayName("正常系: 複雑な型でもラップできる")
        void apiResponse_複雑な型_ラップできる() {
            // Given
            List<String> data = List.of("item1", "item2", "item3");

            // When
            ApiResponse<List<String>> response = ApiResponse.of(data);

            // Then
            assertThat(response.getData()).hasSize(3);
            assertThat(response.getData()).containsExactly("item1", "item2", "item3");
        }
    }

    // ========================================
    // PagedResponse
    // ========================================

    @Nested
    @DisplayName("PagedResponse")
    class PagedResponseTests {

        @Test
        @DisplayName("正常系: of()でデータとページメタをラップできる")
        void pagedResponse_of_データとメタをラップ() {
            // Given
            List<String> data = List.of("item1", "item2");
            PagedResponse.PageMeta meta = new PagedResponse.PageMeta(100L, 1, 20, 5);

            // When
            PagedResponse<String> response = PagedResponse.of(data, meta);

            // Then
            assertThat(response.getData()).hasSize(2);
            assertThat(response.getMeta()).isNotNull();
            assertThat(response.getMeta().getTotal()).isEqualTo(100L);
            assertThat(response.getMeta().getPage()).isEqualTo(1);
            assertThat(response.getMeta().getSize()).isEqualTo(20);
            assertThat(response.getMeta().getTotalPages()).isEqualTo(5);
        }

        @Test
        @DisplayName("正常系: 空データでも生成できる")
        void pagedResponse_空データ_生成できる() {
            // Given
            List<String> data = List.of();
            PagedResponse.PageMeta meta = new PagedResponse.PageMeta(0L, 1, 20, 0);

            // When
            PagedResponse<String> response = PagedResponse.of(data, meta);

            // Then
            assertThat(response.getData()).isEmpty();
            assertThat(response.getMeta().getTotal()).isEqualTo(0L);
            assertThat(response.getMeta().getTotalPages()).isEqualTo(0);
        }

        @Test
        @DisplayName("正常系: PagedResponseはApiResponseを継承している")
        void pagedResponse_ApiResponseを継承() {
            // Given
            List<String> data = List.of("item1");
            PagedResponse.PageMeta meta = new PagedResponse.PageMeta(1L, 1, 10, 1);

            // When
            PagedResponse<String> response = PagedResponse.of(data, meta);

            // Then
            assertThat(response).isInstanceOf(ApiResponse.class);
        }
    }

    // ========================================
    // CursorPagedResponse
    // ========================================

    @Nested
    @DisplayName("CursorPagedResponse")
    class CursorPagedResponseTests {

        @Test
        @DisplayName("正常系: of()でデータとカーソルメタをラップできる")
        void cursorPagedResponse_of_データとカーソルメタをラップ() {
            // Given
            List<String> data = List.of("msg1", "msg2");
            CursorPagedResponse.CursorMeta meta = new CursorPagedResponse.CursorMeta("msg_12345", true, 20);

            // When
            CursorPagedResponse<String> response = CursorPagedResponse.of(data, meta);

            // Then
            assertThat(response.getData()).hasSize(2);
            assertThat(response.getMeta()).isNotNull();
            assertThat(response.getMeta().getNextCursor()).isEqualTo("msg_12345");
            assertThat(response.getMeta().isHasNext()).isTrue();
            assertThat(response.getMeta().getLimit()).isEqualTo(20);
        }

        @Test
        @DisplayName("正常系: 最終ページはnextCursorがnullでhasNextがfalse")
        void cursorPagedResponse_最終ページ_nextCursorがnull() {
            // Given
            List<String> data = List.of("msg1");
            CursorPagedResponse.CursorMeta meta = new CursorPagedResponse.CursorMeta(null, false, 20);

            // When
            CursorPagedResponse<String> response = CursorPagedResponse.of(data, meta);

            // Then
            assertThat(response.getMeta().getNextCursor()).isNull();
            assertThat(response.getMeta().isHasNext()).isFalse();
        }

        @Test
        @DisplayName("正常系: CursorPagedResponseはApiResponseを継承している")
        void cursorPagedResponse_ApiResponseを継承() {
            // Given
            List<String> data = List.of();
            CursorPagedResponse.CursorMeta meta = new CursorPagedResponse.CursorMeta(null, false, 10);

            // When
            CursorPagedResponse<String> response = CursorPagedResponse.of(data, meta);

            // Then
            assertThat(response).isInstanceOf(ApiResponse.class);
        }
    }
}
