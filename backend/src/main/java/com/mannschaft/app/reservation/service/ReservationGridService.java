package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.reservation.GridCellState;
import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.dto.ReservationGridResponse;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 複数予約対象の空きグリッド（機能C・§4.C）の read-only 専用サービス。
 *
 * <p>{@code ReservationSlotService} の肥大化を避けるため独立させた。列＝予約対象（スタッフ・共通）、
 * 各セル＝時間帯の状態（{@link GridCellState}）を返す。単日のみ（週タブは FE が単日 API を 7 回呼ぶ）。</p>
 *
 * <h2>認可（§4.C）</h2>
 * <p>{@code @PreAuthorize} では表現できない予約閲覧可否（会員 or 公開）を、予約作成と同一述語の
 * {@link ReservationViewAccessGuard} で判定する（非許可は 403 = RESERVATION_021）。ADMIN 限定にしない。</p>
 *
 * <h2>予約者 PII 非露出（§4.C / C-4）</h2>
 * <p>グリッドは埋まり具合（{@code BOOKED}）のみを返し、予約者氏名 / userId / 予約詳細を
 * {@link ReservationGridResponse.GridCellDto} に構造的に一切載せない。氏名解決はスタッフ列見出しのみ。</p>
 *
 * <h2>state 決定順（§4.C）</h2>
 * <p>機能B の予約不可枠 overlap に該当するセルは最優先で {@link GridCellState#UNAVAILABLE} に上書きし、
 * それ以外は {@code slot_status} を写像する。overlap 判定は空き枠除外・予約作成拒否と共有の
 * {@link ReservationUnavailabilityChecker}（§5.B 単一ユーティリティ）を再利用する（別実装厳禁）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationGridService {

    private final ReservationSlotRepository slotRepository;
    private final ReservationLineRepository lineRepository;
    private final ReservationBlockedTimeRepository blockedTimeRepository;
    private final ReservationUnavailabilityChecker unavailabilityChecker;
    private final NameResolverService nameResolverService;
    private final ReservationViewAccessGuard viewAccessGuard;

    /**
     * 指定日の空きグリッドを構築する。
     *
     * @param teamId       チームID
     * @param userId       閲覧ユーザーID（view ゲート用）
     * @param date         対象日（単日）
     * @param staffUserIds 列に並べる予約対象。{@code null}/空なら当日 slot を持つ全スタッフを自動導出
     * @return グリッドレスポンス（列＝予約対象・セル＝時間帯 state）
     */
    public ReservationGridResponse getGrid(Long teamId, Long userId, LocalDate date, List<Long> staffUserIds) {
        // 認可: 予約作成と同一述語（会員 or 公開）。非許可は 403（RESERVATION_021）。ADMIN 限定にしない。
        viewAccessGuard.assertCanView(teamId, userId);

        // 当日の全 slot を 1 クエリで取得（単日＝from==to）。
        List<ReservationSlotEntity> slots =
                slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(teamId, date, date);

        // 当日の予約不可枠を 1 クエリで取得（overlap 判定に共有）。
        List<ReservationBlockedTimeEntity> blocks =
                blockedTimeRepository.findByTeamIdAndBlockedDateOrderByStartTimeAsc(teamId, date);

        // 列キー（スタッフ user_id）を決定する。
        boolean hasCommonSlot = slots.stream().anyMatch(s -> s.getStaffUserId() == null);
        List<Long> staffKeys = resolveStaffColumnKeys(staffUserIds, slots);

        // スタッフ列見出しの氏名を一括解決（N+1 回避）。共通列は氏名解決対象外。
        Map<Long, String> staffNames = nameResolverService.resolveUserFullNames(staffKeys);

        // 列の lineIds プリセット導出用: active ラインを default_staff_user_id で束ねる。
        Map<Long, List<Long>> lineIdsByStaff = resolveLineIdsByStaff(teamId);

        List<ReservationGridResponse.GridColumnDto> columns = new ArrayList<>();
        for (Long staffKey : staffKeys) {
            List<ReservationGridResponse.GridCellDto> cells = slots.stream()
                    .filter(s -> staffKey.equals(s.getStaffUserId()))
                    .map(s -> toCell(s, blocks))
                    .toList();
            columns.add(new ReservationGridResponse.GridColumnDto(
                    staffKey,
                    staffNames.get(staffKey),
                    lineIdsByStaff.getOrDefault(staffKey, List.of()),
                    cells));
        }

        // 共通列（staff_user_id = null の店共通 slot を集約・MVP）。当日に共通 slot がある場合のみ。
        if (hasCommonSlot) {
            List<ReservationGridResponse.GridCellDto> commonCells = slots.stream()
                    .filter(s -> s.getStaffUserId() == null)
                    .map(s -> toCell(s, blocks))
                    .toList();
            // 共通列は staffUserId=null・氏名 null（FE が i18n ラベルで描画）・lineIds は常に空。
            columns.add(new ReservationGridResponse.GridColumnDto(null, null, List.of(), commonCells));
        }

        return ReservationGridResponse.builder()
                .date(date)
                .columns(columns)
                .build();
    }

    /**
     * 列キー（スタッフ user_id）を決定する。
     *
     * <ul>
     *   <li>{@code staffUserIds} 指定あり: その並び順を維持（重複除去）。slot 有無に関わらず列にする。</li>
     *   <li>未指定: 当日 slot を持つ非 null スタッフを重複なく昇順で導出する。</li>
     * </ul>
     * いずれも共通列（null）はここには含めない（別途 hasCommonSlot で付与）。
     */
    private List<Long> resolveStaffColumnKeys(List<Long> staffUserIds, List<ReservationSlotEntity> slots) {
        if (staffUserIds != null && !staffUserIds.isEmpty()) {
            // 並び順維持＋重複除去（null は無視）。
            Set<Long> ordered = new LinkedHashSet<>();
            for (Long id : staffUserIds) {
                if (id != null) {
                    ordered.add(id);
                }
            }
            return new ArrayList<>(ordered);
        }
        // 未指定: 当日 slot の非 null スタッフを昇順で。
        return slots.stream()
                .map(ReservationSlotEntity::getStaffUserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * active（{@code is_active=TRUE} かつ {@code deleted_at IS NULL}）な予約ラインを
     * {@code default_staff_user_id} で束ね、スタッフ→lineIds のマップを作る（C-8）。
     * {@code default_staff_user_id} が null のラインはどの列にも属さない。
     */
    private Map<Long, List<Long>> resolveLineIdsByStaff(Long teamId) {
        List<ReservationLineEntity> activeLines =
                lineRepository.findByTeamIdAndIsActiveTrueOrderByDisplayOrderAsc(teamId);
        return activeLines.stream()
                .filter(l -> l.getDefaultStaffUserId() != null)
                .collect(Collectors.groupingBy(
                        ReservationLineEntity::getDefaultStaffUserId,
                        Collectors.mapping(ReservationLineEntity::getId, Collectors.toList())));
    }

    /**
     * slot を 1 セルへ写像する。state 決定順は「機能B overlap（最優先で UNAVAILABLE）→ slot_status 写像」。
     * 予約者 PII は一切載せない（slotId / 時間帯 / state / price のみ）。
     */
    private ReservationGridResponse.GridCellDto toCell(
            ReservationSlotEntity slot, Collection<ReservationBlockedTimeEntity> blocks) {
        GridCellState state = resolveState(slot, blocks);
        return new ReservationGridResponse.GridCellDto(
                slot.getId(),
                slot.getStartTime(),
                slot.getEndTime(),
                state,
                slot.getPrice());
    }

    /**
     * セル state を決定する（§4.C）。予約不可枠 overlap を最優先で UNAVAILABLE 上書きし、
     * それ以外は slot_status（AVAILABLE→AVAILABLE / FULL→BOOKED / CLOSED→CLOSED）を写像する。
     */
    private GridCellState resolveState(
            ReservationSlotEntity slot, Collection<ReservationBlockedTimeEntity> blocks) {
        // 最優先: 機能B の予約不可枠 overlap（空き枠除外・作成拒否と同一ユーティリティ）。
        if (unavailabilityChecker.isBlockedByAny(slot, blocks)) {
            return GridCellState.UNAVAILABLE;
        }
        SlotStatus status = slot.getSlotStatus();
        return switch (status) {
            case FULL -> GridCellState.BOOKED;
            case CLOSED -> GridCellState.CLOSED;
            case AVAILABLE -> GridCellState.AVAILABLE;
        };
    }
}
