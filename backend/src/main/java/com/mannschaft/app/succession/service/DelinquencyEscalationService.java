package com.mannschaft.app.succession.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.resident.service.ResidentRegistryService;
import com.mannschaft.app.succession.SuccessionErrorCode;
import com.mannschaft.app.succession.entity.DelinquencyEscalationEntity;
import com.mannschaft.app.succession.entity.DelinquencyEscalationStage;
import com.mannschaft.app.succession.repository.DelinquencyEscalationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 5 段階エスカレーション管理サービス（F09.15 S5-A）。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §5.7 / §7.4
 *
 * <p>滞納＋連絡不通の区分所有者に対して、以下の 5 段階を自動または手動で進行させる:
 * <ol>
 *   <li>D+30  : STAGE_1_REMINDER（督促）</li>
 *   <li>D+60  : STAGE_2_EMERGENCY_CONTACT（緊急連絡先への連絡）</li>
 *   <li>D+90  : STAGE_3_WATCHER_VISIT（見守り員の訪問）</li>
 *   <li>D+120 : STAGE_4_DEATH_SUSPECTED（死亡疑い・行政連携）</li>
 *   <li>D+150 : STAGE_5_LEGAL_PREP（法的手続き準備）</li>
 * </ol>
 *
 * <p>テナント分離: 全メソッドで {@code organizationId} による絞り込みを維持する。
 *
 * <p>認可（認可根治戦役 Wave 2 トランシェ2A #3）: Controller から到達可能な操作
 * （{@link #listActive} / {@link #getById(UUID, Long, Long)} / {@link #freeze} / {@link #resolve}）は
 * {@link AccessControlService#checkAdminOrAbove} で ADMIN/DEPUTY_ADMIN 以上を要求する。
 * {@link #createEscalation} / {@link #advanceStage} は {@link DelinquencyEscalationListener}（イベント）
 * / {@link DelinquencyEscalationBatchService}（日次バッチ）からのみ呼ばれる内部専用処理であり、
 * HTTP 入口が存在せず操作ユーザーが存在しないため、ユーザー認可の対象外
 * （台帳の capability 経路除外と同種: システム内部トリガーのみ）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DelinquencyEscalationService {

    /** 認可スコープ種別。succession は組織単位（管理組合）で完結するドメインのため固定。 */
    private static final String SCOPE_TYPE = "ORGANIZATION";

    private final DelinquencyEscalationRepository escalationRepository;
    // TODO: residentドメイン → successionドメインのクロスドメイン呼び出し。将来は
    //       DelinquencyReachedStage4Event を発火してresidentドメインがサブスクライブする形に分離予定。
    private final ResidentRegistryService residentRegistryService;
    private final AccessControlService accessControlService;

    /**
     * 滞納エスカレーションを新規作成する（F08.2 イベント受信時に呼ぶ）。
     *
     * <p>冪等設計: 既に未解決のエスカレーション（resolved_at IS NULL かつ frozen_at IS NULL）
     * が存在する場合は何もせずに既存レコードを返す。
     *
     * @param organizationId      組織 ID
     * @param residentRegistryId  居住者台帳 ID
     * @param dwellingUnitId      居室 ID
     * @param delinquencyStartedAt 滞納開始日（valid_until + 猶予期間 を超えた日）
     * @return 作成 or 既存のエスカレーションエンティティ
     */
    @Transactional
    public DelinquencyEscalationEntity createEscalation(
            Long organizationId,
            Long residentRegistryId,
            Long dwellingUnitId,
            LocalDate delinquencyStartedAt) {

        // 冪等チェック: 既に未解決のエスカレーションが存在する場合は既存を返す
        return escalationRepository
                .findByResidentRegistryIdAndDeletedAtIsNull(residentRegistryId)
                .filter(e -> e.getResolvedAt() == null && e.getFrozenAt() == null)
                .orElseGet(() -> {
                    log.info("エスカレーション新規作成: organizationId={}, residentRegistryId={}, delinquencyStartedAt={}",
                            organizationId, residentRegistryId, delinquencyStartedAt);
                    DelinquencyEscalationEntity entity = DelinquencyEscalationEntity.builder()
                            .organizationId(organizationId)
                            .residentRegistryId(residentRegistryId)
                            .dwellingUnitId(dwellingUnitId)
                            .delinquencyStartedAt(delinquencyStartedAt)
                            .build();
                    return escalationRepository.save(entity);
                });
    }

    /**
     * 指定エスカレーションを次のステージに進める（バッチまたは手動操作から呼ぶ）。
     *
     * <p>STAGE_5_LEGAL_PREP はこれ以上進めない（{@link SuccessionErrorCode#ESCALATION_ALREADY_FINAL_STAGE}）。
     *
     * @param escalationId   エスカレーション ID
     * @param organizationId 組織 ID（テナント分離）
     * @return 更新後のエンティティ
     * @throws BusinessException ESCALATION_NOT_FOUND / ESCALATION_ALREADY_RESOLVED /
     *                           ESCALATION_FROZEN / ESCALATION_ALREADY_FINAL_STAGE
     */
    @Transactional
    public DelinquencyEscalationEntity advanceStage(UUID escalationId, Long organizationId) {
        DelinquencyEscalationEntity entity = getValidEscalation(escalationId, organizationId);

        DelinquencyEscalationStage currentStage =
                DelinquencyEscalationStage.fromString(entity.getCurrentStage());

        // 最終ステージチェック
        if (currentStage == DelinquencyEscalationStage.STAGE_5_LEGAL_PREP) {
            throw new BusinessException(SuccessionErrorCode.ESCALATION_ALREADY_FINAL_STAGE);
        }

        // 次のステージを取得（ordinal + 1）
        DelinquencyEscalationStage nextStage =
                DelinquencyEscalationStage.values()[currentStage.ordinal() + 1];

        LocalDateTime now = LocalDateTime.now();

        // 完了タイムスタンプの記録
        switch (currentStage) {
            case STAGE_1_REMINDER -> entity.setStage1CompletedAt(now);
            case STAGE_2_EMERGENCY_CONTACT -> entity.setStage2CompletedAt(now);
            case STAGE_3_WATCHER_VISIT -> entity.setStage3CompletedAt(now);
            case STAGE_4_DEATH_SUSPECTED -> entity.setStage4CompletedAt(now);
            default -> { /* STAGE_5 はガードされている */ }
        }

        entity.setCurrentStage(nextStage.name());
        log.info("エスカレーションステージ昇格: id={}, {} → {}",
                escalationId, currentStage, nextStage);

        DelinquencyEscalationEntity saved = escalationRepository.save(entity);

        // STAGE_4 到達時: 居住者台帳の death_status を ALIVE → SUSPECTED に自動遷移
        if (nextStage == DelinquencyEscalationStage.STAGE_4_DEATH_SUSPECTED) {
            autoMarkDeathSuspected(entity.getResidentRegistryId());
        }

        return saved;
    }

    /**
     * エスカレーションを凍結する（弁護士介入・誤起票等）。
     *
     * <p>凍結中のエスカレーションはバッチによる自動昇格や手動操作の対象外となる。
     *
     * @param escalationId     エスカレーション ID
     * @param organizationId   組織 ID（テナント分離）
     * @param reason           凍結理由（自由記述）
     * @param requestingUserId 操作ユーザー ID（ADMIN/DEPUTY_ADMIN 以上のみ）
     * @throws BusinessException ESCALATION_NOT_FOUND / ESCALATION_ALREADY_RESOLVED /
     *                           ESCALATION_FROZEN
     */
    @Transactional
    public void freeze(UUID escalationId, Long organizationId, String reason, Long requestingUserId) {
        accessControlService.checkAdminOrAbove(requestingUserId, organizationId, SCOPE_TYPE);
        DelinquencyEscalationEntity entity = getValidEscalation(escalationId, organizationId);
        entity.setFrozenAt(LocalDateTime.now());
        entity.setFrozenReason(reason);
        escalationRepository.save(entity);
        log.info("エスカレーション凍結: id={}, reason={}", escalationId, reason);
    }

    /**
     * エスカレーションを解決済みにする（支払い完了・死亡確定・手動クローズ等）。
     *
     * @param escalationId     エスカレーション ID
     * @param organizationId   組織 ID（テナント分離）
     * @param resolvedReason   解決理由（PAID / DEATH_CONFIRMED / MANUAL_CLOSE 等）
     * @param requestingUserId 操作ユーザー ID（ADMIN/DEPUTY_ADMIN 以上のみ）
     * @throws BusinessException ESCALATION_NOT_FOUND / ESCALATION_ALREADY_RESOLVED
     */
    @Transactional
    public void resolve(UUID escalationId, Long organizationId, String resolvedReason, Long requestingUserId) {
        accessControlService.checkAdminOrAbove(requestingUserId, organizationId, SCOPE_TYPE);
        DelinquencyEscalationEntity entity = fetchEntity(escalationId, organizationId);

        // 解決済みチェック（凍結中でも解決は許容する）
        if (entity.getResolvedAt() != null) {
            throw new BusinessException(SuccessionErrorCode.ESCALATION_ALREADY_RESOLVED);
        }

        entity.setResolvedAt(LocalDateTime.now());
        entity.setResolvedReason(resolvedReason);
        // 凍結も解除する
        entity.setFrozenAt(null);
        entity.setFrozenReason(null);
        escalationRepository.save(entity);
        log.info("エスカレーション解決: id={}, resolvedReason={}", escalationId, resolvedReason);
    }

    /**
     * 組織内の未解決エスカレーション一覧を取得する（理事長ダッシュボード用・ADMIN 以上）。
     *
     * @param organizationId   組織 ID
     * @param requestingUserId 閲覧ユーザー ID
     * @return 未解決エスカレーションのリスト（削除済みを除く）
     */
    public List<DelinquencyEscalationEntity> listActive(Long organizationId, Long requestingUserId) {
        accessControlService.checkAdminOrAbove(requestingUserId, organizationId, SCOPE_TYPE);
        return escalationRepository
                .findByOrganizationIdAndResolvedAtIsNullAndDeletedAtIsNull(organizationId);
    }

    /**
     * 組織内の特定エスカレーションを取得する（ADMIN 以上）。
     *
     * <p>{@code (escalationId, organizationId)} の複合キーで取得するため、path の
     * organizationId に対する認可はエンティティ由来 scope の認可と必ず一致する（BOLA 安全）。
     * 別テナントの ID を指定した場合は認可の成否に関わらず {@code ESCALATION_NOT_FOUND}（存在秘匿）。
     *
     * @param escalationId     エスカレーション ID
     * @param organizationId   組織 ID（テナント分離）
     * @param requestingUserId 閲覧ユーザー ID
     * @return エスカレーションエンティティ
     * @throws BusinessException ESCALATION_NOT_FOUND
     */
    public DelinquencyEscalationEntity getById(UUID escalationId, Long organizationId, Long requestingUserId) {
        accessControlService.checkAdminOrAbove(requestingUserId, organizationId, SCOPE_TYPE);
        return fetchEntity(escalationId, organizationId);
    }

    /**
     * STAGE_4 到達時に居住者台帳の death_status を SUSPECTED に自動変更する。
     *
     * <p>存在しない居住者 ID または例外発生時はログを記録してスキップする。
     * エスカレーション本体の保存は既に完了しているため、ここで例外が発生しても
     * エスカレーション昇格自体はロールバックしない（death_status 更新は best-effort）。
     * TODO: 将来的に DelinquencyReachedStage4Event 経由でresidentドメインを分離予定。
     *
     * @param residentRegistryId 居住者台帳 ID
     */
    private void autoMarkDeathSuspected(Long residentRegistryId) {
        try {
            residentRegistryService.markDeathSuspected(residentRegistryId);
        } catch (Exception e) {
            // death_status 更新失敗はエスカレーション昇格を止めない（best-effort）
            log.error("死亡疑い自動起票に失敗（エスカレーション昇格は維持）: residentRegistryId={}, error={}",
                    residentRegistryId, e.getMessage(), e);
        }
    }

    /**
     * 有効な（解決済みでなく・凍結中でもない）エスカレーションを取得する。
     * 各種操作の前段チェックで使用する。呼び出し元（advanceStage / freeze）で
     * 認可済みであることを前提とする内部ヘルパー。
     */
    private DelinquencyEscalationEntity getValidEscalation(UUID escalationId, Long organizationId) {
        DelinquencyEscalationEntity entity = fetchEntity(escalationId, organizationId);

        if (entity.getResolvedAt() != null) {
            throw new BusinessException(SuccessionErrorCode.ESCALATION_ALREADY_RESOLVED);
        }
        if (entity.getFrozenAt() != null) {
            throw new BusinessException(SuccessionErrorCode.ESCALATION_FROZEN);
        }
        return entity;
    }

    /**
     * テナント分離済みのエンティティ取得（認可なし・内部専用）。
     *
     * <p>{@link #advanceStage}（内部バッチ専用・操作ユーザーが存在しない）と
     * {@link #getById(UUID, Long, Long)} / {@link #freeze} / {@link #resolve}（呼び出し元で
     * 認可済み）からのみ呼ばれる。Controller から直接到達不可能な private ヘルパーであり、
     * 誤って認可なしに公開しないよう private のまま維持すること（BOLA 再混入防止）。
     */
    private DelinquencyEscalationEntity fetchEntity(UUID escalationId, Long organizationId) {
        return escalationRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(escalationId, organizationId)
                .orElseThrow(() -> new BusinessException(SuccessionErrorCode.ESCALATION_NOT_FOUND));
    }
}
