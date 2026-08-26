package com.mannschaft.app.todo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * TODO キャッチボール（引き渡し）履歴エンティティ（F02.3.1 Phase 2）。
 *
 * <p>1回の引き渡しで以下を1行記録する:
 * <ul>
 *   <li>誰が（fromUserId）・どの担当者群から（fromAssigneeUserIds）どの担当者群へ（toAssigneeUserIds）</li>
 *   <li>どのステータス/ラベルから（previousStatus / previousStatusLabelId, ...Name）どこへ
 *       （newStatus / newStatusLabelId, ...Name）変えたか</li>
 *   <li>添えメッセージ（任意, 500 文字まで）</li>
 * </ul>
 *
 * <p>ラベル ID は FK にせず生のまま BIGINT で保持。削除されてもスナップショット名で
 * フロントは「（削除済み）」表示にフォールバックできる。</p>
 */
@Entity
@Table(name = "todo_handoffs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class TodoHandoffEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "todo_id", nullable = false)
    private Long todoId;

    @Column(name = "from_user_id", nullable = false)
    private Long fromUserId;

    /** 操作前 assignees の userId 一覧（JSON 配列を文字列で保持）。 */
    @Column(name = "from_assignee_user_ids", nullable = false, columnDefinition = "JSON")
    private String fromAssigneeUserIds;

    /** 操作後 assignees の userId 一覧（JSON 配列を文字列で保持）。 */
    @Column(name = "to_assignee_user_ids", nullable = false, columnDefinition = "JSON")
    private String toAssigneeUserIds;

    @Column(name = "previous_status", nullable = false, length = 20)
    private String previousStatus;

    @Column(name = "previous_status_label_id")
    private Long previousStatusLabelId;

    @Column(name = "previous_status_label_name", length = 50)
    private String previousStatusLabelName;

    @Column(name = "new_status", nullable = false, length = 20)
    private String newStatus;

    @Column(name = "new_status_label_id")
    private Long newStatusLabelId;

    @Column(name = "new_status_label_name", length = 50)
    private String newStatusLabelName;

    @Column(length = 500)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
