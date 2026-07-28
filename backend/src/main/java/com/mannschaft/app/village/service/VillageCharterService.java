package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.CharterArticleCreateRequest;
import com.mannschaft.app.village.dto.CharterArticleOrderUpdateRequest;
import com.mannschaft.app.village.dto.CharterArticleResponse;
import com.mannschaft.app.village.dto.CharterArticleUpdateRequest;
import com.mannschaft.app.village.dto.CharterDrafterCreateRequest;
import com.mannschaft.app.village.dto.CharterDrafterResponse;
import com.mannschaft.app.village.dto.CharterRevisionCreateRequest;
import com.mannschaft.app.village.dto.CharterRevisionResponse;
import com.mannschaft.app.village.dto.VillageCharterResponse;
import com.mannschaft.app.village.entity.VillageCharterArticleEntity;
import com.mannschaft.app.village.entity.VillageCharterDrafterEntity;
import com.mannschaft.app.village.entity.VillageCharterEntity;
import com.mannschaft.app.village.entity.VillageCharterRevisionEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageCharterArticleRepository;
import com.mannschaft.app.village.repository.VillageCharterDrafterRepository;
import com.mannschaft.app.village.repository.VillageCharterRepository;
import com.mannschaft.app.village.repository.VillageCharterRevisionRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 村憲章のサービス（F17.3・設計書 §4〜§8・§15.4）。
 *
 * <p>read は {@link VillageCharterAccessService} の公開ゲート（§3.2）、write は「村状態ガード
 * （{@code loadActiveVillage}）→ 現役 HEADMAN/ELDER（{@code requireHeadmanOrElder}）」の 2 段
 * （§3.3）を先頭で通す。条の自動採番・再連番（層1 非バンプ・§6.3）、末尾追加/削除の親 charter 悲観
 * ロック直列化（§4.5）、並び替えの層2 楽観検査（§7）、策定者スナップショット（§5.2）を担う。</p>
 *
 * <h2>並行制御の要（§6.3/§7）</h2>
 * <p>全構造変更 EP（{@code POST}/{@code DELETE}/{@code PATCH order}）は
 * {@link VillageCharterRepository#findByIdForUpdate}（{@code SELECT ... FOR UPDATE}）で
 * <b>親 charter 行 → 条行</b>の統一ロック順を守り、デッドロックを封殺する（AC-11d）。
 * さらに {@code @Transactional(isolation = READ_COMMITTED)} を付け、悲観ロック取得後の条一覧読みを
 * 「現在の確定値」で読む（MySQL 既定の REPEATABLE READ だと冒頭の非ロック読みで確立した一貫
 * スナップショットに引きずられ、相手がロック解放直前に確定させた条を取りこぼして sort_order が
 * 重複する。寄合定員 {@code upsertAttendance} と同じ設計）。再連番は
 * {@link VillageCharterArticleRepository#updateSortOrder} の bulk UPDATE で条の層1 {@code @Version}
 * を触らず、親 charter の層2 {@code @Version} のみバンプする（AC-11c/12b）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageCharterService {

    /** 条のサブリスト上限（既定・PR レビューで変更可・§15.1/AC-20b）。 */
    static final int MAX_ARTICLES = 200;

    /** 策定者のサブリスト上限（既定・PR レビューで変更可・§15.1/AC-20b）。 */
    static final int MAX_DRAFTERS = 20;

    /** {@code nickname_snapshot} の DDL 上限（VARCHAR(40)）。焼付時に防御的に切り詰める。 */
    private static final int NICKNAME_SNAPSHOT_MAX = 40;

    private final VillageCharterRepository charterRepository;
    private final VillageCharterArticleRepository articleRepository;
    private final VillageCharterDrafterRepository drafterRepository;
    private final VillageCharterRevisionRepository revisionRepository;
    private final VillageRepository villageRepository;
    private final VillageMembershipRepository membershipRepository;
    private final VillageCharterAccessService charterAccessService;
    private final VillageBulletinAccessService bulletinAccessService;
    private final VillageNicknameResolver villageNicknameResolver;
    private final AuditLogService auditLogService;

    // ====================================================================
    // read（公開ゲート・§3.2）
    // ====================================================================

    /** 憲章メタ＋条一覧（自動採番）＋策定者＋改定履歴を返す（未制定は 200＋hasCharter=false・§12.2）。 */
    @Transactional(readOnly = true)
    public VillageCharterResponse getCharter(UUID villageId, Long viewerId) {
        charterAccessService.loadReadableVillageOrHide(villageId, viewerId);
        boolean canEdit = isHeadmanOrElder(villageId, viewerId);
        Optional<VillageCharterEntity> charter = charterRepository.findByVillageIdAndDeletedAtIsNull(villageId);
        if (charter.isEmpty()) {
            return emptyResponse(villageId, canEdit);
        }
        return buildFullResponse(villageId, charter.get(), canEdit);
    }

    // ====================================================================
    // 条 CRUD（§4.4/§4.5/§6.3）
    // ====================================================================

    /** 条を末尾に追加（初回は charter 自動生成＋enacted_at=now・悲観ロック直列化・§4.5・409 なし）。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public VillageCharterResponse addArticle(UUID villageId, CharterArticleCreateRequest req, Long actorUserId) {
        loadActiveVillage(villageId);
        bulletinAccessService.requireHeadmanOrElder(villageId, actorUserId);

        CharterLock lock = getOrCreateCharterLocked(villageId);
        VillageCharterEntity charter = lock.charter();

        if (articleRepository.countByCharterIdAndDeletedAtIsNull(charter.getId()) >= MAX_ARTICLES) {
            throw new BusinessException(VillageErrorCode.VILLAGE_FIELD_INVALID); // 上限超過は 400
        }
        List<VillageCharterArticleEntity> existing =
                articleRepository.findByCharterIdAndDeletedAtIsNullOrderBySortOrderAsc(charter.getId());
        int nextSort = existing.isEmpty() ? 0 : existing.get(existing.size() - 1).getSortOrder() + 1;

        VillageCharterArticleEntity article = VillageCharterArticleEntity.builder()
                .charterId(charter.getId())
                .villageId(villageId)
                .sortOrder(nextSort)
                .body(req.body())
                .supplement(req.supplement())
                .build();
        articleRepository.saveAndFlush(article);

        VillageCharterEntity bumped = bumpCharterVersion(charter); // 層2 バンプ（§7）
        if (lock.created()) {
            audit(AuditEventType.VILLAGE_CHARTER_ENACTED, actorUserId, villageId);
        }
        audit(AuditEventType.VILLAGE_CHARTER_ARTICLE_ADDED, actorUserId, villageId);
        return buildFullResponse(villageId, bumped, true);
    }

    /** 条の本文/付則を更新（条単位 {@code @Version} 層1 楽観ロック・IDOR は village_id 照合・§7/AC-08）。 */
    @Transactional
    public CharterArticleResponse updateArticle(UUID villageId, UUID articleId,
                                                CharterArticleUpdateRequest req, Long actorUserId) {
        loadActiveVillage(villageId);
        bulletinAccessService.requireHeadmanOrElder(villageId, actorUserId);

        VillageCharterArticleEntity article = articleRepository.findByIdAndDeletedAtIsNull(articleId)
                .filter(a -> villageId.equals(a.getVillageId()))
                .orElseThrow(() -> new BusinessException(VillageErrorCode.CHARTER_ARTICLE_NOT_FOUND));

        if (!Objects.equals(article.getVersion(), req.version())) {
            throw new BusinessException(VillageErrorCode.CHARTER_ARTICLE_VERSION_CONFLICT);
        }
        article.setBody(req.body());
        article.setSupplement(req.supplement());
        VillageCharterArticleEntity saved = articleRepository.saveAndFlush(article); // 層1 version ++
        audit(AuditEventType.VILLAGE_CHARTER_ARTICLE_UPDATED, actorUserId, villageId);
        return CharterArticleResponse.of(saved, articleNumberOf(saved));
    }

    /** 条を論理削除し残条を 0,1,2… へ再連番（悲観ロック直列化・409 なし・§6.3）。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public VillageCharterResponse deleteArticle(UUID villageId, UUID articleId, Long actorUserId) {
        loadActiveVillage(villageId);
        bulletinAccessService.requireHeadmanOrElder(villageId, actorUserId);

        VillageCharterEntity charter = loadCharterLockedOrThrow(
                villageId, VillageErrorCode.CHARTER_ARTICLE_NOT_FOUND);
        VillageCharterArticleEntity article = articleRepository.findByIdAndDeletedAtIsNull(articleId)
                .filter(a -> villageId.equals(a.getVillageId()) && charter.getId().equals(a.getCharterId()))
                .orElseThrow(() -> new BusinessException(VillageErrorCode.CHARTER_ARTICLE_NOT_FOUND));

        article.setDeletedAt(LocalDateTime.now());
        articleRepository.saveAndFlush(article);

        VillageCharterEntity bumped = bumpCharterVersion(charter); // 先にバンプ（managed のうちに）
        renumberArticles(charter.getId());                          // bulk UPDATE（層1 非バンプ・context clear）
        audit(AuditEventType.VILLAGE_CHARTER_ARTICLE_DELETED, actorUserId, villageId);
        return buildFullResponse(villageId, bumped, true);
    }

    /** 条の並び順を一括更新（親 charter {@code @Version} 層2 楽観検査・§7・AC-11/12/13）。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public VillageCharterResponse reorderArticles(UUID villageId,
                                                  CharterArticleOrderUpdateRequest req, Long actorUserId) {
        loadActiveVillage(villageId);
        bulletinAccessService.requireHeadmanOrElder(villageId, actorUserId);

        VillageCharterEntity charter = loadCharterLockedOrThrow(villageId, VillageErrorCode.VILLAGE_NOT_FOUND);

        // ロック取得後に層2 楽観一致検査（不一致→409・§6.3）。version 不一致は集合検査より先に弾く。
        if (!Objects.equals(charter.getVersion(), req.charterVersion())) {
            throw new BusinessException(VillageErrorCode.CHARTER_ORDER_VERSION_CONFLICT);
        }

        // 集合検証: 非削除条の完全集合とちょうど一致（過不足・重複は 400）。
        List<VillageCharterArticleEntity> current =
                articleRepository.findByCharterIdAndDeletedAtIsNullOrderBySortOrderAsc(charter.getId());
        Set<UUID> currentIds = new LinkedHashSet<>();
        current.forEach(a -> currentIds.add(a.getId()));
        List<UUID> requested = req.articleIds();
        Set<UUID> requestedSet = new LinkedHashSet<>(requested);
        if (requestedSet.size() != requested.size()          // 重複あり
                || requestedSet.size() != currentIds.size()  // 過不足
                || !requestedSet.containsAll(currentIds)) {
            throw new BusinessException(VillageErrorCode.VILLAGE_FIELD_INVALID); // 400
        }

        VillageCharterEntity bumped = bumpCharterVersion(charter); // 先にバンプ（managed のうちに）
        for (int i = 0; i < requested.size(); i++) {
            articleRepository.updateSortOrder(requested.get(i), i); // bulk（層1 非バンプ・context clear）
        }
        audit(AuditEventType.VILLAGE_CHARTER_REORDERED, actorUserId, villageId);
        return buildFullResponse(villageId, bumped, true);
    }

    // ====================================================================
    // 策定者（§5）
    // ====================================================================

    /** 策定者を追加（村ニックネームを nickname_snapshot に焼付・末尾 sort_order・重複 409・上限 400・§5.2）。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public VillageCharterResponse addDrafter(UUID villageId, CharterDrafterCreateRequest req, Long actorUserId) {
        loadActiveVillage(villageId);
        bulletinAccessService.requireHeadmanOrElder(villageId, actorUserId);

        VillageCharterEntity charter = getOrCreateCharterLocked(villageId).charter();

        if (drafterRepository.existsByCharterIdAndUserId(charter.getId(), req.userId())) {
            throw new BusinessException(VillageErrorCode.CHARTER_DRAFTER_DUPLICATE);
        }
        if (drafterRepository.countByCharterId(charter.getId()) >= MAX_DRAFTERS) {
            throw new BusinessException(VillageErrorCode.VILLAGE_FIELD_INVALID); // 上限超過は 400
        }

        String snapshot = villageNicknameResolver.resolve(req.userId(), villageId);
        if (snapshot != null && snapshot.length() > NICKNAME_SNAPSHOT_MAX) {
            snapshot = snapshot.substring(0, NICKNAME_SNAPSHOT_MAX);
        }
        List<VillageCharterDrafterEntity> existing =
                drafterRepository.findByCharterIdOrderBySortOrderAsc(charter.getId());
        int nextSort = existing.isEmpty() ? 0 : existing.get(existing.size() - 1).getSortOrder() + 1;

        VillageCharterDrafterEntity drafter = VillageCharterDrafterEntity.builder()
                .charterId(charter.getId())
                .userId(req.userId())
                .nicknameSnapshot(snapshot)
                .sortOrder(nextSort)
                .build();
        drafterRepository.saveAndFlush(drafter);
        audit(AuditEventType.VILLAGE_CHARTER_DRAFTER_ADDED, actorUserId, villageId);
        return buildFullResponse(villageId, charter, true);
    }

    /** 策定者を削除（不存在/他 charter は 404・残る策定者を 0,1,2… 再連番・全体返却・§5.3/AC-16b）。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public VillageCharterResponse removeDrafter(UUID villageId, UUID drafterId, Long actorUserId) {
        loadActiveVillage(villageId);
        bulletinAccessService.requireHeadmanOrElder(villageId, actorUserId);

        VillageCharterEntity charter = loadCharterLockedOrThrow(
                villageId, VillageErrorCode.CHARTER_DRAFTER_NOT_FOUND);
        VillageCharterDrafterEntity drafter = drafterRepository.findByIdAndCharterId(drafterId, charter.getId())
                .orElseThrow(() -> new BusinessException(VillageErrorCode.CHARTER_DRAFTER_NOT_FOUND));

        drafterRepository.delete(drafter);
        drafterRepository.flush();

        // 策定者の再連番（drafter は @Version を持たないので通常 save で可）。
        List<VillageCharterDrafterEntity> remaining =
                drafterRepository.findByCharterIdOrderBySortOrderAsc(charter.getId());
        for (int i = 0; i < remaining.size(); i++) {
            if (remaining.get(i).getSortOrder() == null || remaining.get(i).getSortOrder() != i) {
                remaining.get(i).setSortOrder(i);
            }
        }
        drafterRepository.saveAll(remaining);
        audit(AuditEventType.VILLAGE_CHARTER_DRAFTER_REMOVED, actorUserId, villageId);
        return buildFullResponse(villageId, charter, true);
    }

    // ====================================================================
    // 改定（§8）
    // ====================================================================

    /** 「改正を確定」＝last_revised_at=now・改定履歴に 1 行追記（未制定村は 404・enacted_at 不変・§8.2/AC-18）。 */
    @Transactional
    public VillageCharterResponse addRevision(UUID villageId, CharterRevisionCreateRequest req, Long actorUserId) {
        loadActiveVillage(villageId);
        bulletinAccessService.requireHeadmanOrElder(villageId, actorUserId);

        // 未制定村（charter 無し）への「改正を確定」は概念矛盾。存在秘匿に寄せ VILLAGE_NOT_FOUND(404)（§18.2）。
        VillageCharterEntity charter = charterRepository.findByVillageIdAndDeletedAtIsNull(villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now();
        charter.setLastRevisedAt(now); // enacted_at は触らない（不変・INV-3）
        VillageCharterEntity saved = charterRepository.saveAndFlush(charter);

        VillageCharterRevisionEntity revision = VillageCharterRevisionEntity.builder()
                .charterId(charter.getId())
                .revisedAt(now)
                .note(req.note())
                .build();
        revisionRepository.saveAndFlush(revision);
        audit(AuditEventType.VILLAGE_CHARTER_REVISED, actorUserId, villageId);
        return buildFullResponse(villageId, saved, true);
    }

    // ====================================================================
    // 共通ヘルパ
    // ====================================================================

    /** 有効な村を取得する（削除/不存在→404・凍結→VILLAGE_027・§3.3 write 村状態ガード）。 */
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

    /** 閲覧者が現役 HEADMAN/ELDER か（canEdit 判定・read 用・例外は投げない）。 */
    private boolean isHeadmanOrElder(UUID villageId, Long userId) {
        if (userId == null) {
            return false;
        }
        return membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, userId)
                .map(m -> m.getRole() == VillageRole.HEADMAN || m.getRole() == VillageRole.ELDER)
                .orElse(false);
    }

    /**
     * 親 charter を取得（無ければ自動生成）し悲観ロックする（§4.5）。
     *
     * <p>初回作成の並行競合（2 管理者が同時に最初の条を足す）で UNIQUE(village_id) 違反が出た場合は、
     * 1 回だけ再取得して既存 charter をロックする（upsert 冪等リトライと同思想）。</p>
     */
    private CharterLock getOrCreateCharterLocked(UUID villageId) {
        // ロック前は id のみをスカラ取得する（本体を先読みすると一次キャッシュが古い @Version を
        // 保持し、ロック後のバンプが OptimisticLock 失敗になる・findIdByVillageId の Javadoc 参照）。
        Optional<UUID> existingId = charterRepository.findIdByVillageId(villageId);
        if (existingId.isPresent()) {
            return new CharterLock(lockCharter(existingId.get()), false);
        }
        try {
            VillageCharterEntity created = VillageCharterEntity.builder()
                    .villageId(villageId)
                    .enactedAt(LocalDateTime.now())
                    .build();
            VillageCharterEntity saved = charterRepository.saveAndFlush(created);
            return new CharterLock(lockCharter(saved.getId()), true);
        } catch (DataIntegrityViolationException dup) {
            // 並行初回作成の UNIQUE 競合。既存 charter の id を取り直してロックする（append にフォールバック）。
            UUID otherId = charterRepository.findIdByVillageId(villageId)
                    .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));
            return new CharterLock(lockCharter(otherId), false);
        }
    }

    /** 村の既存 charter を悲観ロックで取得する。無ければ指定コードで throw。 */
    private VillageCharterEntity loadCharterLockedOrThrow(UUID villageId, VillageErrorCode notFound) {
        // ロック前は id のみをスカラ取得して最新版でロックする（findIdByVillageId の Javadoc 参照）。
        UUID charterId = charterRepository.findIdByVillageId(villageId)
                .orElseThrow(() -> new BusinessException(notFound));
        return charterRepository.findByIdForUpdate(charterId)
                .orElseThrow(() -> new BusinessException(notFound));
    }

    private VillageCharterEntity lockCharter(UUID charterId) {
        return charterRepository.findByIdForUpdate(charterId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));
    }

    /**
     * 親 charter の層2 {@code @Version} をバンプする（§7）。
     *
     * <p>子（条）のみ変える構造変更では親エンティティが自然には dirty にならないため、{@code updatedAt} を
     * 明示的に更新して dirty 化し、通常の版付き UPDATE を flush で発火させる（OFI は commit 時発火で
     * 500 化しうるため避ける・memory {@code feedback_ofi_version_bump_fires_at_commit_not_flush}）。
     * 親行は本メソッド前に悲観ロック済みなので、この UPDATE は他管理者と衝突しない。</p>
     */
    private VillageCharterEntity bumpCharterVersion(VillageCharterEntity charter) {
        charter.setUpdatedAt(LocalDateTime.now());
        return charterRepository.saveAndFlush(charter);
    }

    /** 非削除条を {@code sort_order} 昇順に 0,1,2… へ詰め直す（bulk UPDATE・層1 非バンプ・§6.3）。 */
    private void renumberArticles(UUID charterId) {
        List<VillageCharterArticleEntity> remaining =
                articleRepository.findByCharterIdAndDeletedAtIsNullOrderBySortOrderAsc(charterId);
        for (int i = 0; i < remaining.size(); i++) {
            if (remaining.get(i).getSortOrder() == null || remaining.get(i).getSortOrder() != i) {
                articleRepository.updateSortOrder(remaining.get(i).getId(), i);
            }
        }
    }

    /** 条の表示採番（第 N 条）を非削除条の並び順から導出する（§6.1）。 */
    private int articleNumberOf(VillageCharterArticleEntity article) {
        List<VillageCharterArticleEntity> ordered =
                articleRepository.findByCharterIdAndDeletedAtIsNullOrderBySortOrderAsc(article.getCharterId());
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(article.getId())) {
                return i + 1;
            }
        }
        return (article.getSortOrder() == null ? 0 : article.getSortOrder()) + 1;
    }

    private VillageCharterResponse emptyResponse(UUID villageId, boolean canEdit) {
        return VillageCharterResponse.builder()
                .villageId(villageId)
                .hasCharter(false)
                .enactedAt(null)
                .lastRevisedAt(null)
                .version(null)
                .canEdit(canEdit)
                .articles(List.of())
                .drafters(List.of())
                .revisions(List.of())
                .build();
    }

    /** 憲章全体（メタ＋条〔自動採番〕＋策定者＋改定履歴）を組み立てる（GET は各サブリスト1本＝N+1なし・§15.1/AC-20）。 */
    private VillageCharterResponse buildFullResponse(UUID villageId, VillageCharterEntity charter, boolean canEdit) {
        List<VillageCharterArticleEntity> articles =
                articleRepository.findByCharterIdAndDeletedAtIsNullOrderBySortOrderAsc(charter.getId());
        List<CharterArticleResponse> articleDtos = new ArrayList<>(articles.size());
        for (int i = 0; i < articles.size(); i++) {
            articleDtos.add(CharterArticleResponse.of(articles.get(i), i + 1));
        }
        List<CharterDrafterResponse> drafterDtos =
                drafterRepository.findByCharterIdOrderBySortOrderAsc(charter.getId()).stream()
                        .map(CharterDrafterResponse::of)
                        .toList();
        List<CharterRevisionResponse> revisionDtos =
                revisionRepository.findByCharterIdOrderByRevisedAtDesc(charter.getId()).stream()
                        .map(CharterRevisionResponse::of)
                        .toList();
        return VillageCharterResponse.builder()
                .villageId(villageId)
                .hasCharter(true)
                .enactedAt(charter.getEnactedAt())
                .lastRevisedAt(charter.getLastRevisedAt())
                .version(charter.getVersion())
                .canEdit(canEdit)
                .articles(articleDtos)
                .drafters(drafterDtos)
                .revisions(revisionDtos)
                .build();
    }

    private void audit(AuditEventType type, Long actorUserId, UUID villageId) {
        auditLogService.record(
                type.name(), actorUserId, null, null, null, null, null, null,
                "{\"villageId\":\"" + villageId + "\"}");
    }

    /** 悲観ロック済みの charter と「今回自動生成したか」を運ぶ内部ホルダ。 */
    private record CharterLock(VillageCharterEntity charter, boolean created) {
    }
}
