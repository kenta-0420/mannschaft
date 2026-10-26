package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.Sport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GoCatalog} と {@link SportEventCatalog} 上の GO 配線の UT（test-first・sports/06_go.md §2）。
 */
@DisplayName("GoCatalog（囲碁イベントカタログ）UT")
class GoCatalogTest {

    @Test
    @DisplayName("結果系イベントが許可される（将棋と同一集合）")
    void 結果系イベントが許可される() {
        assertThat(SportEventCatalog.isAllowed(Sport.GO, MatchEventType.GAME_RESULT)).isTrue();
        assertThat(SportEventCatalog.isAllowed(Sport.GO, MatchEventType.MOVE_COUNT)).isTrue();
        assertThat(SportEventCatalog.isAllowed(Sport.GO, MatchEventType.POSITION_PHOTO)).isTrue();
        assertThat(SportEventCatalog.isAllowed(Sport.GO, MatchEventType.COMMENT)).isTrue();
        assertThat(SportEventCatalog.isAllowed(Sport.GO, MatchEventType.OTHER)).isTrue();
    }

    @Test
    @DisplayName("将棋と囲碁の event_type 集合は同一")
    void 将棋と同一集合() {
        assertThat(GoCatalog.EVENT_TYPES).isEqualTo(ShogiCatalog.EVENT_TYPES);
    }

    @Test
    @DisplayName("球技固有イベントは囲碁カタログ外（弾く）")
    void 球技固有イベントは弾く() {
        assertThat(SportEventCatalog.isAllowed(Sport.GO, MatchEventType.GOAL)).isFalse();
        assertThat(SportEventCatalog.isAllowed(Sport.GO, MatchEventType.STARTER)).isFalse();
        assertThat(SportEventCatalog.isAllowed(Sport.GO, MatchEventType.SET_START)).isFalse();
    }
}
