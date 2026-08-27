package com.mannschaft.app.village.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageLobbyDailyThreadEntity;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.service.VillageLobbyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * F17.1 Phase 1 B11 — 井戸端会議「本日の日次スレッド」自動生成バッチ。
 *
 * <p>毎日 UTC 00:00 に全村のロビーチャネル配下に当日分の
 * {@link VillageLobbyDailyThreadEntity} を 1 件ずつ用意する。
 * 既存行があれば {@link VillageLobbyService#ensureDailyThread} が冪等にスキップする。</p>
 *
 * <p>アーキテクチャ原則:</p>
 * <ul>
 *   <li>原則5: {@code @Transactional} は {@link VillageLobbyService#ensureDailyThread}
 *       に閉じるため、本バッチ側は無トランザクションで for ループする
 *       （1 村失敗しても次の村は続行する）。</li>
 *   <li>ShedLock により複数インスタンス起動時の二重実行を防ぐ。</li>
 * </ul>
 *
 * <p>スケール対応 (TODO Phase 2+):</p>
 * <ul>
 *   <li>村数が増えると {@link VillageRepository#findAll} が重くなる。
 *       1000 村を超えるあたりで「アクティブ村のみページネーション取得」に切り替える。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageLobbyDailyThreadBatchService {

    private final VillageRepository villageRepository;
    private final VillageLobbyService villageLobbyService;
    private final AuditLogService auditLogService;

    /**
     * 毎日 UTC 00:00 に全村のロビー日次スレッドを生成する。
     *
     * <p>UTC 0 時を採用しているのは「村は組織横断」ゆえ地域タイムゾーンに合わせると
     * 不公平が生じるため。Phase 2 で村ごとのタイムゾーン設定が入れば切替予定。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。村ロビーの日次スレッド生成であり、冪等に生成される。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "village-lobby-daily-thread", description = "全村のロビー日次スレッドを毎日 UTC 00:00 に冪等生成する")
    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    @SchedulerLock(
            name = "villageLobbyDailyThreadBatch",
            lockAtLeastFor = "PT1M",
            lockAtMostFor = "PT30M")
    public void runBatch() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        log.info("井戸端会議日次スレッドバッチ開始: date={}", today);

        int created = 0;
        int skipped = 0;
        int failed = 0;
        int totalVillages = 0;

        final int CHUNK_SIZE = 500;
        Pageable pageable = PageRequest.of(0, CHUNK_SIZE);
        Page<VillageEntity> page;
        do {
            page = villageRepository.findByDeletedAtIsNullAndArchivedAtIsNull(pageable);
            for (VillageEntity village : page.getContent()) {
                totalVillages++;
                try {
                    boolean existed = villageLobbyService.findDailyThread(village.getId(), today).isPresent();
                    VillageLobbyDailyThreadEntity thread =
                            villageLobbyService.ensureDailyThread(village.getId(), today);
                    if (existed) {
                        skipped++;
                    } else {
                        created++;
                        auditLogService.record(
                                AuditEventType.VILLAGE_LOBBY_THREAD_CREATED.name(),
                                null, null, null, null,
                                null, null, null,
                                "{\"villageId\":\"" + village.getId()
                                        + "\",\"threadId\":\"" + thread.getId()
                                        + "\",\"date\":\"" + today + "\"}"
                        );
                    }
                } catch (Exception e) {
                    failed++;
                    log.error("井戸端日次スレッド生成失敗: villageId={} date={}", village.getId(), today, e);
                }
            }
            pageable = pageable.next();
        } while (page.hasNext());

        log.info("井戸端会議日次スレッドバッチ完了: date={} 総村数={} 作成={} スキップ={} 失敗={}",
                today, totalVillages, created, skipped, failed);
    }
}
