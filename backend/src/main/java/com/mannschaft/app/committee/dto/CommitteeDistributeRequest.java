package com.mannschaft.app.committee.dto;

import com.mannschaft.app.committee.entity.ConfirmationMode;
import com.mannschaft.app.committee.entity.DistributionScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * F04.10 委員会伝達処理リクエスト DTO。
 */
@Getter
@NoArgsConstructor
public class CommitteeDistributeRequest {

    /**
     * 伝達対象種別。
     * SURVEY_RESULT / ACTIVITY_RECORD / CIRCULATION_RESULT / CUSTOM_MESSAGE のいずれか。
     */
    @NotBlank
    private String contentType;

    /**
     * 伝達対象エンティティ ID。
     * CUSTOM_MESSAGE の場合は null 可。
     */
    private Long contentId;

    /**
     * CUSTOM_MESSAGE 時のタイトル（最大 200 文字）。
     */
    @Size(max = 200)
    private String customTitle;

    /**
     * CUSTOM_MESSAGE 時の本文（最大 5000 文字）。
     */
    @Size(max = 5000)
    private String customBody;

    /**
     * 配信先スコープ。
     */
    @NotNull
    private DistributionScope targetScope;

    /**
     * お知らせフィードに投下するかどうか。
     */
    @NotNull
    private Boolean announcementEnabled;

    /**
     * 確認通知モード。
     */
    @NotNull
    private ConfirmationMode confirmationMode;

    /**
     * 確認期限日時。REQUIRED モード時に使用する。
     */
    private LocalDateTime confirmationDeadlineAt;

    /**
     * お知らせのタイトル上書き（最大 200 文字）。
     * null の場合はコンテンツのタイトルをそのまま使用する。
     */
    @Size(max = 200)
    private String customHeadline;
}
