package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.AddBookmarkRequest;
import com.mannschaft.app.chat.dto.BookmarkResponse;
import com.mannschaft.app.chat.entity.ChatMessageBookmarkEntity;
import com.mannschaft.app.chat.repository.ChatMessageBookmarkRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * チャットブックマークサービス。メッセージのブックマーク管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatBookmarkService {

    private final ChatMessageBookmarkRepository bookmarkRepository;
    private final ChatMessageService messageService;
    private final ChatMapper chatMapper;

    /**
     * ブックマークを追加する。
     *
     * @param request ブックマーク追加リクエスト
     * @param userId  ユーザーID
     * @return ブックマークレスポンス
     */
    @Transactional
    public BookmarkResponse addBookmark(AddBookmarkRequest request, Long userId) {
        messageService.findMessageOrThrow(request.getMessageId());

        if (bookmarkRepository.existsByUserIdAndMessageId(userId, request.getMessageId())) {
            throw new BusinessException(ChatErrorCode.BOOKMARK_ALREADY_EXISTS);
        }

        ChatMessageBookmarkEntity bookmark = ChatMessageBookmarkEntity.builder()
                .messageId(request.getMessageId())
                .userId(userId)
                .note(request.getNote())
                .build();

        ChatMessageBookmarkEntity saved = bookmarkRepository.save(bookmark);
        log.info("ブックマーク追加完了: messageId={}, userId={}", request.getMessageId(), userId);
        return chatMapper.toBookmarkResponse(saved);
    }

    /**
     * ユーザーのブックマーク一覧を取得する。
     *
     * @param userId ユーザーID
     * @return ブックマークレスポンスリスト
     */
    public List<BookmarkResponse> listBookmarks(Long userId) {
        List<ChatMessageBookmarkEntity> bookmarks = bookmarkRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return chatMapper.toBookmarkResponseList(bookmarks);
    }

    /**
     * ブックマークを削除する。
     *
     * @param messageId メッセージID
     * @param userId    ユーザーID
     */
    @Transactional
    public void removeBookmark(Long messageId, Long userId) {
        bookmarkRepository.deleteByUserIdAndMessageId(userId, messageId);
        log.info("ブックマーク削除完了: messageId={}, userId={}", messageId, userId);
    }
}
