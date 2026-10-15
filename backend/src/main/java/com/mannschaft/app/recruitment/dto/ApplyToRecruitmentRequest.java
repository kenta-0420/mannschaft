package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.RecruitmentParticipantType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.11 募集への参加申込リクエスト。
 * participantType=USER → user_id は SecurityUtils から取得 (ボディには含めない)
 * participantType=TEAM → teamId 必須
 */
@Getter
@RequiredArgsConstructor
public class ApplyToRecruitmentRequest {

    @NotNull
    private final RecruitmentParticipantType participantType;

    private final Long teamId;

    @Size(max = 500)
    private final String note;
}
