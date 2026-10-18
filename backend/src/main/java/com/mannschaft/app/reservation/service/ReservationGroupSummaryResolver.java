package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationMenuEntity;
import com.mannschaft.app.reservation.repository.ReservationMenuRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 予約一覧の {@code ReservationResponse.GroupSummaryDto} 一括解決コンポーネント（F03.4.3 §5.6 #10）。
 *
 * <p>グループ所属行（{@code group_id IS NOT NULL}）に対し「枠数・末尾枠終了時刻・メニュー名」を
 * バッチ解決する（集約 1 クエリ＋メニュー名 1 クエリ・N+1 回避）。メニュー名は削除済みメニューも
 * 履歴解決する（G-14・{@code findAllByIdIncludingDeleted} 経由）。
 * 単枠予約（group_id NULL）は対象外（{@code group=null} 維持＝既存契約不変）。</p>
 */
@Component
@RequiredArgsConstructor
public class ReservationGroupSummaryResolver {

    private final ReservationRepository reservationRepository;
    private final ReservationMenuRepository menuRepository;

    /**
     * 本人履歴に限り、削除済み枠を含むグループを一括解決する。
     * 通常の管理一覧・直近予約の集約には適用しない。
     */
    public Map<Long, ReservationResponse.GroupSummaryDto> resolveForHistory(
            List<ReservationEntity> entities, Long userId) {
        List<ReservationEntity> groupRows = entities.stream()
                .filter(e -> e.getGroupId() != null && userId.equals(e.getUserId()))
                .toList();
        if (groupRows.isEmpty()) {
            return Map.of();
        }
        List<Long> reservationIds = groupRows.stream().map(ReservationEntity::getId).toList();
        Map<Long, Object[]> aggregates = reservationRepository.aggregateGroupsForHistory(reservationIds, userId)
                .stream().collect(Collectors.toMap(row -> ((Number) row[0]).longValue(), row -> row));
        Set<UUID> menuIds = groupRows.stream().map(ReservationEntity::getMenuId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, ReservationMenuEntity> menus = menuIds.isEmpty() ? Map.of()
                : menuRepository.findAllByIdIncludingDeleted(menuIds).stream()
                        .collect(Collectors.toMap(ReservationMenuEntity::getId, menu -> menu));
        Map<Long, ReservationResponse.GroupSummaryDto> result = new HashMap<>();
        for (ReservationEntity entity : groupRows) {
            Object[] aggregate = aggregates.get(entity.getId());
            Integer size = aggregate != null ? ((Number) aggregate[1]).intValue() : null;
            LocalTime endTime = aggregate != null && aggregate[2] != null
                    ? ((java.sql.Time) aggregate[2]).toLocalTime() : null;
            ReservationMenuEntity menu = entity.getMenuId() != null ? menus.get(entity.getMenuId()) : null;
            // 不整合なmenuIdでも別チームの名前を履歴へ混入させない（同チームの削除済み名は保持）。
            String menuName = menu != null && entity.getTeamId().equals(menu.getTeamId()) ? menu.getName() : null;
            result.put(entity.getId(), new ReservationResponse.GroupSummaryDto(
                    entity.getGroupId(), size, endTime, menuName));
        }
        return result;
    }

    /**
     * 予約行リストからグループ要約を entity ID キーで一括解決する。
     *
     * @param entities 予約エンティティリスト（グループ所属・単枠混在可）
     * @return {@code entityId -> GroupSummaryDto}（グループ所属行のみ。単枠行はキーに含まれない）
     */
    public Map<Long, ReservationResponse.GroupSummaryDto> resolve(List<ReservationEntity> entities) {
        List<ReservationEntity> groupRows = entities.stream()
                .filter(e -> e.getGroupId() != null)
                .toList();
        if (groupRows.isEmpty()) {
            return Map.of();
        }

        Set<UUID> groupIds = groupRows.stream()
                .map(ReservationEntity::getGroupId)
                .collect(Collectors.toSet());
        // [groupId, count, maxEndTime] の集約 1 クエリ
        Map<UUID, Object[]> aggregates = reservationRepository.aggregateGroupSummaries(groupIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> row));

        Set<UUID> menuIds = groupRows.stream()
                .map(ReservationEntity::getMenuId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> menuNames = menuIds.isEmpty()
                ? Map.of()
                : menuRepository.findAllByIdIncludingDeleted(menuIds).stream()
                        .collect(Collectors.toMap(ReservationMenuEntity::getId, ReservationMenuEntity::getName));

        Map<Long, ReservationResponse.GroupSummaryDto> result = new HashMap<>();
        for (ReservationEntity entity : groupRows) {
            Object[] agg = aggregates.get(entity.getGroupId());
            Integer groupSize = agg != null ? ((Number) agg[1]).intValue() : null;
            LocalTime groupEndTime = agg != null ? (LocalTime) agg[2] : null;
            String menuName = entity.getMenuId() != null ? menuNames.get(entity.getMenuId()) : null;
            result.put(entity.getId(), new ReservationResponse.GroupSummaryDto(
                    entity.getGroupId(), groupSize, groupEndTime, menuName));
        }
        return result;
    }
}
