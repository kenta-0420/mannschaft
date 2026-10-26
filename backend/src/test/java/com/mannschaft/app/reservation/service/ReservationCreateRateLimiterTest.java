package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import com.mannschaft.app.reservation.ReservationErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 予約作成レートリミッタ単体の検証（F03.4.5 §6.4・受け入れ条件 AC-6-13 / AC-6-14 と zone 定義）。
 *
 * <p><b>なぜ実 Valkey を使わないか</b>: CI 環境には Valkey が存在せず
 * （{@code application-ci.yml}「CI 環境には Valkey が存在しない」）、{@link ValkeyRateLimiter} は
 * Redis Bean 不在時に <b>fail-open で {@code allowed=true}</b> を返す。実 Valkey を前提に
 * 「6 回目が 429」を書くと CI では必ず素通りして<b>偽 green</b> になる。よって上限判定は
 * {@link ValkeyRateLimiter} をモックして戻り値を制御して検証し、fail-open の側は
 * 逆に Bean 不在の実挙動で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("予約作成レートリミッタ 単体テスト（F03.4.5 §6.4）")
class ReservationCreateRateLimiterTest {

    private static final Long USER_ID = 4242L;

    @Mock
    private ValkeyRateLimiter valkeyRateLimiter;

    private static RateLimitResult allowed() {
        return new RateLimitResult(true, 5, 4, 0L, 1L);
    }

    private static RateLimitResult denied() {
        return new RateLimitResult(false, 5, 0, 0L, 60L);
    }

    // ────────────────────────────────────────────────────────────
    // zone / limit の定義（単枠・グループが共有する唯一の正準値）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("zone は reservation-create・上限は 1 ユーザー 1 分 5 回である")
    void zoneと上限が設計どおり() {
        assertThat(ReservationCreateRateLimiter.RATE_ZONE).isEqualTo("reservation-create");
        assertThat(ReservationCreateRateLimiter.RATE_LIMIT).isEqualTo(5);
        assertThat(ReservationCreateRateLimiter.RATE_WINDOW).isEqualTo(Duration.ofMinutes(1));
        // キャンセル待ちは別 zone・別上限のまま（予約バケットを消費させない・§6.4）
        assertThat(ReservationWaitlistService.RATE_ZONE)
                .as("キャンセル待ちは予約作成と別 zone であること")
                .isNotEqualTo(ReservationCreateRateLimiter.RATE_ZONE);
    }

    @Test
    @DisplayName("消費キーは user:{userId}（ユーザー軸。チーム軸の generate リミットと混ざらない）")
    void 消費キーはユーザー軸() {
        ReservationCreateRateLimiter limiter = new ReservationCreateRateLimiter(valkeyRateLimiter);
        given(valkeyRateLimiter.tryConsume(anyString(), anyString(), anyInt(), any()))
                .willReturn(allowed());

        limiter.assertNotRateLimited(USER_ID);

        org.mockito.Mockito.verify(valkeyRateLimiter).tryConsume(
                "reservation-create", "user:" + USER_ID, 5, Duration.ofMinutes(1));
    }

    // ────────────────────────────────────────────────────────────
    // 上限超過の挙動
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("上限内は素通しする")
    void 上限内は通過する() {
        ReservationCreateRateLimiter limiter = new ReservationCreateRateLimiter(valkeyRateLimiter);
        given(valkeyRateLimiter.tryConsume(anyString(), anyString(), anyInt(), any()))
                .willReturn(allowed());

        assertThatCode(() -> limiter.assertNotRateLimited(USER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("上限超過は RESERVATION_053 を投げる")
    void 上限超過はRESERVATION_053() {
        ReservationCreateRateLimiter limiter = new ReservationCreateRateLimiter(valkeyRateLimiter);
        given(valkeyRateLimiter.tryConsume(anyString(), anyString(), anyInt(), any()))
                .willReturn(denied());

        assertThatThrownBy(() -> limiter.assertNotRateLimited(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.RESERVATION_CREATE_RATE_LIMITED);
    }

    // ────────────────────────────────────────────────────────────
    // AC-6-13: RESERVATION_053 は 429 で返る（既定の 400 のままになっていない）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-6-13: RESERVATION_053 は HTTP 429 にマップされる（400 ではない）")
    void エラーコードは429にマップされる() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(new StaticMessageSource());

        var response = handler.handleBusinessException(
                new BusinessException(ReservationErrorCode.RESERVATION_CREATE_RATE_LIMITED));

        assertThat(response.getStatusCode())
                .as("GlobalExceptionHandler の個別マップに載せないと Severity.WARN 既定の 400 になってしまう")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ReservationErrorCode.RESERVATION_CREATE_RATE_LIMITED.getCode())
                .isEqualTo("RESERVATION_053");
    }

    // ────────────────────────────────────────────────────────────
    // AC-6-14: Valkey 障害（Bean 不在）時は fail-open で通す
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-6-14: Redis Bean 不在（Valkey 障害相当）では fail-open で通す")
    void Valkey不在はfailOpenで通過する() {
        // 実 ValkeyRateLimiter を Bean 不在状態で構築する（CI の実構成と同じ）。
        ObjectProvider<StringRedisTemplate> emptyRedis = emptyProvider();
        ObjectProvider<MeterRegistry> emptyMeter = emptyProvider();
        ReservationCreateRateLimiter limiter =
                new ReservationCreateRateLimiter(new ValkeyRateLimiter(emptyRedis, emptyMeter));

        // 上限（5 回）を超える回数を叩いても、可用性優先で 1 度も 429 にしない。
        assertThatCode(() -> {
            for (int i = 0; i < 20; i++) {
                limiter.assertNotRateLimited(USER_ID);
            }
        }).as("レートリミット基盤の障害でサービスを止めない（docs/security/06 §4.3）")
                .doesNotThrowAnyException();
    }

    /** 何も提供しない {@link ObjectProvider}（{@code getIfAvailable()} が null を返す）。 */
    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public T getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }
        };
    }
}
