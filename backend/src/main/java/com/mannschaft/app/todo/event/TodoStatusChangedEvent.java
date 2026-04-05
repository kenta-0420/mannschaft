package com.mannschaft.app.todo.event;

import com.mannschaft.app.todo.TodoStatus;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * TODOステータス変更イベント。ダッシュボードウィジェットのリアルタイム更新に使用する。
 */
@Getter
@RequiredArgsConstructor
public class TodoStatusChangedEvent {

    private final Long todoId;
    private final Long projectId;
    private final TodoStatus oldStatus;
    private final TodoStatus newStatus;
    private final Long userId;
}
