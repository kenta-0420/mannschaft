package com.mannschaft.app.translation.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.translation.TranslationErrorCode;
import com.mannschaft.app.translation.TranslationStatus;
import com.mannschaft.app.translation.entity.ContentTranslationEntity;
import com.mannschaft.app.translation.repository.ContentTranslationRepository;
import com.mannschaft.app.translation.service.ContentTranslationService.ChangeStatusRequest;
import com.mannschaft.app.translation.service.ContentTranslationService.ContentTranslationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 翻訳コンテンツのステータス遷移・公開・陳腐化マーク処理を担うサービス。
 * <p>
 * 第12弾リファクタリングで {@link ContentTranslationService} から
 * ステータス遷移・PUBLISHED 公開・NEEDS_UPDATE 一括マーク（markAsStale）系の
 * 振る舞いを切り出した。
 * <p>
 * 振る舞いは元実装と完全に同一に保つ。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentTranslationStatusService {

    private final ContentTranslationRepository contentTranslationRepository;

    /**
     * 翻訳コンテンツのステータスを変更する。遷移ルールをバリデーションする。
     *
     * @param id        翻訳コンテンツID
     * @param scopeType スコープ種別（BOLA是正: idがこのscope配下か束縛検証する）
     * @param scopeId   スコープID
     * @param req       ステータス変更リクエスト
     * @return 更新後の翻訳コンテンツのレスポンス
     * @throws BusinessException TRANSLATION_002: 対象が見つからない、またはscope不一致（越境）の場合
     * @throws BusinessException TRANSLATION_005: 不正なステータス遷移の場合
     * @throws BusinessException TRANSLATION_007: バージョン不一致の場合
     */
    @Transactional
    public ApiResponse<ContentTranslationResponse> changeStatus(
            Long id, String scopeType, Long scopeId, ChangeStatusRequest req) {
        ContentTranslationEntity entity = findOrThrow(id, scopeType, scopeId);

        // 楽観的ロック: バージョンチェック
        checkVersion(entity, req.getVersion());

        TranslationStatus currentStatus = TranslationStatus.valueOf(entity.getStatus());
        TranslationStatus targetStatus;
        try {
            targetStatus = TranslationStatus.valueOf(req.getStatus());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(TranslationErrorCode.TRANSLATION_005);
        }

        // ステータス遷移バリデーション
        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new BusinessException(TranslationErrorCode.TRANSLATION_005);
        }

        // PUBLISHED への遷移時は publishedAt を記録
        if (targetStatus == TranslationStatus.PUBLISHED) {
            entity.publish();
        } else {
            entity.updateStatus(targetStatus.name());
        }

        try {
            ContentTranslationEntity saved = contentTranslationRepository.save(entity);
            log.info("翻訳ステータス変更: id={}, {} → {}", id, currentStatus, targetStatus);
            return ApiResponse.of(new ContentTranslationResponse(saved));
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BusinessException(TranslationErrorCode.TRANSLATION_007);
        }
    }

    /**
     * 翻訳コンテンツを公開状態（PUBLISHED）に更新する。
     *
     * @param id        翻訳コンテンツID
     * @param scopeType スコープ種別（BOLA是正: idがこのscope配下か束縛検証する）
     * @param scopeId   スコープID
     * @return 更新後の翻訳コンテンツのレスポンス
     * @throws BusinessException TRANSLATION_002: 対象が見つからない、またはscope不一致（越境）の場合
     */
    @Transactional
    public ApiResponse<ContentTranslationResponse> publishTranslation(Long id, String scopeType, Long scopeId) {
        ContentTranslationEntity entity = findOrThrow(id, scopeType, scopeId);
        entity.publish();
        ContentTranslationEntity saved = contentTranslationRepository.save(entity);
        log.info("翻訳コンテンツ公開: id={}", id);
        return ApiResponse.of(new ContentTranslationResponse(saved));
    }

    /**
     * 指定原文コンテンツのPUBLISHED翻訳を全てNEEDS_UPDATEに更新する。
     * 原文が更新された際に呼び出す（イベントリスナー・バッチからの呼び出し）。
     *
     * @param scopeType   スコープ種別（BOLA是正: 原文IDがこのscope配下か束縛検証する）
     * @param scopeId     スコープID
     * @param contentType 原文コンテンツ種別
     * @param contentId   原文コンテンツID
     * @return 更新件数
     */
    @Transactional
    public int markAsStale(String scopeType, Long scopeId, String contentType, Long contentId) {
        // PUBLISHED状態の翻訳を全て取得（scope束縛: 他scope所属の同一sourceId翻訳を巻き込まない）
        List<ContentTranslationEntity> publishedList =
                contentTranslationRepository
                        .findBySourceTypeAndSourceIdAndStatusAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                                contentType, contentId, TranslationStatus.PUBLISHED.name(), scopeType, scopeId);

        int count = 0;
        for (ContentTranslationEntity entity : publishedList) {
            entity.updateStatus(TranslationStatus.NEEDS_UPDATE.name());
            contentTranslationRepository.save(entity);
            count++;
        }

        if (count > 0) {
            log.info("翻訳コンテンツをNEEDS_UPDATEに更新: contentType={}, contentId={}, 件数={}",
                    contentType, contentId, count);
        }
        return count;
    }

    /**
     * IDとスコープで翻訳コンテンツを取得する。見つからない、またはscope不一致（BOLA越境）の場合は
     * 同一コード（TRANSLATION_002）で例外をスローし存在を秘匿する。
     *
     * @param id        翻訳コンテンツID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 翻訳コンテンツエンティティ
     */
    private ContentTranslationEntity findOrThrow(Long id, String scopeType, Long scopeId) {
        return contentTranslationRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(id, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(TranslationErrorCode.TRANSLATION_002));
    }

    /**
     * 楽観的ロックのバージョンチェック。
     *
     * @param entity         エンティティ
     * @param requestVersion リクエストのバージョン
     * @throws BusinessException TRANSLATION_007: バージョン不一致の場合
     */
    private void checkVersion(ContentTranslationEntity entity, Long requestVersion) {
        if (!entity.getVersion().equals(requestVersion)) {
            throw new BusinessException(TranslationErrorCode.TRANSLATION_007);
        }
    }
}
