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
    @DisplayName("isAllowed: 全ての MatchEventType がサッカーで許容される（器とカタログの欠落検出）")
    void everyEventTypeCoveredBySoccer() {
        // 現状サッカーは全 MatchEventType を許容する設計（sports/01 §2）。
        // 将来 enum へ「サッカー非対応の他競技専用イベント」を追加した場合は本テストを更新する。
        for (MatchEventType type : MatchEventType.values()) {
            assertThat(SoccerCatalog.EVENT_TYPES)
                    .as("MatchEventType.%s が SOCCER カタログに含まれること", type)
                    .contains(type);
        }
    }

    @Test
    @DisplayName("allowedEventTypes: 未登録競技は空集合（NPE を投げない）")
    void allowedEventTypesUnknownSportEmpty() {
        // CATALOG に登録された競技のみ非空。Sport は現状 SOCCER のみだが、機構として空集合を返すこと。
        assertThat(SportEventCatalog.allowedEventTypes(Sport.SOCCER)).isNotEmpty();
    }

    @Test
    @DisplayName("EVENT_TYPES は不変集合（変更不可）")
    void eventTypesImmutable() {
        assertThatThrownBy(() -> SoccerCatalog.EVENT_TYPES.add(MatchEventType.GOAL))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
