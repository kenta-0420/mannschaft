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
 * {@link BasketballCatalog}・{@link BasketballFoulCode}・
 * {@link SportEventCatalog} への BASKETBALL 登録の整合テスト。
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/03_basketball.md §2 / §5</p>
 */
@DisplayName("BasketballCatalog / BasketballFoulCode 整合テスト")
class BasketballCatalogTest {

    /**
     * sports/03_basketball.md §2 が正準とする BASKETBALL の event_type 集合。
     */
    private static final Set<MatchEventType> EXPECTED_BASKETBALL = EnumSet.of(
            MatchEventType.STARTER,
            MatchEventType.SUB_IN,
            MatchEventType.SUB_OUT,
            MatchEventType.FIELD_GOAL_2,
            MatchEventType.FIELD_GOAL_3,
            MatchEventType.FREE_THROW,
            MatchEventType.SHOT_MISS,
            MatchEventType.REBOUND,
            MatchEventType.STEAL,
            MatchEventType.BLOCK,
            MatchEventType.TURNOVER,
            MatchEventType.ASSIST,
            MatchEventType.PERSONAL_FOUL,
            MatchEventType.TECHNICAL_FOUL,
            MatchEventType.FOUL_OUT,
            MatchEventType.INJURY,
            MatchEventType.PERIOD_START,
            MatchEventType.PERIOD_END,
            MatchEventType.OTHER);

    @Nested
    @DisplayName("event_type カタログ（§2）")
    class EventTypeCatalog {

        @Test
        @DisplayName("BASKETBALL の event_type 集合が sports/03 §2 の正準と一致する")
        void basketballEventTypesMatchCanonical() {
            assertThat(BasketballCatalog.EVENT_TYPES).isEqualTo(EXPECTED_BASKETBALL);
        }

        @Test
        @DisplayName("SportEventCatalog は BASKETBALL を登録している")
        void catalogContainsBasketball() {
            assertThat(SportEventCatalog.CATALOG).containsKey(Sport.BASKETBALL);
        }

        @Test
        @DisplayName("SportEventCatalog.allowedEventTypes(BASKETBALL) は BasketballCatalog.EVENT_TYPES を返す")
        void catalogReferencesBasketballCatalog() {
            assertThat(SportEventCatalog.allowedEventTypes(Sport.BASKETBALL))
                    .isEqualTo(BasketballCatalog.EVENT_TYPES);
        }

        @Test
        @DisplayName("isAllowed(BASKETBALL, *): 集合内イベントはすべて true")
        void isAllowedTrueForAllBasketballTypes() {
            for (MatchEventType type : EXPECTED_BASKETBALL) {
                assertThat(SportEventCatalog.isAllowed(Sport.BASKETBALL, type))
                        .as("BASKETBALL は %s を許容する", type).isTrue();
            }
        }

        @Test
        @DisplayName("isAllowed(BASKETBALL, GOAL): サッカー固有 GOAL は BASKETBALL では false（§2 注）")
        void isAllowedFalseForSoccerGoalInBasketball() {
            assertThat(SportEventCatalog.isAllowed(Sport.BASKETBALL, MatchEventType.GOAL))
                    .as("GOAL（サッカー専用）はバスケに非許容（sports/03 §2 注）").isFalse();
        }

        @Test
        @DisplayName("isAllowed(BASKETBALL, YELLOW_CARD): サッカー専用カードは BASKETBALL では false")
        void isAllowedFalseForSoccerCardInBasketball() {
            assertThat(SportEventCatalog.isAllowed(Sport.BASKETBALL, MatchEventType.YELLOW_CARD))
                    .as("YELLOW_CARD はバスケに非許容").isFalse();
            assertThat(SportEventCatalog.isAllowed(Sport.BASKETBALL, MatchEventType.RED_CARD))
                    .as("RED_CARD はバスケに非許容").isFalse();
            assertThat(SportEventCatalog.isAllowed(Sport.BASKETBALL, MatchEventType.SECOND_YELLOW))
                    .as("SECOND_YELLOW はバスケに非許容").isFalse();
        }

        @Test
        @DisplayName("isAllowed(BASKETBALL, PENALTY_SHOOTOUT): PK戦はバスケに非許容（§4.1 注）")
        void isAllowedFalseForPenaltyShootoutInBasketball() {
            assertThat(SportEventCatalog.isAllowed(Sport.BASKETBALL, MatchEventType.PENALTY_SHOOTOUT))
                    .as("PENALTY_SHOOTOUT はバスケに非許容（sports/03 §4.1）").isFalse();
        }

        @Test
        @DisplayName("isAllowed(BASKETBALL, OWN_GOAL): オウンゴールはバスケに非許容（§4.2 注）")
        void isAllowedFalseForOwnGoalInBasketball() {
            assertThat(SportEventCatalog.isAllowed(Sport.BASKETBALL, MatchEventType.OWN_GOAL))
                    .as("OWN_GOAL はバスケに非許容（sports/03 §4.2）").isFalse();
        }

        @Test
        @DisplayName("BasketballCatalog.EVENT_TYPES は不変集合（変更不可）")
        void eventTypesImmutable() {
            assertThatThrownBy(() -> BasketballCatalog.EVENT_TYPES.add(MatchEventType.GOAL))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("BasketballFoulCode 定義（§5）")
    class FoulCodeDefinition {

        @Test
        @DisplayName("BasketballFoulCode には PF/SF/OF/TF/UF/DF の 6 コードが存在する（sports/03 §5）")
        void foulCodeCountAndValues() {
            BasketballFoulCode[] codes = BasketballFoulCode.values();
            assertThat(codes).hasSize(6);
            assertThat(codes).containsExactlyInAnyOrder(
                    BasketballFoulCode.PF,
                    BasketballFoulCode.SF,
                    BasketballFoulCode.OF,
                    BasketballFoulCode.TF,
                    BasketballFoulCode.UF,
                    BasketballFoulCode.DF);
        }

        @Test
        @DisplayName("PERSONAL_FOUL の許容コードは PF/SF/OF/UF（sports/03 §5 表）")
        void personalFoulAllowedCodes() {
            Set<String> allowed = BasketballFoulReasonCatalog.allowedCodes(MatchEventType.PERSONAL_FOUL);
            assertThat(allowed)
                    .as("PERSONAL_FOUL の許容コード（sports/03 §5）")
                    .containsExactlyInAnyOrder(
                            BasketballFoulCode.PF.name(),
                            BasketballFoulCode.SF.name(),
                            BasketballFoulCode.OF.name(),
                            BasketballFoulCode.UF.name());
        }

        @Test
        @DisplayName("TECHNICAL_FOUL の許容コードは TF のみ（sports/03 §5 表）")
        void technicalFoulAllowedCodes() {
            Set<String> allowed = BasketballFoulReasonCatalog.allowedCodes(MatchEventType.TECHNICAL_FOUL);
            assertThat(allowed)
                    .as("TECHNICAL_FOUL の許容コードは TF のみ（sports/03 §5）")
                    .containsExactlyInAnyOrder(BasketballFoulCode.TF.name());
        }

        @Test
        @DisplayName("FOUL_OUT の許容コードは DF のみ（ディスクォリファイ時。5ファウル退場は NULL）")
        void foulOutAllowedCodes() {
            Set<String> allowed = BasketballFoulReasonCatalog.allowedCodes(MatchEventType.FOUL_OUT);
            assertThat(allowed)
                    .as("FOUL_OUT の許容コードは DF のみ（sports/03 §5 表）")
                    .containsExactlyInAnyOrder(BasketballFoulCode.DF.name());
        }

        @Test
        @DisplayName("得点イベント（FIELD_GOAL_2 等）は理由コード非対象（空集合）")
        void nonFoulEventHasNoAllowedCodes() {
            assertThat(BasketballFoulReasonCatalog.allowedCodes(MatchEventType.FIELD_GOAL_2)).isEmpty();
            assertThat(BasketballFoulReasonCatalog.allowedCodes(MatchEventType.FIELD_GOAL_3)).isEmpty();
            assertThat(BasketballFoulReasonCatalog.allowedCodes(MatchEventType.FREE_THROW)).isEmpty();
            assertThat(BasketballFoulReasonCatalog.allowedCodes(MatchEventType.REBOUND)).isEmpty();
            assertThat(BasketballFoulReasonCatalog.allowedCodes(MatchEventType.OTHER)).isEmpty();
        }

        @Test
        @DisplayName("isValid: NULL コードは常に true（理由コードは任意）")
        void isValidNullCodeAlwaysTrue() {
            assertThat(BasketballFoulReasonCatalog.isValid(MatchEventType.PERSONAL_FOUL, null)).isTrue();
            assertThat(BasketballFoulReasonCatalog.isValid(MatchEventType.TECHNICAL_FOUL, null)).isTrue();
            assertThat(BasketballFoulReasonCatalog.isValid(MatchEventType.FOUL_OUT, null)).isTrue();
            assertThat(BasketballFoulReasonCatalog.isValid(MatchEventType.FIELD_GOAL_2, null)).isTrue();
        }

        @Test
        @DisplayName("isValid: 整合コードは true・不整合コードは false")
        void isValidCorrectVsIncorrect() {
            // PERSONAL_FOUL に PF は合法
            assertThat(BasketballFoulReasonCatalog.isValid(MatchEventType.PERSONAL_FOUL,
                    BasketballFoulCode.PF.name())).isTrue();
            // PERSONAL_FOUL に TF（テクニカル）は不正
            assertThat(BasketballFoulReasonCatalog.isValid(MatchEventType.PERSONAL_FOUL,
                    BasketballFoulCode.TF.name())).isFalse();
            // TECHNICAL_FOUL に TF は合法
            assertThat(BasketballFoulReasonCatalog.isValid(MatchEventType.TECHNICAL_FOUL,
                    BasketballFoulCode.TF.name())).isTrue();
            // TECHNICAL_FOUL に PF は不正
            assertThat(BasketballFoulReasonCatalog.isValid(MatchEventType.TECHNICAL_FOUL,
                    BasketballFoulCode.PF.name())).isFalse();
            // FIELD_GOAL_2 に任意コードを与えると不正（理由コード非対象）
            assertThat(BasketballFoulReasonCatalog.isValid(MatchEventType.FIELD_GOAL_2,
                    BasketballFoulCode.PF.name())).isFalse();
        }

        @Test
        @DisplayName("BasketballFoulCode.name() はそのまま理由コード記号として使用できる")
        void foulCodeNameIsSymbol() {
            assertThat(BasketballFoulCode.PF.name()).isEqualTo("PF");
            assertThat(BasketballFoulCode.SF.name()).isEqualTo("SF");
            assertThat(BasketballFoulCode.OF.name()).isEqualTo("OF");
            assertThat(BasketballFoulCode.TF.name()).isEqualTo("TF");
            assertThat(BasketballFoulCode.UF.name()).isEqualTo("UF");
            assertThat(BasketballFoulCode.DF.name()).isEqualTo("DF");
        }
    }

    @Nested
    @DisplayName("ポジション語彙（§7）")
    class Positions {

        @Test
        @DisplayName("BasketballCatalog.POSITIONS: PG/SG/SF/PF/C の 5 要素（sports/03 §7）")
        void basketballPositionsContainRequiredVocabulary() {
            assertThat(BasketballCatalog.POSITIONS)
                    .as("バスケポジション語彙（sports/03 §7）")
                    .containsExactlyInAnyOrder("PG", "SG", "SF", "PF", "C");
        }
    }
}
