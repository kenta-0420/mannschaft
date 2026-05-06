package com.mannschaft.app.memberinfo.dto;

import com.mannschaft.app.memberinfo.MemberInfoFieldType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CreateMemberInfoFieldRequest {
    @NotBlank
    @Size(max = 100)
    private String fieldName;

    @NotNull
    private MemberInfoFieldType fieldType;

    @NotNull
    private Boolean isRequired;

    @NotNull
    private Boolean isSensitive;

    private Integer refreshIntervalMonths;

    private Integer sortOrder;
}
