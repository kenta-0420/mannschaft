package com.mannschaft.app.shift.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * シフト枠作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor(onConstructor_ = @JsonCreator)
public class CreateShiftSlotRequest {

    @NotNull
    private final LocalDate slotDate;

    @NotNull
    private final LocalTime startTime;

    @NotNull
    private final LocalTime endTime;

    private final Long positionId;

    private final Integer requiredCount;

    @Size(max = 200)
    private final String note;

    /**
     * 翌日終了（日跨ぎ）か。設計 F03.5 §11.2.5 規則5。
     *
     * <p>省略時（{@code null}）は「日跨ぎでない」＝ {@code false} として扱う。
     * 暗黙の {@code end < start} による日跨ぎは認めない。</p>
     */
    private final Boolean endsNextDay;

    /**
     * 日跨ぎ指定なしの互換コンストラクタ（{@code endsNextDay = false}）。
     *
     * @param slotDate      対象日
     * @param startTime     開始時刻
     * @param endTime       終了時刻
     * @param positionId    ポジションID
     * @param requiredCount 必要人数
     * @param note          メモ
     */
    public CreateShiftSlotRequest(LocalDate slotDate, LocalTime startTime, LocalTime endTime,
                                  Long positionId, Integer requiredCount, String note) {
        this(slotDate, startTime, endTime, positionId, requiredCount, note, Boolean.FALSE);
    }

    /**
     * 日跨ぎか（{@code null} を {@code false} に畳んだ値）。
     *
     * @return 翌日終了なら true
     */
    public boolean endsNextDayOrFalse() {
        return Boolean.TRUE.equals(endsNextDay);
    }
}
