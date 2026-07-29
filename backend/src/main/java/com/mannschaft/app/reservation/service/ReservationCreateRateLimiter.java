package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import com.mannschaft.app.reservation.ReservationErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 予約作成のレートリミッタ（F03.4.5 §6.4・W2-6）。
 *
 * <p><b>単枠予約とグループ予約で同一バケットを共有する</b>ための単一実装。zone 名とリミット値を
 * {@link ReservationService} と {@link ReservationGroupService} の 2 箇所に重複定義すると、
 * 片方だけ値がずれた瞬間に「単枠 5 回＋グループ 5 回」の買い占めが可能になる。定義を本クラスへ
 * 一元化し、両経路が必ず本クラスを経由する構造にする。</p>
 *
 * <p>実装は同一ドメインの先行実装 {@link ReservationWaitlistService}（zone 定数 3 本＋
 * {@code "user:" + userId} のキー書式）を写経している。キャンセル待ち登録は別 zone
 * {@code reservation-waitlist}（1 分 10 回）のままで、本バケットを消費しない（§6.4）。</p>
 *
 * <p><b>fail-open</b>: {@link ValkeyRateLimiter} は Valkey 障害時・Redis Bean 不在時に
 * {@code allowed=true} を返す（可用性優先・{@code docs/security/06 §4.3}）。本クラスはその判定を
 * そのまま尊重し、独自のフォールバックを持たない。</p>
 */
@Component
@RequiredArgsConstructor
public class ReservationCreateRateLimiter {

    /** §6.4: 予約作成の zone。単枠・グループで共有する唯一のバケット。 */
    static final String RATE_ZONE = "reservation-create";

    /** §6.4: 1 ユーザー 1 分 5 回。 */
    static final int RATE_LIMIT = 5;

    /** §6.4: 固定ウィンドウ長（1 分）。 */
    static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private final ValkeyRateLimiter rateLimiter;

    /**
     * 予約作成 1 回分を消費し、上限を超えていれば 429 を投げる。
     *
     * @param userId 予約者ユーザーID（制限主体）
     * @throws com.mannschaft.app.common.BusinessException 上限超過時
     *         （{@code RESERVATION_053} → HTTP 429）
     */
    public void assertNotRateLimited(Long userId) {
        RateLimitResult rate = rateLimiter.tryConsume(
                RATE_ZONE, "user:" + userId, RATE_LIMIT, RATE_WINDOW);
        if (!rate.allowed()) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_CREATE_RATE_LIMITED);
        }
    }
}
