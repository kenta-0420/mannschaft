package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.reservation.GridAxis;
import com.mannschaft.app.reservation.GridCellState;
import com.mannschaft.app.reservation.ReservationErrorCode;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 複数予約対象の空きグリッド（機能C・§4.C / F03.4.4 拡張）の read-only 専用サービス。
 *
 * <p>{@code ReservationSlotService} の肥大化を避けるため独立させた。列＝予約対象（スタッフ・共通、
 * {@code axis=LINE} 時はライン・共通）、各セル＝時間帯の状態（{@link GridCellState}）を返す。
 * F03.4.4 で単日（{@code date}）に加え日付レンジ（{@code from}/{@code to}・最大7日・{@code days[]} 応答）、
 * ライン軸（{@code axis=LINE}）、メニューフィルター（{@code menuId}）を additive に拡張した
 * （既存の単日・staff 軸契約は無変更）。</p>
 *
 * <h2>認可（§4.C / F03.4.4 §6）</h2>
 * <p>{@code @PreAuthorize} では表現できない予約閲覧可否（会員 or 公開）を、予約作成と同一述語の
 * {@link ReservationViewAccessGuard} で判定する（非許可は 403 = RESERVATION_021）。ADMIN 限定にしない。
 * {@code axis}/{@code menuId}/{@code from-to} のパラメータ検証は<b>認可判定の後</b>に行う
 * （パラメータは認可判定に影響しない — 判定より前に実行しない）。</p>
 *
 * <h2>パラメータ検証（F03.4.4 §4.1・B3）</h2>
 * <p>「{@code date} XOR ({@code from},{@code to})」の排他は Controller のバインドに任せず本 Service 層で
 * 明示検証する（検証位置と文言の一元管理）。両方未指定は専用メッセージの 400。新規エラーコードは
 * 追加しない（汎用 400 = COMMON_001 + fieldErrors / 404 = RESERVATION_032 の再利用のみ・§9）。</p>
 *
 * <h2>予約者 PII 非露出（§4.C / C-4・F03.4.4 でも全面踏襲）</h2>
 * <p>グリッドは埋まり具合（{@code BOOKED}）のみを返し、予約者氏名 / userId / 予約詳細を
 * {@link ReservationGridResponse.GridCellDto} に構造的に一切載せない。氏名解決はスタッフ列見出しのみ。
 * {@code axis=LINE}/{@code days[]} でも同一の {@code GridCellDto} を共有する（H-6）。</p>
 *
 * <h2>state 決定順（§4.C）</h2>
 * <p>機能B の予約不可枠 overlap に該当するセルは最優先で {@link GridCellState#UNAVAILABLE} に上書きし、
 * それ以外は {@code slot_status} を写像する。overlap 判定は空き枠除外・予約作成拒否と共有の
 * {@link ReservationUnavailabilityChecker}（§5.B 単一ユーティリティ）を再利用する（別実装厳禁）。
 * この決定順は {@code axis=LINE}/{@code days[]} でも同一（H-5）。</p>
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
    private final ReservationLineRepository lineRepository;
    private final ReservationBlockedTimeRepository blockedTimeRepository;
    /** F03.4.5 §4 W2-2: 定期予約不可枠（週次繰り返し）の active ルール参照。 */
    private final ReservationRecurringBlockedTimeRepository recurringBlockedTimeRepository;
    private final ReservationUnavailabilityChecker unavailabilityChecker;
    private final NameResolverService nameResolverService;
    private final ReservationViewAccessGuard viewAccessGuard;
    private final ReservationMenuRepository menuRepository;
    private final ReservationMenuLineRepository menuLineRepository;

    /**
     * 指定日の空きグリッドを構築する（従来シグネチャ・完全後方互換）。
     *
     * <p>F03.4.4 拡張版 {@link #getGrid(Long, Long, LocalDate, LocalDate, LocalDate, String, UUID, List)}
     * へ委譲する（axis 省略 = STAFF・単日）。応答の JSON 構造は拡張前と同一
     * （{@code axis="STAFF"} が増える以外は無変更 — H-1）。</p>
     *
     * @param teamId       チームID
     * @param userId       閲覧ユーザーID（view ゲート用）
     * @param date         対象日（単日）
     * @param staffUserIds 列に並べる予約対象。{@code null}/空なら当日 slot を持つ全スタッフを自動導出
     * @return グリッドレスポンス（列＝予約対象・セル＝時間帯 state）
     */
    public ReservationGridResponse getGrid(Long teamId, Long userId, LocalDate date, List<Long> staffUserIds) {
        return getGrid(teamId, userId, date, null, null, null, null, staffUserIds);
    }

    /**
     * 拡張グリッド取得（F03.4.4 §4.1: ライン軸・日付レンジ・メニューフィルター）。
     *
     * <p>パラメータ検証（{@code date} XOR {@code from/to}・axis・menuId 併用可否）は
     * <b>認可判定の後</b>に Service 層で行う（§6: パラメータは認可判定に影響しない。
     * 検証位置と文言を Service 層に一元管理する — B3）。</p>
     *
     * @param teamId       チームID
     * @param userId       閲覧ユーザーID（view ゲート用）
     * @param date         単日指定（{@code from/to} と XOR）
     * @param from         レンジ開始日（{@code to} と両方指定・最大7日）
     * @param to           レンジ終了日
     * @param axisParam    列軸（{@code "STAFF"} 既定 / {@code "LINE"}。不正値は 400）
     * @param menuId       メニューフィルター（{@code axis=LINE} のときのみ有効。他チーム・不存在は 404）
     * @param staffUserIds 列に並べる予約対象（axis=STAFF のときのみ意味を持つ）
     * @return グリッドレスポンス（単日: {@code date}/{@code columns}・レンジ: {@code days[]}）
     */
    public ReservationGridResponse getGrid(
            Long teamId, Long userId, LocalDate date, LocalDate from, LocalDate to,
            String axisParam, UUID menuId, List<Long> staffUserIds) {
        // 認可: 予約作成と同一述語（会員 or 公開）。非許可は 403（RESERVATION_021）。
        // パラメータ検証より先に実行する（§6: パラメータは認可判定に影響しない）。
        viewAccessGuard.assertCanView(teamId, userId);

        // パラメータ検証（Service 層・到達順 — §4.1 検証表）。
        GridAxis axis = parseAxis(axisParam);
        validateDateXorRange(date, from, to);
        if (menuId != null && axis == GridAxis.STAFF) {
            throw badRequest("menuId", "menuId は axis=LINE のときのみ指定できます");
        }

        // active ライン（LINE 軸の列・staff 軸の lineIds プリセットの双方に使用）を 1 クエリで取得。
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
                    blockedTimeRepository.findByTeamIdAndBlockedDateOrderByStartTimeAsc(teamId, date);
            List<ReservationGridResponse.GridColumnDto> columns = (axis == GridAxis.LINE)
                    ? buildLineColumns(lineColumns, slots, blocks, recurringRules)
                    : buildStaffColumns(slots, blocks, recurringRules, staffUserIds,
                            resolveStaffNames(staffUserIds, slots), resolveLineIdsByStaff(activeLines));
            return ReservationGridResponse.builder()
                    .date(date)
                    .columns(columns)
                    .axis(axis.name())
                    .meta(meta)
                    .build();
        }

        // ── レンジ呼び（days[] 応答・date/columns は null）──
        // slot・予約不可枠ともレンジクエリ 1 回で取得し日付でグルーピングする（§11 定数クエリ）。
        List<ReservationSlotEntity> slots =
                slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(teamId, from, to);
        List<ReservationBlockedTimeEntity> blocks =
                blockedTimeRepository.findByTeamIdAndBlockedDateBetweenOrderByBlockedDateAscStartTimeAsc(
                        teamId, from, to);
        Map<LocalDate, List<ReservationSlotEntity>> slotsByDate = slots.stream()
                .collect(Collectors.groupingBy(ReservationSlotEntity::getSlotDate));
        // staff 軸: 氏名・lineIds プリセットはレンジ全体で 1 回だけ解決する（N+1 回避）。
        Map<Long, String> staffNames = (axis == GridAxis.STAFF)
                ? resolveStaffNames(staffUserIds, slots) : Map.of();
        Map<Long, List<Long>> lineIdsByStaff = (axis == GridAxis.STAFF)
                ? resolveLineIdsByStaff(activeLines) : Map.of();

        List<ReservationGridResponse.GridDayDto> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            List<ReservationSlotEntity> daySlots = slotsByDate.getOrDefault(d, List.of());
            // 予約不可枠は checker が日付一致を内包判定するためレンジ全件を渡してよい（該当日以外は非マッチ）。
            List<ReservationGridResponse.GridColumnDto> columns = (axis == GridAxis.LINE)
                    ? buildLineColumns(lineColumns, daySlots, blocks, recurringRules)
                    : buildStaffColumns(daySlots, blocks, recurringRules, staffUserIds, staffNames, lineIdsByStaff);
            days.add(new ReservationGridResponse.GridDayDto(d, columns));
        }
        return ReservationGridResponse.builder()
                .days(days)
                .axis(axis.name())
                .meta(meta)
                .build();
    }

    // ────────────────────────────────────────────────────────────
    // パラメータ検証（Service 層一元管理・B3）
    // ────────────────────────────────────────────────────────────

    /** {@code axis} を解決する（省略時は STAFF・不正値は 400。大文字の正準値のみ許容）。 */
    private GridAxis parseAxis(String axisParam) {
        if (axisParam == null || axisParam.isBlank()) {
            return GridAxis.STAFF;
        }
        try {
            return GridAxis.valueOf(axisParam);
        } catch (IllegalArgumentException e) {
            throw badRequest("axis", "axis は STAFF または LINE を指定してください");
        }
    }

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
     * staff 軸の列（スタッフ列＋該当あれば共通列）を構築する（機能C の従来動作そのまま）。
     * staff 軸列は {@code lineId}/{@code lineName} を持たない（F03.4.4 契約: axis=STAFF では常に null）。
     */
    private List<ReservationGridResponse.GridColumnDto> buildStaffColumns(
            List<ReservationSlotEntity> daySlots,
            Collection<ReservationBlockedTimeEntity> blocks,
            Collection<ReservationRecurringBlockedTimeEntity> recurringRules,
            List<Long> requestedStaffUserIds,
            Map<Long, String> staffNames,
            Map<Long, List<Long>> lineIdsByStaff) {
        boolean hasCommonSlot = daySlots.stream().anyMatch(s -> s.getStaffUserId() == null);
        List<Long> staffKeys = resolveStaffColumnKeys(requestedStaffUserIds, daySlots);

        List<ReservationGridResponse.GridColumnDto> columns = new ArrayList<>();
        for (Long staffKey : staffKeys) {
            List<ReservationGridResponse.GridCellDto> cells = daySlots.stream()
                    .filter(s -> staffKey.equals(s.getStaffUserId()))
                    .map(s -> toCell(s, blocks, recurringRules))
                    .toList();
            columns.add(new ReservationGridResponse.GridColumnDto(
                    staffKey,
                    staffNames.get(staffKey),
                    null,
                    null,
                    lineIdsByStaff.getOrDefault(staffKey, List.of()),
                    cells));
        }

        // 共通列（staff_user_id = null の店共通 slot を集約・MVP）。当日に共通 slot がある場合のみ。
        if (hasCommonSlot) {
            List<ReservationGridResponse.GridCellDto> commonCells = daySlots.stream()
                    .filter(s -> s.getStaffUserId() == null)
                    .map(s -> toCell(s, blocks, recurringRules))
                    .toList();
            // 共通列は staffUserId=null・氏名 null（FE が i18n ラベルで描画）・lineIds は常に空。
            columns.add(new ReservationGridResponse.GridColumnDto(
                    null, null, null, null, List.of(), commonCells));
        }
        return columns;
    }

    /**
     * LINE 軸の列（ライン列＋末尾の共通列）を構築する（F03.4.4 §4.1 確定仕様）。
     *
     * <ul>
     *   <li>列 = active ライン（{@code display_order} 昇順）。当日 slot の有無に関わらず列を出す
     *       （「枠がない」ことが見えるのが有益 — セルなし列は空）。</li>
     *   <li>各列のセル = {@code slot.line_id == 列の lineId} の枠。</li>
     *   <li>共通枠（{@code line_id IS NULL}）は末尾の共通列（{@code lineId: null}）に集約。
     *       共通列は menuId フィルター時も<b>常に含める</b>（共通枠はライン非拘束のため）。</li>
     *   <li>LINE 軸列は {@code staffUserId}/{@code staffName} 常に null・{@code lineIds} は空配列。</li>
     * </ul>
     */
    private List<ReservationGridResponse.GridColumnDto> buildLineColumns(
            List<ReservationLineEntity> lineColumns,
            List<ReservationSlotEntity> daySlots,
            Collection<ReservationBlockedTimeEntity> blocks,
            Collection<ReservationRecurringBlockedTimeEntity> recurringRules) {
        List<ReservationGridResponse.GridColumnDto> columns = new ArrayList<>();
        for (ReservationLineEntity line : lineColumns) {
            List<ReservationGridResponse.GridCellDto> cells = daySlots.stream()
                    .filter(s -> line.getId().equals(s.getLineId()))
                    .map(s -> toCell(s, blocks, recurringRules))
                    .toList();
            columns.add(new ReservationGridResponse.GridColumnDto(
                    null, null, line.getId(), line.getName(), List.of(), cells));
        }
        List<ReservationGridResponse.GridCellDto> commonCells = daySlots.stream()
                .filter(s -> s.getLineId() == null)
                .map(s -> toCell(s, blocks, recurringRules))
                .toList();
        columns.add(new ReservationGridResponse.GridColumnDto(
                null, null, null, null, List.of(), commonCells));
        return columns;
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
     * スタッフ列見出しの氏名を一括解決する（N+1 回避・共通列は氏名解決対象外）。
     * 明示指定スタッフ＋取得済み slot のスタッフの和集合を 1 回で解決する。
     */
    private Map<Long, String> resolveStaffNames(List<Long> staffUserIds, List<ReservationSlotEntity> slots) {
        Set<Long> keys = new LinkedHashSet<>();
        if (staffUserIds != null) {
            staffUserIds.stream().filter(java.util.Objects::nonNull).forEach(keys::add);
        }
        slots.stream()
                .map(ReservationSlotEntity::getStaffUserId)
                .filter(java.util.Objects::nonNull)
                .forEach(keys::add);
        return nameResolverService.resolveUserFullNames(new ArrayList<>(keys));
    }

    /**
     * active（{@code is_active=TRUE} かつ {@code deleted_at IS NULL}）な予約ラインを
     * {@code default_staff_user_id} で束ね、スタッフ→lineIds のマップを作る（C-8）。
     * {@code default_staff_user_id} が null のラインはどの列にも属さない。
     */
    private Map<Long, List<Long>> resolveLineIdsByStaff(List<ReservationLineEntity> activeLines) {
        return activeLines.stream()
                .filter(l -> l.getDefaultStaffUserId() != null)
                .collect(Collectors.groupingBy(
                        ReservationLineEntity::getDefaultStaffUserId,
                        Collectors.mapping(ReservationLineEntity::getId, Collectors.toList())));
    }

    /**
     * slot を 1 セルへ写像する。state 決定順は「機能B/§4.2 overlap（最優先で UNAVAILABLE）→
     * slot_status 写像」。予約者 PII は一切載せない（slotId / 時間帯 / state / price / unavailableReason のみ）。
     */
    private ReservationGridResponse.GridCellDto toCell(
            ReservationSlotEntity slot,
            Collection<ReservationBlockedTimeEntity> blocks,
            Collection<ReservationRecurringBlockedTimeEntity> recurringRules) {
        CellStateResult result = resolveState(slot, blocks, recurringRules);
        return new ReservationGridResponse.GridCellDto(
                slot.getId(),
                slot.getStartTime(),
                slot.getEndTime(),
                result.state(),
                slot.getPrice(),
                result.unavailableReason());
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
            Collection<ReservationRecurringBlockedTimeEntity> recurringRules) {
        // 最優先: 機能B の単発予約不可枠 overlap（常に非公開＝unavailableReason は null 固定）。
        if (unavailabilityChecker.isBlockedByAny(slot, blocks)) {
            return new CellStateResult(GridCellState.UNAVAILABLE, null);
        }
        // 次点: F03.4.5 §4 定期予約不可枠。該当があれば UNAVAILABLE。
        // reason は is_public=TRUE のうち開始時刻昇順で最初のものを採用（決定的・AC R-4）。
        List<ReservationRecurringBlockedTimeEntity> matchedRecurring = recurringRules.stream()
                .filter(r -> unavailabilityChecker.isRecurringBlocked(slot, r))
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
