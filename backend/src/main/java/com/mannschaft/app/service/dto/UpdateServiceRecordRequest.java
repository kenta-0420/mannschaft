package com.mannschaft.app.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

/**
 * サービス記録更新リクエスト。
 */
@Getter
@Setter
public class UpdateServiceRecordRequest {

    @NotNull
    private Long memberUserId;

    private Long staffUserId;

    @NotNull
    private LocalDate serviceDate;

    @NotBlank
    @Size(max = 200)
    private String title;

    private String note;

    private Integer durationMinutes;

    private List<CustomFieldValueRequest> customFields;
}
