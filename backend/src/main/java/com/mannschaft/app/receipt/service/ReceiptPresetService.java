package com.mannschaft.app.receipt.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.receipt.ReceiptErrorCode;
import com.mannschaft.app.receipt.ReceiptMapper;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.dto.CreatePresetRequest;
import com.mannschaft.app.receipt.dto.PresetResponse;
import com.mannschaft.app.receipt.dto.UpdatePresetRequest;
import com.mannschaft.app.receipt.entity.ReceiptPresetEntity;
import com.mannschaft.app.receipt.repository.ReceiptPresetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 領収書プリセットサービス。プリセットのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReceiptPresetService {

    private static final int MAX_PRESETS_PER_SCOPE = 30;

    private final ReceiptPresetRepository presetRepository;
    private final ReceiptMapper receiptMapper;

    /**
     * プリセット一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return プリセットレスポンスリスト
     */
    public List<PresetResponse> listPresets(ReceiptScopeType scopeType, Long scopeId) {
        List<ReceiptPresetEntity> presets = presetRepository
                .findByScopeTypeAndScopeIdOrderByCreatedAtDesc(scopeType, scopeId);
        return receiptMapper.toPresetResponseList(presets);
    }

    /**
     * プリセットを作成する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    作成者ID
     * @param request   作成リクエスト
     * @return 作成されたプリセットレスポンス
     */
    @Transactional
    public PresetResponse createPreset(ReceiptScopeType scopeType, Long scopeId,
                                        Long userId, CreatePresetRequest request) {
        long count = presetRepository.countByScopeTypeAndScopeId(scopeType, scopeId);
        if (count >= MAX_PRESETS_PER_SCOPE) {
            throw new BusinessException(ReceiptErrorCode.PRESET_LIMIT_EXCEEDED);
        }

        ReceiptPresetEntity entity = ReceiptPresetEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(request.getName())
                .description(request.getDescription())
                .amount(request.getAmount())
                .taxRate(request.getTaxRate() != null ? request.getTaxRate() : new BigDecimal("10.00"))
                .lineItemsJson(request.getLineItemsJson())
                .paymentMethodLabel(request.getPaymentMethodLabel())
                .sealStamp(request.getSealStamp() != null ? request.getSealStamp() : true)
                .createdBy(userId)
                .build();

        ReceiptPresetEntity saved = presetRepository.save(entity);
        log.info("プリセット作成: presetId={}, name={}", saved.getId(), request.getName());
        return receiptMapper.toPresetResponse(saved);
    }

    /**
     * プリセットを更新する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param presetId  プリセットID
     * @param request   更新リクエスト
     * @return 更新されたプリセットレスポンス
     */
    @Transactional
    public PresetResponse updatePreset(ReceiptScopeType scopeType, Long scopeId,
                                        Long presetId, UpdatePresetRequest request) {
        ReceiptPresetEntity entity = findPresetOrThrow(scopeType, scopeId, presetId);

        entity.update(
                request.getName(),
                request.getDescription(),
                request.getAmount(),
                request.getTaxRate() != null ? request.getTaxRate() : entity.getTaxRate(),
                request.getLineItemsJson(),
                request.getPaymentMethodLabel(),
                request.getSealStamp() != null ? request.getSealStamp() : entity.getSealStamp()
        );

        ReceiptPresetEntity saved = presetRepository.save(entity);
        log.info("プリセット更新: presetId={}", presetId);
        return receiptMapper.toPresetResponse(saved);
    }

    /**
     * プリセットを論理削除する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param presetId  プリセットID
     */
    @Transactional
    public void deletePreset(ReceiptScopeType scopeType, Long scopeId, Long presetId) {
        ReceiptPresetEntity entity = findPresetOrThrow(scopeType, scopeId, presetId);
        entity.softDelete();
        presetRepository.save(entity);
        log.info("プリセット削除: presetId={}", presetId);
    }

    /**
     * プリセットエンティティを取得する。存在しない場合は例外をスローする。
     */
    ReceiptPresetEntity findPresetOrThrow(ReceiptScopeType scopeType, Long scopeId, Long presetId) {
        return presetRepository.findByIdAndScopeTypeAndScopeId(presetId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ReceiptErrorCode.PRESET_NOT_FOUND));
    }
}
