package com.mannschaft.app.todo.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * マイカレンダー表示用の、自分に割り当てられた TODO の縮約レスポンス。
 *
 * <p>予定と同じ日付面に置くため、開始日未設定の TODO も期限日を基準に返す。
 * {@code linkedScheduleId} はクライアントが可視予定との二重表示を避けるための識別子であり、
 * この API 自身は予定の可視性を判定しない。</p>
 */
public record CalendarTodoResponse(
        Long id,
        String title,
        LocalDate startDate,
        LocalDate dueDate,
        LocalTime dueTime,
        String status,
        String priority,
        String scopeType,
        Long scopeId,
        String scopeSlug,
        String scopeName,
        Long linkedScheduleId
) {
}
