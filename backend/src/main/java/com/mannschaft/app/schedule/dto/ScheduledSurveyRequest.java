package com.mannschaft.app.schedule.dto;

import com.mannschaft.app.survey.dto.CreateSurveyRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 予約アンケート作成リクエスト（機能55 第二陣）。
 *
 * <p>予定作成時に「この時刻になったら集計可能な本格アンケートを自動生成・公開する」予約を表す。
 * {@link #scheduledAt} 到来時に後続バッチ（{@code ScheduleScheduledTaskBatchService}）が
 * {@link #survey} のスナップショットを元に {@code SurveyService.createSurvey/publishSurvey} を呼び、
 * 実体のアンケートを materialize する。</p>
 *
 * <p>アンケート定義は survey ドメインの {@link CreateSurveyRequest} をそのまま保持し、
 * materialize 時に survey ドメインへ渡す。設問・選択肢・匿名可否・結果公開範囲・配信モードなどを
 * フルに指定でき、集計可能な survey を作れる。</p>
 *
 * <p>{@link #scheduledAt} は {@link OffsetDateTime}（TZ 付き）で受け取り、
 * サービス層で Asia/Tokyo の {@link java.time.LocalDateTime} に変換して Entity に保存する。
 * これにより非 JST ユーザーが指定した絶対時刻が正確に扱われる。</p>
 */
@Getter
@RequiredArgsConstructor
public class ScheduledSurveyRequest {

    /** この時刻にアンケートを生成・公開する（TZ 付き）。 */
    @NotNull
    @Future
    private final OffsetDateTime scheduledAt;

    /** 生成するアンケートの定義（survey ドメインの作成パラメータ）。 */
    @NotNull
    @Valid
    private final CreateSurveyRequest survey;
}
