package com.mannschaft.app.cms;

import com.mannschaft.app.cms.entity.BlogTagEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BlogTagEntity} の単体テスト。
 */
@DisplayName("BlogTagEntity 単体テスト")
class BlogTagEntityTest {

    private BlogTagEntity createTag() {
        return BlogTagEntity.builder()
                .teamId(1L)
                .name("タグ名")
                .color("#FF0000")
                .postCount(5)
                .sortOrder(1)
                .build();
    }

    // ========================================
    // update
    // ========================================

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("正常系: タグ情報が更新される")
        void タグ情報更新_正常() {
            BlogTagEntity tag = createTag();

            tag.update("新タグ名", "#00FF00", 3);

            assertThat(tag.getName()).isEqualTo("新タグ名");
            assertThat(tag.getColor()).isEqualTo("#00FF00");
            assertThat(tag.getSortOrder()).isEqualTo(3);
        }
    }

    // ========================================
    // incrementPostCount
    // ========================================

    @Nested
    @DisplayName("incrementPostCount")
    class IncrementPostCount {

        @Test
        @DisplayName("正常系: 記事数がインクリメントされる")
        void 記事数インクリメント_正常() {
            BlogTagEntity tag = createTag();
            int before = tag.getPostCount();

            tag.incrementPostCount();

            assertThat(tag.getPostCount()).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("正常系: 0から1にインクリメントされる")
        void 記事数0から1インクリメント_正常() {
            BlogTagEntity tag = BlogTagEntity.builder()
                    .teamId(1L)
                    .name("タグ")
                    .postCount(0)
                    .build();

            tag.incrementPostCount();

            assertThat(tag.getPostCount()).isEqualTo(1);
        }
    }

    // ========================================
    // decrementPostCount
    // ========================================

    @Nested
    @DisplayName("decrementPostCount")
    class DecrementPostCount {

        @Test
        @DisplayName("正常系: 記事数がデクリメントされる")
        void 記事数デクリメント_正常() {
            BlogTagEntity tag = createTag();

            tag.decrementPostCount();

            assertThat(tag.getPostCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("正常系: 0のときはデクリメントされない")
        void 記事数0でデクリメント_変化なし() {
            BlogTagEntity tag = BlogTagEntity.builder()
                    .teamId(1L)
                    .name("タグ")
                    .postCount(0)
                    .build();

            tag.decrementPostCount();

            assertThat(tag.getPostCount()).isEqualTo(0);
        }
    }

    // ========================================
    // Default values
    // ========================================

    @Nested
    @DisplayName("デフォルト値")
    class DefaultValues {

        @Test
        @DisplayName("正常系: デフォルト値が正しく設定される")
        void デフォルト値_正常() {
            BlogTagEntity tag = BlogTagEntity.builder()
                    .teamId(1L)
                    .name("タグ名")
                    .build();

            assertThat(tag.getColor()).isEqualTo("#6B7280");
            assertThat(tag.getPostCount()).isEqualTo(0);
            assertThat(tag.getSortOrder()).isEqualTo(0);
        }
    }
}
