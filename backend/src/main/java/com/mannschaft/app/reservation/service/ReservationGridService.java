package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.reservation.GridCellState;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.dto.ReservationGridResponse;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationMenuEntity;
import com.mannschaft.app.reservation.entity.ReservationMenuLineEntity;
import com.mannschaft.app.reservation.entity.ReservationRecurringBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuRepository;
import com.mannschaft.app.reservation.repository.ReservationRecurringBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.common.timezone.TeamTimezoneResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 複数予約対象の空きグリッド（機能C・§4.C / F03.4.4 拡張）の read-only 専用サービス。
 *
 * <p>{@code ReservationSlotService} の肥大化を避けるため独立させた。列＝予約対象ライン（＋共通列）、
 * 各セル＝時間帯の状態（{@link GridCellState}）を返す。単日（{@code date}）と日付レンジ
 * （{@code from}/{@code to}・最大7日・{@code days[]} 応答）、メニューフィルター（{@code menuId}）に対応する。</p>
 *
 * <p><b>列軸（#2575）:</b> かつて存在したスタッフ軸（{@code axis=STAFF}）とその列指定
 * （{@code staffUserIds}）は、PR #2574 の旧表示撤去で FE からの呼び出し経路が消滅したため撤去した。
 * 本 API はライン軸固定である。</p>
 *
 * <h2>認可（§4.C / F03.4.4 §6）</h2>
 * <p>{@code @PreAuthorize} では表現できない予約閲覧可否（会員 or 公開）を、予約作成と同一述語の
 * {@link ReservationViewAccessGuard} で判定する（非許可は 403 = RESERVATION_021）。ADMIN 限定にしない。
 * {@code menuId}/{@code from-to} のパラメータ検証は<b>認可判定の後</b>に行う
 * （パラメータは認可判定に影響しない — 判定より前に実行しない）。</p>
 *
 * <h2>パラメータ検証（F03.4.4 §4.1・B3）</h2>
 * <p>「{@code date} XOR ({@code from},{@code to})」の排他は Controller のバインドに任せず本 Service 層で
 * 明示検証する（検証位置と文言の一元管理）。両方未指定は専用メッセージの 400。新規エラーコードは
 * 追加しない（汎用 400 = COMMON_001 + fieldErrors / 404 = RESERVATION_032 の再利用のみ・§9）。</p>
 *
 * <h2>予約者 PII 非露出（§4.C / C-4・F03.4.4 でも全面踏襲）</h2>
 * <p>グリッドは埋まり具合（{@code BOOKED}）のみを返し、予約者氏名 / userId / 予約詳細を
 * {@link ReservationGridResponse.GridCellDto} に構造的に一切載せない。列見出しはライン名（設備名であり
 * PII ではない）のみで、氏名解決は行わない。{@code days[]} でも同一の {@code GridCellDto} を共有する（H-6）。</p>
 *
 * <h2>state 決定順（§4.C）</h2>
 * <p>機能B の予約不可枠 overlap に該当するセルは最優先で {@link GridCellState#UNAVAILABLE} に上書きし、
 * それ以外は {@code slot_status} を写像する。overlap 判定は空き枠除外・予約作成拒否と共有の
 * {@link ReservationUnavailabilityChecker}（§5.B 単一ユーティリティ）を再利用する（別実装厳禁）。
 * この決定順は単日/{@code days[]} で同一（H-5）。</p>
 *
 * <h2>クエリ数（F03.4.4 §11）</h2>
 * <p>レンジ呼びでも slot 取得 1・予約不可枠取得 1・ライン取得 1・（{@code menuId} 時）menu と
 * menu_lines 各 1 の定数クエリ（日数でクエリを増やさない）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationGridService {

    /** 1 セルの分数（固定 30・F03.4.4 §4.1 meta.cellMinutes）。 */
    private static final int CELL_MINUTES = 30;

    /** 日付レンジの最大日数（7 日。8 日以上は 400 — 応答サイズ上限の担保・F03.4.4 §4.1）。 */
    private static final int MAX_RANGE_DAYS = 7;

    private final ReservationSlotRepository slotRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationLineRepository lineRepository;
    private final ReservationBlockedTimeRepository blockedTimeRepository;
    /** F03.4.5 §4 W2-2: 定期予約不可枠（週次繰り返し）の active ルール参照。 */
    private final ReservationRecurringBlockedTimeRepository recurringBlockedTimeRepository;
    private final ReservationUnavailabilityChecker unavailabilityChecker;
    private final ReservationViewAccessGuard viewAccessGuard;
    private final ReservationMenuRepository menuRepository;
    private final ReservationMenuLineRepository menuLineRepository;
    private final TeamTimezoneResolver teamTimezoneResolver;

    /**
     * グリッド取得（F03.4.4 §4.1: 日付レンジ・メニューフィルター。列軸はライン固定 — #2575）。
     *
     * <p>パラメータ検証（{@code date} XOR {@code from/to}）は<b>認可判定の後</b>に Service 層で行う
     * （§6: パラメータは認可判定に影響しない。検証位置と文言を Service 層に一元管理する — B3）。</p>
     *
     * @param teamId チームID
     * @param userId 閲覧ユーザーID（view ゲート用）
     * @param date   単日指定（{@code from/to} と XOR）
     * @param from   レンジ開始日（{@code to} と両方指定・最大7日）
     * @param to     レンジ終了日
     * @param menuId メニューフィルター（他チーム・不存在は 404）
     * @return グリッドレスポンス（単日: {@code date}/{@code columns}・レンジ: {@code days[]}）
     */
    public ReservationGridResponse getGrid(
            Long teamId, Long userId, LocalDate date, LocalDate from, LocalDate to, UUID menuId) {
        // 認可: 予約作成と同一述語（会員 or 公開）。非許可は 403（RESERVATION_021）。
        // パラメータ検証より先に実行する（§6: パラメータは認可判定に影響しない）。
        viewAccessGuard.assertCanView(teamId, userId);
        ZoneId teamZone = teamTimezoneResolver.resolveZone(teamId);

        // パラメータ検証（Service 層・到達順 — §4.1 検証表）。
        validateDateXorRange(date, from, to);

        // active ライン（列の母集合）を 1 クエリで取得。
        List<ReservationLineEntity> activeLines =
                lineRepository.findByTeamIdAndIsActiveTrueOrderByDisplayOrderAsc(teamId);

        // メニューフィルター: menu 解決（不存在・他チーム・削除済みは 404 = RESERVATION_032）＋
        // menu_lines 結線で提供可能ライン列に絞る（行 0 件 = 全ライン提供可 = 全列・F03.4.1 §3）。
        ReservationGridResponse.GridMetaDto meta = null;
        List<ReservationLineEntity> lineColumns = activeLines;
        if (menuId != null) {
            ReservationMenuEntity menu = menuRepository.findByIdAndTeamId(menuId, teamId)
                    .orElseThrow(() -> new BusinessException(ReservationErrorCode.MENU_NOT_FOUND));
            // requiredCellCount は BE 導出（durationMinutes / 30・F03.4.1 と同一導出 — 三者不一致時は BE が正）。
            meta = new ReservationGridResponse.GridMetaDto(
                    menu.getId(), menu.getName(), menu.getRequiredSlotCount(), CELL_MINUTES);
            Set<Long> allowedLineIds = menuLineRepository.findByMenuId(menuId).stream()
                    .map(ReservationMenuLineEntity::getLineId)
                    .collect(Collectors.toSet());
            if (!allowedLineIds.isEmpty()) {
                lineColumns = activeLines.stream()
                        .filter(l -> allowedLineIds.contains(l.getId()))
                        .toList();
            }
        }

        // F03.4.5 §4 W2-2: 定期予約不可枠の active ルールは日付に依らずチーム単位で 1 回だけ取得する
        // （§11 定数クエリ・上限50行のメモリ突合・単日/レンジ双方で共有）。
        List<ReservationRecurringBlockedTimeEntity> recurringRules =
                recurringBlockedTimeRepository.findByTeamIdAndIsActiveTrue(teamId);

        if (date != null) {
            // ── 単日呼び（従来契約: date/columns 非 null・days null）──
            List<ReservationSlotEntity> slots =
                    slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(teamId, date, date);
            List<ReservationBlockedTimeEntity> blocks =
                    blockedTimeRepository.findEffectiveOnDate(teamId, date, date.minusDays(1));
            List<ReservationGridResponse.GridColumnDto> columns =
                    buildLineColumns(lineColumns, slots, blocks, recurringRules, teamZone, reservedSlotIds(userId, slots));
            return ReservationGridResponse.builder()
                    .date(date)
                    .columns(columns)
                    .meta(meta)
                    .build();
        }

        // ── レンジ呼び（days[] 応答・date/columns は null）──
        // slot・予約不可枠ともレンジクエリ 1 回で取得し日付でグルーピングする（§11 定数クエリ）。
        List<ReservationSlotEntity> slots =
                slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(teamId, from, to);
        Set<Long> reservedSlotIds = reservedSlotIds(userId, slots);
        List<ReservationBlockedTimeEntity> blocks =
                blockedTimeRepository.findEffectiveBetween(teamId, from, to, from.minusDays(1));
        Map<LocalDate, List<ReservationSlotEntity>> slotsByDate = slots.stream()
                .collect(Collectors.groupingBy(ReservationSlotEntity::getSlotDate));

        List<ReservationGridResponse.GridDayDto> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            List<ReservationSlotEntity> daySlots = slotsByDate.getOrDefault(d, List.of());
            // 予約不可枠は checker が日付一致を内包判定するためレンジ全件を渡してよい（該当日以外は非マッチ）。
            days.add(new ReservationGridResponse.GridDayDto(
                    d, buildLineColumns(lineColumns, daySlots, blocks, recurringRules, teamZone, reservedSlotIds)));
        }
        return ReservationGridResponse.builder()
                .days(days)
                .meta(meta)
                .build();
    }

    // ────────────────────────────────────────────────────────────
    // パラメータ検証（Service 層一元管理・B3）
    // ────────────────────────────────────────────────────────────

    /**
     * 「{@code date} XOR ({@code from},{@code to})」の排他検証（F03.4.4 §4.1・B3）。
     * 両方未指定は専用メッセージ（バインド段階の汎用 400 に任せない）。
     */
    private void validateDateXorRange(LocalDate date, LocalDate from, LocalDate to) {
        boolean hasDate = date != null;
        boolean hasAnyRange = from != null || to != null;
        if (hasDate && hasAnyRange) {
            throw badRequest("date", "date と from/to は同時に指定できません");
        }
        if (!hasDate && !hasAnyRange) {
            throw badRequest("date", "date または from/to のいずれかを指定してください");
        }
        if (hasDate) {
            return;
        }
        if (from == null || to == null) {
            throw badRequest("from", "from と to は両方指定してください");
        }
        if (from.isAfter(to)) {
            throw badRequest("from", "from は to 以前の日付を指定してください");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw badRequest("from", "日付レンジは最大7日までです");
        }
    }

    /** 汎用 400（新規エラーコードなし・§9）。専用メッセージは fieldErrors に載せる。 */
    private BusinessException badRequest(String field, String message) {
        return new BusinessException(CommonErrorCode.COMMON_001,
                List.of(new ErrorResponse.FieldError(field, message)));
    }

    // ────────────────────────────────────────────────────────────
    // 列構築
    // ────────────────────────────────────────────────────────────

    /**
     * ライン列（＋末尾の共通列）を構築する（F03.4.4 §4.1 確定仕様）。
     *
     * <ul>
     *   <li>列 = active ライン（{@code display_order} 昇順）。当日 slot の有無に関わらず列を出す
     *       （「枠がない」ことが見えるのが有益 — セルなし列は空）。</li>
     *   <li>各列のセル = {@code slot.line_id == 列の lineId} の枠。</li>
     *   <li>共通枠（{@code line_id IS NULL}）は末尾の共通列（{@code lineId: null}）に集約。
     *       共通列は menuId フィルター時も<b>常に含める</b>（共通枠はライン非拘束のため）。</li>
     * </ul>
     */
    private List<ReservationGridResponse.GridColumnDto> buildLineColumns(
            List<ReservationLineEntity> lineColumns,
            List<ReservationSlotEntity> daySlots,
            Collection<ReservationBlockedTimeEntity> blocks,
            Collection<ReservationRecurringBlockedTimeEntity> recurringRules,
            ZoneId teamZone,
            Set<Long> reservedSlotIds) {
        List<ReservationGridResponse.GridColumnDto> columns = new ArrayList<>();
        for (ReservationLineEntity line : lineColumns) {
            List<ReservationGridResponse.GridCellDto> cells = daySlots.stream()
                    .filter(s -> line.getId().equals(s.getLineId()))
                    .map(s -> toCell(s, blocks, recurringRules, teamZone, reservedSlotIds))
                    .toList();
            columns.add(new ReservationGridResponse.GridColumnDto(line.getId(), line.getName(), cells));
        }
        List<ReservationGridResponse.GridCellDto> commonCells = daySlots.stream()
                .filter(s -> s.getLineId() == null)
                .map(s -> toCell(s, blocks, recurringRules, teamZone, reservedSlotIds))
                .toList();
        columns.add(new ReservationGridResponse.GridColumnDto(null, null, commonCells));
        return columns;
    }

    /**
     * slot を 1 セルへ写像する。state 決定順は「機能B/§4.2 overlap（最優先で UNAVAILABLE）→
     * slot_status 写像」。予約者 PII は一切載せない（slotId / 時間帯 / state / price / unavailableReason のみ）。
     */
    private ReservationGridResponse.GridCellDto toCell(
            ReservationSlotEntity slot,
            Collection<ReservationBlockedTimeEntity> blocks,
            Collection<ReservationRecurringBlockedTimeEntity> recurringRules,
            ZoneId teamZone,
            Set<Long> reservedSlotIds) {
        CellStateResult result = resolveState(slot, blocks, recurringRules, teamZone);
        return new ReservationGridResponse.GridCellDto(
                slot.getId(),
                slot.getSlotDate(),
                slot.getEndDate() != null ? slot.getEndDate() : slot.getSlotDate(),
                slot.getStartTime(),
                slot.getEndTime(),
                result.state(),
                slot.getPrice(),
                result.unavailableReason(),
                reservedSlotIds.contains(slot.getId()));
    }

    private Set<Long> reservedSlotIds(Long userId, List<ReservationSlotEntity> slots) {
        if (slots.isEmpty()) return Set.of();
        return Set.copyOf(reservationRepository.findSlotIdsAlreadyReservedByUser(
                userId,
                slots.stream().map(ReservationSlotEntity::getId).toList(),
                List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED)));
    }

    /**
     * セル state（＋F03.4.5 §4.4 unavailableReason）を決定する。
     *
     * <h2>決定順（§4.C・§4.4）</h2>
     * <ol>
     *   <li>単発予約不可枠（機能B・常に非公開）に該当 → UNAVAILABLE・reason=null（非公開優先）</li>
     *   <li>単発は非該当だが定期予約不可枠（§4）のいずれかに該当 → UNAVAILABLE・reason=該当ルールのうち
     *       {@code is_public=TRUE} で開始時刻が最も早いものの {@code reason}（無ければ null）</li>
     *   <li>いずれも非該当 → slot_status を写像（AVAILABLE→AVAILABLE / FULL→BOOKED / CLOSED→CLOSED）</li>
     * </ol>
     */
    private CellStateResult resolveState(
            ReservationSlotEntity slot,
            Collection<ReservationBlockedTimeEntity> blocks,
            Collection<ReservationRecurringBlockedTimeEntity> recurringRules,
            ZoneId teamZone) {
        // 最優先: 機能B の単発予約不可枠 overlap（常に非公開＝unavailableReason は null 固定）。
        if (blocks.stream().anyMatch(block -> unavailabilityChecker.isBlocked(slot, block, teamZone))) {
            return new CellStateResult(GridCellState.UNAVAILABLE, null);
        }
        // 次点: F03.4.5 §4 定期予約不可枠。該当があれば UNAVAILABLE。
        // reason は is_public=TRUE のうち開始時刻昇順で最初のものを採用（決定的・AC R-4）。
        List<ReservationRecurringBlockedTimeEntity> matchedRecurring = recurringRules.stream()
                .filter(r -> unavailabilityChecker.isRecurringBlocked(slot, r, teamZone))
                .toList();
        if (!matchedRecurring.isEmpty()) {
            String publicReason = matchedRecurring.stream()
                    .filter(ReservationRecurringBlockedTimeEntity::isPublicRule)
                    .min(Comparator.comparing(ReservationRecurringBlockedTimeEntity::getStartTime))
                    .map(ReservationRecurringBlockedTimeEntity::getReason)
                    .orElse(null);
            return new CellStateResult(GridCellState.UNAVAILABLE, publicReason);
        }
        SlotStatus status = slot.getSlotStatus();
        GridCellState state = switch (status) {
            case FULL -> GridCellState.BOOKED;
            case CLOSED -> GridCellState.CLOSED;
            case AVAILABLE -> GridCellState.AVAILABLE;
        };
        return new CellStateResult(state, null);
    }

    /** セル state 決定結果（内部専用・F03.4.5 §4.4）。 */
    private record CellStateResult(GridCellState state, String unavailableReason) {}
}
