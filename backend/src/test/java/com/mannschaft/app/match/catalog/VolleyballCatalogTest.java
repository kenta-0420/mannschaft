package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.Sport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link VolleyballCatalog}・{@link SportEventCatalog} への VOLLEYBALL 登録の整合テスト。
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/04_volleyball.md §2 / §5 / §7</p>
 */
@DisplayName("VolleyballCatalog 整合テスト")
class VolleyballCatalogTest {

    /**
     * sports/04_volleyball.md §2 が正準とする VOLLEYBALL の event_type 集合。
     */
    private static final Set<MatchEventType> EXPECTED_VOLLEYBALL = EnumSet.of(
            MatchEventType.STARTER,
            MatchEventType.SUB_IN,
            MatchEventType.SUB_OUT,
            MatchEventType.SET_START,
            MatchEventType.SET_END,
            MatchEventType.POINT,
            MatchEventType.SERVE_ACE,
            MatchEventType.BLOCK_POINT,
            MatchEventType.ATTACK_POINT,
            MatchEventType.SERVE_ERROR,
            MatchEventType.INJURY,
            MatchEventType.OTHER);

    @Nested
    @DisplayName("event_type カタログ（§2）")
    class EventTypeCatalog {

        @Test
        @DisplayName("VOLLEYBALL の event_type 集合が sports/04 §2 の正準と一致する")
        void volleyballEventTypesMatchCanonical() {
            assertThat(VolleyballCatalog.EVENT_TYPES).isEqualTo(EXPECTED_VOLLEYBALL);
        }

        @Test
        @DisplayName("SportEventCatalog は VOLLEYBALL を登録している")
        void catalogContainsVolleyball() {
            assertThat(SportEventCatalog.CATALOG).containsKey(Sport.VOLLEYBALL);
        }

        @Test
        @DisplayName("SportEventCatalog.allowedEventTypes(VOLLEYBALL) は VolleyballCatalog.EVENT_TYPES を返す")
        void catalogReferencesVolleyballCatalog() {
            assertThat(SportEventCatalog.allowedEventTypes(Sport.VOLLEYBALL))
                    .isEqualTo(VolleyballCatalog.EVENT_TYPES);
        }

        @Test
        @DisplayName("isAllowed(VOLLEYBALL, *): 集合内イベントはすべて true")
        void isAllowedTrueForAllVolleyballTypes() {
            for (MatchEventType type : EXPECTED_VOLLEYBALL) {
                assertThat(SportEventCatalog.isAllowed(Sport.VOLLEYBALL, type))
                        .as("VOLLEYBALL は %s を許容する", type).isTrue();
            }
        }

        @Test
        @DisplayName("isAllowed(VOLLEYBALL, SERVE_ACE/BLOCK_POINT/ATTACK_POINT): バレー固有得点は true")
        void isAllowedTrueForVolleyballScoringTypes() {
            assertThat(SportEventCatalog.isAllowed(Sport.VOLLEYBALL, MatchEventType.SERVE_ACE)).isTrue();
            assertThat(SportEventCatalog.isAllowed(Sport.VOLLEYBALL, MatchEventType.BLOCK_POINT)).isTrue();
            assertThat(SportEventCatalog.isAllowed(Sport.VOLLEYBALL, MatchEventType.ATTACK_POINT)).isTrue();
            assertThat(SportEventCatalog.isAllowed(Sport.VOLLEYBALL, MatchEventType.SET_START)).isTrue();
            assertThat(SportEventCatalog.isAllowed(Sport.VOLLEYBALL, MatchEventType.SET_END)).isTrue();
        }

        @Test
        @DisplayName("isAllowed(VOLLEYBALL, GOAL): サッカー固有 GOAL はバレーでは false")
        void isAllowedFalseForSoccerGoal() {
            assertThat(SportEventCatalog.isAllowed(Sport.VOLLEYBALL, MatchEventType.GOAL))
                    .as("GOAL（サッカー専用）はバレーに非許容").isFalse();
        }

        @Test
        @DisplayName("isAllowed(VOLLEYBALL, FIELD_GOAL_2/REBOUND): バスケ固有値はバレーでは false")
        void isAllowedFalseForBasketballTypes() {
            assertThat(SportEventCatalog.isAllowed(Sport.VOLLEYBALL, MatchEventType.FIELD_GOAL_2)).isFalse();
            assertThat(SportEventCatalog.isAllowed(Sport.VOLLEYBALL, MatchEventType.REBOUND)).isFalse();
            assertThat(SportEventCatalog.isAllowed(Sport.VOLLEYBALL, MatchEventType.PERSONAL_FOUL)).isFalse();
        }

        @Test
        @DisplayName("isAllowed(VOLLEYBALL, YELLOW_CARD): バレーは規律コード非対象＝カードは非許容（§5）")
        void isAllowedFalseForCards() {
            assertThat(SportEventCatalog.isAllowed(Sport.VOLLEYBALL, MatchEventType.YELLOW_CARD)).isFalse();
            assertThat(SportEventCatalog.isAllowed(Sport.VOLLEYBALL, MatchEventType.RED_CARD)).isFalse();
        }

        @Test
        @DisplayName("VolleyballCatalog.EVENT_TYPES は不変集合（変更不可）")
        void eventTypesImmutable() {
            assertThatThrownBy(() -> VolleyballCatalog.EVENT_TYPES.add(MatchEventType.GOAL))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("ポジション語彙（§7）")
    class Positions {

        @Test
        @DisplayName("VolleyballCatalog.POSITIONS: OH/OP/MB/S/L の 5 要素（sports/04 §7）")
        void volleyballPositionsContainRequiredVocabulary() {
            assertThat(VolleyballCatalog.POSITIONS)
                    .as("バレーポジション語彙（sports/04 §7）")
                    .containsExactlyInAnyOrder("OH", "OP", "MB", "S", "L");
        }
    }
}
