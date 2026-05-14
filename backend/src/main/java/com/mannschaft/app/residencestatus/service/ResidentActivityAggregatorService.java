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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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

    /** リスクスコア閾値: ハイリスク（70 以上） */
    static final int HIGH_RISK_THRESHOLD = 70;

    /** リスクスコア閾値: ミドルリスク（40 以上） */
    static final int MID_RISK_THRESHOLD = 40;

    /** 30 日無ログイン判定: score = 0 をプレースホルダとして使用（v1 判定基準） */
    static final int UNRESPONSIVE_SCORE = 0;

    private final ResidentActivitySnapshotRepository snapshotRepo;
    private final AnnualReviewRepository annualReviewRepo;
    private final AccessControlService accessControlService;
    private final ApplicationEventPublisher eventPublisher;

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
                .findByResidentRegistryIdAndDeletedAtIsNullOrderBySnapshotDateDesc(residentRegistryId);

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
     * <p>当日のスナップショット一覧からリスク分布を算出する。
     * Redis キャッシュ 5 分 TTL の想定（キャッシュ制御は Controller 層で行う）。
     * TODO: {@code @Cacheable} 適用は別フェーズで実施予定。
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
        List<ResidentActivitySnapshot> todaySnapshots = snapshotRepo
                .findByOrganizationIdAndDeletedAtIsNull(organizationId)
                .stream()
                .filter(s -> today.equals(s.getSnapshotDate()))
                .toList();

        int highRisk = 0;
        int midRisk = 0;
        int lowRisk = 0;
        int unresponsive = 0;

        for (ResidentActivitySnapshot s : todaySnapshots) {
            int score = s.getActivityScoreTotal() != null ? s.getActivityScoreTotal() : 0;
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
                .totalResidents(todaySnapshots.size())
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
     * <p>毎日 05:00 のローテーションバッチから呼ばれる。
     */
    @Transactional
    public void deleteOldSnapshots() {
        LocalDate cutoff = LocalDate.now().minusDays(30);
        List<ResidentActivitySnapshot> oldSnapshots =
                snapshotRepo.findBySnapshotDateLessThanAndDeletedAtIsNull(cutoff);

        oldSnapshots.forEach(s -> {
            s.setDeletedAt(LocalDateTime.now());
            snapshotRepo.save(s);
        });

        log.info("[ActivityAggregator] {}日以前の snapshot を論理削除: {}件", cutoff, oldSnapshots.size());
    }

    // ─────────────────────────────────────────────────────────────────
    // private ヘルパー
    // ─────────────────────────────────────────────────────────────────

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
