package com.mannschaft.app.bulletin.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 返信レスポンスDTO。
 */
@Getter
@Builder(toBuilder = true)
@RequiredArgsConstructor
public class ReplyResponse {

    private final Long id;
    private final Long threadId;
    private final Long parentId;
    private final Long authorId;
    private final String body;
    private final Boolean isEdited;
    private final Integer replyCount;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    /** ネストの深さ（設計書 F05.1 §5）。0 = スレッド直下、最大4。 */
    private final Integer depth;
    private final List<ReplyResponse> children;
    private final ReplyAuthorResponse author;

    /** 既存のドメイン内変換用コンストラクタ。投稿者表示情報は認可・業務TXの完了後に補完する。 */
    public ReplyResponse(Long id, Long threadId, Long parentId, Long authorId, String body,
                         Boolean isEdited, Integer replyCount, LocalDateTime createdAt,
                         LocalDateTime updatedAt, Integer depth, List<ReplyResponse> children) {
        this(id, threadId, parentId, authorId, body, isEdited, replyCount, createdAt,
                updatedAt, depth, children, null);
    }
}
