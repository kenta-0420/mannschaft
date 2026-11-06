package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.WaitlistStatus;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationWaitlistEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * キャンセル待ち空き通知の永続化まわりを担う実行 Bean（Issue #2997 / CMP-260827-1152 G3）。
 *
 * <p>配送リスナー（{@code ReservationWaitlistNotificationEventListener}）が AFTER_COMMIT の外側（トランザクションなし）で
 * 次の 3 つを呼ぶ。通知配送（{@code sendOne}）自体はリスナーに置く（{@code sendOne} を呼べるのは
 * AFTER_COMMIT リスナーとその private ヘルパのみという番人の規則のため）。</p>
 *
 * <ul>
 *   <li>{@link #findTargets} — 枠が実際に空いているかを再確認し、通知候補の行（entryId・userId・prev）を読む。</li>
 *   <li>{@link #claim} — 行ごとの条件付き UPDATE（{@code WAITING かつ notified_at が NULL または抑止期限以前}）で
 *       {@code notified_at} を取る（独立トランザクション）。取れなければ他の配送が担当済み、または抑止期限内。</li>
 *   <li>{@link #release} — 配送が例外で失敗したとき、{@code WHERE notified_at = claimedAt} で prev に戻す
 *       （独立トランザクション）。古い戻しが、その後に別の周回が取った新しい印を消さない。</li>
 * </ul>
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code ReservationWaitlistService#notifySlotReopened} が 1 つの {@code REQUIRES_NEW} トランザクションで
 * 全員への通知（{@code NotificationHelper#notify}・既定 {@code REQUIRED} で参加）と {@code notified_at} 更新を行い、
 * 行ごとの catch で続行していた。1 人の DB 例外が rollback-only を残し、<b>通知済みの他の行の {@code notified_at} まで
 * 巻き戻って</b>、次の空き復帰で二重通知になる状態だった。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationWaitlistNotifyRunner {

    /** 再通知抑制窓（同一エントリへ 60 分未満は再送しない・§6.1）。 */
    static final Duration RENOTIFY_SUPPRESSION = Duration.ofMinutes(60);

    private final ReservationSlotRepository slotRepository;
    private final ReservationWaitlistEntryRepository waitlistRepository;

    /** 通知候補の 1 行。{@code prevNotifiedAt} は失敗時に戻す値。 */
    public record Target(UUID entryId, Long userId, Instant prevNotifiedAt) {
    }

    /** 通知候補の一覧と、文面に使う枠の情報。 */
    public record Targets(String slotTitle, List<Target> targets) {
    }

    /**
     * 通知対象を読む。枠が消えた／まだ空いていない（CLOSED 化・満席のまま等）／WAITING が 0 件なら {@code null}。
     *
     * <p>抑止期限内に通知済みの行はここで除外する（claim 側でも同じ条件で再判定する）。</p>
     *
     * @param slotId       枠ID
     * @param suppressBefore 抑止期限の基準（これ以前に通知済みの行は再通知してよい。境界は含む）
     */
    @Transactional(readOnly = true)
    public Targets findTargets(Long slotId, LocalDateTime suppressBefore) {
        ReservationSlotEntity slot = slotRepository.findById(slotId).orElse(null);
        if (slot == null || slot.getSlotStatus() != SlotStatus.AVAILABLE) {
            // 枠が消えた / まだ空いていない（CLOSED 化された等）→ 通知しない。
            return null;
        }
        List<Target> targets = waitlistRepository.findBySlotIdAndStatus(slotId, WaitlistStatus.WAITING).stream()
                .filter(entry -> isNotifiable(entry.getNotifiedAt(), suppressBefore))
                .map(entry -> new Target(entry.getId(), entry.getUserId(),
                        entry.getNotifiedAt() == null ? null : entry.getNotifiedAt().toInstant(ZoneOffset.UTC)))
                .toList();
        if (targets.isEmpty()) {
            return null;
        }
        return new Targets(slot.getTitle(), targets);
    }

    /**
     * 1 行の通知の印（{@code notified_at}）を条件付きで先取りする。
     *
     * <p>{@code WAITING} かつ（{@code notified_at} が NULL、または {@code suppressBefore} 以前）のときだけ
     * {@code claimedAt} を書く。抑止期限ちょうど（60 分）は通知する側に倒す。</p>
     *
     * @return 取得できた場合 {@code true}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(UUID entryId, LocalDateTime claimedAt, LocalDateTime suppressBefore) {
        // updated_at はエンティティの @PrePersist/@PreUpdate と同じ基準（JVM 既定ゾーンの壁時計）で入れる。
        // notified_at(claimedAt) は UTC Clock 基準なので、同じ値を流用すると 9 時間ずれる。
        return waitlistRepository.claimNotification(entryId, claimedAt,
                LocalDateTime.now(UserZoneLocalDateTimeParser.SERVER_ZONE), suppressBefore) == 1;
    }

    /**
     * 配送が失敗した行の {@code notified_at} を prev に戻す。
     *
     * <p>{@code WHERE notified_at = claimedAt} のため、自分が取った印だけを戻す。その後に別の周回が
     * 取り直した新しい印は消さない（0 件更新はエラー扱いにしない）。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID entryId, LocalDateTime claimedAt, Instant prevNotifiedAt) {
        LocalDateTime prev = prevNotifiedAt == null ? null : LocalDateTime.ofInstant(prevNotifiedAt, ZoneOffset.UTC);
        int updated = waitlistRepository.releaseNotification(entryId, claimedAt, prev);
        if (updated == 0) {
            log.info("キャンセル待ち通知の印は戻さなかった（別の周回が取り直した等）: entryId={}", entryId);
        }
    }

    private static boolean isNotifiable(LocalDateTime notifiedAt, LocalDateTime suppressBefore) {
        return notifiedAt == null || !notifiedAt.isAfter(suppressBefore);
    }

    /** {@link #claim} / {@link #findTargets} に渡す抑止期限の基準を求める。 */
    public static LocalDateTime suppressBefore(LocalDateTime now) {
        return now.minus(RENOTIFY_SUPPRESSION);
    }
}
