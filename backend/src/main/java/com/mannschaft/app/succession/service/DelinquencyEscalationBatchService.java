package com.mannschaft.app.succession.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.succession.entity.DelinquencyEscalationEntity;
import com.mannschaft.app.succession.entity.DelinquencyEscalationStage;
import com.mannschaft.app.succession.repository.DelinquencyEscalationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.mannschaft.app.succession.entity.DelinquencyEscalationStage.STAGE_1_REMINDER;
import static com.mannschaft.app.succession.entity.DelinquencyEscalationStage.STAGE_2_EMERGENCY_CONTACT;
import static com.mannschaft.app.succession.entity.DelinquencyEscalationStage.STAGE_3_WATCHER_VISIT;
import static com.mannschaft.app.succession.entity.DelinquencyEscalationStage.STAGE_4_DEATH_SUSPECTED;
import static com.mannschaft.app.succession.entity.DelinquencyEscalationStage.STAGE_5_LEGAL_PREP;

/**
 * 5 段階エスカレーション日次バッチサービス（F09.15 S5-A）。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §7.4
 *
 * <p>毎日 02:00 JST に起動し、未解決・非凍結のエスカレーションを
 * 滞納開始日からの経過日数に基づいて自動昇格する。
 *
 * <p>昇格閾値:
 * <ul>
 *   <li>D+30  → STAGE_1_REMINDER</li>
 *   <li>D+60  → STAGE_2_EMERGENCY_CONTACT</li>
 *   <li>D+90  → STAGE_3_WATCHER_VISIT</li>
 *   <li>D+120 → STAGE_4_DEATH_SUSPECTED</li>
 *   <li>D+150 → STAGE_5_LEGAL_PREP（以降自動昇格なし）</li>
 * </ul>
 *
 * <p>冪等設計: 同日に複数回実行されても現在ステージ &lt; 必要ステージの場合のみ昇格する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DelinquencyEscalationBatchService {

    private final DelinquencyEscalationRepository escalationRepository;
    private final DelinquencyEscalationAdvanceRunner advanceRunner;

    /**
     * 日次バッチ: 経過日数に応じてエスカレーションを自動昇格する。
     *
     * <p>処理対象: {@code resolved_at IS NULL AND frozen_at IS NULL} の全エスカレーション。
     * バッチ失敗時のリトライ安全性を確保するため、各昇格は個別トランザクションで実行する
     * （{@link DelinquencyEscalationAdvanceRunner} を {@code REQUIRES_NEW} で経由する）。
     * 本メソッド自体は読み取りのみのため {@code @Transactional} を付けない。
     *
     * <p>1 件の処理は昇格要否の判定を含めて全体を捕捉する。ステージ文字列の解釈は
     * 判定段階で行われるため、ここを保護しないと 1 行の異常データでバッチ全体が停止する。
     */
    @BatchEndpoint(name = "succession-delinquency-escalation-daily", description = "滞納エスカレーションを毎日 02:00 に経過日数で 5 段階自動昇格する")
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Tokyo")
    // 起動間隔は日次 02:00。滞納契約の段階昇格と通知送出で、契約数に比例する。余裕を取り 1 時間を上限とする。
    @SchedulerLock(name = "successionDelinquencyEscalationDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT1H")
    public void advanceEscalations() {
        List<DelinquencyEscalationEntity> actives =
                escalationRepository.findByResolvedAtIsNullAndFrozenAtIsNullAndDeletedAtIsNull();

        log.info("エスカレーション日次バッチ開始: 対象件数={}", actives.size());

        LocalDate today = LocalDate.now();
        int advancedCount = 0;

        for (DelinquencyEscalationEntity e : actives) {
            // 昇格要否の判定自体も try の内側に置く。DB のステージ文字列が不正な場合に
            // shouldAdvance が例外を投げるため、外に出すと 1 行の異常データでバッチ全体が停止する。
            try {
                long daysElapsed = ChronoUnit.DAYS.between(e.getDelinquencyStartedAt(), today);
                DelinquencyEscalationStage requiredStage = determineRequiredStage(daysElapsed);

                if (shouldAdvance(e.getCurrentStage(), requiredStage)) {
                    // 別トランザクション（REQUIRES_NEW）で実行するため、このループで
                    // 取得済みのエンティティは使わず ID で再フェッチさせる。
                    advanceRunner.advanceStage(e.getId(), e.getOrganizationId());
                    advancedCount++;
                    log.info("エスカレーション自動昇格: id={}, currentStage={}, daysElapsed={}",
                            e.getId(), e.getCurrentStage(), daysElapsed);
                }
            } catch (Exception ex) {
                // 個別エスカレーションの失敗でバッチ全体を停止しない
                log.error("エスカレーション昇格失敗 (id={}): {}", e.getId(), ex.getMessage(), ex);
            }
        }

        log.info("エスカレーション日次バッチ完了: 昇格件数={}", advancedCount);
    }

    /**
     * 経過日数から到達すべきステージを決定する。
     *
     * @param days 滞納開始日からの経過日数
     * @return 到達すべきステージ（30 日未満は null = まだ起動しない）
     */
    DelinquencyEscalationStage determineRequiredStage(long days) {
        if (days >= 150) return STAGE_5_LEGAL_PREP;
        if (days >= 120) return STAGE_4_DEATH_SUSPECTED;
        if (days >= 90)  return STAGE_3_WATCHER_VISIT;
        if (days >= 60)  return STAGE_2_EMERGENCY_CONTACT;
        if (days >= 30)  return STAGE_1_REMINDER;
        return null; // 30 日未満はエスカレーション対象外
    }

    /**
     * 現在ステージが必要ステージより低い場合に昇格が必要と判定する（冪等保証）。
     *
     * @param currentStageStr 現在のステージ文字列（DB 保存値）
     * @param requiredStage   到達すべきステージ（null の場合は昇格不要）
     * @return 昇格が必要な場合 true
     */
    boolean shouldAdvance(String currentStageStr, DelinquencyEscalationStage requiredStage) {
        if (requiredStage == null) {
            return false;
        }
        DelinquencyEscalationStage currentStage =
                DelinquencyEscalationStage.fromString(currentStageStr);
        return currentStage.ordinal() < requiredStage.ordinal();
    }
}
