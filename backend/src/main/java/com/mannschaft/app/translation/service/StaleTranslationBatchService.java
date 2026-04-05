package com.mannschaft.app.translation.service;

import com.mannschaft.app.translation.TranslationStatus;
import com.mannschaft.app.translation.entity.ContentTranslationEntity;
import com.mannschaft.app.translation.repository.ContentTranslationQueryRepository;
import com.mannschaft.app.translation.repository.ContentTranslationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 陳腐化翻訳検知バッチサービス。
 * 毎日午前2時（JST）に実行し、原文が更新されたにも関わらず翻訳が古いままになっている
 * PUBLISHED状態の翻訳コンテンツを NEEDS_UPDATE に一括更新する。
 * イベント欠落時のリカバリ（日次バッチ補完）として機能する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaleTranslationBatchService {

    private final ContentTranslationQueryRepository contentTranslationQueryRepository;
    private final ContentTranslationRepository contentTranslationRepository;

    /**
     * 毎日午前2時（JST）に実行される陳腐化翻訳チェックバッチ。
     * ShedLock による分散ロックで多重起動を防止する。
     * <p>
     * 処理内容:
     * 1. QueryRepositoryで原文が更新された後に翻訳が古いままのPUBLISHEDレコードを取得
     * 2. 対象レコードのステータスをNEEDS_UPDATEに一括更新
     * 3. 更新件数をINFOログに出力
     * </p>
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "stale_translation_check", lockAtMostFor = "PT15M")
    @Transactional
    public void runStaleCheck() {
        log.info("陳腐化翻訳チェックバッチ開始");

        // 原文更新後に翻訳が古いままのPUBLISHEDレコードIDを取得
        List<Long> staleIds =
                contentTranslationQueryRepository.findPublishedTranslationsOlderThanSourceUpdatedAt();

        int updatedCount = 0;
        for (Long id : staleIds) {
            try {
                ContentTranslationEntity entity = contentTranslationRepository.findById(id)
                        .orElse(null);
                if (entity == null) {
                    // 取得時から削除されたレコードはスキップ
                    continue;
                }
                entity.updateStatus(TranslationStatus.NEEDS_UPDATE.name());
                contentTranslationRepository.save(entity);
                updatedCount++;
            } catch (Exception e) {
                log.warn("陳腐化翻訳ステータス更新失敗: translationId={}", id, e);
            }
        }

        log.info("陳腐化翻訳チェックバッチ完了: NEEDS_UPDATE更新件数={}", updatedCount);
    }
}
