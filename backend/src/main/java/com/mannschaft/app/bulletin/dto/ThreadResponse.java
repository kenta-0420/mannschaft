package com.mannschaft.app.bulletin.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * スレッドレスポンスDTO。
 * フィールドをドメイン区分でネストし、@Builder で構築する。
 */
@Builder(toBuilder = true)
@Getter
public class ThreadResponse {

    Long id;
    ThreadScopeDto scope;
    ThreadContentDto content;
    ThreadStateDto state;
    ThreadStatsDto stats;
    ThreadSourceDto source;
    ThreadAuditDto audit;

    /** スコープ情報（カテゴリ・スコープ種別・スコープID）。 */
    public record ThreadScopeDto(Long categoryId, String scopeType, Long scopeId) {}

    /** コンテンツ情報（タイトル・本文・優先度・既読トラッキングモード）。 */
    public record ThreadContentDto(String title, String body, String priority, String readTrackingMode) {}

    /** 状態情報（ピン留め・ロック・アーカイブ・アーカイブフォルダID）。 */
    public record ThreadStateDto(Boolean isPinned, Boolean isLocked, Boolean isArchived, UUID archiveFolderId) {}

    /** 統計情報（返信数・既読数・最終返信日時）。 */
    public record ThreadStatsDto(Integer replyCount, Integer readCount, LocalDateTime lastRepliedAt) {}

    /** ソース情報（ソース種別・ソースID）。 */
    public record ThreadSourceDto(String sourceType, Long sourceId) {}

    /** 監査情報（投稿者ID・作成日時・更新日時）。 */
    public record ThreadAuditDto(Long authorId, LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
