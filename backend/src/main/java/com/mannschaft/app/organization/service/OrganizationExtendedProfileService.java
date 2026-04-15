package com.mannschaft.app.organization.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PlainTextValidator;
import com.mannschaft.app.common.ProfileUrlValidator;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.ProfileVisibility;
import com.mannschaft.app.organization.dto.CreateCustomFieldRequest;
import com.mannschaft.app.organization.dto.CreateOfficerRequest;
import com.mannschaft.app.organization.dto.CustomFieldResponse;
import com.mannschaft.app.organization.dto.OfficerResponse;
import com.mannschaft.app.organization.dto.OrganizationProfileResponse;
import com.mannschaft.app.organization.dto.ReorderRequest;
import com.mannschaft.app.organization.dto.UpdateCustomFieldRequest;
import com.mannschaft.app.organization.dto.UpdateOfficerRequest;
import com.mannschaft.app.organization.dto.UpdateOrgProfileRequest;
import com.mannschaft.app.organization.entity.OrganizationCustomFieldEntity;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.entity.OrganizationOfficerEntity;
import com.mannschaft.app.organization.repository.OrganizationCustomFieldRepository;
import com.mannschaft.app.organization.repository.OrganizationOfficerRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 組織拡張プロフィールサービス。
 * 拡張プロフィール・役員・カスタムフィールドの CRUD を提供する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrganizationExtendedProfileService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationOfficerRepository officerRepository;
    private final OrganizationCustomFieldRepository customFieldRepository;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;

    // 文字数上限
    private static final int MAX_PHILOSOPHY_LENGTH = 2000;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_LABEL_LENGTH = 100;
    private static final int MAX_VALUE_LENGTH = 1000;

    // 件数上限
    private static final int MAX_OFFICERS = 50;
    private static final int MAX_CUSTOM_FIELDS = 20;

    // ========================================
    // 拡張プロフィール
    // ========================================

    /**
     * 組織の拡張プロフィールを一括更新する。
     * PATCH /organizations/{id}/profile
     *
     * @param userId リクエストユーザーID
     * @param orgId  組織ID
     * @param req    更新内容
     * @return 更新後のプロフィールレスポンス
     */
    @Transactional
    public ApiResponse<OrganizationProfileResponse> updateProfile(
            Long userId, Long orgId, UpdateOrgProfileRequest req) {

        // ADMIN/DEPUTY_ADMIN 権限チェック
        if (!accessControlService.isAdminOrAbove(userId, orgId, "ORGANIZATION")) {
            throw new BusinessException(OrgErrorCode.ORG_048);
        }

        OrganizationEntity org = findOrgOrThrow(orgId);

        // URL バリデーション
        if (req.getHomepageUrl() != null && !ProfileUrlValidator.isValid(req.getHomepageUrl())) {
            throw new BusinessException(OrgErrorCode.ORG_040);
        }

        // established_date ペアチェック
        boolean hasDate = req.getEstablishedDate() != null;
        boolean hasPrecision = req.getEstablishedDatePrecision() != null;
        if (hasDate != hasPrecision) {
            throw new BusinessException(OrgErrorCode.ORG_045);
        }

        // philosophy の HTML 検出・文字数チェック
        validateTextLength(req.getPhilosophy(), MAX_PHILOSOPHY_LENGTH);

        // URL の正規化
        String normalizedUrl = req.getHomepageUrl() != null
                ? ProfileUrlValidator.normalize(req.getHomepageUrl().trim())
                : org.getHomepageUrl();

        // philosophy の trim・null 化
        String philosophy = req.getPhilosophy() != null
                ? (req.getPhilosophy().trim().isEmpty() ? null : req.getPhilosophy().trim())
                : org.getPhilosophy();

        // profile_visibility
        ProfileVisibility profileVisibility = req.getProfileVisibility() != null
                ? req.getProfileVisibility()
                : org.getProfileVisibility();

        OrganizationEntity updated = org.toBuilder()
                .homepageUrl(normalizedUrl)
                .establishedDate(hasDate ? req.getEstablishedDate() : org.getEstablishedDate())
                .establishedDatePrecision(hasPrecision ? req.getEstablishedDatePrecision() : org.getEstablishedDatePrecision())
                .philosophy(philosophy)
                .profileVisibility(profileVisibility)
                .build();
        organizationRepository.save(updated);

        auditLogService.record(
                "ORGANIZATION_PROFILE_UPDATE",
                userId, null, null, orgId, null, null, null,
                "{\"action\":\"profile_update\"}");

        log.info("組織プロフィール更新完了: orgId={}, userId={}", orgId, userId);

        return ApiResponse.of(toProfileResponse(updated));
    }

    // ========================================
    // 役員
    // ========================================

    /**
     * 役員一覧を取得する（可視性ルールを適用）。
     * GET /organizations/{id}/officers
     *
     * @param userId            リクエストユーザーID
     * @param orgId             組織ID
     * @param visibilityPreview true の場合は ADMIN/DEPUTY_ADMIN のみ全件返却
     * @return 役員一覧
     */
    public ApiResponse<List<OfficerResponse>> getOfficers(
            Long userId, Long orgId, boolean visibilityPreview) {

        OrganizationEntity org = findOrgOrThrow(orgId);
        boolean isMember = accessControlService.isMember(userId, orgId, "ORGANIZATION");
        boolean isAdminOrAbove = accessControlService.isAdminOrAbove(userId, orgId, "ORGANIZATION");

        // PRIVATE 組織かつ非メンバー → 403
        if (org.getVisibility() == OrganizationEntity.Visibility.PRIVATE && !isMember) {
            throw new BusinessException(OrgErrorCode.ORG_048);
        }

        // profile_visibility.officers == false かつ非メンバー → 空リスト
        ProfileVisibility visibility = org.getProfileVisibility();
        if (visibility != null && !visibility.isOfficersVisible() && !isMember) {
            return ApiResponse.of(List.of());
        }

        List<OrganizationOfficerEntity> officers =
                officerRepository.findByOrganizationIdOrderByDisplayOrderAsc(orgId);

        // visibilityPreview=true の場合は ADMIN/DEPUTY_ADMIN チェック → 全件 + フラグ付き
        if (visibilityPreview) {
            if (!isAdminOrAbove) {
                throw new BusinessException(OrgErrorCode.ORG_048);
            }
            List<OfficerResponse> responses = officers.stream()
                    .map(o -> toOfficerResponse(o, true))
                    .toList();
            return ApiResponse.of(responses);
        }

        // 通常取得: is_visible == false のエントリは非メンバーにはフィルタリング
        List<OfficerResponse> responses = officers.stream()
                .filter(o -> isMember || Boolean.TRUE.equals(o.getIsVisible()))
                .map(o -> toOfficerResponse(o, false))
                .toList();

        return ApiResponse.of(responses);
    }

    /**
     * 役員を追加する（最大50件）。
     * POST /organizations/{id}/officers
     *
     * @param userId リクエストユーザーID
     * @param orgId  組織ID
     * @param req    作成内容
     * @return 作成した役員
     */
    @Transactional
    public ApiResponse<OfficerResponse> createOfficer(
            Long userId, Long orgId, CreateOfficerRequest req) {

        if (!accessControlService.isAdminOrAbove(userId, orgId, "ORGANIZATION")) {
            throw new BusinessException(OrgErrorCode.ORG_048);
        }

        findOrgOrThrow(orgId);

        // 上限チェック
        if (officerRepository.countByOrganizationId(orgId) >= MAX_OFFICERS) {
            throw new BusinessException(OrgErrorCode.ORG_041);
        }

        // 文字数バリデーション
        validateTextLength(req.getName(), MAX_NAME_LENGTH);
        validateTextLength(req.getTitle(), MAX_NAME_LENGTH);

        // 既存最大 displayOrder を取得して +1
        List<OrganizationOfficerEntity> existing =
                officerRepository.findByOrganizationIdOrderByDisplayOrderAsc(orgId);
        int nextOrder = existing.isEmpty()
                ? 1
                : existing.get(existing.size() - 1).getDisplayOrder() + 1;

        OrganizationOfficerEntity officer = OrganizationOfficerEntity.builder()
                .organizationId(orgId)
                .name(req.getName().trim())
                .title(req.getTitle().trim())
                .displayOrder(nextOrder)
                .isVisible(req.getIsVisible() != null ? req.getIsVisible() : true)
                .build();
        officerRepository.save(officer);

        auditLogService.record(
                "ORGANIZATION_OFFICER_CREATE",
                userId, null, null, orgId, null, null, null,
                "{\"officerId\":" + officer.getId() + "}");

        log.info("組織役員追加完了: orgId={}, officerId={}", orgId, officer.getId());
        return ApiResponse.of(toOfficerResponse(officer, false));
    }

    /**
     * 役員を更新する。
     * PATCH /organizations/{id}/officers/{officerId}
     *
     * @param userId    リクエストユーザーID
     * @param orgId     組織ID
     * @param officerId 役員ID
     * @param req       更新内容
     * @return 更新後の役員
     */
    @Transactional
    public ApiResponse<OfficerResponse> updateOfficer(
            Long userId, Long orgId, Long officerId, UpdateOfficerRequest req) {

        if (!accessControlService.isAdminOrAbove(userId, orgId, "ORGANIZATION")) {
            throw new BusinessException(OrgErrorCode.ORG_048);
        }

        findOrgOrThrow(orgId);
        OrganizationOfficerEntity officer = findOfficerOrThrow(officerId, orgId);

        // 文字数バリデーション（指定された場合のみ）
        if (req.getName() != null) validateTextLength(req.getName(), MAX_NAME_LENGTH);
        if (req.getTitle() != null) validateTextLength(req.getTitle(), MAX_NAME_LENGTH);

        String newName = req.getName() != null ? req.getName().trim() : officer.getName();
        String newTitle = req.getTitle() != null ? req.getTitle().trim() : officer.getTitle();
        boolean newVisible = req.getIsVisible() != null ? req.getIsVisible() : officer.getIsVisible();

        officer.update(newName, newTitle, newVisible);
        officerRepository.save(officer);

        auditLogService.record(
                "ORGANIZATION_OFFICER_UPDATE",
                userId, null, null, orgId, null, null, null,
                "{\"officerId\":" + officerId + "}");

        log.info("組織役員更新完了: orgId={}, officerId={}", orgId, officerId);
        return ApiResponse.of(toOfficerResponse(officer, false));
    }

    /**
     * 役員を削除する（物理削除）。
     * DELETE /organizations/{id}/officers/{officerId}
     *
     * @param userId    リクエストユーザーID
     * @param orgId     組織ID
     * @param officerId 役員ID
     */
    @Transactional
    public void deleteOfficer(Long userId, Long orgId, Long officerId) {

        if (!accessControlService.isAdminOrAbove(userId, orgId, "ORGANIZATION")) {
            throw new BusinessException(OrgErrorCode.ORG_048);
        }

        findOrgOrThrow(orgId);
        OrganizationOfficerEntity officer = findOfficerOrThrow(officerId, orgId);
        officerRepository.delete(officer);

        auditLogService.record(
                "ORGANIZATION_OFFICER_DELETE",
                userId, null, null, orgId, null, null, null,
                "{\"officerId\":" + officerId + "}");

        log.info("組織役員削除完了: orgId={}, officerId={}", orgId, officerId);
    }

    /**
     * 役員の表示順を並び替える。
     * PUT /organizations/{id}/officers/reorder
     *
     * @param userId リクエストユーザーID
     * @param orgId  組織ID
     * @param req    並び替えリクエスト
     */
    @Transactional
    public void reorderOfficers(Long userId, Long orgId, ReorderRequest req) {

        if (!accessControlService.isAdminOrAbove(userId, orgId, "ORGANIZATION")) {
            throw new BusinessException(OrgErrorCode.ORG_048);
        }

        findOrgOrThrow(orgId);

        List<OrganizationOfficerEntity> officers =
                officerRepository.findByOrganizationIdOrderByDisplayOrderAsc(orgId);

        // リクエストが全役員IDを網羅しているか検証
        Set<Long> existingIds = officers.stream()
                .map(o -> o.getId())
                .collect(Collectors.toSet());
        Set<Long> requestIds = req.getOrders().stream()
                .map(ReorderRequest.OrderItem::getId)
                .collect(Collectors.toSet());

        if (!existingIds.equals(requestIds)) {
            throw new BusinessException(OrgErrorCode.ORG_042);
        }

        // 並び替え適用
        officers.forEach(officer -> {
            req.getOrders().stream()
                    .filter(item -> item.getId().equals(officer.getId()))
                    .findFirst()
                    .ifPresent(item -> officer.updateDisplayOrder(item.getDisplayOrder()));
        });
        officerRepository.saveAll(officers);

        log.info("組織役員並び替え完了: orgId={}", orgId);
    }

    // ========================================
    // カスタムフィールド
    // ========================================

    /**
     * カスタムフィールド一覧を取得する（可視性ルールを適用）。
     * GET /organizations/{id}/custom-fields
     *
     * @param userId            リクエストユーザーID
     * @param orgId             組織ID
     * @param visibilityPreview true の場合は ADMIN/DEPUTY_ADMIN のみ全件返却
     * @return カスタムフィールド一覧
     */
    public ApiResponse<List<CustomFieldResponse>> getCustomFields(
            Long userId, Long orgId, boolean visibilityPreview) {

        OrganizationEntity org = findOrgOrThrow(orgId);
        boolean isMember = accessControlService.isMember(userId, orgId, "ORGANIZATION");
        boolean isAdminOrAbove = accessControlService.isAdminOrAbove(userId, orgId, "ORGANIZATION");

        // PRIVATE 組織かつ非メンバー → 403
        if (org.getVisibility() == OrganizationEntity.Visibility.PRIVATE && !isMember) {
            throw new BusinessException(OrgErrorCode.ORG_048);
        }

        // profile_visibility.custom_fields == false かつ非メンバー → 空リスト
        ProfileVisibility visibility = org.getProfileVisibility();
        if (visibility != null && !visibility.isCustomFieldsVisible() && !isMember) {
            return ApiResponse.of(List.of());
        }

        List<OrganizationCustomFieldEntity> fields =
                customFieldRepository.findByOrganizationIdOrderByDisplayOrderAsc(orgId);

        // visibilityPreview=true の場合は ADMIN/DEPUTY_ADMIN チェック → 全件 + フラグ付き
        if (visibilityPreview) {
            if (!isAdminOrAbove) {
                throw new BusinessException(OrgErrorCode.ORG_048);
            }
            List<CustomFieldResponse> responses = fields.stream()
                    .map(f -> toCustomFieldResponse(f, true))
                    .toList();
            return ApiResponse.of(responses);
        }

        // 通常取得: is_visible == false のエントリは非メンバーにはフィルタリング
        List<CustomFieldResponse> responses = fields.stream()
                .filter(f -> isMember || Boolean.TRUE.equals(f.getIsVisible()))
                .map(f -> toCustomFieldResponse(f, false))
                .toList();

        return ApiResponse.of(responses);
    }

    /**
     * カスタムフィールドを追加する（最大20件）。
     * POST /organizations/{id}/custom-fields
     *
     * @param userId リクエストユーザーID
     * @param orgId  組織ID
     * @param req    作成内容
     * @return 作成したカスタムフィールド
     */
    @Transactional
    public ApiResponse<CustomFieldResponse> createCustomField(
            Long userId, Long orgId, CreateCustomFieldRequest req) {

        if (!accessControlService.isAdminOrAbove(userId, orgId, "ORGANIZATION")) {
            throw new BusinessException(OrgErrorCode.ORG_048);
        }

        findOrgOrThrow(orgId);

        // 上限チェック
        if (customFieldRepository.countByOrganizationId(orgId) >= MAX_CUSTOM_FIELDS) {
            throw new BusinessException(OrgErrorCode.ORG_043);
        }

        // 文字数バリデーション
        validateTextLength(req.getLabel(), MAX_LABEL_LENGTH);
        validateTextLength(req.getValue(), MAX_VALUE_LENGTH);

        // 既存最大 displayOrder を取得して +1
        List<OrganizationCustomFieldEntity> existing =
                customFieldRepository.findByOrganizationIdOrderByDisplayOrderAsc(orgId);
        int nextOrder = existing.isEmpty()
                ? 1
                : existing.get(existing.size() - 1).getDisplayOrder() + 1;

        OrganizationCustomFieldEntity field = OrganizationCustomFieldEntity.builder()
                .organizationId(orgId)
                .label(req.getLabel().trim())
                .value(req.getValue().trim())
                .displayOrder(nextOrder)
                .isVisible(req.getIsVisible() != null ? req.getIsVisible() : true)
                .build();
        customFieldRepository.save(field);

        auditLogService.record(
                "ORGANIZATION_CUSTOM_FIELD_CREATE",
                userId, null, null, orgId, null, null, null,
                "{\"fieldId\":" + field.getId() + "}");

        log.info("組織カスタムフィールド追加完了: orgId={}, fieldId={}", orgId, field.getId());
        return ApiResponse.of(toCustomFieldResponse(field, false));
    }

    /**
     * カスタムフィールドを更新する。
     * PATCH /organizations/{id}/custom-fields/{fieldId}
     *
     * @param userId  リクエストユーザーID
     * @param orgId   組織ID
     * @param fieldId カスタムフィールドID
     * @param req     更新内容
     * @return 更新後のカスタムフィールド
     */
    @Transactional
    public ApiResponse<CustomFieldResponse> updateCustomField(
            Long userId, Long orgId, Long fieldId, UpdateCustomFieldRequest req) {

        if (!accessControlService.isAdminOrAbove(userId, orgId, "ORGANIZATION")) {
            throw new BusinessException(OrgErrorCode.ORG_048);
        }

        findOrgOrThrow(orgId);
        OrganizationCustomFieldEntity field = findCustomFieldOrThrow(fieldId, orgId);

        // 文字数バリデーション（指定された場合のみ）
        if (req.getLabel() != null) validateTextLength(req.getLabel(), MAX_LABEL_LENGTH);
        if (req.getValue() != null) validateTextLength(req.getValue(), MAX_VALUE_LENGTH);

        String newLabel = req.getLabel() != null ? req.getLabel().trim() : field.getLabel();
        String newValue = req.getValue() != null ? req.getValue().trim() : field.getValue();
        boolean newVisible = req.getIsVisible() != null ? req.getIsVisible() : field.getIsVisible();

        field.update(newLabel, newValue, newVisible);
        customFieldRepository.save(field);

        auditLogService.record(
                "ORGANIZATION_CUSTOM_FIELD_UPDATE",
                userId, null, null, orgId, null, null, null,
                "{\"fieldId\":" + fieldId + "}");

        log.info("組織カスタムフィールド更新完了: orgId={}, fieldId={}", orgId, fieldId);
        return ApiResponse.of(toCustomFieldResponse(field, false));
    }

    /**
     * カスタムフィールドを削除する（物理削除）。
     * DELETE /organizations/{id}/custom-fields/{fieldId}
     *
     * @param userId  リクエストユーザーID
     * @param orgId   組織ID
     * @param fieldId カスタムフィールドID
     */
    @Transactional
    public void deleteCustomField(Long userId, Long orgId, Long fieldId) {

        if (!accessControlService.isAdminOrAbove(userId, orgId, "ORGANIZATION")) {
            throw new BusinessException(OrgErrorCode.ORG_048);
        }

        findOrgOrThrow(orgId);
        OrganizationCustomFieldEntity field = findCustomFieldOrThrow(fieldId, orgId);
        customFieldRepository.delete(field);

        auditLogService.record(
                "ORGANIZATION_CUSTOM_FIELD_DELETE",
                userId, null, null, orgId, null, null, null,
                "{\"fieldId\":" + fieldId + "}");

        log.info("組織カスタムフィールド削除完了: orgId={}, fieldId={}", orgId, fieldId);
    }

    /**
     * カスタムフィールドの表示順を並び替える。
     * PUT /organizations/{id}/custom-fields/reorder
     *
     * @param userId リクエストユーザーID
     * @param orgId  組織ID
     * @param req    並び替えリクエスト
     */
    @Transactional
    public void reorderCustomFields(Long userId, Long orgId, ReorderRequest req) {

        if (!accessControlService.isAdminOrAbove(userId, orgId, "ORGANIZATION")) {
            throw new BusinessException(OrgErrorCode.ORG_048);
        }

        findOrgOrThrow(orgId);

        List<OrganizationCustomFieldEntity> fields =
                customFieldRepository.findByOrganizationIdOrderByDisplayOrderAsc(orgId);

        // リクエストが全フィールド ID を網羅しているか検証
        Set<Long> existingIds = fields.stream()
                .map(f -> f.getId())
                .collect(Collectors.toSet());
        Set<Long> requestIds = req.getOrders().stream()
                .map(ReorderRequest.OrderItem::getId)
                .collect(Collectors.toSet());

        if (!existingIds.equals(requestIds)) {
            throw new BusinessException(OrgErrorCode.ORG_044);
        }

        // 並び替え適用
        fields.forEach(field -> {
            req.getOrders().stream()
                    .filter(item -> item.getId().equals(field.getId()))
                    .findFirst()
                    .ifPresent(item -> field.updateDisplayOrder(item.getDisplayOrder()));
        });
        customFieldRepository.saveAll(fields);

        log.info("組織カスタムフィールド並び替え完了: orgId={}", orgId);
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    private OrganizationEntity findOrgOrThrow(Long orgId) {
        return organizationRepository.findById(orgId)
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_001));
    }

    private OrganizationOfficerEntity findOfficerOrThrow(Long officerId, Long orgId) {
        return officerRepository.findById(officerId)
                .filter(o -> orgId.equals(o.getOrganizationId()))
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_050));
    }

    private OrganizationCustomFieldEntity findCustomFieldOrThrow(Long fieldId, Long orgId) {
        return customFieldRepository.findById(fieldId)
                .filter(f -> orgId.equals(f.getOrganizationId()))
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_051));
    }

    private void validateTextLength(String text, int maxLength) {
        if (text == null) return;
        String trimmed = text.trim();
        if (PlainTextValidator.containsHtml(trimmed)) {
            throw new BusinessException(OrgErrorCode.ORG_046);
        }
        if (codePointLength(trimmed) > maxLength) {
            throw new BusinessException(OrgErrorCode.ORG_046);
        }
    }

    private int codePointLength(String text) {
        return Character.codePointCount(text, 0, text.length());
    }

    private OrganizationProfileResponse toProfileResponse(OrganizationEntity org) {
        return OrganizationProfileResponse.builder()
                .id(org.getId())
                .homepageUrl(org.getHomepageUrl())
                .establishedDate(org.getEstablishedDate())
                .establishedDatePrecision(org.getEstablishedDatePrecision())
                .philosophy(org.getPhilosophy())
                .profileVisibility(org.getProfileVisibility())
                .build();
    }

    private OfficerResponse toOfficerResponse(OrganizationOfficerEntity officer, boolean includePublicFlag) {
        return OfficerResponse.builder()
                .id(officer.getId())
                .organizationId(officer.getOrganizationId())
                .name(officer.getName())
                .title(officer.getTitle())
                .displayOrder(officer.getDisplayOrder())
                .isVisible(officer.getIsVisible())
                .isPubliclyVisible(includePublicFlag ? officer.getIsVisible() : null)
                .build();
    }

    private CustomFieldResponse toCustomFieldResponse(OrganizationCustomFieldEntity field, boolean includePublicFlag) {
        return CustomFieldResponse.builder()
                .id(field.getId())
                .organizationId(field.getOrganizationId())
                .label(field.getLabel())
                .value(field.getValue())
                .displayOrder(field.getDisplayOrder())
                .isVisible(field.getIsVisible())
                .isPubliclyVisible(includePublicFlag ? field.getIsVisible() : null)
                .build();
    }
}
