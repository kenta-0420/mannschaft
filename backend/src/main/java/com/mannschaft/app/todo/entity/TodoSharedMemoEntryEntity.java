package com.mannschaft.app.todo.entity;

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
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * TODO共有メモエントリエンティティ。
 * メンバー全員が参照・投稿可能なスレッド形式のメモ。論理削除あり。
 * 引用機能のため自己参照（quotedEntryId）を保持するが、@ManyToOneは使用しない。
 */
@Entity
@Table(name = "todo_shared_memo_entries")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TodoSharedMemoEntryEntity {

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

    /** 引用元エントリID。NULLの場合は引用なし。自己参照だが単純なLongカラムとして保持する。 */
    @Column(name = "quoted_entry_id")
    private Long quotedEntryId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

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

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
