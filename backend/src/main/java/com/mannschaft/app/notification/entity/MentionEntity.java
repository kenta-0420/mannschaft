package com.mannschaft.app.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * メンションエンティティ。
 * タイムライン投稿・チャットメッセージ・掲示板スレッド等で `@displayName` 表記により
 * 他ユーザーに対するメンションが発生したとき、対象ユーザーごとに1行作成される。
 */
@Entity
@Table(name = "mentions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MentionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** メンションされた人のユーザーID */
    @Column(nullable = false)
    private Long userId;

    /** メンションした人のユーザーID */
    @Column(nullable = false)
    private Long mentionedByUserId;

    /** POST | MESSAGE | THREAD | COMMENT */
    @Column(nullable = false, length = 20)
    private String contentType;

    /** 元コンテンツのID */
    @Column(nullable = false)
    private Long contentId;

    /** スレッドタイトル等（任意） */
    @Column(length = 200)
    private String contentTitle;

    /** 本文の抜粋（最大500文字） */
    @Column(nullable = false, length = 500)
    private String contentSnippet;

    /** 該当コンテンツへの遷移URL */
    @Column(nullable = false, length = 500)
    private String url;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    private LocalDateTime readAt;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * メンションを既読にする。
     */
    public void markAsRead() {
        this.isRead = true;
        this.readAt = LocalDateTime.now();
    }
}
