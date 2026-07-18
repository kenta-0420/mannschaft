package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.NewsletterIssueDetailResponse;
import com.mannschaft.app.village.dto.NewsletterIssuePageResponse;
import com.mannschaft.app.village.dto.NewsletterIssueSummaryResponse;
import com.mannschaft.app.village.dto.NewsletterTagResponse;
import com.mannschaft.app.village.dto.PublicNewsletterIssuePageResponse;
import com.mannschaft.app.village.dto.PublicNewsletterIssueResponse;
import com.mannschaft.app.village.entity.VillageNewsletterIssueEntity;
import com.mannschaft.app.village.entity.VillageNewsletterIssueTagEntity;
import com.mannschaft.app.village.entity.VillageNewsletterTagEntity;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueStatus;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueType;
import com.mannschaft.app.village.entity.enums.VillageNewsletterVisibility;
import com.mannschaft.app.village.repository.VillageNewsletterIssueRepository;
import com.mannschaft.app.village.repository.VillageNewsletterIssueTagRepository;
import com.mannschaft.app.village.repository.VillageNewsletterTagRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 村ニュースレター号サービス（F17.1 ②-2・設計書 §4.2 / §5）。
 *
 * <p>集計器（{@link VillageNewsletterDigestAggregator}）が<b>トランザクション外で</b>確定した snapshot を
 * 引数で受け取り、号エンティティへ複写して凍結する。本サービスの {@code @Transactional} は
 * <b>村ドメインのリポジトリのみ</b>に依存し他ドメインを読まないため、越境トランザクション
 * （番人 D-3）にならない。凍結後のダイジェストは不変（改ざん不可・要件①）。</p>
 *
 * <h2>改ざん不可の担保（AC-02・設計書 §4.2）</h2>
 * <ul>
 *   <li>号エンティティ {@link VillageNewsletterIssueEntity} は {@code digest_*} に setter を一切持たない
 *       （更新経路が存在しない）。値は生成時に {@code @SuperBuilder} 経由でのみ確定する。</li>
 *   <li>{@link VillageNewsletterIssueEntity#freeze} は {@code AGGREGATED} 以外からの遷移を
 *       {@link IllegalStateException} で拒否する。本サービスはこれを
 *       {@link VillageErrorCode#NEWSLETTER_ISSUE_ALREADY_FROZEN} に翻訳し、
 *       凍結済み号の再集計・再凍結を型付きドメインエラーとして弾く。</li>
 * </ul>
 *
 * <h2>原則準拠</h2>
 * <p>原則5: 集計・凍結・監査は village ドメイン内で完結する。配信（notification 越境）は ②-3 で分離。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageNewsletterIssueService {

    private final VillageNewsletterIssueRepository issueRepository;
    private final AuditLogService auditLogService;

    // ②-4（コメント/タグ/公開一覧 API）で追加。バッチ経路（freezeIssue）は無変更で他ドメイン非依存を維持し、
    // API 経路のみが閲覧認可（掲示板流用）・編集認可（村メンバーシップ正準述語）・タグ中間表を用いる。
    private final VillageNewsletterTagRepository tagRepository;
    private final VillageNewsletterIssueTagRepository issueTagRepository;
    private final VillageBulletinAccessService bulletinAccessService;

    // ②-4 堅牢性（Issue #2348）で追加。
    // villageRepository: 公開号の発行元村が生存しているかを確認する（村生存ゲート・AC-4〜8）。
    //   同一 village ドメインのため直接参照してよい（クロスドメインではない）。
    // entityManager: タグ入替は中間表のみを変更し号行を dirty にしないため、@Version の
    //   強制インクリメントロック（OPTIMISTIC_FORCE_INCREMENT）を掛けて lost update を根治する（AC-1〜3）。
    private final VillageRepository villageRepository;
    private final EntityManager entityManager;

    /**
     * 集計済みの snapshot を号へ複写して凍結する（AGGREGATED → FROZEN）。
     *
     * <p>集計（他ドメイン読み取り）は呼び出し元（集計バッチ）が<b>トランザクション外で</b>済ませ、
     * その結果 {@code snapshot} を本メソッドへ渡す。本メソッドの {@code @Transactional} は
     * 村ドメイン（{@code issueRepository}）のみに閉じ、越境しない（番人 D-3 回避）。</p>
     *
     * <p><b>冪等（AC-03）</b>: 同一村×頻度×{@code periodStart} の号が既に存在する場合は、
     * 保存も凍結も行わず既存号をそのまま返す（集計バッチの二重起動・並行実行に対する最終防衛）。
     * 既存号の凍結ダイジェストは触らない＝改ざん不可（AC-02）。</p>
     *
     * @param villageId          村 ID
     * @param frequency          頻度（WEEKLY / MONTHLY）
     * @param newsletterId       紐づくニュースレター設定 ID（号外では null）
     * @param periodStart        集計期間の開始（含む）
     * @param periodEnd          集計期間の終了（含まない・集計基準時刻）
     * @param scheduledPublishAt 配信予定時刻（ラグの終端）
     * @param snapshot           トランザクション外で確定済みの集計 snapshot
     * @return 生成・凍結した号（既存があればその号）
     * @throws BusinessException 既存号が凍結済みで再凍結を試みた場合（{@link VillageErrorCode#NEWSLETTER_ISSUE_ALREADY_FROZEN}）
     */
    @Transactional
    public VillageNewsletterIssueEntity freezeIssue(
            UUID villageId,
            VillageNewsletterFrequency frequency,
            UUID newsletterId,
            LocalDateTime periodStart,
            LocalDateTime periodEnd,
            LocalDateTime scheduledPublishAt,
            NewsletterDigestSnapshot snapshot) {

        // 冪等（AC-03）: 既存号があれば何もせず返す。凍結済み snapshot は不変（AC-02）。
        Optional<VillageNewsletterIssueEntity> existing = issueRepository
                .findByVillageIdAndFrequencyAndPeriodStart(villageId, frequency, periodStart);
        if (existing.isPresent()) {
            log.debug("ニュースレター号は既に存在するため凍結しない（冪等）: villageId={} frequency={} periodStart={}",
                    villageId, frequency, periodStart);
            return existing.get();
        }

        List<Map.Entry<String, Integer>> top3 = snapshot.top3Topics();
        LocalDateTime now = LocalDateTime.now();

        VillageNewsletterIssueEntity issue = VillageNewsletterIssueEntity.builder()
                .villageId(villageId)
                .newsletterId(newsletterId)
                .frequency(frequency)
                .issueType(VillageNewsletterIssueType.REGULAR)
                // status は onCreate で AGGREGATED になるが、freeze() の前提を明示するため明示指定する。
                .status(VillageNewsletterIssueStatus.AGGREGATED)
                .title(generateTitle(frequency, periodStart))
                .visibility(VillageNewsletterVisibility.VILLAGE_MEMBERS)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .aggregatedAt(now)
                .scheduledPublishAt(scheduledPublishAt)
                .digestPostCount(snapshot.postCount())
                .digestNewMemberCount(snapshot.newMemberCount())
                .digestFestivalCount(snapshot.festivalCount())
                .digestMeetupCount(snapshot.meetupCount())
                .digestRecruitCount(snapshot.recruitCount())
                .digestTopic1Name(topicName(top3, 0))
                .digestTopic1Count(topicCount(top3, 0))
                .digestTopic2Name(topicName(top3, 1))
                .digestTopic2Count(topicCount(top3, 1))
                .digestTopic3Name(topicName(top3, 2))
                .digestTopic3Count(topicCount(top3, 2))
                .build();

        VillageNewsletterIssueEntity saved = issueRepository.save(issue);

        // 集計値は build 時に確定済み。ここで状態のみ FROZEN へ遷移させる（以後 digest_* は不変）。
        try {
            saved.freeze(now, scheduledPublishAt);
        } catch (IllegalStateException e) {
            // AGGREGATED 以外からの凍結＝改ざんに当たる遷移。型付きドメインエラーへ翻訳する（設計書 §4.2）。
            throw new BusinessException(VillageErrorCode.NEWSLETTER_ISSUE_ALREADY_FROZEN);
        }
        VillageNewsletterIssueEntity frozen = issueRepository.save(saved);

        auditLogService.record(
                AuditEventType.VILLAGE_NEWSLETTER_ISSUE_FROZEN.name(),
                null, null, null, null,
                null, null, null,
                "{\"villageId\":\"" + villageId
                        + "\",\"issueId\":\"" + frozen.getId()
                        + "\",\"frequency\":\"" + frequency
                        + "\",\"periodStart\":\"" + periodStart
                        + "\",\"periodEnd\":\"" + periodEnd
                        + "\",\"postCount\":" + snapshot.postCount()
                        + ",\"newMemberCount\":" + snapshot.newMemberCount() + "}"
        );
        log.info("ニュースレター号を集計・凍結: villageId={} frequency={} periodStart={} periodEnd={} postCount={}",
                villageId, frequency, periodStart, periodEnd, snapshot.postCount());

        return frozen;
    }

    // ==================================================================
    // ②-4: 号 API（一覧 / 詳細 / コメント / タグ付け / 公開範囲）
    // ==================================================================

    /**
     * 村内の号一覧（新しい順・タグ絞り込み可）。閲覧認可は掲示板と同一（村史に倣う・設計書 §8.1）。
     *
     * <p>{@code tagId} 指定時は中間表の逆引き（{@link VillageNewsletterIssueTagRepository#findByTagId}）で
     * 号 ID 集合を得てから村スコープで絞る（漏洩防止に村スコープ必須・空集合は {@code IN ()} を避けて短絡）。</p>
     */
    @Transactional(readOnly = true)
    public NewsletterIssuePageResponse listIssues(
            UUID villageId, Long userId, UUID tagId, Pageable pageable) {
        bulletinAccessService.checkVillageBulletinViewAccess(villageId, userId);

        Page<VillageNewsletterIssueEntity> page;
        if (tagId != null) {
            Set<UUID> issueIds = issueTagRepository.findByTagId(tagId).stream()
                    .map(VillageNewsletterIssueTagEntity::getIssueId)
                    .collect(Collectors.toSet());
            if (issueIds.isEmpty()) {
                page = Page.empty(pageable);
            } else {
                page = issueRepository.findByVillageIdAndIdInAndDeletedAtIsNullOrderByCreatedAtDesc(
                        villageId, issueIds, pageable);
            }
        } else {
            page = issueRepository.findByVillageIdAndDeletedAtIsNullOrderByCreatedAtDesc(villageId, pageable);
        }

        Map<UUID, List<NewsletterTagResponse>> tagsByIssue = resolveTagsForIssues(
                page.getContent().stream().map(VillageNewsletterIssueEntity::getId).toList());
        List<NewsletterIssueSummaryResponse> content = page.getContent().stream()
                .map(issue -> toSummary(issue, tagsByIssue.getOrDefault(issue.getId(), List.of())))
                .toList();
        return NewsletterIssuePageResponse.builder()
                .content(content)
                .totalElements(page.getTotalElements())
                .page(page.getNumber())
                .size(page.getSize())
                .build();
    }

    /** 号詳細（凍結ダイジェスト＋コメント＋タグ）。閲覧認可は掲示板と同一。村不一致・不存在は 404 秘匿。 */
    @Transactional(readOnly = true)
    public NewsletterIssueDetailResponse getIssue(UUID villageId, UUID issueId, Long userId) {
        bulletinAccessService.checkVillageBulletinViewAccess(villageId, userId);
        VillageNewsletterIssueEntity issue = issueRepository
                .findByIdAndVillageIdAndDeletedAtIsNull(issueId, villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.NEWSLETTER_ISSUE_NOT_FOUND));
        return toDetail(issue);
    }

    /**
     * 村長コメントを保存する（HEADMAN / ELDER・楽観ロック・AC-06/07/08/09）。
     *
     * <p>コメントはダイジェスト本体とは別カラムのため、<b>凍結後（FROZEN / PUBLISHED）でも編集可</b>
     * （AC-09・snapshot 不変性に触れない）。ステータスに依らず保存できる。</p>
     */
    @Transactional
    public NewsletterIssueDetailResponse updateComment(
            UUID villageId, UUID issueId, Long userId, String comment, Long expectedVersion) {
        bulletinAccessService.requireHeadmanOrElder(villageId, userId);
        VillageNewsletterIssueEntity issue = loadIssueForEdit(villageId, issueId, expectedVersion);
        issue.updateComment(comment, userId, LocalDateTime.now());
        VillageNewsletterIssueEntity saved = issueRepository.save(issue);
        log.info("ニュースレター号コメント保存: villageId={} issueId={} userId={}", villageId, issueId, userId);
        return toDetail(saved);
    }

    /**
     * 号へのタグ付けを更新する（HEADMAN / ELDER・楽観ロック・置き換え式・AC-15）。
     *
     * <p>指定タグが<b>すべて当該村に属する</b>ことを検証してから中間表を入れ替える
     * （他村タグの混入＝漏洩を {@code NEWSLETTER_TAG_NOT_FOUND} で拒否）。</p>
     */
    @Transactional
    public NewsletterIssueDetailResponse setIssueTags(
            UUID villageId, UUID issueId, Long userId, List<UUID> tagIds, Long expectedVersion) {
        bulletinAccessService.requireHeadmanOrElder(villageId, userId);
        VillageNewsletterIssueEntity issue = loadIssueForEdit(villageId, issueId, expectedVersion);

        // 【lost update 根治・AC-1〜3】タグ付けは中間表（issue_tags）のみを入れ替え、号行そのものは
        // 変更しない。このままでは Hibernate が号行を dirty と見なさず @Version が上がらないため、
        // 「2 管理者が同一 version で同時にタグ付け」しても両方成功し、後勝ちで先の付け替えが失われる
        // （lost update）。号行に OPTIMISTIC_FORCE_INCREMENT ロックを掛けることで、flush 時に
        // version 付き UPDATE を強制発行させ、並行編集を版競合として検出する。
        entityManager.lock(issue, LockModeType.OPTIMISTIC_FORCE_INCREMENT);

        List<UUID> distinctTagIds = tagIds == null ? List.of()
                : tagIds.stream().filter(Objects::nonNull).distinct().toList();
        for (UUID tagId : distinctTagIds) {
            tagRepository.findByIdAndVillageIdAndDeletedAtIsNull(tagId, villageId)
                    .orElseThrow(() -> new BusinessException(VillageErrorCode.NEWSLETTER_TAG_NOT_FOUND));
        }
        issueTagRepository.deleteByIssueId(issueId);
        for (UUID tagId : distinctTagIds) {
            issueTagRepository.save(VillageNewsletterIssueTagEntity.builder()
                    .issueId(issueId)
                    .tagId(tagId)
                    .build());
        }
        VillageNewsletterIssueEntity saved = issueRepository.save(issue);
        try {
            // 強制インクリメントの version 付き UPDATE をこの場で確定させ、版競合を即検出する
            // （握り潰さず、既存の号版競合と同じ型付きエラー 409 に翻訳する）。
            entityManager.flush();
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
            throw new BusinessException(VillageErrorCode.NEWSLETTER_ISSUE_VERSION_CONFLICT);
        }
        log.info("ニュースレター号タグ付け更新: villageId={} issueId={} tagCount={}",
                villageId, issueId, distinctTagIds.size());
        return toDetail(saved);
    }

    /** 号の公開範囲を切り替える（HEADMAN / ELDER・楽観ロック・VILLAGE_MEMBERS↔PUBLIC）。 */
    @Transactional
    public NewsletterIssueDetailResponse changeVisibility(
            UUID villageId, UUID issueId, Long userId,
            VillageNewsletterVisibility visibility, Long expectedVersion) {
        bulletinAccessService.requireHeadmanOrElder(villageId, userId);
        VillageNewsletterIssueEntity issue = loadIssueForEdit(villageId, issueId, expectedVersion);
        issue.changeVisibility(visibility);
        VillageNewsletterIssueEntity saved = issueRepository.save(issue);
        log.info("ニュースレター号公開範囲切替: villageId={} issueId={} visibility={}",
                villageId, issueId, visibility);
        return toDetail(saved);
    }

    // ==================================================================
    // ②-4: タグ CRUD（HEADMAN / ELDER・使用中ガードは募集カテゴリに倣う）
    // ==================================================================

    /** 村のタグ一覧（表示順）。閲覧認可は掲示板と同一。 */
    @Transactional(readOnly = true)
    public List<NewsletterTagResponse> listTags(UUID villageId, Long userId) {
        bulletinAccessService.checkVillageBulletinViewAccess(villageId, userId);
        return tagRepository.findByVillageIdAndDeletedAtIsNullOrderBySortOrderAsc(villageId).stream()
                .map(VillageNewsletterIssueService::toTagResponse)
                .toList();
    }

    /** タグを作成する（HEADMAN / ELDER）。村内タグ名の重複は 409（NEWSLETTER_TAG_DUPLICATE）。 */
    @Transactional
    public NewsletterTagResponse createTag(
            UUID villageId, Long userId, String name, String color, Integer sortOrder) {
        bulletinAccessService.requireHeadmanOrElder(villageId, userId);
        tagRepository.findByVillageIdAndNameAndDeletedAtIsNull(villageId, name)
                .ifPresent(t -> {
                    throw new BusinessException(VillageErrorCode.NEWSLETTER_TAG_DUPLICATE);
                });
        VillageNewsletterTagEntity tag = VillageNewsletterTagEntity.builder()
                .villageId(villageId)
                .name(name)
                .color(color)          // null は onCreate で既定色 #6B7280
                .sortOrder(sortOrder)  // null は onCreate で 0
                .build();
        VillageNewsletterTagEntity saved = tagRepository.save(tag);
        log.info("ニュースレタータグ作成: villageId={} name={} userId={}", villageId, name, userId);
        return toTagResponse(saved);
    }

    /** タグを更新する（HEADMAN / ELDER・楽観ロック）。改名で村内他タグと衝突する場合は 409。 */
    @Transactional
    public NewsletterTagResponse updateTag(
            UUID villageId, UUID tagId, Long userId,
            String name, String color, Integer sortOrder, Long expectedVersion) {
        bulletinAccessService.requireHeadmanOrElder(villageId, userId);
        VillageNewsletterTagEntity tag = tagRepository
                .findByIdAndVillageIdAndDeletedAtIsNull(tagId, villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.NEWSLETTER_TAG_NOT_FOUND));
        if (expectedVersion != null && !Objects.equals(tag.getVersion(), expectedVersion)) {
            // タグ専用の版競合コード（AC-13）。号の版競合（VILLAGE_089）とは対象エンティティが異なるため分離する。
            throw new BusinessException(VillageErrorCode.NEWSLETTER_TAG_VERSION_CONFLICT);
        }
        tagRepository.findByVillageIdAndNameAndDeletedAtIsNull(villageId, name)
                .filter(other -> !other.getId().equals(tagId))
                .ifPresent(o -> {
                    throw new BusinessException(VillageErrorCode.NEWSLETTER_TAG_DUPLICATE);
                });
        tag.setName(name);
        if (color != null) {
            tag.setColor(color);
        }
        if (sortOrder != null) {
            tag.setSortOrder(sortOrder);
        }
        VillageNewsletterTagEntity saved = tagRepository.save(tag);
        try {
            // 上の expectedVersion 手動チェックは load〜save 間の“素早い”競合しか捕まえない。
            // 真の同時編集レース（両者が同一版を読み、@Version が commit 時に衝突）の敗者を、
            // setIssueTags と対称に flush で即検出し専用コード 093 に翻訳する（汎用 COMMON_003 に落とさない・AC-13）。
            entityManager.flush();
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
            throw new BusinessException(VillageErrorCode.NEWSLETTER_TAG_VERSION_CONFLICT);
        }
        log.info("ニュースレタータグ更新: villageId={} tagId={} userId={}", villageId, tagId, userId);
        return toTagResponse(saved);
    }

    /** タグを削除する（HEADMAN / ELDER・使用中ガード）。号に使われている場合は 409（NEWSLETTER_TAG_IN_USE）。 */
    @Transactional
    public void deleteTag(UUID villageId, UUID tagId, Long userId) {
        bulletinAccessService.requireHeadmanOrElder(villageId, userId);
        VillageNewsletterTagEntity tag = tagRepository
                .findByIdAndVillageIdAndDeletedAtIsNull(tagId, villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.NEWSLETTER_TAG_NOT_FOUND));
        if (issueTagRepository.countByTagId(tagId) > 0) {
            throw new BusinessException(VillageErrorCode.NEWSLETTER_TAG_IN_USE);
        }
        tag.setDeletedAt(LocalDateTime.now());
        tagRepository.save(tag);
        log.info("ニュースレタータグ削除: villageId={} tagId={} userId={}", villageId, tagId, userId);
    }

    // ==================================================================
    // ②-4: 公開一覧（村横断・ログイン必須のみ・AC-16/17）
    // ==================================================================

    /**
     * 公開号の村横断一覧（新しい順）。ログイン必須のみ（村メンバー不問）。
     *
     * <p>クエリが {@code visibility=PUBLIC} かつ {@code status=PUBLISHED} のみを引くため、
     * {@code VILLAGE_MEMBERS} の号は<b>構造的に混入しえない</b>（AC-16・漏洩防止）。さらに②-4 堅牢性で
     * <b>発行元の村が生存している号だけ</b>を返すよう村生存ゲート（{@code deleted_at/archived_at IS NULL}）を
     * クエリに加え、削除／凍結された村のお便り（ゾンビ号）の露出を根治する（AC-4〜8）。</p>
     *
     * <p>タグは号ごとの N+1 を避け、ページ内の全号 ID でリンク 1 本＋全タグ ID でタグ 1 本を引く
     * 一括解決に切り替える（AC-9）。号↔タグの対応は issueId で厳密に保つ（AC-11）。</p>
     */
    @Transactional(readOnly = true)
    public PublicNewsletterIssuePageResponse listPublicIssues(Long userId, Pageable pageable) {
        Page<VillageNewsletterIssueEntity> page = issueRepository
                .findPublicIssuesFromAliveVillages(
                        VillageNewsletterVisibility.PUBLIC,
                        VillageNewsletterIssueStatus.PUBLISHED,
                        pageable);
        Map<UUID, List<NewsletterTagResponse>> tagsByIssue = resolveTagsForIssues(
                page.getContent().stream().map(VillageNewsletterIssueEntity::getId).toList());
        List<PublicNewsletterIssueResponse> content = page.getContent().stream()
                .map(issue -> toPublic(issue, tagsByIssue.getOrDefault(issue.getId(), List.of())))
                .toList();
        return PublicNewsletterIssuePageResponse.builder()
                .content(content)
                .totalElements(page.getTotalElements())
                .page(page.getNumber())
                .size(page.getSize())
                .build();
    }

    /**
     * 公開号の詳細（村横断）。ログイン必須のみ。
     *
     * <p>PUBLIC かつ PUBLISHED 以外（VILLAGE_MEMBERS 号・未配信号・削除号）への直アクセスは
     * {@code NEWSLETTER_ISSUE_NOT_FOUND}（404）で存在秘匿する（AC-17・IDOR 対策）。さらに②-4 堅牢性で、
     * 号自体は PUBLIC×PUBLISHED でも<b>発行元の村が削除／凍結されていれば</b>同じく 404 で秘匿する
     * （AC-6・ゾンビ号の直アクセス封鎖）。</p>
     */
    @Transactional(readOnly = true)
    public PublicNewsletterIssueResponse getPublicIssue(UUID issueId, Long userId) {
        VillageNewsletterIssueEntity issue = issueRepository.findById(issueId)
                .filter(i -> i.getDeletedAt() == null)
                .filter(i -> i.getVisibility() == VillageNewsletterVisibility.PUBLIC)
                .filter(i -> i.getStatus() == VillageNewsletterIssueStatus.PUBLISHED)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.NEWSLETTER_ISSUE_NOT_FOUND));
        // 発行元村が削除／凍結されていたら存在秘匿（AC-6）。checkVillageBulletinViewAccess と同じ生存判定クエリ。
        villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(issue.getVillageId())
                .orElseThrow(() -> new BusinessException(VillageErrorCode.NEWSLETTER_ISSUE_NOT_FOUND));
        return toPublic(issue);
    }

    // ==================================================================
    // 楽観ロック・マッパ
    // ==================================================================
    // 編集認可（現役 HEADMAN / ELDER 判定）は VillageBulletinAccessService#requireHeadmanOrElder へ集約した
    // （②-4 堅牢性 AC-15/16・設定系 VillageNewsletterService と重複していた private 実装を解消）。

    /** 編集用に号をロードし、楽観ロック（expectedVersion）を検証する。不一致は 409（設計書 §4.4）。 */
    private VillageNewsletterIssueEntity loadIssueForEdit(
            UUID villageId, UUID issueId, Long expectedVersion) {
        VillageNewsletterIssueEntity issue = issueRepository
                .findByIdAndVillageIdAndDeletedAtIsNull(issueId, villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.NEWSLETTER_ISSUE_NOT_FOUND));
        if (expectedVersion != null && !Objects.equals(issue.getVersion(), expectedVersion)) {
            throw new BusinessException(VillageErrorCode.NEWSLETTER_ISSUE_VERSION_CONFLICT);
        }
        return issue;
    }

    /** 一覧用サマリ。タグは呼び出し元が一括解決した結果（{@code tags}）を渡す（N+1 回避・AC-9）。 */
    private NewsletterIssueSummaryResponse toSummary(
            VillageNewsletterIssueEntity issue, List<NewsletterTagResponse> tags) {
        return NewsletterIssueSummaryResponse.builder()
                .id(issue.getId())
                .villageId(issue.getVillageId())
                .title(issue.getTitle())
                .frequency(issue.getFrequency())
                .status(issue.getStatus())
                .visibility(issue.getVisibility())
                .periodStart(issue.getPeriodStart())
                .periodEnd(issue.getPeriodEnd())
                .publishedAt(issue.getPublishedAt())
                .createdAt(issue.getCreatedAt())
                .digestPostCount(issue.getDigestPostCount())
                .digestNewMemberCount(issue.getDigestNewMemberCount())
                .hasComment(issue.getHeadmanComment() != null && !issue.getHeadmanComment().isBlank())
                .tags(tags)
                .build();
    }

    private NewsletterIssueDetailResponse toDetail(VillageNewsletterIssueEntity issue) {
        return NewsletterIssueDetailResponse.builder()
                .id(issue.getId())
                .villageId(issue.getVillageId())
                .title(issue.getTitle())
                .frequency(issue.getFrequency())
                .issueType(issue.getIssueType())
                .status(issue.getStatus())
                .visibility(issue.getVisibility())
                .periodStart(issue.getPeriodStart())
                .periodEnd(issue.getPeriodEnd())
                .aggregatedAt(issue.getAggregatedAt())
                .scheduledPublishAt(issue.getScheduledPublishAt())
                .publishedAt(issue.getPublishedAt())
                .digestPostCount(issue.getDigestPostCount())
                .digestNewMemberCount(issue.getDigestNewMemberCount())
                .digestFestivalCount(issue.getDigestFestivalCount())
                .digestMeetupCount(issue.getDigestMeetupCount())
                .digestRecruitCount(issue.getDigestRecruitCount())
                .digestTopic1Name(issue.getDigestTopic1Name())
                .digestTopic1Count(issue.getDigestTopic1Count())
                .digestTopic2Name(issue.getDigestTopic2Name())
                .digestTopic2Count(issue.getDigestTopic2Count())
                .digestTopic3Name(issue.getDigestTopic3Name())
                .digestTopic3Count(issue.getDigestTopic3Count())
                .headmanComment(issue.getHeadmanComment())
                .commentUpdatedBy(issue.getCommentUpdatedBy())
                .commentUpdatedAt(issue.getCommentUpdatedAt())
                .tags(resolveTags(issue.getId()))
                .version(issue.getVersion())
                .build();
    }

    /** 単票の公開 DTO（詳細取得）。タグは単票のため従来どおり号 ID で解決する。 */
    private PublicNewsletterIssueResponse toPublic(VillageNewsletterIssueEntity issue) {
        return toPublic(issue, resolveTags(issue.getId()));
    }

    /** 一覧用の公開 DTO。タグは呼び出し元が一括解決した結果（{@code tags}）を渡す（N+1 回避・AC-9）。 */
    private PublicNewsletterIssueResponse toPublic(
            VillageNewsletterIssueEntity issue, List<NewsletterTagResponse> tags) {
        return PublicNewsletterIssueResponse.builder()
                .id(issue.getId())
                .villageId(issue.getVillageId())
                .title(issue.getTitle())
                .frequency(issue.getFrequency())
                .publishedAt(issue.getPublishedAt())
                .digestPostCount(issue.getDigestPostCount())
                .digestNewMemberCount(issue.getDigestNewMemberCount())
                .digestFestivalCount(issue.getDigestFestivalCount())
                .digestMeetupCount(issue.getDigestMeetupCount())
                .digestRecruitCount(issue.getDigestRecruitCount())
                .digestTopic1Name(issue.getDigestTopic1Name())
                .digestTopic1Count(issue.getDigestTopic1Count())
                .digestTopic2Name(issue.getDigestTopic2Name())
                .digestTopic2Count(issue.getDigestTopic2Count())
                .digestTopic3Name(issue.getDigestTopic3Name())
                .digestTopic3Count(issue.getDigestTopic3Count())
                .headmanComment(issue.getHeadmanComment())
                .tags(tags)
                .build();
    }

    /** 号 ID から付与タグを解決する（中間表 → タグマスタ・論理削除タグは除外・表示順）。単票用。 */
    private List<NewsletterTagResponse> resolveTags(UUID issueId) {
        List<VillageNewsletterIssueTagEntity> links = issueTagRepository.findByIssueId(issueId);
        List<NewsletterTagResponse> tags = new ArrayList<>();
        for (VillageNewsletterIssueTagEntity link : links) {
            tagRepository.findById(link.getTagId())
                    .filter(t -> t.getDeletedAt() == null)
                    .ifPresent(t -> tags.add(toTagResponse(t)));
        }
        tags.sort((a, b) -> Integer.compare(
                a.sortOrder() == null ? 0 : a.sortOrder(),
                b.sortOrder() == null ? 0 : b.sortOrder()));
        return tags;
    }

    /**
     * ページ内の全号のタグを<b>一括解決</b>する（一覧の N+1 回避・②-4 堅牢性 AC-9〜12）。
     *
     * <p>号ごとに {@code findByIssueId}＋タグ毎 {@code findById} を発行していた従来実装（号 N 件で
     * 最悪 1+N+M 回）を、リンク 1 本＋タグ 1 本の計 2 クエリに畳み込む。号↔タグの対応は
     * {@code issueId} をキーにした Map で厳密に保つため取り違えは起きない（AC-11）。論理削除タグは
     * 除外し、各号のタグは表示順（{@code sortOrder}）で整列する。タグ 0 件の号は空リストになる（AC-12）。</p>
     *
     * @param issueIds ページに載る号 ID 群（空なら空 Map を返し {@code IN ()} を発行しない）
     * @return {@code issueId → 表示順に並んだタグ} の Map（タグ無しの号はキー自体が無い＝呼び出し側で空リスト補完）
     */
    private Map<UUID, List<NewsletterTagResponse>> resolveTagsForIssues(List<UUID> issueIds) {
        if (issueIds.isEmpty()) {
            return Map.of();
        }
        List<VillageNewsletterIssueTagEntity> links = issueTagRepository.findByIssueIdIn(issueIds);
        if (links.isEmpty()) {
            return Map.of();
        }
        Set<UUID> tagIds = links.stream()
                .map(VillageNewsletterIssueTagEntity::getTagId)
                .collect(Collectors.toSet());
        // 論理削除タグは findByIdInAndDeletedAtIsNull で除外済み。存在しないタグ ID は Map に載らない。
        Map<UUID, NewsletterTagResponse> tagById = tagRepository.findByIdInAndDeletedAtIsNull(tagIds).stream()
                .collect(Collectors.toMap(
                        VillageNewsletterTagEntity::getId,
                        VillageNewsletterIssueService::toTagResponse));
        Map<UUID, List<NewsletterTagResponse>> byIssue = new HashMap<>();
        for (VillageNewsletterIssueTagEntity link : links) {
            NewsletterTagResponse tag = tagById.get(link.getTagId());
            if (tag != null) {
                byIssue.computeIfAbsent(link.getIssueId(), k -> new ArrayList<>()).add(tag);
            }
        }
        byIssue.values().forEach(list -> list.sort((a, b) -> Integer.compare(
                a.sortOrder() == null ? 0 : a.sortOrder(),
                b.sortOrder() == null ? 0 : b.sortOrder())));
        return byIssue;
    }

    private static NewsletterTagResponse toTagResponse(VillageNewsletterTagEntity tag) {
        return NewsletterTagResponse.builder()
                .id(tag.getId())
                .villageId(tag.getVillageId())
                .name(tag.getName())
                .color(tag.getColor())
                .sortOrder(tag.getSortOrder())
                .version(tag.getVersion())
                .build();
    }

    /**
     * 号タイトルの既定値を生成する（村長が後から編集可）。i18n は不要（BE 内部既定文字列・設計書 §4.2）。
     */
    private String generateTitle(VillageNewsletterFrequency frequency, LocalDateTime periodStart) {
        if (frequency == VillageNewsletterFrequency.MONTHLY) {
            return String.format("%d年%02d月 村だより", periodStart.getYear(), periodStart.getMonthValue());
        }
        // WEEKLY: 期間開始日の週として表現する。
        return String.format("%d年%02d月%02d日週 村だより",
                periodStart.getYear(), periodStart.getMonthValue(), periodStart.getDayOfMonth());
    }

    /** TOP3 トピックの指定順位の名前を返す（無ければ null）。 */
    private static String topicName(List<Map.Entry<String, Integer>> top3, int index) {
        return index < top3.size() ? top3.get(index).getKey() : null;
    }

    /** TOP3 トピックの指定順位の件数を返す（無ければ 0）。 */
    private static Integer topicCount(List<Map.Entry<String, Integer>> top3, int index) {
        return index < top3.size() ? top3.get(index).getValue() : 0;
    }
}
