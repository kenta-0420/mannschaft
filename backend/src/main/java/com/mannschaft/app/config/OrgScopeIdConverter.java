package com.mannschaft.app.config;

import com.mannschaft.app.organization.service.OrganizationService;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * 組織スコープのパス変数変換器（{@code String → }{@link OrgScopeId}・課題 #12・案A）。
 *
 * <p>数値文字列は高速パスでそのまま、非数値 slug は
 * {@link OrganizationService#resolveOrgId(String)} で内部 BIGINT ID へ解決する。
 * 解決失敗は 404 に統一する（{@link ScopeSlugResolution}）。</p>
 *
 * <p>変換先の型が {@link OrgScopeId} で一意なため、team 用の {@link TeamScopeIdConverter} と
 * 併存しても Spring の変換器選択が曖昧にならない（ネスト同一 slug の誤解決を根治）。</p>
 */
@Component
public class OrgScopeIdConverter implements Converter<String, OrgScopeId> {

    private static final String NOT_FOUND_LABEL = "組織が見つかりません: ";

    private final OrganizationService organizationService;

    public OrgScopeIdConverter(@Lazy OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    @Override
    public OrgScopeId convert(@NonNull String source) {
        return new OrgScopeId(
                ScopeSlugResolution.resolve(source, organizationService::resolveOrgId, NOT_FOUND_LABEL));
    }
}
