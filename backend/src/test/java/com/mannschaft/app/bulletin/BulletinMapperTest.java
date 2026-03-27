package com.mannschaft.app.bulletin;

import com.mannschaft.app.bulletin.dto.CategoryResponse;
import com.mannschaft.app.bulletin.dto.ReplyResponse;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.entity.BulletinReplyEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BulletinMapper} の単体テスト。
 * MapStruct生成実装によるEntity→DTO変換を検証する。
 */
@DisplayName("BulletinMapper 単体テスト")
class BulletinMapperTest {

    private final BulletinMapper mapper = Mappers.getMapper(BulletinMapper.class);

    // ========================================
    // BulletinCategoryEntity → CategoryResponse
    // ========================================

    @Nested
    @DisplayName("toCategoryResponse")
    class ToCategoryResponse {

        @Test
        @DisplayName("正常系: エンティティがDTOに変換される")
        void 変換_正常_フィールドが正しくマップされる() {
            // Given
            BulletinCategoryEntity entity = BulletinCategoryEntity.builder()
                    .scopeType(ScopeType.TEAM)
                    .scopeId(10L)
                    .name("一般")
                    .description("一般的な話題")
                    .displayOrder(1)
                    .color("#FF5733")
                    .postMinRole("MEMBER")
                    .createdBy(1L)
                    .build();

            // When
            CategoryResponse response = mapper.toCategoryResponse(entity);

            // Then
            assertThat(response.getScopeType()).isEqualTo("TEAM");
            assertThat(response.getScopeId()).isEqualTo(10L);
            assertThat(response.getName()).isEqualTo("一般");
            assertThat(response.getDescription()).isEqualTo("一般的な話題");
            assertThat(response.getDisplayOrder()).isEqualTo(1);
            assertThat(response.getColor()).isEqualTo("#FF5733");
            assertThat(response.getPostMinRole()).isEqualTo("MEMBER");
            assertThat(response.getCreatedBy()).isEqualTo(1L);
        }

        @Test
        @DisplayName("正常系: ORGANIZATIONスコープが正しく変換される")
        void 変換_ORGANIZATIONスコープ_String変換() {
            // Given
            BulletinCategoryEntity entity = BulletinCategoryEntity.builder()
                    .scopeType(ScopeType.ORGANIZATION)
                    .scopeId(20L)
                    .name("お知らせ")
                    .description("組織からのお知らせ")
                    .displayOrder(0)
                    .postMinRole("MEMBER_PLUS")
                    .createdBy(2L)
                    .build();

            // When
            CategoryResponse response = mapper.toCategoryResponse(entity);

            // Then
            assertThat(response.getScopeType()).isEqualTo("ORGANIZATION");
            assertThat(response.getScopeId()).isEqualTo(20L);
        }
    }

    @Nested
    @DisplayName("toCategoryResponseList")
    class ToCategoryResponseList {

        @Test
        @DisplayName("正常系: 複数エンティティのリストが変換される")
        void 変換_複数_全件変換() {
            // Given
            List<BulletinCategoryEntity> entities = List.of(
                    BulletinCategoryEntity.builder()
                            .scopeType(ScopeType.TEAM).scopeId(1L)
                            .name("カテゴリA").description("A説明").displayOrder(1)
                            .postMinRole("MEMBER").createdBy(1L).build(),
                    BulletinCategoryEntity.builder()
                            .scopeType(ScopeType.TEAM).scopeId(1L)
                            .name("カテゴリB").description("B説明").displayOrder(2)
                            .postMinRole("ADMIN").createdBy(1L).build()
            );

            // When
            List<CategoryResponse> responses = mapper.toCategoryResponseList(entities);

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getName()).isEqualTo("カテゴリA");
            assertThat(responses.get(1).getName()).isEqualTo("カテゴリB");
        }

        @Test
        @DisplayName("正常系: 空リストの場合空リストが返る")
        void 変換_空リスト_空リスト返却() {
            // When
            List<CategoryResponse> responses = mapper.toCategoryResponseList(List.of());

            // Then
            assertThat(responses).isEmpty();
        }
    }

    // ========================================
    // BulletinThreadEntity → ThreadResponse
    // ========================================

    @Nested
    @DisplayName("toThreadResponse")
    class ToThreadResponse {

        @Test
        @DisplayName("正常系: エンティティがDTOに変換される")
        void 変換_正常_フィールドが正しくマップされる() {
            // Given
            BulletinThreadEntity entity = BulletinThreadEntity.builder()
                    .categoryId(5L)
                    .scopeType(ScopeType.TEAM)
                    .scopeId(1L)
                    .authorId(10L)
                    .title("テストスレッド")
                    .body("スレッドの本文")
                    .priority(Priority.IMPORTANT)
                    .readTrackingMode(ReadTrackingMode.INDIVIDUAL)
                    .build();

            // When
            ThreadResponse response = mapper.toThreadResponse(entity);

            // Then
            assertThat(response.getCategoryId()).isEqualTo(5L);
            assertThat(response.getScopeType()).isEqualTo("TEAM");
            assertThat(response.getScopeId()).isEqualTo(1L);
            assertThat(response.getAuthorId()).isEqualTo(10L);
            assertThat(response.getTitle()).isEqualTo("テストスレッド");
            assertThat(response.getBody()).isEqualTo("スレッドの本文");
            assertThat(response.getPriority()).isEqualTo("IMPORTANT");
            assertThat(response.getReadTrackingMode()).isEqualTo("INDIVIDUAL");
            assertThat(response.getIsPinned()).isFalse();
            assertThat(response.getIsLocked()).isFalse();
            assertThat(response.getIsArchived()).isFalse();
            assertThat(response.getReplyCount()).isZero();
        }

        @Test
        @DisplayName("正常系: INFOプライオリティとCOUNT_ONLYトラッキングが変換される")
        void 変換_INFO_COUNT_ONLY_String変換() {
            // Given
            BulletinThreadEntity entity = BulletinThreadEntity.builder()
                    .categoryId(1L)
                    .scopeType(ScopeType.ORGANIZATION)
                    .scopeId(2L)
                    .authorId(5L)
                    .title("組織スレッド")
                    .body("組織の本文")
                    .priority(Priority.INFO)
                    .readTrackingMode(ReadTrackingMode.COUNT_ONLY)
                    .build();

            // When
            ThreadResponse response = mapper.toThreadResponse(entity);

            // Then
            assertThat(response.getScopeType()).isEqualTo("ORGANIZATION");
            assertThat(response.getPriority()).isEqualTo("INFO");
            assertThat(response.getReadTrackingMode()).isEqualTo("COUNT_ONLY");
        }

        @Test
        @DisplayName("正常系: ピン留め・ロック済みスレッドが変換される")
        void 変換_ピン留めロック済み_フラグが正しく変換される() {
            // Given
            BulletinThreadEntity entity = BulletinThreadEntity.builder()
                    .categoryId(1L)
                    .scopeType(ScopeType.TEAM)
                    .scopeId(1L)
                    .authorId(1L)
                    .title("ピン留めスレッド")
                    .body("ピン留めの本文")
                    .priority(Priority.URGENT)
                    .readTrackingMode(ReadTrackingMode.COUNT_ONLY)
                    .build();
            entity.togglePin();
            entity.toggleLock();

            // When
            ThreadResponse response = mapper.toThreadResponse(entity);

            // Then
            assertThat(response.getIsPinned()).isTrue();
            assertThat(response.getIsLocked()).isTrue();
            assertThat(response.getPriority()).isEqualTo("URGENT");
        }
    }

    @Nested
    @DisplayName("toThreadResponseList")
    class ToThreadResponseList {

        @Test
        @DisplayName("正常系: 複数エンティティのリストが変換される")
        void 変換_複数_全件変換() {
            // Given
            List<BulletinThreadEntity> entities = List.of(
                    BulletinThreadEntity.builder()
                            .categoryId(1L).scopeType(ScopeType.TEAM).scopeId(1L)
                            .authorId(10L).title("スレッド1").body("本文1")
                            .priority(Priority.INFO).readTrackingMode(ReadTrackingMode.COUNT_ONLY).build(),
                    BulletinThreadEntity.builder()
                            .categoryId(2L).scopeType(ScopeType.TEAM).scopeId(1L)
                            .authorId(10L).title("スレッド2").body("本文2")
                            .priority(Priority.IMPORTANT).readTrackingMode(ReadTrackingMode.INDIVIDUAL).build()
            );

            // When
            List<ThreadResponse> responses = mapper.toThreadResponseList(entities);

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getTitle()).isEqualTo("スレッド1");
            assertThat(responses.get(1).getTitle()).isEqualTo("スレッド2");
        }

        @Test
        @DisplayName("正常系: 空リストの場合空リストが返る")
        void 変換_空リスト_空リスト返却() {
            // When
            List<ThreadResponse> responses = mapper.toThreadResponseList(List.of());

            // Then
            assertThat(responses).isEmpty();
        }
    }

    // ========================================
    // BulletinReplyEntity → ReplyResponse
    // ========================================

    @Nested
    @DisplayName("toReplyResponse（子返信なし）")
    class ToReplyResponseNoChildren {

        @Test
        @DisplayName("正常系: 子返信なしで変換される")
        void 変換_子返信なし_emptyChildrenリスト() {
            // Given
            BulletinReplyEntity entity = BulletinReplyEntity.builder()
                    .threadId(100L)
                    .parentId(null)
                    .authorId(10L)
                    .body("返信の本文")
                    .build();

            // When
            ReplyResponse response = mapper.toReplyResponse(entity);

            // Then
            assertThat(response.getThreadId()).isEqualTo(100L);
            assertThat(response.getParentId()).isNull();
            assertThat(response.getAuthorId()).isEqualTo(10L);
            assertThat(response.getBody()).isEqualTo("返信の本文");
            assertThat(response.getIsEdited()).isFalse();
            assertThat(response.getReplyCount()).isZero();
            assertThat(response.getChildren()).isEmpty();
        }

        @Test
        @DisplayName("正常系: 編集済み返信が変換される")
        void 変換_編集済み_isEditedがtrue() {
            // Given
            BulletinReplyEntity entity = BulletinReplyEntity.builder()
                    .threadId(100L)
                    .parentId(50L)
                    .authorId(20L)
                    .body("元の本文")
                    .build();
            entity.updateBody("編集後の本文");

            // When
            ReplyResponse response = mapper.toReplyResponse(entity);

            // Then
            assertThat(response.getBody()).isEqualTo("編集後の本文");
            assertThat(response.getIsEdited()).isTrue();
            assertThat(response.getParentId()).isEqualTo(50L);
        }
    }

    @Nested
    @DisplayName("toReplyResponse（子返信あり）")
    class ToReplyResponseWithChildren {

        @Test
        @DisplayName("正常系: 子返信付きで変換される")
        void 変換_子返信あり_childrenリストが含まれる() {
            // Given
            BulletinReplyEntity entity = BulletinReplyEntity.builder()
                    .threadId(100L)
                    .parentId(null)
                    .authorId(10L)
                    .body("親返信の本文")
                    .build();

            BulletinReplyEntity childEntity = BulletinReplyEntity.builder()
                    .threadId(100L)
                    .parentId(null) // parentId is set via replyMapper logic
                    .authorId(20L)
                    .body("子返信の本文")
                    .build();

            ReplyResponse childResponse = mapper.toReplyResponse(childEntity);

            // When
            ReplyResponse response = mapper.toReplyResponse(entity, List.of(childResponse));

            // Then
            assertThat(response.getBody()).isEqualTo("親返信の本文");
            assertThat(response.getChildren()).hasSize(1);
            assertThat(response.getChildren().get(0).getBody()).isEqualTo("子返信の本文");
        }

        @Test
        @DisplayName("正常系: 空の子返信リストで変換される")
        void 変換_子返信空_emptyChildrenリスト() {
            // Given
            BulletinReplyEntity entity = BulletinReplyEntity.builder()
                    .threadId(100L)
                    .parentId(null)
                    .authorId(10L)
                    .body("本文")
                    .build();

            // When
            ReplyResponse response = mapper.toReplyResponse(entity, List.of());

            // Then
            assertThat(response.getChildren()).isEmpty();
        }
    }
}
