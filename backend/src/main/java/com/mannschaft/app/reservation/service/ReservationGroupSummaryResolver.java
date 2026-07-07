package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 予約一覧の {@code ReservationResponse.GroupSummaryDto} 一括解決コンポーネント（F03.4.3 §5.6 #10）。
 *
 * <p>グループ所属行（{@code group_id IS NOT NULL}）に対し「枠数・末尾枠終了時刻・メニュー名」を
 * バッチ解決する（N+1 回避）。メニュー名は削除済みメニューも履歴解決する（G-14・
 * {@code findAllByIdIncludingDeleted} 経由）。単枠予約（group_id NULL）は対象外（{@code group=null} 維持）。</p>
 */
@Component
@RequiredArgsConstructor
public class ReservationGroupSummaryResolver {

    /**
     * 予約行リストからグループ要約を entity ID キーで一括解決する。
     *
     * @param entities 予約エンティティリスト（グループ所属・単枠混在可）
     * @return {@code entityId -> GroupSummaryDto}（グループ所属行のみ。単枠行はキーに含まれない）
     */
    public Map<Long, ReservationResponse.GroupSummaryDto> resolve(List<ReservationEntity> entities) {
        throw new UnsupportedOperationException("未実装（/試練 red）");
    }
}
