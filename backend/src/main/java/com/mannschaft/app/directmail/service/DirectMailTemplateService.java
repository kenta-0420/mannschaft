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
 * 認可検証を行う（作成・更新・削除=checkAdminOrAbove）。
 * テンプレートは (id, scopeType, scopeId) 複合条件でフェッチするため、path スコープと
 * entity スコープの不一致（BOLA）は {@link DirectMailErrorCode#TEMPLATE_NOT_FOUND} → 404 で存在秘匿される。</p>
 *
 * <p><b>認可根治戦役 CMP-260917-2102 Phase 1 の追撃（スコープ差分あり）</b>: 一覧
 * （{@link #listTemplates}）は当初 checkMembership 止まりだったが、実機で ORGANIZATION の
 * MEMBER が組織DMテンプレート一覧を閲覧できることを確認した。組織サイドバーで
 * ADMIN/DEPUTY_ADMIN 限定表示している「ダイレクトメール」機能の一部のため、
 * <b>ORGANIZATION スコープのみ</b> ADMIN 以上に限定する。<b>TEAM スコープは
 * {@code DirectMailScopeContractIT}「一般メンバーのテンプレート一覧は200（閲覧系は
 * checkMembership）」が意図的に固定した契約であり、これを崩してはならないため
 * checkMembership のまま維持する</b>（{@link DirectMailService#listMails} と同型のスコープ差分方針）。</p>
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
     * テンプレート一覧を取得する。ORGANIZATION スコープのみ ADMIN 以上に限定し、
     * TEAM スコープは従来どおり checkMembership のまま維持する（クラスコメント参照）。
     */
    public List<DirectMailTemplateResponse> listTemplates(String scopeType, Long scopeId, Long actorUserId) {
        checkReadAccess(scopeType, scopeId, actorUserId);
        List<DirectMailTemplateEntity> templates = templateRepository
                .findByScopeTypeAndScopeIdOrderByCreatedAtDesc(scopeType, scopeId);
        return directMailMapper.toTemplateResponseList(templates);
    }

    /**
     * 閲覧系（listTemplates）の認可判定。ORGANIZATION スコープのみ ADMIN 以上に限定し、
     * TEAM スコープは従来どおり checkMembership のまま維持する。
     */
    private void checkReadAccess(String scopeType, Long scopeId, Long actorUserId) {
        if ("ORGANIZATION".equals(scopeType)) {
            accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType);
        } else {
            accessControlService.checkMembership(actorUserId, scopeId, scopeType);
        }
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
