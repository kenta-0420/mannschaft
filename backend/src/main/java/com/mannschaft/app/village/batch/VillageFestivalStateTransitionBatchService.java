package com.mannschaft.app.village.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.village.entity.VillageFestivalEntity;
import com.mannschaft.app.village.entity.enums.VillageEventNotificationType;
import com.mannschaft.app.village.entity.enums.VillageFestivalStatus;
import com.mannschaft.app.village.repository.VillageFestivalRepository;
import com.mannschaft.app.village.service.VillageEventArchiveService;
import com.mannschaft.app.village.service.VillageEventFeedRefluxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F17.1 Phase 2 U5 — 村お祭り状態自動遷移バッチ（設計書 §2.2 / §13.2）。
 *
 * <p>15 分ごとに以下を実行する:</p>
 * <ul>
 *   <li>{@code SCHEDULED} の祭りで {@code starts_at <= now} のものを {@code ACTIVE} に遷移</li>
 *   <li>{@code ACTIVE} の祭りで {@code ends_at <= now} のものを {@code ENDED} に遷移</li>
 * </ul>
 *
 * <p>{@code CANCELLED} / {@code ENDED} および {@code deleted_at IS NOT NULL} のレコードは遷移対象外。</p>
 *
 * <h2>アーキテクチャ原則</h2>
 * <ul>
 *   <li>原則5: 本バッチは village ドメイン内に閉じる。AuditLogService は非同期 @Async の
 *       監査ログ専用で副作用書き込みなし。</li>
 *   <li>ShedLock により複数インスタンス起動時の二重実行を防ぐ。</li>
 *   <li>1 件失敗しても次のお祭りは続行する（@Transactional は 1 件単位の更新メソッドで限定）。</li>
 * </ul>
 *
 * <h2>タイムゾーン</h2>
 * <p>Phase 2 は UTC 固定。村ローカル TZ 対応は Phase 3 へ繰越。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageFestivalStateTransitionBatchService {

    private final VillageFestivalRepository festivalRepository;
    private final AuditLogService auditLogService;
    /** F17.2 Wave2 ①: 祭 ACTIVE 化時の FESTIVAL_STARTED 自動投稿・通知（設計書 §3.3.1）。 */
    private final VillageEventFeedRefluxService refluxService;
    /** F17.2 Wave2 ③: 祭 ENDED 時の村史（行事アーカイブ）自動編纂（設計書 §5.5）。 */
    private final VillageEventArchiveService eventArchiveService;

    /**
     * 15 分ごとに状態遷移を実行する。
     *
     * <p>cron 表現 {@code "0 *\/15 * * * *"} は毎時 0/15/30/45 分の 0 秒に発火。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。村のお祭りの状態遷移であり、再開後の実行で現在時刻に応じた状態へ収束する。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "village-festival-state-transition", description = "村のお祭り SCHEDULED→ACTIVE→ENDED 状態遷移を 15 分毎に処理する")
    @Scheduled(cron = "0 */15 * * * *", zone = "UTC")
    @SchedulerLock(
            name = "villageFestivalStateTransitionBatch",
            lockAtLeastFor = "PT30S",
            lockAtMostFor = "PT30M")
    public void runBatch() {
        LocalDateTime now = LocalDateTime.now();
        log.info("村お祭り状態遷移バッチ開始: now={}", now);

        int activated = 0;
        int ended = 0;
        int failed = 0;

        // SCHEDULED → ACTIVE
        List<VillageFestivalEntity> scheduled =
                festivalRepository.findByStatusAndDeletedAtIsNull(VillageFestivalStatus.SCHEDULED);
        for (VillageFestivalEntity f : scheduled) {
            if (f.getStartsAt() == null || f.getStartsAt().isAfter(now)) {
                continue;
            }
            boolean transitioned = false;
            try {
                transitionToActive(f);
                activated++;
                transitioned = true;
            } catch (Exception e) {
                failed++;
                log.error("お祭り ACTIVE 遷移失敗: festivalId={}", f.getId(), e);
            }
            // §3.3.1: 状態遷移のコミット後（メソッド戻り後）に副作用を発火。失敗しても状態は巻き戻らない。
            // refluxService.publish は内部で best-effort（例外を外へ出さない）。
            if (transitioned) {
                refluxService.publish(f.getVillageId(), VillageEventNotificationType.FESTIVAL_STARTED, f.getId(),
                        f.getTitle(), "/villages/" + f.getVillageId() + "/festivals/" + f.getId());
            }
        }

        // ACTIVE → ENDED
        List<VillageFestivalEntity> active =
                festivalRepository.findByStatusAndDeletedAtIsNull(VillageFestivalStatus.ACTIVE);
        for (VillageFestivalEntity f : active) {
            if (f.getEndsAt() == null || f.getEndsAt().isAfter(now)) {
                continue;
            }
            boolean transitioned = false;
            try {
                transitionToEnded(f);
                ended++;
                transitioned = true;
            } catch (Exception e) {
                failed++;
                log.error("お祭り ENDED 遷移失敗: festivalId={}", f.getId(), e);
            }
            // §5.5: ENDED コミット後に村史編纂。編纂失敗は握って次の祭へ継続（状態は既に確定・AC-17b）。
            if (transitioned) {
                try {
                    eventArchiveService.archiveFestival(f);
                } catch (Exception e) {
                    log.error("祭の村史編纂失敗（祭は ENDED 確定済み・継続）: festivalId={}", f.getId(), e);
                }
            }
        }

        log.info("村お祭り状態遷移バッチ完了: ACTIVE化={} ENDED化={} 失敗={}", activated, ended, failed);
    }

    /**
     * 個別の SCHEDULED → ACTIVE 遷移（テストから直接呼べるよう package-private）。
     */
    @Transactional
    public void transitionToActive(VillageFestivalEntity entity) {
        entity.setStatus(VillageFestivalStatus.ACTIVE);
        festivalRepository.save(entity);
        auditLogService.record(
                AuditEventType.VILLAGE_FESTIVAL_ACTIVATED.name(),
                null, null, null, null,
                null, null, null,
                "{\"villageId\":\"" + entity.getVillageId()
                        + "\",\"festivalId\":\"" + entity.getId()
                        + "\",\"transitionedAt\":\"" + LocalDateTime.now() + "\"}"
        );
        log.info("お祭り ACTIVE 化: villageId={} festivalId={}", entity.getVillageId(), entity.getId());
    }

    /**
     * 個別の ACTIVE → ENDED 遷移（テストから直接呼べるよう package-private）。
     */
    @Transactional
    public void transitionToEnded(VillageFestivalEntity entity) {
        entity.setStatus(VillageFestivalStatus.ENDED);
        festivalRepository.save(entity);
        auditLogService.record(
                AuditEventType.VILLAGE_FESTIVAL_ENDED.name(),
                null, null, null, null,
                null, null, null,
                "{\"villageId\":\"" + entity.getVillageId()
                        + "\",\"festivalId\":\"" + entity.getId()
                        + "\",\"transitionedAt\":\"" + LocalDateTime.now() + "\"}"
        );
        log.info("お祭り ENDED 化: villageId={} festivalId={}", entity.getVillageId(), entity.getId());
    }
}
