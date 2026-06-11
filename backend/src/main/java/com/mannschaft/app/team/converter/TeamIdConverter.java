package com.mannschaft.app.team.converter;

import com.mannschaft.app.team.service.TeamService;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * チームIDのパスパラメータ変換器。
 * スラッグ文字列（slug）→ 内部BIGINT IDへの解決と、数値文字列→Longの両方を扱う。
 */
@Component
public class TeamIdConverter implements Converter<String, Long> {

    private final TeamService teamService;

    public TeamIdConverter(@Lazy TeamService teamService) {
        this.teamService = teamService;
    }

    @Override
    public Long convert(String source) {
        try {
            return Long.parseLong(source);
        } catch (NumberFormatException e) {
            try {
                // 数値でない場合はスラッグとして解決する
                return teamService.resolveTeamId(source);
            } catch (Exception ex) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "チームが見つかりません: " + source);
            }
        }
    }
}
