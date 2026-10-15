package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.committee.entity.CommitteeVisibility;
import com.mannschaft.app.common.visibility.StandardVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link CommitteeVisibilityMapper} の exhaustive 単体テスト。
 *
 * <p>設計書 §13.3 — Mapper 網羅性を CI で保証する。
 */
@DisplayName("CommitteeVisibilityMapper")
class CommitteeVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(CommitteeVisibility.class)
    @DisplayName("全ての値が non-null な StandardVisibility に対応する")
    void every_value_maps_to_some_standard(CommitteeVisibility v) {
        assertThat(CommitteeVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("HIDDEN -> PRIVATE")
    void mapsHidden() {
        assertThat(CommitteeVisibilityMapper.toStandard(CommitteeVisibility.HIDDEN))
            .isEqualTo(StandardVisibility.PRIVATE);
    }

    @Test
    @DisplayName("NAME_ONLY -> CUSTOM (部分公開、Resolver 個別実装)")
    void mapsNameOnly() {
        assertThat(CommitteeVisibilityMapper.toStandard(CommitteeVisibility.NAME_ONLY))
            .isEqualTo(StandardVisibility.CUSTOM);
    }

    @Test
    @DisplayName("NAME_AND_PURPOSE -> CUSTOM (部分公開、Resolver 個別実装)")
    void mapsNameAndPurpose() {
        assertThat(CommitteeVisibilityMapper.toStandard(CommitteeVisibility.NAME_AND_PURPOSE))
            .isEqualTo(StandardVisibility.CUSTOM);
    }
}
