package com.mannschaft.app.memberinfo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UpsertMemberInfoResponseRequest {
    @NotNull
    private List<ResponseItem> responses;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseItem {
        @NotNull
        private Long fieldId;
        private String value;
    }
}
