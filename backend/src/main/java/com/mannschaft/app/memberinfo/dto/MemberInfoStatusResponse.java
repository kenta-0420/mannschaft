package com.mannschaft.app.memberinfo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@AllArgsConstructor
@Builder
public class MemberInfoStatusResponse {
    private int totalMembers;
    private int completedCount;
    private int overdueCount;
    private List<MemberStatusItem> members;

    @Getter
    @AllArgsConstructor
    @Builder
    public static class MemberStatusItem {
        private Long userId;
        private String displayName;
        private List<ResponseStatusItem> responses;
    }

    @Getter
    @AllArgsConstructor
    @Builder
    public static class ResponseStatusItem {
        private Long fieldId;
        private String fieldName;
        private String value;
        private LocalDateTime confirmedAt;
        private Boolean isOverdue;
    }
}
