package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.Set;

/**
 * Bulletin API の scope_id を Long（BIGINT ID）に解決するコンポーネント。
 *
 * <p>スラッグ（slug）形式の場合は各ドメインのリポジトリを通じて BIGINT ID に変換する。
 * Long 形式の文字列はそのまま Long に変換する（後方互換性）。</p>
 *
 * <p>チームページ（{@code /teams/{slug}/bulletin}）から呼ばれる経路では、
 * FE が slug を scope_id として渡してくるため、本コンポーネントで変換する。</p>
 *
 * <h2>スコープ種別の入口ゲート（fail-closed）</h2>
 * <p>本コンポーネントは {@code /api/v1/{scopeType}/{scopeId}/bulletin/...} という
 * <b>スコープ付きパス経路の全 EP が必ず通る唯一の入口</b>であり、
 * ここで「そのパス形式で扱ってよいスコープ種別か」を一元的に判定する
 * （{@link #PATH_ADDRESSABLE_SCOPES}）。EP ごとに個別判定すると必ず漏れるため、
 * 判定は本メソッド 1 箇所に集約する。</p>
 *
 * <p>受理するのは {@code TEAM / ORGANIZATION}（ロール・メンバーシップ基盤で認可判定できる）と
 * {@code PERSONAL}（{@code scope_id = userId} の本人スコープ）のみ。
 * <b>{@code VILLAGE} は受理しない</b>: 村掲示板の認可は村 ID（{@code scope_village_id}）を
 * 必要とするが、このパス形式は村 ID を伴わないため村側の認可判定を行う手段がない。
 * 村掲示板は {@code scope_village_id} を伴うグローバル経路
 * （{@code /api/v1/bulletin/threads} 等）が正規の入口であり、FE もそちらのみを使う。
 * 入口を一本化し、判定手段を持たない経路は fail-closed で拒否する。</p>
 *
 * <p>グローバル経路も非村スコープの解決で本メソッドを利用するが、村スコープは
 * 呼び出し前に分岐して村専用サービスへ委譲されるため、本ゲートには到達しない。</p>
 */
@Component
@RequiredArgsConstructor
public class BulletinScopeIdResolver {

    /**
     * スコープ付きパス経路で受理するスコープ種別（許可リスト方式・fail-closed）。
     *
     * <p>列挙に無い種別（{@code VILLAGE} / 大会連絡スコープ等）は認可判定手段を持たないため拒否する。
     * 新しいスコープ種別を追加する際は、その種別の認可判定をどこで行うか決めてから本集合に足すこと。</p>
     */
    private static final Set<ScopeType> PATH_ADDRESSABLE_SCOPES =
            EnumSet.of(ScopeType.TEAM, ScopeType.ORGANIZATION, ScopeType.PERSONAL);

    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;

    /**
     * scope_id 文字列を Long（BIGINT ID）に解決する。
     *
     * <p>まず「そのスコープ種別をこのパス形式で扱ってよいか」を検証し（fail-closed）、
     * そのうえで Long として解析できる場合はそのまま返す（後方互換）。
     * それ以外はスラッグ形式として scopeType に応じたリポジトリを経由して BIGINT ID に変換する。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   scope_id 文字列（Long 形式またはスラッグ形式）
     * @return 対応する BIGINT ID
     * @throws BusinessException       受理しないスコープ種別（{@link CommonErrorCode#COMMON_002}・403）
     * @throws ResponseStatusException エンティティが見つからない場合（404）
     */
    public Long resolve(ScopeType scopeType, String scopeId) {
        requirePathAddressableScope(scopeType);

        // まず Long として解析を試みる（後方互換）
        try {
            return Long.parseLong(scopeId);
        } catch (NumberFormatException e) {
            // Long でなければスラッグとして解析する
        }

        return switch (scopeType) {
            case TEAM -> teamRepository.findBySlugAndDeletedAtIsNull(scopeId)
                    .map(t -> t.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "チームが見つかりません: " + scopeId));
            case ORGANIZATION -> organizationRepository.findBySlugAndDeletedAtIsNull(scopeId)
                    .map(o -> o.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "組織が見つかりません: " + scopeId));
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "スラッグ形式の scope_id は " + scopeType + " では使用できません");
        };
    }

    /**
     * スコープ付きパス経路で扱えるスコープ種別であることを要求する（fail-closed）。
     *
     * <p>許可リストに無い種別は、この経路では認可判定の手段が無い（判定に必要な情報が
     * パスに含まれない）ため、素通しせず 403 で拒否する。</p>
     *
     * @throws BusinessException 受理しないスコープ種別（{@link CommonErrorCode#COMMON_002}）
     */
    private void requirePathAddressableScope(ScopeType scopeType) {
        if (scopeType == null || !PATH_ADDRESSABLE_SCOPES.contains(scopeType)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }
}
