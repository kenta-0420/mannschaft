package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.Priority;
import com.mannschaft.app.bulletin.ReadTrackingMode;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.TargetType;
import com.mannschaft.app.bulletin.dto.CreateThreadRequest;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.dto.UpdateThreadRequest;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinCategoryRepository;
import com.mannschaft.app.bulletin.repository.BulletinReactionRepository;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.service.TournamentContactAccessService;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.service.PostingIdentityService;
import com.mannschaft.app.village.service.VillageBulletinAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 掲示板スレッドサービス。スレッドのCRUD・検索・状態管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BulletinThreadService {

    /** 安否確認由来スレッドの source_type（手動削除を禁止する。設計書 §6）。 */
    private static final String SOURCE_TYPE_SAFETY_CHECK = "SAFETY_CHECK";

    private final BulletinThreadRepository threadRepository;
    private final BulletinCategoryService categoryService;
    private final BulletinMapper bulletinMapper;
    private final BulletinAccessGuard accessGuard;
    private final AuditLogService auditLogService;
    /** F05.1 保管庫フォルダ: フォルダ存在 + scope 一致検証に利用する。 */
    private final BulletinArchiveFolderService archiveFolderService;
    /** F17.1 Phase 3: scope=VILLAGE 投稿の主体検証。null 安全のため Optional 注入は使わず常時 inject。 */
    private final PostingIdentityService postingIdentityService;
    /** F17.1 村掲示板グローバル方式: 村スコープの閲覧認可（可視性ゲート）を委譲する。 */
    private final VillageBulletinAccessService villageBulletinAccessService;
    /** F08.7.1 連絡機能: 大会/ディビジョンスコープの閲覧・投稿認可を委譲する（クロスドメイン・原則1）。 */
    private final TournamentContactAccessService tournamentContactAccessService;

    // --- enrichment 用依存（一覧/詳細の投稿者名・アバター・カテゴリ・既読・リアクションをバッチ解決）---
    /** 投稿者表示名のバッチ解決（匿名/退会フォールバックは本サービス内で補完）。 */
    private final NameResolverService nameResolverService;
    /** 投稿者アバター URL の一括取得（findAllById）。 */
    private final UserRepository userRepository;
    /** カテゴリ名/色の一括取得（findAllById）。 */
    private final BulletinCategoryRepository categoryRepository;
    /** 既読スレッド ID 集合のバッチ取得。 */
    private final BulletinReadStatusRepository readStatusRepository;
    /** リアクション集計（reactionSummary / myReactions）のバッチ取得。 */
    private final BulletinReactionRepository reactionRepository;

    /** 投稿者解決に失敗した（退会/不明）場合の表示名フォールバック。 */
    private static final String UNKNOWN_USER_DISPLAY_NAME = "不明なユーザー";

    /**
     * スコープのスレッド一覧をページング取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param pageable  ページング情報
     * @return スレッドレスポンスのページ
     */
    public Page<ThreadResponse> listThreads(ScopeType scopeType, Long scopeId, Long userId, Pageable pageable) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        Page<BulletinThreadEntity> page =
                threadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(scopeType, scopeId, pageable);
        return enrichPage(page, userId);
    }

    /**
     * カテゴリ指定でスレッド一覧をページング取得する。所属メンバーのみ。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param categoryId カテゴリID
     * @param userId     操作ユーザーID
     * @param pageable   ページング情報
     * @return スレッドレスポンスのページ
     */
    public Page<ThreadResponse> listThreadsByCategory(ScopeType scopeType, Long scopeId, Long categoryId, Long userId, Pageable pageable) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        Page<BulletinThreadEntity> page =
                threadRepository.findByCategoryIdOrderByIsPinnedDescUpdatedAtDesc(categoryId, pageable);
        return enrichPage(page, userId);
    }

    /**
     * スレッド詳細を取得する。所属メンバーのみ。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @param userId    操作ユーザーID
     * @return スレッドレスポンス
     */
    public ThreadResponse getThread(ScopeType scopeType, Long scopeId, Long threadId, Long userId) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        BulletinThreadEntity entity = findThreadOrThrow(scopeType, scopeId, threadId);
        return enrichSingle(entity, userId);
    }

    /**
     * 村スコープのスレッド一覧をページング取得する（F17.1 村掲示板グローバル方式）。
     *
     * <p>村の {@code bulletin_visibility} に基づく閲覧認可を {@link VillageBulletinAccessService}
     * に委譲し、許可された場合のみ {@code scope_village_id} 一致のスレッドを
     * ピン留め優先→更新日時降順で返す。{@code categoryId} 指定時はカテゴリで絞り込む。</p>
     *
     * @param villageId  村 ID（必須）
     * @param categoryId カテゴリ ID（null = 全件）
     * @param userId     操作ユーザーID
     * @param pageable   ページング情報
     * @return スレッドレスポンスのページ
     */
    public Page<ThreadResponse> listVillageThreads(UUID villageId, Long categoryId, Long userId, Pageable pageable) {
        villageBulletinAccessService.checkVillageBulletinViewAccess(villageId, userId);
        Page<BulletinThreadEntity> page;
        if (categoryId != null) {
            page = threadRepository.findByScopeVillageIdAndCategoryIdOrderByIsPinnedDescUpdatedAtDesc(
                    villageId, categoryId, pageable);
        } else {
            page = threadRepository.findByScopeVillageIdOrderByIsPinnedDescUpdatedAtDesc(villageId, pageable);
        }
        return enrichPage(page, userId);
    }

    /**
     * 村スコープのスレッド詳細を取得する（F17.1 村掲示板グローバル方式）。
     *
     * <p>{@code scope_village_id} 一致で所有確認したうえで（他村のスレッドは 404）、
     * 村の {@code bulletin_visibility} に基づく閲覧認可を行う。</p>
     *
     * @param villageId 村 ID（必須）
     * @param threadId  スレッド ID
     * @param userId    操作ユーザーID
     * @return スレッドレスポンス
     * @throws BusinessException スレッドが当該村に存在しない（{@link BulletinErrorCode#THREAD_NOT_FOUND}・404）
     */
    public ThreadResponse getVillageThread(UUID villageId, Long threadId, Long userId) {
        villageBulletinAccessService.checkVillageBulletinViewAccess(villageId, userId);
        BulletinThreadEntity entity = threadRepository.findByIdAndScopeVillageId(threadId, villageId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.THREAD_NOT_FOUND));
        return enrichSingle(entity, userId);
    }

    /**
     * グローバル方式のスレッド詳細を取得する（F17.1 村掲示板グローバル方式 / 既存スコープ方式の双方を吸収）。
     *
     * <p>FE のグローバル詳細 API（{@code GET /api/v1/bulletin/threads/{threadId}}）は
     * スコープ情報を伴わず {@code threadId} のみで叩かれるため、まず threadId でスレッドを引き、
     * その {@code scopeType} に応じて認可経路を分岐する:</p>
     * <ul>
     *   <li>{@code VILLAGE}: 当該スレッドの {@code scope_village_id} で村可視性認可（PUBLIC/MEMBERS_ONLY）</li>
     *   <li>{@code TEAM/ORGANIZATION/PERSONAL}: 既存の {@link BulletinAccessGuard#checkMembership} で所属認可</li>
     * </ul>
     *
     * @param threadId スレッド ID
     * @param userId   操作ユーザーID
     * @return スレッドレスポンス
     * @throws BusinessException スレッドが存在しない（{@link BulletinErrorCode#THREAD_NOT_FOUND}・404）
     */
    public ThreadResponse getThreadGlobal(Long threadId, Long userId) {
        BulletinThreadEntity entity = threadRepository.findById(threadId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.THREAD_NOT_FOUND));
        if (entity.getScopeType() == ScopeType.VILLAGE) {
            villageBulletinAccessService.checkVillageBulletinViewAccess(entity.getScopeVillageId(), userId);
        } else if (isTournamentScope(entity.getScopeType())) {
            tournamentContactAccessService.checkView(
                    toContactScope(entity.getScopeType()), entity.getScopeId(), ContactSpaceKind.BULLETIN, userId);
        } else {
            accessGuard.checkMembership(userId, entity.getScopeType(), entity.getScopeId());
        }
        return enrichSingle(entity, userId);
    }

    /** bulletin の scope_type が大会/ディビジョン連絡スペースか。 */
    private static boolean isTournamentScope(ScopeType scopeType) {
        return scopeType == ScopeType.TOURNAMENT || scopeType == ScopeType.TOURNAMENT_DIVISION;
    }

    /** bulletin {@link ScopeType} を連絡スペースの {@link ContactSpaceScopeType} に変換する。 */
    private static ContactSpaceScopeType toContactScope(ScopeType scopeType) {
        return scopeType == ScopeType.TOURNAMENT
                ? ContactSpaceScopeType.TOURNAMENT
                : ContactSpaceScopeType.TOURNAMENT_DIVISION;
    }

    /**
     * 全文検索でスレッドを検索する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    操作ユーザーID
     * @param keyword   検索キーワード
     * @param pageable  ページング情報
     * @return スレッドレスポンスのページ
     */
    public Page<ThreadResponse> searchThreads(ScopeType scopeType, Long scopeId, Long userId, String keyword, Pageable pageable) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        Page<BulletinThreadEntity> page =
                threadRepository.searchByKeyword(scopeType.name(), scopeId, keyword, pageable);
        return enrichPage(page, userId);
    }

    /**
     * スレッドを作成する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    作成者ID
     * @param request   作成リクエスト
     * @return 作成されたスレッドレスポンス
     */
    @Transactional
    public ThreadResponse createThread(ScopeType scopeType, Long scopeId, Long userId, CreateThreadRequest request) {
        // F08.7.1: 大会/ディビジョン連絡スペースは連絡認可（canPost）に委譲する。
        // 投稿者＝各チーム代表/副代表 or 主催組織 ADMIN/SYSTEM_ADMIN（§4.2）。
        if (isTournamentScope(scopeType)) {
            tournamentContactAccessService.checkPost(toContactScope(scopeType), scopeId, userId);
            if (request.getCategoryId() != null) {
                categoryService.findCategoryOrThrow(scopeType, scopeId, request.getCategoryId());
            }
            BulletinThreadEntity tournamentThread = BulletinThreadEntity.builder()
                    .categoryId(request.getCategoryId())
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .authorId(userId)
                    .postedAsSubjectType(VillageSubjectType.USER)
                    .title(request.getTitle())
                    .body(request.getBody())
                    .priority(request.getPriority() != null
                            ? Priority.valueOf(request.getPriority()) : Priority.INFO)
                    .readTrackingMode(request.getReadTrackingMode() != null
                            ? ReadTrackingMode.valueOf(request.getReadTrackingMode()) : ReadTrackingMode.COUNT_ONLY)
                    .sourceType(request.getSourceType())
                    .sourceId(request.getSourceId())
                    .build();
            BulletinThreadEntity savedTournament = threadRepository.save(tournamentThread);
            log.info("大会連絡スレッド作成: scopeType={}, scopeId={}, threadId={}",
                    scopeType, scopeId, savedTournament.getId());
            return enrichSingle(savedTournament, userId);
        }

        accessGuard.checkMembership(userId, scopeType, scopeId);

        // カテゴリの存在確認 + post_min_role の取得（未分類=null の場合はデフォルト MEMBER 扱い）
        String postMinRole = null;
        if (request.getCategoryId() != null) {
            BulletinCategoryEntity category =
                    categoryService.findCategoryOrThrow(scopeType, scopeId, request.getCategoryId());
            postMinRole = category.getPostMinRole();
        }

        // スレッド作成権限の検証（SUPPORTER 不可 + カテゴリ post_min_role 充足）
        accessGuard.requireCanCreateThread(userId, scopeType, scopeId, postMinRole);

        Priority priority = request.getPriority() != null
                ? Priority.valueOf(request.getPriority()) : Priority.INFO;
        ReadTrackingMode trackingMode = request.getReadTrackingMode() != null
                ? ReadTrackingMode.valueOf(request.getReadTrackingMode()) : ReadTrackingMode.COUNT_ONLY;

        // F17.1 Phase 3: scope=VILLAGE 投稿の主体検証
        VillageSubjectType postedAsType = VillageSubjectType.USER;
        Long postedAsId = null;
        UUID scopeVillageId = null;
        if (scopeType == ScopeType.VILLAGE) {
            scopeVillageId = request.getScopeVillageId();
            if (scopeVillageId == null) {
                throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
            }
            VillageSubjectType reqType = request.getPostedAsSubjectType();
            Long reqId = request.getPostedAsSubjectId();
            // 個人投稿（postedAs 省略時）は USER + userId として検証
            postedAsType = reqType != null ? reqType : VillageSubjectType.USER;
            postedAsId = postedAsType == VillageSubjectType.USER
                    ? userId
                    : reqId;
            postingIdentityService.validatePostingIdentity(
                    userId, scopeVillageId, postedAsType, postedAsId);
        }

        BulletinThreadEntity entity = BulletinThreadEntity.builder()
                .categoryId(request.getCategoryId())
                .scopeType(scopeType)
                .scopeId(scopeId)
                .scopeVillageId(scopeVillageId)
                .authorId(userId)
                .postedAsSubjectType(postedAsType)
                .postedAsSubjectId(postedAsId)
                .title(request.getTitle())
                .body(request.getBody())
                .priority(priority)
                .readTrackingMode(trackingMode)
                .sourceType(request.getSourceType())
                .sourceId(request.getSourceId())
                .build();

        BulletinThreadEntity saved = threadRepository.save(entity);
        log.info("スレッド作成: scopeType={}, scopeId={}, threadId={}, postedAs={}/{}",
                scopeType, scopeId, saved.getId(), postedAsType, postedAsId);
        return enrichSingle(saved, userId);
    }

    /**
     * スレッドを更新する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @param userId    操作者ID
     * @param request   更新リクエスト
     * @return 更新されたスレッドレスポンス
     */
    @Transactional
    public ThreadResponse updateThread(ScopeType scopeType, Long scopeId, Long threadId, Long userId, UpdateThreadRequest request) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        BulletinThreadEntity entity = findThreadOrThrow(scopeType, scopeId, threadId);

        // 投稿者本人 or ADMIN（現状 NOT_AUTHOR のみで ADMIN が編集できないバグを是正）
        boolean isOwner = entity.getAuthorId() != null && entity.getAuthorId().equals(userId);
        boolean isAdmin = accessGuard.isAdminOrAbove(userId, scopeType, scopeId);
        if (!isOwner && !isAdmin) {
            throw new BusinessException(BulletinErrorCode.NOT_AUTHOR);
        }

        // ロック中の本文編集は ADMIN のみ許可（設計書 §4: 423）
        if (Boolean.TRUE.equals(entity.getIsLocked()) && !isAdmin) {
            throw new BusinessException(BulletinErrorCode.THREAD_LOCKED);
        }

        Priority priority = request.getPriority() != null
                ? Priority.valueOf(request.getPriority()) : entity.getPriority();

        entity.update(request.getTitle(), request.getBody(), priority);
        BulletinThreadEntity saved = threadRepository.save(entity);
        log.info("スレッド更新: threadId={}", threadId);
        return enrichSingle(saved, userId);
    }

    /**
     * スレッドを論理削除する。投稿者本人 or ADMIN（DEPUTY は MANAGE_CONTENT 明示時）。
     *
     * <p>設計書 §6: {@code source_type = 'SAFETY_CHECK'} のスレッドは手動削除不可。
     * 他者のコンテンツを削除した場合は監査ログを記録する（本人削除は記録不要）。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @param userId    操作ユーザーID
     */
    @Transactional
    public void deleteThread(ScopeType scopeType, Long scopeId, Long threadId, Long userId) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        BulletinThreadEntity entity = findThreadOrThrow(scopeType, scopeId, threadId);

        // 安否確認スレッドは手動削除不可（設計書 §6）
        if (SOURCE_TYPE_SAFETY_CHECK.equals(entity.getSourceType())) {
            throw new BusinessException(BulletinErrorCode.SAFETY_THREAD_DELETE_FORBIDDEN);
        }

        // 投稿者本人 or ADMIN/DEPUTY（DEPUTY は MANAGE_CONTENT 明示付与時のみ）
        boolean isOwner = entity.getAuthorId() != null && entity.getAuthorId().equals(userId);
        if (!isOwner) {
            accessGuard.requireManageContent(userId, scopeType, scopeId);
        }

        entity.softDelete();
        threadRepository.save(entity);
        log.info("スレッド削除: threadId={}, by={}", threadId, userId);

        // 他者コンテンツの削除のみ監査ログを記録（本人削除は記録不要）
        if (!isOwner) {
            recordContentDeletionAudit(AuditEventType.BULLETIN_THREAD_DELETED, scopeType, scopeId,
                    userId, entity.getAuthorId(), threadId);
        }
    }

    /**
     * ピン留めを切り替える。ADMIN or DEPUTY(MANAGE_CONTENT) のみ。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @param userId    操作ユーザーID
     * @return 更新されたスレッドレスポンス
     */
    @Transactional
    public ThreadResponse togglePin(ScopeType scopeType, Long scopeId, Long threadId, Long userId) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        accessGuard.requireManageContent(userId, scopeType, scopeId);
        BulletinThreadEntity entity = findThreadOrThrow(scopeType, scopeId, threadId);
        entity.togglePin();
        BulletinThreadEntity saved = threadRepository.save(entity);
        log.info("スレッドピン切替: threadId={}, isPinned={}", threadId, saved.getIsPinned());
        return enrichSingle(saved, userId);
    }

    /**
     * ロックを切り替える。ADMIN or DEPUTY(MANAGE_CONTENT) のみ。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @param userId    操作ユーザーID
     * @return 更新されたスレッドレスポンス
     */
    @Transactional
    public ThreadResponse toggleLock(ScopeType scopeType, Long scopeId, Long threadId, Long userId) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        accessGuard.requireManageContent(userId, scopeType, scopeId);
        BulletinThreadEntity entity = findThreadOrThrow(scopeType, scopeId, threadId);
        entity.toggleLock();
        BulletinThreadEntity saved = threadRepository.save(entity);
        log.info("スレッドロック切替: threadId={}, isLocked={}", threadId, saved.getIsLocked());
        return enrichSingle(saved, userId);
    }

    /**
     * アーカイブ状態を変更する。ADMIN or DEPUTY(MANAGE_CONTENT) のみ（設計書 F05.1 §4）。
     *
     * <p>{@code isArchived=true} でアーカイブ（保管庫へ格納）、{@code false} でアーカイブ解除
     * （一覧へ戻す）を行う双方向操作。認可は従来どおり {@code requireManageContent} で硬化する。</p>
     *
     * <p>保管庫フォルダ振り分け（{@code archiveFolderId}）を任意指定可能（後方互換）。
     * is_archived=true 時に指定するとアーカイブと同時に振り分ける（省略・null = 保管庫直下）。
     * 指定フォルダはスレッドと同一スコープに存在し論理削除されていないこと（不一致は 409 / 不存在は 404）。
     * is_archived=false（解除）時は archiveFolderId を無視し、自動 NULL リセットされる。</p>
     *
     * @param scopeType       スコープ種別
     * @param scopeId         スコープID
     * @param threadId        スレッドID
     * @param userId          操作ユーザーID
     * @param isArchived      設定するアーカイブ状態（true=アーカイブ / false=解除）
     * @param archiveFolderId 振り分け先フォルダ（任意。null = 保管庫直下。is_archived=false 時は無視）
     * @return 更新されたスレッドレスポンス
     */
    @Transactional
    public ThreadResponse archive(ScopeType scopeType, Long scopeId, Long threadId, Long userId,
                                  boolean isArchived, UUID archiveFolderId) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        accessGuard.requireManageContent(userId, scopeType, scopeId);
        BulletinThreadEntity entity = findThreadOrThrow(scopeType, scopeId, threadId);
        if (isArchived) {
            entity.archive();
            if (archiveFolderId != null) {
                // フォルダ存在 + scope 一致を検証（404 / 409）
                archiveFolderService.validateFolderInScope(scopeType, scopeId, archiveFolderId);
                entity.assignArchiveFolder(archiveFolderId);
            } else {
                entity.clearArchiveFolder();
            }
        } else {
            // 解除時は folder を自動 NULL リセット（unarchive 内で実施）
            entity.unarchive();
        }
        BulletinThreadEntity saved = threadRepository.save(entity);
        log.info("スレッドアーカイブ状態変更: threadId={}, isArchived={}, folderId={}",
                threadId, saved.getIsArchived(), saved.getArchiveFolderId());
        return enrichSingle(saved, userId);
    }

    // ========================================================================
    // F17.1 村掲示板グローバル方式 — 書込・モデレーション
    // ========================================================================

    /**
     * グローバル方式でスレッドを作成する（F17.1 村掲示板グローバル方式）。
     *
     * <p>FE のグローバル作成 API（{@code POST /api/v1/bulletin/threads}）は body で
     * {@code scopeType / scopeId / scopeVillageId} を渡す。VILLAGE は村メンバー必須 +
     * 投稿主体検証（{@link PostingIdentityService#validatePostingIdentity}）を、
     * ORG/TEAM/PERSONAL は所属認可を既存 {@link #createThread} がそのまま担う。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID（VILLAGE 時は 0）
     * @param userId    作成者 ID
     * @param request   作成リクエスト（VILLAGE 時は {@code scopeVillageId} 必須）
     * @return 作成されたスレッドレスポンス
     */
    @Transactional
    public ThreadResponse createThreadGlobal(ScopeType scopeType, Long scopeId, Long userId,
                                             CreateThreadRequest request) {
        // 既存 createThread が VILLAGE / ORG / TEAM / PERSONAL の認可・主体検証を内包しているため委譲する。
        return createThread(scopeType, scopeId, userId, request);
    }

    /**
     * グローバル方式でスレッドを更新する（F17.1 村掲示板グローバル方式）。
     *
     * <p>{@code threadId} のみで叩かれるため、スレッドの {@code scopeType} を逆引きして認可経路を分岐する。
     * VILLAGE は投稿者本人 or 村モデレーター（HEADMAN/ELDER/SYSTEM_ADMIN）、
     * ORG/TEAM/PERSONAL は既存 {@link #updateThread} に委譲する（他村スレッドは認可で弾く）。</p>
     *
     * @param threadId スレッド ID
     * @param userId   操作者 ID
     * @param request  更新リクエスト
     * @return 更新されたスレッドレスポンス
     */
    @Transactional
    public ThreadResponse updateThreadGlobal(Long threadId, Long userId, UpdateThreadRequest request) {
        BulletinThreadEntity entity = findThreadByIdOrThrow(threadId);
        if (isTournamentScope(entity.getScopeType())) {
            // 大会連絡: 投稿者本人 or 連絡投稿権限者（canPost = チーム代表/主催者）
            boolean owner = entity.getAuthorId() != null && entity.getAuthorId().equals(userId);
            if (!owner) {
                tournamentContactAccessService.checkPost(
                        toContactScope(entity.getScopeType()), entity.getScopeId(), userId);
            }
            Priority p = request.getPriority() != null
                    ? Priority.valueOf(request.getPriority()) : entity.getPriority();
            entity.update(request.getTitle(), request.getBody(), p);
            BulletinThreadEntity saved = threadRepository.save(entity);
            log.info("大会連絡スレッド更新: threadId={}, scopeType={}", threadId, entity.getScopeType());
            return enrichSingle(saved, userId);
        }
        if (entity.getScopeType() != ScopeType.VILLAGE) {
            return updateThread(entity.getScopeType(), entity.getScopeId(), threadId, userId, request);
        }
        // VILLAGE: 投稿者本人 or 村モデレーター
        boolean isOwner = entity.getAuthorId() != null && entity.getAuthorId().equals(userId);
        if (!isOwner) {
            villageBulletinAccessService.checkVillageBulletinModerator(entity.getScopeVillageId(), userId);
        }
        // ロック中の編集はモデレーターのみ（設計書 §4）
        if (Boolean.TRUE.equals(entity.getIsLocked()) && isOwner) {
            villageBulletinAccessService.checkVillageBulletinModerator(entity.getScopeVillageId(), userId);
        }
        Priority priority = request.getPriority() != null
                ? Priority.valueOf(request.getPriority()) : entity.getPriority();
        entity.update(request.getTitle(), request.getBody(), priority);
        BulletinThreadEntity saved = threadRepository.save(entity);
        log.info("村スレッド更新: threadId={}, villageId={}", threadId, entity.getScopeVillageId());
        return enrichSingle(saved, userId);
    }

    /**
     * グローバル方式でスレッドを論理削除する（F17.1 村掲示板グローバル方式）。
     *
     * <p>VILLAGE は投稿者本人 or 村モデレーター、ORG/TEAM/PERSONAL は既存 {@link #deleteThread} に委譲。
     * 安否確認由来スレッド（{@code source_type=SAFETY_CHECK}）は手動削除不可（設計書 §6）。
     * 他者投稿の削除時は監査ログを記録する。</p>
     *
     * @param threadId スレッド ID
     * @param userId   操作者 ID
     */
    @Transactional
    public void deleteThreadGlobal(Long threadId, Long userId) {
        BulletinThreadEntity entity = findThreadByIdOrThrow(threadId);
        if (isTournamentScope(entity.getScopeType())) {
            // 安否確認スレッドは手動削除不可（設計書 §6）
            if (SOURCE_TYPE_SAFETY_CHECK.equals(entity.getSourceType())) {
                throw new BusinessException(BulletinErrorCode.SAFETY_THREAD_DELETE_FORBIDDEN);
            }
            boolean owner = entity.getAuthorId() != null && entity.getAuthorId().equals(userId);
            if (!owner) {
                tournamentContactAccessService.checkPost(
                        toContactScope(entity.getScopeType()), entity.getScopeId(), userId);
            }
            entity.softDelete();
            threadRepository.save(entity);
            log.info("大会連絡スレッド削除: threadId={}, scopeType={}, by={}",
                    threadId, entity.getScopeType(), userId);
            if (!owner) {
                recordContentDeletionAudit(AuditEventType.BULLETIN_THREAD_DELETED, entity.getScopeType(),
                        entity.getScopeId(), userId, entity.getAuthorId(), threadId);
            }
            return;
        }
        if (entity.getScopeType() != ScopeType.VILLAGE) {
            deleteThread(entity.getScopeType(), entity.getScopeId(), threadId, userId);
            return;
        }
        // 安否確認スレッドは手動削除不可（設計書 §6）
        if (SOURCE_TYPE_SAFETY_CHECK.equals(entity.getSourceType())) {
            throw new BusinessException(BulletinErrorCode.SAFETY_THREAD_DELETE_FORBIDDEN);
        }
        boolean isOwner = entity.getAuthorId() != null && entity.getAuthorId().equals(userId);
        if (!isOwner) {
            villageBulletinAccessService.checkVillageBulletinModerator(entity.getScopeVillageId(), userId);
        }
        entity.softDelete();
        threadRepository.save(entity);
        log.info("村スレッド削除: threadId={}, villageId={}, by={}", threadId, entity.getScopeVillageId(), userId);
        if (!isOwner) {
            recordContentDeletionAudit(AuditEventType.BULLETIN_THREAD_DELETED, entity.getScopeType(),
                    entity.getScopeId(), userId, entity.getAuthorId(), threadId);
        }
    }

    /**
     * グローバル方式でスレッドの優先度を変更する（F17.1 村掲示板グローバル方式）。村モデレーターのみ。
     *
     * <p>VILLAGE は村モデレーター（HEADMAN/ELDER/SYSTEM_ADMIN）、ORG/TEAM/PERSONAL は
     * 既存 {@link BulletinAccessGuard#requireManageContent} による管理権限を要求する。</p>
     *
     * @param threadId スレッド ID
     * @param userId   操作者 ID
     * @param priority 設定する優先度
     * @return 更新されたスレッドレスポンス
     */
    @Transactional
    public ThreadResponse changePriorityGlobal(Long threadId, Long userId, String priority) {
        BulletinThreadEntity entity = findThreadByIdOrThrow(threadId);
        requireModeration(entity, userId);
        Priority p = priority != null ? Priority.valueOf(priority) : entity.getPriority();
        entity.update(entity.getTitle(), entity.getBody(), p);
        BulletinThreadEntity saved = threadRepository.save(entity);
        log.info("スレッド優先度変更: threadId={}, priority={}", threadId, saved.getPriority());
        return enrichSingle(saved, userId);
    }

    /**
     * グローバル方式でピン留め状態を設定する（set 方式・F17.1 村掲示板グローバル方式）。村モデレーターのみ。
     *
     * @param threadId スレッド ID
     * @param userId   操作者 ID
     * @param pinned   設定するピン留め状態
     * @return 更新されたスレッドレスポンス
     */
    @Transactional
    public ThreadResponse setPinGlobal(Long threadId, Long userId, boolean pinned) {
        BulletinThreadEntity entity = findThreadByIdOrThrow(threadId);
        requireModeration(entity, userId);
        entity.setPinned(pinned);
        BulletinThreadEntity saved = threadRepository.save(entity);
        log.info("スレッドピン設定: threadId={}, isPinned={}", threadId, saved.getIsPinned());
        return enrichSingle(saved, userId);
    }

    /**
     * グローバル方式でロック状態を設定する（set 方式・F17.1 村掲示板グローバル方式）。村モデレーターのみ。
     *
     * @param threadId スレッド ID
     * @param userId   操作者 ID
     * @param locked   設定するロック状態
     * @return 更新されたスレッドレスポンス
     */
    @Transactional
    public ThreadResponse setLockGlobal(Long threadId, Long userId, boolean locked) {
        BulletinThreadEntity entity = findThreadByIdOrThrow(threadId);
        requireModeration(entity, userId);
        entity.setLocked(locked);
        BulletinThreadEntity saved = threadRepository.save(entity);
        log.info("スレッドロック設定: threadId={}, isLocked={}", threadId, saved.getIsLocked());
        return enrichSingle(saved, userId);
    }

    /**
     * グローバル方式でアーカイブ状態を変更する（F17.1 村掲示板グローバル方式）。
     *
     * <p>VILLAGE は村モデレーター、ORG/TEAM/PERSONAL は既存 {@link #archive} に委譲する
     * （管理権限要求は既存仕様どおり）。フォルダ振り分けは村スコープでは未対応のため null 固定。</p>
     *
     * @param threadId   スレッド ID
     * @param userId     操作者 ID
     * @param isArchived 設定するアーカイブ状態
     * @return 更新されたスレッドレスポンス
     */
    @Transactional
    public ThreadResponse archiveGlobal(Long threadId, Long userId, boolean isArchived) {
        BulletinThreadEntity entity = findThreadByIdOrThrow(threadId);
        if (isTournamentScope(entity.getScopeType())) {
            tournamentContactAccessService.checkPost(
                    toContactScope(entity.getScopeType()), entity.getScopeId(), userId);
            if (isArchived) {
                entity.archive();
                entity.clearArchiveFolder();
            } else {
                entity.unarchive();
            }
            BulletinThreadEntity saved = threadRepository.save(entity);
            log.info("大会連絡スレッドアーカイブ状態変更: threadId={}, isArchived={}", threadId, saved.getIsArchived());
            return enrichSingle(saved, userId);
        }
        if (entity.getScopeType() != ScopeType.VILLAGE) {
            return archive(entity.getScopeType(), entity.getScopeId(), threadId, userId, isArchived, null);
        }
        villageBulletinAccessService.checkVillageBulletinModerator(entity.getScopeVillageId(), userId);
        if (isArchived) {
            entity.archive();
            entity.clearArchiveFolder();
        } else {
            entity.unarchive();
        }
        BulletinThreadEntity saved = threadRepository.save(entity);
        log.info("村スレッドアーカイブ状態変更: threadId={}, isArchived={}", threadId, saved.getIsArchived());
        return enrichSingle(saved, userId);
    }

    /**
     * モデレーション認可を適用する。VILLAGE は村モデレーター、それ以外は管理権限を要求する。
     */
    private void requireModeration(BulletinThreadEntity entity, Long userId) {
        if (entity.getScopeType() == ScopeType.VILLAGE) {
            villageBulletinAccessService.checkVillageBulletinModerator(entity.getScopeVillageId(), userId);
        } else if (isTournamentScope(entity.getScopeType())) {
            // 大会連絡: モデレーション＝投稿権限者（チーム代表/主催者・§4.2）
            tournamentContactAccessService.checkPost(
                    toContactScope(entity.getScopeType()), entity.getScopeId(), userId);
        } else {
            accessGuard.checkMembership(userId, entity.getScopeType(), entity.getScopeId());
            accessGuard.requireManageContent(userId, entity.getScopeType(), entity.getScopeId());
        }
    }

    /**
     * スレッドを ID のみで取得する（グローバル方式の逆引き）。存在しなければ 404。
     */
    private BulletinThreadEntity findThreadByIdOrThrow(Long threadId) {
        return threadRepository.findById(threadId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.THREAD_NOT_FOUND));
    }

    /**
     * 保管庫内のアーカイブ済みスレッド一覧を取得する（設計書 F05.1 §4 GET .../archive/threads）。
     *
     * <p>閲覧は所属メンバーなら可（MEMBER/SUPPORTER も閲覧可）。</p>
     *
     * <ul>
     *   <li>{@code folderId == null かつ !allFolders}: 保管庫直下（未分類）</li>
     *   <li>{@code allFolders == true}: 全保管庫スレッド（フォルダ問わず is_archived=TRUE 全件）</li>
     *   <li>{@code folderId != null}: 指定フォルダ直下（フォルダ存在 + scope 一致を検証）</li>
     * </ul>
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param userId     操作ユーザーID
     * @param folderId   絞り込みフォルダ（null = 未分類 or 全件）
     * @param allFolders {@code folder_id=all} 指定（全保管庫横断）
     * @param pageable   ページング情報
     * @return アーカイブ済みスレッドのページ
     */
    public Page<ThreadResponse> listArchiveThreads(ScopeType scopeType, Long scopeId, Long userId,
                                                   UUID folderId, boolean allFolders, Pageable pageable) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        Page<BulletinThreadEntity> page;
        if (allFolders) {
            page = threadRepository.findByScopeTypeAndScopeIdAndIsArchivedTrue(scopeType, scopeId, pageable);
        } else if (folderId != null) {
            // フォルダ存在 + scope 一致を検証（404 / 409）
            archiveFolderService.validateFolderInScope(scopeType, scopeId, folderId);
            page = threadRepository.findByScopeTypeAndScopeIdAndIsArchivedTrueAndArchiveFolderId(
                    scopeType, scopeId, folderId, pageable);
        } else {
            page = threadRepository.findByScopeTypeAndScopeIdAndIsArchivedTrueAndArchiveFolderIdIsNull(
                    scopeType, scopeId, pageable);
        }
        return enrichPage(page, userId);
    }

    /**
     * アーカイブ済みスレッドを別の保管庫フォルダへ振り分ける
     * （設計書 F05.1 §4 PATCH .../archive/threads/{threadId}/folder）。ADMIN or DEPUTY(MANAGE_CONTENT) のみ。
     *
     * <p>対象スレッドは is_archived=TRUE であること（未アーカイブは 409）。
     * 移動先フォルダ（null 以外）はスレッドと同一スコープに存在する有効フォルダであること（404 / 409）。</p>
     *
     * @param scopeType       スコープ種別
     * @param scopeId         スコープID
     * @param threadId        スレッドID
     * @param userId          操作ユーザーID
     * @param archiveFolderId 移動先フォルダ（null = 保管庫直下・未分類）
     * @return 更新されたスレッドレスポンス
     */
    @Transactional
    public ThreadResponse moveThreadToFolder(ScopeType scopeType, Long scopeId, Long threadId, Long userId,
                                             UUID archiveFolderId) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        accessGuard.requireManageContent(userId, scopeType, scopeId);
        BulletinThreadEntity entity = findThreadOrThrow(scopeType, scopeId, threadId);

        // 未アーカイブのスレッドはフォルダ振り分け不可（409）
        if (!Boolean.TRUE.equals(entity.getIsArchived())) {
            throw new BusinessException(BulletinErrorCode.THREAD_NOT_ARCHIVED);
        }

        if (archiveFolderId != null) {
            archiveFolderService.validateFolderInScope(scopeType, scopeId, archiveFolderId);
            entity.assignArchiveFolder(archiveFolderId);
        } else {
            entity.clearArchiveFolder();
        }
        BulletinThreadEntity saved = threadRepository.save(entity);
        log.info("スレッドフォルダ振り分け: threadId={}, folderId={}", threadId, archiveFolderId);

        // 振り分け操作の監査ログ（設計書 §6）
        Long teamId = scopeType == ScopeType.TEAM ? scopeId : null;
        Long organizationId = scopeType == ScopeType.ORGANIZATION ? scopeId : null;
        String metadata = String.format(
                "{\"source\":\"BULLETIN\",\"thread_id\":%d,\"archive_folder_id\":%s,\"scope_type\":\"%s\",\"scope_id\":%d}",
                threadId, archiveFolderId == null ? "null" : "\"" + archiveFolderId + "\"",
                scopeType.name(), scopeId);
        auditLogService.record(AuditEventType.BULLETIN_THREAD_ARCHIVE_FOLDER_CHANGED.name(),
                userId, null, teamId, organizationId, null, null, null, metadata);

        return enrichSingle(saved, userId);
    }

    /**
     * 他者コンテンツ削除の監査ログを非同期記録する。
     */
    private void recordContentDeletionAudit(AuditEventType eventType, ScopeType scopeType, Long scopeId,
                                            Long actorUserId, Long ownerUserId, Long resourceId) {
        Long teamId = scopeType == ScopeType.TEAM ? scopeId : null;
        Long organizationId = scopeType == ScopeType.ORGANIZATION ? scopeId : null;
        String metadata = String.format(
                "{\"source\":\"BULLETIN\",\"resource_id\":%d,\"owner_user_id\":%s,\"scope_type\":\"%s\",\"scope_id\":%d}",
                resourceId, ownerUserId, scopeType.name(), scopeId);
        auditLogService.record(eventType.name(), actorUserId, ownerUserId,
                teamId, organizationId, null, null, null, metadata);
    }

    // ========================================================================
    // enrichment（投稿者名/アバター・カテゴリ名/色・既読・リアクション集計のバッチ解決）
    // ========================================================================

    /**
     * スレッド集合の enrichment 5 項目をバッチ解決し、フラット enrich 済みレスポンスを返す（N+1 厳禁）。
     *
     * <p>authorId 集合・categoryId 集合・threadId 集合を作り、各依存を「各 1 バッチクエリ」で解決して
     * {@code toBuilder()} で inline 注入する。スレッド件数に比例した個別クエリは発行しない。</p>
     *
     * <ul>
     *   <li>displayName: {@link NameResolverService#resolveUserDisplayNames(java.util.Collection)}
     *       （未解決 ID は「不明なユーザー」フォールバック）</li>
     *   <li>avatarUrl: {@link UserRepository#findAllById(Iterable)} → Map(userId → avatarUrl)</li>
     *   <li>categoryName/color: {@link BulletinCategoryRepository#findAllById(Iterable)} → Map</li>
     *   <li>isRead: {@link BulletinReadStatusRepository#findReadThreadIds(java.util.Collection, Long)} の Set 含有判定</li>
     *   <li>reactionSummary/myReactions: {@code countByTargetIdsGroupedByEmoji} /
     *       {@code findUserReactionsByTargetIds} の一括集計</li>
     * </ul>
     *
     * @param entities      enrich 対象スレッド（順序維持）
     * @param currentUserId 操作ユーザー ID（既読・myReactions の主体。null 可）
     * @return enrich 済み {@link ThreadResponse} リスト（入力順を維持）
     */
    List<ThreadResponse> enrichThreads(List<BulletinThreadEntity> entities, Long currentUserId) {
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }

        // --- ID 集合を抽出 ---
        Set<Long> authorIds = entities.stream()
                .map(BulletinThreadEntity::getAuthorId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Set<Long> categoryIds = entities.stream()
                .map(BulletinThreadEntity::getCategoryId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Set<Long> threadIds = entities.stream()
                .map(BulletinThreadEntity::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        // --- 投稿者名（1 クエリ）---
        Map<Long, String> displayNames = nameResolverService.resolveUserDisplayNames(authorIds);

        // --- 投稿者アバター（1 クエリ）---
        Map<Long, String> avatarUrls = new HashMap<>();
        if (!authorIds.isEmpty()) {
            for (UserEntity user : userRepository.findAllById(authorIds)) {
                avatarUrls.put(user.getId(), user.getAvatarUrl());
            }
        }

        // --- カテゴリ名/色（1 クエリ）---
        Map<Long, BulletinCategoryEntity> categories = new HashMap<>();
        if (!categoryIds.isEmpty()) {
            for (BulletinCategoryEntity category : categoryRepository.findAllById(categoryIds)) {
                categories.put(category.getId(), category);
            }
        }

        // --- 既読集合（1 クエリ）---
        Set<Long> readThreadIds;
        if (currentUserId != null && !threadIds.isEmpty()) {
            readThreadIds = new HashSet<>(readStatusRepository.findReadThreadIds(threadIds, currentUserId));
        } else {
            readThreadIds = Collections.emptySet();
        }

        // --- リアクション集計（reactionSummary: 1 クエリ / myReactions: 1 クエリ）---
        Map<Long, Map<String, Integer>> reactionSummaries = new HashMap<>();
        Map<Long, List<String>> myReactions = new HashMap<>();
        if (!threadIds.isEmpty()) {
            for (Object[] row : reactionRepository.countByTargetIdsGroupedByEmoji(TargetType.THREAD, threadIds)) {
                Long targetId = (Long) row[0];
                String emoji = (String) row[1];
                int count = ((Number) row[2]).intValue();
                reactionSummaries
                        .computeIfAbsent(targetId, k -> new LinkedHashMap<>())
                        .put(emoji, count);
            }
            if (currentUserId != null) {
                for (Object[] row : reactionRepository.findUserReactionsByTargetIds(
                        TargetType.THREAD, threadIds, currentUserId)) {
                    Long targetId = (Long) row[0];
                    String emoji = (String) row[1];
                    myReactions.computeIfAbsent(targetId, k -> new ArrayList<>()).add(emoji);
                }
            }
        }

        // --- inline 注入（順序維持）---
        List<ThreadResponse> result = new ArrayList<>(entities.size());
        for (BulletinThreadEntity entity : entities) {
            ThreadResponse base = bulletinMapper.toThreadResponse(entity);
            Long authorId = entity.getAuthorId();
            Long categoryId = entity.getCategoryId();
            Long threadId = entity.getId();

            ThreadResponse.AuthorDto author;
            if (authorId != null) {
                String displayName = displayNames.getOrDefault(authorId, UNKNOWN_USER_DISPLAY_NAME);
                author = new ThreadResponse.AuthorDto(authorId, displayName, avatarUrls.get(authorId));
            } else {
                // システム生成スレッド等（authorId=null）: 投稿者なし
                author = new ThreadResponse.AuthorDto(null, null, null);
            }

            BulletinCategoryEntity category = categoryId != null ? categories.get(categoryId) : null;

            result.add(base.toBuilder()
                    .author(author)
                    .categoryName(category != null ? category.getName() : null)
                    .categoryColor(category != null ? category.getColor() : null)
                    .isRead(threadId != null && readThreadIds.contains(threadId))
                    .reactionSummary(reactionSummaries.getOrDefault(threadId, Collections.emptyMap()))
                    .myReactions(myReactions.getOrDefault(threadId, Collections.emptyList()))
                    .build());
        }
        return result;
    }

    /**
     * 単一スレッドを enrich 済みレスポンスに変換する（{@link #enrichThreads} の単件版）。
     *
     * @param entity        スレッドエンティティ
     * @param currentUserId 操作ユーザー ID（null 可）
     * @return enrich 済み {@link ThreadResponse}
     */
    ThreadResponse enrichSingle(BulletinThreadEntity entity, Long currentUserId) {
        return enrichThreads(List.of(entity), currentUserId).get(0);
    }

    /**
     * スレッドページを enrich 済みレスポンスのページに変換する（順序・ページメタ維持）。
     */
    private Page<ThreadResponse> enrichPage(Page<BulletinThreadEntity> page, Long currentUserId) {
        List<ThreadResponse> enriched = enrichThreads(page.getContent(), currentUserId);
        return new org.springframework.data.domain.PageImpl<>(enriched, page.getPageable(), page.getTotalElements());
    }

    /**
     * スレッドエンティティを取得する。存在しない場合は例外をスローする。
     */
    BulletinThreadEntity findThreadOrThrow(ScopeType scopeType, Long scopeId, Long threadId) {
        return threadRepository.findByIdAndScopeTypeAndScopeId(threadId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.THREAD_NOT_FOUND));
    }
}
