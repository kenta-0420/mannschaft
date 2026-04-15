package com.mannschaft.app.team.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PlainTextValidator;
import com.mannschaft.app.common.ProfileUrlValidator;
import com.mannschaft.app.organization.ProfileVisibility;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.dto.CreateTeamCustomFieldRequest;
import com.mannschaft.app.team.dto.CreateTeamOfficerRequest;
import com.mannschaft.app.team.dto.TeamCustomFieldResponse;
import com.mannschaft.app.team.dto.TeamOfficerResponse;
import com.mannschaft.app.team.dto.TeamProfileResponse;
import com.mannschaft.app.team.dto.TeamReorderRequest;
import com.mannschaft.app.team.dto.UpdateTeamCustomFieldRequest;
import com.mannschaft.app.team.dto.UpdateTeamOfficerRequest;
import com.mannschaft.app.team.dto.UpdateTeamProfileRequest;
import com.mannschaft.app.team.entity.TeamCustomFieldEntity;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOfficerEntity;
import com.mannschaft.app.team.repository.TeamCustomFieldRepository;
import com.mannschaft.app.team.repository.TeamOfficerRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * チーム拡張プロフィールサービス。
 * 拡張プロフィール・役員・カスタムフィールドの CRUD を提供する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TeamExtendedProfileService {

    private final TeamRepository teamRepository;
    private final TeamOfficerRepository officerRepository;
    private final TeamCustomFieldRepository customFieldRepository;
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
     * チームの拡張プロフィールを取得する。
     * GET /teams/{id}/profile
     *
     * @param userId リクエストユーザーID（null 可: 非ログイン）
     * @param teamId チームID
     * @return 現在のプロフィール
     */
    public ApiResponse<TeamProfileResponse> getProfile(Long userId, Long teamId) {
        TeamEntity team = findTeamOrThrow(teamId);
        boolean isMember = accessControlService.isMember(userId, teamId, "TEAM");
        if (team.getVisibility() == TeamEntity.Visibility.PRIVATE && !isMember) {
            throw new BusinessException(TeamErrorCode.TEAM_048);
        }
        return ApiResponse.of(toProfileResponse(team));
    }

    /**
     * チームの拡張プロフィールを一括更新する。
     * PATCH /teams/{id}/profile
     *
     * @param userId  リクエストユーザーID
     * @param teamId  チームID
     * @param req     更新内容
     * @return 更新後のプロフィールレスポンス
     */
    @Transactional
    public ApiResponse<TeamProfileResponse> updateProfile(
            Long userId, Long teamId, UpdateTeamProfileRequest req) {

        // ADMIN/DEPUTY_ADMIN 権限チェック
        if (!accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            throw new BusinessException(TeamErrorCode.TEAM_048);
        }

        TeamEntity team = findTeamOrThrow(teamId);

        // URL バリデーション
        if (req.getHomepageUrl() != null && !ProfileUrlValidator.isValid(req.getHomepageUrl())) {
            throw new BusinessException(TeamErrorCode.TEAM_040);
        }

        // established_date ペアチェック
        boolean hasDate = req.getEstablishedDate() != null;
        boolean hasPrecision = req.getEstablishedDatePrecision() != null;
        if (hasDate != hasPrecision) {
            throw new BusinessException(TeamErrorCode.TEAM_045);
        }

        // philosophy の HTML 検出・文字数チェック
        validateTextLength(req.getPhilosophy(), MAX_PHILOSOPHY_LENGTH, TeamErrorCode.TEAM_046);

        // URL の正規化
        String normalizedUrl = req.getHomepageUrl() != null
                ? ProfileUrlValidator.normalize(req.getHomepageUrl().trim())
                : team.getHomepageUrl();

        // philosophy の trim・null 化
        String philosophy = req.getPhilosophy() != null
                ? (req.getPhilosophy().trim().isEmpty() ? null : req.getPhilosophy().trim())
                : team.getPhilosophy();

        // profile_visibility
        ProfileVisibility profileVisibility = req.getProfileVisibility() != null
                ? req.getProfileVisibility()
                : team.getProfileVisibility();

        TeamEntity updated = team.toBuilder()
                .homepageUrl(normalizedUrl)
                .establishedDate(hasDate ? req.getEstablishedDate() : team.getEstablishedDate())
                .establishedDatePrecision(hasPrecision ? req.getEstablishedDatePrecision() : team.getEstablishedDatePrecision())
                .philosophy(philosophy)
                .profileVisibility(profileVisibility)
                .build();
        teamRepository.save(updated);

        auditLogService.record(
                "TEAM_PROFILE_UPDATE",
                userId, null, teamId, null, null, null, null,
                "{\"action\":\"profile_update\"}");

        log.info("チームプロフィール更新完了: teamId={}, userId={}", teamId, userId);

        return ApiResponse.of(toProfileResponse(updated));
    }

    // ========================================
    // 役員
    // ========================================

    /**
     * 役員一覧を取得する（可視性ルールを適用）。
     * GET /teams/{id}/officers
     *
     * @param userId            リクエストユーザーID
     * @param teamId            チームID
     * @param visibilityPreview true の場合は ADMIN/DEPUTY_ADMIN のみ全件返却
     * @return 役員一覧
     */
    public ApiResponse<List<TeamOfficerResponse>> getOfficers(
            Long userId, Long teamId, boolean visibilityPreview) {

        TeamEntity team = findTeamOrThrow(teamId);
        boolean isMember = accessControlService.isMember(userId, teamId, "TEAM");
        boolean isAdminOrAbove = accessControlService.isAdminOrAbove(userId, teamId, "TEAM");

        // PRIVATE チームかつ非メンバー → 403
        if (team.getVisibility() == TeamEntity.Visibility.PRIVATE && !isMember) {
            throw new BusinessException(TeamErrorCode.TEAM_048);
        }

        // profile_visibility.officers == false かつ非メンバー → 空リスト
        ProfileVisibility visibility = team.getProfileVisibility();
        if (visibility != null && !visibility.isOfficersVisible() && !isMember) {
            return ApiResponse.of(List.of());
        }

        List<TeamOfficerEntity> officers =
                officerRepository.findByTeamIdOrderByDisplayOrderAsc(teamId);

        // visibilityPreview=true の場合は ADMIN/DEPUTY_ADMIN チェック → 全件 + フラグ付き
        if (visibilityPreview) {
            if (!isAdminOrAbove) {
                throw new BusinessException(TeamErrorCode.TEAM_048);
            }
            List<TeamOfficerResponse> responses = officers.stream()
                    .map(o -> toOfficerResponse(o, true))
                    .toList();
            return ApiResponse.of(responses);
        }

        // 通常取得: is_visible == false のエントリは非メンバーにはフィルタリング
        List<TeamOfficerResponse> responses = officers.stream()
                .filter(o -> isMember || Boolean.TRUE.equals(o.getIsVisible()))
                .map(o -> toOfficerResponse(o, false))
                .toList();

        return ApiResponse.of(responses);
    }

    /**
     * 役員を追加する（最大50件）。
     * POST /teams/{id}/officers
     *
     * @param userId リクエストユーザーID
     * @param teamId チームID
     * @param req    作成内容
     * @return 作成した役員
     */
    @Transactional
    public ApiResponse<TeamOfficerResponse> createOfficer(
            Long userId, Long teamId, CreateTeamOfficerRequest req) {

        if (!accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            throw new BusinessException(TeamErrorCode.TEAM_048);
        }

        findTeamOrThrow(teamId);

        // 上限チェック
        if (officerRepository.countByTeamId(teamId) >= MAX_OFFICERS) {
            throw new BusinessException(TeamErrorCode.TEAM_041);
        }

        // 文字数バリデーション
        validateTextLength(req.getName(), MAX_NAME_LENGTH, TeamErrorCode.TEAM_046);
        validateTextLength(req.getTitle(), MAX_NAME_LENGTH, TeamErrorCode.TEAM_046);

        // 既存最大 displayOrder を取得して +1
        List<TeamOfficerEntity> existing =
                officerRepository.findByTeamIdOrderByDisplayOrderAsc(teamId);
        int nextOrder = existing.isEmpty()
                ? 1
                : existing.get(existing.size() - 1).getDisplayOrder() + 1;

        TeamOfficerEntity officer = TeamOfficerEntity.builder()
                .teamId(teamId)
                .name(req.getName().trim())
                .title(req.getTitle().trim())
                .displayOrder(nextOrder)
                .isVisible(req.getIsVisible() != null ? req.getIsVisible() : true)
                .build();
        officerRepository.save(officer);

        auditLogService.record(
                "TEAM_OFFICER_CREATE",
                userId, null, teamId, null, null, null, null,
                "{\"officerId\":" + officer.getId() + "}");

        log.info("チーム役員追加完了: teamId={}, officerId={}", teamId, officer.getId());
        return ApiResponse.of(toOfficerResponse(officer, false));
    }

    /**
     * 役員を更新する。
     * PATCH /teams/{id}/officers/{officerId}
     *
     * @param userId    リクエストユーザーID
     * @param teamId    チームID
     * @param officerId 役員ID
     * @param req       更新内容
     * @return 更新後の役員
     */
    @Transactional
    public ApiResponse<TeamOfficerResponse> updateOfficer(
            Long userId, Long teamId, Long officerId, UpdateTeamOfficerRequest req) {

        if (!accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            throw new BusinessException(TeamErrorCode.TEAM_048);
        }

        findTeamOrThrow(teamId);
        TeamOfficerEntity officer = findOfficerOrThrow(officerId, teamId);

        // 文字数バリデーション（指定された場合のみ）
        if (req.getName() != null) validateTextLength(req.getName(), MAX_NAME_LENGTH, TeamErrorCode.TEAM_046);
        if (req.getTitle() != null) validateTextLength(req.getTitle(), MAX_NAME_LENGTH, TeamErrorCode.TEAM_046);

        String newName = req.getName() != null ? req.getName().trim() : officer.getName();
        String newTitle = req.getTitle() != null ? req.getTitle().trim() : officer.getTitle();
        boolean newVisible = req.getIsVisible() != null ? req.getIsVisible() : officer.getIsVisible();

        officer.update(newName, newTitle, newVisible);
        officerRepository.save(officer);

        auditLogService.record(
                "TEAM_OFFICER_UPDATE",
                userId, null, teamId, null, null, null, null,
                "{\"officerId\":" + officerId + "}");

        log.info("チーム役員更新完了: teamId={}, officerId={}", teamId, officerId);
        return ApiResponse.of(toOfficerResponse(officer, false));
    }

    /**
     * 役員を削除する（物理削除）。
     * DELETE /teams/{id}/officers/{officerId}
     *
     * @param userId    リクエストユーザーID
     * @param teamId    チームID
     * @param officerId 役員ID
     */
    @Transactional
    public void deleteOfficer(Long userId, Long teamId, Long officerId) {

        if (!accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            throw new BusinessException(TeamErrorCode.TEAM_048);
        }

        findTeamOrThrow(teamId);
        TeamOfficerEntity officer = findOfficerOrThrow(officerId, teamId);
        officerRepository.delete(officer);

        auditLogService.record(
                "TEAM_OFFICER_DELETE",
                userId, null, teamId, null, null, null, null,
                "{\"officerId\":" + officerId + "}");

        log.info("チーム役員削除完了: teamId={}, officerId={}", teamId, officerId);
    }

    /**
     * 役員の表示順を並び替える。
     * PUT /teams/{id}/officers/reorder
     *
     * @param userId リクエストユーザーID
     * @param teamId チームID
     * @param req    並び替えリクエスト
     */
    @Transactional
    public void reorderOfficers(Long userId, Long teamId, TeamReorderRequest req) {

        if (!accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            throw new BusinessException(TeamErrorCode.TEAM_048);
        }

        findTeamOrThrow(teamId);

        List<TeamOfficerEntity> officers =
                officerRepository.findByTeamIdOrderByDisplayOrderAsc(teamId);

        // リクエストが全役員IDを網羅しているか検証
        Set<Long> existingIds = officers.stream()
                .map(o -> o.getId())
                .collect(Collectors.toSet());
        Set<Long> requestIds = req.getOrders().stream()
                .map(TeamReorderRequest.OrderItem::getId)
                .collect(Collectors.toSet());

        if (!existingIds.equals(requestIds)) {
            throw new BusinessException(TeamErrorCode.TEAM_042);
        }

        // 並び替え適用
        officers.forEach(officer -> {
            req.getOrders().stream()
                    .filter(item -> item.getId().equals(officer.getId()))
                    .findFirst()
                    .ifPresent(item -> officer.updateDisplayOrder(item.getDisplayOrder()));
        });
        officerRepository.saveAll(officers);

        log.info("チーム役員並び替え完了: teamId={}", teamId);
    }

    // ========================================
    // カスタムフィールド
    // ========================================

    /**
     * カスタムフィールド一覧を取得する（可視性ルールを適用）。
     * GET /teams/{id}/custom-fields
     *
     * @param userId            リクエストユーザーID
     * @param teamId            チームID
     * @param visibilityPreview true の場合は ADMIN/DEPUTY_ADMIN のみ全件返却
     * @return カスタムフィールド一覧
     */
    public ApiResponse<List<TeamCustomFieldResponse>> getCustomFields(
            Long userId, Long teamId, boolean visibilityPreview) {

        TeamEntity team = findTeamOrThrow(teamId);
        boolean isMember = accessControlService.isMember(userId, teamId, "TEAM");
        boolean isAdminOrAbove = accessControlService.isAdminOrAbove(userId, teamId, "TEAM");

        // PRIVATE チームかつ非メンバー → 403
        if (team.getVisibility() == TeamEntity.Visibility.PRIVATE && !isMember) {
            throw new BusinessException(TeamErrorCode.TEAM_048);
        }

        // profile_visibility.custom_fields == false かつ非メンバー → 空リスト
        ProfileVisibility visibility = team.getProfileVisibility();
        if (visibility != null && !visibility.isCustomFieldsVisible() && !isMember) {
            return ApiResponse.of(List.of());
        }

        List<TeamCustomFieldEntity> fields =
                customFieldRepository.findByTeamIdOrderByDisplayOrderAsc(teamId);

        // visibilityPreview=true の場合は ADMIN/DEPUTY_ADMIN チェック → 全件 + フラグ付き
        if (visibilityPreview) {
            if (!isAdminOrAbove) {
                throw new BusinessException(TeamErrorCode.TEAM_048);
            }
            List<TeamCustomFieldResponse> responses = fields.stream()
                    .map(f -> toCustomFieldResponse(f, true))
                    .toList();
            return ApiResponse.of(responses);
        }

        // 通常取得: is_visible == false のエントリは非メンバーにはフィルタリング
        List<TeamCustomFieldResponse> responses = fields.stream()
                .filter(f -> isMember || Boolean.TRUE.equals(f.getIsVisible()))
                .map(f -> toCustomFieldResponse(f, false))
                .toList();

        return ApiResponse.of(responses);
    }

    /**
     * カスタムフィールドを追加する（最大20件）。
     * POST /teams/{id}/custom-fields
     *
     * @param userId リクエストユーザーID
     * @param teamId チームID
     * @param req    作成内容
     * @return 作成したカスタムフィールド
     */
    @Transactional
    public ApiResponse<TeamCustomFieldResponse> createCustomField(
            Long userId, Long teamId, CreateTeamCustomFieldRequest req) {

        if (!accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            throw new BusinessException(TeamErrorCode.TEAM_048);
        }

        findTeamOrThrow(teamId);

        // 上限チェック
        if (customFieldRepository.countByTeamId(teamId) >= MAX_CUSTOM_FIELDS) {
            throw new BusinessException(TeamErrorCode.TEAM_043);
        }

        // 文字数バリデーション
        validateTextLength(req.getLabel(), MAX_LABEL_LENGTH, TeamErrorCode.TEAM_046);
        validateTextLength(req.getValue(), MAX_VALUE_LENGTH, TeamErrorCode.TEAM_046);

        // 既存最大 displayOrder を取得して +1
        List<TeamCustomFieldEntity> existing =
                customFieldRepository.findByTeamIdOrderByDisplayOrderAsc(teamId);
        int nextOrder = existing.isEmpty()
                ? 1
                : existing.get(existing.size() - 1).getDisplayOrder() + 1;

        TeamCustomFieldEntity field = TeamCustomFieldEntity.builder()
                .teamId(teamId)
                .label(req.getLabel().trim())
                .value(req.getValue().trim())
                .displayOrder(nextOrder)
                .isVisible(req.getIsVisible() != null ? req.getIsVisible() : true)
                .build();
        customFieldRepository.save(field);

        auditLogService.record(
                "TEAM_CUSTOM_FIELD_CREATE",
                userId, null, teamId, null, null, null, null,
                "{\"fieldId\":" + field.getId() + "}");

        log.info("チームカスタムフィールド追加完了: teamId={}, fieldId={}", teamId, field.getId());
        return ApiResponse.of(toCustomFieldResponse(field, false));
    }

    /**
     * カスタムフィールドを更新する。
     * PATCH /teams/{id}/custom-fields/{fieldId}
     *
     * @param userId  リクエストユーザーID
     * @param teamId  チームID
     * @param fieldId カスタムフィールドID
     * @param req     更新内容
     * @return 更新後のカスタムフィールド
     */
    @Transactional
    public ApiResponse<TeamCustomFieldResponse> updateCustomField(
            Long userId, Long teamId, Long fieldId, UpdateTeamCustomFieldRequest req) {

        if (!accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            throw new BusinessException(TeamErrorCode.TEAM_048);
        }

        findTeamOrThrow(teamId);
        TeamCustomFieldEntity field = findCustomFieldOrThrow(fieldId, teamId);

        // 文字数バリデーション（指定された場合のみ）
        if (req.getLabel() != null) validateTextLength(req.getLabel(), MAX_LABEL_LENGTH, TeamErrorCode.TEAM_046);
        if (req.getValue() != null) validateTextLength(req.getValue(), MAX_VALUE_LENGTH, TeamErrorCode.TEAM_046);

        String newLabel = req.getLabel() != null ? req.getLabel().trim() : field.getLabel();
        String newValue = req.getValue() != null ? req.getValue().trim() : field.getValue();
        boolean newVisible = req.getIsVisible() != null ? req.getIsVisible() : field.getIsVisible();

        field.update(newLabel, newValue, newVisible);
        customFieldRepository.save(field);

        auditLogService.record(
                "TEAM_CUSTOM_FIELD_UPDATE",
                userId, null, teamId, null, null, null, null,
                "{\"fieldId\":" + fieldId + "}");

        log.info("チームカスタムフィールド更新完了: teamId={}, fieldId={}", teamId, fieldId);
        return ApiResponse.of(toCustomFieldResponse(field, false));
    }

    /**
     * カスタムフィールドを削除する（物理削除）。
     * DELETE /teams/{id}/custom-fields/{fieldId}
     *
     * @param userId  リクエストユーザーID
     * @param teamId  チームID
     * @param fieldId カスタムフィールドID
     */
    @Transactional
    public void deleteCustomField(Long userId, Long teamId, Long fieldId) {

        if (!accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            throw new BusinessException(TeamErrorCode.TEAM_048);
        }

        findTeamOrThrow(teamId);
        TeamCustomFieldEntity field = findCustomFieldOrThrow(fieldId, teamId);
        customFieldRepository.delete(field);

        auditLogService.record(
                "TEAM_CUSTOM_FIELD_DELETE",
                userId, null, teamId, null, null, null, null,
                "{\"fieldId\":" + fieldId + "}");

        log.info("チームカスタムフィールド削除完了: teamId={}, fieldId={}", teamId, fieldId);
    }

    /**
     * カスタムフィールドの表示順を並び替える。
     * PUT /teams/{id}/custom-fields/reorder
     *
     * @param userId リクエストユーザーID
     * @param teamId チームID
     * @param req    並び替えリクエスト
     */
    @Transactional
    public void reorderCustomFields(Long userId, Long teamId, TeamReorderRequest req) {

        if (!accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            throw new BusinessException(TeamErrorCode.TEAM_048);
        }

        findTeamOrThrow(teamId);

        List<TeamCustomFieldEntity> fields =
                customFieldRepository.findByTeamIdOrderByDisplayOrderAsc(teamId);

        // リクエストが全フィールド ID を網羅しているか検証
        Set<Long> existingIds = fields.stream()
                .map(f -> f.getId())
                .collect(Collectors.toSet());
        Set<Long> requestIds = req.getOrders().stream()
                .map(TeamReorderRequest.OrderItem::getId)
                .collect(Collectors.toSet());

        if (!existingIds.equals(requestIds)) {
            throw new BusinessException(TeamErrorCode.TEAM_044);
        }

        // 並び替え適用
        fields.forEach(field -> {
            req.getOrders().stream()
                    .filter(item -> item.getId().equals(field.getId()))
                    .findFirst()
                    .ifPresent(item -> field.updateDisplayOrder(item.getDisplayOrder()));
        });
        customFieldRepository.saveAll(fields);

        log.info("チームカスタムフィールド並び替え完了: teamId={}", teamId);
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    private TeamEntity findTeamOrThrow(Long teamId) {
        return teamRepository.findById(teamId)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_001));
    }

    private TeamOfficerEntity findOfficerOrThrow(Long officerId, Long teamId) {
        return officerRepository.findById(officerId)
                .filter(o -> teamId.equals(o.getTeamId()))
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_050));
    }

    private TeamCustomFieldEntity findCustomFieldOrThrow(Long fieldId, Long teamId) {
        return customFieldRepository.findById(fieldId)
                .filter(f -> teamId.equals(f.getTeamId()))
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_051));
    }

    private void validateTextLength(String text, int maxLength, TeamErrorCode errorCode) {
        if (text == null) return;
        String trimmed = text.trim();
        if (PlainTextValidator.containsHtml(trimmed)) {
            throw new BusinessException(errorCode);
        }
        if (codePointLength(trimmed) > maxLength) {
            throw new BusinessException(errorCode);
        }
    }

    private int codePointLength(String text) {
        return Character.codePointCount(text, 0, text.length());
    }

    private TeamProfileResponse toProfileResponse(TeamEntity team) {
        return TeamProfileResponse.builder()
                .id(team.getId())
                .homepageUrl(team.getHomepageUrl())
                .establishedDate(team.getEstablishedDate())
                .establishedDatePrecision(team.getEstablishedDatePrecision())
                .philosophy(team.getPhilosophy())
                .profileVisibility(team.getProfileVisibility())
                .build();
    }

    private TeamOfficerResponse toOfficerResponse(TeamOfficerEntity officer, boolean includePublicFlag) {
        return TeamOfficerResponse.builder()
                .id(officer.getId())
                .teamId(officer.getTeamId())
                .name(officer.getName())
                .title(officer.getTitle())
                .displayOrder(officer.getDisplayOrder())
                .isVisible(officer.getIsVisible())
                .isPubliclyVisible(includePublicFlag ? officer.getIsVisible() : null)
                .build();
    }

    private TeamCustomFieldResponse toCustomFieldResponse(TeamCustomFieldEntity field, boolean includePublicFlag) {
        return TeamCustomFieldResponse.builder()
                .id(field.getId())
                .teamId(field.getTeamId())
                .label(field.getLabel())
                .value(field.getValue())
                .displayOrder(field.getDisplayOrder())
                .isVisible(field.getIsVisible())
                .isPubliclyVisible(includePublicFlag ? field.getIsVisible() : null)
                .build();
    }
}
