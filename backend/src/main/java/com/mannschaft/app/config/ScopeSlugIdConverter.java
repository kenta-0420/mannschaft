package com.mannschaft.app.config;

import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * スコープ識別子（team / organization）のパスパラメータ変換器。
 *
 * <p>数値文字列はそのまま {@code Long} へ、非数値スラッグはリクエストパスのスコープ種別に応じて
 * team もしくは organization の内部 BIGINT ID へ解決する。</p>
 *
 * <p><b>なぜ 1 クラスで両ドメインを扱うのか:</b> Spring MVC は {@code String → Long} の変換器を
 * 高々 1 つしか選べない。team 用と organization 用に別々の {@code Converter<String, Long>} を
 * 登録すると変換器選択が曖昧になり競合する。そこで本クラスがリクエスト URI の直前セグメント
 * （{@code /organizations/{value}} か {@code /teams/{value}} か）を見て解決先ドメインを一意に決める。
 * これにより 118 の org 系コントローラの {@code @PathVariable Long} を変更せずに slug 解決を通す。</p>
 *
 * <p>解決に失敗した場合（存在しない slug 等）は 404 NOT_FOUND を送出する（400 ではない）。</p>
 */
@Component
public class ScopeSlugIdConverter implements Converter<String, Long> {

    private final TeamService teamService;
    private final OrganizationService organizationService;

    public ScopeSlugIdConverter(@Lazy TeamService teamService,
                                @Lazy OrganizationService organizationService) {
        this.teamService = teamService;
        this.organizationService = organizationService;
    }

    @Override
    public Long convert(@NonNull String source) {
        // 数値はそのまま解釈する（team / organization 共通の高速パス）
        try {
            return Long.parseLong(source);
        } catch (NumberFormatException ignored) {
            // 非数値 → スラッグとしてスコープ判定のうえ解決する
        }

        Scope scope = detectScope(source);
        try {
            return switch (scope) {
                case ORGANIZATION -> organizationService.resolveOrgId(source);
                case TEAM -> teamService.resolveTeamId(source);
            };
        } catch (ResponseStatusException e) {
            // 既に適切なステータスを持つ例外はそのまま伝播させる
            throw e;
        } catch (Exception e) {
            // 解決失敗（不在 slug 等）は 404 に統一する（型変換失敗の 400 に落とさない）
            String label = scope == Scope.ORGANIZATION ? "組織が見つかりません: " : "チームが見つかりません: ";
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, label + source);
        }
    }

    private enum Scope { TEAM, ORGANIZATION }

    /**
     * リクエスト URI から、変換対象値の直前パスセグメントを見てスコープを判定する。
     *
     * <p>{@code /organizations/{source}} なら ORGANIZATION、それ以外（{@code /teams/{source}} を含む）は
     * TEAM と判定する。リクエストコンテキストが得られない場合は従来動作（team 解決）を既定とし、
     * 後方互換を保つ。</p>
     */
    private Scope detectScope(String source) {
        String uri = currentRequestUri();
        if (uri == null) {
            return Scope.TEAM;
        }
        String[] segments = uri.split("/");
        for (int i = 1; i < segments.length; i++) {
            if (source.equals(segments[i])) {
                String prev = segments[i - 1];
                if ("organizations".equals(prev)) {
                    return Scope.ORGANIZATION;
                }
                if ("teams".equals(prev)) {
                    return Scope.TEAM;
                }
            }
        }
        return Scope.TEAM;
    }

    private String currentRequestUri() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getRequestURI();
        }
        return null;
    }
}
