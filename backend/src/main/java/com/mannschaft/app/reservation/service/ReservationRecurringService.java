package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.dto.CreateReservationRequest;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * 定期予約（毎週繰り返し）の<b>オーケストレーター</b>（F03.4.5 §6.2 W2-5）。
 *
 * <h2>本クラスに {@code @Transactional} を付けてはならない</h2>
 * <p>設計書 §6.2 は「満席週はスキップして残りを成立させる」を採用しており、AC-5-5 は
 * <b>週ごと独立トランザクション</b>を要求する。もし本クラスのメソッドを {@code @Transactional} で
 * 囲むと、内側の {@code @Transactional}（1 週ぶんの予約作成）から例外が抜けた時点で Spring が
 * 参加中トランザクションを rollback-only にマークし、外側が例外を握っても最終コミットが
 * {@code UnexpectedRollbackException} で失敗する。すなわち<b>「1 週の失敗が全週を巻き込む」</b>——
 * スキップ設計が実質無効化される（{@code ReservationPendingExpireService} が同じ理由で
 * バッチ本体を非トランザクションにしているのと同型の罠）。</p>
 *
 * <p>週ごとの作成は<b>別 Bean</b>である {@link ReservationService} のトランザクション境界に委譲する
 * （自己呼び出しでは Spring のプロキシを通らず {@code @Transactional} が効かないため、
 * 同一クラス内に切り出しても意味がない）。</p>
 *
 * <h2>認可・レートリミットは series 単位で 1 回だけ</h2>
 * <p>view ゲート（{@link ReservationViewAccessGuard}）と予約作成レートリミット
 * （{@link ReservationCreateRateLimiter}・zone {@code reservation-create}）は<b>本クラスが 1 回だけ</b>
 * 適用する。「1 操作 = 1 意思決定」なので週数ぶん消費しない（AC-5-11・設計書 §6.2 末尾）。
 * 週ごとの作成が通る {@link ReservationService#createReservationForSeries} は
 * ゲートとリミッタを持たないため、<b>本クラス以外から呼んではならない</b>。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationRecurringService {

    /** 繰り返し週数の上限（設計書 §6.2・13 以上は {@code RESERVATION_054}）。 */
    static final int MAX_REPEAT_WEEKS = 12;

    /** 予約閲覧の view ゲート（会員 or 公開）。series 単位で 1 回だけ適用する。 */
    private final ReservationViewAccessGuard viewAccessGuard;
    /** 予約作成レートリミット（zone {@code reservation-create}）。1 series = 1 消費（AC-5-11）。 */
    private final ReservationCreateRateLimiter createRateLimiter;
    /** 週ごとの作成を「独立トランザクション」で実行させるための別 Bean。 */
    private final ReservationService reservationService;
    /** 起点枠の取得（日付・時間帯・ラインの基準）。 */
    private final ReservationSlotService slotService;
    /** 週次枠解決（固定本数クエリ・N+1 なし）。 */
    private final ReservationRecurringSlotResolver slotResolver;
    /** series ID（UUIDv7）採番の基準時刻。テストは固定 Clock を注入する。 */
    private final Clock clock;

    /**
     * 定期予約を作成する（起点週を含む最大 12 週）。
     *
     * <p>起点週の確保に失敗した場合は例外をそのまま伝播させ<b>全体を失敗</b>にする（AC-5-4）。
     * 「起点が無いのに 2 週目以降だけ成立している」という利用者の意図と乖離した結果を作らない。
     * 2 週目以降の失敗はスキップとして明細に記録し、成立分はコミットする（AC-5-5）。</p>
     *
     * @param teamId  チームID
     * @param userId  予約者ユーザーID
     * @param request 作成リクエスト（{@code repeatWeeks} は 2 以上）
     * @return 起点予約のレスポンス（{@code recurring} に series と結果明細を含む）
     */
    public ReservationResponse createRecurring(
            Long teamId, Long userId, CreateReservationRequest request) {
        throw new UnsupportedOperationException("F03.4.5 §6.2 W2-5: 未実装（実装コミットで green 化する）");
    }
}
