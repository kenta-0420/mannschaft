package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.reservation.RecurringWeekSkipReason;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.dto.CreateReservationRequest;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

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
        int repeatWeeks = request.getRepeatWeeks() == null ? 1 : request.getRepeatWeeks();

        // 1. 認可（view ゲート）。単枠 createReservation と同じ述語・同じ順序。
        //    レートリミットより先に判定する（403 のはずが 429 で返る状況を作らない）。
        viewAccessGuard.assertCanView(teamId, userId);

        // 2. 上限検証（13 以上は 400 = RESERVATION_054・AC-5-3）。
        //    レートリミットより先に置く。上限超過のリクエストでバケットを消費させない。
        if (repeatWeeks > MAX_REPEAT_WEEKS) {
            throw new BusinessException(ReservationErrorCode.RECURRING_RESERVATION_LIMIT_EXCEEDED);
        }

        // 3. レートリミット（AC-5-11: 1 series = 1 消費。週数ぶん消費しない）。
        createRateLimiter.assertNotRateLimited(userId);

        // Issue #2538: 起点枠の reservationSlotId もリクエスト由来のため teamId スコープで解決する。
        // 他チームの枠 id を渡した場合は SLOT_NOT_FOUND（404）で秘匿する。
        ReservationSlotEntity baseSlot = slotService.getSlotEntity(teamId, request.getReservationSlotId());
        UUID seriesId = UuidV7.generate(clock);

        // 4. 起点週。失敗（満席・予約不可枠・重複 等）は<b>そのまま伝播させて全体を失敗</b>にする（AC-5-4）。
        //    「起点が無いのに 2 週目以降だけ成立している」という利用者の意図と乖離した結果を作らない。
        ReservationResponse baseResponse = reservationService.createReservationForSeries(
                teamId, userId, weekRequest(request, request.getReservationSlotId()), seriesId);

        List<ReservationResponse.RecurringWeekOutcomeDto> created = new ArrayList<>();
        List<ReservationResponse.RecurringWeekOutcomeDto> skipped = new ArrayList<>();
        created.add(new ReservationResponse.RecurringWeekOutcomeDto(
                baseSlot.getSlotDate(), null, baseResponse.getId()));

        // 5. 2 週目以降。ロック順序を安定させるため必ず slot_date 昇順に並べ直す（AC-5-6）。
        //    解決結果の順序に依存すると、実装や DB の並びが変わった瞬間にデッドロック耐性を失う。
        List<ReservationRecurringSlotResolver.WeekCandidate> candidates =
                slotResolver.resolve(teamId, userId, baseSlot, repeatWeeks).stream()
                        .sorted(Comparator.comparing(ReservationRecurringSlotResolver.WeekCandidate::date))
                        .toList();

        for (ReservationRecurringSlotResolver.WeekCandidate candidate : candidates) {
            if (!candidate.bookable()) {
                skipped.add(new ReservationResponse.RecurringWeekOutcomeDto(
                        candidate.date(), candidate.skipReason(), null));
                continue;
            }
            try {
                // 別 Bean のトランザクション境界に入るため、この 1 週は独立トランザクションになる（AC-5-5）。
                ReservationResponse weekResponse = reservationService.createReservationForSeries(
                        teamId, userId, weekRequest(request, candidate.slotId()), seriesId);
                created.add(new ReservationResponse.RecurringWeekOutcomeDto(
                        candidate.date(), null, weekResponse.getId()));
            } catch (BusinessException e) {
                // 解決から確保までの間に他者が埋めた等の競合。握り潰さず理由を明細に載せ、ログにも残す。
                RecurringWeekSkipReason reason = mapSkipReason(e.getErrorCode());
                log.warn("定期予約: {} の回をスキップしました（teamId={}, userId={}, slotId={}, errorCode={}, reason={}）",
                        candidate.date(), teamId, userId, candidate.slotId(),
                        e.getErrorCode().getCode(), reason, e);
                skipped.add(new ReservationResponse.RecurringWeekOutcomeDto(
                        candidate.date(), reason, null));
            }
        }

        // 6. 成立が起点 1 件だけなら series を解除する（AC-5-13）。
        //    1 行だけの series は単発予約と区別する意味がなく、FE に「繰り返し」を誤表示させる。
        UUID effectiveSeriesId = seriesId;
        if (created.size() == 1) {
            reservationService.clearRecurringSeries(baseResponse.getId());
            effectiveSeriesId = null;
        }

        log.info("定期予約作成: teamId={}, userId={}, repeatWeeks={}, 成立={}件, スキップ={}件, seriesId={}",
                teamId, userId, repeatWeeks, created.size(), skipped.size(), effectiveSeriesId);

        return baseResponse.toBuilder()
                .recurring(new ReservationResponse.RecurringSeriesDto(
                        effectiveSeriesId, repeatWeeks, created.size(), skipped.size(),
                        List.copyOf(created), List.copyOf(skipped)))
                .build();
    }

    /**
     * 当該週ぶんの作成リクエストを組み立てる。
     *
     * <p>{@code repeatWeeks} は<b>載せない</b>（週ごとの作成が再帰的に定期予約として扱われる余地を消す）。
     * 備考（{@code userNote}）は全週に引き継ぐ——「毎週この内容で」という 1 回の意思表示なので、
     * 起点だけに残すと 2 週目以降の予約から情報が落ちる。</p>
     */
    private CreateReservationRequest weekRequest(CreateReservationRequest base, Long slotId) {
        return new CreateReservationRequest(slotId, base.getLineId(), base.getUserNote(), null);
    }

    /**
     * 確保時の業務エラーをスキップ理由へ写像する。
     *
     * <p>未知のエラーコードを {@link RecurringWeekSkipReason#FULL} などに丸めると会員に嘘の理由を伝えるため、
     * {@link RecurringWeekSkipReason#UNAVAILABLE} に落として<b>実コードは WARN ログに残す</b>
     * （呼び出し側でログ出力している）。</p>
     */
    private RecurringWeekSkipReason mapSkipReason(ErrorCode errorCode) {
        if (errorCode == ReservationErrorCode.SLOT_FULL) {
            return RecurringWeekSkipReason.FULL;
        }
        if (errorCode == ReservationErrorCode.SLOT_CLOSED) {
            return RecurringWeekSkipReason.CLOSED;
        }
        if (errorCode == ReservationErrorCode.BLOCKED_TIME_CONFLICT) {
            return RecurringWeekSkipReason.BLOCKED;
        }
        if (errorCode == ReservationErrorCode.DUPLICATE_RESERVATION) {
            return RecurringWeekSkipReason.ALREADY_RESERVED;
        }
        if (errorCode == ReservationErrorCode.SLOT_NOT_FOUND) {
            return RecurringWeekSkipReason.NOT_GENERATED;
        }
        return RecurringWeekSkipReason.UNAVAILABLE;
    }
}
