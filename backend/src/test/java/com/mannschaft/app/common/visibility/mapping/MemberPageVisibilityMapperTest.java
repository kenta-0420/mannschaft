package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.member.PageVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link MemberPageVisibilityMapper} の単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表。
 */
@DisplayName("MemberPageVisibilityMapper")
class MemberPageVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(PageVisibility.class)
    @DisplayName("全 enum 値が non-null な StandardVisibility にマップされる")
    void every_value_maps_to_non_null(PageVisibility v) {
        assertThat(MemberPageVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("PUBLIC → StandardVisibility.PUBLIC")
    void public_maps_to_PUBLIC() {
        assertThat(MemberPageVisibilityMapper.toStandard(PageVisibility.PUBLIC))
            .isEqualTo(StandardVisibility.PUBLIC);
    }

    @Test
    @DisplayName("MEMBERS_ONLY → StandardVisibility.MEMBERS_AND_ABOVE（W5 内輪・応援者除外）")
    void members_only_maps_to_MEMBERS_AND_ABOVE() {
        // 判定根拠: 設計書 F06.2 §権限と役割で SUPPORTER は「公開されたメンバー紹介ページの閲覧」のみ。
        // MEMBERS_ONLY ページは応援者に見せない内輪 → 応援者除外の MEMBERS_AND_ABOVE。
        // 挙動変更: SUPPORTER は MEMBERS_ONLY のメンバー紹介ページを閲覧できなくなる。
        assertThat(MemberPageVisibilityMapper.toStandard(PageVisibility.MEMBERS_ONLY))
            .isEqualTo(StandardVisibility.MEMBERS_AND_ABOVE);
    }
}
