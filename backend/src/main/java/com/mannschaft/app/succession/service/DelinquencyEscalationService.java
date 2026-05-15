package com.mannschaft.app.succession.service;

import com.mannschaft.app.common.BusinessException;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DelinquencyEscalationService {

    private final DelinquencyEscalationRepository escalationRepository;
    // S5-C で追加: private final SuccessionPreRegistrationService preRegistrationService;

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

        return escalationRepository.save(entity);
    }

    /**
     * エスカレーションを凍結する（弁護士介入・誤起票等）。
     *
     * <p>凍結中のエスカレーションはバッチによる自動昇格や手動操作の対象外となる。
     *
     * @param escalationId   エスカレーション ID
     * @param organizationId 組織 ID（テナント分離）
     * @param reason         凍結理由（自由記述）
     * @throws BusinessException ESCALATION_NOT_FOUND / ESCALATION_ALREADY_RESOLVED /
     *                           ESCALATION_FROZEN
     */
    @Transactional
    public void freeze(UUID escalationId, Long organizationId, String reason) {
        DelinquencyEscalationEntity entity = getValidEscalation(escalationId, organizationId);
        entity.setFrozenAt(LocalDateTime.now());
        entity.setFrozenReason(reason);
        escalationRepository.save(entity);
        log.info("エスカレーション凍結: id={}, reason={}", escalationId, reason);
    }

    /**
     * エスカレーションを解決済みにする（支払い完了・死亡確定・手動クローズ等）。
     *
     * @param escalationId   エスカレーション ID
     * @param organizationId 組織 ID（テナント分離）
     * @param resolvedReason 解決理由（PAID / DEATH_CONFIRMED / MANUAL_CLOSE 等）
     * @throws BusinessException ESCALATION_NOT_FOUND / ESCALATION_ALREADY_RESOLVED
     */
    @Transactional
    public void resolve(UUID escalationId, Long organizationId, String resolvedReason) {
        DelinquencyEscalationEntity entity = getById(escalationId, organizationId);

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
     * 組織内の未解決エスカレーション一覧を取得する（理事長ダッシュボード用）。
     *
     * @param organizationId 組織 ID
     * @return 未解決エスカレーションのリスト（削除済みを除く）
     */
    public List<DelinquencyEscalationEntity> listActive(Long organizationId) {
        return escalationRepository
                .findByOrganizationIdAndResolvedAtIsNullAndDeletedAtIsNull(organizationId);
    }

    /**
     * 組織内の特定エスカレーションを取得する。
     *
     * @param escalationId   エスカレーション ID
     * @param organizationId 組織 ID（テナント分離）
     * @return エスカレーションエンティティ
     * @throws BusinessException ESCALATION_NOT_FOUND
     */
    public DelinquencyEscalationEntity getById(UUID escalationId, Long organizationId) {
        return escalationRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(escalationId, organizationId)
                .orElseThrow(() -> new BusinessException(SuccessionErrorCode.ESCALATION_NOT_FOUND));
    }

    /**
     * 有効な（解決済みでなく・凍結中でもない）エスカレーションを取得する。
     * 各種操作の前段チェックで使用する。
     */
    private DelinquencyEscalationEntity getValidEscalation(UUID escalationId, Long organizationId) {
        DelinquencyEscalationEntity entity = getById(escalationId, organizationId);

        if (entity.getResolvedAt() != null) {
            throw new BusinessException(SuccessionErrorCode.ESCALATION_ALREADY_RESOLVED);
        }
        if (entity.getFrozenAt() != null) {
            throw new BusinessException(SuccessionErrorCode.ESCALATION_FROZEN);
        }
        return entity;
    }
}
