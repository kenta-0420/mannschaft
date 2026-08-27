package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.MembershipBanRequest;
import com.mannschaft.app.village.dto.MembershipJoinRequest;
import com.mannschaft.app.village.dto.MembershipListResponse;
import com.mannschaft.app.village.dto.MembershipResponse;
import com.mannschaft.app.village.dto.RoleChangeRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F17.1 Phase 1 B3 — 村メンバーシップ管理 Service。
 *
 * <p>担当範囲（出陣指示書 §B3）:</p>
 * <ul>
 *   <li>参加（USER / TEAM / ORGANIZATION）— FREE 村のみ即時参加、APPROVAL 村は拒否</li>
 *   <li>退出（HEADMAN 引き継ぎ込み）</li>
 *   <li>メンバー一覧（ページネーション）</li>
 *   <li>ロール変更（HEADMAN のみ）</li>
 *   <li>BAN（HEADMAN のみ）</li>
 * </ul>
 *
 * <p>アーキテクチャ原則:</p>
 * <ul>
 *   <li>原則1: TeamMember/OrganizationMember 相当の {@link UserRoleRepository} は Read-only で呼ぶ。
 *       subject_id に FK は張らない（B1 で対応済み）。</li>
 *   <li>原則5: {@code @Transactional} は village ドメインに閉じる。
 *       UserRoleRepository は読取のみで呼び、書き込みは行わない。</li>
 * </ul>
 *
 * <p>HEADMAN 引き継ぎ仕様:</p>
 * <ul>
 *   <li>HEADMAN 退出時は、村内 ELDER 最古参を自動昇格。</li>
 *   <li>ELDER がいなければ VILLAGER 最古参を昇格。</li>
 *   <li>誰もいなければ memberships のみ更新し、村の状態（{@code archived}）は B11 バッチが対応する。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageMembershipService {

    /** 1 主体がアクティブメンバーとして所属できる村数のハードリミット。 */
    static final int PARTICIPATION_HARD_LIMIT = 100;

    /** ソフト警告閾値（30 村超過でレスポンスに warn フラグを立てる）。 */
    static final int PARTICIPATION_SOFT_WARN_THRESHOLD = 30;

    private final VillageMembershipRepository membershipRepository;
    /** Read-only: チーム/組織の ADMIN 権限検証用（原則1 FK 不在）。 */
    private final UserRoleRepository userRoleRepository;
    private final VillageAccessGate accessGate;

    // ========================================================================
    // 4.3.1 参加
    // ========================================================================

    /**
     * 村に参加する。FREE 村は即時参加、APPROVAL 村は拒否（B6 の join-request 経由）。
     *
     * @param villageId  対象村
     * @param actorUserId リクエストユーザー
     * @param request    参加リクエスト
     * @return 作成された（または再参加で再生成された）メンバーシップ
     */
    @Transactional
    public MembershipResponse join(UUID villageId, Long actorUserId, MembershipJoinRequest request) {
        VillageEntity village = loadActiveVillage(villageId, actorUserId);

        if (village.getJoinPolicy() != VillageJoinPolicy.FREE) {
            throw new BusinessException(VillageErrorCode.VILLAGE_JOIN_REQUIRES_APPROVAL);
        }

        // 主体検証（IDOR / 代表権限）
        validateSubjectAuthorization(actorUserId, request.subjectType(), request.subjectId());

        // 既存の現役メンバーシップを検出
        Optional<VillageMembershipEntity> existing = membershipRepository
                .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                        villageId, request.subjectType(), request.subjectId());
        if (existing.isPresent()) {
            VillageMembershipEntity m = existing.get();
            if (m.getBannedAt() != null) {
                throw new BusinessException(VillageErrorCode.MEMBER_BANNED);
            }
            throw new BusinessException(VillageErrorCode.ALREADY_MEMBER);
        }

        // 参加上限（USER のみ。TEAM/ORG は別管理）
        if (request.subjectType() == VillageSubjectType.USER) {
            int activeCount = membershipRepository
                    .findBySubjectTypeAndSubjectIdAndLeftAtIsNull(VillageSubjectType.USER, request.subjectId())
                    .size();
            if (activeCount >= PARTICIPATION_HARD_LIMIT) {
                throw new BusinessException(VillageErrorCode.PARTICIPATION_LIMIT_EXCEEDED);
            }
        }

        // 退村中レコードがあれば履歴として残し、新規行で再参加（B1 設計の UNIQUE に準拠）
        VillageMembershipEntity created = membershipRepository.save(
                VillageMembershipEntity.builder()
                        .villageId(villageId)
                        .subjectType(request.subjectType())
                        .subjectId(request.subjectId())
                        .role(VillageRole.VILLAGER)
                        .joinedAt(LocalDateTime.now())
                        .build()
        );
        log.info("Village joined: villageId={} subjectType={} subjectId={} membershipId={}",
                villageId, request.subjectType(), request.subjectId(), created.getId());

        boolean warn = request.subjectType() == VillageSubjectType.USER
                && (membershipRepository
                        .findBySubjectTypeAndSubjectIdAndLeftAtIsNull(VillageSubjectType.USER, request.subjectId())
                        .size() > PARTICIPATION_SOFT_WARN_THRESHOLD);
        return MembershipResponse.ofJoined(created, resolveDisplayName(created), warn);
    }

    // ========================================================================
    // 4.3.2 退出
    // ========================================================================

    /**
     * 自分の村メンバーシップから退出する。
     * HEADMAN が退出する場合は村内 ELDER → VILLAGER 最古参を自動昇格する。
     */
    @Transactional
    public void leave(UUID villageId, UUID membershipId, Long actorUserId) {
        VillageEntity village = loadActiveVillage(villageId, actorUserId);

        VillageMembershipEntity membership = membershipRepository.findById(membershipId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.NOT_MEMBER));

        if (!membership.getVillageId().equals(villageId)) {
            // IDOR 防止: パスとボディの villageId 不一致は不存在扱い
            throw new BusinessException(VillageErrorCode.NOT_MEMBER);
        }
        if (membership.getLeftAt() != null) {
            throw new BusinessException(VillageErrorCode.NOT_MEMBER);
        }

        // 退出は本人（USER）または当該チーム/組織 ADMIN（TEAM/ORG）のみ
        validateSubjectAuthorization(actorUserId, membership.getSubjectType(), membership.getSubjectId());

        // HEADMAN 引き継ぎ
        if (membership.getRole() == VillageRole.HEADMAN) {
            promoteSuccessorOrSignalAbandoned(village, membership);
        }

        membership.setLeftAt(LocalDateTime.now());
        membershipRepository.save(membership);
        log.info("Village left: villageId={} membershipId={} actorUserId={}",
                villageId, membershipId, actorUserId);
    }

    /**
     * HEADMAN 引き継ぎロジック。設計書 §5.5 準拠。
     *
     * <ul>
     *   <li>ELDER 最古参 → HEADMAN 昇格</li>
     *   <li>ELDER がいなければ VILLAGER 最古参 → HEADMAN 昇格</li>
     *   <li>誰もいなければ memberships のみ更新（村は B11 バッチで {@code archived} へ）</li>
     * </ul>
     */
    private void promoteSuccessorOrSignalAbandoned(VillageEntity village, VillageMembershipEntity leavingHeadman) {
        UUID villageId = village.getId();

        Optional<VillageMembershipEntity> elder = membershipRepository
                .findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(villageId, VillageRole.ELDER);
        if (elder.isPresent() && !elder.get().getId().equals(leavingHeadman.getId())) {
            VillageMembershipEntity succ = elder.get();
            succ.setRole(VillageRole.HEADMAN);
            membershipRepository.save(succ);
            log.info("Headman succession: villageId={} from membershipId={} to membershipId={} (ELDER)",
                    villageId, leavingHeadman.getId(), succ.getId());
            return;
        }

        Optional<VillageMembershipEntity> villager = membershipRepository
                .findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(villageId, VillageRole.VILLAGER);
        if (villager.isPresent() && !villager.get().getId().equals(leavingHeadman.getId())) {
            VillageMembershipEntity succ = villager.get();
            succ.setRole(VillageRole.HEADMAN);
            membershipRepository.save(succ);
            log.info("Headman succession: villageId={} from membershipId={} to membershipId={} (VILLAGER)",
                    villageId, leavingHeadman.getId(), succ.getId());
            return;
        }

        // 後継者なし。村側 status は B11 バッチが ABANDONED 検出を担当する。
        // ここではログのみ残してメンバーシップを退出させる。
        log.warn("Headman left without successor: villageId={} membershipId={}. " +
                        "Village abandonment will be processed by B11 batch.",
                villageId, leavingHeadman.getId());
    }

    // ========================================================================
    // 4.3.3 メンバー一覧
    // ========================================================================

    /**
     * 村のメンバー一覧を取得する。村人のみ閲覧可。
     */
    @Transactional(readOnly = true)
    public MembershipListResponse listMembers(UUID villageId, Long actorUserId, int page, int size) {
        loadActiveVillage(villageId, actorUserId);
        // 村人判定（IDOR）
        if (!isUserMember(villageId, actorUserId)) {
            throw new BusinessException(VillageErrorCode.NOT_MEMBER);
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<VillageMembershipEntity> p = membershipRepository
                .findByVillageIdAndLeftAtIsNullOrderByJoinedAtAsc(villageId, pageable);

        List<MembershipResponse> content = p.getContent().stream()
                .map(e -> MembershipResponse.of(e, resolveDisplayName(e)))
                .toList();
        return MembershipListResponse.of(content, page, size, p.getTotalElements());
    }

    // ========================================================================
    // 4.3.4 ロール変更
    // ========================================================================

    /**
     * 村内ロールを変更する。HEADMAN のみ実行可。
     *
     * <ul>
     *   <li>HEADMAN→VILLAGER/ELDER の自己降格は、他に HEADMAN/ELDER がいる場合のみ可。</li>
     *   <li>VILLAGER→HEADMAN の昇格時は、既存 HEADMAN を VILLAGER に降格する処理は呼出し元責任
     *       （※本 Phase では一括処理せず、複数 HEADMAN が一時的に共存しうる設計＝設計書 Q15 で許容）。</li>
     * </ul>
     */
    @Transactional
    public MembershipResponse changeRole(UUID villageId,
                                         UUID membershipId,
                                         Long actorUserId,
                                         RoleChangeRequest request) {
        loadActiveVillage(villageId, actorUserId);

        // 実行者が HEADMAN であること
        VillageMembershipEntity actor = membershipRepository
                .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                        villageId, VillageSubjectType.USER, actorUserId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN));
        if (actor.getRole() != VillageRole.HEADMAN) {
            throw new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN);
        }

        VillageMembershipEntity target = membershipRepository.findById(membershipId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.NOT_MEMBER));
        if (!target.getVillageId().equals(villageId) || target.getLeftAt() != null) {
            throw new BusinessException(VillageErrorCode.NOT_MEMBER);
        }

        // 自身を HEADMAN から降格する場合、他に HEADMAN/ELDER が居ることを必須
        if (target.getId().equals(actor.getId()) && request.role() != VillageRole.HEADMAN) {
            long otherHeadman = membershipRepository
                    .countByVillageIdAndRoleAndLeftAtIsNull(villageId, VillageRole.HEADMAN);
            long elders = membershipRepository
                    .countByVillageIdAndRoleAndLeftAtIsNull(villageId, VillageRole.ELDER);
            // 自分以外の HEADMAN または ELDER が居なければ降格不可
            if (otherHeadman <= 1 && elders == 0) {
                throw new BusinessException(VillageErrorCode.HEADMAN_CANNOT_LEAVE);
            }
        }

        target.setRole(request.role());
        VillageMembershipEntity saved = membershipRepository.save(target);
        log.info("Village role changed: villageId={} membershipId={} newRole={} by actorUserId={}",
                villageId, membershipId, request.role(), actorUserId);
        return MembershipResponse.of(saved, resolveDisplayName(saved));
    }

    // ========================================================================
    // 4.3.5 BAN
    // ========================================================================

    /**
     * 村メンバーを BAN する。HEADMAN のみ実行可。
     * 設計書では HEADMAN/ELDER 共に可だが、出陣指示書 B3 § 仕様で「HEADMAN 権限」に絞られているため
     * 本 Phase は HEADMAN のみ。後続 Phase で ELDER 拡張する場合は設計再確認の上で開放する。
     */
    @Transactional
    public MembershipResponse ban(UUID villageId,
                                  UUID membershipId,
                                  Long actorUserId,
                                  MembershipBanRequest request) {
        loadActiveVillage(villageId, actorUserId);

        VillageMembershipEntity actor = membershipRepository
                .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                        villageId, VillageSubjectType.USER, actorUserId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN));
        if (actor.getRole() != VillageRole.HEADMAN) {
            throw new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN);
        }

        VillageMembershipEntity target = membershipRepository.findById(membershipId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.NOT_MEMBER));
        if (!target.getVillageId().equals(villageId) || target.getLeftAt() != null) {
            throw new BusinessException(VillageErrorCode.NOT_MEMBER);
        }
        if (target.getId().equals(actor.getId())) {
            // 自分自身を BAN することは禁止
            throw new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN);
        }

        LocalDateTime now = LocalDateTime.now();
        target.setBannedAt(now);
        target.setBannedReason(request != null ? request.reason() : null);
        target.setLeftAt(now); // BAN は退村扱い
        VillageMembershipEntity saved = membershipRepository.save(target);
        log.info("Village member banned: villageId={} membershipId={} actorUserId={} reason={}",
                villageId, membershipId, actorUserId, target.getBannedReason());
        return MembershipResponse.of(saved, resolveDisplayName(saved));
    }

    // ========================================================================
    // 共通ヘルパ
    // ========================================================================

    /**
     * 稼働中かつ操作者に可視な村を取得する（判定は {@link VillageAccessGate} に一元化）。
     *
     * <p>非公開(UNLISTED)村を非村人が叩いた場合は、実在しない村 ID と<b>同一の</b>
     * {@code VILLAGE_NOT_FOUND} を返して村の存在ごと秘匿する。公開(PUBLIC)村は素通りし、
     * 非村人かどうかの 403 判定は従来どおり本サービスの呼び出し元に残る。
     * 判定順序とその理由は {@link VillageAccessGate#loadActiveVillage} の Javadoc を参照。</p>
     */
    private VillageEntity loadActiveVillage(UUID villageId, Long actorUserId) {
        return accessGate.loadActiveVillage(villageId, actorUserId);
    }

    /**
     * 主体権限の検証（IDOR / 代表権限）。
     *
     * <ul>
     *   <li>USER: subjectId が actorUserId 本人と一致すること（IDOR 対策）</li>
     *   <li>TEAM: actorUserId が当該チームの ADMIN/DEPUTY_ADMIN であること</li>
     *   <li>ORGANIZATION: actorUserId が当該組織の ADMIN/DEPUTY_ADMIN であること</li>
     * </ul>
     */
    void validateSubjectAuthorization(Long actorUserId, VillageSubjectType subjectType, Long subjectId) {
        switch (subjectType) {
            case USER -> {
                if (!actorUserId.equals(subjectId)) {
                    throw new BusinessException(VillageErrorCode.REPRESENT_FORBIDDEN);
                }
            }
            case TEAM -> {
                long count = userRoleRepository.countTeamAdminByUserIdAndTeamId(actorUserId, subjectId);
                if (count == 0) {
                    throw new BusinessException(VillageErrorCode.REPRESENT_FORBIDDEN);
                }
            }
            case ORGANIZATION -> {
                List<Long> admins = userRoleRepository.findAdminUserIdsByOrganizationId(subjectId);
                if (!admins.contains(actorUserId)) {
                    throw new BusinessException(VillageErrorCode.REPRESENT_FORBIDDEN);
                }
            }
            default -> throw new BusinessException(VillageErrorCode.REPRESENT_FORBIDDEN);
        }
    }

    /** 当該ユーザーが対象村の現役 USER 主体メンバーであるか。 */
    private boolean isUserMember(UUID villageId, Long userId) {
        return membershipRepository
                .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                        villageId, VillageSubjectType.USER, userId)
                .filter(m -> m.getBannedAt() == null)
                .isPresent();
    }

    /**
     * 表示名を解決する。
     *
     * <p>Phase 1 B3 ではニックネーム解決は別足軽の責務（B4 ニックネーム機能）のため、
     * USER は {@code null} を返し、TEAM/ORG は {@code "TEAM:#<id>"} / {@code "ORG:#<id>"} の
     * プレースホルダを返す。後続 Phase で本物の解決ロジックに差し替える。</p>
     */
    private String resolveDisplayName(VillageMembershipEntity entity) {
        return switch (entity.getSubjectType()) {
            case USER -> null;
            case TEAM -> "TEAM:#" + entity.getSubjectId();
            case ORGANIZATION -> "ORG:#" + entity.getSubjectId();
        };
    }
}
