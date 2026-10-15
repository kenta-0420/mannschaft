package com.mannschaft.app.service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * サービス記録一括作成リクエスト。
 */
@Getter
@Setter
public class BulkCreateServiceRecordRequest {

    @NotNull
    private String mode;

    @NotEmpty
    @Size(max = 20)
    @Valid
    private List<CreateServiceRecordRequest> records;
}
