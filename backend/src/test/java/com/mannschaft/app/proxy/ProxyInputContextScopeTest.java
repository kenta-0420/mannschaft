package com.mannschaft.app.proxy;

import com.mannschaft.app.proxy.entity.ProxyInputConsentScopeEntity.FeatureScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProxyInputContext} のスコープ保持・{@code hasScope} 判定テスト（F08.9 P3b）。
 */
@DisplayName("ProxyInputContext スコープ判定テスト")
class ProxyInputContextScopeTest {

    @Test
    @DisplayName("activate でスコープ集合を渡すと hasScope が許可スコープに true を返す")
    void shouldReturnTrueForGrantedScope() {
        ProxyInputContext context = new ProxyInputContext();
        context.activate(100L, 1L, "PAPER_FORM", "金庫No.1",
                Set.of(FeatureScope.PAYMENT, FeatureScope.SURVEY));

        assertThat(context.hasScope(FeatureScope.PAYMENT)).isTrue();
        assertThat(context.hasScope(FeatureScope.SURVEY)).isTrue();
    }

    @Test
    @DisplayName("許可されていないスコープには hasScope が false")
    void shouldReturnFalseForUngrantedScope() {
        ProxyInputContext context = new ProxyInputContext();
        context.activate(100L, 1L, "PAPER_FORM", "金庫No.1", Set.of(FeatureScope.SURVEY));

        assertThat(context.hasScope(FeatureScope.PAYMENT)).isFalse();
    }

    @Test
    @DisplayName("代理モードでない（本人操作）場合は hasScope が常に false")
    void shouldReturnFalseWhenNotProxy() {
        ProxyInputContext context = new ProxyInputContext();

        assertThat(context.isProxy()).isFalse();
        assertThat(context.hasScope(FeatureScope.PAYMENT)).isFalse();
    }

    @Test
    @DisplayName("旧 activate（スコープなし）は空スコープで有効化され hasScope は false")
    void legacyActivateHasNoScopes() {
        ProxyInputContext context = new ProxyInputContext();
        context.activate(100L, 1L, "PAPER_FORM", "金庫No.1");

        assertThat(context.isProxy()).isTrue();
        assertThat(context.hasScope(FeatureScope.PAYMENT)).isFalse();
    }

    @Test
    @DisplayName("clear でスコープ集合もリセットされる")
    void clearResetsScopes() {
        ProxyInputContext context = new ProxyInputContext();
        context.activate(100L, 1L, "PAPER_FORM", "金庫No.1", Set.of(FeatureScope.PAYMENT));
        context.clear();

        assertThat(context.isProxy()).isFalse();
        assertThat(context.hasScope(FeatureScope.PAYMENT)).isFalse();
        assertThat(context.getScopes()).isEmpty();
    }
}
