package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.FileSharingMapper;
import com.mannschaft.app.filesharing.dto.AccessLinkRequest;
import com.mannschaft.app.filesharing.dto.CreateLinkRequest;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.dto.LinkResponse;
import com.mannschaft.app.filesharing.entity.SharedFileLinkEntity;
import com.mannschaft.app.filesharing.repository.SharedFileLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * ファイル共有リンクサービス。外部共有用のトークンベースリンクを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SharedFileLinkService {

    private final SharedFileLinkRepository linkRepository;
    private final SharedFileService fileService;
    private final FileSharingMapper fileSharingMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * ファイルの共有リンク一覧を取得する。
     *
     * @param fileId ファイルID
     * @return リンクレスポンスリスト
     */
    public List<LinkResponse> listLinks(Long fileId) {
        List<SharedFileLinkEntity> links = linkRepository.findByFileIdOrderByCreatedAtDesc(fileId);
        return fileSharingMapper.toLinkResponseList(links);
    }

    /**
     * 共有リンクを作成する。
     *
     * @param fileId  ファイルID
     * @param userId  作成者ユーザーID
     * @param request 作成リクエスト
     * @return 作成されたリンクレスポンス
     */
    @Transactional
    public LinkResponse createLink(Long fileId, Long userId, CreateLinkRequest request) {
        String passwordHash = null;
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            passwordHash = passwordEncoder.encode(request.getPassword());
        }

        SharedFileLinkEntity entity = SharedFileLinkEntity.builder()
                .fileId(fileId)
                .token(UUID.randomUUID().toString())
                .expiresAt(request.getExpiresAt())
                .passwordHash(passwordHash)
                .createdBy(userId)
                .build();

        SharedFileLinkEntity saved = linkRepository.save(entity);
        log.info("共有リンク作成: fileId={}, linkId={}", fileId, saved.getId());
        return fileSharingMapper.toLinkResponse(saved);
    }

    /**
     * 共有リンクを削除する。
     *
     * @param linkId リンクID
     */
    @Transactional
    public void deleteLink(Long linkId) {
        SharedFileLinkEntity entity = linkRepository.findById(linkId)
                .orElseThrow(() -> new BusinessException(FileSharingErrorCode.LINK_NOT_FOUND));
        linkRepository.delete(entity);
        log.info("共有リンク削除: linkId={}", linkId);
    }

    /**
     * トークンで共有リンクにアクセスし、ファイル情報を取得する。
     *
     * @param token   共有リンクトークン
     * @param request アクセスリクエスト（パスワード）
     * @return ファイルレスポンス
     */
    @Transactional
    public FileResponse accessLink(String token, AccessLinkRequest request) {
        SharedFileLinkEntity entity = linkRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(FileSharingErrorCode.LINK_NOT_FOUND));

        if (entity.isExpired()) {
            throw new BusinessException(FileSharingErrorCode.LINK_EXPIRED);
        }

        if (entity.getPasswordHash() != null) {
            String password = request != null ? request.getPassword() : null;
            if (password == null || !passwordEncoder.matches(password, entity.getPasswordHash())) {
                throw new BusinessException(FileSharingErrorCode.LINK_PASSWORD_INVALID);
            }
        }

        entity.recordAccess();
        linkRepository.save(entity);

        return fileService.getFile(entity.getFileId());
    }
}
