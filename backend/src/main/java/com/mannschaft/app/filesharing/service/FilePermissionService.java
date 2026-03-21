package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.FileSharingMapper;
import com.mannschaft.app.filesharing.PermissionTargetType;
import com.mannschaft.app.filesharing.PermissionType;
import com.mannschaft.app.filesharing.dto.CreatePermissionRequest;
import com.mannschaft.app.filesharing.dto.PermissionResponse;
import com.mannschaft.app.filesharing.entity.FilePermissionEntity;
import com.mannschaft.app.filesharing.repository.FilePermissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ファイル権限サービス。ファイル・フォルダに対するアクセス権限管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FilePermissionService {

    private final FilePermissionRepository permissionRepository;
    private final FileSharingMapper fileSharingMapper;

    /**
     * 対象の権限一覧を取得する。
     *
     * @param targetType 対象種別（FILE / FOLDER）
     * @param targetId   対象ID
     * @return 権限レスポンスリスト
     */
    public List<PermissionResponse> listPermissions(String targetType, Long targetId) {
        List<FilePermissionEntity> permissions = permissionRepository.findByTargetTypeAndTargetId(targetType, targetId);
        return fileSharingMapper.toPermissionResponseList(permissions);
    }

    /**
     * 権限を付与する。
     *
     * @param request 権限作成リクエスト
     * @return 作成された権限レスポンス
     */
    @Transactional
    public PermissionResponse createPermission(CreatePermissionRequest request) {
        FilePermissionEntity entity = FilePermissionEntity.builder()
                .targetType(request.getTargetType())
                .targetId(request.getTargetId())
                .permissionType(PermissionType.valueOf(request.getPermissionType()))
                .permissionTargetType(PermissionTargetType.valueOf(request.getPermissionTargetType()))
                .permissionTargetId(request.getPermissionTargetId())
                .build();

        FilePermissionEntity saved = permissionRepository.save(entity);
        log.info("権限付与: targetType={}, targetId={}, permissionId={}", request.getTargetType(), request.getTargetId(), saved.getId());
        return fileSharingMapper.toPermissionResponse(saved);
    }

    /**
     * 権限を削除する。
     *
     * @param permissionId 権限ID
     */
    @Transactional
    public void deletePermission(Long permissionId) {
        FilePermissionEntity entity = permissionRepository.findById(permissionId)
                .orElseThrow(() -> new BusinessException(FileSharingErrorCode.PERMISSION_NOT_FOUND));
        permissionRepository.delete(entity);
        log.info("権限削除: permissionId={}", permissionId);
    }
}
