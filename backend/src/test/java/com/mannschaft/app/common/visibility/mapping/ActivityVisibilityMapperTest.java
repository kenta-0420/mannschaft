package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.common.visibility.StandardVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

/**
 * {@link ActivityVisibilityMapper} の単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表。
 */
@DisplayName("ActivityVisibilityMapper")
class ActivityVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(ActivityVisibility.class)
    @DisplayName("全 enum 値が non-null な StandardVisibility にマップされる")
    void every_value_maps_to_non_null(ActivityVisibility v) {
        assertThat(ActivityVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("PUBLIC → StandardVisibility.PUBLIC")
    void public_maps_to_PUBLIC() {
        assertThat(ActivityVisibilityMapper.toStandard(ActivityVisibility.PUBLIC))
            .isEqualTo(StandardVisibility.PUBLIC);
    }

    @Test
    @DisplayName("MEMBERS_ONLY → StandardVisibility.MEMBERS_AND_ABOVE（W5 内輪・応援者除外）")
    void members_only_maps_to_MEMBERS_AND_ABOVE() {
        // 判定根拠: 設計書 F06.4 §権限と役割で SUPPORTER は「公開記録の閲覧のみ」。
        // MEMBERS_ONLY 記録は応援者に見せない内輪 → 応援者除外の MEMBERS_AND_ABOVE。
        // 挙動変更: SUPPORTER は MEMBERS_ONLY の活動記録を閲覧できなくなる。
        assertThat(ActivityVisibilityMapper.toStandard(ActivityVisibility.MEMBERS_ONLY))
            .isEqualTo(StandardVisibility.MEMBERS_AND_ABOVE);
    }

    /**
     * CMP-028 Phase B: 逆写像 {@code toFunctional} が forward mapping の単射性に基づいて
     * 正しく機能側 enum を復元することを検証する。実装ミスで PUBLIC/MEMBERS_ONLY の
     * 対応が入れ替わったり、片方が漏れたりすると red になる。
     */
    @Test
    @DisplayName("toFunctional: PUBLIC のみ → ActivityVisibility.PUBLIC のみ")
    void toFunctional_PUBLICのみ() {
        assertThat(ActivityVisibilityMapper.toFunctional(Set.of(StandardVisibility.PUBLIC)))
                .containsExactly(ActivityVisibility.PUBLIC);
    }

    @Test
    @DisplayName("toFunctional: PUBLIC + MEMBERS_AND_ABOVE → 両方")
    void toFunctional_両方() {
        assertThat(ActivityVisibilityMapper.toFunctional(
                Set.of(StandardVisibility.PUBLIC, StandardVisibility.MEMBERS_AND_ABOVE)))
                .containsExactlyInAnyOrder(ActivityVisibility.PUBLIC, ActivityVisibility.MEMBERS_ONLY);
    }

    @Test
    @DisplayName("toFunctional: 行依存値（CUSTOM 等）は無視される（対応する ActivityVisibility が無いため）")
    void toFunctional_行依存値は無視() {
        assertThat(ActivityVisibilityMapper.toFunctional(
                Set.of(StandardVisibility.PUBLIC, StandardVisibility.CUSTOM)))
                .containsExactly(ActivityVisibility.PUBLIC);
    }

    @Test
    @DisplayName("toFunctional: 空集合 → 空集合")
    void toFunctional_空集合() {
        assertThat(ActivityVisibilityMapper.toFunctional(Set.of())).isEmpty();
        assertThat(ActivityVisibilityMapper.toFunctional(null)).isEmpty();
    }
}
