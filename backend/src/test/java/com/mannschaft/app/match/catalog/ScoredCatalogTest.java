package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.Sport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScoredCatalog} と {@link SportEventCatalog} 上の FIGURE_SKATING / GYMNASTICS 配線の UT
 * （test-first・sports/07_scored.md §3 / §10 / 01 §D.8）。
 *
 * <p>採点競技は結果系少数（SCORE_SUBMITTED/COMMENT/OTHER）のみを許可し、出場交代・得点・カード・
 * 盤上系のイベントは弾く（MVP は合計点のみ・演技中の逐次イベントを記録しない）。フィギュア・体操は
 * 同一 event_type 集合を共有する。</p>
 */
@DisplayName("ScoredCatalog（採点競技イベントカタログ）UT")
class ScoredCatalogTest {

    @Test
    @DisplayName("採点結果系イベント（SCORE_SUBMITTED/COMMENT/OTHER）が両競技で許可される")
    void 結果系イベントが許可される() {
        for (Sport sport : new Sport[]{Sport.FIGURE_SKATING, Sport.GYMNASTICS}) {
            assertThat(SportEventCatalog.isAllowed(sport, MatchEventType.SCORE_SUBMITTED)).isTrue();
            assertThat(SportEventCatalog.isAllowed(sport, MatchEventType.COMMENT)).isTrue();
            assertThat(SportEventCatalog.isAllowed(sport, MatchEventType.OTHER)).isTrue();
        }
    }

    @Test
    @DisplayName("出場交代・得点・カード・盤上系イベントは採点競技カタログ外（弾く）")
    void 他競技固有イベントは弾く() {
        for (Sport sport : new Sport[]{Sport.FIGURE_SKATING, Sport.GYMNASTICS}) {
            assertThat(SportEventCatalog.isAllowed(sport, MatchEventType.STARTER)).isFalse();
            assertThat(SportEventCatalog.isAllowed(sport, MatchEventType.SUB_IN)).isFalse();
            assertThat(SportEventCatalog.isAllowed(sport, MatchEventType.GOAL)).isFalse();
            assertThat(SportEventCatalog.isAllowed(sport, MatchEventType.YELLOW_CARD)).isFalse();
            assertThat(SportEventCatalog.isAllowed(sport, MatchEventType.POINT)).isFalse();
            assertThat(SportEventCatalog.isAllowed(sport, MatchEventType.GAME_RESULT)).isFalse();
            assertThat(SportEventCatalog.isAllowed(sport, MatchEventType.MOVE_COUNT)).isFalse();
            assertThat(SportEventCatalog.isAllowed(sport, MatchEventType.PERIOD_START)).isFalse();
        }
    }

    @Test
    @DisplayName("フィギュアと体操は同一 event_type 集合を共有する（MVP は合計点に還元され競技差が消える）")
    void 両競技は同一集合を共有する() {
        assertThat(SportEventCatalog.allowedEventTypes(Sport.FIGURE_SKATING))
                .isEqualTo(SportEventCatalog.allowedEventTypes(Sport.GYMNASTICS))
                .isEqualTo(ScoredCatalog.EVENT_TYPES);
    }

    @Test
    @DisplayName("EVENT_TYPES は不変集合")
    void 不変集合である() {
        assertThat(ScoredCatalog.EVENT_TYPES).contains(MatchEventType.SCORE_SUBMITTED);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> ScoredCatalog.EVENT_TYPES.add(MatchEventType.GOAL));
    }
}
