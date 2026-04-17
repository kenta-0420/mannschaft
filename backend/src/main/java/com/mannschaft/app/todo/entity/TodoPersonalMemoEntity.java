package com.mannschaft.app.todo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * TODO個人メモエンティティ。
 * 1TODO × 1ユーザー = 1レコード（UPSERT運用）。
 * 本人のみ参照可能なプライベートメモ。論理削除なし（物理削除のみ）。
 */
@Entity
@Table(
    name = "todo_personal_memos",
    uniqueConstraints = @UniqueConstraint(columnNames = {"todo_id", "user_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TodoPersonalMemoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long todoId;

    @Column(nullable = false)
    private Long userId;

    /** メモ本文（DBカラム名: body）。 */
    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String memo;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * メモ本文を更新する。
     *
     * @param newMemo 新しい本文
     */
    public void updateMemo(String newMemo) {
        this.memo = newMemo;
    }
}
