package com.mannschaft.app.resident.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.resident.ResidentErrorCode;
import com.mannschaft.app.resident.dto.ResidentDocumentResponse;
import com.mannschaft.app.resident.dto.UploadDocumentRequest;
import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import com.mannschaft.app.resident.entity.ResidentDocumentEntity;
import com.mannschaft.app.resident.entity.ResidentRegistryEntity;
import com.mannschaft.app.resident.mapper.ResidentMapper;
import com.mannschaft.app.resident.repository.DwellingUnitRepository;
import com.mannschaft.app.resident.repository.ResidentDocumentRepository;
import com.mannschaft.app.resident.repository.ResidentRegistryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 居住者書類サービス（本人確認書類。最機密PII）。
 *
 * <p>認可根治戦役 Wave2: 全メソッドで居住者→居室を辿り、entity由来のスコープ
 * （{@link DwellingUnitEntity#resolveScopeId()}）で認可する（path/request の scopeId は信用しない＝BOLA防止）。
 * 閲覧（listByResident）は {@code checkMembership}、追加・削除（upload/delete）は
 * {@code checkAdminOrAbove}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResidentDocumentService {

    private final ResidentDocumentRepository documentRepository;
    private final ResidentRegistryRepository residentRepository;
    private final DwellingUnitRepository dwellingUnitRepository;
    private final ResidentMapper residentMapper;
    private final AccessControlService accessControlService;

    /**
     * 書類一覧を取得する。
     * 認可: 居住者が実在するスコープ（entity由来）のメンバーのみ閲覧可能。
     */
    public List<ResidentDocumentResponse> listByResident(Long actorUserId, Long residentId) {
        DwellingUnitEntity unit = findUnitForResident(residentId);
        accessControlService.checkMembership(actorUserId, unit.resolveScopeId(), unit.getScopeType());

        List<ResidentDocumentEntity> entities = documentRepository.findByResidentIdOrderByCreatedAtDesc(residentId);
        return residentMapper.toDocumentResponseList(entities);
    }

    /**
     * 書類をアップロードする。
     * 認可: 居住者が実在するスコープ（entity由来）の ADMIN/DEPUTY_ADMIN のみ追加可能。
     * uploaderId は操作者本人であり、そのまま認可チェックのactorとして使う。
     */
    @Transactional
    public ResidentDocumentResponse upload(Long residentId, Long uploaderId, UploadDocumentRequest request) {
        DwellingUnitEntity unit = findUnitForResident(residentId);
        accessControlService.checkAdminOrAbove(uploaderId, unit.resolveScopeId(), unit.getScopeType());

        ResidentDocumentEntity entity = ResidentDocumentEntity.builder()
                .residentId(residentId)
                .documentType(request.getDocumentType())
                .fileName(request.getFileName())
                .s3Key(request.getS3Key())
                .fileSize(request.getFileSize())
                .contentType(request.getContentType())
                .uploadedBy(uploaderId)
                .build();
        ResidentDocumentEntity saved = documentRepository.save(entity);
        log.info("書類アップロード: residentId={}, docId={}", residentId, saved.getId());
        return residentMapper.toDocumentResponse(saved);
    }

    /**
     * 書類を削除する（物理削除）。
     * 認可: 居住者が実在するスコープ（entity由来）の ADMIN/DEPUTY_ADMIN のみ削除可能。
     */
    @Transactional
    public void delete(Long actorUserId, Long residentId, Long docId) {
        DwellingUnitEntity unit = findUnitForResident(residentId);
        accessControlService.checkAdminOrAbove(actorUserId, unit.resolveScopeId(), unit.getScopeType());

        ResidentDocumentEntity entity = documentRepository.findById(docId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DOCUMENT_NOT_FOUND));
        if (!entity.getResidentId().equals(residentId)) {
            throw new BusinessException(ResidentErrorCode.DOCUMENT_NOT_FOUND);
        }
        documentRepository.delete(entity);
        log.info("書類削除: residentId={}, docId={}", residentId, docId);
    }

    /**
     * 居住者ID から所属居室を fetch する（存在検証も兼ねる）。
     */
    private DwellingUnitEntity findUnitForResident(Long residentId) {
        ResidentRegistryEntity resident = residentRepository.findById(residentId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.RESIDENT_NOT_FOUND));
        return dwellingUnitRepository.findById(resident.getDwellingUnitId())
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND));
    }
}
