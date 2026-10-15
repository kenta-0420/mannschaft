package com.mannschaft.app.moderation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationExtErrorCode;
import com.mannschaft.app.moderation.ModerationExtMapper;
import com.mannschaft.app.moderation.dto.ModerationTemplateResponse;
import com.mannschaft.app.moderation.entity.ModerationActionTemplateEntity;
import com.mannschaft.app.moderation.repository.ModerationActionTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * モデレーション対応テンプレートサービス。テンプレートCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ModerationTemplateService {

    private final ModerationActionTemplateRepository templateRepository;
    private final ModerationExtMapper mapper;

    /**
     * テンプレート一覧を取得する。
     *
     * @return テンプレートレスポンス一覧
     */
    public List<ModerationTemplateResponse> getAllTemplates() {
        return mapper.toTemplateResponseList(templateRepository.findAllByOrderByActionTypeAscNameAsc());
    }

    /**
     * テンプレートを作成する。
     *
     * @param name         テンプレート名
     * @param actionType   アクション種別
     * @param reason       理由
     * @param templateText テンプレート文
     * @param language     言語
     * @param isDefault    デフォルトフラグ
     * @param createdBy    作成者ID
     * @return テンプレートレスポンス
     */
    @Transactional
    public ModerationTemplateResponse createTemplate(String name, String actionType, String reason,
                                                      String templateText, String language,
                                                      Boolean isDefault, Long createdBy) {
        ModerationActionTemplateEntity entity = ModerationActionTemplateEntity.builder()
                .name(name)
                .actionType(actionType)
                .reason(reason)
                .templateText(templateText)
                .language(language != null ? language : "ja")
                .isDefault(isDefault != null ? isDefault : false)
                .createdBy(createdBy)
                .build();

        entity = templateRepository.save(entity);

        log.info("対応テンプレート作成: id={}, name={}, actionType={}", entity.getId(), name, actionType);
        return mapper.toTemplateResponse(entity);
    }

    /**
     * テンプレートを更新する。
     *
     * @param id           テンプレートID
     * @param name         テンプレート名
     * @param actionType   アクション種別
     * @param reason       理由
     * @param templateText テンプレート文
     * @param language     言語
     * @param isDefault    デフォルトフラグ
     * @return 更新後のテンプレートレスポンス
     */
    @Transactional
    public ModerationTemplateResponse updateTemplate(Long id, String name, String actionType, String reason,
                                                      String templateText, String language, Boolean isDefault) {
        ModerationActionTemplateEntity entity = templateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ModerationExtErrorCode.TEMPLATE_NOT_FOUND));

        entity.update(name, actionType, reason, templateText, language, isDefault);
        templateRepository.save(entity);

        log.info("対応テンプレート更新: id={}, name={}", id, name);
        return mapper.toTemplateResponse(entity);
    }

    /**
     * テンプレートを論理削除する。
     *
     * @param id テンプレートID
     */
    @Transactional
    public void deleteTemplate(Long id) {
        ModerationActionTemplateEntity entity = templateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ModerationExtErrorCode.TEMPLATE_NOT_FOUND));

        entity.softDelete();
        templateRepository.save(entity);

        log.info("対応テンプレート削除: id={}", id);
    }
}
