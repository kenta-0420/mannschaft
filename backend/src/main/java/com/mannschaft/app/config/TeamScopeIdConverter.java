package com.mannschaft.app.config;

import com.mannschaft.app.team.service.TeamService;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * チームスコープのパス変数変換器（{@code String → }{@link TeamScopeId}・課題 #12・案A）。
 *
 * <p>数値文字列は高速パスでそのまま、非数値 slug は
 * {@link TeamService#resolveTeamId(String)} で内部 BIGINT ID へ解決する。
 * 解決失敗は 404 に統一する（{@link ScopeSlugResolution}）。</p>
 *
 * <p>変換先の型が {@link TeamScopeId} で一意なため、org 用の {@link OrgScopeIdConverter} と
 * 併存しても Spring の変換器選択が曖昧にならない（ネスト同一 slug の誤解決を根治）。</p>
 */
@Component
public class TeamScopeIdConverter implements Converter<String, TeamScopeId> {

    private static final String NOT_FOUND_LABEL = "チームが見つかりません: ";

    private final TeamService teamService;

    public TeamScopeIdConverter(@Lazy TeamService teamService) {
        this.teamService = teamService;
    }

    @Override
    public TeamScopeId convert(@NonNull String source) {
        return new TeamScopeId(
                ScopeSlugResolution.resolve(source, teamService::resolveTeamId, NOT_FOUND_LABEL));
    }
}
