package com.mannschaft.app.reservation.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.reservation.ReservationDayOfWeek;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.dto.CreateRecurringBlockedTimeRequest;
import com.mannschaft.app.reservation.dto.RecurringBlockedTimeImpactResponse;
import com.mannschaft.app.reservation.dto.RecurringBlockedTimeResponse;
import com.mannschaft.app.reservation.dto.UpdateRecurringBlockedTimeRequest;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationRecurringBlockedTimeEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationRecurringBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationRecurringOverlapRow;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 定期予約不可枠 CRUD サービス（F03.4.5 §4 W2-2）。
 *
 * <p>週次繰り返しの予約不可枠（事由ラベル＋公開可否）の作成/更新/削除/一覧/影響プレビューを担う。
 * enforcement 本体（runtime overlap 判定）は {@link ReservationUnavailabilityChecker} に集約し、
 * 本サービスは CRUD・上限（50行/チーム）・409 ガード（overlap する active 予約の拒否）を担当する。</p>
 *
 * <h2>409 ガード / impact の判定 horizon（§4.3）</h2>
 * <p>週次ルールは将来無限に効くため、判定対象は「今日から90日先までの active 予約
 * （PENDING/CONFIRMED）」に限定する（枠の生成horizon=28日・手動枠を含めても90日で実用上全件）。
 * 生成horizon（28日）とは意図的に非対称（混同禁止）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationRecurringBlockedTimeService {

    /** F03.4.5 §4.1: 1 チームあたりの定期予約不可枠ルール上限。 */
    static final long MAX_RULES_PER_TEAM = 50L;

    /** F03.4.5 §4.3: 409 ガード / impact の判定 horizon（今日から何日先まで）。 */
    static final int GUARD_HORIZON_DAYS = 90;

    private static final List<ReservationStatus> ACTIVE_STATUSES =
            List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);

    private final ReservationRecurringBlockedTimeRepository ruleRepository;
    private final ReservationLineRepository lineRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationUnavailabilityChecker unavailabilityChecker;
    private final NameResolverService nameResolverService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    // ────────────────────────────────────────────────────────────
    // 一覧
    // ────────────────────────────────────────────────────────────

    /**
     * ルール一覧（曜日→開始時刻順）を取得する。
     */
    public List<RecurringBlockedTimeResponse> listRules(Long teamId) {
        List<ReservationRecurringBlockedTimeEntity> rules = ruleRepository.findByTeamId(teamId).stream()
                .sorted(Comparator
                        .comparing((ReservationRecurringBlockedTimeEntity r) -> r.getDayOfWeek().ordinal())
                        .thenComparing(ReservationRecurringBlockedTimeEntity::getStartTime))
                .toList();
        Map<Long, String> lineNames = resolveLineNames(rules);
        return rules.stream().map(r -> toResponse(r, lineNames.get(r.getLineId()))).toList();
    }

    // ────────────────────────────────────────────────────────────
    // 作成
    // ────────────────────────────────────────────────────────────

    /**
     * ルールを作成する（§4.6 POST）。
     *
     * <p>検証順: 上限50行（RESERVATION_052）→ 時刻 007/022（{@link SlotTimeValidator} 再利用）→
     * lineId 存在・active（001再利用）→ 409 ガード（overlap する active 予約は RESERVATION_027）。</p>
     */
    @Transactional
    public RecurringBlockedTimeResponse createRule(
            Long teamId, CreateRecurringBlockedTimeRequest request, Long createdBy) {
        if (ruleRepository.countByTeamId(teamId) >= MAX_RULES_PER_TEAM) {
            throw new BusinessException(ReservationErrorCode.RECURRING_BLOCKED_TIME_LIMIT_EXCEEDED);
        }
        SlotTimeValidator.validateTimeRange(request.getStartTime(), request.getEndTime());
        ReservationLineEntity line = resolveLineOrThrow(teamId, request.getLineId());
        boolean isPublic = Boolean.TRUE.equals(request.getIsPublic());

        // F03.4.5 §6.2 W2-5（殿の裁定）: force なら 409 の代わりに衝突予約を一括キャンセルして通知する。
        Integer forceCancelledCount = resolveConflicts(
                teamId, request.getLineId(), request.getDayOfWeek(),
                request.getStartTime(), request.getEndTime(), request.getReason(),
                Boolean.TRUE.equals(request.getForceCancelConflicting()), createdBy);

        ReservationRecurringBlockedTimeEntity entity = ReservationRecurringBlockedTimeEntity.builder()
                .teamId(teamId)
                .lineId(request.getLineId())
                .dayOfWeek(request.getDayOfWeek())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .reason(request.getReason())
                .isPublic(isPublic)
                .createdBy(createdBy)
                .build();
        ReservationRecurringBlockedTimeEntity saved = ruleRepository.save(entity);
        log.info("定期予約不可枠作成: teamId={}, ruleId={}, dayOfWeek={}, lineId={}, forceCancelled={}",
                teamId, saved.getId(), saved.getDayOfWeek(), saved.getLineId(), forceCancelledCount);
        recordAudit("RESERVATION_RECURRING_BLOCKED_TIME_CREATED", createdBy, teamId, saved.getId());
        return toResponse(saved, line != null ? line.getName() : null).toBuilder()
                .forceCancelledCount(forceCancelledCount)
                .build();
    }

    // ────────────────────────────────────────────────────────────
    // 更新（部分更新・null=据え置き）
    // ────────────────────────────────────────────────────────────

    /**
     * ルールを部分更新する（§4.6 PATCH・null=据え置き・clearLineId・isActive 切替）。
     * 更新後の最終形（曜日・時間帯・ライン）で 409 ガードを再検証する。
     */
    @Transactional
    public RecurringBlockedTimeResponse updateRule(
            Long teamId, UUID ruleId, UpdateRecurringBlockedTimeRequest request, Long updatedBy) {
        ReservationRecurringBlockedTimeEntity entity = findRuleOrThrow(teamId, ruleId);

        if (Boolean.TRUE.equals(request.getClearLineId())) {
            entity.clearLine();
        } else if (request.getLineId() != null) {
            resolveLineOrThrow(teamId, request.getLineId());
            entity.changeLine(request.getLineId());
        }
        if (request.getDayOfWeek() != null) {
            entity.changeDayOfWeek(request.getDayOfWeek());
        }
        if (request.getStartTime() != null || request.getEndTime() != null) {
            LocalTime newStart = request.getStartTime() != null ? request.getStartTime() : entity.getStartTime();
            LocalTime newEnd = request.getEndTime() != null ? request.getEndTime() : entity.getEndTime();
            SlotTimeValidator.validateTimeRange(newStart, newEnd);
            entity.changeTimeRange(newStart, newEnd);
        }
        if (request.getReason() != null) {
            entity.changeReason(request.getReason());
        }
        if (request.getIsPublic() != null) {
            entity.changeIsPublic(request.getIsPublic());
        }
        if (request.getIsActive() != null) {
            if (request.getIsActive()) {
                entity.activate();
            } else {
                entity.deactivate();
            }
        }

        // 曜日・時間帯・ラインのいずれかが変わった可能性があるため、最終形で 409 ガードを再検証する。
        // F03.4.5 §6.2 W2-5（殿の裁定）: force なら 409 の代わりに衝突予約を一括キャンセルして通知する。
        Integer forceCancelledCount = resolveConflicts(
                teamId, entity.getLineId(), entity.getDayOfWeek(),
                entity.getStartTime(), entity.getEndTime(), entity.getReason(),
                Boolean.TRUE.equals(request.getForceCancelConflicting()), updatedBy);

        ReservationRecurringBlockedTimeEntity saved = ruleRepository.save(entity);
        log.info("定期予約不可枠更新: teamId={}, ruleId={}, forceCancelled={}",
                teamId, ruleId, forceCancelledCount);
        recordAudit("RESERVATION_RECURRING_BLOCKED_TIME_UPDATED", updatedBy, teamId, ruleId);
        return toResponse(saved, resolveLineName(saved.getLineId())).toBuilder()
                .forceCancelledCount(forceCancelledCount)
                .build();
    }

    // ────────────────────────────────────────────────────────────
    // 削除（物理削除・履歴価値なし）
    // ────────────────────────────────────────────────────────────

    /**
     * ルールを物理削除する（§4.6 DELETE。論理削除にしない・一時停止は isActive=FALSE）。
     * 削除には 409 ガードを課さない（受付を止める操作であり、既存予約への影響はない）。
     */
    @Transactional
    public void deleteRule(Long teamId, UUID ruleId, Long deletedBy) {
        ReservationRecurringBlockedTimeEntity entity = findRuleOrThrow(teamId, ruleId);
        ruleRepository.delete(entity);
        log.info("定期予約不可枠削除: teamId={}, ruleId={}", teamId, ruleId);
        recordAudit("RESERVATION_RECURRING_BLOCKED_TIME_DELETED", deletedBy, teamId, ruleId);
    }

    // ────────────────────────────────────────────────────────────
    // 影響プレビュー（副作用ゼロ）
    // ────────────────────────────────────────────────────────────

    /**
     * 登録前の影響プレビューを取得する（§4.3 GET .../impact）。
     * 90日horizon内で overlap する active 予約（PENDING/CONFIRMED）の件数＋一覧を返す。
     */
    public RecurringBlockedTimeImpactResponse getImpact(
            Long teamId, ReservationDayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, Long lineId) {
        List<ReservationRecurringOverlapRow> matched =
                findOverlappingRows(teamId, lineId, dayOfWeek, startTime, endTime);
        if (matched.isEmpty()) {
            return RecurringBlockedTimeImpactResponse.builder().affectedCount(0).reservations(List.of()).build();
        }

        Set<Long> userIds = matched.stream().map(ReservationRecurringOverlapRow::userId).collect(Collectors.toSet());
        Set<Long> staffIds = matched.stream()
                .map(ReservationRecurringOverlapRow::staffUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> userNames = nameResolverService.resolveUserFullNames(userIds);
        Map<Long, String> staffNames = staffIds.isEmpty()
                ? Map.of() : nameResolverService.resolveUserFullNames(staffIds);

        List<RecurringBlockedTimeImpactResponse.ImpactedReservationDto> reservations = matched.stream()
                .sorted(Comparator.comparing(ReservationRecurringOverlapRow::slotDate)
                        .thenComparing(ReservationRecurringOverlapRow::startTime))
                .map(row -> new RecurringBlockedTimeImpactResponse.ImpactedReservationDto(
                        row.reservationId(),
                        row.userId(),
                        userNames.get(row.userId()),
                        row.slotId(),
                        row.slotDate(),
                        row.staffUserId() != null ? staffNames.get(row.staffUserId()) : null,
                        row.startTime(),
                        row.endTime(),
                        row.status().name()))
                .toList();

        return RecurringBlockedTimeImpactResponse.builder()
                .affectedCount(reservations.size())
                .reservations(reservations)
                .build();
    }

    // ────────────────────────────────────────────────────────────
    // 内部ヘルパー
    // ────────────────────────────────────────────────────────────

    private ReservationRecurringBlockedTimeEntity findRuleOrThrow(Long teamId, UUID ruleId) {
        // IDOR: 他チームの ruleId も存在ごと 404（RESERVATION_051）で秘匿する（§4.6）。
        return ruleRepository.findByIdAndTeamId(ruleId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RECURRING_BLOCKED_TIME_NOT_FOUND));
    }

    /**
     * lineId の検証（null = チーム全体で検証不要）。
     * 当該チームの active ライン以外（他チーム/不存在/無効）は 400（LINE_NOT_FOUND=001 再利用）。
     */
    private ReservationLineEntity resolveLineOrThrow(Long teamId, Long lineId) {
        if (lineId == null) {
            return null;
        }
        ReservationLineEntity line = lineRepository.findByIdAndTeamId(lineId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.LINE_NOT_FOUND));
        if (!Boolean.TRUE.equals(line.getIsActive())) {
            throw new BusinessException(ReservationErrorCode.LINE_NOT_FOUND);
        }
        return line;
    }

    /**
     * 衝突する active 予約をどう扱うかを決める単一の分岐点（F03.4.5 §6.2 W2-5・殿の裁定 2026-07-30）。
     *
     * <p>{@code force=false}（既定・従来経路）は 409 ガードをそのまま適用し {@code null} を返す。
     * {@code force=true} は衝突予約を一括キャンセルし、キャンセル件数を返す（0 件でも 0 を返す）。</p>
     *
     * <h2>なぜ強行登録が必要か（機能の構造的破綻の根治）</h2>
     * <p>§4.3 の 409 ガードは「今日から 90 日先までに active 予約があれば拒否」する。一方 §6.2 の
     * 定期予約は最大 12 週 = 約 84 日分の予約を並べる。したがって<b>会員 1 人が定期予約を入れるだけで、
     * 管理者は「毎週火曜19時は研修」を恒久的に登録できなくなる</b>。設計書 §4.3 と §6.2 は互いに
     * 言及しておらず、この衝突は W2-5 で初めて現実化した。
     * 「409 のまま運用で回避」は不可という裁定であり、管理者が impact で影響を確認したうえで
     * 既存予約を整理して登録できる正式な導線を用意することが根治である。</p>
     *
     * @param force TRUE = 衝突予約を一括キャンセルして登録を通す
     * @return force のときキャンセルした件数 / force でないとき null（レスポンスで区別するため）
     */
    private Integer resolveConflicts(
            Long teamId, Long lineId, ReservationDayOfWeek dayOfWeek,
            LocalTime startTime, LocalTime endTime, String blockReason, boolean force, Long actorUserId) {
        if (!force) {
            guardNoActiveOverlap(teamId, lineId, dayOfWeek, startTime, endTime);
            return null;
        }
        return forceCancelOverlapping(
                teamId, lineId, dayOfWeek, startTime, endTime, blockReason, actorUserId);
    }

    /**
     * 提案されたルール（曜日・時間帯・ライン）と overlap する active 予約が 1 件以上あれば
     * {@code RESERVATION_027}（409）で拒否する（§4.3）。
     */
    private void guardNoActiveOverlap(
            Long teamId, Long lineId, ReservationDayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {
        if (!findOverlappingRows(teamId, lineId, dayOfWeek, startTime, endTime).isEmpty()) {
            throw new BusinessException(ReservationErrorCode.UNAVAILABILITY_HAS_ACTIVE_RESERVATIONS);
        }
    }

    /**
     * overlap する active 予約を一括で管理者キャンセルし、各申込者へ通知する
     * （F03.4.5 §6.2 W2-5・強行登録の本体・AC-5-17）。
     *
     * <p><b>グループ予約の原子性</b>: 衝突した行がグループ予約の一部だった場合、その行だけを消すと
     * F03.4.3 が禁じる<b>部分キャンセル</b>（booked_count 不整合・グループ状態の分裂）になる。
     * 兄弟行まで展開して一括キャンセルする。</p>
     *
     * <p><b>枠復帰は {@link ReservationSlotService#decrementAndReopen} を必ず経由する</b>。
     * DB が実際に FULL→AVAILABLE 遷移を起こしたときのみ {@code ReservationSlotReopenedEvent} が
     * 発行され §6.1 のキャンセル待ち通知が AFTER_COMMIT で連鎖する（独自にイベントを撃たない統合点）。</p>
     *
     * @return キャンセルした予約行数
     */
    private int forceCancelOverlapping(
            Long teamId, Long lineId, ReservationDayOfWeek dayOfWeek,
            LocalTime startTime, LocalTime endTime, String blockReason, Long actorUserId) {
        throw new UnsupportedOperationException("F03.4.5 §6.2 W2-5: 未実装（実装コミットで green 化する）");
    }

    /**
     * 90日horizon内で提案ルールと overlap する active 予約の projection 行を取得する
     * （409 ガード・impact 共通の観測点）。
     *
     * <p>SQL では team・日付レンジ・ライン軸のみで絞り込み、曜日一致・時間帯 overlap は
     * {@link ReservationUnavailabilityChecker#isRecurringBlocked} の判定コアを再利用して
     * アプリ層でフィルタする（別実装厳禁・checker と同一の半開区間・3文字曜日変換）。</p>
     */
    private List<ReservationRecurringOverlapRow> findOverlappingRows(
            Long teamId, Long lineId, ReservationDayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {
        LocalDate today = LocalDate.now(clock);
        LocalDate horizonEnd = today.plusDays(GUARD_HORIZON_DAYS);
        List<ReservationRecurringOverlapRow> candidates = reservationRepository
                .findActiveReservationsInRangeForRecurringGuard(teamId, today, horizonEnd, lineId, ACTIVE_STATUSES);
        return candidates.stream()
                .filter(row -> unavailabilityChecker.isRecurringBlocked(
                        row.slotDate(), row.startTime(), row.endTime(), row.lineId(),
                        true, dayOfWeek, startTime, endTime, lineId))
                .toList();
    }

    private Map<Long, String> resolveLineNames(List<ReservationRecurringBlockedTimeEntity> rules) {
        List<Long> lineIds = rules.stream()
                .map(ReservationRecurringBlockedTimeEntity::getLineId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> names = new HashMap<>();
        if (!lineIds.isEmpty()) {
            for (ReservationLineEntity line : lineRepository.findAllById(lineIds)) {
                names.put(line.getId(), line.getName());
            }
        }
        return names;
    }

    private String resolveLineName(Long lineId) {
        if (lineId == null) {
            return null;
        }
        return lineRepository.findById(lineId).map(ReservationLineEntity::getName).orElse(null);
    }

    private RecurringBlockedTimeResponse toResponse(ReservationRecurringBlockedTimeEntity entity, String lineName) {
        return RecurringBlockedTimeResponse.builder()
                .id(entity.getId())
                .teamId(entity.getTeamId())
                .lineId(entity.getLineId())
                .lineName(lineName)
                .dayOfWeek(entity.getDayOfWeek() != null ? entity.getDayOfWeek().name() : null)
                .startTime(entity.getStartTime())
                .endTime(entity.getEndTime())
                .reason(entity.getReason())
                .isPublic(entity.getIsPublic())
                .isActive(entity.getIsActive())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private void recordAudit(String eventType, Long userId, Long teamId, UUID ruleId) {
        String meta = "{\"ruleId\":\"" + ruleId + "\"}";
        auditLogService.record(eventType, userId, null, teamId, null, null, null, null, meta);
    }
}
