package com.mannschaft.app.circulation.controller;

import com.mannschaft.app.circulation.CirculationErrorCode;
import com.mannschaft.app.circulation.service.CirculationCommentService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * F05.2 Phase 11 第一陣 A 分類: 回覧コメント削除 API のコントローラー単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CirculationCommentController 単体テスト (Phase 11)")
class CirculationCommentControllerTest {

    @Mock
    private CirculationCommentService commentService;

    @InjectMocks
    private CirculationCommentController commentController;

    private static final Long USER_ID = 1L;
    private static final Long DOC_ID = 100L;
    private static final Long COMMENT_ID = 500L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("DELETE コメント削除 正常系: 204 を返し Service に委譲")
    void deleteComment_204() {
        ResponseEntity<Void> response = commentController.deleteComment(DOC_ID, COMMENT_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(commentService).deleteComment(DOC_ID, COMMENT_ID, USER_ID);
    }

    @Test
    @DisplayName("DELETE コメント削除 異常系: コメント未存在で CIRCULATION_004")
    void deleteComment_notFound() {
        willThrow(new BusinessException(CirculationErrorCode.COMMENT_NOT_FOUND))
                .given(commentService).deleteComment(DOC_ID, COMMENT_ID, USER_ID);

        assertThatThrownBy(() -> commentController.deleteComment(DOC_ID, COMMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CirculationErrorCode.COMMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("DELETE コメント削除 異常系: 投稿者本人でないと CIRCULATION_010")
    void deleteComment_notOwned() {
        willThrow(new BusinessException(CirculationErrorCode.COMMENT_NOT_OWNED))
                .given(commentService).deleteComment(DOC_ID, COMMENT_ID, USER_ID);

        assertThatThrownBy(() -> commentController.deleteComment(DOC_ID, COMMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CirculationErrorCode.COMMENT_NOT_OWNED));
    }
}
