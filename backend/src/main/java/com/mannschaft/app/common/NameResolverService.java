package com.mannschaft.app.common;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ユーザー表示名・チーム名・組織名のバッチ解決サービス。
 * 複数の機能横断で名前解決が必要な場面で、N+1 問題を回避するために使用する。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NameResolverService {

    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;

    /**
     * ユーザーIDの集合から表示名マップを返す。
     *
     * @param userIds ユーザーIDの集合
     * @return Map(userId → displayName)。該当なしのIDは含まれない
     */
    public Map<Long, String> resolveUserDisplayNames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(
                        UserEntity::getId,
                        UserEntity::getDisplayName
                ));
    }

    /**
     * 単一ユーザーの表示名を返す。
     *
     * @param userId ユーザーID
     * @return 表示名。該当なしの場合は "不明なユーザー"
     */
    public String resolveUserDisplayName(Long userId) {
        if (userId == null) {
            return "不明なユーザー";
        }
        return userRepository.findById(userId)
                .map(UserEntity::getDisplayName)
                .orElse("不明なユーザー");
    }

    /**
     * チームIDの集合から名前マップを返す。
     *
     * @param teamIds チームIDの集合
     * @return Map(teamId → name)
     */
    public Map<Long, String> resolveTeamNames(Collection<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return teamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(
                        TeamEntity::getId,
                        TeamEntity::getName
                ));
    }

    /**
     * 組織IDの集合から名前マップを返す。
     *
     * @param orgIds 組織IDの集合
     * @return Map(orgId → name)
     */
    public Map<Long, String> resolveOrganizationNames(Collection<Long> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return organizationRepository.findAllById(orgIds).stream()
                .collect(Collectors.toMap(
                        OrganizationEntity::getId,
                        OrganizationEntity::getName
                ));
    }

    /**
     * スコープ種別とIDからアイコン画像URLを返す。
     * scopeType は文字列で受け取り、各パッケージ固有の ScopeType enum に依存しない。
     * PERSONAL スコープまたは該当なしの場合は null を返す。
     *
     * @param scopeType "TEAM" または "ORGANIZATION"
     * @param scopeId   スコープID
     * @return アイコン画像URL。未設定またはPERSONALスコープの場合は null
     */
    public String resolveIconUrl(String scopeType, Long scopeId) {
        if (scopeType == null || scopeId == null) {
            return null;
        }
        return switch (scopeType.toUpperCase()) {
            case "TEAM" -> teamRepository.findById(scopeId)
                    .map(TeamEntity::getIconUrl)
                    .orElse(null);
            case "ORGANIZATION" -> organizationRepository.findById(scopeId)
                    .map(OrganizationEntity::getIconUrl)
                    .orElse(null);
            default -> null;
        };
    }

    /**
     * スコープ種別とIDからスコープ名を返す。
     * scopeType は文字列で受け取り、各パッケージ固有の ScopeType enum に依存しない。
     *
     * @param scopeType "TEAM" または "ORGANIZATION"
     * @param scopeId   スコープID
     * @return スコープ名。該当なしの場合は "不明なスコープ"
     */
    public String resolveScopeName(String scopeType, Long scopeId) {
        if (scopeType == null || scopeId == null) {
            return "不明なスコープ";
        }
        return switch (scopeType.toUpperCase()) {
            case "TEAM" -> teamRepository.findById(scopeId)
                    .map(TeamEntity::getName)
                    .orElse("不明なチーム");
            case "ORGANIZATION" -> organizationRepository.findById(scopeId)
                    .map(OrganizationEntity::getName)
                    .orElse("不明な組織");
            case "PERSONAL" -> "個人";
            default -> "不明なスコープ";
        };
    }
}
