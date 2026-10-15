package com.mannschaft.app.circulation;

import com.mannschaft.app.circulation.dto.AttachmentResponse;
import com.mannschaft.app.circulation.dto.CommentResponse;
import com.mannschaft.app.circulation.dto.DocumentResponse;
import com.mannschaft.app.circulation.dto.RecipientResponse;
import com.mannschaft.app.circulation.entity.CirculationAttachmentEntity;
import com.mannschaft.app.circulation.entity.CirculationCommentEntity;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CirculationMapper} の単体テスト。MapStruct生成実装を直接インスタンス化して検証する。
 */
@DisplayName("CirculationMapper 単体テスト")
class CirculationMapperTest {

    private final CirculationMapper mapper = new CirculationMapperImpl();

    // ========================================
    // toDocumentResponse
    // ========================================

    @Nested
    @DisplayName("toDocumentResponse")
    class ToDocumentResponse {

        @Test
        @DisplayName("正常系: SIMULTANEOUS/DRAFT/NORMAL/STANDARD の文書エンティティ → DTOに変換される")
        void 文書エンティティ_DTO変換_デフォルト値() {
            CirculationDocumentEntity entity = CirculationDocumentEntity.builder()
                    .scopeType("TEAM")
                    .scopeId(1L)
                    .createdBy(10L)
                    .title("回覧テスト")
                    .body("本文")
                    .build();

            DocumentResponse result = mapper.toDocumentResponse(entity);

            assertThat(result).isNotNull();
            assertThat(result.getTitle()).isEqualTo("回覧テスト");
            assertThat(result.getScopeType()).isEqualTo("TEAM");
            assertThat(result.getCirculationMode()).isEqualTo("SIMULTANEOUS");
            assertThat(result.getStatus()).isEqualTo("DRAFT");
            assertThat(result.getPriority()).isEqualTo("NORMAL");
            assertThat(result.getStampDisplayStyle()).isEqualTo("STANDARD");
        }

        @Test
        @DisplayName("正常系: SEQUENTIAL/ACTIVE/HIGH/GRID の文書エンティティ → DTOに変換される")
        void 文書エンティティ_DTO変換_非デフォルト値() {
            CirculationDocumentEntity entity = CirculationDocumentEntity.builder()
                    .scopeType("ORGANIZATION")
                    .scopeId(2L)
                    .createdBy(20L)
                    .title("組織回覧")
                    .body("組織向け本文")
                    .circulationMode(CirculationMode.SEQUENTIAL)
                    .priority(CirculationPriority.HIGH)
                    .stampDisplayStyle(StampDisplayStyle.COMPACT)
                    .dueDate(LocalDate.of(2026, 4, 1))
                    .reminderEnabled(true)
                    .reminderIntervalHours((short) 12)
                    .build();

            entity.activate();

            DocumentResponse result = mapper.toDocumentResponse(entity);

            assertThat(result.getCirculationMode()).isEqualTo("SEQUENTIAL");
            assertThat(result.getStatus()).isEqualTo("ACTIVE");
            assertThat(result.getPriority()).isEqualTo("HIGH");
            assertThat(result.getStampDisplayStyle()).isEqualTo("COMPACT");
        }

        @Test
        @DisplayName("正常系: 文書リスト変換")
        void 文書リスト_DTO変換_正常() {
            CirculationDocumentEntity e1 = CirculationDocumentEntity.builder()
                    .scopeType("TEAM").scopeId(1L).createdBy(10L)
                    .title("文書1").body("本文1").build();
            CirculationDocumentEntity e2 = CirculationDocumentEntity.builder()
                    .scopeType("TEAM").scopeId(1L).createdBy(10L)
                    .title("文書2").body("本文2").build();

            List<DocumentResponse> result = mapper.toDocumentResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getTitle()).isEqualTo("文書1");
            assertThat(result.get(1).getTitle()).isEqualTo("文書2");
        }

        @Test
        @DisplayName("正常系: 空リスト変換")
        void 文書空リスト_DTO変換_正常() {
            List<DocumentResponse> result = mapper.toDocumentResponseList(List.of());
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // toRecipientResponse
    // ========================================

    @Nested
    @DisplayName("toRecipientResponse")
    class ToRecipientResponse {

        @Test
        @DisplayName("正常系: PENDING ステータスの受信者エンティティ → DTOに変換される")
        void 受信者エンティティ_DTO変換_PENDING() {
            CirculationRecipientEntity entity = CirculationRecipientEntity.builder()
                    .documentId(100L)
                    .userId(50L)
                    .sortOrder(0)
                    .build();

            RecipientResponse result = mapper.toRecipientResponse(entity);

            assertThat(result).isNotNull();
            assertThat(result.getDocumentId()).isEqualTo(100L);
            assertThat(result.getUserId()).isEqualTo(50L);
            assertThat(result.getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("正常系: STAMPED ステータスの受信者エンティティ → DTOに変換される")
        void 受信者エンティティ_DTO変換_STAMPED() {
            CirculationRecipientEntity entity = CirculationRecipientEntity.builder()
                    .documentId(100L)
                    .userId(50L)
                    .sortOrder(1)
                    .build();
            entity.stamp(1L, "default", (short) 15, false);

            RecipientResponse result = mapper.toRecipientResponse(entity);

            assertThat(result.getStatus()).isEqualTo("STAMPED");
            assertThat(result.getSealId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("正常系: 受信者リスト変換")
        void 受信者リスト_DTO変換_正常() {
            CirculationRecipientEntity e1 = CirculationRecipientEntity.builder()
                    .documentId(1L).userId(10L).build();
            CirculationRecipientEntity e2 = CirculationRecipientEntity.builder()
                    .documentId(1L).userId(20L).build();

            List<RecipientResponse> result = mapper.toRecipientResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
        }
    }

    // ========================================
    // toAttachmentResponse
    // ========================================

    @Nested
    @DisplayName("toAttachmentResponse")
    class ToAttachmentResponse {

        @Test
        @DisplayName("正常系: 添付ファイルエンティティ → DTOに変換される")
        void 添付ファイルエンティティ_DTO変換_正常() {
            CirculationAttachmentEntity entity = CirculationAttachmentEntity.builder()
                    .documentId(100L)
                    .fileKey("uploads/doc.pdf")
                    .originalFilename("document.pdf")
                    .fileSize(1024L)
                    .mimeType("application/pdf")
                    .build();

            AttachmentResponse result = mapper.toAttachmentResponse(entity);

            assertThat(result).isNotNull();
            assertThat(result.getDocumentId()).isEqualTo(100L);
            assertThat(result.getFileKey()).isEqualTo("uploads/doc.pdf");
            assertThat(result.getOriginalFilename()).isEqualTo("document.pdf");
            assertThat(result.getFileSize()).isEqualTo(1024L);
            assertThat(result.getMimeType()).isEqualTo("application/pdf");
        }

        @Test
        @DisplayName("正常系: 添付ファイルリスト変換")
        void 添付ファイルリスト_DTO変換_正常() {
            CirculationAttachmentEntity e1 = CirculationAttachmentEntity.builder()
                    .documentId(1L).fileKey("k1").originalFilename("f1.pdf").fileSize(100L).mimeType("application/pdf").build();
            CirculationAttachmentEntity e2 = CirculationAttachmentEntity.builder()
                    .documentId(1L).fileKey("k2").originalFilename("f2.pdf").fileSize(200L).mimeType("application/pdf").build();

            List<AttachmentResponse> result = mapper.toAttachmentResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
        }
    }

    // ========================================
    // toCommentResponse
    // ========================================

    @Nested
    @DisplayName("toCommentResponse")
    class ToCommentResponse {

        @Test
        @DisplayName("正常系: コメントエンティティ → DTOに変換される")
        void コメントエンティティ_DTO変換_正常() {
            CirculationCommentEntity entity = CirculationCommentEntity.builder()
                    .documentId(100L)
                    .userId(50L)
                    .body("コメント本文")
                    .build();

            CommentResponse result = mapper.toCommentResponse(entity);

            assertThat(result).isNotNull();
            assertThat(result.getDocumentId()).isEqualTo(100L);
            assertThat(result.getUserId()).isEqualTo(50L);
            assertThat(result.getBody()).isEqualTo("コメント本文");
        }

        @Test
        @DisplayName("正常系: コメントリスト変換")
        void コメントリスト_DTO変換_正常() {
            CirculationCommentEntity e1 = CirculationCommentEntity.builder()
                    .documentId(1L).userId(10L).body("コメント1").build();
            CirculationCommentEntity e2 = CirculationCommentEntity.builder()
                    .documentId(1L).userId(20L).body("コメント2").build();

            List<CommentResponse> result = mapper.toCommentResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
        }
    }
}
