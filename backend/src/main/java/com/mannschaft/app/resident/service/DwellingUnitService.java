package com.mannschaft.app.resident.service;

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

    /**
     * チームの居室一覧を取得する。
     */
    public Page<DwellingUnitResponse> listByTeam(Long teamId, Pageable pageable) {
        return dwellingUnitRepository
                .findByScopeTypeAndTeamIdOrderByUnitNumberAsc("TEAM", teamId, pageable)
                .map(residentMapper::toDwellingUnitResponse);
    }

    /**
     * 組織の居室一覧を取得する。
     */
    public Page<DwellingUnitResponse> listByOrganization(Long orgId, Pageable pageable) {
        return dwellingUnitRepository
                .findByScopeTypeAndOrganizationIdOrderByUnitNumberAsc("ORGANIZATION", orgId, pageable)
                .map(residentMapper::toDwellingUnitResponse);
    }

    /**
     * チームの居室を作成する。
     */
    @Transactional
    public DwellingUnitResponse createForTeam(Long teamId, CreateDwellingUnitRequest request) {
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
     */
    @Transactional
    public DwellingUnitResponse createForOrganization(Long orgId, CreateDwellingUnitRequest request) {
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
     */
    public DwellingUnitResponse getByTeam(Long teamId, Long id) {
        DwellingUnitEntity entity = dwellingUnitRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));
        return residentMapper.toDwellingUnitResponse(entity);
    }

    /**
     * 組織の居室詳細を取得する。
     */
    public DwellingUnitResponse getByOrganization(Long orgId, Long id) {
        DwellingUnitEntity entity = dwellingUnitRepository.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));
        return residentMapper.toDwellingUnitResponse(entity);
    }

    /**
     * チームの居室を更新する。
     */
    @Transactional
    public DwellingUnitResponse updateForTeam(Long teamId, Long id, CreateDwellingUnitRequest request) {
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
     */
    @Transactional
    public DwellingUnitResponse updateForOrganization(Long orgId, Long id, CreateDwellingUnitRequest request) {
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
     */
    @Transactional
    public void deleteForTeam(Long teamId, Long id) {
        DwellingUnitEntity entity = dwellingUnitRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));
        entity.softDelete();
        dwellingUnitRepository.save(entity);
        log.info("居室削除: unitId={}", id);
    }

    /**
     * 組織の居室を削除する。
     */
    @Transactional
    public void deleteForOrganization(Long orgId, Long id) {
        DwellingUnitEntity entity = dwellingUnitRepository.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));
        entity.softDelete();
        dwellingUnitRepository.save(entity);
        log.info("居室削除: unitId={}", id);
    }

    /**
     * チームの居室一括登録。
     */
    @Transactional
    public List<DwellingUnitResponse> batchCreateForTeam(Long teamId, BatchCreateDwellingUnitRequest request) {
        List<DwellingUnitResponse> results = new ArrayList<>();
        for (CreateDwellingUnitRequest unit : request.getUnits()) {
            results.add(createForTeam(teamId, unit));
        }
        log.info("居室一括登録: teamId={}, count={}", teamId, results.size());
        return results;
    }

    /**
     * 組織の居室一括登録。
     */
    @Transactional
    public List<DwellingUnitResponse> batchCreateForOrganization(Long orgId, BatchCreateDwellingUnitRequest request) {
        List<DwellingUnitResponse> results = new ArrayList<>();
        for (CreateDwellingUnitRequest unit : request.getUnits()) {
            results.add(createForOrganization(orgId, unit));
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
