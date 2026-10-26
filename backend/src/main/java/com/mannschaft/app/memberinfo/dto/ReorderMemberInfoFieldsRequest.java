package com.mannschaft.app.memberinfo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ReorderMemberInfoFieldsRequest {
    @NotNull
    private List<FieldOrder> orders;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldOrder {
        @NotNull
        private Long fieldId;
        @NotNull
        private Integer sortOrder;
    }
}
