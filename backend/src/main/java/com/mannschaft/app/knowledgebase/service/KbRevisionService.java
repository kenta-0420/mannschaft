package com.mannschaft.app.knowledgebase.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.knowledgebase.KnowledgeBaseErrorCode;
import com.mannschaft.app.knowledgebase.PageStatus;
import com.mannschaft.app.knowledgebase.entity.KbPageEntity;
import com.mannschaft.app.knowledgebase.entity.KbPageRevisionEntity;
import com.mannschaft.app.knowledgebase.repository.KbPageRepository;
import com.mannschaft.app.knowledgebase.repository.KbPageRevisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ナレッジベースリビジョンサービス。
 * リビジョン一覧取得・復元を担当する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class KbRevisionService {

    private static final int MAX_REVISIONS = 30;

    private final KbPageRepository pageRepository;
    private final KbPageRevisionRepository revisionRepository;

    /**
     * ページのリビジョン一覧を取得する。
     * ADMIN またはページ作成者のみ取得可能。
     *
     * @param scopeType ページが属するべきスコープ種別（BOLA是正: pageId がこのscope配下か束縛検証する）
     * @param scopeId   スコープID
     */
    public ApiResponse<List<KbPageRevisionEntity>> getRevisions(Long pageId, String scopeType, Long scopeId,
                                                                 Long userId, String userRole) {
        KbPageEntity page = findPage(pageId, scopeType, scopeId);
        checkRevisionAccess(page, userId, userRole);

        List<KbPageRevisionEntity> revisions =
                revisionRepository.findByKbPageIdOrderByRevisionNumberDesc(pageId);

        return ApiResponse.of(revisions);
    }

    /**
     * 指定リビジョンを取得する。
     * ADMIN またはページ作成者のみ取得可能。
     *
     * @param scopeType ページが属するべきスコープ種別（BOLA是正: pageId がこのscope配下か束縛検証する）
     * @param scopeId   スコープID
     */
    public ApiResponse<KbPageRevisionEntity> getRevision(Long pageId, Long revisionId,
                                                          String scopeType, Long scopeId,
                                                          Long userId, String userRole) {
        KbPageEntity page = findPage(pageId, scopeType, scopeId);
        checkRevisionAccess(page, userId, userRole);

        KbPageRevisionEntity revision = revisionRepository.findByIdAndKbPageId(revisionId, pageId)
                .orElseThrow(() -> new BusinessException(KnowledgeBaseErrorCode.KB_007));

        return ApiResponse.of(revision);
    }

    /**
     * リビジョンを復元する。
     * 現在の内容を新リビジョンとして保存してから、指定リビジョンのtitle/bodyで更新する。
     *
     * @param scopeType ページが属するべきスコープ種別（BOLA是正: pageId がこのscope配下か束縛検証する）
     * @param scopeId   スコープID
     */
    @Transactional
    public ApiResponse<KbPageEntity> restoreRevision(Long pageId, Long revisionId,
                                                      String scopeType, Long scopeId,
                                                      Long userId, String userRole) {
        KbPageEntity page = findPage(pageId, scopeType, scopeId);
        checkRevisionAccess(page, userId, userRole);

        KbPageRevisionEntity targetRevision = revisionRepository.findByIdAndKbPageId(revisionId, pageId)
                .orElseThrow(() -> new BusinessException(KnowledgeBaseErrorCode.KB_007));

        // 現在の内容を新リビジョンとして保存
        int currentMaxRevisionNumber = revisionRepository.countByKbPageId(pageId);
        KbPageRevisionEntity currentRevision = KbPageRevisionEntity.builder()
                .kbPageId(pageId)
                .revisionNumber(currentMaxRevisionNumber + 1)
                .title(page.getTitle())
                .body(page.getBody())
                .editorId(userId)
                .changeSummary("復元前の自動保存")
                .build();
        revisionRepository.save(currentRevision);

        // 版数超過チェック
        if (currentMaxRevisionNumber + 1 > MAX_REVISIONS) {
            revisionRepository.findFirstByKbPageIdOrderByRevisionNumberAsc(pageId)
                    .ifPresent(revisionRepository::delete);
        }

        // 指定リビジョンのtitle/bodyで現在ページを更新（versionはJPAが自動インクリメント）
        // managed entity を直接ミューテートして UPDATE する（toBuilder().build() は id を引き継がず INSERT 化するため使用禁止）
        page.applyUpdate(targetRevision.getTitle(), targetRevision.getBody(), null, null, userId);
        KbPageEntity saved = pageRepository.save(page);

        log.info("KBページをリビジョン {} から復元しました: pageId={}", revisionId, pageId);
        return ApiResponse.of(saved);
    }

    // ========================================
    // ヘルパー
    // ========================================

    /**
     * IDとスコープでページを取得する。見つからない、またはscope不一致（BOLA越境）の場合は
     * 同一コード（KB_001）で例外をスローし存在を秘匿する。
     * {@link KbPageService#findPageByIdAndScope} と同じ束縛ロジック。
     */
    private KbPageEntity findPage(Long pageId, String scopeType, Long scopeId) {
        KbPageEntity page = pageRepository.findByIdAndDeletedAtIsNull(pageId)
                .orElseThrow(() -> new BusinessException(KnowledgeBaseErrorCode.KB_001));

        if (!page.getScopeType().equals(scopeType) || !page.getScopeId().equals(scopeId)) {
            throw new BusinessException(KnowledgeBaseErrorCode.KB_001);
        }
        return page;
    }

    private void checkRevisionAccess(KbPageEntity page, Long userId, String userRole) {
        boolean isAdmin = "ADMIN".equals(userRole) || "DEPUTY_ADMIN".equals(userRole);
        boolean isCreator = page.getCreatedBy().equals(userId);
        if (!isAdmin && !isCreator) {
            throw new BusinessException(KnowledgeBaseErrorCode.KB_002);
        }
    }
}
