package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.budget.entity.BudgetConfigEntity;
import com.mannschaft.app.budget.repository.BudgetConfigRepository;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ShiftBudgetFeatureService} の単体テスト。
 *
 * <p>Phase 9-δ で三値論理（グローバル × 組織別 NULL/TRUE/FALSE）に拡張。</p>
 */
@DisplayName("ShiftBudgetFeatureService 単体テスト (Phase 9-δ 三値論理)")
class ShiftBudgetFeatureServiceTest {

    private ShiftBudgetFeatureService newService(boolean globalEnabled, Boolean orgFlag) {
        ShiftBudgetProperties props = new ShiftBudgetProperties();
        props.setEnabled(globalEnabled);
        BudgetConfigRepository repo = mock(BudgetConfigRepository.class);
        if (orgFlag == null) {
            when(repo.findByScopeTypeAndScopeId(any(), any())).thenReturn(Optional.empty());
        } else {
            BudgetConfigEntity entity = mock(BudgetConfigEntity.class);
            when(entity.getShiftBudgetEnabled()).thenReturn(orgFlag);
            when(repo.findByScopeTypeAndScopeId(eq("ORGANIZATION"), any())).thenReturn(Optional.of(entity));
        }
        return new ShiftBudgetFeatureService(props, repo);
    }

    @Test
    @DisplayName("グローバルOFF×組織NULL_OFF（強制無効）")
    void グローバルOFF_組織NULL_OFF() {
        ShiftBudgetFeatureService service = newService(false, null);
        assertThat(service.isEnabled(1L)).isFalse();
    }

    @Test
    @DisplayName("グローバルOFF×組織TRUE_OFF（強制無効、組織設定無視）")
    void グローバルOFF_組織TRUE_OFF() {
        ShiftBudgetFeatureService service = newService(false, true);
        assertThat(service.isEnabled(1L)).isFalse();
    }

    @Test
    @DisplayName("グローバルON×組織NULL_ON（既定値継承）")
    void グローバルON_組織NULL_ON() {
        ShiftBudgetFeatureService service = newService(true, null);
        assertThat(service.isEnabled(1L)).isTrue();
    }

    @Test
    @DisplayName("グローバルON×組織TRUE_ON（明示有効）")
    void グローバルON_組織TRUE_ON() {
        ShiftBudgetFeatureService service = newService(true, true);
        assertThat(service.isEnabled(1L)).isTrue();
    }

    @Test
    @DisplayName("グローバルON×組織FALSE_OFF（オプトアウト）")
    void グローバルON_組織FALSE_OFF() {
        ShiftBudgetFeatureService service = newService(true, false);
        assertThat(service.isEnabled(1L)).isFalse();
    }

    @Test
    @DisplayName("requireEnabled_有効_例外を投げない")
    void requireEnabled_有効_例外を投げない() {
        ShiftBudgetFeatureService service = newService(true, null);
        service.requireEnabled(1L);
    }

    @Test
    @DisplayName("requireEnabled_無効_FEATURE_DISABLEDのBusinessExceptionを投げる")
    void requireEnabled_無効_FEATURE_DISABLEDのBusinessExceptionを投げる() {
        ShiftBudgetFeatureService service = newService(false, null);
        assertThatThrownBy(() -> service.requireEnabled(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ShiftBudgetErrorCode.FEATURE_DISABLED);
    }
}
