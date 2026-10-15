package com.mannschaft.app.committee.dto;

import com.mannschaft.app.committee.entity.ConfirmationMode;
import com.mannschaft.app.committee.entity.CommitteePurposeTag;
import com.mannschaft.app.committee.entity.CommitteeVisibility;
import com.mannschaft.app.committee.entity.DistributionScope;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 委員会情報更新リクエスト DTO。
 * すべてのフィールドは任意（null = 変更なし）。
 */
@Getter
@NoArgsConstructor
public class CommitteeUpdateRequest {

    /** 委員会名称 */
    @Size(max = 100)
    private String name;

    /** 説明 */
    @Size(max = 500)
    private String description;

    /** 目的タグ */
    private CommitteePurposeTag purposeTag;

    /** 活動開始日 */
    private LocalDate startDate;

    /** 活動終了日 */
    private LocalDate endDate;

    /** 組織メンバーへの公開範囲 */
    private CommitteeVisibility visibilityToOrg;

    /** デフォルト確認モード */
    private ConfirmationMode defaultConfirmationMode;

    /** デフォルトでお知らせ通知を有効にするか */
    private Boolean defaultAnnouncementEnabled;

    /** デフォルト配信範囲 */
    private DistributionScope defaultDistributionScope;
}
