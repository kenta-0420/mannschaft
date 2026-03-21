package com.mannschaft.app.matching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.matching.MatchingErrorCode;
import com.mannschaft.app.matching.dto.CreateTemplateRequest;
import com.mannschaft.app.matching.dto.TemplateCreateResponse;
import com.mannschaft.app.matching.dto.TemplateResponse;
import com.mannschaft.app.matching.entity.MatchRequestTemplateEntity;
import com.mannschaft.app.matching.mapper.MatchingMapper;
import com.mannschaft.app.matching.repository.MatchRequestTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 募集テンプレートサービス。テンプレートのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MatchTemplateService {

    private static final int MAX_TEMPLATES_PER_TEAM = 20;

    private final MatchRequestTemplateRepository templateRepository;
    private final MatchingMapper matchingMapper;

    /**
     * テンプレート一覧を取得する。
     */
    public List<TemplateResponse> listTemplates(Long teamId) {
        List<MatchRequestTemplateEntity> entities = templateRepository.findByTeamIdOrderByCreatedAtDesc(teamId);
        return matchingMapper.toTemplateResponseList(entities);
    }

    /**
     * テンプレートを作成する。
     */
    @Transactional
    public TemplateCreateResponse createTemplate(Long teamId, CreateTemplateRequest request) {
        long count = templateRepository.countByTeamId(teamId);
        if (count >= MAX_TEMPLATES_PER_TEAM) {
            throw new BusinessException(MatchingErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }

        MatchRequestTemplateEntity entity = MatchRequestTemplateEntity.builder()
                .teamId(teamId)
                .name(request.getName())
                .templateJson(request.getTemplateJson())
                .build();

        MatchRequestTemplateEntity saved = templateRepository.save(entity);
        log.info("テンプレート作成: teamId={}, templateId={}", teamId, saved.getId());
        return new TemplateCreateResponse(saved.getId(), saved.getName());
    }

    /**
     * テンプレートを更新する。
     */
    @Transactional
    public TemplateResponse updateTemplate(Long teamId, Long templateId, CreateTemplateRequest request) {
        MatchRequestTemplateEntity entity = templateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.TEMPLATE_NOT_FOUND));

        if (!entity.getTeamId().equals(teamId)) {
            throw new BusinessException(MatchingErrorCode.INSUFFICIENT_PERMISSION);
        }

        entity.update(request.getName(), request.getTemplateJson());
        MatchRequestTemplateEntity saved = templateRepository.save(entity);
        log.info("テンプレート更新: templateId={}", templateId);
        return matchingMapper.toTemplateResponse(saved);
    }

    /**
     * テンプレートを削除する。
     */
    @Transactional
    public void deleteTemplate(Long teamId, Long templateId) {
        MatchRequestTemplateEntity entity = templateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.TEMPLATE_NOT_FOUND));

        if (!entity.getTeamId().equals(teamId)) {
            throw new BusinessException(MatchingErrorCode.INSUFFICIENT_PERMISSION);
        }

        templateRepository.delete(entity);
        log.info("テンプレート削除: templateId={}", templateId);
    }
}
