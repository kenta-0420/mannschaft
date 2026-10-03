package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.TimelineMapper;
import com.mannschaft.app.timeline.dto.BookmarkResponse;
import com.mannschaft.app.timeline.entity.TimelineBookmarkEntity;
import com.mannschaft.app.timeline.repository.TimelineBookmarkRepository;
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
    private final TimelineMapper timelineMapper;
    /**
     * 認可根治 Wave7: 対象投稿が呼び出し元から可視であることを検証する（投稿本体と同一の正準判定）。
     * ブックマーク自体は呼び出し元本人のデータのみを操作する自己スコープ操作だが、
     * 対象投稿が呼び出し元から可視な scope に属することを確認してから登録する。
     */
    private final TimelinePostVisibilityAccessGuard postVisibilityGuard;

    /**
     * 投稿をブックマークする。
     *
     * <p><b>認可根治 Wave7</b>: {@link TimelinePostVisibilityAccessGuard} で対象投稿の可視性を
     * 検証してから処理する（不可視・不存在は {@link TimelineErrorCode#POST_NOT_FOUND}）。</p>
     *
     * @param postId 投稿ID
     * @param userId ユーザーID
     * @return 作成されたブックマーク
     */
    @Transactional
    public BookmarkResponse addBookmark(Long postId, Long userId) {
        postVisibilityGuard.requireVisiblePost(postId, userId);

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
