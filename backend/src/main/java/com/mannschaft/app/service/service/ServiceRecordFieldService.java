package com.mannschaft.app.service.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.service.FieldType;
import com.mannschaft.app.service.ServiceRecordErrorCode;
import com.mannschaft.app.service.ServiceRecordMapper;
import com.mannschaft.app.service.dto.CreateFieldRequest;
import com.mannschaft.app.service.dto.FieldResponse;
import com.mannschaft.app.service.dto.FieldSortOrderRequest;
import com.mannschaft.app.service.dto.SettingsResponse;
import com.mannschaft.app.service.dto.SortOrderResponse;
import com.mannschaft.app.service.dto.UpdateFieldRequest;
import com.mannschaft.app.service.dto.UpdateSettingsRequest;
import com.mannschaft.app.service.entity.ServiceRecordFieldEntity;
import com.mannschaft.app.service.entity.ServiceRecordSettingsEntity;
import com.mannschaft.app.service.repository.ServiceRecordFieldRepository;
import com.mannschaft.app.service.repository.ServiceRecordSettingsRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * カスタムフィールド定義・設定サービス。
 *
 * <p><b>認可方針（認可根治戦役 Wave7）:</b> 兄弟である {@link ServiceRecordService} /
 * {@code ServiceRecordExportService} と同じく {@link AccessControlService} でスコープ認可する。
 * 参照系（一覧・設定取得）は {@code checkMembership}、フィールド定義や機能設定を書き換える
 * 変更系は {@code checkAdminOrAbove} を敷く（{@code ServiceRecordService} の
 * 参照=membership／変更=adminOrAbove の粒度に揃えた）。</p>
 *
 * <p><b>BOLA 厳禁:</b> 単一フィールドを対象とする操作は {@code findByIdAndTeamId} で
 * path の teamId に束縛して fetch し、認可は fetch 済み entity 由来の teamId で行う。
 * path の teamId を鵜呑みにしない。束縛に失敗した場合（他チームのフィールドIDを
 * 自チームの teamId で叩いた場合）は {@code SERVICE_RECORD_002} で存在秘匿する
 * （{@code GlobalExceptionHandler} で 404 にマップ済み）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ServiceRecordFieldService {

    private final ServiceRecordFieldRepository fieldRepository;
    private final ServiceRecordSettingsRepository settingsRepository;
    private final ServiceRecordMapper mapper;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;

    /** F00.5 メンバーシップ・ロール判定のスコープ種別（チーム）。 */
    private static final String SCOPE_TEAM = "TEAM";

    private static final int MAX_ACTIVE_FIELDS = 20;

    // ==================== カスタムフィールド CRUD ====================

    /**
     * カスタムフィールド定義一覧を取得する。
     */
    public List<FieldResponse> listFields(Long teamId, Long actorUserId) {
        // 認可: チーム会員のみフィールド定義を参照可（越境の定義漏洩を防止）。
        accessControlService.checkMembership(actorUserId, teamId, SCOPE_TEAM);
        return fieldRepository.findByTeamIdOrderBySortOrder(teamId)
                .stream()
                .map(mapper::toFieldResponse)
                .collect(Collectors.toList());
    }

    /**
     * カスタムフィールドを作成する。
     */
    @Transactional
    public FieldResponse createField(Long teamId, Long actorUserId, CreateFieldRequest request) {
        // 認可: 作成先スコープ（path の teamId）の管理者のみフィールドを定義可。
        accessControlService.checkAdminOrAbove(actorUserId, teamId, SCOPE_TEAM);

        long activeCount = fieldRepository.countByTeamIdAndIsActiveTrue(teamId);
        if (activeCount >= MAX_ACTIVE_FIELDS) {
            throw new BusinessException(ServiceRecordErrorCode.FIELD_LIMIT_EXCEEDED);
        }

        FieldType fieldType = FieldType.valueOf(request.getFieldType());
        String options = convertOptionsToJson(request.getOptions());

        ServiceRecordFieldEntity entity = ServiceRecordFieldEntity.builder()
                .teamId(teamId)
                .fieldName(request.getFieldName())
                .fieldType(fieldType)
                .description(request.getDescription())
                .options(options)
                .isRequired(request.getIsRequired() != null ? request.getIsRequired() : false)
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .build();

        ServiceRecordFieldEntity saved = fieldRepository.save(entity);
        log.info("カスタムフィールド作成: teamId={}, fieldId={}", teamId, saved.getId());
        return mapper.toFieldResponse(saved);
    }

    /**
     * カスタムフィールドを更新する（再有効化含む）。
     */
    @Transactional
    public FieldResponse updateField(Long teamId, Long id, Long actorUserId, UpdateFieldRequest request) {
        ServiceRecordFieldEntity entity = fieldRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ServiceRecordErrorCode.FIELD_NOT_FOUND));
        // BOLA厳禁: entity 由来 teamId で認可する（path teamId 鵜呑み禁止）。
        accessControlService.checkAdminOrAbove(actorUserId, entity.getTeamId(), SCOPE_TEAM);

        // 再有効化時の上限チェック
        if (Boolean.TRUE.equals(request.getIsActive()) && !Boolean.TRUE.equals(entity.getIsActive())) {
            long activeCount = fieldRepository.countByTeamIdAndIsActiveTrue(teamId);
            if (activeCount >= MAX_ACTIVE_FIELDS) {
                throw new BusinessException(ServiceRecordErrorCode.FIELD_LIMIT_EXCEEDED);
            }
        }

        FieldType fieldType = FieldType.valueOf(request.getFieldType());
        String options = convertOptionsToJson(request.getOptions());

        entity.update(
                request.getFieldName(),
                fieldType,
                request.getDescription(),
                options,
                request.getIsRequired() != null ? request.getIsRequired() : entity.getIsRequired(),
                request.getSortOrder() != null ? request.getSortOrder() : entity.getSortOrder(),
                request.getIsActive() != null ? request.getIsActive() : entity.getIsActive());

        ServiceRecordFieldEntity saved = fieldRepository.save(entity);
        log.info("カスタムフィールド更新: fieldId={}", id);
        return mapper.toFieldResponse(saved);
    }

    /**
     * カスタムフィールドを無効化する。
     */
    @Transactional
    public void deactivateField(Long teamId, Long id, Long actorUserId) {
        ServiceRecordFieldEntity entity = fieldRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ServiceRecordErrorCode.FIELD_NOT_FOUND));
        // BOLA厳禁: entity 由来 teamId で認可する（path teamId 鵜呑み禁止）。
        accessControlService.checkAdminOrAbove(actorUserId, entity.getTeamId(), SCOPE_TEAM);
        entity.deactivate();
        fieldRepository.save(entity);
        log.info("カスタムフィールド無効化: fieldId={}", id);
    }

    /**
     * カスタムフィールドの並び順を一括更新する。
     */
    @Transactional
    public SortOrderResponse updateSortOrder(Long teamId, Long actorUserId, FieldSortOrderRequest request) {
        // 認可: 対象スコープ（path の teamId）の管理者のみ並び替え可。
        accessControlService.checkAdminOrAbove(actorUserId, teamId, SCOPE_TEAM);

        // BOLA厳禁: 並び替え対象は teamId 束縛で取得した集合に限定する。
        // 他チームの fieldId を混ぜても fieldMap に不在となり FIELD_NOT_FOUND（404 秘匿）になる。
        Map<Long, ServiceRecordFieldEntity> fieldMap = fieldRepository.findByTeamIdOrderBySortOrder(teamId)
                .stream()
                .collect(Collectors.toMap(ServiceRecordFieldEntity::getId, f -> f));

        int updated = 0;
        for (FieldSortOrderRequest.FieldOrderEntry entry : request.getFieldOrders()) {
            ServiceRecordFieldEntity field = fieldMap.get(entry.getFieldId());
            if (field == null) {
                throw new BusinessException(ServiceRecordErrorCode.FIELD_NOT_FOUND);
            }
            field.updateSortOrder(entry.getSortOrder());
            fieldRepository.save(field);
            updated++;
        }

        log.info("カスタムフィールド並び替え: teamId={}, count={}", teamId, updated);
        return SortOrderResponse.builder().updatedCount(updated).build();
    }

    // ==================== 設定 ====================

    /**
     * 機能設定を取得する。
     */
    public SettingsResponse getSettings(Long teamId, Long actorUserId) {
        // 認可: チーム会員のみ機能設定を参照可。
        accessControlService.checkMembership(actorUserId, teamId, SCOPE_TEAM);
        ServiceRecordSettingsEntity settings = settingsRepository.findByTeamId(teamId)
                .orElseGet(() -> createDefaultSettings(teamId));
        return mapper.toSettingsResponse(settings);
    }

    /**
     * 機能設定を更新する。
     */
    @Transactional
    public SettingsResponse updateSettings(Long teamId, Long actorUserId, UpdateSettingsRequest request) {
        // 認可: 対象スコープ（path の teamId）の管理者のみ機能設定を変更可。
        accessControlService.checkAdminOrAbove(actorUserId, teamId, SCOPE_TEAM);

        ServiceRecordSettingsEntity settings = settingsRepository.findByTeamId(teamId)
                .orElseGet(() -> createDefaultSettings(teamId));

        settings.update(
                request.getIsDashboardEnabled() != null ? request.getIsDashboardEnabled() : settings.getIsDashboardEnabled(),
                request.getIsReactionEnabled() != null ? request.getIsReactionEnabled() : settings.getIsReactionEnabled());

        ServiceRecordSettingsEntity saved = settingsRepository.save(settings);
        log.info("機能設定更新: teamId={}", teamId);
        return mapper.toSettingsResponse(saved);
    }

    // ==================== プライベートメソッド ====================

    private ServiceRecordSettingsEntity createDefaultSettings(Long teamId) {
        ServiceRecordSettingsEntity settings = ServiceRecordSettingsEntity.builder()
                .teamId(teamId)
                .build();
        return settingsRepository.save(settings);
    }

    private String convertOptionsToJson(List<String> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(options);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ServiceRecordErrorCode.VALIDATION_ERROR);
        }
    }
}
