package com.mannschaft.app.event.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 事前遅刻連絡リクエストDTO。F03.12 §15 事前遅刻・欠席連絡。
 *
 * <p>メンバー本人または見守り者が、イベント開始前に遅刻予定を申告する際に使用する。</p>
 */
@Getter
@RequiredArgsConstructor
public class LateNoticeRequest {

    /** 遅刻を申告するユーザーのID（本人または見守り者が代理申告する場合はケア対象者のID）。 */
    @NotNull(message = "userIdは必須です")
    private final Long userId;

    /**
     * 遅刻予定分数（1〜120分）。
     * イベント開始時刻からの遅刻見込み分数を指定する。
     */
    @NotNull(message = "expectedArrivalMinutesLateは必須です")
    @Min(value = 1, message = "遅刻予定分数は1分以上を指定してください")
    @Max(value = 120, message = "遅刻予定分数は120分以内を指定してください")
    private final Integer expectedArrivalMinutesLate;

    /** コメント（任意）。遅刻理由や補足情報を記入する。 */
    @Size(max = 500, message = "コメントは500文字以内で入力してください")
    private final String comment;
}
