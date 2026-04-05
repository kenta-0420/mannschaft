package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.AdminFeedbackErrorCode;
import com.mannschaft.app.admin.AnnouncementFeedbackMapper;
import com.mannschaft.app.admin.dto.ActionTemplateResponse;
import com.mannschaft.app.admin.dto.CreateActionTemplateRequest;
import com.mannschaft.app.admin.dto.UpdateActionTemplateRequest;
import com.mannschaft.app.admin.entity.AdminActionTemplateEntity;
import com.mannschaft.app.admin.repository.AdminActionTemplateRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 管理者アクションテンプレートサービス。テンプレートのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminActionTemplateService {

    private final AdminActionTemplateRepository templateRepository;
    private final AnnouncementFeedbackMapper mapper;

    /**
     * 全テンプレート一覧を取得する。
     *
     * @return テンプレート一覧
     */
    public List<ActionTemplateResponse> getAllTemplates() {
        return mapper.toActionTemplateResponseList(
                templateRepository.findAllByOrderByActionTypeAscNameAsc());
    }

    /**
     * アクション種別でテンプレートを取得する。
     *
     * @param actionType アクション種別
     * @return テンプレート一覧
     */
    public List<ActionTemplateResponse> getTemplatesByActionType(String actionType) {
        return mapper.toActionTemplateResponseList(
                templateRepository.findByActionTypeOrderByNameAsc(actionType));
    }

    /**
     * テンプレートを作成する。
     *
     * @param req    作成リクエスト
     * @param userId 作成者ID
     * @return 作成されたテンプレート
     */
    @Transactional
    public ActionTemplateResponse createTemplate(CreateActionTemplateRequest req, Long userId) {
        AdminActionTemplateEntity entity = AdminActionTemplateEntity.builder()
                .name(req.getName())
                .actionType(req.getActionType())
                .reason(req.getReason())
                .templateText(req.getTemplateText())
                .isDefault(req.getIsDefault() != null ? req.getIsDefault() : false)
                .createdBy(userId)
                .build();

        entity = templateRepository.save(entity);
        log.info("アクションテンプレート作成: id={}, name={}, userId={}", entity.getId(), entity.getName(), userId);
        return mapper.toActionTemplateResponse(entity);
    }

    /**
     * テンプレートを更新する。
     *
     * @param id  テンプレートID
     * @param req 更新リクエスト
     * @return 更新後のテンプレート
     */
    @Transactional
    public ActionTemplateResponse updateTemplate(Long id, UpdateActionTemplateRequest req) {
        AdminActionTemplateEntity entity = templateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(AdminFeedbackErrorCode.ACTION_TEMPLATE_NOT_FOUND));

        entity.update(
                req.getName(),
                req.getActionType(),
                req.getReason(),
                req.getTemplateText(),
                req.getIsDefault() != null ? req.getIsDefault() : entity.getIsDefault()
        );
        entity = templateRepository.save(entity);
        log.info("アクションテンプレート更新: id={}", id);
        return mapper.toActionTemplateResponse(entity);
    }

    /**
     * テンプレートを論理削除する。
     *
     * @param id テンプレートID
     */
    @Transactional
    public void deleteTemplate(Long id) {
        AdminActionTemplateEntity entity = templateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(AdminFeedbackErrorCode.ACTION_TEMPLATE_NOT_FOUND));

        entity.softDelete();
        templateRepository.save(entity);
        log.info("アクションテンプレート削除: id={}", id);
    }
}
