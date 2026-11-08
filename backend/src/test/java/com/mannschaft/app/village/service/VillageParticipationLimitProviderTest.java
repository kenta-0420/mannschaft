package com.mannschaft.app.village.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CMP-260827-1808: 参加上限の現在値と、起動時の範囲検査。 */
@DisplayName("村の参加上限 Provider")
class VillageParticipationLimitProviderTest {

    @Test
    @DisplayName("未設定なら定数の既定値")
    void defaultsToConstant() {
        assertThat(new VillageParticipationLimitProvider(null).currentHardLimit())
                .isEqualTo(VillageParticipationLimits.DEFAULT_HARD_LIMIT);
    }

    @ParameterizedTest
    @ValueSource(ints = {VillageParticipationLimits.MIN_HARD_LIMIT, VillageParticipationLimits.DB_SLOT_CEILING})
    @DisplayName("最小値と DB 天井ちょうどは受け入れる")
    void acceptsBoundaries(int value) {
        assertThat(new VillageParticipationLimitProvider(value).currentHardLimit()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(ints = {VillageParticipationLimits.MIN_HARD_LIMIT - 1, VillageParticipationLimits.DB_SLOT_CEILING + 1})
    @DisplayName("範囲外は構築時に失敗し、起動を止める")
    void rejectsOutOfRange(int value) {
        assertThatThrownBy(() -> new VillageParticipationLimitProvider(value))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mannschaft.village.participation-hard-limit");
    }
}
