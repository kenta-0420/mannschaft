package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.timeline.dto.BookmarkResponse;
import com.mannschaft.app.timeline.service.TimelineBookmarkService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

/**
 * {@link TimelineBookmarkController} の単体テスト。
 *
 * <p>TimelineBookmarkController#getBookmarks / #removeBookmark の自己スコープ性を
 * 固定する契約テスト。サービス層のリポジトリクエリ（{@code findByUserId...}）は
 * {@code SecurityUtils.getCurrentUserId()} のみを検索・削除条件に束縛するため、
 * 他人のブックマークへ到達する経路が構造的に無い。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineBookmarkController 単体テスト")
class TimelineBookmarkControllerTest {

    @Mock
    private TimelineBookmarkService bookmarkService;

    @InjectMocks
    private TimelineBookmarkController controller;

    private static final Long USER_ID = 100L;
    private static final Long POST_ID = 9L;

    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUpSecurityUtils() {
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDownSecurityUtils() {
        securityUtils.close();
    }

    @Test
    @DisplayName("getBookmarks は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void getBookmarks_boundToCurrentUserOnly() {
        org.mockito.BDDMockito.given(bookmarkService.getBookmarks(USER_ID, 20)).willReturn(List.of());

        ResponseEntity<ApiResponse<List<BookmarkResponse>>> result = controller.getBookmarks(20);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 他人の userId を検索条件に渡す経路が存在しないことの裏取り。
        verify(bookmarkService).getBookmarks(USER_ID, 20);
    }

    @Test
    @DisplayName("removeBookmark は SecurityUtils.getCurrentUserId() のみを削除条件に渡す")
    void removeBookmark_boundToCurrentUserOnly() {
        doNothing().when(bookmarkService).removeBookmark(POST_ID, USER_ID);

        ResponseEntity<Void> result = controller.removeBookmark(POST_ID);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(bookmarkService).removeBookmark(POST_ID, USER_ID);
    }
}
