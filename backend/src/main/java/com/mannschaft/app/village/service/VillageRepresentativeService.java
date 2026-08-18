package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.RepresentativeGrantRequest;
import com.mannschaft.app.village.dto.RepresentativeResponse;
import com.mannschaft.app.village.dto.RepresentativeRevokeRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.VillageRepresentativeEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.repository.VillageRepresentativeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F17 Phase 2 U3 — 村代表委任 Service。
 *
 * <p>担当範囲（設計書 §3.11 / §5.4 / §13.2）:</p>
 * <ul>
 *   <li>HEADMAN/ELDER による代表委任の付与（{@link #grantRepresentative}）</li>
 *   <li>HEADMAN/ELDER による代表委任の取消し（{@link #revokeRepresentative}）</li>
 *   <li>村単位の代表委任一覧取得（{@link #listRepresentatives}）</li>
 *   <li>投稿主体検証で利用する活性判定（{@link #isUserActiveRepresentative}）</li>
 *   <li>PostingIdentity 計算で利用するユーザー横断検索（{@link #findActiveRepresentativesByUser}）</li>
 * </ul>
 *
 * <p>アーキテクチャ原則の遵守:</p>
 * <ul>
 *   <li>原則1: {@code representative_user_id} / {@code granted_by_user_id} は FK 張らず、
 *       {@link UserRoleRepository}・{@link UserRepository} は読取のみで参照する。</li>
 *   <li>原則5: {@code @Transactional} は village ドメイン内 Repository に閉じる。
 *       UserRole/User リポジトリの読取はクロスドメイン読取として許容（書込みなし）。
 *       将来イベント駆動化が必要になれば {@code UserRoleSyncEvent} 等で分離予定。</li>
 * </ul>
 *
 * <p>Phase 1 では「TEAM/ORG の ADMIN は自動的に代表」運用が
 * {@link VillageMembershipService#validateSubjectAuthorization} に実装されている。
 * 本 Service はその経路に加えて、HEADMAN/ELDER が任意のメンバーに代表権を委譲する
 * オーバーライド経路を提供する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageRepresentativeService {

    private final VillageRepresentativeRepository representativeRepository;
    private final VillageMembershipRepository membershipRepository;
    private final VillageRepository villageRepository;
    /** Read-only: チーム/組織メンバー検証（原則1 FK 不在）。 */
    private final UserRoleRepository userRoleRepository;
    /** Read-only: 表示名解決（原則1 FK 不在）。 */
    private final UserRepository userRepository;

    // ========================================================================
    // 代表委任の付与
    // ========================================================================

    /**
     * 村代表委任を付与する。
     *
     * <ul>
     *   <li>実行者が当該村の HEADMAN または ELDER であること（VILLAGE_024）</li>
     *   <li>対象メンバーシップが TEAM または ORGANIZATION 種別であること（VILLAGE_054）</li>
     *   <li>委任先ユーザーが対象チーム/組織のメンバーであること（VILLAGE_055）</li>
     *   <li>委任先ユーザーのアカウントが生存していること（未削除かつ ACTIVE。CMP-050。
     *       状態を漏らさないため非メンバー時と同じ VILLAGE_055 へ畳む）</li>
     *   <li>同一メンバーシップ × 同一ユーザーで現役の委任が既に存在する場合は重複として拒否（VILLAGE_053）</li>
     * </ul>
     *
     * @param villageId        対象村
     * @param request          委任リクエスト
     * @param grantedByUserId  実行者ユーザーID（HEADMAN/ELDER 想定）
     * @return 作成された委任レコードの DTO
     */
    @Transactional
    public RepresentativeResponse grantRepresentative(UUID villageId,
                                                      RepresentativeGrantRequest request,
                                                      Long grantedByUserId) {
        if (grantedByUserId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }

        loadActiveVillage(villageId);

        // 実行者が HEADMAN/ELDER であること
        ensureModerator(villageId, grantedByUserId);

        // 対象メンバーシップを取得
        VillageMembershipEntity targetMembership = membershipRepository.findById(request.membershipId())
                .orElseThrow(() -> new BusinessException(VillageErrorCode.NOT_MEMBER));

        // IDOR: パス villageId とメンバーシップの villageId 不一致は不存在扱い
        if (!targetMembership.getVillageId().equals(villageId)) {
            throw new BusinessException(VillageErrorCode.NOT_MEMBER);
        }
        if (targetMembership.getLeftAt() != null) {
            throw new BusinessException(VillageErrorCode.NOT_MEMBER);
        }

        // TEAM/ORG メンバーシップのみ受理（USER は対象外）
        VillageSubjectType subjectType = targetMembership.getSubjectType();
        if (subjectType != VillageSubjectType.TEAM && subjectType != VillageSubjectType.ORGANIZATION) {
            throw new BusinessException(VillageErrorCode.REPRESENTATIVE_NOT_TEAM_OR_ORG_MEMBERSHIP);
        }

        // 委任先ユーザーが当該チーム/組織のメンバーであること
        // 原則5 補足: UserRoleRepository は read-only。将来チーム/組織サブシステム
        //              分離時は MembershipQuery イベントへ置換予定。
        Long subjectId = targetMembership.getSubjectId();
        Long representativeUserId = request.representativeUserId();
        boolean isSubjectMember = switch (subjectType) {
            case TEAM -> userRoleRepository.existsByUserIdAndTeamId(representativeUserId, subjectId);
            case ORGANIZATION -> userRoleRepository.existsByUserIdAndOrganizationId(representativeUserId, subjectId);
            default -> false; // unreachable
        };
        if (!isSubjectMember) {
            throw new BusinessException(VillageErrorCode.REPRESENTATIVE_USER_NOT_IN_SUBJECT);
        }

        // CMP-050: 委任先のアカウントが生存している（未削除かつ ACTIVE）ことを確認する。
        // 凍結・退会済みのユーザーへ代表権を委ねると、その村のチーム/組織を代表する者が実質不在になる。
        // ErrorCode は他人のアカウント状態を漏らさないよう非メンバー時と同じ VILLAGE_055 へ畳む
        // （凍結なのか退会なのか非メンバーなのかを呼び出し側から区別させない）。
        // 判定に UserEntity.UserStatus を直接読まないのは、village → auth のエンティティ依存が
        // ArchUnit D-1（no cross-domain entity dependency）の新規違反になるためである。
        // 既に users を参照している role 側のプリミティブへ委ねる（deleted_at と status を SQL で見る）。
        if (!userRoleRepository.isActiveUser(representativeUserId)) {
            throw new BusinessException(VillageErrorCode.REPRESENTATIVE_USER_NOT_IN_SUBJECT);
        }

        // 重複 grant 拒否
        boolean alreadyGranted = representativeRepository
                .existsByMembershipIdAndRepresentativeUserIdAndRevokedAtIsNull(
                        request.membershipId(), representativeUserId);
        if (alreadyGranted) {
            throw new BusinessException(VillageErrorCode.REPRESENTATIVE_ALREADY_GRANTED);
        }

        VillageRepresentativeEntity saved = representativeRepository.save(
                VillageRepresentativeEntity.builder()
                        .villageId(villageId)
                        .membershipId(request.membershipId())
                        .representativeUserId(representativeUserId)
                        .grantedByUserId(grantedByUserId)
                        .grantedAt(LocalDateTime.now())
                        .note(request.note())
                        .build()
        );
        log.info("村代表委任を付与: villageId={}, representativeId={}, membershipId={}, representativeUserId={}, grantedBy={}",
                villageId, saved.getId(), request.membershipId(), representativeUserId, grantedByUserId);

        Map<Long, String> displayNames = resolveDisplayNames(
                Set.of(representativeUserId, grantedByUserId));
        return RepresentativeResponse.from(saved,
                displayNames.get(representativeUserId),
                displayNames.get(grantedByUserId));
    }

    // ========================================================================
    // 代表委任の取消し
    // ========================================================================

    /**
     * 代表委任を取消す（論理削除）。
     *
     * <ul>
     *   <li>実行者が当該村の HEADMAN または ELDER であること（VILLAGE_024）</li>
     *   <li>委任レコードが存在し、対象村に属し、未取消であること（不一致は IDOR 対策で 404 統一）</li>
     * </ul>
     */
    @Transactional
    public RepresentativeResponse revokeRepresentative(UUID villageId,
                                                       UUID representativeId,
                                                       RepresentativeRevokeRequest request,
                                                       Long revokedByUserId) {
        if (revokedByUserId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }

        loadActiveVillage(villageId);

        ensureModerator(villageId, revokedByUserId);

        VillageRepresentativeEntity entity = representativeRepository.findById(representativeId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.REPRESENTATIVE_NOT_FOUND));
        if (!entity.getVillageId().equals(villageId)) {
            // IDOR: パス villageId とレコードの villageId 不一致は不存在扱い
            throw new BusinessException(VillageErrorCode.REPRESENTATIVE_NOT_FOUND);
        }
        if (entity.getRevokedAt() != null) {
            // 既に取消し済みは 404 で揃える（IDOR 対策）
            throw new BusinessException(VillageErrorCode.REPRESENTATIVE_NOT_FOUND);
        }

        entity.setRevokedAt(LocalDateTime.now());
        entity.setRevokedByUserId(revokedByUserId);
        if (request != null && request.note() != null && !request.note().isBlank()) {
            // 取消し理由が来た場合のみ note を上書き（grant 時の note を失わないよう
            //  通常は revoke 用 note は空のままで OK）
            entity.setNote(request.note());
        }
        VillageRepresentativeEntity saved = representativeRepository.save(entity);
        log.info("村代表委任を取消し: villageId={}, representativeId={}, revokedBy={}",
                villageId, representativeId, revokedByUserId);

        Map<Long, String> displayNames = resolveDisplayNames(
                Set.of(saved.getRepresentativeUserId(), saved.getGrantedByUserId()));
        return RepresentativeResponse.from(saved,
                displayNames.get(saved.getRepresentativeUserId()),
                displayNames.get(saved.getGrantedByUserId()));
    }

    // ========================================================================
    // 一覧
    // ========================================================================

    /**
     * 村に紐づく代表委任一覧を取得する。村人（現役メンバー）のみ閲覧可。
     *
     * @param villageId       対象村
     * @param includeRevoked  {@code true} なら取消し済も含めて履歴全件を返す。
     *                        {@code false} なら現役のみ。
     * @param actorUserId     閲覧しようとするログイン済ユーザー ID
     * @return DTO リスト（grantedAt 降順は呼出し元責務とせず、リポジトリの自然順をそのまま返す）
     */
    @Transactional(readOnly = true)
    public List<RepresentativeResponse> listRepresentatives(UUID villageId, boolean includeRevoked,
                                                             Long actorUserId) {
        loadActiveVillage(villageId);
        requireVillager(villageId, actorUserId);

        List<VillageRepresentativeEntity> entities = includeRevoked
                ? representativeRepository.findAll().stream()
                        .filter(e -> villageId.equals(e.getVillageId()))
                        .toList()
                : representativeRepository.findByVillageIdAndRevokedAtIsNull(villageId);

        if (entities.isEmpty()) {
            return List.of();
        }

        // 表示名のバルク解決（N+1 防止）
        Set<Long> userIds = new HashSet<>();
        for (VillageRepresentativeEntity e : entities) {
            userIds.add(e.getRepresentativeUserId());
            userIds.add(e.getGrantedByUserId());
        }
        Map<Long, String> displayNames = resolveDisplayNames(userIds);

        return entities.stream()
                .map(e -> RepresentativeResponse.from(e,
                        displayNames.get(e.getRepresentativeUserId()),
                        displayNames.get(e.getGrantedByUserId())))
                .toList();
    }

    // ========================================================================
    // 検証ヘルパ（外部 Service から利用される公開メソッド）
    // ========================================================================

    /**
     * 指定メンバーシップ × ユーザーで現役の代表委任が存在するか判定する。
     *
     * <p>U10 PostingIdentity 投稿主体検証（§5.4 Phase 2 条項）で利用する想定。</p>
     */
    @Transactional(readOnly = true)
    public boolean isUserActiveRepresentative(UUID membershipId, Long userId) {
        if (membershipId == null || userId == null) {
            return false;
        }
        return representativeRepository
                .existsByMembershipIdAndRepresentativeUserIdAndRevokedAtIsNull(membershipId, userId);
    }

    /**
     * 指定ユーザーが現役で受けている代表委任一覧を取得する。
     *
     * <p>U6 PostingIdentity の {@code canPostAs} 集計に利用する想定。</p>
     */
    @Transactional(readOnly = true)
    public List<VillageRepresentativeEntity> findActiveRepresentativesByUser(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return representativeRepository.findByRepresentativeUserIdAndRevokedAtIsNull(userId);
    }

    // ========================================================================
    // 共通ヘルパ
    // ========================================================================

    /** 有効な村を取得する（削除/凍結済みは VILLAGE_001 で扱う）。 */
    private VillageEntity loadActiveVillage(UUID villageId) {
        VillageEntity v = villageRepository.findById(villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));
        if (v.getDeletedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
        if (v.getArchivedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_ALREADY_ARCHIVED);
        }
        return v;
    }

    /**
     * 操作者が当該村の<strong>現役</strong> HEADMAN または ELDER であることを検証する。
     * 不足時は VILLAGE_024 MODERATION_FORBIDDEN を投げる。
     *
     * <p>「現役」の判定（退村済み {@code leftAt} / BAN 済み {@code bannedAt} の除外）は
     * {@code findActiveByVillageIdAndSubject} のクエリに委譲する（#2284 §12）。
     * 以前は BAN を検査しておらず、BAN された長老が代表委任の付与・取消しを実行できた。</p>
     */
    private VillageMembershipEntity ensureModerator(UUID villageId, Long actorUserId) {
        VillageMembershipEntity m = membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, actorUserId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN));
        if (m.getRole() != VillageRole.HEADMAN && m.getRole() != VillageRole.ELDER) {
            throw new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN);
        }
        return m;
    }

    /**
     * 操作者が当該村の<strong>現役</strong>村人（役職不問）であることを検証する。
     * 不足時は {@link VillageErrorCode#NOT_MEMBER}（IDOR 対策で 404 統一・他ドメインの
     * {@code VillageRecruitCategoryService#requireVillager} と同じ粒度・エラーコード）。
     */
    private void requireVillager(UUID villageId, Long actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }
        membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, actorUserId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.NOT_MEMBER));
    }

    /**
     * 複数 userId の表示名をバルクで解決する。
     *
     * <p>退会済み/未登録ユーザーは結果マップに含まれない（null 扱い）。
     * 原則5 補足: クロスドメイン読取のみ。書込みは行わない。</p>
     */
    private Map<Long, String> resolveDisplayNames(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>(userIds.size());
        List<UserEntity> users = userRepository.findAllById(userIds);
        for (UserEntity u : users) {
            result.put(u.getId(), u.getDisplayName());
        }
        return result;
    }
}
