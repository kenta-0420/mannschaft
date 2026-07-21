package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.timeline.service.TimelinePostService;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F17.2 Wave2 ③/⑦ 村史（行事アーカイブ）編纂サービス（設計書 §5.5・§7）。
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageEventArchiveService {

    private final VillageEventArchiveRepository archiveRepository;
    private final VillageFestivalRsvpRepository rsvpRepository;
    private final VillageFestivalLivePostRepository livePostRepository;
    private final TimelinePostService timelinePostService;
    private final AuditLogService auditLogService;

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
}
