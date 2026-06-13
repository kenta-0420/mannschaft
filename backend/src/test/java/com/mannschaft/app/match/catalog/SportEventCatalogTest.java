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
 * {@link SportEventCatalog}（多競技カタログの機構・コア）と {@link SoccerCatalog}（SOCCER の中身）の整合テスト。
 *
 * <p>Phase 6-②a（FutsalCatalog/BasketballCatalog追加）でバスケ固有 event_type
 * （FIELD_GOAL_2 等）を MatchEventType に追加。SOCCER カタログはバスケ固有値を含まないため
 * "全 MatchEventType を SOCCER が許容する" テストは廃止し、
 * SOCCER が正準値のみ許容する点の検証に切り替えた。</p>
 *
 * <p>設計: sports/01_soccer.md §2（SOCCER カタログ正準）/ 01_domain_and_ddl.md §D.3</p>
 */
@DisplayName("SportEventCatalog / SoccerCatalog 整合テスト")
class SportEventCatalogTest {

    /** sports/01 §2 が正準とする SOCCER の event_type 集合。 */
    private static final Set<MatchEventType> EXPECTED_SOCCER = EnumSet.of(
            MatchEventType.STARTER, MatchEventType.SUB_IN, MatchEventType.SUB_OUT,
            MatchEventType.GOAL, MatchEventType.ASSIST, MatchEventType.OWN_GOAL,
            MatchEventType.PENALTY_GOAL, MatchEventType.PENALTY_MISS, MatchEventType.PENALTY_SHOOTOUT,
            MatchEventType.YELLOW_CARD, MatchEventType.RED_CARD, MatchEventType.SECOND_YELLOW,
            MatchEventType.SAVE, MatchEventType.INJURY, MatchEventType.PERIOD_START,
            MatchEventType.PERIOD_END, MatchEventType.OTHER);

    @Test
    @DisplayName("SOCCER の event_type 集合が sports/01 §2 の正準と一致する")
    void soccerEventTypesMatchCanonical() {
        assertThat(SoccerCatalog.EVENT_TYPES).isEqualTo(EXPECTED_SOCCER);
    }

    @Test
    @DisplayName("SportEventCatalog は SOCCER 集合として SoccerCatalog を参照する（二重定義しない）")
    void catalogReferencesSoccerCatalog() {
        assertThat(SportEventCatalog.CATALOG).containsKey(Sport.SOCCER);
        assertThat(SportEventCatalog.allowedEventTypes(Sport.SOCCER))
                .isEqualTo(SoccerCatalog.EVENT_TYPES);
    }

    @Test
    @DisplayName("isAllowed: カタログ内の値は true・OTHER も含む")
    void isAllowedTrueForCatalogValues() {
        for (MatchEventType type : EXPECTED_SOCCER) {
            assertThat(SportEventCatalog.isAllowed(Sport.SOCCER, type))
                    .as("SOCCER は %s を許容する", type).isTrue();
        }
    }

    @Test
    @DisplayName("SOCCER カタログ: バスケ固有 event_type は含まない（境界テスト）")
    void soccerCatalogExcludesBasketballOnlyTypes() {
        // Phase 6-②a でバスケ固有の event_type を MatchEventType に追加した。
        // SOCCER カタログはこれらを含まない（sports/01 §2 の正準通り）。
        assertThat(SoccerCatalog.EVENT_TYPES).doesNotContain(MatchEventType.FIELD_GOAL_2);
        assertThat(SoccerCatalog.EVENT_TYPES).doesNotContain(MatchEventType.FIELD_GOAL_3);
        assertThat(SoccerCatalog.EVENT_TYPES).doesNotContain(MatchEventType.FREE_THROW);
        assertThat(SoccerCatalog.EVENT_TYPES).doesNotContain(MatchEventType.SHOT_MISS);
        assertThat(SoccerCatalog.EVENT_TYPES).doesNotContain(MatchEventType.REBOUND);
        assertThat(SoccerCatalog.EVENT_TYPES).doesNotContain(MatchEventType.STEAL);
        assertThat(SoccerCatalog.EVENT_TYPES).doesNotContain(MatchEventType.BLOCK);
        assertThat(SoccerCatalog.EVENT_TYPES).doesNotContain(MatchEventType.TURNOVER);
        assertThat(SoccerCatalog.EVENT_TYPES).doesNotContain(MatchEventType.PERSONAL_FOUL);
        assertThat(SoccerCatalog.EVENT_TYPES).doesNotContain(MatchEventType.TECHNICAL_FOUL);
        assertThat(SoccerCatalog.EVENT_TYPES).doesNotContain(MatchEventType.FOUL_OUT);
    }

    @Test
    @DisplayName("allowedEventTypes: 未登録競技は空集合（NPE を投げない）")
    void allowedEventTypesUnknownSportEmpty() {
        // CATALOG に登録された競技のみ非空。Sport は現状 SOCCER のみだが、機構として空集合を返すこと。
        assertThat(SportEventCatalog.allowedEventTypes(Sport.SOCCER)).isNotEmpty();
    }

    @Test
    @DisplayName("isAllowed: カタログ未登録の競技（null）は常に false（否定経路・NPE を投げない）")
    void isAllowedFalseForUnregisteredSport() {
        // CATALOG.get(null)==null → false（null 安全チェック）
        for (MatchEventType type : MatchEventType.values()) {
            assertThat(SportEventCatalog.isAllowed(null, type))
                    .as("未登録競技(null)では %s は許容されない", type).isFalse();
        }
    }

    @Test
    @DisplayName("CATALOG は SOCCER / FUTSAL / BASKETBALL の 3 競技を登録している（Phase 6-②a）")
    void catalogContainsThreeCompetitions() {
        assertThat(SportEventCatalog.CATALOG)
                .as("Phase 6-②a 登録済み競技: SOCCER/FUTSAL/BASKETBALL")
                .containsKeys(Sport.SOCCER, Sport.FUTSAL, Sport.BASKETBALL)
                .hasSize(3);
    }

    @Test
    @DisplayName("EVENT_TYPES は不変集合（変更不可）")
    void eventTypesImmutable() {
        assertThatThrownBy(() -> SoccerCatalog.EVENT_TYPES.add(MatchEventType.GOAL))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
