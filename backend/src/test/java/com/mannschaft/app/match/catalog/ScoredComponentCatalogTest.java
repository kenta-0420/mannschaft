package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.ScoredApparatus;
import com.mannschaft.app.match.domain.ScoredComponentType;
import com.mannschaft.app.match.domain.Sport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScoredComponentCatalog} の純 UT（test-first・sports/07_scored.md §4B.2 / §10）。
 *
 * <p>競技別の採点内訳カタログ（フィギュア=TES/PCS/DEDUCTION・SP/FS／体操=D_SCORE/E_SCORE・種目別）の
 * 列挙整合（競技をまたいだ流用を弾く・列挙外を弾く・apparatus は NULL 許容）を検証する。</p>
 */
@DisplayName("ScoredComponentCatalog（採点内訳の競技別カタログ）UT")
class ScoredComponentCatalogTest {

    @Test
    @DisplayName("採点競技（フィギュア/体操）は採点内訳カタログを持つ・球技は持たない")
    void identifiesScoredSports() {
        assertThat(ScoredComponentCatalog.isScoredSport(Sport.FIGURE_SKATING)).isTrue();
        assertThat(ScoredComponentCatalog.isScoredSport(Sport.GYMNASTICS)).isTrue();
        assertThat(ScoredComponentCatalog.isScoredSport(Sport.SOCCER)).isFalse();
        assertThat(ScoredComponentCatalog.isScoredSport(Sport.VOLLEYBALL)).isFalse();
        assertThat(ScoredComponentCatalog.isScoredSport(null)).isFalse();
    }

    @Test
    @DisplayName("フィギュアの項目は TES/PCS/DEDUCTION のみ許容（体操の D_SCORE は弾く）")
    void figureComponentTypes() {
        assertThat(ScoredComponentCatalog.isComponentTypeAllowed(Sport.FIGURE_SKATING, ScoredComponentType.TES)).isTrue();
        assertThat(ScoredComponentCatalog.isComponentTypeAllowed(Sport.FIGURE_SKATING, ScoredComponentType.PCS)).isTrue();
        assertThat(ScoredComponentCatalog.isComponentTypeAllowed(Sport.FIGURE_SKATING, ScoredComponentType.DEDUCTION)).isTrue();
        // 体操の項目をフィギュアに入れるのは不許容（競技間流用を弾く・§4B.2）
        assertThat(ScoredComponentCatalog.isComponentTypeAllowed(Sport.FIGURE_SKATING, ScoredComponentType.D_SCORE)).isFalse();
        assertThat(ScoredComponentCatalog.isComponentTypeAllowed(Sport.FIGURE_SKATING, ScoredComponentType.E_SCORE)).isFalse();
    }

    @Test
    @DisplayName("体操の項目は D_SCORE/E_SCORE のみ許容（フィギュアの TES は弾く）")
    void gymnasticsComponentTypes() {
        assertThat(ScoredComponentCatalog.isComponentTypeAllowed(Sport.GYMNASTICS, ScoredComponentType.D_SCORE)).isTrue();
        assertThat(ScoredComponentCatalog.isComponentTypeAllowed(Sport.GYMNASTICS, ScoredComponentType.E_SCORE)).isTrue();
        assertThat(ScoredComponentCatalog.isComponentTypeAllowed(Sport.GYMNASTICS, ScoredComponentType.TES)).isFalse();
        assertThat(ScoredComponentCatalog.isComponentTypeAllowed(Sport.GYMNASTICS, ScoredComponentType.PCS)).isFalse();
    }

    @Test
    @DisplayName("component_type が NULL は不許容（必須）・非採点競技も不許容")
    void componentTypeNullAndNonScoredRejected() {
        assertThat(ScoredComponentCatalog.isComponentTypeAllowed(Sport.FIGURE_SKATING, null)).isFalse();
        assertThat(ScoredComponentCatalog.isComponentTypeAllowed(Sport.SOCCER, ScoredComponentType.TES)).isFalse();
    }

    @Test
    @DisplayName("フィギュアのセグメントは SP/FS のみ許容（体操の FLOOR は弾く）")
    void figureApparatuses() {
        assertThat(ScoredComponentCatalog.isApparatusAllowed(Sport.FIGURE_SKATING, ScoredApparatus.SP)).isTrue();
        assertThat(ScoredComponentCatalog.isApparatusAllowed(Sport.FIGURE_SKATING, ScoredApparatus.FS)).isTrue();
        assertThat(ScoredComponentCatalog.isApparatusAllowed(Sport.FIGURE_SKATING, ScoredApparatus.FLOOR)).isFalse();
    }

    @Test
    @DisplayName("体操の種目は床/あん馬…のみ許容（フィギュアの SP は弾く）")
    void gymnasticsApparatuses() {
        assertThat(ScoredComponentCatalog.isApparatusAllowed(Sport.GYMNASTICS, ScoredApparatus.FLOOR)).isTrue();
        assertThat(ScoredComponentCatalog.isApparatusAllowed(Sport.GYMNASTICS, ScoredApparatus.POMMEL_HORSE)).isTrue();
        assertThat(ScoredComponentCatalog.isApparatusAllowed(Sport.GYMNASTICS, ScoredApparatus.UNEVEN_BARS)).isTrue();
        assertThat(ScoredComponentCatalog.isApparatusAllowed(Sport.GYMNASTICS, ScoredApparatus.SP)).isFalse();
    }

    @Test
    @DisplayName("apparatus は NULL 許容（種目を区別しない内訳・常に true）")
    void apparatusNullAllowed() {
        assertThat(ScoredComponentCatalog.isApparatusAllowed(Sport.FIGURE_SKATING, null)).isTrue();
        assertThat(ScoredComponentCatalog.isApparatusAllowed(Sport.GYMNASTICS, null)).isTrue();
    }
}
