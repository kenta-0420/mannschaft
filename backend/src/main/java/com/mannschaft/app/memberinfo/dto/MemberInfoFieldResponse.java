package com.mannschaft.app.memberinfo.dto;

import com.mannschaft.app.memberinfo.MemberInfoFieldType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Builder
public class MemberInfoFieldResponse {
    private Long id;
    private String fieldName;
    private MemberInfoFieldType fieldType;
    private Boolean isRequired;
    private Boolean isSensitive;
    private Integer refreshIntervalMonths;
    private Integer sortOrder;
    private Boolean isActive;
}
