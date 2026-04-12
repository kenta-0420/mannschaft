package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationTemplateEntity;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F04.9 確認通知テンプレートサービス。
 *
 * <p>確認通知テンプレートの CRUD を提供する。
 * 削除は論理削除（soft delete）のみサポートし、削除後もIDによる参照は可能。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConfirmableNotificationTemplateService {

    private final ConfirmableNotificationTemplateRepository templateRepository;
    private final UserRepository userRepository;

    /**
     * スコープ配下の有効なテンプレート一覧を取得する（論理削除除外）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return テンプレートリスト
     */
    public List<ConfirmableNotificationTemplateEntity> findAll(ScopeType scopeType, Long scopeId) {
        return templateRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(scopeType, scopeId);
    }

    /**
     * テンプレートをIDで取得する（論理削除済みを除く）。
     *
     * @param templateId テンプレートID
     * @return テンプレートエンティティ
     * @throws BusinessException テンプレートが存在しないまたは削除済みの場合
     */
    public ConfirmableNotificationTemplateEntity findById(Long templateId) {
        return templateRepository.findByIdAndDeletedAtIsNull(templateId)
                .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.TEMPLATE_NOT_FOUND));
    }

    /**
     * テンプレートを新規作成する。
     *
     * @param scopeType       スコープ種別
     * @param scopeId         スコープID
     * @param name            管理用テンプレート名
     * @param title           テンプレートタイトル
     * @param body            テンプレート本文（任意）
     * @param defaultPriority デフォルト優先度（NULL の場合は NORMAL）
     * @param createdByUserId 作成者ユーザーID
     * @return 作成されたテンプレートエンティティ
     */
    @Transactional
    public ConfirmableNotificationTemplateEntity create(
            ScopeType scopeType,
            Long scopeId,
            String name,
            String title,
            String body,
            ConfirmableNotificationPriority defaultPriority,
            Long createdByUserId) {

        UserEntity createdBy = userRepository.findById(createdByUserId).orElse(null);

        ConfirmableNotificationTemplateEntity template =
                ConfirmableNotificationTemplateEntity.builder()
                        .scopeType(scopeType)
                        .scopeId(scopeId)
                        .name(name)
                        .title(title)
                        .body(body)
                        .defaultPriority(defaultPriority != null
                                ? defaultPriority
                                : ConfirmableNotificationPriority.NORMAL)
                        .createdBy(createdBy)
                        .build();

        ConfirmableNotificationTemplateEntity saved = templateRepository.save(template);
        log.info("確認通知テンプレート作成: templateId={}, scopeType={}, scopeId={}",
                saved.getId(), scopeType, scopeId);
        return saved;
    }

    /**
     * テンプレートを更新する。
     *
     * @param templateId      テンプレートID
     * @param name            管理用テンプレート名
     * @param title           テンプレートタイトル
     * @param body            テンプレート本文（任意）
     * @param defaultPriority デフォルト優先度
     * @return 更新されたテンプレートエンティティ
     * @throws BusinessException テンプレートが存在しないまたは削除済みの場合
     */
    @Transactional
    public ConfirmableNotificationTemplateEntity update(
            Long templateId,
            String name,
            String title,
            String body,
            ConfirmableNotificationPriority defaultPriority) {

        // 論理削除済みのテンプレートは更新不可
        ConfirmableNotificationTemplateEntity existing = findById(templateId);

        ConfirmableNotificationTemplateEntity updated = existing.toBuilder()
                .name(name)
                .title(title)
                .body(body)
                .defaultPriority(defaultPriority != null
                        ? defaultPriority
                        : existing.getDefaultPriority())
                .build();

        ConfirmableNotificationTemplateEntity saved = templateRepository.save(updated);
        log.info("確認通知テンプレート更新: templateId={}", templateId);
        return saved;
    }

    /**
     * テンプレートを論理削除する。
     *
     * <p>物理削除は行わない。削除後も確認通知の {@code template_id} 参照が壊れないよう保持する。</p>
     *
     * @param templateId テンプレートID
     * @throws BusinessException テンプレートが存在しないまたは既に削除済みの場合
     */
    @Transactional
    public void softDelete(Long templateId) {
        ConfirmableNotificationTemplateEntity template = findById(templateId);
        template.softDelete();
        templateRepository.save(template);
        log.info("確認通知テンプレート論理削除: templateId={}", templateId);
    }
}
