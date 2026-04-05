package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.TimelineMapper;
import com.mannschaft.app.timeline.dto.BookmarkResponse;
import com.mannschaft.app.timeline.entity.TimelineBookmarkEntity;
import com.mannschaft.app.timeline.repository.TimelineBookmarkRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * タイムラインブックマークサービス。投稿のブックマーク追加・削除・一覧取得を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelineBookmarkService {

    private static final int DEFAULT_BOOKMARK_SIZE = 20;

    private final TimelineBookmarkRepository bookmarkRepository;
    private final TimelinePostRepository postRepository;
    private final TimelineMapper timelineMapper;

    /**
     * 投稿をブックマークする。
     *
     * @param postId 投稿ID
     * @param userId ユーザーID
     * @return 作成されたブックマーク
     */
    @Transactional
    public BookmarkResponse addBookmark(Long postId, Long userId) {
        postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(TimelineErrorCode.POST_NOT_FOUND));

        if (bookmarkRepository.existsByUserIdAndTimelinePostId(userId, postId)) {
            throw new BusinessException(TimelineErrorCode.BOOKMARK_ALREADY_EXISTS);
        }

        TimelineBookmarkEntity bookmark = TimelineBookmarkEntity.builder()
                .userId(userId)
                .timelinePostId(postId)
                .build();
        bookmark = bookmarkRepository.save(bookmark);

        log.info("ブックマーク追加: postId={}, userId={}", postId, userId);
        return timelineMapper.toBookmarkResponse(bookmark);
    }

    /**
     * ブックマークを削除する。
     *
     * @param postId 投稿ID
     * @param userId ユーザーID
     */
    @Transactional
    public void removeBookmark(Long postId, Long userId) {
        TimelineBookmarkEntity bookmark = bookmarkRepository.findByUserIdAndTimelinePostId(userId, postId)
                .orElseThrow(() -> new BusinessException(TimelineErrorCode.BOOKMARK_NOT_FOUND));

        bookmarkRepository.delete(bookmark);

        log.info("ブックマーク削除: postId={}, userId={}", postId, userId);
    }

    /**
     * ユーザーのブックマーク一覧を取得する。
     *
     * @param userId ユーザーID
     * @param size   取得件数
     * @return ブックマーク一覧
     */
    public List<BookmarkResponse> getBookmarks(Long userId, int size) {
        int bookmarkSize = size > 0 ? size : DEFAULT_BOOKMARK_SIZE;
        return timelineMapper.toBookmarkResponseList(
                bookmarkRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, bookmarkSize)));
    }
}
