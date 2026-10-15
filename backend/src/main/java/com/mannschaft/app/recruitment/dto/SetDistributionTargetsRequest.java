package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.RecruitmentDistributionTargetType;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * F03.11 募集型予約: 配信対象設定リクエスト。
 */
@Getter
@NoArgsConstructor
public class SetDistributionTargetsRequest {

    @NotEmpty(message = "配信対象を1件以上指定してください")
    private List<RecruitmentDistributionTargetType> targetTypes;
}
