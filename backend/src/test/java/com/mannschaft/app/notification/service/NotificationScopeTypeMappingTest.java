package com.mannschaft.app.notification.service;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link NotificationService#toNotificationScopeType(ScopeType)} の写像を固定する番人テスト。
 *
 * <p>scopefolder ドメインの {@link ScopeType} と通知ドメインの {@link NotificationScopeType} は
 * <b>別ドメインの別 enum</b> であり、値集合が一致する保証はない。片方に定数が増えたときに
 * 黙って壊れる（あるいは握りつぶされる）ことがないよう、全定数が写像可能であることを機械的に固定する。</p>
 *
 * <p><b>経緯</b>: 写像本体はかつて javac が enum switch のために生成する合成クラス
 * {@code NotificationService$1} がクロスドメイン Entity 参照の番人
 * （D-1 / {@code CrossDomainEntityImportArchTest}）に新規違反として誤検出され CI が落ちたため、
 * 一時的に {@code ==} による参照比較へ書き換えていた。D-1 番人に合成クラス除外
 * （{@code SyntheticClasses#isSynthetic}）を実装して根治したため、写像本体は
 * {@code default} 句付きの {@code switch} 式へ差し戻し済みである。</p>
 *
 * <p>{@code switch} 式には既知の定数のみを列挙し {@code default} で未知値を検出する形に
 * しているため、コンパイル時の網羅性チェックは働かない。本テストは
 * <b>{@code ScopeType.values()} を全件ループ</b>して、その実行時の網羅性保証を肩代わりする。
 * TEAM / ORGANIZATION を個別に書き並べる形にすると、{@code ScopeType} に定数が増えても
 * 何も落ちず、写像漏れが実行時 {@link IllegalStateException} として本番で初めて表面化してしまう。</p>
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
    @DisplayName("ScopeType.values() 全件で写像が成功する（新定数の追加をここで検出する）")
    void 全定数の写像が例外を投げない() {
        for (ScopeType scopeType : ScopeType.values()) {
            assertThatCode(() -> NotificationService.toNotificationScopeType(scopeType))
                    .as("ScopeType.%s の写像先が NotificationService#toNotificationScopeType に"
                            + "定義されていない。定数を追加したなら写像先も明示的に決めること"
                            + "（黙って null / 空ページを返す実装にはしないこと）", scopeType.name())
                    .doesNotThrowAnyException();
        }
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
