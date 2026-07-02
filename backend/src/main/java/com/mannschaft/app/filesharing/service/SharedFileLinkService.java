package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.FileSharingMapper;
import com.mannschaft.app.filesharing.dto.AccessLinkRequest;
import com.mannschaft.app.filesharing.dto.CreateLinkRequest;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.dto.LinkResponse;
import com.mannschaft.app.filesharing.dto.SharedFileDownloadUrlResponse;
import com.mannschaft.app.filesharing.entity.SharedFileLinkEntity;
import com.mannschaft.app.filesharing.repository.SharedFileLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    /** PR-D マスター確定仕様: 公開リンクの有効期限は最大30日先まで（無期限リンク不可）。 */
    private static final int MAX_LINK_EXPIRY_DAYS = 30;

    private final SharedFileLinkRepository linkRepository;
    private final SharedFileService fileService;
    private final FileSharingMapper fileSharingMapper;
    private final PasswordEncoder passwordEncoder;
    /** F08.7.1 / 04: 大会フォルダ配下の共有リンク管理に対する横断認可ゲート（大会以外は no-op）。 */
    private final FolderScopeAccessGuard folderScopeAccessGuard;
    /**
     * PR-D: 公開リンク管理（発行 / 一覧 / 削除）の発行認可（ADMIN/DEPUTY 限定）と、
     * 公開リンク DL の C: download_disabled 貫通防御を担う。
     */
    private final SharedFolderQueryService folderQueryService;

    /**
     * ファイルの共有リンク一覧を取得する。
     *
     * @param fileId ファイルID
     * @return リンクレスポンスリスト
     */
    public List<LinkResponse> listLinks(Long fileId) {
        // PR-D 発行認可是正: 公開リンクの一覧はスコープの管理者（ADMIN/DEPUTY・PERSONAL は所有者）限定。
        // 従来は大会フォルダ以外で認可 no-op（素通り）だった穴を是正する（PR-A と同型）。
        folderQueryService.authorizeLinkManageByFileId(fileId, SecurityUtils.getCurrentUserIdOrNull());
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
        // PR-D 発行認可是正: 公開リンクを発行できるのはスコープの管理者（ADMIN/DEPUTY・PERSONAL は所有者）のみ。
        // 未認証・非会員にファイルを開く capability を配る強力な操作のため、一般 MEMBER は 403。
        folderQueryService.authorizeLinkManageByFileId(fileId, userId);

        // PR-D マスター確定仕様: 有効期限は必須・最大30日先まで（無期限リンク不可）。
        LocalDateTime expiresAt = request.getExpiresAt();
        validateExpiry(expiresAt);

        String passwordHash = null;
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            passwordHash = passwordEncoder.encode(request.getPassword());
        }
        // PR-D マスター確定仕様: download_allowed 既定 false（閲覧のみ）。明示 true のリンクのみ DL 許可。
        boolean downloadAllowed = Boolean.TRUE.equals(request.getDownloadAllowed());

        SharedFileLinkEntity entity = SharedFileLinkEntity.builder()
                .fileId(fileId)
                .token(UUID.randomUUID().toString())
                .expiresAt(expiresAt)
                .passwordHash(passwordHash)
                .downloadAllowed(downloadAllowed)
                .createdBy(userId)
                .build();

        SharedFileLinkEntity saved = linkRepository.save(entity);
        log.info("共有リンク作成: fileId={}, linkId={}, downloadAllowed={}", fileId, saved.getId(), downloadAllowed);
        return fileSharingMapper.toLinkResponse(saved);
    }

    /**
     * PR-D: 発行時の有効期限を検証する（必須・過去不可・最大30日先）。違反は {@code LINK_EXPIRY_INVALID}（400）。
     */
    private void validateExpiry(LocalDateTime expiresAt) {
        LocalDateTime now = LocalDateTime.now();
        if (expiresAt == null
                || expiresAt.isBefore(now)
                || expiresAt.isAfter(now.plusDays(MAX_LINK_EXPIRY_DAYS))) {
            throw new BusinessException(FileSharingErrorCode.LINK_EXPIRY_INVALID);
        }
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
        // PR-D 発行認可是正: 公開リンク削除もスコープの管理者（ADMIN/DEPUTY・PERSONAL は所有者）限定。
        folderQueryService.authorizeLinkManageByFileId(entity.getFileId(), SecurityUtils.getCurrentUserIdOrNull());
        linkRepository.delete(entity);
        log.info("共有リンク削除: linkId={}", linkId);
    }

    /**
     * トークンで共有リンクにアクセスし、ファイル情報を取得する。
     *
     * @param token   共有リンクトークン
     * @param request アクセスリクエスト（パスワード）
     * @return ファイルレスポンス
     * @deprecated PR-D で未認証公開経路 {@link #accessLinkPublic} に一本化した。本メソッドは互換のため
     *     公開経路へ委譲するだけの薄いラッパーで、旧 {@code /api/v1/shared-links/{token}/access}
     *     （permitAll 未設定で実質認証必須）からのみ参照される。新規呼び出しは {@link #accessLinkPublic} を使うこと。
     */
    @Deprecated
    @Transactional
    public FileResponse accessLink(String token, AccessLinkRequest request) {
        return accessLinkPublic(token, request);
    }

    /**
     * PR-D: <b>未認証・非会員</b>がトークンで公開リンクにアクセスし、ファイルメタを取得する。
     *
     * <p>検証順（存在秘匿を保つ）: token 実在（404 LINK_NOT_FOUND）→ is_active（410 LINK_INACTIVE）
     * → 期限（410 LINK_EXPIRED）→ password（403 LINK_PASSWORD_INVALID）。フォルダスコープ認可
     * （membership / role）は<b>通さない</b>（トークンが capability・D の主旨）。アクセス数をインクリメントする。</p>
     *
     * @param token   公開リンクトークン
     * @param request アクセスリクエスト（パスワード・任意）
     * @return ファイルレスポンス（メタ）
     */
    @Transactional
    public FileResponse accessLinkPublic(String token, AccessLinkRequest request) {
        SharedFileLinkEntity entity = validateLinkForAccess(token, request);
        entity.recordAccess();
        linkRepository.save(entity);
        // 共有リンクはトークン自体が capability。フォルダスコープ認可は通さない専用メソッドを使う。
        return fileService.getFileForSharedLink(entity.getFileId());
    }

    /**
     * PR-D: <b>未認証・非会員</b>が公開リンク経由で DL URL を発行する。
     *
     * <p>{@link #validateLinkForAccess}（token/is_active/期限/password）を通したうえで、
     * リンクの {@code download_allowed}（false→403 LINK_DOWNLOAD_NOT_ALLOWED）を確認し、さらに
     * ファイル/フォルダの C: {@code download_disabled}（true→403 DOWNLOAD_DISABLED・C 優先）を
     * {@link SharedFileService#presignDownloadForSharedLink} 内で貫通防御する。
     * すなわち DL は <b>download_allowed かつ NOT download_disabled</b> の AND で許可される。
     * アクセス数をインクリメントする。</p>
     *
     * @param token   公開リンクトークン
     * @param request アクセスリクエスト（パスワード・任意）
     * @return ダウンロード URL レスポンス
     */
    @Transactional
    public SharedFileDownloadUrlResponse presignDownloadForLink(String token, AccessLinkRequest request) {
        SharedFileLinkEntity entity = validateLinkForAccess(token, request);
        // B': このリンクで DL が許可されているか（既定 false=閲覧のみ）。
        if (!entity.isDownloadAllowed()) {
            throw new BusinessException(FileSharingErrorCode.LINK_DOWNLOAD_NOT_ALLOWED);
        }
        entity.recordAccess();
        linkRepository.save(entity);
        // C: download_disabled は presignDownloadForSharedLink 内で必ず評価（C 優先の AND）。
        return fileService.presignDownloadForSharedLink(entity.getFileId());
    }

    /**
     * PR-D: 公開リンクアクセスの共通検証（token 実在 → is_active → 期限 → password）。
     *
     * <p>検証順は存在秘匿の一貫性を保つ: 不在トークンは 404、失効/期限切れは 410、パスワード不一致は 403。</p>
     *
     * @param token   公開リンクトークン
     * @param request アクセスリクエスト（パスワード・任意）
     * @return 検証を通過したリンクエンティティ
     */
    private SharedFileLinkEntity validateLinkForAccess(String token, AccessLinkRequest request) {
        SharedFileLinkEntity entity = linkRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(FileSharingErrorCode.LINK_NOT_FOUND));

        // 手動失効（is_active=false）→ 410 Gone。
        if (!entity.isActive()) {
            throw new BusinessException(FileSharingErrorCode.LINK_INACTIVE);
        }
        // 期限切れ → 410 Gone。
        if (entity.isExpired()) {
            throw new BusinessException(FileSharingErrorCode.LINK_EXPIRED);
        }
        // パスワード付きリンクは照合（未入力/不一致は 403）。
        if (entity.getPasswordHash() != null) {
            String password = request != null ? request.getPassword() : null;
            if (password == null || !passwordEncoder.matches(password, entity.getPasswordHash())) {
                throw new BusinessException(FileSharingErrorCode.LINK_PASSWORD_INVALID);
            }
        }
        return entity;
    }
}
