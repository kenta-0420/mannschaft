package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.Priority;
import com.mannschaft.app.bulletin.ReadTrackingMode;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CreateThreadRequest;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.dto.UpdateThreadRequest;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.service.PostingIdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

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
        return page.map(bulletinMapper::toThreadResponse);
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
        return page.map(bulletinMapper::toThreadResponse);
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
        return bulletinMapper.toThreadResponse(entity);
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
        return page.map(bulletinMapper::toThreadResponse);
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
        return bulletinMapper.toThreadResponse(saved);
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
        return bulletinMapper.toThreadResponse(saved);
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
        return bulletinMapper.toThreadResponse(saved);
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
        return bulletinMapper.toThreadResponse(saved);
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
        return bulletinMapper.toThreadResponse(saved);
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
        return page.map(bulletinMapper::toThreadResponse);
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

        return bulletinMapper.toThreadResponse(saved);
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

    /**
     * スレッドエンティティを取得する。存在しない場合は例外をスローする。
     */
    BulletinThreadEntity findThreadOrThrow(ScopeType scopeType, Long scopeId, Long threadId) {
        return threadRepository.findByIdAndScopeTypeAndScopeId(threadId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.THREAD_NOT_FOUND));
    }
}
