package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.Sport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FutsalCatalog} と {@link SportEventCatalog} への FUTSAL 登録の整合テスト。
 *
 * <p>フットサルはサッカーと同一の event_type 集合を用いる（sports/02_futsal.md §2）。
 * 設計: docs/features/F08.10_match_record_analytics/sports/02_futsal.md §2</p>
 */
@DisplayName("FutsalCatalog 整合テスト")
class FutsalCatalogTest {

    /**
     * sports/02_futsal.md §2 が正準とする FUTSAL の event_type 集合（サッカーと同一）。
     */
    private static final Set<MatchEventType> EXPECTED_FUTSAL = EnumSet.of(
            MatchEventType.STARTER,
            MatchEventType.SUB_IN,
            MatchEventType.SUB_OUT,
            MatchEventType.GOAL,
            MatchEventType.ASSIST,
            MatchEventType.OWN_GOAL,
            MatchEventType.PENALTY_GOAL,
            MatchEventType.PENALTY_MISS,
            MatchEventType.PENALTY_SHOOTOUT,
            MatchEventType.YELLOW_CARD,
            MatchEventType.RED_CARD,
            MatchEventType.SECOND_YELLOW,
            MatchEventType.SAVE,
            MatchEventType.INJURY,
            MatchEventType.PERIOD_START,
            MatchEventType.PERIOD_END,
            MatchEventType.OTHER);

    @Test
    @DisplayName("FUTSAL の event_type 集合が sports/02 §2 の正準と一致する")
    void futsalEventTypesMatchCanonical() {
        assertThat(FutsalCatalog.EVENT_TYPES).isEqualTo(EXPECTED_FUTSAL);
    }

    @Test
    @DisplayName("FUTSAL の event_type 集合はサッカーと完全に同一（差分なし）")
    void futsalEventTypesEqualsSoccer() {
        assertThat(FutsalCatalog.EVENT_TYPES)
                .as("フットサルはサッカーと同一のevent_type集合（sports/02 §2）")
                .isEqualTo(SoccerCatalog.EVENT_TYPES);
    }

    @Test
    @DisplayName("SportEventCatalog は FUTSAL を登録している")
    void catalogContainsFutsal() {
        assertThat(SportEventCatalog.CATALOG).containsKey(Sport.FUTSAL);
    }

    @Test
    @DisplayName("SportEventCatalog.allowedEventTypes(FUTSAL) は FutsalCatalog.EVENT_TYPES を返す")
    void catalogReferencesFutsalCatalog() {
        assertThat(SportEventCatalog.allowedEventTypes(Sport.FUTSAL))
                .isEqualTo(FutsalCatalog.EVENT_TYPES);
    }

    @Test
    @DisplayName("isAllowed(FUTSAL, *): サッカーと同じ集合がすべて true")
    void isAllowedTrueForAllFutsalTypes() {
        for (MatchEventType type : EXPECTED_FUTSAL) {
            assertThat(SportEventCatalog.isAllowed(Sport.FUTSAL, type))
                    .as("FUTSAL は %s を許容する", type).isTrue();
        }
    }

    @Test
    @DisplayName("isAllowed(FUTSAL, *): バスケ固有イベントは false（カタログ境界検証）")
    void isAllowedFalseForBasketballOnlyTypes() {
        // バスケ固有 event_type は FUTSAL カタログに含まれない
        assertThat(SportEventCatalog.isAllowed(Sport.FUTSAL, MatchEventType.FIELD_GOAL_2))
                .as("FIELD_GOAL_2 はフットサルに非許容").isFalse();
        assertThat(SportEventCatalog.isAllowed(Sport.FUTSAL, MatchEventType.FIELD_GOAL_3))
                .as("FIELD_GOAL_3 はフットサルに非許容").isFalse();
        assertThat(SportEventCatalog.isAllowed(Sport.FUTSAL, MatchEventType.FREE_THROW))
                .as("FREE_THROW はフットサルに非許容").isFalse();
        assertThat(SportEventCatalog.isAllowed(Sport.FUTSAL, MatchEventType.REBOUND))
                .as("REBOUND はフットサルに非許容").isFalse();
        assertThat(SportEventCatalog.isAllowed(Sport.FUTSAL, MatchEventType.PERSONAL_FOUL))
                .as("PERSONAL_FOUL はフットサルに非許容").isFalse();
        assertThat(SportEventCatalog.isAllowed(Sport.FUTSAL, MatchEventType.FOUL_OUT))
                .as("FOUL_OUT はフットサルに非許容").isFalse();
    }

    @Test
    @DisplayName("FutsalCatalog.EVENT_TYPES は不変集合（変更不可）")
    void eventTypesImmutable() {
        assertThatThrownBy(() -> FutsalCatalog.EVENT_TYPES.add(MatchEventType.GOAL))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("FutsalCatalog.POSITIONS: GK/FIXO/ALA/PIVO の 4 要素（sports/02 §7）")
    void futsalPositionsContainRequiredVocabulary() {
        assertThat(FutsalCatalog.POSITIONS)
                .as("フットサルポジション語彙（sports/02 §7）")
                .containsExactlyInAnyOrder("GK", "FIXO", "ALA", "PIVO");
    }
}
