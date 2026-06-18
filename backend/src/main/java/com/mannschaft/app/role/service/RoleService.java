package com.mannschaft.app.role.service;

import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.RolePermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.RolePermissionRepository;
import com.mannschaft.app.role.repository.PermissionRepository;
import com.mannschaft.app.role.repository.PermissionGroupRepository;
import com.mannschaft.app.role.repository.PermissionGroupPermissionRepository;
import com.mannschaft.app.role.repository.UserPermissionGroupRepository;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.dto.RoleChangeRequest;
import com.mannschaft.app.role.event.MembershipChangedEvent;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.team.event.TeamMemberAuditEvent;
import com.mannschaft.app.organization.event.OrganizationMemberAuditEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * ロール・権限管理の中核サービス。
 * ロール割当・変更・除名・退会・有効権限解決を提供する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class RoleService {

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionRepository permissionRepository;
    private final PermissionGroupRepository permissionGroupRepository;
    private final PermissionGroupPermissionRepository permissionGroupPermissionRepository;
    private final UserPermissionGroupRepository userPermissionGroupRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MembershipService membershipService;

    /**
     * ユーザーにロールを割り当てる。
     */
    @Transactional
    @CacheEvict(value = "role-permissions", key = "#targetUserId + ':' + #scopeType + ':' + #scopeId")
    public void assignRole(Long scopeId, String scopeType, Long targetUserId, Long roleId, Long grantedBy) {
        // ロール存在確認
        roleRepository.findById(roleId)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // 既存ロール存在チェック → 上書き
        // 上書き時は changeRole と同様に flush して DELETE を先に確定させる
        // （uq_user_roles_user_scope ユニーク制約の衝突回避。詳細は changeRole 参照）。
        findUserRole(targetUserId, scopeId, scopeType)
                .ifPresent(existing -> {
                    userRoleRepository.delete(existing);
                    userRoleRepository.flush();
                });

        UserRoleEntity.UserRoleEntityBuilder builder = UserRoleEntity.builder()
                .userId(targetUserId)
                .roleId(roleId)
                .grantedBy(grantedBy);
        setScopeField(builder, scopeId, scopeType);
        userRoleRepository.save(builder.build());

        log.info("ロール割当完了: scopeType={}, scopeId={}, userId={}, roleId={}, grantedBy={}",
                scopeType, scopeId, targetUserId, roleId, grantedBy);

        // F00.5 認可基盤根治: memberships にも MEMBER として入会させる。
        // 認可（AccessControlService.isMember）は memberships を真実の源とするため、
        // user_roles だけでは割当対象者が当該スコープから 403 で締め出される構造的欠陥を防ぐ。
        // join 自身が MembershipChangedEvent(ASSIGNED) を発火するため、
        // 従来この直後に手動発火していた同イベントは二重発火回避のため削除し join に一本化した。
        joinMembershipForRoleGrant(targetUserId, scopeId, scopeType, grantedBy, "ROLE_ASSIGN");
    }

    /**
     * ユーザーのロールを変更する。最後のADMIN保護チェック付き。
     */
    @Transactional
    @CacheEvict(value = "role-permissions", key = "#targetUserId + ':' + #scopeType + ':' + #scopeId")
    public void changeRole(Long scopeId, String scopeType, Long targetUserId,
                           RoleChangeRequest req, Long changedBy) {
        UserRoleEntity current = findUserRole(targetUserId, scopeId, scopeType)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // 最後のADMIN保護
        RoleEntity currentRole = roleRepository.findById(current.getRoleId()).orElse(null);
        if (currentRole != null && "ADMIN".equals(currentRole.getName())) {
            long adminCount = countByRoleInScope(scopeId, scopeType, current.getRoleId());
            if (adminCount <= 1) {
                throw new BusinessException(RoleErrorCode.ROLE_004);
            }
        }

        // 新ロール存在確認
        roleRepository.findById(req.getRoleId())
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // 既存を削除して新規作成
        // 根治: delete 直後に flush して DELETE を先に DB へ確定させる。
        //   user_roles には uq_user_roles_user_scope(user_id, scope_key) のユニーク制約がある
        //   （scope_key は organization_id / team_id から導出される生成列）。
        //   flush しないと Hibernate の write-behind が INSERT を先に発行し、
        //   同一 (user_id, scope_key) で旧行と衝突して DuplicateKeyException → 500 になる。
        userRoleRepository.delete(current);
        userRoleRepository.flush();
        UserRoleEntity.UserRoleEntityBuilder builder = UserRoleEntity.builder()
                .userId(targetUserId)
                .roleId(req.getRoleId());
        setScopeField(builder, scopeId, scopeType);
        userRoleRepository.save(builder.build());

        log.info("ロール変更完了: scopeType={}, scopeId={}, userId={}, newRoleId={}, changedBy={}",
                scopeType, scopeId, targetUserId, req.getRoleId(), changedBy);

        // F00.5 認可基盤根治（防御補填）: ロール変更対象は本来既に memberships に在籍済みのはずだが、
        // 移行バックフィル以前の欠落データ対策として冪等 join を補填する。
        // join は既存アクティブ membership があれば何もしない（冪等）ため無害。
        // 既存在籍時 join はイベントを発火しないため、ロール変更の通知は従来通り下記 CHANGED で担う。
        joinMembershipForRoleGrant(targetUserId, scopeId, scopeType, changedBy, "ROLE_CHANGE");

        // F02.2.1: メンバーシップ変更イベントを発火（ダッシュボードキャッシュ無効化用）
        eventPublisher.publishEvent(new MembershipChangedEvent(
                targetUserId, scopeType, scopeId, MembershipChangedEvent.ChangeType.CHANGED));

        // 監査ログ用イベント発行
        if ("TEAM".equals(scopeType)) {
            eventPublisher.publishEvent(new TeamMemberAuditEvent(
                    changedBy, targetUserId, scopeId, TeamMemberAuditEvent.SubType.ROLE_CHANGED));
        } else if ("ORGANIZATION".equals(scopeType)) {
            eventPublisher.publishEvent(new OrganizationMemberAuditEvent(
                    changedBy, targetUserId, scopeId, OrganizationMemberAuditEvent.SubType.ROLE_CHANGED));
        }
    }

    /**
     * メンバーを除名する。最後のADMIN保護チェック付き。
     */
    @Transactional
    @CacheEvict(value = "role-permissions", key = "#targetUserId + ':' + #scopeType + ':' + #scopeId")
    public void removeMember(Long scopeId, String scopeType, Long targetUserId) {
        UserRoleEntity current = findUserRole(targetUserId, scopeId, scopeType)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // 最後のADMIN保護
        checkLastAdmin(scopeId, scopeType, current);

        userRoleRepository.delete(current);
        log.info("メンバー除名完了: scopeType={}, scopeId={}, userId={}", scopeType, scopeId, targetUserId);

        // F02.2.1: メンバーシップ変更イベントを発火（ダッシュボードキャッシュ無効化用）
        eventPublisher.publishEvent(new MembershipChangedEvent(
                targetUserId, scopeType, scopeId, MembershipChangedEvent.ChangeType.REMOVED));
    }

    /**
     * メンバーを除名する（最後のADMIN保護チェックをスキップ）。
     *
     * <p><b>⚠️ AccountPurgeService 等の管理者操作専用。通常の API からは呼ばないこと。</b></p>
     *
     * <p>本メソッドは {@link #removeMember(Long, String, Long)} と同等のロジックを実行するが、
     * {@link #checkLastAdmin(Long, String, UserRoleEntity)} を呼ばないため、
     * 「最後の ADMIN を除名」しても {@link RoleErrorCode#ROLE_004} を投げない。
     * 退会済ユーザーの 30 日後物理削除（{@code AccountPurgeService#purgeUser}）の経路で
     * {@code RolePurgeEventListener} から呼び出される安全弁メソッド。</p>
     *
     * <p>呼び出し後は対象スコープが ADMIN 不在状態になる可能性があるため、
     * 別途運用通知バッチで検出し、SYSTEM_ADMIN または夜次承継バッチで是正する設計
     * （設計書: {@code docs/architecture/account_purge_last_admin_succession.md} §4.1 / §6 Phase α-1）。</p>
     *
     * <p>通常版 {@code removeMember} と同様に {@link MembershipChangedEvent#REMOVED} を発火する。</p>
     *
     * @param scopeId      スコープID（チームID or 組織ID）
     * @param scopeType    スコープ種別（{@code TEAM} or {@code ORGANIZATION}）
     * @param targetUserId 除名対象のユーザーID
     * @throws BusinessException 対象ユーザーが当該スコープに所属していない場合（{@link RoleErrorCode#ROLE_001}）
     */
    @Transactional
    @CacheEvict(value = "role-permissions", key = "#targetUserId + ':' + #scopeType + ':' + #scopeId")
    public void removeMemberWithoutAdminCheck(Long scopeId, String scopeType, Long targetUserId) {
        UserRoleEntity current = findUserRole(targetUserId, scopeId, scopeType)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // checkLastAdmin はあえて呼ばない（安全弁メソッドの本質）

        userRoleRepository.delete(current);
        log.warn("メンバー除名完了（ADMIN保護バイパス）: scopeType={}, scopeId={}, userId={}",
                scopeType, scopeId, targetUserId);

        // F02.2.1: メンバーシップ変更イベントを発火（ダッシュボードキャッシュ無効化用）
        eventPublisher.publishEvent(new MembershipChangedEvent(
                targetUserId, scopeType, scopeId, MembershipChangedEvent.ChangeType.REMOVED));
    }

    /**
     * ユーザーが自主退会する。最後のADMIN保護チェック付き。
     */
    @Transactional
    @CacheEvict(value = "role-permissions", key = "#userId + ':' + #scopeType + ':' + #scopeId")
    public void leaveScope(Long userId, Long scopeId, String scopeType) {
        UserRoleEntity current = findUserRole(userId, scopeId, scopeType)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // 最後のADMIN保護
        checkLastAdmin(scopeId, scopeType, current);

        userRoleRepository.delete(current);
        log.info("スコープ退会完了: scopeType={}, scopeId={}, userId={}", scopeType, scopeId, userId);

        // F02.2.1: メンバーシップ変更イベントを発火（ダッシュボードキャッシュ無効化用）
        eventPublisher.publishEvent(new MembershipChangedEvent(
                userId, scopeType, scopeId, MembershipChangedEvent.ChangeType.REMOVED));
    }

    /**
     * ユーザーの有効権限リストを解決する。
     * ロール由来 + 権限グループ由来の統合リスト。
     *
     * <p>Phase 4-E: Valkey にて 5 分キャッシュ。同一クラス内からの this. 呼び出し（hasPermission 等）は
     * Spring AOP を迂回するためキャッシュが効かない点に注意（hasPermission 自体はキャッシュ対象外）。</p>
     */
    @Cacheable(value = "role-permissions", key = "#userId + ':' + #scopeType + ':' + #scopeId")
    public List<String> resolveEffectivePermissions(Long userId, Long scopeId, String scopeType) {
        // 1. ロール由来の権限（N+1根治: permissionId をバッチ取得）
        List<String> rolePermissions = findUserRole(userId, scopeId, scopeType)
                .map(ur -> {
                    List<Long> permissionIds = rolePermissionRepository.findByRoleId(ur.getRoleId())
                            .stream().map(RolePermissionEntity::getPermissionId).toList();
                    return permissionIds.isEmpty() ? List.<PermissionEntity>of()
                            : permissionRepository.findByIdIn(permissionIds);
                })
                .orElse(List.of())
                .stream()
                .map(PermissionEntity::getName)
                .toList();

        // 2. 権限グループ由来の権限（N+1根治: permissionId をバッチ取得）
        List<PermissionGroupEntity> groups = findPermissionGroups(scopeId, scopeType);
        List<Long> groupIds = groups.stream().map(PermissionGroupEntity::getId).toList();

        List<String> groupPermissions = new ArrayList<>();
        if (!groupIds.isEmpty()) {
            List<UserPermissionGroupEntity> userGroups = userPermissionGroupRepository
                    .findByUserId(userId)
                    .stream()
                    .filter(ug -> groupIds.contains(ug.getGroupId()))
                    .toList();
            for (UserPermissionGroupEntity ug : userGroups) {
                List<Long> pgpPermIds = permissionGroupPermissionRepository.findByGroupId(ug.getGroupId())
                        .stream().map(PermissionGroupPermissionEntity::getPermissionId).toList();
                if (!pgpPermIds.isEmpty()) {
                    permissionRepository.findByIdIn(pgpPermIds)
                            .stream().map(PermissionEntity::getName)
                            .forEach(groupPermissions::add);
                }
            }
        }

        // 3. 統合して重複排除
        return Stream.concat(rolePermissions.stream(), groupPermissions.stream())
                .distinct()
                .toList();
    }

    /**
     * ユーザーが特定の権限を持っているかチェックする。
     */
    public boolean hasPermission(Long userId, Long scopeId, String scopeType, String permissionName) {
        return resolveEffectivePermissions(userId, scopeId, scopeType).contains(permissionName);
    }

    /**
     * オーナー（ADMIN）権限を譲渡する。
     * 現オーナーは MEMBER にダウングレードされ、対象ユーザーが ADMIN に昇格する。
     *
     * <p>2ユーザー分のキャッシュを一括無効化するため allEntries = true を使用する。</p>
     *
     * @param scopeId      スコープID（チームID or 組織ID）
     * @param scopeType    スコープ種別（TEAM or ORGANIZATION）
     * @param currentUserId 現オーナーのユーザーID
     * @param targetUserId  譲渡先ユーザーID
     */
    @Transactional
    @CacheEvict(value = "role-permissions", allEntries = true)
    public void transferOwnership(Long scopeId, String scopeType, Long currentUserId, Long targetUserId) {
        if (currentUserId.equals(targetUserId)) {
            throw new BusinessException(RoleErrorCode.ROLE_001);
        }

        // 現ユーザーが ADMIN であることを確認
        UserRoleEntity currentUserRole = findUserRole(currentUserId, scopeId, scopeType)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));
        RoleEntity currentRole = roleRepository.findById(currentUserRole.getRoleId())
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));
        if (!"ADMIN".equals(currentRole.getName())) {
            throw new BusinessException(RoleErrorCode.ROLE_001);
        }

        // 対象ユーザーがスコープに所属していることを確認
        UserRoleEntity targetUserRole = findUserRole(targetUserId, scopeId, scopeType)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // ADMIN ロールと MEMBER ロールを取得
        RoleEntity adminRole = currentRole;
        RoleEntity memberRole = roleRepository.findByName("MEMBER")
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // 対象ユーザーを ADMIN に昇格
        // delete→save が同一 scope_key を再挿入するため flush で DELETE を先に確定させる
        // （uq_user_roles_user_scope ユニーク制約の衝突回避。詳細は changeRole 参照）。
        userRoleRepository.delete(targetUserRole);
        userRoleRepository.flush();
        UserRoleEntity.UserRoleEntityBuilder newAdminBuilder = UserRoleEntity.builder()
                .userId(targetUserId)
                .roleId(adminRole.getId())
                .grantedBy(currentUserId);
        setScopeField(newAdminBuilder, scopeId, scopeType);
        userRoleRepository.save(newAdminBuilder.build());

        // 現オーナーを MEMBER にダウングレード
        userRoleRepository.delete(currentUserRole);
        userRoleRepository.flush();
        UserRoleEntity.UserRoleEntityBuilder demotedBuilder = UserRoleEntity.builder()
                .userId(currentUserId)
                .roleId(memberRole.getId());
        setScopeField(demotedBuilder, scopeId, scopeType);
        userRoleRepository.save(demotedBuilder.build());

        log.info("オーナー譲渡完了: scopeType={}, scopeId={}, from={}, to={}",
                scopeType, scopeId, currentUserId, targetUserId);

        // F00.5 認可基盤根治（防御補填）: 譲渡の当事者両名は本来既に memberships に在籍済みのはずだが、
        // 移行バックフィル以前の欠落データ対策として双方に冪等 join を補填する。
        // join は既存アクティブ membership があれば何もしない（冪等）ため無害。
        // 既存在籍時 join はイベントを発火しないため、昇格/降格の通知は従来通り下記 CHANGED で担う。
        joinMembershipForRoleGrant(targetUserId, scopeId, scopeType, currentUserId, "OWNERSHIP_TRANSFER");
        joinMembershipForRoleGrant(currentUserId, scopeId, scopeType, currentUserId, "OWNERSHIP_TRANSFER");

        // F02.2.1: メンバーシップ変更イベントを発火（ダッシュボードキャッシュ無効化用）
        // 対象ユーザーは新規 ADMIN 昇格、現オーナーは MEMBER ダウングレード
        eventPublisher.publishEvent(new MembershipChangedEvent(
                targetUserId, scopeType, scopeId, MembershipChangedEvent.ChangeType.CHANGED));
        eventPublisher.publishEvent(new MembershipChangedEvent(
                currentUserId, scopeType, scopeId, MembershipChangedEvent.ChangeType.CHANGED));
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    /**
     * スコープタイプに応じてユーザーロールを検索する。
     */
    private Optional<UserRoleEntity> findUserRole(Long userId, Long scopeId, String scopeType) {
        if ("TEAM".equals(scopeType)) {
            return userRoleRepository.findByUserIdAndTeamId(userId, scopeId);
        }
        return userRoleRepository.findByUserIdAndOrganizationId(userId, scopeId);
    }

    /**
     * スコープタイプに応じてビルダーのフィールドをセットする。
     */
    private void setScopeField(UserRoleEntity.UserRoleEntityBuilder builder, Long scopeId, String scopeType) {
        if ("TEAM".equals(scopeType)) {
            builder.teamId(scopeId);
        } else {
            builder.organizationId(scopeId);
        }
    }

    /**
     * スコープ内のロール数をカウントする。
     */
    private long countByRoleInScope(Long scopeId, String scopeType, Long roleId) {
        if ("TEAM".equals(scopeType)) {
            return userRoleRepository.countByTeamIdAndRoleId(scopeId, roleId);
        }
        return userRoleRepository.countByOrganizationIdAndRoleId(scopeId, roleId);
    }

    /**
     * スコープに応じてパーミッショングループを検索する。
     */
    private List<PermissionGroupEntity> findPermissionGroups(Long scopeId, String scopeType) {
        if ("TEAM".equals(scopeType)) {
            return permissionGroupRepository.findByTeamId(scopeId);
        }
        return permissionGroupRepository.findByOrganizationId(scopeId);
    }

    /**
     * F00.5 認可基盤根治: 権限ロール付与に伴い memberships へ MEMBER として入会させる。
     *
     * <p>権限ロール（ADMIN/DEPUTY_ADMIN/MEMBER）の付与・変更・譲渡では、認可の真実の源である
     * memberships に在籍行が必要となる。本ヘルパーは {@link MembershipService#join} を冪等に呼び出し、
     * 在籍行が無ければ作成、既にあれば何もしない。membership の role_kind は在籍有無のみを表すため
     * 常に {@link RoleKind#MEMBER} とし、権限の細分は user_roles 側が担う。</p>
     */
    private void joinMembershipForRoleGrant(Long userId, Long scopeId, String scopeType,
                                            Long invitedBy, String source) {
        MembershipCreateRequest req = new MembershipCreateRequest();
        req.setUserId(userId);
        req.setScopeType("TEAM".equals(scopeType) ? ScopeType.TEAM : ScopeType.ORGANIZATION);
        req.setScopeId(scopeId);
        req.setRoleKind(RoleKind.MEMBER);
        req.setInvitedBy(invitedBy);
        req.setSource(source);
        membershipService.join(req);
    }

    /**
     * 最後のADMINを除名・変更できないよう保護する。
     */
    private void checkLastAdmin(Long scopeId, String scopeType, UserRoleEntity current) {
        RoleEntity currentRole = roleRepository.findById(current.getRoleId()).orElse(null);
        if (currentRole != null && "ADMIN".equals(currentRole.getName())) {
            long adminCount = countByRoleInScope(scopeId, scopeType, current.getRoleId());
            if (adminCount <= 1) {
                throw new BusinessException(RoleErrorCode.ROLE_004);
            }
        }
    }
}
