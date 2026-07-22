package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.timeline.service.TimelinePostService;
import com.mannschaft.app.village.dto.VillageEventArchiveResponse;
import com.mannschaft.app.village.entity.VillageEventArchiveEntity;
import com.mannschaft.app.village.entity.VillageFestivalEntity;
import com.mannschaft.app.village.entity.VillageFestivalLivePostEntity;
import com.mannschaft.app.village.entity.enums.VillageEventArchiveSourceType;
import com.mannschaft.app.village.entity.enums.VillageFestivalRsvpStatus;
import com.mannschaft.app.village.repository.VillageEventArchiveRepository;
import com.mannschaft.app.village.repository.VillageFestivalLivePostRepository;
import com.mannschaft.app.village.repository.VillageFestivalRsvpRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F17.2 Wave2 ③/⑦ 村史（行事アーカイブ）編纂・読み出しサービス（設計書 §5.5・§7）。
 *
 * <p>祭が ENDED に遷移したときに、祭の基本情報＋RSVP 集計＋実況投稿の集約を
 * {@link VillageEventArchiveEntity} へ<b>スナップショット確定</b>する。編纂は
 * {@code (source_type, source_id)} の UNIQUE により冪等（二重編纂防止）。</p>
 *
 * <h2>分離（原則5・設計書 §5.5）</h2>
 * <p>本サービスは祭の状態遷移トランザクションの<b>外</b>（{@code runBatch} のループ本体で
 * {@code transitionToEnded} のコミット後）から呼ばれる。編纂の失敗が ENDED 遷移を巻き戻さないよう、
 * 呼び出し側（バッチ）が try/catch で囲んで次の祭へ継続する（AC-17b）。本メソッド自身は
 * 「既に編纂済み」「UNIQUE 競合」のみ握って冪等 return し、それ以外の異常は呼び出し側へ伝播させる。</p>
 *
 * <h2>読み出し（Wave2 追補・設計書 §7.4/§13.5）</h2>
 * <p>{@link #listArchives} は村史タブ（村人向け read-only）を提供する。編纂（write）は
 * バッチ専用で村人による書き込みは無いため、読み出しのみ村掲示板と同一の閲覧認可
 * （{@link VillageBulletinAccessService}）を要求する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageEventArchiveService {

    /** 一覧の既定ページサイズ（設計書 §13.5: 既定20・archived_at 降順）。 */
    private static final int DEFAULT_LIST_PAGE_SIZE = 20;

    /** 一覧の最大ページサイズ（全件一括返却の禁止・G3 整合）。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final VillageEventArchiveRepository archiveRepository;
    private final VillageFestivalRsvpRepository rsvpRepository;
    private final VillageFestivalLivePostRepository livePostRepository;
    private final TimelinePostService timelinePostService;
    private final AuditLogService auditLogService;
    private final VillageBulletinAccessService bulletinAccessService;
    private final MediaUrlResolver mediaUrlResolver;

    /**
     * 祭を村史（行事アーカイブ）へ編纂する（ENDED 遷移後・冪等）。
     *
     * @param festival ENDED へ遷移済みの祭エンティティ
     */
    @Transactional
    public void archiveFestival(VillageFestivalEntity festival) {
        UUID festivalId = festival.getId();
        UUID villageId = festival.getVillageId();

        // 冪等: 既に編纂済みなら何もしない。
        if (archiveRepository.findBySourceTypeAndSourceId(
                VillageEventArchiveSourceType.FESTIVAL, festivalId).isPresent()) {
            return;
        }

        long going = rsvpRepository.countByFestivalIdAndStatus(festivalId, VillageFestivalRsvpStatus.GOING);
        long maybe = rsvpRepository.countByFestivalIdAndStatus(festivalId, VillageFestivalRsvpStatus.MAYBE);
        long total = rsvpRepository.countByFestivalId(festivalId);

        // 実況投稿は timeline 側 deleted_at 済みを除外して数える（AC-17c）。
        List<VillageFestivalLivePostEntity> links = livePostRepository.findByFestivalId(festivalId);
        Set<Long> aliveIds = timelinePostService.filterAliveVillagePostIds(
                links.stream().map(VillageFestivalLivePostEntity::getTimelinePostId).toList(), villageId);
        long liveCount = aliveIds.size();

        String summary = "参加表明 合計=" + total + "（GOING=" + going + " / MAYBE=" + maybe + "）"
                + " ／ 実況=" + liveCount + "件";

        VillageEventArchiveEntity archive = VillageEventArchiveEntity.builder()
                .villageId(villageId)
                .sourceType(VillageEventArchiveSourceType.FESTIVAL)
                .sourceId(festivalId)
                .title(festival.getTitle())
                .summary(summary)
                .thumbnailR2Key(festival.getBannerR2Key())
                .archivedAt(LocalDateTime.now())
                .build();
        try {
            archiveRepository.saveAndFlush(archive);
        } catch (DataIntegrityViolationException dup) {
            // UNIQUE(source_type, source_id) の並行編纂競合。冪等に握って return（二重編纂防止）。
            log.info("村史編纂の並行競合を検出（冪等 skip）: festivalId={}", festivalId);
            return;
        }

        auditLogService.record(
                AuditEventType.VILLAGE_FESTIVAL_ARCHIVED.name(),
                null, null, null, null, null, null, null,
                "{\"villageId\":\"" + villageId + "\",\"festivalId\":\"" + festivalId
                        + "\",\"rsvpTotal\":" + total + ",\"livePosts\":" + liveCount + "}");
        log.info("祭を村史へ編纂: villageId={} festivalId={} rsvpTotal={} livePosts={}",
                villageId, festivalId, total, liveCount);
    }

    // ====================================================================
    // F17.2 Wave2 追補 — 村史一覧（読み出し・設計書 §7.4/§13.5）
    // ====================================================================

    /**
     * 村史（行事アーカイブ）一覧を取得する（{@code archived_at} 降順・ページング必須）。
     *
     * <p>認可は<b>村掲示板と同一の閲覧認可</b>に従う（設計書 §7.4「村人（閲覧・掲示板と同一の
     * 閲覧認可）」）。{@link VillageBulletinAccessService#checkVillageBulletinViewAccess} へ委譲し、
     * 村が存在しない／削除済み／凍結済みは {@code VILLAGE_NOT_FOUND}（404、IDOR 対策で統一）、
     * {@code MEMBERS_ONLY} 村への非メンバーアクセスは {@code VILLAGE_BULLETIN_VIEW_FORBIDDEN}
     * （403）で拒否する。認可ガードは public 入口である本メソッドに置く（Controller は素通し）。</p>
     *
     * @param villageId   村 ID
     * @param sourceType  元行事の種別で絞り込む（null なら全種別）
     * @param actorUserId 閲覧しようとするログイン済ユーザー ID
     * @param pageable    ページング指定（size は {@value #MAX_PAGE_SIZE} で上限クランプ）
     * @throws com.mannschaft.app.common.BusinessException 村が存在しない（404）／掲示板の閲覧権限が無い（403）
     */
    @Transactional(readOnly = true)
    public List<VillageEventArchiveResponse> listArchives(UUID villageId, VillageEventArchiveSourceType sourceType,
                                                           Long actorUserId, Pageable pageable) {
        bulletinAccessService.checkVillageBulletinViewAccess(villageId, actorUserId);
        Pageable resolved = resolvePageable(pageable);
        Page<VillageEventArchiveEntity> page = (sourceType == null)
                ? archiveRepository.findByVillageIdAndDeletedAtIsNullOrderByArchivedAtDesc(villageId, resolved)
                : archiveRepository.findByVillageIdAndSourceTypeAndDeletedAtIsNullOrderByArchivedAtDesc(
                        villageId, sourceType, resolved);
        List<VillageEventArchiveEntity> content = page.getContent();

        // 一覧では同一サムネイルキー（祭バナー使い回し）が複数行に現れうるため、
        // 行ごとに resolve() を個別に呼ばず resolveAll() で一括解決してメモ化する（N+1 防止）。
        Map<String, String> thumbnailUrlsByKey = mediaUrlResolver.resolveAll(
                content.stream().map(VillageEventArchiveEntity::getThumbnailR2Key).toList());

        return content.stream()
                .map(a -> VillageEventArchiveResponse.of(a, thumbnailUrlsByKey.get(a.getThumbnailR2Key())))
                .toList();
    }

    /** ページサイズを {@value #MAX_PAGE_SIZE} で上限クランプする（全件一括返却の禁止・G3 整合）。 */
    private Pageable resolvePageable(Pageable pageable) {
        if (pageable == null) {
            return PageRequest.of(0, DEFAULT_LIST_PAGE_SIZE);
        }
        int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        return PageRequest.of(Math.max(pageable.getPageNumber(), 0), size <= 0 ? DEFAULT_LIST_PAGE_SIZE : size);
    }
}
