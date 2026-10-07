package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.timezone.TeamTimezoneResolver;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.reservation.CancelledBy;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationPolicyRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository.PendingExpireCandidate;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.service.ReservationPendingExpireProgressService.RunState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 仮押さえ(PENDING)自動失効の実処理サービス（F03.4.5 §6.3・W2-6）。
 *
 * <p>スケジュール宣言（{@code @BatchEndpoint} / {@code @Scheduled} / {@code @SchedulerLock}）は
 * {@link ReservationPendingExpireBatchService} が持ち、本クラスは「対象抽出」と「1 単位の失効」だけを担う
 * （{@link ReservationWaitlistCleanupBatchService} → {@link ReservationWaitlistService} と同じ役割分担。
 * 注入 {@code Clock} も実処理側である本クラスが保持する）。</p>
 *
 * <h2>なぜ 2 クラスに分けるのか（トランザクション境界の根治）</h2>
 * <p>バッチ全体を 1 つの {@code @Transactional} で囲むと、行単位 try/catch は<b>機能しない</b>。
 * 内側の {@code @Transactional} メソッド（{@link ReservationSlotService#decrementAndReopen} 等）から
 * 例外が抜けた時点で Spring は参加中トランザクションを rollback-only にマークするため、
 * 呼び出し元が例外を握っても最終コミットが {@code UnexpectedRollbackException} で失敗し、
 * 「1 件の失敗が全件を巻き込む」ことになる。
 * 失効 1 単位を {@link Propagation#REQUIRES_NEW} の独立トランザクションにすることで、
 * <b>単位内は原子的（部分失効なし）・単位間は独立（1 件の失敗が他を巻き込まない）</b>を両立させる
 * （AC-6-6 / AC-6-9）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationPendingExpireService {

    /** 申込者へ送る通知の種別（{@code NotificationType.RESERVATION_PENDING_EXPIRED}）。 */
    static final String NOTIFICATION_TYPE = "RESERVATION_PENDING_EXPIRED";

    /** 通知 sourceType（F00 visibility / 受信権の判定キー・予約ドメイン共通）。 */
    static final String SOURCE_TYPE = "RESERVATION";

    /** {@code cancel_reason} に入れる定型文（DB 保存用・FE の翻訳対象ではないため i18n しない）。 */
    static final String CANCEL_REASON = "承認期限切れのため自動キャンセルされました";

    private static final DateTimeFormatter SLOT_AT_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    /**
     * 1 回の実行で処理する失効単位の上限（殿の裁定・2026-07-29）。
     *
     * <p>デプロイ初回の一斉失効・通知バーストを「5 分ごとに最大 500 単位ずつ」へ平滑化する。
     * 成功・0件・失敗を含む単位呼出回数を数える。検査済み連続prefixを永続cursorへ保存し、
     * 上限到達時は次回へ継続する。失敗IDはretryに残し、cursorを後退させない。</p>
     */
    static final int MAX_UNITS_PER_RUN = 500;

    private final ReservationRepository reservationRepository;
    private final ReservationSlotRepository slotRepository;
    private final ReservationSlotService slotService;
    private final NotificationHelper notificationHelper;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;
    private final Clock clock;
    private final ReservationPolicyRepository policyRepository;
    private final ReservationPendingExpireProgressService progress;
    private final TeamTimezoneResolver timezoneResolver;
    private final EntityManager entityManager;

    /** 検査順を保つraw行。eligibleでも500枠外ならunitはnullで、cursorを越えさせない。 */
    public record ScanEntry(long primaryId, boolean retry, boolean eligible, PendingExpireUnit unit) { }
    public record ScanPlan(List<ScanEntry> entries, long scannedThrough, boolean exhausted) { }

    /** 非TX抽出の既入口。進捗を変更しない検査用で、公開batchはRunState付き入口を使う。 */
    public List<PendingExpireUnit> findExpirableUnits() {
        var run = new RunState(0, reservationRepository.findPendingExpireHighWater(), 0, List.of());
        return findScanPlan(run).entries().stream().map(ScanEntry::unit).filter(Objects::nonNull).toList();
    }

    /**
     * 候補最大9＋TZ一括1＋兄弟1＋slot1。COUNTなし、raw4500までを先に取得して分類する。
     * 進捗/highwater/unit再読はこの抽出予算に含めない。共通TZ窓口をTX内では呼ばない。
     */
    public ScanPlan findScanPlan(RunState run) {
        var now = LocalDateTime.ofInstant(clock.instant(), UserZoneLocalDateTimeParser.SERVER_ZONE);
        var oldRetry = new HashSet<>(run.retryPrimaryIds());
        List<PendingExpireCandidate> retryRows = run.retryPrimaryIds().isEmpty() ? List.of()
                : reservationRepository.findPendingPrimaryCandidatesByIds(ReservationStatus.PENDING,
                        run.retryPrimaryIds(), now, ReservationPolicyEntity.DEFAULT_PENDING_EXPIRE_HOURS);
        var freshRows = new ArrayList<PendingExpireCandidate>();
        long pageCursor = run.cursor();
        boolean exhausted = pageCursor == run.highWater();
        int pages = retryRows.isEmpty() && run.retryPrimaryIds().isEmpty() ? 9 : 8;
        for (int page = 0; page < pages && !exhausted; page++) {
            var rows = reservationRepository.findPendingPrimaryCandidates(ReservationStatus.PENDING,
                    pageCursor, run.highWater(), now, ReservationPolicyEntity.DEFAULT_PENDING_EXPIRE_HOURS,
                    PageRequest.of(0, MAX_UNITS_PER_RUN));
            freshRows.addAll(rows);
            if (!rows.isEmpty()) pageCursor = rows.getLast().getPrimary().getId();
            exhausted = rows.size() < MAX_UNITS_PER_RUN || pageCursor == run.highWater();
            if (exhausted) pageCursor = run.highWater();
        }
        var all = new ArrayList<>(retryRows);
        all.addAll(freshRows);
        var zones = all.isEmpty() ? Map.<Long, ZoneId>of() : timezoneResolver.resolveZones(
                all.stream().map(row -> row.getPrimary().getTeamId()).collect(Collectors.toSet()));
        var retryById = retryRows.stream().collect(Collectors.toMap(row -> row.getPrimary().getId(), row -> row));
        var candidates = new LinkedHashMap<Long, PendingExpireCandidate>();
        var eligible = new HashSet<Long>();
        for (long id : run.retryPrimaryIds()) {
            var candidate = retryById.get(id);
            candidates.put(id, candidate);
            if (candidate != null && isExpired(candidate, zones)) eligible.add(id);
        }
        for (var candidate : freshRows) {
            long id = candidate.getPrimary().getId();
            if (!oldRetry.contains(id)) {
                candidates.put(id, candidate);
                if (isExpired(candidate, zones)) eligible.add(id);
            }
        }
        var selected = candidates.entrySet().stream().filter(entry -> eligible.contains(entry.getKey()))
                .limit(MAX_UNITS_PER_RUN).map(Map.Entry::getValue).toList();
        var groupIds = selected.stream().map(row -> row.getPrimary().getGroupId())
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, List<ReservationEntity>> siblings = groupIds.isEmpty() ? Map.of()
                : reservationRepository.findByGroupIdInAndStatus(groupIds, ReservationStatus.PENDING).stream()
                        .collect(Collectors.groupingBy(ReservationEntity::getGroupId));
        var rowsById = new LinkedHashMap<Long, List<ReservationEntity>>();
        var slotIds = new HashSet<Long>();
        for (var candidate : selected) {
            var primary = candidate.getPrimary();
            var rows = primary.getGroupId() == null ? List.of(primary)
                    : siblings.getOrDefault(primary.getGroupId(), List.of(primary));
            rowsById.put(primary.getId(), rows);
            rows.forEach(row -> slotIds.add(row.getReservationSlotId()));
        }
        Map<Long, ReservationSlotEntity> slotById = slotIds.isEmpty() ? Map.of()
                : slotRepository.findAllById(slotIds).stream()
                        .collect(Collectors.toMap(ReservationSlotEntity::getId, slot -> slot));
        var units = new HashMap<Long, PendingExpireUnit>();
        for (var candidate : selected) {
            var primary = candidate.getPrimary();
            units.put(primary.getId(), new PendingExpireUnit(primary, rowsById.get(primary.getId()), slotById));
        }
        var entries = new ArrayList<ScanEntry>();
        for (long id : run.retryPrimaryIds()) {
            entries.add(new ScanEntry(id, true, eligible.contains(id), units.get(id)));
        }
        for (var candidate : freshRows) {
            long id = candidate.getPrimary().getId();
            entries.add(new ScanEntry(id, false, !oldRetry.contains(id) && eligible.contains(id),
                    oldRetry.contains(id) ? null : units.get(id)));
        }
        return new ScanPlan(List.copyOf(entries), pageCursor, exhausted);
    }

    private boolean isExpired(PendingExpireCandidate candidate, Map<Long, ZoneId> zones) {
        if (!Boolean.TRUE.equals(candidate.getEnabled())) return false;
        if (Boolean.TRUE.equals(candidate.getBookedExpired())) return true;
        return endPassed(candidate.getSlotDate(), candidate.getEndDate(), candidate.getEndTime(),
                zones.getOrDefault(candidate.getPrimary().getTeamId(), ZoneId.of(TeamTimezoneResolver.DEFAULT_TIMEZONE)));
    }

    private boolean endPassed(java.time.LocalDate slotDate, java.time.LocalDate endDate,
                              java.time.LocalTime endTime, ZoneId zone) {
        if (slotDate == null || endTime == null) return false;
        try {
            return !timezoneResolver.toInstant(endDate == null ? slotDate : endDate, endTime, zone)
                    .isAfter(clock.instant());
        } catch (DateTimeException e) {
            // DST gapは終了枝だけfalse。bookedAt枝がtrueなら先に採用済み。
            return false;
        }
    }

    /**
     * 最新行をcurrent readで再構成し、進捗・予約・枠・既通知DB行を同じunit TXでcommitする。
     * 旧unitはIDだけを使う。必要slotの欠損は失敗として全rollbackしdurable retryを残す。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int expireUnit(PendingExpireUnit unit) {
        long epoch = unit.epoch();
        long completedPrefix = unit.completedPrefix();
        ZoneId zone = unit.zone();
        if (epoch <= 0 || completedPrefix < 0 || zone == null) {
            throw new IllegalArgumentException("失効unitにはepoch・検査済みprefix・TZが必要です");
        }
        progress.lockForUnit(epoch);
        var primary = reservationRepository.findPendingExpirePrimaryForUpdate(unit.primary().getId()).orElse(null);
        // OSIV等で候補取得時のmanaged Entityが残っても最新行を使う。EM全体はclearしない。
        if (primary != null) entityManager.refresh(primary, LockModeType.PESSIMISTIC_WRITE);
        if (primary == null || primary.getDeletedAt() != null || primary.getStatus() != ReservationStatus.PENDING
                || !Boolean.TRUE.equals(primary.getIsGroupPrimary())) {
            progress.completeUnit(epoch, unit.primary().getId(), completedPrefix);
            return 0;
        }
        var rows = primary.getGroupId() == null ? List.of(primary)
                : reservationRepository.findPendingExpireGroupForUpdate(primary.getGroupId());
        rows.forEach(row -> entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE));
        if (rows.stream().anyMatch(row -> row.getStatus() != ReservationStatus.PENDING)) {
            log.warn("仮押さえ自動失効スキップ: グループの一部だけ状態が異なる groupId={}", primary.getGroupId());
            progress.completeUnit(epoch, primary.getId(), completedPrefix);
            return 0;
        }
        if (!Objects.equals(primary.getTeamId(), unit.primary().getTeamId())) {
            throw new IllegalStateException("抽出後に予約のチームが変更されました: reservationId=" + primary.getId());
        }
        var policy = policyRepository.findPendingExpirePolicyForUpdate(primary.getTeamId()).orElse(null);
        if (policy != null) entityManager.refresh(policy, LockModeType.PESSIMISTIC_WRITE);
        Integer hours = policy == null ? ReservationPolicyEntity.DEFAULT_PENDING_EXPIRE_HOURS
                : policy.getPendingExpireHours();
        if (hours == null) {
            progress.completeUnit(epoch, primary.getId(), completedPrefix);
            return 0;
        }
        var slots = slotRepository.findPendingExpireSlotsForUpdate(
                rows.stream().map(ReservationEntity::getReservationSlotId).collect(Collectors.toSet()));
        slots.forEach(slot -> entityManager.refresh(slot, LockModeType.PESSIMISTIC_WRITE));
        var slotsById = slots.stream().collect(Collectors.toMap(ReservationSlotEntity::getId, slot -> slot));
        if (rows.isEmpty() || rows.stream().anyMatch(row -> !slotsById.containsKey(row.getReservationSlotId()))) {
            throw new IllegalStateException("仮押さえ失効に必要な最新枠がありません: reservationId=" + primary.getId());
        }
        var primarySlot = slotsById.get(primary.getReservationSlotId());
        var now = LocalDateTime.ofInstant(clock.instant(), UserZoneLocalDateTimeParser.SERVER_ZONE);
        boolean expired = reservationRepository.pendingExpireElapsedHours(primary.getBookedAt(), now) >= hours
                || endPassed(primarySlot.getSlotDate(), primarySlot.getEndDate(), primarySlot.getEndTime(), zone);
        if (!expired) {
            progress.completeUnit(epoch, primary.getId(), completedPrefix);
            return 0;
        }
        for (var row : rows) row.cancel(CANCEL_REASON, CancelledBy.SYSTEM);
        reservationRepository.saveAll(rows);
        for (var row : rows) slotService.decrementAndReopen(slotsById.get(row.getReservationSlotId()));
        notifyApplicant(primary, primarySlot);
        progress.completeUnit(epoch, primary.getId(), completedPrefix);
        log.info("仮押さえ自動失効: teamId={}, reservationId={}, groupId={}, {}行",
                primary.getTeamId(), primary.getId(), primary.getGroupId(), rows.size());
        return rows.size();
    }

    /**
     * 申込者本人へ「仮予約が期限切れになった」通知を送る（グループは代表行基準で 1 通のみ）。
     *
     * <p>{@code sourceType/sourceId} は {@code RESERVATION}/予約ID、{@code scopeType/scopeId} は
     * {@code TEAM}/チームID、{@code actorId} はシステム発のため {@code null}
     * （{@link ReservationReminderDispatchBatchService} と同じ決め方）。</p>
     */
    private void notifyApplicant(ReservationEntity primary, ReservationSlotEntity slot) {
        Locale locale = resolveLocale(primary.getUserId());
        String title = messageSource.getMessage(
                "notification.reservation.pendingExpire.title", null,
                "仮予約が期限切れになりました", locale);
        String body;
        if (slot != null) {
            String slotAt = LocalDateTime.of(slot.getSlotDate(), slot.getStartTime()).format(SLOT_AT_FORMAT);
            String slotTitle = slot.getTitle() != null ? slot.getTitle()
                    : messageSource.getMessage("notification.reservation.common.defaultSlotTitle", null, "ご予約", locale);
            body = messageSource.getMessage(
                    "notification.reservation.pendingExpire.body.withSlot",
                    new Object[]{slotAt, slotTitle},
                    slotAt + " の「" + slotTitle + "」は承認期限を過ぎたため自動的にキャンセルされました。",
                    locale);
        } else {
            body = messageSource.getMessage(
                    "notification.reservation.pendingExpire.body.noSlot", null,
                    "お申し込みの仮予約は承認期限を過ぎたため自動的にキャンセルされました。", locale);
        }
        String actionUrl = "/teams/" + primary.getTeamId() + "/reservations";

        notificationHelper.notify(
                primary.getUserId(),
                NOTIFICATION_TYPE,
                title,
                body,
                SOURCE_TYPE,
                primary.getId(),
                NotificationScopeType.TEAM,
                primary.getTeamId(),
                actionUrl,
                null);
    }

    /**
     * 受信者ユーザーの locale を解決する（{@link UserLocaleCache} 経由。D-5: 予約ドメインから
     * auth ドメインのリポジトリへ直接依存しない・{@code common.i18n} 配下の共有サービス経由に限定する）。
     */
    private Locale resolveLocale(Long userId) {
        return Locale.forLanguageTag(userLocaleCache.getLocale(userId));
    }

    /** managed Entityへ依存しない候補の同一性。latest read後も採取値が書き換わらない。 */
    public record PendingExpirePrimary(Long id, Long teamId) {
        public Long getId() { return id; }
        public Long getTeamId() { return teamId; }
    }

    /**
     * 抽出候補とunit実行command。業務更新はprimaryのIDから最新行を再構成する。
     *
     * @param primary 採取した代表IDとチームID
     * @param rows 抽出時の兄弟候補（更新には使用しない）
     * @param slotsById 抽出時の枠候補（更新・通知には使用しない）
     * @param epoch 所有runnerの実行世代
     * @param completedPrefix 検査済み連続prefixの終端
     * @param zone 非TXのunit直前に解決したチームTZ
     */
    public record PendingExpireUnit(
            PendingExpirePrimary primary,
            List<ReservationEntity> rows,
            Map<Long, ReservationSlotEntity> slotsById,
            long epoch,
            long completedPrefix,
            ZoneId zone) {

        /** 抽出候補はまだ実行commandではない。Entityから同一性だけを切り離す。 */
        public PendingExpireUnit(ReservationEntity primary, List<ReservationEntity> rows,
                                 Map<Long, ReservationSlotEntity> slotsById) {
            this(new PendingExpirePrimary(primary.getId(), primary.getTeamId()),
                    List.copyOf(rows), Map.copyOf(slotsById), 0, 0, null);
        }

        public PendingExpireUnit withProgress(long epoch, long completedPrefix, ZoneId zone) {
            return new PendingExpireUnit(primary, rows, slotsById, epoch, completedPrefix, zone);
        }
    }
}
