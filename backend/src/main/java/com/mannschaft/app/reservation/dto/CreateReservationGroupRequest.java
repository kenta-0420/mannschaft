package com.mannschaft.app.reservation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * 予約グループ作成リクエストDTO（F03.4.3 §4）。
 *
 * <p>親 §4.B の Jackson 手本に倣い<b>全 final・単一コンストラクタ</b>
 * （複数コンストラクタは Jackson の creator 解決に失敗し POST 500 になる —
 * feedback_dto_all_final_multi_constructor_jackson_no_creators）。</p>
 */
@Getter
@RequiredArgsConstructor
public class CreateReservationGroupRequest {

    /**
     * 選択メニューID。null = メニューなしの自由グループ（枠数 1〜16 を任意に連続確保）。
     * 不正/他チーム/無効は 404 = RESERVATION_032。
     */
    private final UUID menuId;

    /**
     * 予約ラインID。各 slot について「共通枠（line_id NULL）または slot.line_id == lineId」であること
     * （違反は 400 = RESERVATION_038）。メニュー指定時は提供可否も満たすこと（違反は 400 = RESERVATION_043）。
     */
    @NotNull
    private final Long lineId;

    /**
     * 確保する枠ID列（1〜16 個・同一日・時間連続）。16 超は 400 = RESERVATION_041、
     * 非連続/別日/必要枠数不足は 400 = RESERVATION_038。
     */
    @NotEmpty
    @Size(max = 16)
    private final List<Long> slotIds;

    /** ユーザー備考（代表行にのみ保存）。 */
    @Size(max = 500)
    private final String userNote;
}
