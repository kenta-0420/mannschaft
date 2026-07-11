package com.mannschaft.app.resident.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.resident.ResidentErrorCode;
import com.mannschaft.app.resident.dto.BatchCreateDwellingUnitRequest;
import com.mannschaft.app.resident.dto.CreateDwellingUnitRequest;
import com.mannschaft.app.resident.dto.DwellingUnitResponse;
import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import com.mannschaft.app.resident.mapper.ResidentMapper;
import com.mannschaft.app.resident.repository.DwellingUnitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 居室管理サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DwellingUnitService {

    private final DwellingUnitRepository dwellingUnitRepository;
    private final ResidentMapper residentMapper;
    private final AccessControlService accessControlService;

    /**
     * チームの居室一覧を取得する。
     * 認可: 指定チームのメンバーのみ閲覧可能。
     */
    public Page<DwellingUnitResponse> listByTeam(Long actorUserId, Long teamId, Pageable pageable) {
        accessControlService.checkMembership(actorUserId, teamId, "TEAM");
        return dwellingUnitRepository
                .findByScopeTypeAndTeamIdOrderByUnitNumberAsc("TEAM", teamId, pageable)
                .map(residentMapper::toDwellingUnitResponse);
    }

    /**
     * 組織の居室一覧を取得する。
     * 認可: 指定組織のメンバーのみ閲覧可能。
     */
    public Page<DwellingUnitResponse> listByOrganization(Long actorUserId, Long orgId, Pageable pageable) {
        accessControlService.checkMembership(actorUserId, orgId, "ORGANIZATION");
        return dwellingUnitRepository
                .findByScopeTypeAndOrganizationIdOrderByUnitNumberAsc("ORGANIZATION", orgId, pageable)
                .map(residentMapper::toDwellingUnitResponse);
    }

    /**
     * チームの居室を作成する。
     * 認可: 指定チームの ADMIN/DEPUTY_ADMIN のみ作成可能。
     */
    @Transactional
    public DwellingUnitResponse createForTeam(Long actorUserId, Long teamId, CreateDwellingUnitRequest request) {
        accessControlService.checkAdminOrAbove(actorUserId, teamId, "TEAM");
        if (dwellingUnitRepository.existsByTeamIdAndUnitNumber(teamId, request.getUnitNumber())) {
            throw new BusinessException(ResidentErrorCode.DUPLICATE_UNIT_NUMBER);
        }
        DwellingUnitEntity entity = DwellingUnitEntity.builder()
                .scopeType("TEAM")
                .teamId(teamId)
                .unitNumber(request.getUnitNumber())
                .floor(request.getFloor())
                .areaSqm(request.getAreaSqm())
                .layout(request.getLayout())
                .unitType(request.getUnitType() != null ? request.getUnitType() : "STANDARD")
                .notes(request.getNotes())
                .build();
        DwellingUnitEntity saved = dwellingUnitRepository.save(entity);
        log.info("居室作成: teamId={}, unitId={}", teamId, saved.getId());
        return residentMapper.toDwellingUnitResponse(saved);
    }

    /**
     * 組織の居室を作成する。
     * 認可: 指定組織の ADMIN/DEPUTY_ADMIN のみ作成可能。
     */
    @Transactional
    public DwellingUnitResponse createForOrganization(Long actorUserId, Long orgId, CreateDwellingUnitRequest request) {
        accessControlService.checkAdminOrAbove(actorUserId, orgId, "ORGANIZATION");
        if (dwellingUnitRepository.existsByOrganizationIdAndUnitNumber(orgId, request.getUnitNumber())) {
            throw new BusinessException(ResidentErrorCode.DUPLICATE_UNIT_NUMBER);
        }
        DwellingUnitEntity entity = DwellingUnitEntity.builder()
                .scopeType("ORGANIZATION")
                .organizationId(orgId)
                .unitNumber(request.getUnitNumber())
                .floor(request.getFloor())
                .areaSqm(request.getAreaSqm())
                .layout(request.getLayout())
                .unitType(request.getUnitType() != null ? request.getUnitType() : "STANDARD")
                .notes(request.getNotes())
                .build();
        DwellingUnitEntity saved = dwellingUnitRepository.save(entity);
        log.info("居室作成: orgId={}, unitId={}", orgId, saved.getId());
        return residentMapper.toDwellingUnitResponse(saved);
    }

    /**
     * チームの居室詳細を取得する。
     * 認可: 指定チームのメンバーのみ閲覧可能。
     */
    public DwellingUnitResponse getByTeam(Long actorUserId, Long teamId, Long id) {
        accessControlService.checkMembership(actorUserId, teamId, "TEAM");
        DwellingUnitEntity entity = dwellingUnitRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));
        return residentMapper.toDwellingUnitResponse(entity);
    }

    /**
     * 組織の居室詳細を取得する。
     * 認可: 指定組織のメンバーのみ閲覧可能。
     */
    public DwellingUnitResponse getByOrganization(Long actorUserId, Long orgId, Long id) {
        accessControlService.checkMembership(actorUserId, orgId, "ORGANIZATION");
        DwellingUnitEntity entity = dwellingUnitRepository.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));
        return residentMapper.toDwellingUnitResponse(entity);
    }

    /**
     * チームの居室を更新する。
     * 認可: 指定チームの ADMIN/DEPUTY_ADMIN のみ更新可能。
     */
    @Transactional
    public DwellingUnitResponse updateForTeam(Long actorUserId, Long teamId, Long id, CreateDwellingUnitRequest request) {
        accessControlService.checkAdminOrAbove(actorUserId, teamId, "TEAM");
        DwellingUnitEntity entity = dwellingUnitRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));
        entity.update(request.getUnitNumber(), request.getFloor(), request.getAreaSqm(),
                request.getLayout(), request.getUnitType() != null ? request.getUnitType() : "STANDARD",
                request.getNotes());
        DwellingUnitEntity saved = dwellingUnitRepository.save(entity);
        log.info("居室更新: unitId={}", id);
        return residentMapper.toDwellingUnitResponse(saved);
    }

    /**
     * 組織の居室を更新する。
     * 認可: 指定組織の ADMIN/DEPUTY_ADMIN のみ更新可能。
     */
    @Transactional
    public DwellingUnitResponse updateForOrganization(Long actorUserId, Long orgId, Long id, CreateDwellingUnitRequest request) {
        accessControlService.checkAdminOrAbove(actorUserId, orgId, "ORGANIZATION");
        DwellingUnitEntity entity = dwellingUnitRepository.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));
        entity.update(request.getUnitNumber(), request.getFloor(), request.getAreaSqm(),
                request.getLayout(), request.getUnitType() != null ? request.getUnitType() : "STANDARD",
                request.getNotes());
        DwellingUnitEntity saved = dwellingUnitRepository.save(entity);
        log.info("居室更新: unitId={}", id);
        return residentMapper.toDwellingUnitResponse(saved);
    }

    /**
     * チームの居室を削除する。
     * 認可: 指定チームの ADMIN/DEPUTY_ADMIN のみ削除可能。
     */
    @Transactional
    public void deleteForTeam(Long actorUserId, Long teamId, Long id) {
        accessControlService.checkAdminOrAbove(actorUserId, teamId, "TEAM");
        DwellingUnitEntity entity = dwellingUnitRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));
        entity.softDelete();
        dwellingUnitRepository.save(entity);
        log.info("居室削除: unitId={}", id);
    }

    /**
     * 組織の居室を削除する。
     * 認可: 指定組織の ADMIN/DEPUTY_ADMIN のみ削除可能。
     */
    @Transactional
    public void deleteForOrganization(Long actorUserId, Long orgId, Long id) {
        accessControlService.checkAdminOrAbove(actorUserId, orgId, "ORGANIZATION");
        DwellingUnitEntity entity = dwellingUnitRepository.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));
        entity.softDelete();
        dwellingUnitRepository.save(entity);
        log.info("居室削除: unitId={}", id);
    }

    /**
     * チームの居室一括登録。
     * 認可: 指定チームの ADMIN/DEPUTY_ADMIN のみ実行可能（各行の createForTeam でも再検証される）。
     */
    @Transactional
    public List<DwellingUnitResponse> batchCreateForTeam(Long actorUserId, Long teamId, BatchCreateDwellingUnitRequest request) {
        accessControlService.checkAdminOrAbove(actorUserId, teamId, "TEAM");
        List<DwellingUnitResponse> results = new ArrayList<>();
        for (CreateDwellingUnitRequest unit : request.getUnits()) {
            results.add(createForTeam(actorUserId, teamId, unit));
        }
        log.info("居室一括登録: teamId={}, count={}", teamId, results.size());
        return results;
    }

    /**
     * 組織の居室一括登録。
     * 認可: 指定組織の ADMIN/DEPUTY_ADMIN のみ実行可能（各行の createForOrganization でも再検証される）。
     */
    @Transactional
    public List<DwellingUnitResponse> batchCreateForOrganization(Long actorUserId, Long orgId, BatchCreateDwellingUnitRequest request) {
        accessControlService.checkAdminOrAbove(actorUserId, orgId, "ORGANIZATION");
        List<DwellingUnitResponse> results = new ArrayList<>();
        for (CreateDwellingUnitRequest unit : request.getUnits()) {
            results.add(createForOrganization(actorUserId, orgId, unit));
        }
        log.info("居室一括登録: orgId={}, count={}", orgId, results.size());
        return results;
    }

    /**
     * 居室エンティティを取得する（内部用）。
     */
    DwellingUnitEntity findEntityByIdAndTeamId(Long id, Long teamId) {
        return dwellingUnitRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));
    }

    /**
     * 居室エンティティを取得する（内部用・組織）。
     */
    DwellingUnitEntity findEntityByIdAndOrganizationId(Long id, Long orgId) {
        return dwellingUnitRepository.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));
    }
}
