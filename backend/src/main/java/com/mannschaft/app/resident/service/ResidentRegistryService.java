package com.mannschaft.app.resident.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.resident.ResidentErrorCode;
import com.mannschaft.app.resident.dto.CreateResidentRequest;
import com.mannschaft.app.resident.dto.DwellingUnitResponse;
import com.mannschaft.app.resident.dto.ResidentResponse;
import com.mannschaft.app.resident.dto.UpdateResidentRequest;
import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import com.mannschaft.app.resident.entity.ResidentRegistryEntity;
import com.mannschaft.app.resident.mapper.ResidentMapper;
import com.mannschaft.app.resident.repository.DwellingUnitRepository;
import com.mannschaft.app.resident.repository.ResidentRegistryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 居住者管理サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResidentRegistryService {

    private final ResidentRegistryRepository residentRepository;
    private final DwellingUnitRepository dwellingUnitRepository;
    private final ResidentMapper residentMapper;
    private final EncryptionService encryptionService;

    /**
     * 居室の居住者一覧を取得する。
     */
    public List<ResidentResponse> listByUnit(Long unitId) {
        List<ResidentRegistryEntity> entities =
                residentRepository.findByDwellingUnitIdOrderByIsPrimaryDescMoveInDateAsc(unitId);
        return residentMapper.toResidentResponseList(entities);
    }

    /**
     * 居住者を登録する。
     */
    @Transactional
    public ResidentResponse create(Long unitId, CreateResidentRequest request) {
        DwellingUnitEntity unit = dwellingUnitRepository.findById(unitId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));

        ResidentRegistryEntity entity = ResidentRegistryEntity.builder()
                .dwellingUnitId(unitId)
                .userId(request.getUserId())
                .residentType(request.getResidentType())
                .lastName(request.getLastName())
                .firstName(request.getFirstName())
                .lastNameKana(request.getLastNameKana())
                .firstNameKana(request.getFirstNameKana())
                .phone(request.getPhone())
                .email(request.getEmail())
                .emergencyContact(request.getEmergencyContact())
                .lastNameHash(encryptionService.hmac(request.getLastName()))
                .firstNameHash(encryptionService.hmac(request.getFirstName()))
                .moveInDate(request.getMoveInDate())
                .ownershipRatio(request.getOwnershipRatio())
                .isPrimary(request.getIsPrimary() != null ? request.getIsPrimary() : false)
                .notes(request.getNotes())
                .build();

        ResidentRegistryEntity saved = residentRepository.save(entity);
        unit.incrementResidentCount();
        dwellingUnitRepository.save(unit);

        log.info("居住者登録: unitId={}, residentId={}", unitId, saved.getId());
        return residentMapper.toResidentResponse(saved);
    }

    /**
     * 居住者情報を更新する。
     */
    @Transactional
    public ResidentResponse update(Long id, UpdateResidentRequest request) {
        ResidentRegistryEntity entity = findOrThrow(id);
        entity.update(
                request.getResidentType(), request.getLastName(), request.getFirstName(),
                request.getLastNameKana(), request.getFirstNameKana(),
                request.getPhone(), request.getEmail(), request.getEmergencyContact(),
                request.getMoveInDate(), request.getOwnershipRatio(),
                request.getIsPrimary() != null ? request.getIsPrimary() : false,
                request.getNotes());
        entity.updateHashes(
                encryptionService.hmac(request.getLastName()),
                encryptionService.hmac(request.getFirstName()));
        ResidentRegistryEntity saved = residentRepository.save(entity);
        log.info("居住者更新: residentId={}", id);
        return residentMapper.toResidentResponse(saved);
    }

    /**
     * 居住者を論理削除する。
     */
    @Transactional
    public void delete(Long id) {
        ResidentRegistryEntity entity = findOrThrow(id);
        entity.softDelete();
        residentRepository.save(entity);

        DwellingUnitEntity unit = dwellingUnitRepository.findById(entity.getDwellingUnitId())
                .orElse(null);
        if (unit != null) {
            unit.decrementResidentCount();
            dwellingUnitRepository.save(unit);
        }

        log.info("居住者削除: residentId={}", id);
    }

    /**
     * 居住者を確認済みにする。
     */
    @Transactional
    public ResidentResponse verify(Long id, Long verifierId) {
        ResidentRegistryEntity entity = findOrThrow(id);
        if (entity.getIsVerified()) {
            throw new BusinessException(ResidentErrorCode.ALREADY_VERIFIED);
        }
        entity.verify(verifierId);
        ResidentRegistryEntity saved = residentRepository.save(entity);
        log.info("居住者確認: residentId={}, verifiedBy={}", id, verifierId);
        return residentMapper.toResidentResponse(saved);
    }

    /**
     * 退去処理を行う。
     */
    @Transactional
    public ResidentResponse moveOut(Long id, LocalDate moveOutDate) {
        ResidentRegistryEntity entity = findOrThrow(id);
        if (entity.getMoveOutDate() != null) {
            throw new BusinessException(ResidentErrorCode.ALREADY_MOVED_OUT);
        }
        entity.moveOut(moveOutDate != null ? moveOutDate : LocalDate.now());
        ResidentRegistryEntity saved = residentRepository.save(entity);

        DwellingUnitEntity unit = dwellingUnitRepository.findById(entity.getDwellingUnitId())
                .orElse(null);
        if (unit != null) {
            unit.decrementResidentCount();
            dwellingUnitRepository.save(unit);
        }

        log.info("退去処理: residentId={}", id);
        return residentMapper.toResidentResponse(saved);
    }

    /**
     * ユーザーの自室情報を取得する。
     */
    public DwellingUnitResponse getMyUnit(Long userId) {
        ResidentRegistryEntity resident = residentRepository.findActiveByUserId(userId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.MY_UNIT_NOT_FOUND));
        DwellingUnitEntity unit = dwellingUnitRepository.findById(resident.getDwellingUnitId())
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));
        return residentMapper.toDwellingUnitResponse(unit);
    }

    /**
     * ユーザーの居住者情報を取得する。
     */
    public ResidentResponse getMyResidentInfo(Long userId) {
        ResidentRegistryEntity entity = residentRepository.findActiveByUserId(userId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.RESIDENT_NOT_FOUND));
        return residentMapper.toResidentResponse(entity);
    }

    private ResidentRegistryEntity findOrThrow(Long id) {
        return residentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.RESIDENT_NOT_FOUND));
    }
}
