package com.mannschaft.app.village.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.service.VillageChronicleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;

/**
 * F17.1 Phase 3-β — 村史（月次ダイジェスト）月次生成バッチ。
 *
 * <p>毎月 1 日 03:00 JST に、全村について前月分の村史を生成（UPSERT）する。
 * ShedLock により複数インスタンス起動時の二重実行を防ぐ。</p>
 *
 * <h2>アーキテクチャ原則</h2>
 * <ul>
 *   <li>原則5: バッチ自体は villages を順に列挙して
 *       {@link VillageChronicleService#generateForVillage} を呼ぶだけで、
 *       Service 側 1 メソッド = 1 村 = 1 トランザクション。1 件失敗しても次に進む。</li>
 *   <li>ShedLock の lockAtMostFor は 60 分を設定。10000 村程度までは想定内。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageChronicleBatchService {

    private final VillageRepository villageRepository;
    private final VillageChronicleService chronicleService;

    /**
     * 月初 03:00 JST に前月分を生成する。
     *
     * <p>cron 表現: {@code "0 0 3 1 * *"} — 毎月 1 日の 03:00:00 に発火（JST）。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "前月固定の no-arg 入口しか無く、対象月を指定して再実行する運用経路が無い。止めた月の村史は二度と生成できない")
    @BatchEndpoint(name = "village-chronicle-monthly", description = "全村の前月分村史を毎月 1 日 03:00 に生成する")
    @Scheduled(cron = "0 0 3 1 * *", zone = "Asia/Tokyo")
    @SchedulerLock(
            name = "villageChronicleMonthlyBatch",
            lockAtLeastFor = "PT1M",
            lockAtMostFor = "PT60M")
    public void runMonthlyBatch() {
        LocalDate previousMonth = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        log.info("村史月次生成バッチ開始: 対象月={}", previousMonth);

        int generated = 0;
        int failed = 0;

        final int CHUNK_SIZE = 500;
        Pageable pageable = PageRequest.of(0, CHUNK_SIZE);
        Page<VillageEntity> page;
        do {
            page = villageRepository.findByDeletedAtIsNull(pageable);
            for (VillageEntity v : page.getContent()) {
                try {
                    chronicleService.generateForVillage(v.getId(), previousMonth);
                    generated++;
                } catch (Exception e) {
                    failed++;
                    log.error("村史生成失敗: villageId={} yearMonth={}", v.getId(), previousMonth, e);
                }
            }
            pageable = pageable.next();
        } while (page.hasNext());

        log.info("村史月次生成バッチ完了: 生成={} 失敗={} 対象月={}",
                generated, failed, previousMonth);
    }
}
