package com.mannschaft.app.service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * カスタムフィールド並び替えリクエスト。
 */
@Getter
@Setter
public class FieldSortOrderRequest {

    @NotEmpty
    @Valid
    private List<FieldOrderEntry> fieldOrders;

    @Getter
    @Setter
    public static class FieldOrderEntry {
        private Long fieldId;
        private Integer sortOrder;
    }
}
