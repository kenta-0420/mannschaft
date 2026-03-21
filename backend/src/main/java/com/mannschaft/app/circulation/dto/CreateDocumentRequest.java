package com.mannschaft.app.circulation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 回覧文書作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateDocumentRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    @NotBlank
    private final String body;

    private final String circulationMode;

    private final String priority;

    private final LocalDate dueDate;

    private final Boolean reminderEnabled;

    private final Short reminderIntervalHours;

    private final String stampDisplayStyle;

    @NotNull
    @Size(min = 1)
    private final List<RecipientEntry> recipients;
}
