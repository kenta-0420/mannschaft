package com.mannschaft.app.todo.event;

import com.mannschaft.app.todo.TodoStatus;
import lombok.Getter;

/**
 * TODOステータス変更イベント。ダッシュボードウィジェットのリアルタイム更新に使用する。
 *
 * <p>F02.3.1 Phase 2: {@code fromHandoff} フラグを追加。キャッチボール由来の
 * ステータス変更では、別途 {@link TodoHandoffEvent} 経由で通知が飛ぶため、
 * リスナー側で {@code fromHandoff == true} のステータス通知を抑制する想定。</p>
 */
@Getter
public class TodoStatusChangedEvent {

    private final Long todoId;
    private final Long projectId;
    private final TodoStatus oldStatus;
    private final TodoStatus newStatus;
    private final Long userId;

    /** キャッチボール由来かどうか。デフォルト false（既存通常変更）。 */
    private final boolean fromHandoff;

    /**
     * 通常のステータス変更用コンストラクタ（後方互換、fromHandoff=false 固定）。
     */
    public TodoStatusChangedEvent(Long todoId, Long projectId, TodoStatus oldStatus,
                                   TodoStatus newStatus, Long userId) {
        this(todoId, projectId, oldStatus, newStatus, userId, false);
    }

    /**
     * fromHandoff フラグを明示できるコンストラクタ（F02.3.1 Phase 2）。
     */
    public TodoStatusChangedEvent(Long todoId, Long projectId, TodoStatus oldStatus,
                                   TodoStatus newStatus, Long userId, boolean fromHandoff) {
        this.todoId = todoId;
        this.projectId = projectId;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.userId = userId;
        this.fromHandoff = fromHandoff;
    }
}
