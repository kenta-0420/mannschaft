package com.mannschaft.app.resident.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.resident.ResidentErrorCode;
import com.mannschaft.app.resident.dto.CreateInquiryRequest;
import com.mannschaft.app.resident.dto.CreatePropertyListingRequest;
import com.mannschaft.app.resident.dto.InquiryResponse;
import com.mannschaft.app.resident.dto.PropertyListingResponse;
import com.mannschaft.app.resident.dto.UpdatePropertyListingRequest;
import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import com.mannschaft.app.resident.entity.PropertyListingEntity;
import com.mannschaft.app.resident.entity.PropertyListingInquiryEntity;
import com.mannschaft.app.resident.mapper.ResidentMapper;
import com.mannschaft.app.resident.repository.DwellingUnitRepository;
import com.mannschaft.app.resident.repository.PropertyListingInquiryRepository;
import com.mannschaft.app.resident.repository.PropertyListingRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 物件掲示板サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PropertyListingService {

    private final PropertyListingRepository listingRepository;
    private final PropertyListingInquiryRepository inquiryRepository;
    private final DwellingUnitRepository dwellingUnitRepository;
    private final ResidentMapper residentMapper;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;

    /**
     * チームの物件一覧を取得する。
     * 認可: 指定チームのメンバーのみ閲覧可能。
     */
    public Page<PropertyListingResponse> listByTeam(Long actorUserId, Long teamId, String status, String listingType, Pageable pageable) {
        accessControlService.checkMembership(actorUserId, teamId, "TEAM");
        return listingRepository.findByTeamId(teamId, status, listingType, pageable)
                .map(residentMapper::toPropertyListingResponse);
    }

    /**
     * 組織の物件一覧を取得する。
     * 認可: 指定組織のメンバーのみ閲覧可能。
     */
    public Page<PropertyListingResponse> listByOrganization(Long actorUserId, Long orgId, String status, String listingType, Pageable pageable) {
        accessControlService.checkMembership(actorUserId, orgId, "ORGANIZATION");
        return listingRepository.findByOrganizationId(orgId, status, listingType, pageable)
                .map(residentMapper::toPropertyListingResponse);
    }

    /**
     * チームの物件掲示を作成する。
     * 認可: 指定チームの ADMIN/DEPUTY_ADMIN のみ作成可能。userId は操作者本人であり、
     * そのまま認可チェックのactorとして使う。
     */
    @Transactional
    public PropertyListingResponse createForTeam(Long teamId, Long userId, CreatePropertyListingRequest request) {
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        String imageUrlsJson = serializeImageUrls(request.getImageUrls());
        PropertyListingEntity entity = PropertyListingEntity.builder()
                .dwellingUnitId(request.getDwellingUnitId())
                .listedBy(userId)
                .listingType(request.getListingType())
                .title(request.getTitle())
                .description(request.getDescription())
                .askingPrice(request.getAskingPrice())
                .monthlyRent(request.getMonthlyRent())
                .expiresAt(request.getExpiresAt())
                .imageUrls(imageUrlsJson)
                .build();
        PropertyListingEntity saved = listingRepository.save(entity);
        log.info("物件掲示作成: teamId={}, listingId={}", teamId, saved.getId());
        return residentMapper.toPropertyListingResponse(saved);
    }

    /**
     * 組織の物件掲示を作成する。
     * 認可: 指定組織の ADMIN/DEPUTY_ADMIN のみ作成可能。userId は操作者本人であり、
     * そのまま認可チェックのactorとして使う。
     */
    @Transactional
    public PropertyListingResponse createForOrganization(Long orgId, Long userId, CreatePropertyListingRequest request) {
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");
        String imageUrlsJson = serializeImageUrls(request.getImageUrls());
        PropertyListingEntity entity = PropertyListingEntity.builder()
                .dwellingUnitId(request.getDwellingUnitId())
                .listedBy(userId)
                .listingType(request.getListingType())
                .title(request.getTitle())
                .description(request.getDescription())
                .askingPrice(request.getAskingPrice())
                .monthlyRent(request.getMonthlyRent())
                .expiresAt(request.getExpiresAt())
                .imageUrls(imageUrlsJson)
                .build();
        PropertyListingEntity saved = listingRepository.save(entity);
        log.info("物件掲示作成: orgId={}, listingId={}", orgId, saved.getId());
        return residentMapper.toPropertyListingResponse(saved);
    }

    /**
     * チームの物件詳細を取得する。
     * 認可: 指定チームのメンバーのみ閲覧可能。
     */
    public PropertyListingResponse getByTeam(Long actorUserId, Long teamId, Long id) {
        accessControlService.checkMembership(actorUserId, teamId, "TEAM");
        PropertyListingEntity entity = listingRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.LISTING_NOT_FOUND));
        return residentMapper.toPropertyListingResponse(entity);
    }

    /**
     * 組織の物件詳細を取得する。
     * 認可: 指定組織のメンバーのみ閲覧可能。
     */
    public PropertyListingResponse getByOrganization(Long actorUserId, Long orgId, Long id) {
        accessControlService.checkMembership(actorUserId, orgId, "ORGANIZATION");
        PropertyListingEntity entity = listingRepository.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.LISTING_NOT_FOUND));
        return residentMapper.toPropertyListingResponse(entity);
    }

    /**
     * チームの物件を更新する。
     * 認可: 指定チームの ADMIN/DEPUTY_ADMIN のみ更新可能。
     */
    @Transactional
    public PropertyListingResponse updateForTeam(Long actorUserId, Long teamId, Long id, UpdatePropertyListingRequest request) {
        accessControlService.checkAdminOrAbove(actorUserId, teamId, "TEAM");
        PropertyListingEntity entity = listingRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.LISTING_NOT_FOUND));
        if (!entity.isEditable()) {
            throw new BusinessException(ResidentErrorCode.LISTING_NOT_EDITABLE);
        }
        String imageUrlsJson = serializeImageUrls(request.getImageUrls());
        entity.update(request.getTitle(), request.getDescription(),
                request.getAskingPrice(), request.getMonthlyRent(),
                request.getExpiresAt(), imageUrlsJson);
        PropertyListingEntity saved = listingRepository.save(entity);
        log.info("物件更新: listingId={}", id);
        return residentMapper.toPropertyListingResponse(saved);
    }

    /**
     * 組織の物件を更新する。
     * 認可: 指定組織の ADMIN/DEPUTY_ADMIN のみ更新可能。
     */
    @Transactional
    public PropertyListingResponse updateForOrganization(Long actorUserId, Long orgId, Long id, UpdatePropertyListingRequest request) {
        accessControlService.checkAdminOrAbove(actorUserId, orgId, "ORGANIZATION");
        PropertyListingEntity entity = listingRepository.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.LISTING_NOT_FOUND));
        if (!entity.isEditable()) {
            throw new BusinessException(ResidentErrorCode.LISTING_NOT_EDITABLE);
        }
        String imageUrlsJson = serializeImageUrls(request.getImageUrls());
        entity.update(request.getTitle(), request.getDescription(),
                request.getAskingPrice(), request.getMonthlyRent(),
                request.getExpiresAt(), imageUrlsJson);
        PropertyListingEntity saved = listingRepository.save(entity);
        log.info("物件更新: listingId={}", id);
        return residentMapper.toPropertyListingResponse(saved);
    }

    /**
     * チームの物件を削除する。
     * 認可: 指定チームの ADMIN/DEPUTY_ADMIN のみ削除可能。
     */
    @Transactional
    public void deleteForTeam(Long actorUserId, Long teamId, Long id) {
        accessControlService.checkAdminOrAbove(actorUserId, teamId, "TEAM");
        PropertyListingEntity entity = listingRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.LISTING_NOT_FOUND));
        entity.softDelete();
        listingRepository.save(entity);
        log.info("物件削除: listingId={}", id);
    }

    /**
     * 組織の物件を削除する。
     * 認可: 指定組織の ADMIN/DEPUTY_ADMIN のみ削除可能。
     */
    @Transactional
    public void deleteForOrganization(Long actorUserId, Long orgId, Long id) {
        accessControlService.checkAdminOrAbove(actorUserId, orgId, "ORGANIZATION");
        PropertyListingEntity entity = listingRepository.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.LISTING_NOT_FOUND));
        entity.softDelete();
        listingRepository.save(entity);
        log.info("物件削除: listingId={}", id);
    }

    /**
     * 問い合わせを作成する。
     * 認可: 物件が実在するスコープ（entity由来）のメンバーのみ可能。userId は操作者本人であり、
     * そのまま認可チェックのactorとして使う。
     */
    @Transactional
    public InquiryResponse createInquiry(Long listingId, Long userId, CreateInquiryRequest request) {
        DwellingUnitEntity unit = findUnitForListing(listingId);
        accessControlService.checkMembership(userId, unit.resolveScopeId(), unit.getScopeType());

        if (inquiryRepository.existsByListingIdAndUserId(listingId, userId)) {
            throw new BusinessException(ResidentErrorCode.DUPLICATE_INQUIRY);
        }
        PropertyListingInquiryEntity entity = PropertyListingInquiryEntity.builder()
                .listingId(listingId)
                .userId(userId)
                .message(request.getMessage())
                .build();
        PropertyListingInquiryEntity saved = inquiryRepository.save(entity);
        log.info("問い合わせ作成: listingId={}, userId={}", listingId, userId);
        return residentMapper.toInquiryResponse(saved);
    }

    /**
     * 問い合わせ一覧を取得する。
     * 認可: 物件が実在するスコープ（entity由来）のメンバーのみ閲覧可能。
     */
    public List<InquiryResponse> listInquiries(Long actorUserId, Long listingId) {
        DwellingUnitEntity unit = findUnitForListing(listingId);
        accessControlService.checkMembership(actorUserId, unit.resolveScopeId(), unit.getScopeType());

        List<PropertyListingInquiryEntity> entities =
                inquiryRepository.findByListingIdOrderByCreatedAtDesc(listingId);
        return residentMapper.toInquiryResponseList(entities);
    }

    /**
     * 物件掲示IDから所属居室を fetch する（存在検証も兼ねる）。
     */
    private DwellingUnitEntity findUnitForListing(Long listingId) {
        PropertyListingEntity listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.LISTING_NOT_FOUND));
        return dwellingUnitRepository.findById(listing.getDwellingUnitId())
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));
    }

    private String serializeImageUrls(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(imageUrls);
        } catch (JsonProcessingException e) {
            log.warn("画像URL JSON変換失敗", e);
            return null;
        }
    }
}
