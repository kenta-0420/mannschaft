package com.mannschaft.app.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261105-1507 A6: チームの時間割にも表示方針の機能キーがあること。
 * チームの時間割画面は存在するのに TEAM の一覧に timetable が無く、導線を結線できなかった。
 */
@DisplayName("FeatureDisplayCatalog 単体テスト")
class FeatureDisplayCatalogTest {

    @Test
    @DisplayName("A6: TEAM と ORGANIZATION の両方に timetable がある")
    void timetableはチームと組織の両方にある() {
        assertThat(FeatureDisplayCatalog.keys(FeatureDisplayScope.TEAM)).contains("timetable");
        assertThat(FeatureDisplayCatalog.keys(FeatureDisplayScope.ORGANIZATION)).contains("timetable");
    }

    @Test
    @DisplayName("各スコープの機能キーに重複が無い")
    void 機能キーに重複が無い() {
        for (FeatureDisplayScope scope : FeatureDisplayScope.values()) {
            assertThat(FeatureDisplayCatalog.keys(scope)).doesNotHaveDuplicates();
        }
    }
}
