package com.mannschaft.app.quickmemo.dto;

import java.time.LocalDate;

/**
 * メモをTODOへ変換するリクエスト（すべてのフィールドはオプショナル）。
 */
public record ConvertToTodoRequest(
        String priority,
        LocalDate dueDate,
        Long projectId
) {}
