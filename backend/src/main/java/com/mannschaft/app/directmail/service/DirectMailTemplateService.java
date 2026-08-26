package com.mannschaft.app.directmail.service;

import com.mannschaft.app.common.AccessControlService;
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
 *
 * <p>認可根治戦役 Wave2 トランシェ2C: 全公開メソッドの入口で {@link AccessControlService} による
 * 認可検証を行う（一覧=checkMembership／作成・更新・削除=checkAdminOrAbove）。
 * テンプレートは (id, scopeType, scopeId) 複合条件でフェッチするため、path スコープと
 * entity スコープの不一致（BOLA）は {@link DirectMailErrorCode#TEMPLATE_NOT_FOUND} → 404 で存在秘匿される。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DirectMailTemplateService {

    /** 認可根治戦役 Wave2 トランシェ2C: スコープ認可基盤 */
    private final AccessControlService accessControlService;

    private final DirectMailTemplateRepository templateRepository;
    private final DirectMailMapper directMailMapper;

    /**
     * テンプレート一覧を取得する。閲覧系のため操作者はスコープのメンバーであること。
     */
    public List<DirectMailTemplateResponse> listTemplates(String scopeType, Long scopeId, Long actorUserId) {
        accessControlService.checkMembership(actorUserId, scopeId, scopeType);
        List<DirectMailTemplateEntity> templates = templateRepository
                .findByScopeTypeAndScopeIdOrderByCreatedAtDesc(scopeType, scopeId);
        return directMailMapper.toTemplateResponseList(templates);
    }

    /**
     * テンプレートを作成する。変更系のため操作者はスコープの ADMIN 以上であること。
     */
    @Transactional
    public DirectMailTemplateResponse createTemplate(String scopeType, Long scopeId, Long userId,
                                                      CreateDirectMailTemplateRequest request) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);
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
     * テンプレートを更新する。変更系のため操作者はスコープの ADMIN 以上であること。
     */
    @Transactional
    public DirectMailTemplateResponse updateTemplate(String scopeType, Long scopeId, Long actorUserId,
                                                      Long templateId,
                                                      UpdateDirectMailTemplateRequest request) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType);
        DirectMailTemplateEntity entity = findTemplateOrThrow(scopeType, scopeId, templateId);

        entity.update(request.getName(), request.getSubject(), request.getBodyMarkdown());

        DirectMailTemplateEntity saved = templateRepository.save(entity);
        log.info("DMテンプレート更新: templateId={}", templateId);
        return directMailMapper.toTemplateResponse(saved);
    }

    /**
     * テンプレートを削除する（論理削除）。変更系のため操作者はスコープの ADMIN 以上であること。
     */
    @Transactional
    public void deleteTemplate(String scopeType, Long scopeId, Long actorUserId, Long templateId) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType);
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
