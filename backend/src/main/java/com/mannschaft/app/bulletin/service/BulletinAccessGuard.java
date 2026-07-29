package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.role.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 掲示板（F05.1）の認可ガード。
 *
 * <p>掲示板の各 Service が共通で使う「所属・ロール・権限」判定をここに集約する。
 * 設計書 F05.1 §2 権限表 / §5 ビジネスロジック / §6 セキュリティに従う。</p>
 *
 * <h2>スコープ対応方針</h2>
 * <p>掲示板の {@link ScopeType} は {@code ORGANIZATION / TEAM / PERSONAL / VILLAGE} の 4 種だが、
 * メンバーシップ・ロール基盤（{@link AccessControlService} / {@link RoleService}）が扱えるのは
 * {@code TEAM / ORGANIZATION} のみである。設計書 §2 の対象レベルも組織・チームに限定される。</p>
 * <ul>
 *   <li>{@code TEAM / ORGANIZATION}: {@link AccessControlService} 経由で所属・ロール・権限を実効認可する。
 *       現状の重大欠陥（他チームのスレッド削除・ロック・カテゴリ削除が誰でも可能）を本ガードで塞ぐ。</li>
 *   <li>{@code PERSONAL}: ユーザー個人スコープ（{@code scope_id = userId}）。ロール基盤の外だが
 *       「本人であること」は本ガードで判定できるため、
 *       {@link #checkPersonalOwner(Long, Long)} で本人性を実効検証する。</li>
 *   <li>{@code VILLAGE}: 村は独自のメンバーシップ基盤（{@code village_memberships}）を持ち、
 *       {@code PostingIdentityService.validatePostingIdentity} /
 *       {@code VillageBulletinAccessService} が村メンバー・可視性を検証する。
 *       本ガードのロール基盤では村メンバーを判定できないため、
 *       <b>呼び出し側が村分岐で村ドメインの検証へ委譲すること</b>を前提とする。</li>
 * </ul>
 *
 * <h2>fail-closed 方針（重要）</h2>
 * <p>{@link #checkMembership(Long, ScopeType, Long)} は<b>全スコープ種別を網羅し、
 * 本ガードで判定できない種別は素通しせず拒否する</b>。以前は「当該スコープ側の検証に委ねる」として
 * TEAM / ORGANIZATION 以外を無条件に素通りさせていたが、委ねた先の検証を呼び出し側が
 * 行っていない経路があると誰も検証しない状態になり得た。委譲は「呼び出し側で分岐済み」を
 * 前提にしてよいが、<b>分岐漏れは素通しではなく拒否として現れる</b>ようにする。</p>
 *
 * <p>あわせて、スコープ付きパス経路（{@code /api/v1/{scopeType}/{scopeId}/bulletin/...}）では
 * {@code BulletinScopeIdResolver} が入口で受理スコープ種別を許可リスト制限しており、
 * 判定手段を持たない種別はそもそも本ガードまで到達しない（二段構え）。</p>

 * <p>他のロール判定メソッド（{@link #isAdmin} / {@link #isAdminOrAbove} / {@link #isSupporter} /
 * {@link #requireManageContent} / {@link #requireCanCreateThread}）は従来どおり
 * TEAM / ORGANIZATION のみを実効判定する。これらはいずれも
 * {@link #checkMembership(Long, ScopeType, Long)} の後に呼ばれる設計であり、
 * PERSONAL では本人であることが先に保証されているため「本人＝自スコープの管理者」として
 * 素通りさせてよい（個人掲示板の所有者が自分の投稿を管理できる従来挙動を維持する）。</p>
 *
 * <p>認可違反は共通 403（{@link CommonErrorCode#COMMON_002}）を流用する（bulletin 専用認可エラーは作らない）。</p>
 */
@Component
@RequiredArgsConstructor
public class BulletinAccessGuard {

    /** コンテンツ管理権限名（カテゴリ CRUD・ピン/ロック/アーカイブの DEPUTY_ADMIN 付与判定に使用）。 */
    public static final String MANAGE_CONTENT = "MANAGE_CONTENT";

    private final AccessControlService accessControlService;
    private final RoleService roleService;

    /**
     * 当該スコープが {@link AccessControlService} のロール/メンバーシップ基盤で
     * 認可判定可能か（= TEAM / ORGANIZATION か）を返す。
     */
    private boolean isRoleManagedScope(ScopeType scopeType) {
        return scopeType == ScopeType.TEAM || scopeType == ScopeType.ORGANIZATION;
    }

    /**
     * 当該スコープへのアクセス資格を検証する。資格が無ければ 403（COMMON_002）。
     *
     * <p><b>全スコープ種別を網羅する（fail-closed）</b>。以前は TEAM / ORGANIZATION 以外を
     * 一律で素通りさせていたため、呼び出し側が当該スコープの検証を忘れると誰も検証しない
     * 状態になり得た。本メソッドは種別ごとに扱いを明示し、想定外の種別は拒否する。</p>
     *
     * <ul>
     *   <li>{@code TEAM / ORGANIZATION}: {@link AccessControlService} でメンバーシップを検証</li>
     *   <li>{@code PERSONAL}: 本人スコープ（{@code scope_id = userId}）ゆえ本人であることを検証。
     *       近隣の {@code BulletinAttachmentService} の PERSONAL 判定と同一の型</li>
     *   <li>{@code VILLAGE}: 村メンバー判定は本ガードのロール基盤の外にあり、村ドメインの
     *       {@code PostingIdentityService#validatePostingIdentity} /
     *       {@code VillageBulletinAccessService} が担う。<b>呼び出し側で村分岐を済ませること</b>を
     *       前提とし、本メソッドに村スコープが到達した場合は「村側の検証が漏れている」とみなして拒否する</li>
     *   <li>上記以外（大会連絡スコープ等）: 本ガードは判定手段を持たないため拒否する。
     *       各呼び出し側が {@code TournamentContactAccessService} 等へ分岐済みであること</li>
     * </ul>
     *
     * @throws BusinessException アクセス資格なし、または本ガードで判定できないスコープ種別
     *                           （{@link CommonErrorCode#COMMON_002}）
     */
    public void checkMembership(Long userId, ScopeType scopeType, Long scopeId) {
        if (scopeType == null) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        switch (scopeType) {
            case TEAM, ORGANIZATION -> accessControlService.checkMembership(userId, scopeId, scopeType.name());
            case PERSONAL -> checkPersonalOwner(userId, scopeId);
            // VILLAGE を含むそれ以外は本ガードでは判定できない。素通しせず拒否する（fail-closed）。
            default -> throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * PERSONAL スコープの本人チェック（{@code scope_id == userId}）。本人以外は 403。
     *
     * <p>個人掲示板はスコープ ID がそのまま所有者の user_id である。近隣の
     * {@code BulletinAttachmentService#checkPersonalOwner} と同一の判定に揃える。</p>
     *
     * @throws BusinessException 本人でない（{@link CommonErrorCode#COMMON_002}）
     */
    public void checkPersonalOwner(Long userId, Long scopeId) {
        if (userId == null || !userId.equals(scopeId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * ADMIN かどうかを返す（VILLAGE / PERSONAL では常に false）。
     */
    public boolean isAdmin(Long userId, ScopeType scopeType, Long scopeId) {
        return isRoleManagedScope(scopeType)
                && accessControlService.isAdmin(userId, scopeId, scopeType.name());
    }

    /**
     * ADMIN または DEPUTY_ADMIN かどうかを返す（VILLAGE / PERSONAL では常に false）。
     */
    public boolean isAdminOrAbove(Long userId, ScopeType scopeType, Long scopeId) {
        return isRoleManagedScope(scopeType)
                && accessControlService.isAdminOrAbove(userId, scopeId, scopeType.name());
    }

    /**
     * SUPPORTER かどうかを返す（VILLAGE / PERSONAL では常に false）。
     */
    public boolean isSupporter(Long userId, ScopeType scopeType, Long scopeId) {
        return isRoleManagedScope(scopeType)
                && accessControlService.isSupporter(userId, scopeId, scopeType.name());
    }

    /**
     * コンテンツ管理操作（カテゴリ CRUD・ピン/ロック/アーカイブ）を要求する。
     *
     * <p>設計書 §2: ADMIN は無条件許可、DEPUTY_ADMIN は {@code MANAGE_CONTENT} 権限保有時のみ許可。
     * {@code AccessControlService.checkAdminOrHasPermission} は ORGANIZATION 専用のため、
     * bulletin は TEAM / ORG 両対応となるよう本メソッドで判定する。</p>
     *
     * @throws BusinessException 権限なし（COMMON_002）
     */
    public void requireManageContent(Long userId, ScopeType scopeType, Long scopeId) {
        if (!isRoleManagedScope(scopeType)) {
            // VILLAGE / PERSONAL はロール基盤外。当該スコープ側に委ねる（ここではブロックしない）。
            return;
        }
        // ADMIN は無条件許可
        if (accessControlService.isAdmin(userId, scopeId, scopeType.name())) {
            return;
        }
        // DEPUTY_ADMIN 等が MANAGE_CONTENT 権限を持つ場合のみ許可
        if (roleService.hasPermission(userId, scopeId, scopeType.name(), MANAGE_CONTENT)) {
            return;
        }
        throw new BusinessException(CommonErrorCode.COMMON_002);
    }

    /**
     * スレッド作成権限を要求する（設計書 §2 / §5）。
     *
     * <p>MEMBER 以上（SUPPORTER 不可）。さらにカテゴリの {@code post_min_role} を満たすこと。</p>
     *
     * @param userId       操作ユーザー
     * @param scopeType    スコープ種別
     * @param scopeId      スコープ ID
     * @param postMinRole  カテゴリに設定された投稿最低ロール（{@code ADMIN/DEPUTY_ADMIN/MEMBER/SUPPORTER}）
     * @throws BusinessException 権限なし（COMMON_002）
     */
    public void requireCanCreateThread(Long userId, ScopeType scopeType, Long scopeId, String postMinRole) {
        if (!isRoleManagedScope(scopeType)) {
            // VILLAGE は PostingIdentityService が村メンバー検証を担う。PERSONAL は本人スコープ。
            return;
        }
        // SUPPORTER はスレッド作成不可（返信のみ）
        if (accessControlService.isSupporter(userId, scopeId, scopeType.name())) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        // カテゴリの post_min_role 充足を検証（例: post_min_role=ADMIN のカテゴリに MEMBER は投稿不可）
        String requiredRole = (postMinRole == null || postMinRole.isBlank()) ? "MEMBER" : postMinRole;
        if (!accessControlService.hasRoleOrAbove(userId, scopeId, scopeType.name(), requiredRole)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * 本人または ADMIN/DEPUTY_ADMIN であることを検証する（スレッド/返信の更新・削除・重要度変更）。
     *
     * <p>VILLAGE / PERSONAL では本人判定のみ行う（ロール基盤外のため、本人以外は 403）。</p>
     *
     * @throws BusinessException 本人でも管理者でもない（COMMON_002）
     */
    public void checkOwnerOrAdmin(Long currentUserId, Long resourceOwnerId, ScopeType scopeType, Long scopeId) {
        if (currentUserId != null && currentUserId.equals(resourceOwnerId)) {
            return; // 本人は許可
        }
        if (!isAdminOrAbove(currentUserId, scopeType, scopeId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }
}
