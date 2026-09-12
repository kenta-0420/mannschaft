package com.mannschaft.app.membership.query;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MemberDto;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * F00.5 OQ-10 / OQ-2 確定実装: メンバー一覧クエリのディスパッチャ。
 *
 * <p>{@code GET /teams/{id}/members?role=ADMIN} 等の roleName 指定に応じて
 * 参照先（user_roles か memberships か）を 1 段で分岐させる。</p>
 *
 * <p>ディスパッチ規則:</p>
 * <ul>
 *   <li>{@code role=null}: 全件返却（user_roles + memberships を統合し、OQ-2 優先度で 1 値返す）</li>
 *   <li>{@code role IN (ADMIN, DEPUTY_ADMIN, GUEST, SYSTEM_ADMIN)}: user_roles のみ参照</li>
 *   <li>{@code role IN (MEMBER, SUPPORTER)}: memberships のみ参照</li>
 *   <li>その他: {@code BadRequestException}</li>
 * </ul>
 *
 * <p>OQ-2 優先表示: 同一 user に ADMIN(user_roles) と MEMBER(memberships) が両方ある場合、
 * 優先度 SYSTEM_ADMIN &gt; ADMIN &gt; DEPUTY_ADMIN &gt; MEMBER &gt; SUPPORTER &gt; GUEST に従い、
 * 高位を {@link MemberDto#roleName()} に返す。</p>
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §13.6.4</p>
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class MemberQueryDispatcher {

    private final UserRoleRepository userRoleRepository;
    private final MembershipRepository membershipRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final MediaUrlResolver mediaUrlResolver;

    /** OQ-2 優先度マップ（小さいほど優先）。 */
    private static final Map<String, Integer> ROLE_PRIORITY = Map.of(
            "SYSTEM_ADMIN", 1,
            "ADMIN", 2,
            "DEPUTY_ADMIN", 3,
            "MEMBER", 4,
            "SUPPORTER", 5,
            "GUEST", 6
    );

    /**
     * メンバー一覧を取得する。
     *
     * @param scopeId スコープ ID
     * @param scopeType スコープ種別
     * @param roleName 絞り込みロール名（NULL なら全件）
     * @return MemberDto のリスト（同一ユーザーは 1 行に集約され、roleName は OQ-2 優先度で決定）
     */
    public List<MemberDto> queryMembers(Long scopeId, ScopeType scopeType, String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return queryAll(scopeId, scopeType);
        }
        return switch (roleName) {
            case "ADMIN", "DEPUTY_ADMIN", "GUEST", "SYSTEM_ADMIN" ->
                    queryByPermissionRole(scopeId, scopeType, roleName);
            case "MEMBER", "SUPPORTER" ->
                    queryByMembershipRoleKind(scopeId, scopeType, RoleKind.valueOf(roleName));
            default -> throw new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_INVALID_ROLE_KIND);
        };
    }

    /**
     * メンバー一覧の「誰がどのロールか」だけを、表示名・アバターを解決せずに取得する（CMP-260910-1555）。
     *
     * <h2>なぜ要るのか</h2>
     * <p>{@link #queryMembers} は常にスコープ全員を返し、しかもユーザー 1 人ごとに
     * {@code findMemberSummaryById} を引く（N+1）。呼び出し側が
     * {@code TeamService#getMembers} のようにメモリ上で切り出してページングを
     * エミュレートしていると、<b>1 ページ取得するたびに全員ぶんの重い処理が走る</b>。
     * 一覧を最後までめくると総処理量が人数 N に対して概ね N^2/ページサイズになり、
     * 大規模スコープでは DB 負荷とタイムアウトで一覧そのものが使えなくなる。</p>
     *
     * <h2>使い方</h2>
     * <p>本メソッドで軽い行（userId・ロール・joinedAt のみ。表示名とアバターは {@code null}）を
     * 全件そろえ、呼び出し側がページ位置で切り出したうえで {@link #hydrate} に渡す。
     * 重い処理（表示名・アバターの解決）はページ内の人数ぶんで頭打ちになり、
     * 全ページを通じた総量は N に比例する。並び順・集約結果・総件数は
     * {@link #queryMembers} と同一であり、ページングの意味論は変えていない。</p>
     *
     * @param scopeId   スコープ ID
     * @param scopeType スコープ種別
     * @param roleName  絞り込みロール名（NULL なら全件）
     * @return 絞り込み後の全メンバー（表示名・アバターは未解決の {@code null}）
     */
    public List<MemberDto> queryMemberIdentities(Long scopeId, ScopeType scopeType, String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return aggregateIdentities(scopeId, scopeType);
        }
        return switch (roleName) {
            case "ADMIN", "DEPUTY_ADMIN", "GUEST", "SYSTEM_ADMIN" -> {
                Optional<RoleEntity> roleOpt = roleRepository.findByName(roleName);
                if (roleOpt.isEmpty()) {
                    yield List.of();
                }
                Long roleId = roleOpt.get().getId();
                List<UserRoleEntity> entities;
                if (scopeType == ScopeType.TEAM) {
                    entities = userRoleRepository.findByTeamIdAndRoleId(scopeId, roleId);
                } else {
                    entities = userRoleRepository.findByOrganizationId(scopeId, Pageable.unpaged())
                            .getContent()
                            .stream()
                            .filter(ur -> roleId.equals(ur.getRoleId()))
                            .toList();
                }
                yield entities.stream()
                        .map(ur -> identity(ur.getUserId(), roleName, ur.getCreatedAt()))
                        .toList();
            }
            case "MEMBER", "SUPPORTER" -> {
                RoleKind roleKind = RoleKind.valueOf(roleName);
                yield membershipRepository
                        .findByScopeAndActive(scopeType, scopeId, Pageable.unpaged())
                        .getContent()
                        .stream()
                        .filter(m -> m.getRoleKind() == roleKind)
                        .filter(m -> m.getUserId() != null)
                        .map(m -> identity(m.getUserId(), roleKind.name(), m.getJoinedAt()))
                        .toList();
            }
            default -> throw new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_INVALID_ROLE_KIND);
        };
    }

    /** 表示名・アバターを未解決（{@code null}）にした軽量行。 */
    private MemberDto identity(Long userId, String roleName, java.time.LocalDateTime joinedAt) {
        return new MemberDto(userId, null, null, roleName, joinedAt);
    }

    /**
     * {@link #queryMemberIdentities} が返した行のうち、渡されたぶんだけを 1 クエリで実体化する。
     *
     * <p>論理削除等で users 側に行が無いユーザーは、{@code findMemberSummaryById} を
     * 使っていた頃と同じく displayName / avatarUrl が {@code null} の行として残す
     * （一覧から消すと総件数と表示件数が食い違うため）。</p>
     *
     * @param identities {@link #queryMemberIdentities} の結果（通常は 1 ページぶんに切り出したもの）
     * @return 表示名・アバターを解決した {@link MemberDto}（並び順は入力どおり）
     */
    public List<MemberDto> hydrate(List<MemberDto> identities) {
        if (identities.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = identities.stream().map(MemberDto::userId).toList();
        Map<Long, UserRepository.MemberSummary> summaries = userRepository
                .findMemberSummariesByIds(userIds).stream()
                .collect(Collectors.toMap(UserRepository.MemberSummary::getId, summary -> summary,
                        (left, right) -> left));

        List<MemberDto> result = new ArrayList<>(identities.size());
        for (MemberDto identity : identities) {
            UserRepository.MemberSummary user = summaries.get(identity.userId());
            result.add(new MemberDto(
                    identity.userId(),
                    user != null ? user.getDisplayName() : null,
                    // 画像 URL 根治 Phase 2: 生 R2 キーを署名付き表示 URL へ解決
                    user != null ? mediaUrlResolver.resolve(user.getAvatarUrl()) : null,
                    identity.roleName(),
                    identity.joinedAt()));
        }
        return result;
    }

    /**
     * 全件（roleName 未指定）の集約 — {@link #queryAll} と同じ優先度規則・同じ並び順。
     */
    private List<MemberDto> aggregateIdentities(Long scopeId, ScopeType scopeType) {
        List<UserRoleEntity> userRoles = scopeType == ScopeType.TEAM
                ? userRoleRepository.findByTeamId(scopeId, Pageable.unpaged()).getContent()
                : userRoleRepository.findByOrganizationId(scopeId, Pageable.unpaged()).getContent();
        List<MembershipEntity> memberships = membershipRepository
                .findByScopeAndActive(scopeType, scopeId, Pageable.unpaged()).getContent();

        // ロール名は roleId ごとに 1 回だけ引く。1 行ごとに roleRepository.findById を呼ぶと
        // user_roles の件数ぶんクエリが出る（N+1）。ロールの種類数はたかだか数件なので
        // 重複を除いた ID でまとめて引けば 1 クエリで済む。
        Map<Long, String> roleNamesById = roleNamesFor(userRoles);

        Map<Long, MemberDto> aggregated = new LinkedHashMap<>();
        for (UserRoleEntity ur : userRoles) {
            String urRoleName = roleNamesById.get(ur.getRoleId());
            if (urRoleName == null) {
                continue;
            }
            mergeAggregated(aggregated, ur.getUserId(), null, null, urRoleName, ur.getCreatedAt());
        }
        for (MembershipEntity m : memberships) {
            if (m.getUserId() == null) {
                continue; // GDPR マスキング済はスキップ
            }
            mergeAggregated(aggregated, m.getUserId(), null, null,
                    m.getRoleKind().name(), m.getJoinedAt());
        }
        return new ArrayList<>(aggregated.values());
    }

    /**
     * user_roles 行が参照する roleId を重複なく集め、ロール名を 1 クエリで引く（CMP-260910-1555）。
     *
     * <p>{@link #roleNameFor} を 1 行ずつ呼ぶと user_roles の件数ぶんクエリが出る。
     * 実在するロールはたかだか数種類なので、重複を除いた ID でまとめて引く。</p>
     *
     * @param userRoles 対象の user_roles 行
     * @return roleId → ロール名（存在しない roleId は含まれない）
     */
    private Map<Long, String> roleNamesFor(List<UserRoleEntity> userRoles) {
        List<Long> roleIds = userRoles.stream()
                .map(UserRoleEntity::getRoleId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (RoleEntity role : roleRepository.findAllById(roleIds)) {
            if (role.getName() != null) {
                names.put(role.getId(), role.getName());
            }
        }
        return names;
    }

    /**
     * 全件返却 — user_roles と memberships を統合する。
     */
    private List<MemberDto> queryAll(Long scopeId, ScopeType scopeType) {
        // user_roles 由来（権限ロール + 既存 MEMBER/SUPPORTER 混在の可能性あり）
        List<UserRoleEntity> userRoles = scopeType == ScopeType.TEAM
                ? userRoleRepository.findByTeamId(scopeId, Pageable.unpaged()).getContent()
                : userRoleRepository.findByOrganizationId(scopeId, Pageable.unpaged()).getContent();

        // memberships 由来（MEMBER/SUPPORTER のアクティブ）
        List<MembershipEntity> memberships = membershipRepository
                .findByScopeAndActive(scopeType, scopeId, Pageable.unpaged()).getContent();

        // userId をキーに OQ-2 優先度で集約
        Map<Long, MemberDto> aggregated = new LinkedHashMap<>();

        for (UserRoleEntity ur : userRoles) {
            String urRoleName = roleNameFor(ur.getRoleId());
            if (urRoleName == null) {
                continue;
            }
            UserRepository.MemberSummary user = userRepository.findMemberSummaryById(ur.getUserId()).orElse(null);
            String displayName = user != null ? user.getDisplayName() : null;
            // 画像 URL 根治 Phase 2: 生 R2 キーを署名付き表示 URL へ解決
            String avatarUrl = user != null ? mediaUrlResolver.resolve(user.getAvatarUrl()) : null;
            mergeAggregated(aggregated, ur.getUserId(), displayName, avatarUrl, urRoleName, ur.getCreatedAt() != null ? ur.getCreatedAt() : null);
        }

        for (MembershipEntity m : memberships) {
            if (m.getUserId() == null) {
                continue; // GDPR マスキング済はスキップ
            }
            UserRepository.MemberSummary user = userRepository.findMemberSummaryById(m.getUserId()).orElse(null);
            String displayName = user != null ? user.getDisplayName() : null;
            // 画像 URL 根治 Phase 2: 生 R2 キーを署名付き表示 URL へ解決
            String avatarUrl = user != null ? mediaUrlResolver.resolve(user.getAvatarUrl()) : null;
            mergeAggregated(aggregated, m.getUserId(), displayName, avatarUrl,
                    m.getRoleKind().name(), m.getJoinedAt());
        }

        return new ArrayList<>(aggregated.values());
    }

    /**
     * 権限ロール（ADMIN/DEPUTY_ADMIN/GUEST/SYSTEM_ADMIN）絞り込みは user_roles のみ参照。
     */
    private List<MemberDto> queryByPermissionRole(Long scopeId, ScopeType scopeType, String roleName) {
        Optional<RoleEntity> roleOpt = roleRepository.findByName(roleName);
        if (roleOpt.isEmpty()) {
            return List.of();
        }
        Long roleId = roleOpt.get().getId();

        List<UserRoleEntity> entities;
        if (scopeType == ScopeType.TEAM) {
            entities = userRoleRepository.findByTeamIdAndRoleId(scopeId, roleId);
        } else {
            // ORGANIZATION 用の絞り込みメソッドが UserRoleRepository に同等のものがないため
            // 全件取得して filter する（roles が少数のため許容）
            entities = userRoleRepository.findByOrganizationId(scopeId, Pageable.unpaged())
                    .getContent()
                    .stream()
                    .filter(ur -> roleId.equals(ur.getRoleId()))
                    .toList();
        }

        List<MemberDto> result = new ArrayList<>();
        for (UserRoleEntity ur : entities) {
            UserRepository.MemberSummary user = userRepository.findMemberSummaryById(ur.getUserId()).orElse(null);
            result.add(new MemberDto(
                    ur.getUserId(),
                    user != null ? user.getDisplayName() : null,
                    user != null ? mediaUrlResolver.resolve(user.getAvatarUrl()) : null,
                    roleName,
                    ur.getCreatedAt()
            ));
        }
        return result;
    }

    /**
     * MEMBER/SUPPORTER 絞り込みは memberships のみ参照。
     */
    private List<MemberDto> queryByMembershipRoleKind(Long scopeId, ScopeType scopeType, RoleKind roleKind) {
        List<MembershipEntity> entities = membershipRepository
                .findByScopeAndActive(scopeType, scopeId, Pageable.unpaged())
                .getContent()
                .stream()
                .filter(m -> m.getRoleKind() == roleKind)
                .filter(m -> m.getUserId() != null)
                .toList();

        List<MemberDto> result = new ArrayList<>();
        for (MembershipEntity m : entities) {
            UserRepository.MemberSummary user = userRepository.findMemberSummaryById(m.getUserId()).orElse(null);
            result.add(new MemberDto(
                    m.getUserId(),
                    user != null ? user.getDisplayName() : null,
                    user != null ? mediaUrlResolver.resolve(user.getAvatarUrl()) : null,
                    roleKind.name(),
                    m.getJoinedAt()
            ));
        }
        return result;
    }

    /** 同一 userId に複数情報が来た場合、OQ-2 優先度で勝者を 1 つに決める。 */
    private void mergeAggregated(Map<Long, MemberDto> agg, Long userId,
                                 String displayName, String avatarUrl,
                                 String roleName, java.time.LocalDateTime joinedAt) {
        MemberDto existing = agg.get(userId);
        if (existing == null) {
            agg.put(userId, new MemberDto(userId, displayName, avatarUrl, roleName, joinedAt));
            return;
        }
        int existingPriority = priority(existing.roleName());
        int newPriority = priority(roleName);
        if (newPriority < existingPriority) {
            // 新の方が高位 → 上書き（joinedAt は新側を採用すると古い権限ロール時刻が消えるため、より古い方を採る）
            java.time.LocalDateTime mergedJoinedAt = pickEarlier(existing.joinedAt(), joinedAt);
            agg.put(userId, new MemberDto(userId,
                    displayName != null ? displayName : existing.displayName(),
                    avatarUrl != null ? avatarUrl : existing.avatarUrl(),
                    roleName,
                    mergedJoinedAt));
        } else {
            // 既存が同等以上 → display 等を補完するのみ
            java.time.LocalDateTime mergedJoinedAt = pickEarlier(existing.joinedAt(), joinedAt);
            if (mergedJoinedAt != existing.joinedAt() ||
                    (existing.displayName() == null && displayName != null) ||
                    (existing.avatarUrl() == null && avatarUrl != null)) {
                agg.put(userId, new MemberDto(userId,
                        existing.displayName() != null ? existing.displayName() : displayName,
                        existing.avatarUrl() != null ? existing.avatarUrl() : avatarUrl,
                        existing.roleName(),
                        mergedJoinedAt));
            }
        }
    }

    private java.time.LocalDateTime pickEarlier(java.time.LocalDateTime a, java.time.LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private int priority(String roleName) {
        return ROLE_PRIORITY.getOrDefault(roleName, Integer.MAX_VALUE);
    }

    /**
     * roleId から role name を取得する。
     * 呼び出し回数は scope 内の user_roles 件数程度（数百件）に抑えられているため、
     * キャッシュなしで roleRepository.findById を都度呼ぶ。
     * Spring データの @Cacheable や 1st level cache に任せる方針。
     */
    private String roleNameFor(Long roleId) {
        return roleRepository.findById(roleId).map(RoleEntity::getName).orElse(null);
    }
}
