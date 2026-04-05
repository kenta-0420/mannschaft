package com.mannschaft.app.circulation.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * 回覧文書更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateDocumentRequest {

    @Size(max = 200)
    private final String title;

    private final String body;

    private final String priority;

    private final LocalDate dueDate;

    private final Boolean reminderEnabled;

    private final Short reminderIntervalHours;

    private final String stampDisplayStyle;
}
