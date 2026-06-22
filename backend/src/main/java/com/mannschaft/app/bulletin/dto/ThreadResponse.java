package com.mannschaft.app.bulletin.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * スレッドレスポンス DTO（フラット構造）。
 *
 * <p>FE 型 {@code BulletinThreadResponse}（フラット）を正準とし、それに一致させる。
 * 投稿者表示名・アバター・カテゴリ名/色・既読・リアクション集計の 5 項目は
 * サービス層（{@code BulletinThreadService#enrichThreads}）でバッチ解決して inline 注入する。</p>
 *
 * <p>このクラスは継承を持たない単純 DTO のため {@code @Builder}（{@code @SuperBuilder} ではない）で構築する。
 * 継承 Entity の標準は {@code @SuperBuilder} だが、本 DTO は継承しないため対象外。</p>
 */
@Builder(toBuilder = true)
@Getter
public class ThreadResponse {

    Long id;

    // --- カテゴリ ---
    Long categoryId;
    String categoryName;
    String categoryColor;

    // --- スコープ ---
    String scopeType;
    Long scopeId;

    // --- 投稿者 ---
    AuthorDto author;

    // --- コンテンツ ---
    String title;
    String body;
    String priority;
    String readTrackingMode;

    // --- 状態 ---
    Boolean isPinned;
    Boolean isLocked;
    Boolean isArchived;
    UUID archiveFolderId;

    // --- 統計 ---
    Integer replyCount;
    Integer readCount;
    Boolean isRead;

    // --- リアクション ---
    Map<String, Integer> reactionSummary;
    List<String> myReactions;

    // --- 日時 ---
    LocalDateTime lastRepliedAt;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    // --- ソース情報（FE 未使用だが互換のため保持）---
    String sourceType;
    Long sourceId;

    /** 投稿者情報（ID・表示名・アバター URL）。 */
    public record AuthorDto(Long id, String displayName, String avatarUrl) {}
}
