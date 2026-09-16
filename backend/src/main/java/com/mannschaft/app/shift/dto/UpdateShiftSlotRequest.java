package com.mannschaft.app.shift.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * シフト枠更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor(onConstructor_ = @JsonCreator)
public class UpdateShiftSlotRequest {

    private final LocalDate slotDate;

    private final LocalTime startTime;

    private final LocalTime endTime;

    private final Long positionId;

    private final Integer requiredCount;

    private final List<Long> assignedUserIds;

    @Size(max = 200)
    private final String note;

    /**
     * 翌日終了（日跨ぎ）か。設計 F03.5 §11.2.5 規則5。
     *
     * <p>部分更新のため {@code null} は「現値維持」を意味する。</p>
     */
    private final Boolean endsNextDay;

    /**
     * 日跨ぎ指定なしの互換コンストラクタ（{@code endsNextDay} 据え置き）。
     *
     * @param slotDate        対象日
     * @param startTime       開始時刻
     * @param endTime         終了時刻
     * @param positionId      ポジションID
     * @param requiredCount   必要人数
     * @param assignedUserIds 割当ユーザーID
     * @param note            メモ
     */
    public UpdateShiftSlotRequest(LocalDate slotDate, LocalTime startTime, LocalTime endTime,
                                  Long positionId, Integer requiredCount,
                                  List<Long> assignedUserIds, String note) {
        this(slotDate, startTime, endTime, positionId, requiredCount, assignedUserIds, note, null);
    }
}
