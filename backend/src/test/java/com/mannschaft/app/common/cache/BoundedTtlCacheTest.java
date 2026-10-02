package com.mannschaft.app.common.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BoundedTtlCache} の単体テスト（Issue #2487 項目 1）。
 *
 * <p>「件数上限つき LRU ＋ 参照時の期限切れ回収」という方式そのものを固定する。
 * 期限切れは {@link Clock} を差し替えて<b>スリープ無しで決定論的に</b>検証する。</p>
 */
@DisplayName("BoundedTtlCache 単体テスト（件数上限つき LRU ＋ TTL）")
class BoundedTtlCacheTest {

    private static final Duration TTL = Duration.ofMinutes(5);

    @Test
    @DisplayName("put した値は TTL 内なら get で返る")
    void TTL内は取得できる() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-29T00:00:00Z"));
        BoundedTtlCache<Long, String> cache = new BoundedTtlCache<>(10, TTL, clock);

        cache.put(1L, "Asia/Tokyo");
        clock.advance(Duration.ofMinutes(4));

        assertThat(cache.get(1L)).isEqualTo("Asia/Tokyo");
    }

    @Test
    @DisplayName("TTL を過ぎた値は get で null になり、エントリ自体も回収される（期限切れのゴミを残さない）")
    void TTL経過で期限切れ回収される() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-29T00:00:00Z"));
        BoundedTtlCache<Long, String> cache = new BoundedTtlCache<>(10, TTL, clock);

        cache.put(1L, "Asia/Tokyo");
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThat(cache.get(1L)).as("期限切れは miss として null を返す").isNull();
        assertThat(cache.size()).as("期限切れエントリはその場で削除される").isZero();
    }

    @Test
    @DisplayName("上限を超えて put してもエントリ数は上限で頭打ちになる")
    void 件数上限で頭打ちになる() {
        BoundedTtlCache<Long, String> cache = new BoundedTtlCache<>(3, TTL);

        for (long key = 1; key <= 1000; key++) {
            cache.put(key, "v" + key);
        }

        assertThat(cache.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("追い出しは LRU 順（get で触ったエントリは残り、最も古い参照が消える）")
    void 追い出しはLRU順() {
        BoundedTtlCache<Long, String> cache = new BoundedTtlCache<>(3, TTL);
        cache.put(1L, "a");
        cache.put(2L, "b");
        cache.put(3L, "c");

        // 1 を参照して「直近利用」へ押し上げる → 次の追い出し対象は 2 になる
        assertThat(cache.get(1L)).isEqualTo("a");
        cache.put(4L, "d");

        assertThat(cache.get(1L)).as("直近参照した 1 は残る").isEqualTo("a");
        assertThat(cache.get(2L)).as("最も古い参照の 2 が追い出される").isNull();
        assertThat(cache.get(3L)).isEqualTo("c");
        assertThat(cache.get(4L)).isEqualTo("d");
    }

    @Test
    @DisplayName("evict すると即座に消える")
    void evictで即消える() {
        BoundedTtlCache<Long, String> cache = new BoundedTtlCache<>(10, TTL);
        cache.put(1L, "a");

        cache.evict(1L);

        assertThat(cache.get(1L)).isNull();
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("上限 0 以下は生成時に拒否する（無制限キャッシュを設定ミスで復活させない）")
    void 上限0以下は拒否する() {
        assertThatThrownBy(() -> new BoundedTtlCache<Long, String>(0, TTL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 経過時間を明示的に進められるテスト用 {@link Clock}。 */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
