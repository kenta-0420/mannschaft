package com.mannschaft.app.dashboard.entity;

import com.mannschaft.app.dashboard.FolderItemType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * フォルダへのアイテム（DM チャネル / 連絡先）の割り当てエンティティ。
 * 1アイテム = 1フォルダ（メールフォルダ方式）。フォルダ間移動は旧レコード DELETE + 新レコード INSERT。
 */
@Entity
@Table(name = "chat_contact_folder_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ChatContactFolderItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long folderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FolderItemType itemType;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 連絡先の任意表示名（未設定時はユーザーの display_name を使用） */
    @Column(length = 50)
    private String customName;

    /** お気に入り（ピン留め）フラグ */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isPinned = false;

    /** プライベートメモ（他ユーザーには非公開） */
    @Column(length = 500)
    private String privateNote;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 連絡先属性（カスタム表示名・ピン留め・メモ）を更新する。
     */
    public void updateAttributes(String customName, Boolean isPinned, String privateNote) {
        this.customName = customName;
        if (isPinned != null) this.isPinned = isPinned;
        this.privateNote = privateNote;
    }
}
