package com.mannschaft.app.committee.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 委員会ステータス遷移リクエスト DTO。
 * action: "ACTIVATE" / "CLOSE" / "ARCHIVE" / "REOPEN"
 */
@Getter
@NoArgsConstructor
public class CommitteeStatusTransitionRequest {

    /** 遷移アクション */
    @NotBlank
    private String action;
}
