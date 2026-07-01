package com.mannschaft.app.circulation.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 回覧文書作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateDocumentRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    @NotBlank
    private final String body;

    private final String circulationMode;

    private final String priority;

    private final LocalDate dueDate;

    private final Boolean reminderEnabled;

    private final Short reminderIntervalHours;

    private final String stampDisplayStyle;

    @NotNull
    @Size(min = 1)
    private final List<RecipientEntry> recipients;

    /**
     * HYBRID モードの「順番に押印する先頭人数 N」。
     *
     * <p>circulationMode=HYBRID のときのみ意味を持ち、必須かつ {@code 1 ≤ N < あて先数} でなければならない
     * （{@link #isHybridSequentialCountValid()} で検証）。SEQUENTIAL / SIMULTANEOUS では無視する。</p>
     */
    private final Integer sequentialCount;

    /**
     * HYBRID モードの sequentialCount 相関バリデーション。
     *
     * <p>circulationMode=HYBRID のときのみ検査する。N は必須かつ
     * {@code 1 ≤ N < recipients.size()} を満たす必要がある
     * （N を先頭順番人数とし、残り(あて先数 - N)人が一斉群となるため、両群が 1 人以上存在する範囲）。
     * HYBRID 以外では sequentialCount の値によらず常に true（無視）。</p>
     *
     * @return 制約を満たす場合 true
     */
    @AssertTrue(message = "HYBRIDモードのsequentialCountは1以上あて先数未満で指定してください")
    public boolean isHybridSequentialCountValid() {
        if (!"HYBRID".equals(circulationMode)) {
            return true;
        }
        if (sequentialCount == null) {
            return false;
        }
        int recipientCount = recipients != null ? recipients.size() : 0;
        return sequentialCount >= 1 && sequentialCount < recipientCount;
    }
}
