package com.mannschaft.app.memberinfo.dto;

import com.mannschaft.app.memberinfo.MemberInfoFieldType;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateMemberInfoFieldRequest {
    @Size(max = 100)
    private String fieldName;
    private MemberInfoFieldType fieldType;
    private Boolean isRequired;
    private Boolean isSensitive;
    private Integer refreshIntervalMonths;
    private Integer sortOrder;
}
