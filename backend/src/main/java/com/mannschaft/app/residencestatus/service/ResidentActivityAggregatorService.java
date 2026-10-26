package com.mannschaft.app.residencestatus.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.residencestatus.ResidenceStatusErrorCode;
import com.mannschaft.app.residencestatus.dto.ActivitySnapshotDto;
import com.mannschaft.app.residencestatus.dto.ResidenceStatusDashboardDto;
import com.mannschaft.app.residencestatus.entity.AnnualReview;
import com.mannschaft.app.residencestatus.entity.ResidentActivitySnapshot;
import com.mannschaft.app.residencestatus.event.ResidentActivityUpdatedEvent;
import com.mannschaft.app.residencestatus.repository.AnnualReviewRepository;
import com.mannschaft.app.residencestatus.repository.ResidentActivitySnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 居住者アクティビティ集計・推定スコア・ダッシュボードサービス（F09.16 S3-B）。
 *
 * <p>日次バッチから呼ばれる UPSERT、ADMIN/WATCHER 向けスナップショット取得、
 * ADMIN/DEPUTY_ADMIN 向けダッシュボード集計を提供する。
 *
 * <p>設計書: {@code docs/features/F09.16_residence_status_monitoring.md} §7
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResidentActivityAggregatorService {

    /**
     * v1 リスクスコア閾値: ハイリスク（40 以上）。
     * computeScore() の上限が 50 のため、v1 では 40+ をハイリスクとする。
     */
    static final int HIGH_RISK_THRESHOLD = 40;

    /**
     * v1 リスクスコア閾値: ミドルリスク（20 以上）。
     * 0-19 = LOW_RISK、20-39 = MID_RISK、40-50 = HIGH_RISK。
     */
    static final int MID_RISK_THRESHOLD = 20;

    /** 30 日無ログイン判定: score = 0 をプレースホルダとして使用（v1 判定基準） */
    static final int UNRESPONSIVE_SCORE = 0;

    /** v1 スコア上限（inactiveDays * 2 の最大値） */
    private static final int MAX_SCORE = 50;

    /** v1 スコア算定: スナップショット未取得時の inactiveDays デフォルト値 */
    private static final int DEFAULT_INACTIVE_DAYS = 30;

    /**
     * 30 日ローテバッチの 1 回あたり UPDATE 件数上限（Issue #2601）。
     * ID をアプリ層に持ち上げず、DB 側の {@code LIMIT} 付き一括 UPDATE をこの件数ずつ繰り返す。
     */
    static final int SNAPSHOT_DELETE_BATCH_SIZE = 1000;

    /**
     * 30 日ローテバッチの最大ループ回数（暴走防止の安全弁）。
     * {@link #SNAPSHOT_DELETE_BATCH_SIZE} との積が 1 回の起動で処理しうる上限件数となる。
     */
    private static final int SNAPSHOT_DELETE_MAX_LOOPS = 2000;

    private final ResidentActivitySnapshotRepository snapshotRepo;
    private final AnnualReviewRepository annualReviewRepo;
    private final AccessControlService accessControlService;
    private final ApplicationEventPublisher eventPublisher;
    private final ResidentActivitySnapshotBatchDeleter batchDeleter;

    // ─────────────────────────────────────────────────────────────────
    // 日次 UPSERT
    // ─────────────────────────────────────────────────────────────────

    /**
     * 日次集計: 特定居住者の当日スナップショットを UPSERT する。
     *
     * <p>既存レコードがあれば score / breakdownJson を更新する。
     * なければ新規 INSERT する。
     * 処理完了後に {@link ResidentActivityUpdatedEvent} を発火する。
     *
     * <p>バッチから呼ばれる。
     *
     * <p>TODO: 現バージョンは各ドメインのアクティビティイベントを購読して
     *     スコアを加算する方式は未実装（将来リファクタ予定）。
     *     @Transactional 越境を避けるため、バッチが集計済み値を直接渡す設計とした。
     *
     * @param organizationId    テナント ID
     * @param dwellingUnitId    居室 ID（弱参照）
     * @param residentRegistryId 居住者台帳 ID（弱参照）
     * @param subjectUserId     集計対象ユーザー ID（弱参照）
     * @param snapshotDate      集計対象日
     * @param scoreTotal        合計アクティビティスコア
     * @param breakdownJson     スコア内訳 JSON 文字列
     */
    @Transactional
    public void upsertDailySnapshot(Long organizationId, Long dwellingUnitId,
                                    Long residentRegistryId, Long subjectUserId,
                                    LocalDate snapshotDate, int scoreTotal,
                                    String breakdownJson) {
        ResidentActivitySnapshot snapshot = snapshotRepo
                .findBySubjectUserIdAndSnapshotDateAndDeletedAtIsNull(subjectUserId, snapshotDate)
                .map(existing -> {
                    existing.setActivityScoreTotal(scoreTotal);
                    existing.setActivityBreakdownJson(breakdownJson);
                    return existing;
                })
                .orElseGet(() -> ResidentActivitySnapshot.builder()
                        .organizationId(organizationId)
                        .dwellingUnitId(dwellingUnitId)
                        .residentRegistryId(residentRegistryId)
                        .subjectUserId(subjectUserId)
                        .snapshotDate(snapshotDate)
                        .activityScoreTotal(scoreTotal)
                        .activityBreakdownJson(breakdownJson)
                        .build());

        snapshotRepo.save(snapshot);
        log.debug("[ActivityAggregator] snapshot UPSERT 完了: registryId={} date={} score={}",
                residentRegistryId, snapshotDate, scoreTotal);

        eventPublisher.publishEvent(
                new ResidentActivityUpdatedEvent(this, organizationId, residentRegistryId, scoreTotal));
    }

    // ─────────────────────────────────────────────────────────────────
    // スナップショット取得（ADMIN / WATCHER のみ）
    // ─────────────────────────────────────────────────────────────────

    /**
     * 居住者のスナップショット一覧を取得する（直近 30 件、新しい順）。
     *
     * <p>権限要件:
     * <ul>
     *   <li>ADMIN / DEPUTY_ADMIN のみ閲覧可</li>
     *   <li>本人アクセス禁止（requestUserId が対象者の subjectUserId と一致する場合は 403）</li>
     * </ul>
     *
     * <p>クロスドメイン参照を避けるため、本人チェックは snapshotRepo の subjectUserId を使用する。
     *
     * @param organizationId      テナント ID
     * @param residentRegistryId  居住者台帳 ID
     * @param requestUserId       リクエストユーザー ID
     * @return アクティビティスナップショット DTO 一覧
     * @throws BusinessException SNAPSHOT_ACCESS_FORBIDDEN: 権限不足
     * @throws BusinessException SNAPSHOT_SELF_ACCESS_FORBIDDEN: 本人アクセス
     */
    public List<ActivitySnapshotDto> getSnapshots(Long organizationId, Long residentRegistryId,
                                                   Long requestUserId) {
        // 権限確認: ADMIN/DEPUTY_ADMIN のみ許可
        checkSnapshotAccessPermission(requestUserId, organizationId);

        // 本人アクセス禁止チェック:
        // 対象居住者の最新スナップショットから subjectUserId を特定して比較する
        // （クロスドメイン参照を避けるため residencestatus ドメイン内の subjectUserId を使用）
        List<ResidentActivitySnapshot> snapshots = snapshotRepo
                .findByResidentRegistryIdAndOrganizationIdAndDeletedAtIsNullOrderBySnapshotDateDesc(
                        residentRegistryId, organizationId);

        if (!snapshots.isEmpty()) {
            Long subjectUserId = snapshots.get(0).getSubjectUserId();
            if (requestUserId.equals(subjectUserId)) {
                throw new BusinessException(ResidenceStatusErrorCode.SNAPSHOT_SELF_ACCESS_FORBIDDEN);
            }
        }

        return snapshots.stream()
                .limit(30)
                .map(this::toDto)
                .toList();
    }

    // ─────────────────────────────────────────────────────────────────
    // ダッシュボード集計（ADMIN / DEPUTY_ADMIN のみ）
    // ─────────────────────────────────────────────────────────────────

    /**
     * 組織単位のダッシュボード集計を返す。
     *
     * <p>v1: 各居住者（subjectUserId）の最新スナップショット日付から
     * {@link #computeScore(LocalDate, LocalDate)} を使い inactiveDays ベースでリスクを算出する。
     * スナップショットがない居住者は {@link #DEFAULT_INACTIVE_DAYS} 日不活動扱いとする。</p>
     *
     * <p>Redis キャッシュ 5 分 TTL の想定（キャッシュ制御は Controller 層で行う）。
     * TODO: {@code @Cacheable} 適用は別フェーズで実施予定。</p>
     *
     * @param organizationId テナント ID
     * @param requestUserId  リクエストユーザー ID
     * @return ダッシュボード集計 DTO
     * @throws BusinessException DASHBOARD_ACCESS_FORBIDDEN: 権限不足
     */
    public ResidenceStatusDashboardDto getDashboard(Long organizationId, Long requestUserId) {
        // 権限確認: ADMIN/DEPUTY_ADMIN のみ
        if (!accessControlService.isAdminOrAbove(requestUserId, organizationId, "ORGANIZATION")) {
            throw new BusinessException(ResidenceStatusErrorCode.DASHBOARD_ACCESS_FORBIDDEN);
        }

        LocalDate today = LocalDate.now();

        // 居住者ごとに最新スナップショットを 1 件ずつ取得する（新しい日付順のため先頭が最新）
        // subjectUserId でグループ化し、各居住者の最新スナップショット日付を特定する
        List<ResidentActivitySnapshot> allSnapshots = snapshotRepo
                .findByOrganizationIdAndDeletedAtIsNull(organizationId);

        // subjectUserId ごとの最新 snapshotDate を抽出する
        Map<Long, LocalDate> latestSnapshotByUser = allSnapshots.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ResidentActivitySnapshot::getSubjectUserId,
                        ResidentActivitySnapshot::getSnapshotDate,
                        // 同一ユーザーに複数スナップショットがある場合は新しいほうを残す
                        (existing, replacement) ->
                                existing.isAfter(replacement) ? existing : replacement
                ));

        int highRisk = 0;
        int midRisk = 0;
        int lowRisk = 0;
        // v1: スナップショットが 0 件の居住者数（暫定: 0 で計上）
        int unresponsive = 0;

        for (LocalDate latestDate : latestSnapshotByUser.values()) {
            // v1 スコア: 最新スナップショット日からの inactiveDays ベースで算定する
            int score = computeScore(latestDate, today);
            if (score >= HIGH_RISK_THRESHOLD) {
                highRisk++;
            } else if (score >= MID_RISK_THRESHOLD) {
                midRisk++;
            } else {
                lowRisk++;
            }
            if (score == UNRESPONSIVE_SCORE) {
                unresponsive++;
            }
        }

        // 進行中年次キャンペーン数（closedAt IS NULL = 進行中）
        // 同一ドメイン内（residencestatus）のリポジトリを使用するため @Transactional 越境なし
        int openAnnualReviewCount = (int) annualReviewRepo
                .findByOrganizationIdAndDeletedAtIsNull(organizationId)
                .stream()
                .filter(ar -> ar.getClosedAt() == null)
                .count();

        return ResidenceStatusDashboardDto.builder()
                .organizationId(organizationId)
                .totalResidents(latestSnapshotByUser.size())
                .highRiskCount(highRisk)
                .midRiskCount(midRisk)
                .lowRiskCount(lowRisk)
                .unresponsiveCount(unresponsive)
                .openAnnualReviewCount(openAnnualReviewCount)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────
    // ローテーションバッチ用
    // ─────────────────────────────────────────────────────────────────

    /**
     * 30 日以前の snapshot を論理削除する。
     *
     * <p>毎日 05:00 のローテーションバッチから呼ばれる。対象件数が多い場合に備え、
     * {@link ResidentActivitySnapshotBatchDeleter#deleteBatch} を {@link #SNAPSHOT_DELETE_BATCH_SIZE}
     * 件ずつ、影響行数が 0 になるまで繰り返す。1 バッチ = 1 独立トランザクションとし、
     * 一部バッチの失敗が全体をロールバックしないようにする。
     *
     * <p>クラスレベルの {@code @Transactional(readOnly = true)} を無効化するため
     * {@link Propagation#NOT_SUPPORTED} を明示する（本メソッド自身はトランザクションを持たず、
     * 実際の更新は {@link ResidentActivitySnapshotBatchDeleter} 側の独立トランザクションで行う）。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deleteOldSnapshots() {
        LocalDate cutoff = LocalDate.now().minusDays(30);
        int totalDeleted = 0;
        int loops = 0;
        int affected;
        do {
            affected = batchDeleter.deleteBatch(cutoff, SNAPSHOT_DELETE_BATCH_SIZE);
            totalDeleted += affected;
            loops++;
        } while (affected == SNAPSHOT_DELETE_BATCH_SIZE && loops < SNAPSHOT_DELETE_MAX_LOOPS);

        if (affected == SNAPSHOT_DELETE_BATCH_SIZE && loops >= SNAPSHOT_DELETE_MAX_LOOPS) {
            log.warn("[ActivityAggregator] {}日以前の snapshot 削除がループ上限({}回)に到達。残存分あり: {}件処理済み",
                    cutoff, SNAPSHOT_DELETE_MAX_LOOPS, totalDeleted);
        }

        log.info("[ActivityAggregator] {}日以前の snapshot を論理削除: {}件（{}バッチ）", cutoff, totalDeleted, loops);
    }

    // ─────────────────────────────────────────────────────────────────
    // private ヘルパー
    // ─────────────────────────────────────────────────────────────────

    /**
     * v1 簡易スコア算定（inactiveDays ベース）。
     * F08.2 滞納連携・resident_registry 年齢連携は v2 以降で実装予定。
     *
     * <p>算定式: inactiveDays（最後のスナップショットからの経過日数）× 2
     * ただし上限 {@link #MAX_SCORE}（50）でクランプする。
     * snapshotDate が null の場合は 30 日前扱いとして計算する。</p>
     *
     * <p>スコア区分:
     * <ul>
     *   <li>40 以上 → HIGH_RISK</li>
     *   <li>20〜39 → MID_RISK</li>
     *   <li>0〜19 → LOW_RISK</li>
     * </ul>
     *
     * @param snapshotDate 最新スナップショット日付（null の場合は 30 日前扱い）
     * @param today        基準日
     * @return 0-50 のリスクスコア
     */
    int computeScore(LocalDate snapshotDate, LocalDate today) {
        int inactiveDays = (snapshotDate != null)
                ? (int) ChronoUnit.DAYS.between(snapshotDate, today)
                : DEFAULT_INACTIVE_DAYS;
        return Math.min(MAX_SCORE, inactiveDays * 2);
    }

    /**
     * スナップショット閲覧権限チェック（ADMIN / DEPUTY_ADMIN のみ許可）。
     */
    private void checkSnapshotAccessPermission(Long requestUserId, Long organizationId) {
        if (!accessControlService.isAdminOrAbove(requestUserId, organizationId, "ORGANIZATION")) {
            throw new BusinessException(ResidenceStatusErrorCode.SNAPSHOT_ACCESS_FORBIDDEN);
        }
    }

    /**
     * Entity → DTO 変換。
     */
    private ActivitySnapshotDto toDto(ResidentActivitySnapshot e) {
        return ActivitySnapshotDto.builder()
                .id(e.getId())
                .organizationId(e.getOrganizationId())
                .residentRegistryId(e.getResidentRegistryId())
                .subjectUserId(e.getSubjectUserId())
                .snapshotDate(e.getSnapshotDate())
                .activityScoreTotal(e.getActivityScoreTotal())
                .activityBreakdownJson(e.getActivityBreakdownJson())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
