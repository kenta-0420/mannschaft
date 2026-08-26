package com.mannschaft.app.schedule.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03.16 予定コメントスレッド — {@link ScheduleCommentEntity} の単体テスト（試練・AC-27b）。
 *
 * <p>設計書: {@code docs/features/F03.16_schedule_comment_thread.md} §3.3 / §5.1 / §5.3 / §9.3 AC-27b。</p>
 *
 * <h2>0 下限ガードが表示の正しさを直接決める</h2>
 * <p>一覧のトゥームストーン述語は {@code deleted_at IS NULL OR (depth = 0 AND reply_count > 0)} である。
 * 二重削除・再試行で {@code reply_count} が負に落ちると「生存返信 &gt; 0」の判定が壊れ、
 * <b>残すべきトゥームストーンが消える／消えるべき行が残る</b>。カウンタのずれは DB を見ても
 * 「壊れている」と分からない種類の事故なので、下限を単体で固定する。</p>
 */
@DisplayName("F03.16 ScheduleCommentEntity 単体テスト（試練）")
class ScheduleCommentEntityTest {

    @Nested
    @DisplayName("AC-27b reply_count の 0 下限ガード")
    class ReplyCountGuard {

        @Test
        @DisplayName("AC-27b カウンタが 0 の状態でデクリメントしても 0 のまま（負に落ちない）")
        void 零からのデクリメントは零のまま() {
            ScheduleCommentEntity comment = topLevel();

            comment.decrementReplyCount();

            assertThat(comment.getReplyCount()).isZero();
        }

        @Test
        @DisplayName("AC-27b 同一の返信に対する削除が2回走っても reply_count は 0 未満にならない")
        void 二重削除でも負にならない() {
            ScheduleCommentEntity comment = topLevel();
            comment.incrementReplyCount();

            // 二重クリック・再試行で同じ返信の削除処理が2回走る状況。
            comment.decrementReplyCount();
            comment.decrementReplyCount();

            assertThat(comment.getReplyCount())
                    .as("負に落ちると «生存返信 > 0» のトゥームストーン判定が壊れる")
                    .isZero();
        }

        @Test
        @DisplayName("AC-27b 増減が対称に効く（3 増やして 1 減らすと 2）")
        void 増減は対称に効く() {
            ScheduleCommentEntity comment = topLevel();

            comment.incrementReplyCount();
            comment.incrementReplyCount();
            comment.incrementReplyCount();
            comment.decrementReplyCount();

            assertThat(comment.getReplyCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("状態遷移（§5.1）")
    class StateTransition {

        @Test
        @DisplayName("編集で本文が置き換わり isEdited が true になる（再編集しても true のまま・不可逆）")
        void 編集はisEditedを立てて戻らない() {
            ScheduleCommentEntity comment = topLevel();
            assertThat(comment.getIsEdited()).isFalse();

            comment.editBody("1回目の編集");
            assertThat(comment.getBody()).isEqualTo("1回目の編集");
            assertThat(comment.getIsEdited()).isTrue();

            comment.editBody("2回目の編集");
            assertThat(comment.getIsEdited())
                    .as("ACTIVE → EDITED は不可逆（一度編集したら false に戻らない）")
                    .isTrue();
        }

        @Test
        @DisplayName("論理削除は deleted_at を打ち、isDeleted が true になる（物理削除しない・原則3）")
        void 論理削除() {
            ScheduleCommentEntity comment = topLevel();
            assertThat(comment.isDeleted()).isFalse();

            comment.softDelete();

            assertThat(comment.getDeletedAt()).isNotNull();
            assertThat(comment.isDeleted()).isTrue();
            assertThat(comment.getBody())
                    .as("論理削除では列に本文が残る。API・エクスポートが他人へ返さないことは上位層の責務")
                    .isNotNull();
        }

        @Test
        @DisplayName("depth=0 はトップレベル、depth=1 はトップレベルでない（返信にトゥームストーンは無い）")
        void トップレベル判定() {
            assertThat(topLevel().isTopLevel()).isTrue();
            assertThat(ScheduleCommentEntity.builder()
                    .scheduleId(1L)
                    .userId(2L)
                    .body("返信")
                    .depth(1)
                    .build()
                    .isTopLevel()).isFalse();
        }
    }

    private ScheduleCommentEntity topLevel() {
        return ScheduleCommentEntity.builder()
                .scheduleId(1L)
                .userId(2L)
                .body("トップレベルの本文")
                .build();
    }
}
