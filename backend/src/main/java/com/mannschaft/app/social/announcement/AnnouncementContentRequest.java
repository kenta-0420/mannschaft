package com.mannschaft.app.social.announcement;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * F02.8 告知ウィザードのコンテンツフィールドを保持する汎用リクエスト DTO。
 *
 * <p>チャネルを問わず共通で使えるフィールド群を保持する。
 * チャネル固有の必須バリデーションはコントローラー層または各アダプターで実施する。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnnouncementContentRequest {

    /** タイトル（掲示板・ブログ・TODO・スケジュール・アンケートで使用）。 */
    private String title;

    /** 本文（掲示板・ブログ・タイムラインで使用）。 */
    private String body;

    /** カテゴリ ID（掲示板で使用）。 */
    @JsonProperty("category_id")
    private Long categoryId;

    /** 担当者 ID リスト（TODO で使用。告知ウィザードでは通常 null）。 */
    @JsonProperty("assignee_ids")
    private List<Long> assigneeIds;

    /** 開始日時（スケジュールで使用）。TZオフセットを含む。 */
    @JsonProperty("start_at")
    private OffsetDateTime startAt;

    /** 終了日時（スケジュールで使用）。TZオフセットを含む。 */
    @JsonProperty("end_at")
    private OffsetDateTime endAt;

    /** 終日フラグ（スケジュールで使用）。 */
    @JsonProperty("all_day")
    private Boolean allDay;

    /** スケジュール会場・場所（SCHEDULE チャネル用。最大300文字）*/
    @JsonProperty("location")
    @Size(max = 300)
    private String location;

    /** 説明文（SURVEY/SCHEDULE チャネル用。最大5000文字）*/
    @JsonProperty("description")
    @Size(max = 5000)
    private String description;

    /** アンケート締切日時（SURVEY チャネル用。null = 無期限）*/
    @JsonProperty("closes_at")
    private LocalDateTime closesAt;
}
