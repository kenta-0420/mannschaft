package com.mannschaft.app.circulation;

import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CirculationDocumentEntity} の単体テスト。
 */
@DisplayName("CirculationDocumentEntity 単体テスト")
class CirculationDocumentEntityTest {

    private CirculationDocumentEntity createDraft() {
        return CirculationDocumentEntity.builder()
                .scopeType("TEAM")
                .scopeId(1L)
                .createdBy(10L)
                .title("テスト文書")
                .body("本文")
                .build();
    }

    // ========================================
    // activate / isActive
    // ========================================

    @Nested
    @DisplayName("activate / isActive")
    class Activate {

        @Test
        @DisplayName("正常系: DRAFTをACTIVEにする")
        void DRAFT_activate_ACTIVE() {
            CirculationDocumentEntity entity = createDraft();

            entity.activate();

            assertThat(entity.getStatus()).isEqualTo(CirculationStatus.ACTIVE);
            assertThat(entity.isActive()).isTrue();
        }
    }

    // ========================================
    // complete
    // ========================================

    @Nested
    @DisplayName("complete")
    class Complete {

        @Test
        @DisplayName("正常系: COMPLETEDに変更されcompletedAtが設定される")
        void complete_COMPLETED_completedAt設定() {
            CirculationDocumentEntity entity = createDraft();
            entity.activate();

            entity.complete();

            assertThat(entity.getStatus()).isEqualTo(CirculationStatus.COMPLETED);
            assertThat(entity.getCompletedAt()).isNotNull();
        }
    }

    // ========================================
    // cancel
    // ========================================

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        @DisplayName("正常系: CANCELLEDに変更される")
        void cancel_CANCELLED() {
            CirculationDocumentEntity entity = createDraft();
            entity.activate();

            entity.cancel();

            assertThat(entity.getStatus()).isEqualTo(CirculationStatus.CANCELLED);
        }
    }

    // ========================================
    // updateRecipientCount
    // ========================================

    @Nested
    @DisplayName("updateRecipientCount")
    class UpdateRecipientCount {

        @Test
        @DisplayName("正常系: 受信者数が更新される")
        void 受信者数更新_正常() {
            CirculationDocumentEntity entity = createDraft();

            entity.updateRecipientCount(5);

            assertThat(entity.getTotalRecipientCount()).isEqualTo(5);
        }
    }

    // ========================================
    // incrementStampedCount
    // ========================================

    @Nested
    @DisplayName("incrementStampedCount")
    class IncrementStampedCount {

        @Test
        @DisplayName("正常系: 押印数がインクリメントされる")
        void 押印数インクリメント_正常() {
            CirculationDocumentEntity entity = createDraft();

            entity.incrementStampedCount();

            assertThat(entity.getStampedCount()).isEqualTo(1);
        }
    }

    // ========================================
    // incrementAttachmentCount / decrementAttachmentCount
    // ========================================

    @Nested
    @DisplayName("attachmentCount操作")
    class AttachmentCount {

        @Test
        @DisplayName("正常系: 添付ファイル数がインクリメントされる")
        void 添付ファイル数インクリメント_正常() {
            CirculationDocumentEntity entity = createDraft();

            entity.incrementAttachmentCount();

            assertThat(entity.getAttachmentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系: 添付ファイル数がデクリメントされる")
        void 添付ファイル数デクリメント_正常() {
            CirculationDocumentEntity entity = createDraft();
            entity.incrementAttachmentCount();

            entity.decrementAttachmentCount();

            assertThat(entity.getAttachmentCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("正常系: 0のときはデクリメントされない")
        void 添付ファイル数0でデクリメント_変化なし() {
            CirculationDocumentEntity entity = createDraft();

            entity.decrementAttachmentCount();

            assertThat(entity.getAttachmentCount()).isEqualTo(0);
        }
    }

    // ========================================
    // incrementCommentCount / decrementCommentCount
    // ========================================

    @Nested
    @DisplayName("commentCount操作")
    class CommentCount {

        @Test
        @DisplayName("正常系: コメント数がインクリメントされる")
        void コメント数インクリメント_正常() {
            CirculationDocumentEntity entity = createDraft();

            entity.incrementCommentCount();

            assertThat(entity.getCommentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系: コメント数がデクリメントされる")
        void コメント数デクリメント_正常() {
            CirculationDocumentEntity entity = createDraft();
            entity.incrementCommentCount();

            entity.decrementCommentCount();

            assertThat(entity.getCommentCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("正常系: 0のときはデクリメントされない")
        void コメント数0でデクリメント_変化なし() {
            CirculationDocumentEntity entity = createDraft();

            entity.decrementCommentCount();

            assertThat(entity.getCommentCount()).isEqualTo(0);
        }
    }

    // ========================================
    // updateContent
    // ========================================

    @Nested
    @DisplayName("updateContent")
    class UpdateContent {

        @Test
        @DisplayName("正常系: タイトルと本文が更新される")
        void タイトル本文更新_正常() {
            CirculationDocumentEntity entity = createDraft();

            entity.updateContent("新タイトル", "新本文");

            assertThat(entity.getTitle()).isEqualTo("新タイトル");
            assertThat(entity.getBody()).isEqualTo("新本文");
        }
    }

    // ========================================
    // updateSettings
    // ========================================

    @Nested
    @DisplayName("updateSettings")
    class UpdateSettings {

        @Test
        @DisplayName("正常系: 設定が更新される")
        void 設定更新_正常() {
            CirculationDocumentEntity entity = createDraft();
            LocalDate dueDate = LocalDate.of(2026, 6, 30);

            entity.updateSettings(CirculationPriority.HIGH, dueDate, true, (short) 12, StampDisplayStyle.COMPACT);

            assertThat(entity.getPriority()).isEqualTo(CirculationPriority.HIGH);
            assertThat(entity.getDueDate()).isEqualTo(dueDate);
            assertThat(entity.getReminderEnabled()).isTrue();
            assertThat(entity.getReminderIntervalHours()).isEqualTo((short) 12);
            assertThat(entity.getStampDisplayStyle()).isEqualTo(StampDisplayStyle.COMPACT);
        }
    }

    // ========================================
    // isAllStamped
    // ========================================

    @Nested
    @DisplayName("isAllStamped")
    class IsAllStamped {

        @Test
        @DisplayName("正常系: 全員押印済みでtrueが返却される")
        void 全員押印済み_true() {
            CirculationDocumentEntity entity = createDraft();
            entity.updateRecipientCount(2);
            entity.incrementStampedCount();
            entity.incrementStampedCount();

            assertThat(entity.isAllStamped()).isTrue();
        }

        @Test
        @DisplayName("正常系: 一部未押印でfalseが返却される")
        void 一部未押印_false() {
            CirculationDocumentEntity entity = createDraft();
            entity.updateRecipientCount(3);
            entity.incrementStampedCount();

            assertThat(entity.isAllStamped()).isFalse();
        }

        @Test
        @DisplayName("正常系: 受信者0人のときはfalseが返却される")
        void 受信者0人_false() {
            CirculationDocumentEntity entity = createDraft();

            assertThat(entity.isAllStamped()).isFalse();
        }
    }

    // ========================================
    // isEditable
    // ========================================

    @Nested
    @DisplayName("isEditable")
    class IsEditable {

        @Test
        @DisplayName("正常系: DRAFTのときはtrueが返却される")
        void DRAFT_true() {
            CirculationDocumentEntity entity = createDraft();

            assertThat(entity.isEditable()).isTrue();
        }

        @Test
        @DisplayName("正常系: ACTIVE のときはfalseが返却される")
        void ACTIVE_false() {
            CirculationDocumentEntity entity = createDraft();
            entity.activate();

            assertThat(entity.isEditable()).isFalse();
        }
    }

    // ========================================
    // softDelete
    // ========================================

    @Nested
    @DisplayName("softDelete")
    class SoftDelete {

        @Test
        @DisplayName("正常系: 論理削除後deletedAtが設定される")
        void 論理削除_deletedAt設定() {
            CirculationDocumentEntity entity = createDraft();

            entity.softDelete();

            assertThat(entity.getDeletedAt()).isNotNull();
        }
    }
}
