package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.TimelineMapper;
import com.mannschaft.app.timeline.dto.BookmarkResponse;
import com.mannschaft.app.timeline.entity.TimelineBookmarkEntity;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelineBookmarkRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link TimelineBookmarkService} の単体テスト。
 * ブックマーク追加・削除・一覧取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineBookmarkService 単体テスト")
class TimelineBookmarkServiceTest {

    @Mock
    private TimelineBookmarkRepository bookmarkRepository;

    @Mock
    private TimelinePostRepository postRepository;

    @Mock
    private TimelineMapper timelineMapper;

    @InjectMocks
    private TimelineBookmarkService timelineBookmarkService;

    private static final Long POST_ID = 1L;
    private static final Long USER_ID = 100L;

    // ========================================
    // addBookmark
    // ========================================
    @Nested
    @DisplayName("addBookmark")
    class AddBookmark {

        @Test
        @DisplayName("正常系: ブックマークを追加できる")
        void ブックマークを追加できる() {
            // given
            TimelinePostEntity post = TimelinePostEntity.builder().userId(USER_ID).build();
            TimelineBookmarkEntity bookmark = TimelineBookmarkEntity.builder()
                    .userId(USER_ID).timelinePostId(POST_ID).build();
            BookmarkResponse expected = new BookmarkResponse(1L, USER_ID, POST_ID, LocalDateTime.now());

            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(bookmarkRepository.existsByUserIdAndTimelinePostId(USER_ID, POST_ID)).willReturn(false);
            given(bookmarkRepository.save(any(TimelineBookmarkEntity.class))).willReturn(bookmark);
            given(timelineMapper.toBookmarkResponse(any(TimelineBookmarkEntity.class))).willReturn(expected);

            // when
            BookmarkResponse result = timelineBookmarkService.addBookmark(POST_ID, USER_ID);

            // then
            assertThat(result).isEqualTo(expected);
            verify(bookmarkRepository).save(any(TimelineBookmarkEntity.class));
        }

        @Test
        @DisplayName("異常系: 存在しない投稿のブックマークはエラー")
        void 存在しない投稿のブックマークはエラー() {
            // given
            given(postRepository.findById(POST_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> timelineBookmarkService.addBookmark(POST_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("異常系: 既にブックマーク済みの場合はエラー")
        void 既にブックマーク済みの場合はエラー() {
            // given
            TimelinePostEntity post = TimelinePostEntity.builder().userId(USER_ID).build();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(bookmarkRepository.existsByUserIdAndTimelinePostId(USER_ID, POST_ID)).willReturn(true);

            // when & then
            assertThatThrownBy(() -> timelineBookmarkService.addBookmark(POST_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.BOOKMARK_ALREADY_EXISTS));
        }
    }

    // ========================================
    // removeBookmark
    // ========================================
    @Nested
    @DisplayName("removeBookmark")
    class RemoveBookmark {

        @Test
        @DisplayName("正常系: ブックマークを削除できる")
        void ブックマークを削除できる() {
            // given
            TimelineBookmarkEntity bookmark = TimelineBookmarkEntity.builder()
                    .userId(USER_ID).timelinePostId(POST_ID).build();
            given(bookmarkRepository.findByUserIdAndTimelinePostId(USER_ID, POST_ID))
                    .willReturn(Optional.of(bookmark));

            // when
            timelineBookmarkService.removeBookmark(POST_ID, USER_ID);

            // then
            verify(bookmarkRepository).delete(bookmark);
        }

        @Test
        @DisplayName("異常系: ブックマークが見つからない場合はエラー")
        void ブックマークが見つからない場合はエラー() {
            // given
            given(bookmarkRepository.findByUserIdAndTimelinePostId(USER_ID, POST_ID))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> timelineBookmarkService.removeBookmark(POST_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.BOOKMARK_NOT_FOUND));
        }
    }

    // ========================================
    // getBookmarks
    // ========================================
    @Nested
    @DisplayName("getBookmarks")
    class GetBookmarks {

        @Test
        @DisplayName("正常系: ブックマーク一覧を取得できる")
        void ブックマーク一覧を取得できる() {
            // given
            List<TimelineBookmarkEntity> bookmarks = List.of(
                    TimelineBookmarkEntity.builder().userId(USER_ID).timelinePostId(POST_ID).build());
            List<BookmarkResponse> expected = List.of(
                    new BookmarkResponse(1L, USER_ID, POST_ID, LocalDateTime.now()));

            given(bookmarkRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(PageRequest.class)))
                    .willReturn(bookmarks);
            given(timelineMapper.toBookmarkResponseList(bookmarks)).willReturn(expected);

            // when
            List<BookmarkResponse> result = timelineBookmarkService.getBookmarks(USER_ID, 10);

            // then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("正常系: sizeが0以下の場合はデフォルト20件で取得する")
        void sizeが0以下の場合はデフォルトで取得する() {
            // given
            given(bookmarkRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(PageRequest.class)))
                    .willReturn(List.of());
            given(timelineMapper.toBookmarkResponseList(any())).willReturn(List.of());

            // when
            timelineBookmarkService.getBookmarks(USER_ID, 0);

            // then
            verify(bookmarkRepository).findByUserIdOrderByCreatedAtDesc(eq(USER_ID), eq(PageRequest.of(0, 20)));
        }
    }
}
