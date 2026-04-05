package com.mannschaft.app.directmail.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.directmail.DirectMailErrorCode;
import com.mannschaft.app.directmail.DirectMailMapper;
import com.mannschaft.app.directmail.dto.CreateDirectMailTemplateRequest;
import com.mannschaft.app.directmail.dto.DirectMailTemplateResponse;
import com.mannschaft.app.directmail.dto.UpdateDirectMailTemplateRequest;
import com.mannschaft.app.directmail.entity.DirectMailTemplateEntity;
import com.mannschaft.app.directmail.repository.DirectMailTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ダイレクトメールテンプレートサービス。テンプレートのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DirectMailTemplateService {

    private final DirectMailTemplateRepository templateRepository;
    private final DirectMailMapper directMailMapper;

    /**
     * テンプレート一覧を取得する。
     */
    public List<DirectMailTemplateResponse> listTemplates(String scopeType, Long scopeId) {
        List<DirectMailTemplateEntity> templates = templateRepository
                .findByScopeTypeAndScopeIdOrderByCreatedAtDesc(scopeType, scopeId);
        return directMailMapper.toTemplateResponseList(templates);
    }

    /**
     * テンプレートを作成する。
     */
    @Transactional
    public DirectMailTemplateResponse createTemplate(String scopeType, Long scopeId, Long userId,
                                                      CreateDirectMailTemplateRequest request) {
        DirectMailTemplateEntity entity = DirectMailTemplateEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(request.getName())
                .subject(request.getSubject())
                .bodyMarkdown(request.getBodyMarkdown())
                .createdBy(userId)
                .build();

        DirectMailTemplateEntity saved = templateRepository.save(entity);
        log.info("DMテンプレート作成: scopeType={}, scopeId={}, templateId={}", scopeType, scopeId, saved.getId());
        return directMailMapper.toTemplateResponse(saved);
    }

    /**
     * テンプレートを更新する。
     */
    @Transactional
    public DirectMailTemplateResponse updateTemplate(String scopeType, Long scopeId, Long templateId,
                                                      UpdateDirectMailTemplateRequest request) {
        DirectMailTemplateEntity entity = findTemplateOrThrow(scopeType, scopeId, templateId);

        entity.update(request.getName(), request.getSubject(), request.getBodyMarkdown());

        DirectMailTemplateEntity saved = templateRepository.save(entity);
        log.info("DMテンプレート更新: templateId={}", templateId);
        return directMailMapper.toTemplateResponse(saved);
    }

    /**
     * テンプレートを削除する（論理削除）。
     */
    @Transactional
    public void deleteTemplate(String scopeType, Long scopeId, Long templateId) {
        DirectMailTemplateEntity entity = findTemplateOrThrow(scopeType, scopeId, templateId);
        entity.softDelete();
        templateRepository.save(entity);
        log.info("DMテンプレート削除: templateId={}", templateId);
    }

    private DirectMailTemplateEntity findTemplateOrThrow(String scopeType, Long scopeId, Long templateId) {
        return templateRepository.findByIdAndScopeTypeAndScopeId(templateId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(DirectMailErrorCode.TEMPLATE_NOT_FOUND));
    }
}
