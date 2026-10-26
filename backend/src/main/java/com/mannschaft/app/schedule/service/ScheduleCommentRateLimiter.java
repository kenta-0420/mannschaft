package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.ScheduleCommentErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F03.16 予定コメント投稿のレート制限（設計書 §10.2・AC-32）。
 *
 * <h2>Clock を注入可能にする理由</h2>
 * <p>試練 AC-32 は「実時間の {@code sleep} に依存しない」ことを要求する。本番閾値のまま
 * 実時間で 31 回叩くテストは CI を flaky にする（memory 相当の教訓）。{@link Clock} を
 * コンストラクタ注入にし、テストでは固定 {@link Clock} を差し替えて窓を制御する。</p>
 *
 * <h2>閾値をプロファイルで変える</h2>
 * <p>{@code mannschaft.schedule-comment.rate-limit.max-per-window} /
 * {@code ...window-seconds} を test プロファイルで縮小する（既定 30 回/分）。</p>
 *
 * <h2>実装方式</h2>
 * <p>ユーザーごとの直近タイムスタンプをスライディングウィンドウで保持するシンプルなインメモリ実装。
 * マルチインスタンス環境での厳密な一貫性は求めない（防止したいのは連投荒らし・ボットであり、
 * 多少緩くても実害は小さい。既存 {@code ScheduleAttendanceService} のインメモリバケットと同方針）。</p>
 */
@Slf4j
@Component
public class ScheduleCommentRateLimiter {

    private final Clock clock;
    private final int maxPerWindow;
    private final Duration window;
    private final Map<Long, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public ScheduleCommentRateLimiter(
            Clock clock,
            @Value("${mannschaft.schedule-comment.rate-limit.max-per-window:30}") int maxPerWindow,
            @Value("${mannschaft.schedule-comment.rate-limit.window-seconds:60}") long windowSeconds) {
        this.clock = clock;
        this.maxPerWindow = maxPerWindow;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    /**
     * 投稿を1件許可対象として記録する。上限超過なら 429 {@code SCHEDULE_COMMENT_012}。
     *
     * @param userId 投稿ユーザー
     * @throws BusinessException 上限超過時
     */
    public void requireWithinLimit(Long userId) {
        if (userId == null) {
            return;
        }
        Instant now = clock.instant();
        Instant windowStart = now.minus(window);
        Deque<Instant> deque = hits.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst().isBefore(windowStart)) {
                deque.pollFirst();
            }
            if (deque.size() >= maxPerWindow) {
                log.warn("SCHEDULE_COMMENT レート制限超過: userId={}", userId);
                throw new BusinessException(ScheduleCommentErrorCode.RATE_LIMITED);
            }
            deque.addLast(now);
        }
    }
}
