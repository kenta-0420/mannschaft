package com.mannschaft.app.shift.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 「今後の予定」向けの本人割当シフト枠 DTO（CMP-260908-2117）。
 *
 * <p>個人ダッシュボード {@code GET /api/v1/dashboard/upcoming-events} が
 * shift ドメインへ問い合わせるための返却型。</p>
 *
 * <p><b>なぜエンティティを返さないか</b>: dashboard ドメインが shift のエンティティ・
 * リポジトリへ直接依存すると、モジュラーモノリスの原則（ドメイン間は ID 参照＋Service 経由）
 * に反し、アーキテクチャ番人 D-1 / D-5 の違反になる。ドメイン間はこの DTO で受け渡す。</p>
 *
 * <p>チーム名・アイコンは含めない。呼び出し側（dashboard）が予約分と合わせて
 * teamId から一括解決するため、ここで解決すると同じクエリを二重に打つことになる。</p>
 */
@Builder
@Getter
public class UpcomingAssignedSlotResponse {

    /** シフト枠ID */
    private Long slotId;

    /** 所属シフト表のタイトル（引けない場合は null） */
    private String scheduleTitle;

    /** シフト日付 */
    private LocalDate slotDate;

    /** 開始時刻 */
    private LocalTime startTime;

    /** 終了時刻（日跨ぎは呼び出し側で解決する） */
    private LocalTime endTime;

    /** 所属シフト表のチームID（引けない場合は null） */
    private Long teamId;
}
