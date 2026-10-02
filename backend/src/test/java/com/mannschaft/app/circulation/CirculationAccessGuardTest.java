package com.mannschaft.app.circulation;

import com.mannschaft.app.circulation.entity.CirculationCommentEntity;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.service.CirculationAccessGuard;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CirculationAccessGuard} の単体テスト。
 *
 * <p>押印系（{@code CirculationStampController#stamp} / {@code #skip} / {@code #reject} /
 * {@code #correctStamp} / {@code #delegateStamp}）と
 * コメント編集・削除（{@code CirculationCommentController#deleteComment}）の本人性判定が、
 * <b>実体が属する文書</b>で行われることを固定する。</p>
 */
@DisplayName("CirculationAccessGuard 単体テスト")
class CirculationAccessGuardTest {

    private static final Long DOCUMENT_ID = 100L;
    private static final Long OTHER_DOCUMENT_ID = 101L;
    private static final Long USER_ID = 900_000_001L;
    private static final Long OTHER_USER_ID = 900_000_002L;

    private final CirculationAccessGuard guard = new CirculationAccessGuard();

    @Nested
    @DisplayName("requireRecipientSelf")
    class RequireRecipientSelf {

        @Test
        @DisplayName("当該文書の自分の受信者行なら通過する")
        void 自分の受信者行なら通過する() {
            assertThatCode(() -> guard.requireRecipientSelf(
                    document(DOCUMENT_ID), recipient(DOCUMENT_ID, USER_ID), USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("他人の受信者行は拒否される")
        void 他人の受信者行は拒否される() {
            assertThatThrownBy(() -> guard.requireRecipientSelf(
                    document(DOCUMENT_ID), recipient(DOCUMENT_ID, OTHER_USER_ID), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.RECIPIENT_NOT_FOUND));
        }

        @Test
        @DisplayName("別文書に属する受信者行は拒否される")
        void 別文書の受信者行は拒否される() {
            assertThatThrownBy(() -> guard.requireRecipientSelf(
                    document(DOCUMENT_ID), recipient(OTHER_DOCUMENT_ID, USER_ID), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.RECIPIENT_NOT_FOUND));
        }

        @Test
        @DisplayName("受信者行が無い場合は拒否される")
        void 受信者行が無い場合は拒否される() {
            assertThatThrownBy(() -> guard.requireRecipientSelf(document(DOCUMENT_ID), null, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("requireCommentAuthor")
    class RequireCommentAuthor {

        @Test
        @DisplayName("当該文書の自分のコメントなら通過する")
        void 自分のコメントなら通過する() {
            assertThatCode(() -> guard.requireCommentAuthor(
                    comment(DOCUMENT_ID, USER_ID), DOCUMENT_ID, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("他人のコメントは COMMENT_NOT_OWNED で拒否される")
        void 他人のコメントは拒否される() {
            assertThatThrownBy(() -> guard.requireCommentAuthor(
                    comment(DOCUMENT_ID, OTHER_USER_ID), DOCUMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.COMMENT_NOT_OWNED));
        }

        @Test
        @DisplayName("別文書に属するコメントは COMMENT_NOT_FOUND で拒否される")
        void 別文書のコメントは拒否される() {
            assertThatThrownBy(() -> guard.requireCommentAuthor(
                    comment(OTHER_DOCUMENT_ID, USER_ID), DOCUMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.COMMENT_NOT_FOUND));
        }
    }

    // ─────────────────────────────────────────────
    // フィクスチャ
    // ─────────────────────────────────────────────

    private static CirculationDocumentEntity document(Long id) {
        CirculationDocumentEntity entity = CirculationDocumentEntity.builder()
                .scopeType("TEAM").scopeId(1L).createdBy(1L)
                .title("テスト").body("本文").build();
        setId(entity, id);
        return entity;
    }

    private static CirculationRecipientEntity recipient(Long documentId, Long userId) {
        return CirculationRecipientEntity.builder()
                .documentId(documentId).userId(userId).sortOrder(0).build();
    }

    private static CirculationCommentEntity comment(Long documentId, Long userId) {
        return CirculationCommentEntity.builder()
                .documentId(documentId).userId(userId).body("コメント").build();
    }

    /** 継承フィールド {@code id} をリフレクションで設定する（永続化せずに ID 比較を検証するため）。 */
    private static void setId(Object entity, Long id) {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField("id");
                field.setAccessible(true);
                field.set(entity, id);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("id フィールドが見つかりません");
    }
}
