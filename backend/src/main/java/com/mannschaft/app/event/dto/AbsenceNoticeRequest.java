package com.mannschaft.app.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 事前欠席連絡リクエストDTO。F03.12 §15 事前遅刻・欠席連絡。
 *
 * <p>メンバー本人または見守り者が、イベントへの欠席を事前に申告する際に使用する。</p>
 */
@Getter
@RequiredArgsConstructor
public class AbsenceNoticeRequest {

    /** 欠席を申告するユーザーのID（本人または見守り者が代理申告する場合はケア対象者のID）。 */
    @NotNull(message = "userIdは必須です")
    private final Long userId;

    /**
     * 欠席理由。SICK（病気）/ PERSONAL_REASON（個人的な事情）/ OTHER（その他）のいずれかを指定する。
     */
    @NotBlank(message = "absenceReasonは必須です")
    @Pattern(regexp = "SICK|PERSONAL_REASON|OTHER",
             message = "absenceReasonはSICK, PERSONAL_REASON, OTHERのいずれかを指定してください")
    private final String absenceReason;

    /** コメント（任意）。欠席理由の補足情報を記入する。 */
    @Size(max = 500, message = "コメントは500文字以内で入力してください")
    private final String comment;
}
