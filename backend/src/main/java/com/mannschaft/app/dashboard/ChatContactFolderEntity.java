package com.mannschaft.app.dashboard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * チャット・連絡先カスタムフォルダエンティティ。
 * ユーザーが DM や連絡先を分類するために自由に作成するフォルダ。
 * 1ユーザーあたり最大20フォルダまで。フォルダ名はユーザー内で一意。
 */
@Entity
@Table(name = "chat_contact_folders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ChatContactFolderEntity {

    private static final int MAX_FOLDERS_PER_USER = 20;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 30)
    private String icon;

    @Column(length = 7)
    private String color;

    @Column(nullable = false)
    private Integer sortOrder;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.sortOrder == null) {
            this.sortOrder = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * フォルダ情報を更新する。
     */
    public void update(String name, String icon, String color, Integer sortOrder) {
        this.name = name;
        if (icon != null) {
            this.icon = icon;
        }
        if (color != null) {
            this.color = color;
        }
        if (sortOrder != null) {
            this.sortOrder = sortOrder;
        }
    }

    /**
     * 1ユーザーあたりのフォルダ上限数を返す。
     */
    public static int getMaxFoldersPerUser() {
        return MAX_FOLDERS_PER_USER;
    }
}
