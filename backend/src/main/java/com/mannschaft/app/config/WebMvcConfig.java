package com.mannschaft.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC カスタム設定。
 *
 * <p>スコープのスラッグ（slug）→ 内部 BIGINT ID 変換を登録する。</p>
 *
 * <ul>
 *   <li>{@link ScopeSlugIdConverter}（{@code String→Long}）: 単一スコープ（{@code /teams/{id}} または
 *       {@code /organizations/{id}}）を持つ 118 本のコントローラ用。変換先が {@code Long} で共通のため
 *       1 本に統合し、URI の直前セグメントでスコープを推定する（従来どおり・据え置き）。</li>
 *   <li>{@link OrgScopeIdConverter}（{@code String→}{@link OrgScopeId}） / {@link TeamScopeIdConverter}
 *       （{@code String→}{@link TeamScopeId}）: ネスト二重スコープ
 *       {@code /organizations/{orgId}/teams/{teamId}} を持つコントローラ用（課題 #12・案A）。
 *       org/team を <b>型で</b>分離し、同一 slug でも変換器が一意に選ばれるため誤解決しない。
 *       変換先の型が異なるので {@link ScopeSlugIdConverter} とは競合しない。</li>
 * </ul>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ScopeSlugIdConverter scopeSlugIdConverter;
    private final OrgScopeIdConverter orgScopeIdConverter;
    private final TeamScopeIdConverter teamScopeIdConverter;

    public WebMvcConfig(@Lazy ScopeSlugIdConverter scopeSlugIdConverter,
                        @Lazy OrgScopeIdConverter orgScopeIdConverter,
                        @Lazy TeamScopeIdConverter teamScopeIdConverter) {
        this.scopeSlugIdConverter = scopeSlugIdConverter;
        this.orgScopeIdConverter = orgScopeIdConverter;
        this.teamScopeIdConverter = teamScopeIdConverter;
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(scopeSlugIdConverter);
        registry.addConverter(orgScopeIdConverter);
        registry.addConverter(teamScopeIdConverter);
    }
}
