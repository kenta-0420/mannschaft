package com.mannschaft.app.notification.service;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.scopefolder.entity.ScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NotificationService#toNotificationScopeType(ScopeType)} の写像を固定する番人テスト。
 *
 * <p>scopefolder ドメインの {@link ScopeType} と通知ドメインの {@link NotificationScopeType} は
 * <b>別ドメインの別 enum</b> であり、値集合が一致する保証はない。片方に定数が増えたときに
 * 黙って壊れる（あるいは握りつぶされる）ことがないよう、全定数が写像可能であることを機械的に固定する。</p>
 *
 * <p>写像本体は {@code default} 句を持たない網羅 switch であるため、{@code ScopeType} に定数が
 * 増えればコンパイル時点で落ちる。本テストはそれに加えて<b>「名前が一致する定数へ写像されている」</b>
 * ことを実行時に固定し、うっかり別の定数へ繋いでしまう事故（例: ORGANIZATION → SYSTEM）を防ぐ。</p>
 *
 * <p>Docker 不要のプレーン単体テスト。実 DB での回帰は
 * {@code NotificationFolderFilterContractIT} が担保する。</p>
 */
@DisplayName("ScopeType → NotificationScopeType 写像 番人テスト")
class NotificationScopeTypeMappingTest {

    @ParameterizedTest
    @EnumSource(ScopeType.class)
    @DisplayName("ScopeType の全定数が同名の NotificationScopeType へ写像される")
    void 全定数が同名のNotificationScopeTypeへ写像される(ScopeType scopeType) {
        NotificationScopeType mapped = NotificationService.toNotificationScopeType(scopeType);

        assertThat(mapped).isNotNull();
        assertThat(mapped.name())
                .as("ScopeType.%s の写像先は同名の NotificationScopeType であるべき", scopeType.name())
                .isEqualTo(scopeType.name());
    }

    @Test
    @DisplayName("ScopeType の全定数が NotificationScopeType に存在する（値集合の包含関係を固定）")
    void ScopeTypeの全定数がNotificationScopeTypeに存在する() {
        for (ScopeType scopeType : ScopeType.values()) {
            assertThat(NotificationScopeType.values())
                    .as("ScopeType.%s に対応する NotificationScopeType 定数が無い。"
                            + "写像方針（写像先の新設 / 空ページ返却など）を明示的に決めること", scopeType.name())
                    .anyMatch(n -> n.name().equals(scopeType.name()));
        }
    }
}
