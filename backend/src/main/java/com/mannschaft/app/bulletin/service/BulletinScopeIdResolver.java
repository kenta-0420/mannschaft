package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Bulletin API の scope_id を Long（BIGINT ID）に解決するコンポーネント。
 *
 * <p>UUID（publicId）形式の場合は各ドメインのリポジトリを通じて BIGINT ID に変換する。
 * Long 形式の文字列はそのまま Long に変換する（後方互換性）。</p>
 *
 * <p>チームページ（{@code /teams/{publicId}/bulletin}）から呼ばれる経路では、
 * FE が publicId（UUID）を scope_id として渡してくるため、本コンポーネントで変換する。</p>
 */
@Component
@RequiredArgsConstructor
public class BulletinScopeIdResolver {

    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;

    /**
     * scope_id 文字列を Long（BIGINT ID）に解決する。
     *
     * <p>Long として解析できる場合はそのまま返す（後方互換）。
     * UUID 形式の場合は scopeType に応じたリポジトリを経由して BIGINT ID に変換する。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   scope_id 文字列（Long 形式または UUID 形式）
     * @return 対応する BIGINT ID
     * @throws ResponseStatusException scope_id の形式が不正な場合（400）、またはエンティティが見つからない場合（404）
     */
    public Long resolve(ScopeType scopeType, String scopeId) {
        // まず Long として解析を試みる（後方互換）
        try {
            return Long.parseLong(scopeId);
        } catch (NumberFormatException e) {
            // Long でなければ UUID として解析する
        }

        UUID publicId;
        try {
            publicId = UUID.fromString(scopeId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scope_id の形式が不正です: " + scopeId);
        }

        return switch (scopeType) {
            case TEAM -> teamRepository.findByPublicId(publicId)
                    .map(t -> t.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "チームが見つかりません: " + scopeId));
            case ORGANIZATION -> organizationRepository.findByPublicId(publicId)
                    .map(o -> o.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "組織が見つかりません: " + scopeId));
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "UUID 形式の scope_id は " + scopeType + " では使用できません");
        };
    }
}
