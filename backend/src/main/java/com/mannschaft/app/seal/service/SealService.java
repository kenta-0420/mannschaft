package com.mannschaft.app.seal.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.seal.SealErrorCode;
import com.mannschaft.app.seal.SealMapper;
import com.mannschaft.app.seal.SealScopeType;
import com.mannschaft.app.seal.SealVariant;
import com.mannschaft.app.seal.dto.CreateSealRequest;
import com.mannschaft.app.seal.dto.ScopeDefaultResponse;
import com.mannschaft.app.seal.dto.SealResponse;
import com.mannschaft.app.seal.dto.SetScopeDefaultRequest;
import com.mannschaft.app.seal.dto.UpdateSealRequest;
import com.mannschaft.app.seal.entity.ElectronicSealEntity;
import com.mannschaft.app.seal.entity.SealScopeDefaultEntity;
import com.mannschaft.app.seal.repository.ElectronicSealRepository;
import com.mannschaft.app.seal.repository.SealScopeDefaultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 電子印鑑サービス。印鑑の生成・管理・スコープデフォルト設定を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SealService {

    private final ElectronicSealRepository sealRepository;
    private final SealScopeDefaultRepository scopeDefaultRepository;
    private final SealMapper sealMapper;
    private final SealGenerator sealGenerator;

    /**
     * ユーザーの印鑑一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 印鑑レスポンスリスト
     */
    public List<SealResponse> listSeals(Long userId) {
        List<ElectronicSealEntity> seals = sealRepository.findByUserIdOrderByCreatedAtAsc(userId);
        return sealMapper.toSealResponseList(seals);
    }

    /**
     * 印鑑詳細を取得する。
     *
     * @param userId ユーザーID
     * @param sealId 印鑑ID
     * @return 印鑑レスポンス
     */
    public SealResponse getSeal(Long userId, Long sealId) {
        ElectronicSealEntity entity = findSealOrThrow(userId, sealId);
        return sealMapper.toSealResponse(entity);
    }

    /**
     * 印鑑を作成する。
     *
     * @param userId  ユーザーID
     * @param request 作成リクエスト
     * @return 作成された印鑑レスポンス
     */
    @Transactional
    public SealResponse createSeal(Long userId, CreateSealRequest request) {
        SealVariant variant = SealVariant.valueOf(request.getVariant());

        if (sealRepository.existsByUserIdAndVariant(userId, variant)) {
            throw new BusinessException(SealErrorCode.DUPLICATE_VARIANT);
        }

        String svgData = sealGenerator.generateSvg(request.getDisplayText(), variant);
        String sealHash = sealGenerator.computeHash(svgData);

        ElectronicSealEntity entity = ElectronicSealEntity.builder()
                .userId(userId)
                .variant(variant)
                .displayText(request.getDisplayText())
                .svgData(svgData)
                .sealHash(sealHash)
                .build();

        ElectronicSealEntity saved = sealRepository.save(entity);
        log.info("印鑑作成: userId={}, sealId={}, variant={}", userId, saved.getId(), variant);
        return sealMapper.toSealResponse(saved);
    }

    /**
     * 印鑑を更新する（表示テキスト変更 → SVG再生成）。
     *
     * @param userId  ユーザーID
     * @param sealId  印鑑ID
     * @param request 更新リクエスト
     * @return 更新された印鑑レスポンス
     */
    @Transactional
    public SealResponse updateSeal(Long userId, Long sealId, UpdateSealRequest request) {
        ElectronicSealEntity entity = findSealOrThrow(userId, sealId);

        entity.updateDisplayText(request.getDisplayText());

        String newSvgData = sealGenerator.generateSvg(request.getDisplayText(), entity.getVariant());
        String newSealHash = sealGenerator.computeHash(newSvgData);
        entity.regenerate(newSvgData, newSealHash);

        ElectronicSealEntity saved = sealRepository.save(entity);
        log.info("印鑑更新: userId={}, sealId={}, version={}", userId, sealId, saved.getGenerationVersion());
        return sealMapper.toSealResponse(saved);
    }

    /**
     * 印鑑を論理削除する。
     *
     * @param userId ユーザーID
     * @param sealId 印鑑ID
     */
    @Transactional
    public void deleteSeal(Long userId, Long sealId) {
        ElectronicSealEntity entity = findSealOrThrow(userId, sealId);
        entity.softDelete();
        sealRepository.save(entity);

        // 関連するスコープデフォルトも削除
        scopeDefaultRepository.deleteBySealId(sealId);

        log.info("印鑑削除: userId={}, sealId={}", userId, sealId);
    }

    /**
     * スコープデフォルトを設定する。
     *
     * @param userId  ユーザーID
     * @param request 設定リクエスト
     * @return スコープデフォルトレスポンス
     */
    @Transactional
    public ScopeDefaultResponse setScopeDefault(Long userId, SetScopeDefaultRequest request) {
        SealScopeType scopeType = SealScopeType.valueOf(request.getScopeType());

        // 印鑑の存在確認
        findSealOrThrow(userId, request.getSealId());

        // 既存のスコープデフォルトがあれば更新、なければ新規作成
        SealScopeDefaultEntity entity = scopeDefaultRepository
                .findByUserIdAndScopeTypeAndScopeId(userId, scopeType, request.getScopeId())
                .map(existing -> {
                    existing.changeSeal(request.getSealId());
                    return existing;
                })
                .orElseGet(() -> SealScopeDefaultEntity.builder()
                        .userId(userId)
                        .scopeType(scopeType)
                        .scopeId(request.getScopeId())
                        .sealId(request.getSealId())
                        .build());

        SealScopeDefaultEntity saved = scopeDefaultRepository.save(entity);
        log.info("スコープデフォルト設定: userId={}, scopeType={}, sealId={}", userId, scopeType, request.getSealId());
        return sealMapper.toScopeDefaultResponse(saved);
    }

    /**
     * ユーザーのスコープデフォルト一覧を取得する。
     *
     * @param userId ユーザーID
     * @return スコープデフォルトレスポンスリスト
     */
    public List<ScopeDefaultResponse> listScopeDefaults(Long userId) {
        List<SealScopeDefaultEntity> defaults = scopeDefaultRepository.findByUserIdOrderByCreatedAtAsc(userId);
        return sealMapper.toScopeDefaultResponseList(defaults);
    }

    /**
     * 印鑑エンティティを取得する。他サービスからの参照用。
     *
     * @param sealId 印鑑ID
     * @return 印鑑エンティティ
     */
    public ElectronicSealEntity getSealEntity(Long sealId) {
        return sealRepository.findById(sealId)
                .orElseThrow(() -> new BusinessException(SealErrorCode.SEAL_NOT_FOUND));
    }

    /**
     * 印鑑を取得する。存在しない場合は例外をスローする。
     */
    private ElectronicSealEntity findSealOrThrow(Long userId, Long sealId) {
        return sealRepository.findByIdAndUserId(sealId, userId)
                .orElseThrow(() -> new BusinessException(SealErrorCode.SEAL_NOT_FOUND));
    }

}
