package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.WaitlistStatus;
import com.mannschaft.app.reservation.dto.WaitlistCountResponse;
import com.mannschaft.app.reservation.dto.WaitlistEntryResponse;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.entity.ReservationWaitlistEntryEntity;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationWaitlistEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * キャンセル待ち（waitlist）サービス（F03.4.5 §6.1）。
 *
 * <p>満席枠への登録・本人取消・本人一覧・枠別件数（ADMIN）・予約成立時の消し込み・
 * 空き復帰時の一斉通知・失効クリーンアップを担う。全処理 reservation ドメイン内に閉じる（原則5）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationWaitlistService {

    /** 空き通知の通知種別（HIGH・"RESERVATION" カテゴリ）。 */
    static final String NOTIFICATION_TYPE = "RESERVATION_WAITLIST_OPENING";

    /** 通知 sourceType（F00 visibility / 受信権の判定キー）。 */
    private static final String SOURCE_TYPE = "RESERVATION";

    /** レートリミット zone（登録は軽量操作のため予約作成バケットとは別・§6.4）。 */
    static final String RATE_ZONE = "reservation-waitlist";
    /** 1 ユーザー 1 分 10 回（§6.4）。 */
    static final int RATE_LIMIT = 10;
    static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    /** 1 ユーザー同時 WAITING 上限（§6.1）。 */
    static final long MAX_WAITING_PER_USER = 10L;
    /** 1 枠あたり WAITING 上限（§6.1）。 */
    static final long MAX_WAITING_PER_SLOT = 50L;

    /** 再通知抑制窓（同一エントリへ 60 分未満は再送しない・§6.1）。 */
    static final Duration RENOTIFY_SUPPRESSION = Duration.ofMinutes(60);

    private final ReservationWaitlistEntryRepository waitlistRepository;
    private final ReservationSlotRepository slotRepository;
    private final ReservationViewAccessGuard viewAccessGuard;
    private final ValkeyRateLimiter rateLimiter;
    private final NotificationHelper notificationHelper;
    private final Clock clock;

    // ────────────────────────────────────────────────────────────
    // 登録（会員/公開・view ゲート）
    // ────────────────────────────────────────────────────────────

    /**
     * 満席枠へキャンセル待ちを登録する（§6.1）。
     *
     * <p>認可は予約作成と同一の view ゲート（会員 or 公開）。満席（FULL）枠のみ登録可
     * （AVAILABLE は 400=WAITLIST_SLOT_NOT_FULL・過去/CLOSED は既存検証再利用）。</p>
     *
     * @param teamId チームID
     * @param slotId 枠ID
     * @param userId 登録ユーザーID
     * @return 登録されたエントリ
     */
    @Transactional
    public WaitlistEntryResponse register(Long teamId, Long slotId, Long userId) {
        // 認可（会員 or 公開）。非許可は 403（RESERVATION_021）。
        viewAccessGuard.assertCanView(teamId, userId);

        // レートリミット（1 ユーザー 1 分 10 回・超過は 429）。
        RateLimitResult rate = rateLimiter.tryConsume(
                RATE_ZONE, "user:" + userId, RATE_LIMIT, RATE_WINDOW);
        if (!rate.allowed()) {
            throw new BusinessException(ReservationErrorCode.WAITLIST_RATE_LIMITED);
        }

        // 枠解決（他チームは存在ごと 404 で秘匿）。
        ReservationSlotEntity slot = slotRepository.findByIdAndTeamId(slotId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.SLOT_NOT_FOUND));

        // 過去枠は待つ意味がない（既存 014 を再利用）。
        // Issue #2526: slot_date/start_time は「業務ローカル時刻」であり、注入 Clock（UTC固定）を
        // そのまま LocalDateTime.now(clock) で使うと JVM 既定ゾーンとの差分だけ判定がずれる。
        // ReservationPendingExpireService#findExpirableUnits と同型に、Clock の瞬間を
        // JVM 既定ゾーンで解釈し直してから比較する。
        LocalDateTime slotStart = LocalDateTime.of(slot.getSlotDate(), slot.getStartTime());
        if (slotStart.isBefore(LocalDateTime.now(clock.withZone(ZoneId.systemDefault())))) {
            throw new BusinessException(ReservationErrorCode.PAST_DATE_RESERVATION);
        }
        // CLOSED は受付終了（既存 005 を再利用）。
        if (slot.getSlotStatus() == SlotStatus.CLOSED) {
            throw new BusinessException(ReservationErrorCode.SLOT_CLOSED);
        }
        // 満席でなければ待つ必要はない（そのまま予約すべき）。
        if (slot.getSlotStatus() != SlotStatus.FULL) {
            throw new BusinessException(ReservationErrorCode.WAITLIST_SLOT_NOT_FULL);
        }

        // 重複登録ガード（アプリ層・409）。
        if (waitlistRepository.existsBySlotIdAndUserIdAndStatus(slotId, userId, WaitlistStatus.WAITING)) {
            throw new BusinessException(ReservationErrorCode.WAITLIST_ALREADY_REGISTERED);
        }

        // 上限（ユーザー10 / 枠50・400）。
        if (waitlistRepository.countByUserIdAndStatus(userId, WaitlistStatus.WAITING) >= MAX_WAITING_PER_USER) {
            throw new BusinessException(ReservationErrorCode.WAITLIST_LIMIT_EXCEEDED);
        }
        if (waitlistRepository.countBySlotIdAndStatus(slotId, WaitlistStatus.WAITING) >= MAX_WAITING_PER_SLOT) {
            throw new BusinessException(ReservationErrorCode.WAITLIST_LIMIT_EXCEEDED);
        }

        ReservationWaitlistEntryEntity entry = waitlistRepository.save(
                ReservationWaitlistEntryEntity.builder()
                        .teamId(teamId)
                        .slotId(slotId)
                        .userId(userId)
                        .status(WaitlistStatus.WAITING)
                        .build());
        log.info("キャンセル待ち登録: teamId={}, slotId={}, userId={}, entryId={}",
                teamId, slotId, userId, entry.getId());
        return toResponse(entry, slot);
    }

    // ────────────────────────────────────────────────────────────
    // 本人取消
    // ────────────────────────────────────────────────────────────

    /**
     * 本人の WAITING エントリを取消（CANCELLED）にする（§6.1）。
     *
     * <p>解決は (slot, user, WAITING) で行うため、他人のエントリは構造的に掴めない
     * （userId 絞り込み＝IDOR 秘匿）。自分の WAITING が無ければ 404=WAITLIST_ENTRY_NOT_FOUND。</p>
     *
     * @param teamId チームID（監査用途）
     * @param slotId 枠ID
     * @param userId 取消するユーザーID（本人）
     */
    @Transactional
    public void cancelOwn(Long teamId, Long slotId, Long userId) {
        ReservationWaitlistEntryEntity entry = waitlistRepository
                .findBySlotIdAndUserIdAndStatus(slotId, userId, WaitlistStatus.WAITING)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.WAITLIST_ENTRY_NOT_FOUND));
        entry.cancel();
        waitlistRepository.save(entry);
        log.info("キャンセル待ち取消: teamId={}, slotId={}, userId={}, entryId={}",
                teamId, slotId, userId, entry.getId());
    }

    // ────────────────────────────────────────────────────────────
    // 本人一覧
    // ────────────────────────────────────────────────────────────

    /**
     * 本人の WAITING 一覧を取得する（枠情報同梱・新しい順・§6.1）。
     *
     * @param userId 本人ユーザーID
     * @return 待ち一覧
     */
    public List<WaitlistEntryResponse> listMine(Long userId) {
        List<ReservationWaitlistEntryEntity> entries =
                waitlistRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, WaitlistStatus.WAITING);
        if (entries.isEmpty()) {
            return List.of();
        }
        // N+1 回避: 枠を一括取得してからマッピングする。
        Set<Long> slotIds = entries.stream()
                .map(ReservationWaitlistEntryEntity::getSlotId)
                .collect(Collectors.toSet());
        Map<Long, ReservationSlotEntity> slotMap = slotRepository.findAllById(slotIds).stream()
                .collect(Collectors.toMap(ReservationSlotEntity::getId, s -> s));
        return entries.stream()
                .map(e -> toResponse(e, slotMap.get(e.getSlotId())))
                .toList();
    }

    // ────────────────────────────────────────────────────────────
    // 枠別件数（ADMIN 専用）
    // ────────────────────────────────────────────────────────────

    /**
     * 枠別の WAITING 件数を取得する（ADMIN 専用・§6.1）。
     *
     * <p>認可順序（§6.1 一意定義）: パスの {@code teamId} に対する {@code isScopeAdmin} が
     * コントローラ層で先（非 ADMIN は 403）→ 本メソッドで {@code findByIdAndTeamId} により slot を解決し、
     * 他チームの slot は 404 で秘匿する（403→404 の順）。</p>
     *
     * @param teamId チームID
     * @param slotId 枠ID
     * @return 件数レスポンス
     */
    public WaitlistCountResponse countWaiting(Long teamId, Long slotId) {
        // 他チームの slot は存在ごと 404 で秘匿（IDOR）。
        slotRepository.findByIdAndTeamId(slotId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.SLOT_NOT_FOUND));
        long count = waitlistRepository.countBySlotIdAndStatus(slotId, WaitlistStatus.WAITING);
        return WaitlistCountResponse.builder().slotId(slotId).waitingCount(count).build();
    }

    // ────────────────────────────────────────────────────────────
    // 予約成立時の消し込み（同一 tx・reservation ドメイン内）
    // ────────────────────────────────────────────────────────────

    /**
     * 予約成立時に同一 (slot, user) の WAITING を CONVERTED へ消し込む（§6.1）。
     *
     * <p>{@code createReservation} / グループ作成の成功時に同一トランザクション内で呼ぶ。
     * WAITING が無ければ何もしない（べき等）。</p>
     *
     * @param slotId 枠ID
     * @param userId ユーザーID
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markConvertedIfExists(Long slotId, Long userId) {
        waitlistRepository.findBySlotIdAndUserIdAndStatus(slotId, userId, WaitlistStatus.WAITING)
                .ifPresent(entry -> {
                    entry.markConverted();
                    waitlistRepository.save(entry);
                    log.info("キャンセル待ち消し込み(CONVERTED): slotId={}, userId={}, entryId={}",
                            slotId, userId, entry.getId());
                });
    }

    // ────────────────────────────────────────────────────────────
    // 空き復帰時の一斉通知（AFTER_COMMIT リスナーから REQUIRES_NEW で呼ばれる）
    // ────────────────────────────────────────────────────────────

    /**
     * 枠が空きに転じた瞬間に、当該枠の WAITING 登録者全員へ一斉通知する（§6.1）。
     *
     * <p>再通知抑制: 同一エントリへの通知は {@code notified_at} から 60 分未満なら送らない
     * （キャンセル連発時の通知洪水防止）。通知した行は {@code notified_at} を更新する。</p>
     *
     * <p>トランザクション: AFTER_COMMIT リスナーから呼ばれるため新規 tx（REQUIRES_NEW）で書き込む
     * （{@code feedback_transactional_event_listener_requires_new}）。枠が実際に AVAILABLE でない
     * （まだ満席・CLOSED 等）場合は通知しない（空き復帰の実体を再確認する）。</p>
     *
     * @param teamId チームID
     * @param slotId 枠ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifySlotReopened(Long teamId, Long slotId) {
        ReservationSlotEntity slot = slotRepository.findById(slotId).orElse(null);
        if (slot == null || slot.getSlotStatus() != SlotStatus.AVAILABLE) {
            // 枠が消えた / まだ空いていない（CLOSED 化された等）→ 通知しない。
            return;
        }
        // 追加防御: WAITING 行を悲観ロック（FOR UPDATE）で掴んでから notified_at 抑制判定＋更新を原子化する。
        // これにより万一イベントが並行で複数回起動しても「両方が notified_at=NULL を読んで二重 push」を封じる。
        List<ReservationWaitlistEntryEntity> waiting =
                waitlistRepository.findBySlotIdAndStatusForUpdate(slotId, WaitlistStatus.WAITING);
        if (waiting.isEmpty()) {
            return;
        }

        // Issue #2526 検討済み・変更しない: ここでの比較相手は notifiedAt であり、
        // notifiedAt 自体も本メソッド内で LocalDateTime.now(clock) から書かれる（markNotified(now)）。
        // 業務ローカル時刻（slot_date/start_time）は一切絡まない UTC Clock 同士の自己完結した比較のため、
        // .withZone(ZoneId.systemDefault()) に変えると逆に他の判定基準とズレて壊れる。一律置換禁止。
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime suppressBefore = now.minus(RENOTIFY_SUPPRESSION);
        String slotTitle = slot.getTitle() != null ? slot.getTitle() : "ご予約";
        String title = "キャンセルが出ました";
        String body = String.format("「%s」に空きが出ました。お早めにご予約ください。", slotTitle);
        String actionUrl = "/teams/" + teamId + "/reservations";

        int notified = 0;
        for (ReservationWaitlistEntryEntity entry : waiting) {
            // 再通知抑制: 直近 60 分以内に通知済みならスキップ。
            if (entry.getNotifiedAt() != null && entry.getNotifiedAt().isAfter(suppressBefore)) {
                continue;
            }
            try {
                notificationHelper.notify(
                        entry.getUserId(),
                        NOTIFICATION_TYPE,
                        NotificationPriority.HIGH,
                        title,
                        body,
                        SOURCE_TYPE,
                        slotId,
                        NotificationScopeType.TEAM,
                        teamId,
                        actionUrl,
                        null);
                entry.markNotified(now);
                waitlistRepository.save(entry);
                notified++;
            } catch (Exception e) {
                // 1 件の失敗が他の宛先を巻き込まないよう行単位で握らず記録する。
                log.error("キャンセル待ち空き通知の送出に失敗しました: entryId={}, slotId={}, userId={}",
                        entry.getId(), slotId, entry.getUserId(), e);
            }
        }
        log.info("キャンセル待ち空き通知: teamId={}, slotId={}, 対象{}件中 {}件送出",
                teamId, slotId, waiting.size(), notified);
    }

    // ────────────────────────────────────────────────────────────
    // 失効クリーンアップ（バッチから呼ばれる）
    // ────────────────────────────────────────────────────────────

    /**
     * 枠開始時刻を過ぎた WAITING エントリを物理削除する（§6.1・履歴価値なし）。
     *
     * @return 削除件数
     */
    @Transactional
    public int purgeExpiredWaiting() {
        // Issue #2526: 枠開始（slot_date/start_time・業務ローカル時刻）と比較するため、
        // Clock の瞬間を JVM 既定ゾーンで解釈し直してから比較する（register と同型）。
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneId.systemDefault()));
        List<ReservationWaitlistEntryEntity> expired = waitlistRepository.findExpiredWaiting(
                WaitlistStatus.WAITING, now.toLocalDate(), now.toLocalTime());
        if (expired.isEmpty()) {
            return 0;
        }
        waitlistRepository.deleteAll(expired);
        log.info("キャンセル待ち失効クリーンアップ: {}件を物理削除", expired.size());
        return expired.size();
    }

    // ────────────────────────────────────────────────────────────
    // 内部ヘルパー
    // ────────────────────────────────────────────────────────────

    private WaitlistEntryResponse toResponse(ReservationWaitlistEntryEntity entry, ReservationSlotEntity slot) {
        WaitlistEntryResponse.WaitlistEntryResponseBuilder builder = WaitlistEntryResponse.builder()
                .id(entry.getId())
                .teamId(entry.getTeamId())
                .slotId(entry.getSlotId())
                .status(entry.getStatus().name())
                .createdAt(entry.getCreatedAt());
        if (slot != null) {
            builder.slotDate(slot.getSlotDate())
                    .startTime(slot.getStartTime())
                    .endTime(slot.getEndTime())
                    .slotTitle(slot.getTitle());
        }
        return builder.build();
    }
}
