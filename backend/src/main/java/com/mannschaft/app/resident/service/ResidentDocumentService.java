package com.mannschaft.app.resident.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.resident.ResidentErrorCode;
import com.mannschaft.app.resident.dto.ResidentDocumentResponse;
import com.mannschaft.app.resident.dto.UploadDocumentRequest;
import com.mannschaft.app.resident.entity.ResidentDocumentEntity;
import com.mannschaft.app.resident.mapper.ResidentMapper;
import com.mannschaft.app.resident.repository.ResidentDocumentRepository;
import com.mannschaft.app.resident.repository.ResidentRegistryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 居住者書類サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResidentDocumentService {

    private final ResidentDocumentRepository documentRepository;
    private final ResidentRegistryRepository residentRepository;
    private final ResidentMapper residentMapper;

    /**
     * 書類一覧を取得する。
     */
    public List<ResidentDocumentResponse> listByResident(Long residentId) {
        validateResidentExists(residentId);
        List<ResidentDocumentEntity> entities = documentRepository.findByResidentIdOrderByCreatedAtDesc(residentId);
        return residentMapper.toDocumentResponseList(entities);
    }

    /**
     * 書類をアップロードする。
     */
    @Transactional
    public ResidentDocumentResponse upload(Long residentId, Long uploaderId, UploadDocumentRequest request) {
        validateResidentExists(residentId);
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
     * 書類を削除する。
     */
    @Transactional
    public void delete(Long residentId, Long docId) {
        validateResidentExists(residentId);
        ResidentDocumentEntity entity = documentRepository.findById(docId)
                .orElseThrow(() -> new BusinessException(ResidentErrorCode.DOCUMENT_NOT_FOUND));
        if (!entity.getResidentId().equals(residentId)) {
            throw new BusinessException(ResidentErrorCode.DOCUMENT_NOT_FOUND);
        }
        documentRepository.delete(entity);
        log.info("書類削除: residentId={}, docId={}", residentId, docId);
    }

    private void validateResidentExists(Long residentId) {
        if (!residentRepository.existsById(residentId)) {
            throw new BusinessException(ResidentErrorCode.RESIDENT_NOT_FOUND);
        }
    }
}
