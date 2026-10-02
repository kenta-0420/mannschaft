package com.mannschaft.app.common;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
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
 *
 * <p>表示名ルール:</p>
 * <ul>
 *   <li>TEAM_INTERNAL / CROSS_TEAM → 実名（lastName + firstName）</li>
 *   <li>PUBLIC → ニックネーム（displayName）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NameResolverService {

    /**
     * 表示スコープ。スコープに応じて実名・ニックネームを切り替える。
     */
    public enum DisplayScope {
        /** チーム内・組織内（実名表示） */
        TEAM_INTERNAL,
        /** フレンドチーム・上位/下位組織（実名表示） */
        CROSS_TEAM,
        /** 外部公開コンテンツ（ニックネーム表示） */
        PUBLIC
    }

    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;
    private final MediaUrlResolver mediaUrlResolver;

    /**
     * ユーザーIDの集合から表示名マップを返す（ニックネーム）。
     * 外部公開スコープ専用。チーム内表示には {@link #resolveUserFullNames} を使うこと。
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
     * ユーザーIDの集合からアバター表示URLマップを返す。avatarUrl 未設定／解決不能のIDは含まれない。
     *
     * <p>DB には生の R2 キーが格納されているため、{@link MediaUrlResolver} で署名付き表示 URL に解決する
     * （生キーをそのまま返すと FE が相対 URL として解釈し 404 になる）。
     * {@code Collectors.toMap} は null 値で例外を投げるため、HashMap + null 除外で構築する。</p>
     *
     * @param userIds ユーザーIDの集合
     * @return Map(userId → 署名付きアバター表示URL)。avatarUrl が null／解決不能のIDは含まれない
     */
    public Map<Long, String> resolveUserAvatarUrls(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> result = new java.util.HashMap<>();
        for (UserEntity u : userRepository.findAllById(userIds)) {
            String resolved = mediaUrlResolver.resolve(u.getAvatarUrl());
            if (resolved != null) {
                result.put(u.getId(), resolved);
            }
        }
        return result;
    }

    /**
     * 単一ユーザーの表示名を返す（ニックネーム）。
     * 外部公開スコープ専用。チーム内表示には {@link #resolveUserFullName(Long)} を使うこと。
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
     * ユーザーの実名（姓 + 名）を返す。
     * チーム内・組織内・クロスチームスコープで使用する。
     *
     * @param user ユーザーエンティティ
     * @return 実名。退会済みユーザーの場合は "退会済みユーザー"
     */
    public String resolveUserFullName(UserEntity user) {
        if (user == null) {
            return "不明なユーザー";
        }
        return user.getLastName() + " " + user.getFirstName();
    }

    /**
     * 単一ユーザーの実名（姓 + 名）を返す。
     * チーム内・組織内・クロスチームスコープで使用する。
     *
     * @param userId ユーザーID
     * @return 実名。該当なしの場合は "不明なユーザー"
     */
    public String resolveUserFullName(Long userId) {
        if (userId == null) {
            return "不明なユーザー";
        }
        return userRepository.findById(userId)
                .map(u -> u.getLastName() + " " + u.getFirstName())
                .orElse("不明なユーザー");
    }

    /**
     * ユーザーIDの集合から実名マップを返す（姓 + 名）。
     * チーム内・組織内・クロスチームスコープで使用する。
     *
     * @param userIds ユーザーIDの集合
     * @return Map(userId → fullName)。該当なしのIDは含まれない
     */
    public Map<Long, String> resolveUserFullNames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(
                        UserEntity::getId,
                        u -> u.getLastName() + " " + u.getFirstName()
                ));
    }

    /**
     * スコープに応じてユーザー名を解決する汎用メソッド。
     *
     * @param user  ユーザーエンティティ
     * @param scope 表示スコープ
     * @return スコープに対応した表示名
     */
    public String resolveDisplayName(UserEntity user, DisplayScope scope) {
        if (scope == DisplayScope.PUBLIC) {
            return user.getDisplayName();
        }
        return resolveUserFullName(user);
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
     * チームIDの集合からアイコン表示URLマップを返す（N+1 回避・バッチ）。
     *
     * <p>DB には生の R2 キーが格納されているため {@link MediaUrlResolver} で署名付き表示 URL に
     * 解決する（{@link #resolveUserAvatarUrls} と同方針）。iconUrl が null／解決不能のIDは含めない
     * （{@code Collectors.toMap} は null 値で例外を投げるため HashMap + null 除外で構築する）。</p>
     *
     * @param teamIds チームIDの集合
     * @return Map(teamId → 署名付きアイコン表示URL)。未設定／解決不能のIDは含まれない
     */
    public Map<Long, String> resolveTeamIconUrls(Collection<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> result = new java.util.HashMap<>();
        for (TeamEntity t : teamRepository.findAllById(teamIds)) {
            String resolved = mediaUrlResolver.resolve(t.getIconUrl());
            if (resolved != null) {
                result.put(t.getId(), resolved);
            }
        }
        return result;
    }

    /**
     * 組織IDの集合からアイコン表示URLマップを返す（N+1 回避・バッチ）。
     *
     * <p>解決方針は {@link #resolveTeamIconUrls} と同じ（生 R2 キー → 署名付き URL）。</p>
     *
     * @param orgIds 組織IDの集合
     * @return Map(orgId → 署名付きアイコン表示URL)。未設定／解決不能のIDは含まれない
     */
    public Map<Long, String> resolveOrganizationIconUrls(Collection<Long> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> result = new java.util.HashMap<>();
        for (OrganizationEntity o : organizationRepository.findAllById(orgIds)) {
            String resolved = mediaUrlResolver.resolve(o.getIconUrl());
            if (resolved != null) {
                result.put(o.getId(), resolved);
            }
        }
        return result;
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
     * 印鑑初回生成用に姓・名を個別に返す。
     * いずれかが null の場合は空文字で補完する。
     *
     * @param userId ユーザーID
     * @return 姓名パーツ（姓・名が両方空の場合も返す）
     */
    public UserNameParts resolveUserNameParts(Long userId) {
        if (userId == null) {
            return new UserNameParts("", "");
        }
        return userRepository.findById(userId)
                .map(u -> new UserNameParts(
                        u.getLastName() != null ? u.getLastName() : "",
                        u.getFirstName() != null ? u.getFirstName() : ""))
                .orElse(new UserNameParts("", ""));
    }

    /** 電子印鑑初回生成用の氏名パーツ。 */
    public record UserNameParts(String lastName, String firstName) {}

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

    /** 共有予定の詳細API・画面URLで使用する公開slugを内部IDから解決する。 */
    public String resolveScopeSlug(String scopeType, Long scopeId) {
        if (scopeType == null || scopeId == null) {
            return null;
        }
        return switch (scopeType.toUpperCase()) {
            case "TEAM" -> teamRepository.findById(scopeId).map(TeamEntity::getSlug).orElse(null);
            case "ORGANIZATION" -> organizationRepository.findById(scopeId).map(OrganizationEntity::getSlug).orElse(null);
            default -> null;
        };
    }
}
