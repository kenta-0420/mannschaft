package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ShiftBudgetFeatureService} の単体テスト。
 *
 * <p>Phase 9-α ではグローバルフラグ単独判定のみをテストする。
 * 三値論理（組織別オプトイン/オプトアウト）は Phase 9-δ で追加実装される。</p>
 */
@DisplayName("ShiftBudgetFeatureService 単体テスト (Phase 9-α)")
class ShiftBudgetFeatureServiceTest {

    @Test
    @DisplayName("isEnabled_グローバルtrue_組織IDに関係なくtrueを返す")
    void isEnabled_グローバルtrue_組織IDに関係なくtrueを返す() {
        ShiftBudgetProperties props = new ShiftBudgetProperties();
        props.setEnabled(true);
        ShiftBudgetFeatureService service = new ShiftBudgetFeatureService(props);

        assertThat(service.isEnabled(null)).isTrue();
        assertThat(service.isEnabled(1L)).isTrue();
        assertThat(service.isEnabled(999L)).isTrue();
    }

    @Test
    @DisplayName("isEnabled_グローバルfalse_組織IDに関係なくfalseを返す")
    void isEnabled_グローバルfalse_組織IDに関係なくfalseを返す() {
        ShiftBudgetProperties props = new ShiftBudgetProperties();
        props.setEnabled(false);
        ShiftBudgetFeatureService service = new ShiftBudgetFeatureService(props);

        assertThat(service.isEnabled(null)).isFalse();
        assertThat(service.isEnabled(1L)).isFalse();
    }

    @Test
    @DisplayName("requireEnabled_有効_例外を投げない")
    void requireEnabled_有効_例外を投げない() {
        ShiftBudgetProperties props = new ShiftBudgetProperties();
        props.setEnabled(true);
        ShiftBudgetFeatureService service = new ShiftBudgetFeatureService(props);

        // 例外を投げないこと
        service.requireEnabled(1L);
    }

    @Test
    @DisplayName("requireEnabled_無効_FEATURE_DISABLEDのBusinessExceptionを投げる")
    void requireEnabled_無効_FEATURE_DISABLEDのBusinessExceptionを投げる() {
        ShiftBudgetProperties props = new ShiftBudgetProperties();
        props.setEnabled(false);
        ShiftBudgetFeatureService service = new ShiftBudgetFeatureService(props);

        assertThatThrownBy(() -> service.requireEnabled(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ShiftBudgetErrorCode.FEATURE_DISABLED);
    }
}
