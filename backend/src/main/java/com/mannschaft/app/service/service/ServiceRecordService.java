package com.mannschaft.app.service.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.service.BulkCreateMode;
import com.mannschaft.app.service.FieldType;
import com.mannschaft.app.service.ReactionType;
import com.mannschaft.app.service.ServiceRecordErrorCode;
import com.mannschaft.app.service.ServiceRecordMapper;
import com.mannschaft.app.service.ServiceRecordStatus;
import com.mannschaft.app.service.dto.AttachmentResponse;
import com.mannschaft.app.service.dto.BulkCreateResponse;
import com.mannschaft.app.service.dto.BulkCreateServiceRecordRequest;
import com.mannschaft.app.service.dto.ConfirmResponse;
import com.mannschaft.app.service.dto.CreateServiceRecordRequest;
import com.mannschaft.app.service.dto.CustomFieldValueRequest;
import com.mannschaft.app.service.dto.CustomFieldValueResponse;
import com.mannschaft.app.service.dto.DuplicateServiceRecordRequest;
import com.mannschaft.app.service.dto.ReactionRequest;
import com.mannschaft.app.service.dto.ReactionResponse;
import com.mannschaft.app.service.dto.RegisterAttachmentRequest;
import com.mannschaft.app.service.dto.ServiceHistorySummaryResponse;
import com.mannschaft.app.service.dto.ServiceRecordResponse;
import com.mannschaft.app.service.dto.UpdateServiceRecordRequest;
import com.mannschaft.app.service.dto.UploadUrlRequest;
import com.mannschaft.app.service.dto.UploadUrlResponse;
import com.mannschaft.app.service.entity.ServiceRecordAttachmentEntity;
import com.mannschaft.app.service.entity.ServiceRecordEntity;
import com.mannschaft.app.service.entity.ServiceRecordFieldEntity;
import com.mannschaft.app.service.entity.ServiceRecordReactionEntity;
import com.mannschaft.app.service.entity.ServiceRecordSettingsEntity;
import com.mannschaft.app.service.entity.ServiceRecordValueEntity;
import com.mannschaft.app.service.repository.ServiceRecordAttachmentRepository;
import com.mannschaft.app.service.repository.ServiceRecordFieldRepository;
import com.mannschaft.app.service.repository.ServiceRecordReactionRepository;
import com.mannschaft.app.service.repository.ServiceRecordRepository;
import com.mannschaft.app.service.repository.ServiceRecordSettingsRepository;
import com.mannschaft.app.service.repository.ServiceRecordValueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * サービス記録サービス。記録のCRUD・一括作成・複製・確定・検索を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ServiceRecordService {

    private final ServiceRecordRepository recordRepository;
    private final ServiceRecordFieldRepository fieldRepository;
    private final ServiceRecordValueRepository valueRepository;
    private final ServiceRecordAttachmentRepository attachmentRepository;
    private final ServiceRecordSettingsRepository settingsRepository;
    private final ServiceRecordReactionRepository reactionRepository;
    private final ServiceRecordMapper mapper;
    private final ObjectMapper objectMapper;

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final List<String> ALLOWED_CONTENT_TYPES = List.of(
            "image/jpeg", "image/png", "image/webp", "application/pdf");
    private static final int MAX_ATTACHMENTS = 5;
    private static final int MAX_BULK_RECORDS = 20;

    // ==================== サービス記録 CRUD ====================

    /**
     * チーム内のサービス履歴一覧を取得する。
     */
    public Page<ServiceRecordResponse> listRecords(Long teamId, Long memberUserId, Long staffUserId,
                                                    LocalDate serviceDateFrom, LocalDate serviceDateTo,
                                                    String titleLike, String status,
                                                    Map<Long, String> customFieldFilters,
                                                    Pageable pageable) {
        Specification<ServiceRecordEntity> spec = Specification.where(
                (root, query, cb) -> cb.equal(root.get("teamId"), teamId));

        if (memberUserId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("memberUserId"), memberUserId));
        }
        if (staffUserId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("staffUserId"), staffUserId));
        }
        if (serviceDateFrom != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("serviceDate"), serviceDateFrom));
        }
        if (serviceDateTo != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("serviceDate"), serviceDateTo));
        }
        if (titleLike != null && !titleLike.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.like(root.get("title"), "%" + titleLike + "%"));
        }
        if (status != null && !status.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), ServiceRecordStatus.valueOf(status)));
        }

        Page<ServiceRecordEntity> page = recordRepository.findAll(spec, pageable);
        return page.map(entity -> toRecordResponse(entity, null));
    }

    /**
     * サービス履歴詳細を取得する。
     */
    public ServiceRecordResponse getRecord(Long teamId, Long id) {
        ServiceRecordEntity entity = findRecordOrThrow(teamId, id);
        return toRecordResponse(entity, null);
    }

    /**
     * サービス記録を作成する。
     */
    @Transactional
    public ServiceRecordResponse createRecord(Long teamId, Long currentUserId,
                                               CreateServiceRecordRequest request) {
        ServiceRecordStatus recordStatus = request.getStatus() != null
                ? ServiceRecordStatus.valueOf(request.getStatus())
                : ServiceRecordStatus.DRAFT;

        ServiceRecordEntity entity = ServiceRecordEntity.builder()
                .teamId(teamId)
                .memberUserId(request.getMemberUserId())
                .staffUserId(request.getStaffUserId())
                .serviceDate(request.getServiceDate())
                .title(request.getTitle())
                .note(request.getNote())
                .durationMinutes(request.getDurationMinutes())
                .status(recordStatus)
                .build();

        ServiceRecordEntity saved = recordRepository.save(entity);

        if (request.getCustomFields() != null) {
            saveCustomFieldValues(saved.getId(), teamId, request.getCustomFields(), recordStatus);
        }

        log.info("サービス記録作成: teamId={}, recordId={}, status={}", teamId, saved.getId(), recordStatus);
        return toRecordResponse(saved, null);
    }

    /**
     * サービス記録を更新する。
     */
    @Transactional
    public ServiceRecordResponse updateRecord(Long teamId, Long id, UpdateServiceRecordRequest request) {
        ServiceRecordEntity entity = findRecordOrThrow(teamId, id);

        entity.update(
                request.getMemberUserId(),
                request.getStaffUserId(),
                request.getServiceDate(),
                request.getTitle(),
                request.getNote(),
                request.getDurationMinutes());

        ServiceRecordEntity saved = recordRepository.save(entity);

        if (request.getCustomFields() != null) {
            valueRepository.deleteByServiceRecordId(id);
            saveCustomFieldValues(id, teamId, request.getCustomFields(), entity.getStatus());
        }

        log.info("サービス記録更新: recordId={}", id);
        return toRecordResponse(saved, null);
    }

    /**
     * 下書き記録を確定する。
     */
    @Transactional
    public ConfirmResponse confirmRecord(Long teamId, Long id) {
        ServiceRecordEntity entity = findRecordOrThrow(teamId, id);

        if (entity.getStatus() == ServiceRecordStatus.CONFIRMED) {
            throw new BusinessException(ServiceRecordErrorCode.ALREADY_CONFIRMED);
        }

        // 必須フィールドチェック
        validateRequiredFields(id, teamId);

        entity.confirm();
        recordRepository.save(entity);

        log.info("サービス記録確定: recordId={}", id);
        return ConfirmResponse.builder()
                .id(id)
                .status("CONFIRMED")
                .confirmedAt(LocalDateTime.now())
                .build();
    }

    /**
     * サービス記録を論理削除する。
     */
    @Transactional
    public void deleteRecord(Long teamId, Long id) {
        ServiceRecordEntity entity = findRecordOrThrow(teamId, id);
        entity.softDelete();
        recordRepository.save(entity);
        log.info("サービス記録削除: recordId={}", id);
    }

    /**
     * 既存記録を複製する。
     */
    @Transactional
    public ServiceRecordResponse duplicateRecord(Long teamId, Long id, Long currentUserId,
                                                  DuplicateServiceRecordRequest request) {
        ServiceRecordEntity original = findRecordOrThrow(teamId, id);

        LocalDate serviceDate = request != null && request.getServiceDate() != null
                ? request.getServiceDate() : LocalDate.now();
        Long staffUserId = request != null && request.getStaffUserId() != null
                ? request.getStaffUserId() : currentUserId;

        ServiceRecordEntity duplicate = ServiceRecordEntity.builder()
                .teamId(teamId)
                .memberUserId(original.getMemberUserId())
                .staffUserId(staffUserId)
                .serviceDate(serviceDate)
                .title(original.getTitle())
                .note(original.getNote())
                .durationMinutes(original.getDurationMinutes())
                .status(ServiceRecordStatus.DRAFT)
                .build();

        ServiceRecordEntity saved = recordRepository.save(duplicate);

        // カスタムフィールド値をコピー
        List<ServiceRecordValueEntity> originalValues = valueRepository.findByServiceRecordId(id);
        for (ServiceRecordValueEntity ov : originalValues) {
            ServiceRecordValueEntity copy = ServiceRecordValueEntity.builder()
                    .serviceRecordId(saved.getId())
                    .fieldId(ov.getFieldId())
                    .value(ov.getValue())
                    .build();
            valueRepository.save(copy);
        }

        log.info("サービス記録複製: originalId={}, newId={}", id, saved.getId());
        return toRecordResponse(saved, id);
    }

    /**
     * 一括作成する。
     */
    @Transactional
    public BulkCreateResponse bulkCreate(Long teamId, Long currentUserId,
                                          BulkCreateServiceRecordRequest request) {
        if (request.getRecords().size() > MAX_BULK_RECORDS) {
            throw new BusinessException(ServiceRecordErrorCode.BULK_LIMIT_EXCEEDED);
        }

        BulkCreateMode mode = BulkCreateMode.valueOf(request.getMode());

        if (mode == BulkCreateMode.ALL_OR_NOTHING) {
            return bulkCreateAllOrNothing(teamId, currentUserId, request.getRecords());
        } else {
            return bulkCreateBestEffort(teamId, currentUserId, request.getRecords());
        }
    }

    /**
     * 特定メンバーの履歴一覧を取得する。
     */
    public Page<ServiceRecordResponse> getMemberHistory(Long teamId, Long userId, Pageable pageable) {
        Page<ServiceRecordEntity> page = recordRepository.findByTeamIdAndMemberUserId(teamId, userId, pageable);
        return page.map(entity -> toRecordResponse(entity, null));
    }

    /**
     * 特定メンバーの履歴サマリーを取得する。
     */
    public ServiceHistorySummaryResponse getMemberSummary(Long teamId, Long userId, int months) {
        LocalDate fromDate = LocalDate.now().minusMonths(months);
        List<ServiceRecordEntity> records = recordRepository.findForSummary(teamId, userId, fromDate);

        long totalCount = records.size();
        long totalDuration = records.stream()
                .filter(r -> r.getDurationMinutes() != null)
                .mapToLong(ServiceRecordEntity::getDurationMinutes)
                .sum();
        long avgDuration = totalCount > 0 ? totalDuration / totalCount : 0;

        LocalDate lastServiceDate = records.stream()
                .map(ServiceRecordEntity::getServiceDate)
                .max(LocalDate::compareTo)
                .orElse(null);

        long daysSince = lastServiceDate != null
                ? ChronoUnit.DAYS.between(lastServiceDate, LocalDate.now()) : 0;

        // 月別集計
        Map<YearMonth, long[]> monthlyMap = new LinkedHashMap<>();
        for (int i = 0; i < months; i++) {
            YearMonth ym = YearMonth.now().minusMonths(i);
            monthlyMap.put(ym, new long[]{0, 0});
        }
        for (ServiceRecordEntity r : records) {
            YearMonth ym = YearMonth.from(r.getServiceDate());
            long[] counts = monthlyMap.get(ym);
            if (counts != null) {
                counts[0]++;
                counts[1] += r.getDurationMinutes() != null ? r.getDurationMinutes() : 0;
            }
        }
        List<ServiceHistorySummaryResponse.MonthlyCount> monthlyCounts = monthlyMap.entrySet().stream()
                .map(e -> ServiceHistorySummaryResponse.MonthlyCount.builder()
                        .month(e.getKey().format(DateTimeFormatter.ofPattern("yyyy-MM")))
                        .count(e.getValue()[0])
                        .durationMinutes(e.getValue()[1])
                        .build())
                .collect(Collectors.toList());

        // 担当スタッフ Top
        Map<Long, Long> staffCounts = records.stream()
                .filter(r -> r.getStaffUserId() != null)
                .collect(Collectors.groupingBy(ServiceRecordEntity::getStaffUserId, Collectors.counting()));
        List<ServiceHistorySummaryResponse.TopStaff> topStaff = staffCounts.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(5)
                .map(e -> ServiceHistorySummaryResponse.TopStaff.builder()
                        .staffUserId(e.getKey())
                        .staffName("") // TODO: UserService から取得
                        .count(e.getValue())
                        .build())
                .collect(Collectors.toList());

        return ServiceHistorySummaryResponse.builder()
                .memberUserId(userId)
                .totalCount(totalCount)
                .totalDurationMinutes(totalDuration)
                .averageDurationMinutes(avgDuration)
                .lastServiceDate(lastServiceDate)
                .daysSinceLastService(daysSince)
                .monthlyCounts(monthlyCounts)
                .topStaff(topStaff)
                .build();
    }

    /**
     * 自分のサービス履歴を全チーム横断で取得する。
     */
    public Page<ServiceRecordResponse> getMyRecords(Long userId, Long teamIdFilter, Pageable pageable) {
        // ダッシュボード表示が許可されたチーム一覧を取得
        List<ServiceRecordSettingsEntity> enabledSettings;
        if (teamIdFilter != null) {
            enabledSettings = settingsRepository.findByTeamIdInAndIsDashboardEnabledTrue(List.of(teamIdFilter));
        } else {
            // TODO: ユーザーが所属する全チームIDを取得して渡す
            enabledSettings = Collections.emptyList();
        }

        if (enabledSettings.isEmpty()) {
            return Page.empty(pageable);
        }

        List<Long> enabledTeamIds = enabledSettings.stream()
                .map(ServiceRecordSettingsEntity::getTeamId)
                .collect(Collectors.toList());

        Page<ServiceRecordEntity> page = recordRepository.findMyRecords(userId, enabledTeamIds, pageable);

        // リアクション情報を取得
        List<Long> recordIds = page.getContent().stream()
                .map(ServiceRecordEntity::getId)
                .collect(Collectors.toList());
        List<ServiceRecordReactionEntity> reactions = recordIds.isEmpty()
                ? Collections.emptyList()
                : reactionRepository.findByServiceRecordIdIn(recordIds);

        Map<Long, ServiceRecordReactionEntity> myReactions = reactions.stream()
                .filter(r -> r.getUserId().equals(userId))
                .collect(Collectors.toMap(ServiceRecordReactionEntity::getServiceRecordId, r -> r));

        Map<Long, Map<String, Integer>> reactionSummaries = new HashMap<>();
        for (ServiceRecordReactionEntity r : reactions) {
            reactionSummaries.computeIfAbsent(r.getServiceRecordId(), k -> {
                Map<String, Integer> summary = new HashMap<>();
                summary.put("LIKE", 0);
                summary.put("THANKS", 0);
                summary.put("GREAT", 0);
                return summary;
            }).merge(r.getReactionType().name(), 1, Integer::sum);
        }

        // settings をチームIDでマップ化
        Map<Long, ServiceRecordSettingsEntity> settingsMap = enabledSettings.stream()
                .collect(Collectors.toMap(ServiceRecordSettingsEntity::getTeamId, s -> s));

        return page.map(entity -> {
            ServiceRecordReactionEntity myReaction = myReactions.get(entity.getId());
            Map<String, Integer> summary = reactionSummaries.getOrDefault(entity.getId(),
                    Map.of("LIKE", 0, "THANKS", 0, "GREAT", 0));
            ServiceRecordSettingsEntity settings = settingsMap.get(entity.getTeamId());
            boolean reactionEnabled = settings != null && Boolean.TRUE.equals(settings.getIsReactionEnabled());

            List<ServiceRecordValueEntity> values = valueRepository.findByServiceRecordId(entity.getId());
            List<ServiceRecordFieldEntity> fields = fieldRepository.findByTeamIdOrderBySortOrder(entity.getTeamId());
            Map<Long, ServiceRecordFieldEntity> fieldMap = fields.stream()
                    .collect(Collectors.toMap(ServiceRecordFieldEntity::getId, f -> f));

            List<CustomFieldValueResponse> customFieldResponses = values.stream()
                    .filter(v -> fieldMap.containsKey(v.getFieldId()))
                    .map(v -> mapper.toCustomFieldValueResponse(v, fieldMap.get(v.getFieldId())))
                    .collect(Collectors.toList());

            List<ServiceRecordAttachmentEntity> attachments =
                    attachmentRepository.findByServiceRecordIdOrderBySortOrder(entity.getId());
            List<AttachmentResponse> attachmentResponses = attachments.stream()
                    .map(mapper::toAttachmentResponse)
                    .collect(Collectors.toList());

            return ServiceRecordResponse.builder()
                    .id(entity.getId())
                    .teamId(entity.getTeamId())
                    .memberUserId(entity.getMemberUserId())
                    .staffUserId(entity.getStaffUserId())
                    .serviceDate(entity.getServiceDate())
                    .title(entity.getTitle())
                    .note(entity.getNote())
                    .durationMinutes(entity.getDurationMinutes())
                    .status(entity.getStatus().name())
                    .customFields(customFieldResponses)
                    .attachments(attachmentResponses)
                    .myReaction(myReaction != null ? myReaction.getReactionType().name() : null)
                    .reactionSummary(summary)
                    .isReactionEnabled(reactionEnabled)
                    .createdAt(entity.getCreatedAt())
                    .build();
        });
    }

    // ==================== リアクション ====================

    /**
     * リアクションを追加（トグル更新）する。
     */
    @Transactional
    public ReactionResponse addReaction(Long teamId, Long recordId, Long userId, ReactionRequest request) {
        ServiceRecordEntity record = findRecordOrThrow(teamId, recordId);

        // ダッシュボード共有・リアクション有効チェック
        ServiceRecordSettingsEntity settings = settingsRepository.findByTeamId(teamId)
                .orElseThrow(() -> new BusinessException(ServiceRecordErrorCode.SETTINGS_NOT_FOUND));
        if (!Boolean.TRUE.equals(settings.getIsDashboardEnabled())) {
            throw new BusinessException(ServiceRecordErrorCode.DASHBOARD_NOT_ENABLED);
        }
        if (!Boolean.TRUE.equals(settings.getIsReactionEnabled())) {
            throw new BusinessException(ServiceRecordErrorCode.REACTION_NOT_ENABLED);
        }

        // 自分の記録のみ
        if (!record.getMemberUserId().equals(userId)) {
            throw new BusinessException(ServiceRecordErrorCode.NOT_OWN_RECORD);
        }

        ReactionType reactionType = ReactionType.valueOf(request.getReactionType());

        Optional<ServiceRecordReactionEntity> existing =
                reactionRepository.findByServiceRecordIdAndUserId(recordId, userId);

        ServiceRecordReactionEntity reaction;
        if (existing.isPresent()) {
            reaction = existing.get();
            reaction.updateReactionType(reactionType);
            reactionRepository.save(reaction);
        } else {
            reaction = ServiceRecordReactionEntity.builder()
                    .serviceRecordId(recordId)
                    .userId(userId)
                    .reactionType(reactionType)
                    .build();
            reaction = reactionRepository.save(reaction);
        }

        log.info("リアクション追加: recordId={}, userId={}, type={}", recordId, userId, reactionType);
        return ReactionResponse.builder()
                .serviceRecordId(recordId)
                .reactionType(reaction.getReactionType().name())
                .createdAt(reaction.getCreatedAt())
                .build();
    }

    /**
     * リアクションを削除する。
     */
    @Transactional
    public void deleteReaction(Long teamId, Long recordId, Long userId) {
        findRecordOrThrow(teamId, recordId);
        reactionRepository.deleteByServiceRecordIdAndUserId(recordId, userId);
        log.info("リアクション削除: recordId={}, userId={}", recordId, userId);
    }

    // ==================== 添付ファイル ====================

    /**
     * アップロード用 Pre-signed URL を発行する。
     */
    @Transactional
    public UploadUrlResponse generateUploadUrl(Long teamId, Long recordId, UploadUrlRequest request) {
        findRecordOrThrow(teamId, recordId);

        if (!ALLOWED_CONTENT_TYPES.contains(request.getContentType())) {
            throw new BusinessException(ServiceRecordErrorCode.INVALID_CONTENT_TYPE);
        }
        if (request.getFileSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ServiceRecordErrorCode.FILE_SIZE_EXCEEDED);
        }

        long currentCount = attachmentRepository.countByServiceRecordId(recordId);
        if (currentCount >= MAX_ATTACHMENTS) {
            throw new BusinessException(ServiceRecordErrorCode.ATTACHMENT_LIMIT_EXCEEDED);
        }

        String fileKey = String.format("service-records/%d/%d/%s", teamId, recordId, UUID.randomUUID());

        // TODO: S3 Pre-signed URL 生成の実装
        String uploadUrl = "https://s3.example.com/presigned/" + fileKey;

        return UploadUrlResponse.builder()
                .uploadUrl(uploadUrl)
                .fileKey(fileKey)
                .expiresIn(600)
                .build();
    }

    /**
     * 添付ファイルメタデータを登録する。
     */
    @Transactional
    public AttachmentResponse registerAttachment(Long teamId, Long recordId,
                                                  RegisterAttachmentRequest request) {
        findRecordOrThrow(teamId, recordId);

        long currentCount = attachmentRepository.countByServiceRecordId(recordId);
        if (currentCount >= MAX_ATTACHMENTS) {
            throw new BusinessException(ServiceRecordErrorCode.ATTACHMENT_LIMIT_EXCEEDED);
        }

        ServiceRecordAttachmentEntity entity = ServiceRecordAttachmentEntity.builder()
                .serviceRecordId(recordId)
                .fileKey(request.getFileKey())
                .fileName(request.getFileName())
                .contentType(request.getContentType())
                .fileSize(request.getFileSize())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .build();

        ServiceRecordAttachmentEntity saved = attachmentRepository.save(entity);
        log.info("添付ファイル登録: recordId={}, attachmentId={}", recordId, saved.getId());
        return mapper.toAttachmentResponse(saved);
    }

    /**
     * 添付ファイルを削除する。
     */
    @Transactional
    public void deleteAttachment(Long teamId, Long recordId, Long attachmentId) {
        findRecordOrThrow(teamId, recordId);
        ServiceRecordAttachmentEntity attachment = attachmentRepository
                .findByIdAndServiceRecordId(attachmentId, recordId)
                .orElseThrow(() -> new BusinessException(ServiceRecordErrorCode.ATTACHMENT_NOT_FOUND));
        attachmentRepository.delete(attachment);
        log.info("添付ファイル削除: recordId={}, attachmentId={}", recordId, attachmentId);
    }

    // ==================== プライベートメソッド ====================

    private ServiceRecordEntity findRecordOrThrow(Long teamId, Long id) {
        return recordRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ServiceRecordErrorCode.RECORD_NOT_FOUND));
    }

    private void saveCustomFieldValues(Long recordId, Long teamId,
                                        List<CustomFieldValueRequest> customFields,
                                        ServiceRecordStatus status) {
        Map<Long, ServiceRecordFieldEntity> fieldMap = fieldRepository.findByTeamIdAndIsActiveTrue(teamId)
                .stream()
                .collect(Collectors.toMap(ServiceRecordFieldEntity::getId, f -> f));

        for (CustomFieldValueRequest cf : customFields) {
            ServiceRecordFieldEntity field = fieldMap.get(cf.getFieldId());
            if (field == null) {
                throw new BusinessException(ServiceRecordErrorCode.FIELD_NOT_FOUND);
            }

            // 値が非空の場合、フィールド型に応じたバリデーションを実行
            if (cf.getValue() != null && !cf.getValue().isBlank()) {
                validateFieldValue(field, cf.getValue());
            }

            ServiceRecordValueEntity value = ServiceRecordValueEntity.builder()
                    .serviceRecordId(recordId)
                    .fieldId(cf.getFieldId())
                    .value(cf.getValue())
                    .build();
            valueRepository.save(value);
        }

        // CONFIRMED の場合のみ必須チェック
        if (status == ServiceRecordStatus.CONFIRMED) {
            validateRequiredFields(recordId, teamId);
        }
    }

    /**
     * フィールド型に応じた値バリデーションを実行する。
     */
    private void validateFieldValue(ServiceRecordFieldEntity field, String value) {
        switch (field.getFieldType()) {
            case NUMBER -> {
                try {
                    new BigDecimal(value);
                } catch (NumberFormatException e) {
                    throw new BusinessException(ServiceRecordErrorCode.FIELD_VALUE_INVALID);
                }
            }
            case DATE -> {
                try {
                    LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
                } catch (DateTimeParseException e) {
                    throw new BusinessException(ServiceRecordErrorCode.FIELD_VALUE_INVALID);
                }
            }
            case SELECT -> {
                List<String> options = parseOptions(field.getOptions());
                if (!options.contains(value)) {
                    throw new BusinessException(ServiceRecordErrorCode.FIELD_VALUE_INVALID);
                }
            }
            case MULTISELECT -> {
                List<String> options = parseOptions(field.getOptions());
                List<String> selectedValues;
                try {
                    selectedValues = objectMapper.readValue(value, new TypeReference<>() {});
                } catch (JsonProcessingException e) {
                    throw new BusinessException(ServiceRecordErrorCode.FIELD_VALUE_INVALID);
                }
                for (String sv : selectedValues) {
                    if (!options.contains(sv)) {
                        throw new BusinessException(ServiceRecordErrorCode.FIELD_VALUE_INVALID);
                    }
                }
            }
            case CHECKBOX -> {
                if (!"true".equals(value) && !"false".equals(value)) {
                    throw new BusinessException(ServiceRecordErrorCode.FIELD_VALUE_INVALID);
                }
            }
            default -> {
                // TEXT: バリデーション不要
            }
        }
    }

    /**
     * フィールドの options JSON を文字列リストにパースする。
     */
    private List<String> parseOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(optionsJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("options JSONパース失敗: {}", optionsJson, e);
            return Collections.emptyList();
        }
    }

    private void validateRequiredFields(Long recordId, Long teamId) {
        List<ServiceRecordFieldEntity> requiredFields = fieldRepository.findByTeamIdAndIsActiveTrue(teamId)
                .stream()
                .filter(f -> Boolean.TRUE.equals(f.getIsRequired()))
                .collect(Collectors.toList());

        List<ServiceRecordValueEntity> values = valueRepository.findByServiceRecordId(recordId);
        Map<Long, String> valueMap = values.stream()
                .collect(Collectors.toMap(ServiceRecordValueEntity::getFieldId, v -> v.getValue() != null ? v.getValue() : ""));

        for (ServiceRecordFieldEntity field : requiredFields) {
            String val = valueMap.get(field.getId());
            if (val == null || val.isBlank()) {
                throw new BusinessException(ServiceRecordErrorCode.REQUIRED_FIELD_MISSING);
            }
        }
    }

    private ServiceRecordResponse toRecordResponse(ServiceRecordEntity entity, Long duplicatedFrom) {
        List<ServiceRecordValueEntity> values = valueRepository.findByServiceRecordId(entity.getId());
        List<ServiceRecordFieldEntity> fields = fieldRepository.findByTeamIdOrderBySortOrder(entity.getTeamId());
        Map<Long, ServiceRecordFieldEntity> fieldMap = fields.stream()
                .collect(Collectors.toMap(ServiceRecordFieldEntity::getId, f -> f));

        List<CustomFieldValueResponse> customFieldResponses = values.stream()
                .filter(v -> fieldMap.containsKey(v.getFieldId()))
                .map(v -> mapper.toCustomFieldValueResponse(v, fieldMap.get(v.getFieldId())))
                .collect(Collectors.toList());

        List<ServiceRecordAttachmentEntity> attachments =
                attachmentRepository.findByServiceRecordIdOrderBySortOrder(entity.getId());
        List<AttachmentResponse> attachmentResponses = attachments.stream()
                .map(mapper::toAttachmentResponse)
                .collect(Collectors.toList());

        return ServiceRecordResponse.builder()
                .id(entity.getId())
                .teamId(entity.getTeamId())
                .memberUserId(entity.getMemberUserId())
                .staffUserId(entity.getStaffUserId())
                .serviceDate(entity.getServiceDate())
                .title(entity.getTitle())
                .note(entity.getNote())
                .durationMinutes(entity.getDurationMinutes())
                .status(entity.getStatus().name())
                .customFields(customFieldResponses)
                .attachments(attachmentResponses)
                .duplicatedFrom(duplicatedFrom)
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private BulkCreateResponse bulkCreateAllOrNothing(Long teamId, Long currentUserId,
                                                       List<CreateServiceRecordRequest> records) {
        List<BulkCreateResponse.BulkRecordEntry> entries = new ArrayList<>();
        for (CreateServiceRecordRequest req : records) {
            ServiceRecordResponse response = createRecord(teamId, currentUserId, req);
            entries.add(BulkCreateResponse.BulkRecordEntry.builder()
                    .id(response.getId())
                    .title(response.getTitle())
                    .status(response.getStatus())
                    .build());
        }
        return BulkCreateResponse.builder()
                .createdCount(entries.size())
                .records(entries)
                .build();
    }

    private BulkCreateResponse bulkCreateBestEffort(Long teamId, Long currentUserId,
                                                     List<CreateServiceRecordRequest> records) {
        List<BulkCreateResponse.BulkResultEntry> results = new ArrayList<>();
        int created = 0;
        int failed = 0;

        for (int i = 0; i < records.size(); i++) {
            try {
                ServiceRecordResponse response = createRecord(teamId, currentUserId, records.get(i));
                results.add(BulkCreateResponse.BulkResultEntry.builder()
                        .index(i)
                        .status("SUCCESS")
                        .id(response.getId())
                        .title(response.getTitle())
                        .build());
                created++;
            } catch (Exception e) {
                results.add(BulkCreateResponse.BulkResultEntry.builder()
                        .index(i)
                        .status("FAILED")
                        .error(e.getMessage())
                        .build());
                failed++;
            }
        }

        return BulkCreateResponse.builder()
                .createdCount(created)
                .failedCount(failed)
                .results(results)
                .build();
    }
}
