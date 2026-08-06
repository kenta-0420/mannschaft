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
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.role.dto.RoleChangeRequest;
import com.mannschaft.app.role.dto.ScopeUserRoleResponse;
import com.mannschaft.app.role.dto.UserRoleOnlyDiffRow;
import com.mannschaft.app.role.event.MembershipChangedEvent;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.team.event.TeamMemberAuditEvent;
import com.mannschaft.app.organization.event.OrganizationMemberAuditEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
     * 自己プロキシ参照（issue #2544）。{@code @Cacheable} は Spring AOP プロキシ経由でのみ作用するため、
     * {@link #hasPermission} から {@link #resolveEffectivePermissions} を呼ぶ際に
     * {@code this.} で呼ぶとプロキシをバイパスし、認可判定の主経路でキャッシュが一切効かない。
     * 循環参照を避けるため {@link Lazy} を付けたフィールド注入とする。
     */
    @Autowired
    @Lazy
    private RoleService self;

    /** 束1 権限昇格根治: 操作者が持つべき権限ロール（user_roles 由来）。 */
    private static final Set<String> ADMIN_ROLE_NAMES = Set.of("ADMIN", "DEPUTY_ADMIN");

    /**
     * 束1 権限昇格根治（Service 層二重防御）: ロール変更・除名の操作者が当該スコープの
     * ADMIN/DEPUTY_ADMIN であることを要求する。違反時は 403（COMMON_002）。
     *
     * <p>本メソッドは {@link com.mannschaft.app.common.AccessControlService} を注入せずに
     * {@code user_roles}＋{@code roles} を直接引く「ローカル私設ヘルパー」である。
     * AccessControlService は RoleService に依存するため、逆に注入すると DI 循環が発生する。
     * ADMIN/DEPUTY_ADMIN は {@code user_roles} 由来の権限ロールであり判定に memberships は不要
     * （所属ロール MEMBER/SUPPORTER では権限昇格・除名を許可しない）。</p>
     *
     * @param scopeId     スコープ ID（チーム ID or 組織 ID）
     * @param scopeType   スコープ種別（{@code TEAM} or {@code ORGANIZATION}）
     * @param actorUserId 操作者ユーザー ID
     * @throws BusinessException 操作者が ADMIN/DEPUTY_ADMIN でない場合（COMMON_002）
     */
    private void requireActorAdmin(Long scopeId, String scopeType, Long actorUserId) {
        boolean isAdmin = findUserRole(actorUserId, scopeId, scopeType)
                .flatMap(ur -> roleRepository.findById(ur.getRoleId()))
                .map(RoleEntity::getName)
                .filter(ADMIN_ROLE_NAMES::contains)
                .isPresent();
        if (!isAdmin) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

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

        var builder = UserRoleEntity.builder()
                .userId(targetUserId)
                .roleId(roleId)
                .grantedBy(grantedBy);
        if ("TEAM".equals(scopeType)) {
            builder.teamId(scopeId);
        } else {
            builder.organizationId(scopeId);
        }
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
     * スコープ内のユーザー（ロール割当）一覧を取得する。
     *
     * <p>認可根治 Wave5: 従来は {@code AdminDashboardController} が {@code UserRoleRepository} を
     * 直叩きし {@code Page<UserRoleEntity>} を生返却していた（Entity をレスポンスに晒さない規約に違反）。
     * ドメイン境界の原則に従い、role ドメインの本メソッドで entity → DTO 変換まで完結させ、
     * admin ドメインが {@code UserRoleEntity} を参照しなくて済むようにする。</p>
     *
     * <p><b>認可は呼び出し元（Controller の public 入口）の責務</b>。
     * {@code AdminDashboardController#getUsers} が
     * {@code accessControlService.checkAdminOrAbove} で scope 認可済みであることを前提とする。</p>
     *
     * @param scopeId   スコープID（チームID または 組織ID）
     * @param scopeType スコープ種別（TEAM/ORGANIZATION）
     * @param pageable  ページネーション情報
     * @return スコープ内のロール割当ページ
     */
    public Page<ScopeUserRoleResponse> getScopeUsers(Long scopeId, String scopeType, Pageable pageable) {
        Page<UserRoleEntity> page = "TEAM".equals(scopeType)
                ? userRoleRepository.findByTeamId(scopeId, pageable)
                : userRoleRepository.findByOrganizationId(scopeId, pageable);
        return page.map(RoleService::toScopeUserRoleResponse);
    }

    /** {@link UserRoleEntity} をスコープ内ユーザー DTO へ変換する。 */
    private static ScopeUserRoleResponse toScopeUserRoleResponse(UserRoleEntity entity) {
        return new ScopeUserRoleResponse(
                entity.getId(),
                entity.getUserId(),
                entity.getRoleId(),
                entity.getTeamId(),
                entity.getOrganizationId(),
                entity.getGrantedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    /**
     * ユーザーのロールを変更する。最後のADMIN保護チェック付き。
     */
    @Transactional
    @CacheEvict(value = "role-permissions", key = "#targetUserId + ':' + #scopeType + ':' + #scopeId")
    public void changeRole(Long scopeId, String scopeType, Long targetUserId,
                           RoleChangeRequest req, Long changedBy) {
        // 束1 権限昇格根治（Service 層二重防御）: 操作者が当該スコープの ADMIN/DEPUTY_ADMIN であることを要求。
        requireActorAdmin(scopeId, scopeType, changedBy);

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
        var builder = UserRoleEntity.builder()
                .userId(targetUserId)
                .roleId(req.getRoleId());
        if ("TEAM".equals(scopeType)) {
            builder.teamId(scopeId);
        } else {
            builder.organizationId(scopeId);
        }
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
    public void removeMember(Long scopeId, String scopeType, Long targetUserId, Long operatorUserId) {
        // 束1 権限昇格根治（Service 層二重防御）: 操作者が当該スコープの ADMIN/DEPUTY_ADMIN であることを要求。
        requireActorAdmin(scopeId, scopeType, operatorUserId);

        UserRoleEntity current = findUserRole(targetUserId, scopeId, scopeType)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // 最後のADMIN保護
        checkLastAdmin(scopeId, scopeType, current);

        userRoleRepository.delete(current);
        userRoleRepository.flush();
        log.info("メンバー除名完了: scopeType={}, scopeId={}, userId={}", scopeType, scopeId, targetUserId);

        // F00.5 認可基盤: memberships の在籍も同時に終了させる（在籍が認可の真実の源）。
        leaveMembershipForRoleRevoke(targetUserId, scopeId, scopeType, LeaveReason.REMOVED, operatorUserId);
    }

    /**
     * メンバーを除名する（最後のADMIN保護チェックをスキップ）。
     *
     * <p><b>⚠️ AccountPurgeService 等の管理者操作専用。通常の API からは呼ばないこと。</b></p>
     *
     * <p>本メソッドは {@link #removeMember(Long, String, Long, Long)} と同等のロジックを実行するが、
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
        userRoleRepository.flush();
        log.warn("メンバー除名完了（ADMIN保護バイパス）: scopeType={}, scopeId={}, userId={}",
                scopeType, scopeId, targetUserId);

        // F00.5 認可基盤: memberships の在籍も同時に終了させる（在籍が認可の真実の源）。
        // 本メソッドは ADMIN 保護をバイパスする安全弁だが、直前の user_roles 削除を flush 済みのため
        // membership 側の最後の ADMIN 保護（user_roles 参照）も同様に発火せず、保護バイパスの性質を保つ。
        leaveMembershipForRoleRevoke(targetUserId, scopeId, scopeType, LeaveReason.REMOVED, null);
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
        userRoleRepository.flush();
        log.info("スコープ退会完了: scopeType={}, scopeId={}, userId={}", scopeType, scopeId, userId);

        // F00.5 認可基盤: memberships の在籍も同時に終了させる（在籍が認可の真実の源）。
        leaveMembershipForRoleRevoke(userId, scopeId, scopeType, LeaveReason.SELF, null);
    }

    /**
     * 指定組織に所属する（user_roles で organization_id が一致する）チーム ID 一覧を返す。
     *
     * <p>他ドメイン（例: advertising の広告非表示ゲート F09.19.2）が {@code role} ドメインの
     * {@code UserRoleRepository} を直接注入することを避けるための Service 経路（D-3 ArchUnit 準拠:
     * {@code @Transactional} クラスは別ドメイン Repository に直接依存しない）。プリミティブ
     * （{@code List<Long>}）のみを返し Entity を漏らさない。</p>
     *
     * @param organizationId 対象組織 ID
     * @return 当該組織配下のチーム ID 一覧
     */
    public List<Long> getTeamIdsByOrganizationId(Long organizationId) {
        return userRoleRepository.findTeamIdsByOrganizationId(organizationId);
    }

    /**
     * ユーザーの有効権限リストを解決する。
     * ロール由来 + 権限グループ由来の統合リスト。
     *
     * <p>Phase 4-E: Valkey にて 5 分キャッシュ。同一クラス内から呼ぶ場合は必ず自己プロキシ
     * {@code self} を経由すること（{@code this.} だと Spring AOP を迂回してキャッシュが効かない。
     * issue #2544 で {@link #hasPermission} を自己プロキシ経由へ是正済み）。</p>     *
     * <p><b>戻り値を呼び出し側で変異させないこと（issue #2544）。</b>
     * 復元可能性のため不変コレクションをやめて可変の実装を返しているが、
     * これは「変更してよい」という意味ではない。test プロファイルの
     * {@code ConcurrentMapCacheManager} はキャッシュ済みの<b>同一インスタンス</b>を返すため、
     * 呼び出し側が {@code add}/{@code remove}/{@code put} するとキャッシュ本体が汚染され、
     * 以降の全呼び出し元が汚染後の値を受け取る（本番の Valkey は毎回デシリアライズするので
     * 症状が出ず、<b>テストと本番で挙動が食い違う</b>厄介な形になる）。
     * 加工が要る場合は必ずコピーしてから行うこと。</p>
     */
    @Cacheable(value = "role-permissions", key = "#userId + ':' + #scopeType + ':' + #scopeId")
    public List<String> resolveEffectivePermissions(Long userId, Long scopeId, String scopeType) {
        // 1. ロール由来の権限（N+1根治: permissionId をバッチ取得）
        List<String> rolePermissions = findUserRole(userId, scopeId, scopeType)
                .map(ur -> {
                    List<Long> permissionIds = rolePermissionRepository.findByRoleId(ur.getRoleId())
                            .stream().map(RolePermissionEntity::getPermissionId)
                            .collect(Collectors.toCollection(ArrayList::new));
                    return permissionIds.isEmpty() ? new ArrayList<PermissionEntity>()
                            : new ArrayList<>(permissionRepository.findByIdIn(permissionIds));
                })
                .orElseGet(ArrayList::new)
                .stream()
                .map(PermissionEntity::getName)
                .collect(Collectors.toCollection(ArrayList::new));

        // 2. 権限グループ由来の権限（N+1根治: permissionId をバッチ取得）
        List<PermissionGroupEntity> groups = findPermissionGroups(scopeId, scopeType);
        List<Long> groupIds = groups.stream().map(PermissionGroupEntity::getId)
                .collect(Collectors.toCollection(ArrayList::new));

        List<String> groupPermissions = new ArrayList<>();
        if (!groupIds.isEmpty()) {
            List<UserPermissionGroupEntity> userGroups = userPermissionGroupRepository
                    .findByUserId(userId)
                    .stream()
                    .filter(ug -> groupIds.contains(ug.getGroupId()))
                    .collect(Collectors.toCollection(ArrayList::new));
            for (UserPermissionGroupEntity ug : userGroups) {
                List<Long> pgpPermIds = permissionGroupPermissionRepository.findByGroupId(ug.getGroupId())
                        .stream().map(PermissionGroupPermissionEntity::getPermissionId)
                        .collect(Collectors.toCollection(ArrayList::new));
                if (!pgpPermIds.isEmpty()) {
                    permissionRepository.findByIdIn(pgpPermIds)
                            .stream().map(PermissionEntity::getName)
                            .forEach(groupPermissions::add);
                }
            }
        }

        // 3. 統合して重複排除
                // issue #2544 B 群: Stream#toList() の実体は java.util.ImmutableCollections$ListN であり、
                // RedisConfig の activateDefaultTyping(EVERYTHING) が埋め込む具象型 ID から復元できない
                // （既定コンストラクタが無い）。復元失敗は fail-open で WARN に握り潰され、
                // 「毎回ミスするだけの効かないキャッシュ」に静かに戻る。可変の ArrayList に集めること。
        return Stream.concat(rolePermissions.stream(), groupPermissions.stream())
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * ユーザーが特定の権限を持っているかチェックする。
     *
     * <p>issue #2544: 旧実装は {@code this.resolveEffectivePermissions(...)} と自己呼び出ししており、
     * Spring AOP プロキシを通らないため {@code role-permissions} キャッシュが
     * <b>認可判定の主経路で一度も発火していなかった</b>（権限チェックのたびに N クエリ）。
     * 自己プロキシ {@link #self} 経由に変更してキャッシュを実際に効かせる。</p>
     *
     * <p>キャッシュキーは {@code userId} / {@code scopeType} / {@code scopeId} を完全に含むため、
     * 別ユーザー・別スコープのエントリへヒットすることはない
     * （キャッシュの内側に認可ゲートを持ち込んでいない＝issue #2496 の「第三の型」に該当しない）。
     * ロール変更・除名・退会時は {@code @CacheEvict} が同一キー書式で失効させる。</p>
     */
    public boolean hasPermission(Long userId, Long scopeId, String scopeType, String permissionName) {
        return self.resolveEffectivePermissions(userId, scopeId, scopeType).contains(permissionName);
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
        var newAdminBuilder = UserRoleEntity.builder()
                .userId(targetUserId)
                .roleId(adminRole.getId())
                .grantedBy(currentUserId);
        if ("TEAM".equals(scopeType)) {
            newAdminBuilder.teamId(scopeId);
        } else {
            newAdminBuilder.organizationId(scopeId);
        }
        userRoleRepository.save(newAdminBuilder.build());

        // 現オーナーを MEMBER にダウングレード
        userRoleRepository.delete(currentUserRole);
        userRoleRepository.flush();
        var demotedBuilder = UserRoleEntity.builder()
                .userId(currentUserId)
                .roleId(memberRole.getId());
        if ("TEAM".equals(scopeType)) {
            demotedBuilder.teamId(scopeId);
        } else {
            demotedBuilder.organizationId(scopeId);
        }
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
     * F00.5 認可基盤: 権限ロール剥奪（除名・退会）に伴い memberships の在籍を終了させる。
     *
     * <p>認可の真実の源は memberships（{@code AccessControlService.isMember} /
     * {@code findAffiliatedScopeIds} はいずれも {@code left_at IS NULL} のみを在籍とみなす）。
     * 除名・退会では {@code user_roles} の削除と同一トランザクション内で {@code left_at} を確定させ、
     * 両系統が常に同時に成立するようにする（片方だけ成功する状態を作らない）。
     * {@link #joinMembershipForRoleGrant} の対称処理であり、退会の実処理は
     * {@link MembershipService#leaveByUserAndScope} に委譲する（本クラスで独自実装しない）。</p>
     *
     * <p>{@code MembershipChangedEvent(REMOVED)} は委譲先が発火するため、本メソッドは
     * <b>アクティブ membership が無く委譲先が何もしなかった場合に限り</b>同イベントを補填発火する。
     * これによりダッシュボードキャッシュ無効化・メンバー数減算の通知は、在籍行の有無に関わらず
     * ちょうど 1 回だけ発火する。</p>
     *
     * <p>本メソッドは role ドメインから membership ドメインの Service を呼ぶ越境呼び出しである
     * （CLAUDE.md 原則 5）。Repository は直接参照せず Service 窓口経由に限定し、
     * 将来のイベント駆動化候補として記録する。</p>
     */
    private void leaveMembershipForRoleRevoke(Long userId, Long scopeId, String scopeType,
                                              LeaveReason leaveReason, Long removedBy) {
        ScopeType scope = "TEAM".equals(scopeType) ? ScopeType.TEAM : ScopeType.ORGANIZATION;
        boolean ended = membershipService.leaveByUserAndScope(userId, scope, scopeId, leaveReason, removedBy);
        if (!ended) {
            // F02.2.1: メンバーシップ変更イベントを発火（ダッシュボードキャッシュ無効化用）。
            eventPublisher.publishEvent(new MembershipChangedEvent(
                    userId, scopeType, scopeId, MembershipChangedEvent.ChangeType.REMOVED));
        }
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

    /**
     * F00.5 フェーズ 3 — {@code user_roles} のうち、対応する {@code memberships} のアクティブ行が
     * 存在しない件数を返す（write-path 移行漏れの再発兆候）。
     *
     * <p>{@link com.mannschaft.app.membership.batch.MembershipConsistencyChecker} が membership
     * ドメインから role ドメインのデータを参照するための Service 窓口。CrossDomainRepositoryDependencyArchTest
     * が禁止する「他ドメインの Repository を直接注入・参照する」ことを避けるため、role ドメイン内部の
     * {@code UserRoleRepository} は本サービスの外へ出さない。</p>
     */
    public long countUserRolesOnlyDiff() {
        return userRoleRepository.countOnlyInUserRoles();
    }

    /**
     * {@link #countUserRolesOnlyDiff()} が検出した差分のサンプルを {@code pageable} の pageSize 件まで返す。
     * role ドメイン内部の Repository 射影（{@code UserRoleRepository.OnlyInUserRolesRow}）はドメイン境界を
     * 越えて公開せず、{@link UserRoleOnlyDiffRow} DTO に詰め替えて返す。
     */
    public List<UserRoleOnlyDiffRow> sampleUserRolesOnlyDiff(Pageable pageable) {
        return userRoleRepository.sampleOnlyInUserRoles(pageable).stream()
                .map(row -> new UserRoleOnlyDiffRow(row.getUserId(), row.getScopeType(), row.getScopeId()))
                .toList();
    }
}
