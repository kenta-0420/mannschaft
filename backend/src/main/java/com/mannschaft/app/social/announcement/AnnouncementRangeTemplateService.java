package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 告知ウィザード範囲テンプレートサービス（F02.8）。
 *
 * <p>告知ウィザードの対象範囲テンプレートの取得・作成・更新・削除を担当する。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class AnnouncementRangeTemplateService {

    private final AnnouncementRangeTemplateRepository templateRepository;
    private final AccessControlService accessControlService;

    /** 1スコープあたりのテンプレート上限件数 */
    private static final int TEMPLATE_LIMIT = 20;

    /**
     * スコープ内のテンプレート一覧を取得する（MEMBER 以上）。
     *
     * @param scopeType     スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId       スコープ ID
     * @param callerUserId  呼び出し元ユーザー ID
     * @return テンプレートリスト（作成日時降順）
     */
    public List<AnnouncementRangeTemplateEntity> findAll(String scopeType, Long scopeId, Long callerUserId) {
        accessControlService.checkMembership(callerUserId, scopeId, scopeType);
        AnnouncementScopeType scopeTypeEnum = AnnouncementScopeType.valueOf(scopeType);
        return templateRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(scopeTypeEnum, scopeId);
    }

    /**
     * テンプレートを新規作成する（ADMIN / DEPUTY_ADMIN のみ）。
     *
     * @param scopeType     スコープ種別
     * @param scopeId       スコープ ID
     * @param callerUserId  呼び出し元ユーザー ID
     * @param req           作成リクエスト
     * @return 作成されたテンプレートエンティティ
     */
    @Transactional
    public AnnouncementRangeTemplateEntity create(
            String scopeType, Long scopeId, Long callerUserId, AnnouncementRangeTemplateRequest req) {

        if (!accessControlService.isAdminOrAbove(callerUserId, scopeId, scopeType)) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_009);
        }

        AnnouncementScopeType scopeTypeEnum = AnnouncementScopeType.valueOf(scopeType);

        // 上限チェック（1スコープあたり20件）
        long count = templateRepository.countByScopeTypeAndScopeId(scopeTypeEnum, scopeId);
        if (count >= TEMPLATE_LIMIT) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_010);
        }

        // is_default 排他制御: 新規をデフォルトにする場合、既存のデフォルトを解除
        if (Boolean.TRUE.equals(req.getIsDefault())) {
            templateRepository.clearDefault(scopeTypeEnum, scopeId);
        }

        AnnouncementRangeTemplateEntity entity = AnnouncementRangeTemplateEntity.builder()
                .scopeType(scopeTypeEnum)
                .scopeId(scopeId)
                .name(req.getName())
                .targetRole(req.getTargetRole() != null ? req.getTargetRole() : "MEMBERS_AND_ABOVE")
                .targetTeamIds(req.getTargetTeamIdsJson())
                .preferredChannel(req.getPreferredChannel())
                .isDefault(Boolean.TRUE.equals(req.getIsDefault()))
                .createdBy(callerUserId)
                .build();

        return templateRepository.save(entity);
    }

    /**
     * テンプレートを更新する（ADMIN / DEPUTY_ADMIN のみ）。
     *
     * @param scopeType     スコープ種別
     * @param scopeId       スコープ ID
     * @param id            テンプレート ID
     * @param callerUserId  呼び出し元ユーザー ID
     * @param req           更新リクエスト
     * @return 更新されたテンプレートエンティティ
     */
    @Transactional
    public AnnouncementRangeTemplateEntity update(
            String scopeType, Long scopeId, Long id, Long callerUserId,
            AnnouncementRangeTemplateRequest req) {

        if (!accessControlService.isAdminOrAbove(callerUserId, scopeId, scopeType)) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_009);
        }

        AnnouncementScopeType scopeTypeEnum = AnnouncementScopeType.valueOf(scopeType);

        AnnouncementRangeTemplateEntity entity = templateRepository.findById(id)
                .filter(t -> t.getScopeType() == scopeTypeEnum && t.getScopeId().equals(scopeId))
                .orElseThrow(() -> new BusinessException(AnnouncementErrorCode.ANNOUNCE_008));

        boolean newIsDefault = Boolean.TRUE.equals(req.getIsDefault());

        // is_default 排他制御: 新規デフォルト指定かつ現在デフォルトでない場合、既存デフォルトを解除
        if (newIsDefault && !Boolean.TRUE.equals(entity.getIsDefault())) {
            templateRepository.clearDefault(scopeTypeEnum, scopeId);
        }

        entity.update(req.getName(), req.getTargetRole(), req.getTargetTeamIdsJson(),
                req.getPreferredChannel(), newIsDefault);

        return templateRepository.save(entity);
    }

    /**
     * テンプレートを削除する（ADMIN / DEPUTY_ADMIN のみ）。
     *
     * @param scopeType     スコープ種別
     * @param scopeId       スコープ ID
     * @param id            テンプレート ID
     * @param callerUserId  呼び出し元ユーザー ID
     */
    @Transactional
    public void delete(String scopeType, Long scopeId, Long id, Long callerUserId) {

        if (!accessControlService.isAdminOrAbove(callerUserId, scopeId, scopeType)) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_009);
        }

        AnnouncementScopeType scopeTypeEnum = AnnouncementScopeType.valueOf(scopeType);

        AnnouncementRangeTemplateEntity entity = templateRepository.findById(id)
                .filter(t -> t.getScopeType() == scopeTypeEnum && t.getScopeId().equals(scopeId))
                .orElseThrow(() -> new BusinessException(AnnouncementErrorCode.ANNOUNCE_008));

        templateRepository.delete(entity);
    }
}
