package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.village.dto.CharterArticleCreateRequest;
import com.mannschaft.app.village.dto.CharterArticleOrderUpdateRequest;
import com.mannschaft.app.village.dto.CharterArticleResponse;
import com.mannschaft.app.village.dto.CharterArticleUpdateRequest;
import com.mannschaft.app.village.dto.CharterDrafterCreateRequest;
import com.mannschaft.app.village.dto.CharterRevisionCreateRequest;
import com.mannschaft.app.village.dto.VillageCharterResponse;
import com.mannschaft.app.village.repository.VillageCharterArticleRepository;
import com.mannschaft.app.village.repository.VillageCharterDrafterRepository;
import com.mannschaft.app.village.repository.VillageCharterRepository;
import com.mannschaft.app.village.repository.VillageCharterRevisionRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 村憲章のサービス（F17.3・設計書 §4〜§8・§15.4）。
 *
 * <p>read は {@link VillageCharterAccessService} の公開ゲート（§3.2）、write は「村状態ガード
 * （{@code loadActiveVillage} 相当）→ 現役 HEADMAN/ELDER（{@code requireHeadmanOrElder}）」の 2 段
 * （§3.3）を先頭で通す。条の自動採番・再連番（層1 非バンプ・§6.3）、末尾追加/削除の親 charter 悲観
 * ロック直列化（§4.5）、並び替えの層2 楽観検査（§7）、策定者スナップショット（§5.2）、退会 NULL 化
 * （§11.1）を担う。</p>
 *
 * <p><b>W1 骨格スタブ</b>: 認可・並行制御・採番・退会 NULL 化などの enforcement は出陣（W3）で実装する。
 * 本クラスは 8 EP の public メソッド signature と DI の seam のみ提供し、各メソッドは
 * {@link UnsupportedOperationException} を投げる（＝試練の red 化）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageCharterService {

    /** 条のサブリスト上限（既定・PR レビューで変更可・§15.1/AC-20b）。 */
    static final int MAX_ARTICLES = 200;

    /** 策定者のサブリスト上限（既定・PR レビューで変更可・§15.1/AC-20b）。 */
    static final int MAX_DRAFTERS = 20;

    private final VillageCharterRepository charterRepository;
    private final VillageCharterArticleRepository articleRepository;
    private final VillageCharterDrafterRepository drafterRepository;
    private final VillageCharterRevisionRepository revisionRepository;
    private final VillageRepository villageRepository;
    private final VillageCharterAccessService charterAccessService;
    private final VillageBulletinAccessService bulletinAccessService;
    private final VillageNicknameResolver villageNicknameResolver;
    private final AuditLogService auditLogService;

    private static final String W3 = "F17.3 W3（出陣）で実装予定";

    /** 憲章メタ＋条一覧（自動採番）＋策定者＋改定履歴を返す（read 公開ゲート・§3.2/§4.4）。 */
    @Transactional(readOnly = true)
    public VillageCharterResponse getCharter(UUID villageId, Long viewerId) {
        throw new UnsupportedOperationException(W3 + ": getCharter");
    }

    /** 条を末尾に追加（初回は charter 自動生成・悲観ロック直列化・§4.5）。 */
    @Transactional
    public VillageCharterResponse addArticle(UUID villageId, CharterArticleCreateRequest req, Long actorUserId) {
        throw new UnsupportedOperationException(W3 + ": addArticle");
    }

    /** 条の本文/付則を更新（条単位 {@code @Version} 層1 楽観ロック・§7）。 */
    @Transactional
    public CharterArticleResponse updateArticle(UUID villageId, UUID articleId,
                                                CharterArticleUpdateRequest req, Long actorUserId) {
        throw new UnsupportedOperationException(W3 + ": updateArticle");
    }

    /** 条を論理削除し残条を再連番（悲観ロック直列化・§6.3）。 */
    @Transactional
    public VillageCharterResponse deleteArticle(UUID villageId, UUID articleId, Long actorUserId) {
        throw new UnsupportedOperationException(W3 + ": deleteArticle");
    }

    /** 条の並び順を一括更新（親 charter {@code @Version} 層2 楽観検査・§7）。 */
    @Transactional
    public VillageCharterResponse reorderArticles(UUID villageId,
                                                  CharterArticleOrderUpdateRequest req, Long actorUserId) {
        throw new UnsupportedOperationException(W3 + ": reorderArticles");
    }

    /** 策定者を追加（村ニックネームを焼付・§5.2）。 */
    @Transactional
    public VillageCharterResponse addDrafter(UUID villageId, CharterDrafterCreateRequest req, Long actorUserId) {
        throw new UnsupportedOperationException(W3 + ": addDrafter");
    }

    /** 策定者を削除（更新後の憲章全体を返す・再連番・§5.3/AC-16b）。 */
    @Transactional
    public VillageCharterResponse removeDrafter(UUID villageId, UUID drafterId, Long actorUserId) {
        throw new UnsupportedOperationException(W3 + ": removeDrafter");
    }

    /** 「改正を確定」＝{@code last_revised_at} 更新＋改定履歴に 1 行追記（§8.2）。 */
    @Transactional
    public VillageCharterResponse addRevision(UUID villageId, CharterRevisionCreateRequest req, Long actorUserId) {
        throw new UnsupportedOperationException(W3 + ": addRevision");
    }
}
