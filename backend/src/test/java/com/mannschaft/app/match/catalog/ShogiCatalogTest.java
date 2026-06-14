package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.Sport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ShogiCatalog} と {@link SportEventCatalog} 上の SHOGI 配線の UT
 * （test-first・sports/05_shogi.md §2）。
 */
@DisplayName("ShogiCatalog（将棋イベントカタログ）UT")
class ShogiCatalogTest {

    @Test
    @DisplayName("結果系イベント（GAME_RESULT/MOVE_COUNT/POSITION_PHOTO/COMMENT/OTHER）が許可される")
    void 結果系イベントが許可される() {
        assertThat(SportEventCatalog.isAllowed(Sport.SHOGI, MatchEventType.GAME_RESULT)).isTrue();
        assertThat(SportEventCatalog.isAllowed(Sport.SHOGI, MatchEventType.MOVE_COUNT)).isTrue();
        assertThat(SportEventCatalog.isAllowed(Sport.SHOGI, MatchEventType.POSITION_PHOTO)).isTrue();
        assertThat(SportEventCatalog.isAllowed(Sport.SHOGI, MatchEventType.COMMENT)).isTrue();
        assertThat(SportEventCatalog.isAllowed(Sport.SHOGI, MatchEventType.OTHER)).isTrue();
    }

    @Test
    @DisplayName("出場交代・得点・カード系イベントは将棋カタログ外（弾く）")
    void 球技固有イベントは弾く() {
        assertThat(SportEventCatalog.isAllowed(Sport.SHOGI, MatchEventType.STARTER)).isFalse();
        assertThat(SportEventCatalog.isAllowed(Sport.SHOGI, MatchEventType.SUB_IN)).isFalse();
        assertThat(SportEventCatalog.isAllowed(Sport.SHOGI, MatchEventType.GOAL)).isFalse();
        assertThat(SportEventCatalog.isAllowed(Sport.SHOGI, MatchEventType.YELLOW_CARD)).isFalse();
        assertThat(SportEventCatalog.isAllowed(Sport.SHOGI, MatchEventType.POINT)).isFalse();
        assertThat(SportEventCatalog.isAllowed(Sport.SHOGI, MatchEventType.PERIOD_START)).isFalse();
    }

    @Test
    @DisplayName("EVENT_TYPES は不変集合")
    void 不変集合である() {
        assertThat(ShogiCatalog.EVENT_TYPES).contains(MatchEventType.GAME_RESULT);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> ShogiCatalog.EVENT_TYPES.add(MatchEventType.GOAL));
    }
}
