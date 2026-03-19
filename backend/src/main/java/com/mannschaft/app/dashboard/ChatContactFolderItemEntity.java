package com.mannschaft.app.dashboard;

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

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
