package com.mannschaft.app.knowledgebase.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.knowledgebase.KnowledgeBaseErrorCode;
import com.mannschaft.app.knowledgebase.PageAccessLevel;
import com.mannschaft.app.knowledgebase.PageStatus;
import com.mannschaft.app.knowledgebase.entity.KbPageEntity;
import com.mannschaft.app.knowledgebase.entity.KbPagePinEntity;
import com.mannschaft.app.knowledgebase.entity.KbPageRevisionEntity;
import com.mannschaft.app.knowledgebase.entity.KbTemplateEntity;
import com.mannschaft.app.knowledgebase.repository.KbPageFavoriteRepository;
import com.mannschaft.app.knowledgebase.repository.KbPagePinRepository;
import com.mannschaft.app.knowledgebase.repository.KbPageQueryRepository;
import com.mannschaft.app.knowledgebase.repository.KbPageRepository;
import com.mannschaft.app.knowledgebase.repository.KbPageRevisionRepository;
import com.mannschaft.app.knowledgebase.repository.KbTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * ナレッジベースページサービス。
 * ページのCRUD・階層操作・公開/アーカイブを担当する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class KbPageService {

    /** 管理者ロール名 */
    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN", "DEPUTY_ADMIN");

    /** Valkeyビューカウント重複除外TTL（5分） */
    private static final Duration VIEW_DEDUP_TTL = Duration.ofMinutes(5);

    /** Valkeyビューカウントキープレフィックス */
    private static final String VIEW_KEY_PREFIX = "mannschaft:kb:view:";

    /** リビジョン保持上限 */
    private static final int MAX_REVISIONS = 30;

    /** 階層深さ上限 */
    private static final int MAX_DEPTH = 10;

    private final KbPageRepository pageRepository;
    private final KbPageRevisionRepository revisionRepository;
    private final KbPageQueryRepository pageQueryRepository;
    private final KbPagePinRepository pagePinRepository;
    private final KbPageFavoriteRepository pageFavoriteRepository;
    private final KbTemplateRepository templateRepository;
    private final StringRedisTemplate stringRedisTemplate;

    // ========================================
    // Request レコード型
    // ========================================

    public record CreateKbPageRequest(
            String title,
            String slug,
            String body,
            String icon,
            PageAccessLevel accessLevel,
            Long parentId,
            Long templateId
    ) {}

    public record UpdateKbPageRequest(
            String title,
            String body,
            String icon,
            PageAccessLevel accessLevel,
            Long version
    ) {}

    // ========================================
    // 参照系
    // ========================================

    /**
     * ページツリーを取得する。
     * ADMIN_ONLYページは管理者のみ閲覧可能。
     */
    public ApiResponse<List<KbPageEntity>> getPageTree(String scopeType, Long scopeId,
                                                        Long userId, String userRole) {
        List<KbPageEntity> pages = pageRepository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByPathAsc(scopeType, scopeId);

        boolean isAdmin = ADMIN_ROLES.contains(userRole);
        List<KbPageEntity> filtered = pages.stream()
                .filter(p -> p.getAccessLevel() != PageAccessLevel.ADMIN_ONLY || isAdmin)
                .toList();

        return ApiResponse.of(filtered);
    }

    /**
     * ページを取得する。
     * アクセスレベルを確認し、ビューカウントを重複除外で加算する。
     */
    @Transactional
    public ApiResponse<KbPageEntity> getPage(Long pageId, String scopeType, Long scopeId,
                                              Long userId, String userRole) {
        KbPageEntity page = findPageByIdAndScope(pageId, scopeType, scopeId);

        checkAccessLevel(page, userRole);

        // Valkeyで5分間重複除外
        String viewKey = VIEW_KEY_PREFIX + pageId + ":" + userId;
        Boolean isNew = stringRedisTemplate.opsForValue().setIfAbsent(viewKey, "1", VIEW_DEDUP_TTL);
        if (Boolean.TRUE.equals(isNew)) {
            page.incrementViewCount();
            pageRepository.save(page);
        }

        return ApiResponse.of(page);
    }

    // ========================================
    // 更新系
    // ========================================

    /**
     * ページを作成する。
     */
    @Transactional
    public ApiResponse<KbPageEntity> createPage(String scopeType, Long scopeId, Long createdBy,
                                                 CreateKbPageRequest req) {
        // slug一意性チェック
        if (pageQueryRepository.existsBySlugAndScope(req.slug(), scopeType, scopeId, null)) {
            throw new BusinessException(KnowledgeBaseErrorCode.KB_003);
        }

        // 親ページの存在確認と深さ計算
        KbPageEntity parentPage = null;
        int depth = 0;
        if (req.parentId() != null) {
            parentPage = pageRepository.findByIdAndDeletedAtIsNull(req.parentId())
                    .orElseThrow(() -> new BusinessException(KnowledgeBaseErrorCode.KB_001));
            depth = parentPage.getDepth() + 1;
            if (depth > MAX_DEPTH) {
                throw new BusinessException(KnowledgeBaseErrorCode.KB_004);
            }
        }

        // テンプレートからbodyを取得
        String body = req.body();
        if (req.templateId() != null && body == null) {
            KbTemplateEntity template = templateRepository.findById(req.templateId())
                    .orElseThrow(() -> new BusinessException(KnowledgeBaseErrorCode.KB_010));
            body = template.getBody();
        }

        KbPageEntity.KbPageEntityBuilder builder = KbPageEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .parent(parentPage)
                .depth(depth)
                .title(req.title())
                .slug(req.slug())
                .body(body)
                .icon(req.icon())
                .accessLevel(req.accessLevel() != null ? req.accessLevel() : PageAccessLevel.ALL_MEMBERS)
                .status(PageStatus.DRAFT)
                .createdBy(createdBy)
                .path("/0"); // 仮のpath、save後に更新

        KbPageEntity saved = pageRepository.save(builder.build());

        // path設定: save後にIDが確定するので更新
        String path;
        if (parentPage == null) {
            path = "/" + saved.getId();
        } else {
            path = parentPage.getPath() + "/" + saved.getId();
        }

        KbPageEntity withPath = saved.toBuilder().path(path).build();
        KbPageEntity result = pageRepository.save(withPath);

        log.info("KBページを作成しました: id={}, slug={}, scope={}/{}", result.getId(), req.slug(), scopeType, scopeId);
        return ApiResponse.of(result);
    }

    /**
     * ページを更新する。
     * PUBLISHEDの場合はリビジョン保存、版数30超過時は最古版を削除する。
     */
    @Transactional
    public ApiResponse<KbPageEntity> updatePage(Long pageId, String scopeType, Long scopeId,
                                                 Long userId, String userRole,
                                                 UpdateKbPageRequest req) {
        KbPageEntity page = findPageByIdAndScope(pageId, scopeType, scopeId);

        // 楽観的ロック確認
        if (!page.getVersion().equals(req.version())) {
            throw new BusinessException(KnowledgeBaseErrorCode.KB_006);
        }

        // PUBLISHEDの場合、現在内容をリビジョンとして保存
        if (page.getStatus() == PageStatus.PUBLISHED) {
            saveRevision(page, userId);
        }

        KbPageEntity updated = page.toBuilder()
                .title(req.title() != null ? req.title() : page.getTitle())
                .body(req.body() != null ? req.body() : page.getBody())
                .icon(req.icon() != null ? req.icon() : page.getIcon())
                .accessLevel(req.accessLevel() != null ? req.accessLevel() : page.getAccessLevel())
                .lastEditedBy(userId)
                .build();

        KbPageEntity saved = pageRepository.save(updated);
        log.info("KBページを更新しました: id={}", pageId);
        return ApiResponse.of(saved);
    }

    /**
     * ページを論理削除する。子孫も一括削除し、ピン留めを物理削除する。
     */
    @Transactional
    public void deletePage(Long pageId, String scopeType, Long scopeId) {
        KbPageEntity page = findPageByIdAndScope(pageId, scopeType, scopeId);

        // 子孫IDを取得（自身のpathをプレフィックスとして使用）
        List<Long> descendantIds = pageQueryRepository.findIdsByPathPrefixAndScope(
                page.getPath() + "/", scopeType, scopeId);

        // 自身を論理削除
        page.softDelete();
        pageRepository.save(page);

        // 子孫を論理削除
        if (!descendantIds.isEmpty()) {
            List<KbPageEntity> descendants = pageRepository.findAllById(descendantIds);
            descendants.forEach(KbPageEntity::softDelete);
            pageRepository.saveAll(descendants);
        }

        // 対象 + 子孫のピン留めを物理削除
        descendantIds.add(pageId);
        for (Long id : descendantIds) {
            pagePinRepository.findByScopeTypeAndScopeIdOrderBySortOrderAsc(scopeType, scopeId)
                    .stream()
                    .filter(pin -> pin.getKbPageId().equals(id))
                    .forEach(pagePinRepository::delete);
        }

        log.info("KBページを論理削除しました: id={}, 子孫件数={}", pageId, descendantIds.size() - 1);
    }

    /**
     * ページを移動する。循環参照・深さ上限を確認し、path/depthを一括更新する。
     */
    @Transactional
    public void movePage(Long pageId, Long newParentId, String scopeType, Long scopeId) {
        KbPageEntity page = findPageByIdAndScope(pageId, scopeType, scopeId);

        KbPageEntity newParent = null;
        if (newParentId != null) {
            newParent = pageRepository.findByIdAndDeletedAtIsNull(newParentId)
                    .orElseThrow(() -> new BusinessException(KnowledgeBaseErrorCode.KB_001));

            // 循環参照チェック: 新親のpathが自身のpathで始まっていないか確認
            if (newParent.getPath().startsWith(page.getPath() + "/")
                    || newParent.getPath().equals(page.getPath())) {
                throw new BusinessException(KnowledgeBaseErrorCode.KB_005);
            }
        }

        // サブツリーの最大深さを計算
        List<Long> subtreeIds = pageQueryRepository.findIdsByPathPrefixAndScope(
                page.getPath() + "/", scopeType, scopeId);
        int subtreeMaxDepth = page.getDepth(); // 自身の深さを起点
        if (!subtreeIds.isEmpty()) {
            List<KbPageEntity> subtreePages = pageRepository.findAllById(subtreeIds);
            subtreeMaxDepth = subtreePages.stream()
                    .mapToInt(KbPageEntity::getDepth)
                    .max()
                    .orElse(page.getDepth());
        }

        int newParentDepth = newParent != null ? newParent.getDepth() : -1;
        int newPageDepth = newParentDepth + 1;
        int depthDiff = subtreeMaxDepth - page.getDepth();
        int maxNewDepth = newPageDepth + depthDiff;

        if (maxNewDepth > MAX_DEPTH) {
            throw new BusinessException(KnowledgeBaseErrorCode.KB_004);
        }

        // 新しいpath prefix設定
        String oldPathPrefix = page.getPath();
        String newPath = newParent != null ? newParent.getPath() + "/" + pageId : "/" + pageId;
        int newDepth = newPageDepth;

        // 自身のpath/depth更新
        KbPageEntity movedPage = page.toBuilder()
                .parent(newParent)
                .path(newPath)
                .depth(newDepth)
                .build();
        pageRepository.save(movedPage);

        // 子孫のpath/depth再計算と一括UPDATE
        if (!subtreeIds.isEmpty()) {
            List<KbPageEntity> descendants = pageRepository.findAllById(subtreeIds);
            List<KbPageEntity> updated = descendants.stream()
                    .map(d -> {
                        String newChildPath = newPath + d.getPath().substring(oldPathPrefix.length());
                        int newChildDepth = newDepth + (d.getDepth() - page.getDepth());
                        return d.toBuilder()
                                .path(newChildPath)
                                .depth(newChildDepth)
                                .build();
                    })
                    .toList();
            pageRepository.saveAll(updated);
        }

        log.info("KBページを移動しました: id={}, newParentId={}", pageId, newParentId);
    }

    /**
     * ページを公開する。DRAFT or ARCHIVEDからPUBLISHEDに変更する。
     */
    @Transactional
    public ApiResponse<KbPageEntity> publishPage(Long pageId, String scopeType, Long scopeId) {
        KbPageEntity page = findPageByIdAndScope(pageId, scopeType, scopeId);

        if (page.getStatus() == PageStatus.PUBLISHED) {
            return ApiResponse.of(page);
        }

        KbPageEntity published = page.toBuilder()
                .status(PageStatus.PUBLISHED)
                .build();
        KbPageEntity saved = pageRepository.save(published);

        log.info("KBページを公開しました: id={}", pageId);
        return ApiResponse.of(saved);
    }

    /**
     * ページをアーカイブする。PUBLISHEDからARCHIVEDに変更する。
     */
    @Transactional
    public ApiResponse<KbPageEntity> archivePage(Long pageId, String scopeType, Long scopeId) {
        KbPageEntity page = findPageByIdAndScope(pageId, scopeType, scopeId);

        if (page.getStatus() != PageStatus.PUBLISHED) {
            return ApiResponse.of(page);
        }

        KbPageEntity archived = page.toBuilder()
                .status(PageStatus.ARCHIVED)
                .build();
        KbPageEntity saved = pageRepository.save(archived);

        log.info("KBページをアーカイブしました: id={}", pageId);
        return ApiResponse.of(saved);
    }

    // ========================================
    // ヘルパー
    // ========================================

    /**
     * IDとスコープでページを取得する。見つからない場合は例外をスローする。
     */
    KbPageEntity findPageByIdAndScope(Long pageId, String scopeType, Long scopeId) {
        KbPageEntity page = pageRepository.findByIdAndDeletedAtIsNull(pageId)
                .orElseThrow(() -> new BusinessException(KnowledgeBaseErrorCode.KB_001));

        if (!page.getScopeType().equals(scopeType) || !page.getScopeId().equals(scopeId)) {
            throw new BusinessException(KnowledgeBaseErrorCode.KB_001);
        }
        return page;
    }

    /**
     * アクセスレベルを確認する。
     */
    private void checkAccessLevel(KbPageEntity page, String userRole) {
        if (page.getAccessLevel() == PageAccessLevel.ADMIN_ONLY
                && !ADMIN_ROLES.contains(userRole)) {
            throw new BusinessException(KnowledgeBaseErrorCode.KB_002);
        }
    }

    /**
     * 現在ページの内容をリビジョンとして保存する。版数が30超過した場合は最古版を削除する。
     */
    private void saveRevision(KbPageEntity page, Long editorId) {
        int maxRevisionNumber = revisionRepository.countByKbPageId(page.getId());

        KbPageRevisionEntity revision = KbPageRevisionEntity.builder()
                .kbPageId(page.getId())
                .revisionNumber(maxRevisionNumber + 1)
                .title(page.getTitle())
                .body(page.getBody())
                .editorId(editorId)
                .build();
        revisionRepository.save(revision);

        // 版数 > 30 の場合、最古版を削除
        if (maxRevisionNumber + 1 > MAX_REVISIONS) {
            revisionRepository.findFirstByKbPageIdOrderByRevisionNumberAsc(page.getId())
                    .ifPresent(revisionRepository::delete);
        }
    }
}
