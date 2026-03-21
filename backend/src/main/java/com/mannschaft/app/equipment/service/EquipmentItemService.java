package com.mannschaft.app.equipment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.equipment.EquipmentErrorCode;
import com.mannschaft.app.equipment.EquipmentMapper;
import com.mannschaft.app.equipment.EquipmentScopeType;
import com.mannschaft.app.equipment.EquipmentStatus;
import com.mannschaft.app.equipment.dto.CreateEquipmentItemRequest;
import com.mannschaft.app.equipment.dto.EquipmentItemResponse;
import com.mannschaft.app.equipment.dto.PresignedUrlRequest;
import com.mannschaft.app.equipment.dto.PresignedUrlResponse;
import com.mannschaft.app.equipment.dto.QrCodeResponse;
import com.mannschaft.app.equipment.dto.UpdateEquipmentItemRequest;
import com.mannschaft.app.equipment.entity.EquipmentItemEntity;
import com.mannschaft.app.equipment.repository.EquipmentAssignmentRepository;
import com.mannschaft.app.equipment.repository.EquipmentItemRepository;
import com.mannschaft.app.equipment.util.QrCodeGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 備品マスターサービス。備品のCRUD・画像管理・カテゴリ取得・QRコード一覧を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EquipmentItemService {

    private final EquipmentItemRepository itemRepository;
    private final EquipmentAssignmentRepository assignmentRepository;
    private final EquipmentMapper equipmentMapper;
    private final QrCodeGenerator qrCodeGenerator;

    @Value("${app.domain-base:https://app.mannschaft.example}")
    private String domainBase;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

    // ===================== 一覧取得 =====================

    /**
     * チーム備品一覧をページング取得する。
     */
    public Page<EquipmentItemResponse> listByTeam(Long teamId, String category, String status,
                                                   String nameLike, Pageable pageable) {
        Page<EquipmentItemEntity> page = resolveTeamPage(teamId, category, status, nameLike, pageable);
        return page.map(equipmentMapper::toItemResponse);
    }

    /**
     * 組織備品一覧をページング取得する。
     */
    public Page<EquipmentItemResponse> listByOrganization(Long orgId, String category, String status,
                                                           String nameLike, Pageable pageable) {
        Page<EquipmentItemEntity> page = resolveOrgPage(orgId, category, status, nameLike, pageable);
        return page.map(equipmentMapper::toItemResponse);
    }

    // ===================== 詳細取得 =====================

    /**
     * チーム備品の詳細を取得する。
     */
    public EquipmentItemResponse getByTeam(Long teamId, Long id) {
        EquipmentItemEntity entity = findTeamItemOrThrow(teamId, id);
        return equipmentMapper.toItemResponse(entity);
    }

    /**
     * 組織備品の詳細を取得する。
     */
    public EquipmentItemResponse getByOrganization(Long orgId, Long id) {
        EquipmentItemEntity entity = findOrgItemOrThrow(orgId, id);
        return equipmentMapper.toItemResponse(entity);
    }

    // ===================== 作成 =====================

    /**
     * チーム備品を作成する。
     */
    @Transactional
    public EquipmentItemResponse createForTeam(Long teamId, CreateEquipmentItemRequest request) {
        String qrCode = qrCodeGenerator.generate(EquipmentScopeType.TEAM, teamId);
        EquipmentItemEntity entity = buildEntity(request, teamId, null, qrCode);
        EquipmentItemEntity saved = itemRepository.save(entity);
        log.info("備品作成（チーム）: teamId={}, itemId={}", teamId, saved.getId());
        return equipmentMapper.toItemResponse(saved);
    }

    /**
     * 組織備品を作成する。
     */
    @Transactional
    public EquipmentItemResponse createForOrganization(Long orgId, CreateEquipmentItemRequest request) {
        String qrCode = qrCodeGenerator.generate(EquipmentScopeType.ORGANIZATION, orgId);
        EquipmentItemEntity entity = buildEntity(request, null, orgId, qrCode);
        EquipmentItemEntity saved = itemRepository.save(entity);
        log.info("備品作成（組織）: orgId={}, itemId={}", orgId, saved.getId());
        return equipmentMapper.toItemResponse(saved);
    }

    // ===================== 更新 =====================

    /**
     * チーム備品を更新する。
     */
    @Transactional
    public EquipmentItemResponse updateForTeam(Long teamId, Long id, UpdateEquipmentItemRequest request) {
        EquipmentItemEntity entity = findTeamItemOrThrow(teamId, id);
        applyUpdate(entity, request);
        EquipmentItemEntity saved = itemRepository.save(entity);
        log.info("備品更新（チーム）: teamId={}, itemId={}", teamId, id);
        return equipmentMapper.toItemResponse(saved);
    }

    /**
     * 組織備品を更新する。
     */
    @Transactional
    public EquipmentItemResponse updateForOrganization(Long orgId, Long id, UpdateEquipmentItemRequest request) {
        EquipmentItemEntity entity = findOrgItemOrThrow(orgId, id);
        applyUpdate(entity, request);
        EquipmentItemEntity saved = itemRepository.save(entity);
        log.info("備品更新（組織）: orgId={}, itemId={}", orgId, id);
        return equipmentMapper.toItemResponse(saved);
    }

    // ===================== 削除 =====================

    /**
     * チーム備品を論理削除する。
     */
    @Transactional
    public void deleteForTeam(Long teamId, Long id) {
        EquipmentItemEntity entity = findTeamItemOrThrow(teamId, id);
        validateNoActiveAssignments(entity);
        entity.softDelete();
        itemRepository.save(entity);
        log.info("備品削除（チーム）: teamId={}, itemId={}", teamId, id);
    }

    /**
     * 組織備品を論理削除する。
     */
    @Transactional
    public void deleteForOrganization(Long orgId, Long id) {
        EquipmentItemEntity entity = findOrgItemOrThrow(orgId, id);
        validateNoActiveAssignments(entity);
        entity.softDelete();
        itemRepository.save(entity);
        log.info("備品削除（組織）: orgId={}, itemId={}", orgId, id);
    }

    // ===================== カテゴリ一覧 =====================

    /**
     * チーム内のカテゴリ一覧をDISTINCTで取得する。
     */
    public List<String> getCategoriesByTeam(Long teamId) {
        return itemRepository.findDistinctCategoriesByTeamId(teamId);
    }

    /**
     * 組織内のカテゴリ一覧をDISTINCTで取得する。
     */
    public List<String> getCategoriesByOrganization(Long orgId) {
        return itemRepository.findDistinctCategoriesByOrganizationId(orgId);
    }

    // ===================== 画像管理 =====================

    /**
     * 備品画像アップロード用 Pre-signed URL を取得する（チーム）。
     */
    @Transactional
    public PresignedUrlResponse getPresignedUrlForTeam(Long teamId, Long id, PresignedUrlRequest request) {
        EquipmentItemEntity entity = findTeamItemOrThrow(teamId, id);
        return generatePresignedUrl(entity, "teams", teamId, request);
    }

    /**
     * 備品画像アップロード用 Pre-signed URL を取得する（組織）。
     */
    @Transactional
    public PresignedUrlResponse getPresignedUrlForOrganization(Long orgId, Long id, PresignedUrlRequest request) {
        EquipmentItemEntity entity = findOrgItemOrThrow(orgId, id);
        return generatePresignedUrl(entity, "organizations", orgId, request);
    }

    /**
     * 備品画像を削除する（チーム）。
     */
    @Transactional
    public void deleteImageForTeam(Long teamId, Long id) {
        EquipmentItemEntity entity = findTeamItemOrThrow(teamId, id);
        entity.updateS3Key(null);
        itemRepository.save(entity);
        log.info("備品画像削除（チーム）: teamId={}, itemId={}", teamId, id);
        // TODO: ApplicationEvent で旧 S3 オブジェクトを非同期削除
    }

    /**
     * 備品画像を削除する（組織）。
     */
    @Transactional
    public void deleteImageForOrganization(Long orgId, Long id) {
        EquipmentItemEntity entity = findOrgItemOrThrow(orgId, id);
        entity.updateS3Key(null);
        itemRepository.save(entity);
        log.info("備品画像削除（組織）: orgId={}, itemId={}", orgId, id);
        // TODO: ApplicationEvent で旧 S3 オブジェクトを非同期削除
    }

    // ===================== QRコード一覧 =====================

    /**
     * チーム備品のQRコード一覧を取得する。
     */
    public List<QrCodeResponse> getQrCodesByTeam(Long teamId, String ids, String category,
                                                  String status, String nameLike) {
        List<EquipmentItemEntity> items;
        if (ids != null && !ids.isBlank()) {
            List<Long> idList = parseIds(ids);
            items = itemRepository.findAllById(idList).stream()
                    .filter(e -> teamId.equals(e.getTeamId()))
                    .toList();
        } else if (category != null && !category.isBlank()) {
            items = itemRepository.findByTeamIdAndCategoryAndStatusNot(teamId, category, EquipmentStatus.RETIRED);
        } else if (nameLike != null && !nameLike.isBlank()) {
            items = itemRepository.findByTeamIdAndNameContainingAndStatusNot(teamId, nameLike, EquipmentStatus.RETIRED);
        } else {
            EquipmentStatus filterStatus = status != null ? EquipmentStatus.valueOf(status) : null;
            if (filterStatus != null) {
                items = itemRepository.findByTeamIdAndCategoryAndStatusNot(teamId, null, EquipmentStatus.RETIRED).stream()
                        .filter(e -> e.getStatus() == filterStatus)
                        .toList();
            } else {
                items = itemRepository.findByTeamIdAndStatusNot(teamId, EquipmentStatus.RETIRED);
            }
        }
        return items.stream()
                .map(this::toQrCodeResponse)
                .toList();
    }

    /**
     * 組織備品のQRコード一覧を取得する。
     */
    public List<QrCodeResponse> getQrCodesByOrganization(Long orgId, String ids, String category,
                                                          String status, String nameLike) {
        List<EquipmentItemEntity> items;
        if (ids != null && !ids.isBlank()) {
            List<Long> idList = parseIds(ids);
            items = itemRepository.findAllById(idList).stream()
                    .filter(e -> orgId.equals(e.getOrganizationId()))
                    .toList();
        } else if (category != null && !category.isBlank()) {
            items = itemRepository.findByOrganizationIdAndCategoryAndStatusNot(orgId, category, EquipmentStatus.RETIRED);
        } else if (nameLike != null && !nameLike.isBlank()) {
            items = itemRepository.findByOrganizationIdAndNameContainingAndStatusNot(orgId, nameLike, EquipmentStatus.RETIRED);
        } else {
            EquipmentStatus filterStatus = status != null ? EquipmentStatus.valueOf(status) : null;
            if (filterStatus != null) {
                items = itemRepository.findByOrganizationIdAndStatusNot(orgId, EquipmentStatus.RETIRED).stream()
                        .filter(e -> e.getStatus() == filterStatus)
                        .toList();
            } else {
                items = itemRepository.findByOrganizationIdAndStatusNot(orgId, EquipmentStatus.RETIRED);
            }
        }
        return items.stream()
                .map(this::toQrCodeResponse)
                .toList();
    }

    // ===================== 内部ヘルパー =====================

    /**
     * チームスコープの備品を取得する。見つからない場合は例外をスローする。
     */
    EquipmentItemEntity findTeamItemOrThrow(Long teamId, Long id) {
        return itemRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(EquipmentErrorCode.ITEM_NOT_FOUND));
    }

    /**
     * 組織スコープの備品を取得する。見つからない場合は例外をスローする。
     */
    EquipmentItemEntity findOrgItemOrThrow(Long orgId, Long id) {
        return itemRepository.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new BusinessException(EquipmentErrorCode.ITEM_NOT_FOUND));
    }

    private EquipmentItemEntity buildEntity(CreateEquipmentItemRequest request,
                                            Long teamId, Long orgId, String qrCode) {
        return EquipmentItemEntity.builder()
                .teamId(teamId)
                .organizationId(orgId)
                .name(request.getName())
                .description(request.getDescription())
                .category(request.getCategory())
                .quantity(request.getQuantity() != null ? request.getQuantity() : 1)
                .isConsumable(request.getIsConsumable() != null ? request.getIsConsumable() : false)
                .storageLocation(request.getStorageLocation())
                .purchaseDate(request.getPurchaseDate())
                .purchasePrice(request.getPurchasePrice())
                .qrCode(qrCode)
                .build();
    }

    private void applyUpdate(EquipmentItemEntity entity, UpdateEquipmentItemRequest request) {
        entity.update(
                request.getName(),
                request.getDescription(),
                request.getCategory(),
                request.getQuantity() != null ? request.getQuantity() : entity.getQuantity(),
                request.getStorageLocation(),
                request.getPurchaseDate(),
                request.getPurchasePrice(),
                request.getIsConsumable() != null ? request.getIsConsumable() : entity.getIsConsumable()
        );
        if (request.getStatus() != null) {
            entity.changeStatus(EquipmentStatus.valueOf(request.getStatus()));
        }
    }

    private void validateNoActiveAssignments(EquipmentItemEntity entity) {
        if (assignmentRepository.existsByEquipmentItemIdAndReturnedAtIsNull(entity.getId())) {
            throw new BusinessException(EquipmentErrorCode.HAS_ACTIVE_ASSIGNMENTS);
        }
    }

    private PresignedUrlResponse generatePresignedUrl(EquipmentItemEntity entity, String scopeTypePath,
                                                       Long scopeId, PresignedUrlRequest request) {
        if (!ALLOWED_CONTENT_TYPES.contains(request.getContentType())) {
            throw new BusinessException(EquipmentErrorCode.INVALID_CONTENT_TYPE);
        }
        if (request.getFileSize() > MAX_FILE_SIZE) {
            throw new BusinessException(EquipmentErrorCode.FILE_SIZE_EXCEEDED);
        }
        String extension = switch (request.getContentType()) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".bin";
        };
        String s3Key = String.format("equipment/%s/%d/%d/%s%s",
                scopeTypePath, scopeId, entity.getId(), UUID.randomUUID(), extension);
        entity.updateS3Key(s3Key);
        itemRepository.save(entity);

        // TODO: 実際の S3 Pre-signed URL 生成に置き換え
        String uploadUrl = "https://s3.amazonaws.com/mannschaft-bucket/" + s3Key + "?presigned=placeholder";
        return new PresignedUrlResponse(uploadUrl, s3Key, 600);
    }

    private QrCodeResponse toQrCodeResponse(EquipmentItemEntity entity) {
        String qrUrl = qrCodeGenerator.toDeepLinkUrl(entity.getQrCode(), domainBase);
        return new QrCodeResponse(
                entity.getId(),
                entity.getName(),
                entity.getCategory(),
                entity.getStorageLocation(),
                entity.getQrCode(),
                qrUrl
        );
    }

    private List<Long> parseIds(String ids) {
        return java.util.Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
    }

    private Page<EquipmentItemEntity> resolveTeamPage(Long teamId, String category, String status,
                                                       String nameLike, Pageable pageable) {
        if (nameLike != null && !nameLike.isBlank()) {
            return itemRepository.findByTeamIdAndNameContaining(teamId, nameLike, pageable);
        }
        if (category != null && !category.isBlank()) {
            return itemRepository.findByTeamIdAndCategory(teamId, category, pageable);
        }
        if (status != null && !status.isBlank()) {
            return itemRepository.findByTeamIdAndStatus(teamId, EquipmentStatus.valueOf(status), pageable);
        }
        return itemRepository.findByTeamId(teamId, pageable);
    }

    private Page<EquipmentItemEntity> resolveOrgPage(Long orgId, String category, String status,
                                                      String nameLike, Pageable pageable) {
        if (nameLike != null && !nameLike.isBlank()) {
            return itemRepository.findByOrganizationIdAndNameContaining(orgId, nameLike, pageable);
        }
        if (category != null && !category.isBlank()) {
            return itemRepository.findByOrganizationIdAndCategory(orgId, category, pageable);
        }
        if (status != null && !status.isBlank()) {
            return itemRepository.findByOrganizationIdAndStatus(orgId, EquipmentStatus.valueOf(status), pageable);
        }
        return itemRepository.findByOrganizationId(orgId, pageable);
    }
}
