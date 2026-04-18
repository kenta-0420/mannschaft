package com.mannschaft.app.committee.dto;

import com.mannschaft.app.committee.entity.ConfirmationMode;
import com.mannschaft.app.committee.entity.CommitteePurposeTag;
import com.mannschaft.app.committee.entity.CommitteeVisibility;
import com.mannschaft.app.committee.entity.DistributionScope;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 委員会設立リクエスト DTO。
 */
@Getter
@NoArgsConstructor
public class CommitteeCreateRequest {

    /** 委員会名称 */
    @NotBlank
    @Size(max = 100)
    private String name;

    /** 説明（任意） */
    @Size(max = 500)
    private String description;

    /** 目的タグ（任意） */
    private CommitteePurposeTag purposeTag;

    /** 活動開始日（任意） */
    private LocalDate startDate;

    /** 活動終了日（任意） */
    private LocalDate endDate;

    /** 組織メンバーへの公開範囲（任意、デフォルト: NAME_ONLY） */
    private CommitteeVisibility visibilityToOrg;

    /** デフォルト確認モード（任意、デフォルト: OPTIONAL） */
    private ConfirmationMode defaultConfirmationMode;

    /** デフォルトでお知らせ通知を有効にするか（任意、デフォルト: true） */
    private Boolean defaultAnnouncementEnabled;

    /** デフォルト配信範囲（任意、デフォルト: COMMITTEE_ONLY） */
    private DistributionScope defaultDistributionScope;

    /** 初期委員長のユーザーID */
    @NotNull
    private Long initialChairUserId;

    /**
     * 開始日 <= 終了日 であることを検証する。
     */
    @AssertTrue(message = "終了日は開始日以降の日付を指定してください")
    public boolean isEndDateAfterStartDate() {
        if (startDate == null || endDate == null) {
            return true;
        }
        return !endDate.isBefore(startDate);
    }
}
