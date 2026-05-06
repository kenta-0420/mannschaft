package com.mannschaft.app.memberinfo.dto;

import com.mannschaft.app.memberinfo.MemberInfoFieldType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@Builder
public class MemberInfoResponseMeItem {
    private Long fieldId;
    private String fieldName;
    private MemberInfoFieldType fieldType;
    private Boolean isRequired;
    private String value;
    private LocalDateTime confirmedAt;
    private Boolean isOverdue;
    private LocalDateTime nextDueAt;
}
