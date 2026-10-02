package com.mannschaft.app.resident.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.resident.ResidentErrorCode;
import com.mannschaft.app.resident.dto.CreateResidentRequest;
import com.mannschaft.app.resident.dto.DwellingUnitResponse;
import com.mannschaft.app.resident.dto.ResidentResponse;
import com.mannschaft.app.resident.dto.UpdateResidentRequest;
import com.mannschaft.app.resident.entity.DeathStatus;
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
    private final AccessControlService accessControlService;

    /**
     * 居室の居住者一覧を取得する。
     * 認可: 居室が実在するスコープ（entity由来）のメンバーのみ閲覧可能。
     */
    public List<ResidentResponse> listByUnit(Long actorUserId, Long unitId) {
        DwellingUnitEntity unit = dwellingUnitRepository.findById(unitId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));
        accessControlService.checkMembership(actorUserId, unit.resolveScopeId(), unit.getScopeType());

        List<ResidentRegistryEntity> entities =
                residentRepository.findByDwellingUnitIdOrderByIsPrimaryDescMoveInDateAsc(unitId);
        return residentMapper.toResidentResponseList(entities);
    }

    /**
     * 居住者を登録する。
     * 認可: 登録先居室が実在するスコープ（entity由来）の ADMIN/DEPUTY_ADMIN のみ登録可能。
     */
    @Transactional
    public ResidentResponse create(Long actorUserId, Long unitId, CreateResidentRequest request) {
        DwellingUnitEntity unit = dwellingUnitRepository.findById(unitId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));
        accessControlService.checkAdminOrAbove(actorUserId, unit.resolveScopeId(), unit.getScopeType());

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
     * 認可: 居住者が実在するスコープ（entity由来）の ADMIN/DEPUTY_ADMIN のみ更新可能。
     */
    @Transactional
    public ResidentResponse update(Long actorUserId, Long id, UpdateResidentRequest request) {
        ResidentRegistryEntity entity = findOrThrow(id);
        checkAdminForResident(actorUserId, entity);
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
     * 認可: 居住者が実在するスコープ（entity由来）の ADMIN/DEPUTY_ADMIN のみ削除可能。
     */
    @Transactional
    public void delete(Long actorUserId, Long id) {
        ResidentRegistryEntity entity = findOrThrow(id);
        checkAdminForResident(actorUserId, entity);
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
     * 認可: 居住者が実在するスコープ（entity由来）の ADMIN/DEPUTY_ADMIN のみ確認可能。
     * verifierId は操作者本人であり、そのまま認可チェックのactorとして使う。
     */
    @Transactional
    public ResidentResponse verify(Long id, Long verifierId) {
        ResidentRegistryEntity entity = findOrThrow(id);
        checkAdminForResident(verifierId, entity);
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
     * 認可: 居住者が実在するスコープ（entity由来）の ADMIN/DEPUTY_ADMIN のみ実行可能。
     */
    @Transactional
    public ResidentResponse moveOut(Long actorUserId, Long id, LocalDate moveOutDate) {
        ResidentRegistryEntity entity = findOrThrow(id);
        checkAdminForResident(actorUserId, entity);
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

    /**
     * 指定ユーザーが指定組織の現役居住者（active、moveOutDate 無し）であるかを判定する。
     *
     * <p>他ドメイン（例: residencestatus）からの越境呼び出しの受け口。モジュラーモノリスの
     * 「ドメイン間はID参照＋Service経由のみ」原則に従い、{@link ResidentRegistryEntity} を
     * 境界の外へ返さず boolean のみを返す（呼び出し側は resident ドメインの Repository/Entity へ
     * 直接依存しない）。</p>
     *
     * @param userId         判定対象ユーザー ID
     * @param organizationId 組織 ID
     * @return 当該組織の現役居住者であれば true
     */
    public boolean isActiveResidentOfOrganization(Long userId, Long organizationId) {
        return residentRepository.findActiveByUserIdAndOrganizationId(userId, organizationId).isPresent();
    }

    /**
     * 指定の居住者台帳 ID の所有者（userId）が、指定ユーザーと一致するかを判定する。
     *
     * <p>他ドメインからのBOLA対策（未検証の residentRegistryId が永続記録へ到達する事故を防ぐ）
     * の受け口。台帳が存在しない場合は false を返す（存在秘匿は呼び出し側の責務）。</p>
     *
     * @param residentRegistryId 居住者台帳 ID（呼び出し元のリクエストボディ等、信頼できない入力）
     * @param userId             所有者として期待するユーザー ID（認証主体）
     * @return 台帳が実在し、かつその所有者が userId と一致すれば true
     */
    public boolean isResidentRegistryOwnedBy(Long residentRegistryId, Long userId) {
        return residentRepository.findById(residentRegistryId)
                .map(ResidentRegistryEntity::getUserId)
                .map(userId::equals)
                .orElse(false);
    }

    /**
     * D+120 エスカレーション到達時の自動起票: death_status を ALIVE → SUSPECTED に変更する。
     *
     * <p>既に SUSPECTED / CONFIRMED の場合は何もしない（冪等）。
     * CANCELLED_FALSE_ALARM の場合も上書きしない（誤確認後の再確認は手動操作で行う）。
     *
     * <p>呼び出し元: {@code DelinquencyEscalationService#advanceStage()} (F09.15 S5-C)
     * TODO: successionドメイン → residentドメインのクロスドメイン呼び出し。将来は
     *       ResidentDeathSuspectedEvent を発火してresidentドメインがサブスクライブする形に分離予定。
     *
     * @param residentRegistryId 居住者台帳 ID
     */
    @Transactional
    public void markDeathSuspected(Long residentRegistryId) {
        ResidentRegistryEntity entity = residentRepository.findById(residentRegistryId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.RESIDENT_NOT_FOUND));

        if (entity.getDeathStatus() != DeathStatus.ALIVE) {
            log.info("death_status は既に {} のため自動起票をスキップ: residentRegistryId={}",
                    entity.getDeathStatus(), residentRegistryId);
            return;
        }

        // システムによる自動変更のため changedBy は null（バッチ操作）
        entity.updateDeathStatus(DeathStatus.SUSPECTED, null);
        residentRepository.save(entity);
        log.info("死亡疑い自動起票: residentRegistryId={}", residentRegistryId);
    }

    private ResidentRegistryEntity findOrThrow(Long id) {
        return residentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.RESIDENT_NOT_FOUND));
    }

    /**
     * 居住者が所属する居室を fetch し、その居室が実在するスコープ（entity由来）の
     * ADMIN/DEPUTY_ADMIN であることを検証する（BOLA 防止: path/request の scopeId は使わない）。
     */
    private void checkAdminForResident(Long actorUserId, ResidentRegistryEntity entity) {
        DwellingUnitEntity unit = dwellingUnitRepository.findById(entity.getDwellingUnitId())
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));
        accessControlService.checkAdminOrAbove(actorUserId, unit.resolveScopeId(), unit.getScopeType());
    }
}
