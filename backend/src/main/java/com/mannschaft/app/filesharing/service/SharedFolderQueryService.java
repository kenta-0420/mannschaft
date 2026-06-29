package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.filesharing.dto.CreateFolderRequest;
import com.mannschaft.app.filesharing.dto.FolderDetailResponse;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F05.5 フォルダ詳細／一覧／作成のクエリ・コマンドサービス（{@code /api/v1/files/folders}）。
 *
 * <p>本サービスは {@link SharedFolderService#getFolder} の<b>認可素通り問題</b>を回避するために新設した。
 * 既存 {@code getFolder} は {@link FolderScopeAccessGuard} を呼ぶのみで、大会以外（TEAM/ORG/PERSONAL）の
 * スコープでは認可が一切効かず、フォルダ ID を渡すだけで他チーム・他人のフォルダ内容が取得できる
 * 情報漏洩があった。本サービスは folderId からスコープを解決し、スコープ別に自前で認可を当てる。</p>
 *
 * <p>（red フェーズの骨組み。実装は green フェーズで埋める。）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SharedFolderQueryService {

    private final SharedFolderRepository folderRepository;
    private final SharedFileRepository fileRepository;
    private final FolderScopeAccessGuard folderScopeAccessGuard;
    private final AccessControlService accessControlService;
    private final NameResolverService nameResolverService;

    /** パンくず構築時の祖先探索の深さ上限（循環・異常データ防御）。 */
    private static final int MAX_BREADCRUMB_DEPTH = 50;

    /**
     * フォルダ詳細を取得する（スコープ別認可つき）。
     *
     * @param folderId フォルダ ID
     * @param userId   操作ユーザー ID
     * @return フォルダ詳細レスポンス
     */
    public FolderDetailResponse getFolderDetail(Long folderId, Long userId) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * スコープのフォルダ一覧を取得する（ルート or サブ）。
     *
     * @param scopeType スコープ種別（TEAM / ORGANIZATION / PERSONAL）
     * @param scopeId   スコープ ID（teamId / organizationId / userId の文字列）
     * @param parentId  親フォルダ ID（null ならルート）
     * @param userId    操作ユーザー ID
     * @return フォルダ要約リスト
     */
    public List<FolderDetailResponse.FolderSummary> listFolders(
            String scopeType, String scopeId, Long parentId, Long userId) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * フォルダを作成する（スコープ別認可つき）。
     *
     * @param request 作成リクエスト（scopeType / scopeId / parentId / name）
     * @param scopeId スコープ ID（teamId / organizationId、PERSONAL では無視）
     * @param userId  操作ユーザー ID
     * @return 作成されたフォルダ要約
     */
    @Transactional
    public FolderDetailResponse.FolderSummary createFolder(
            CreateFolderRequest request, String scopeId, Long userId) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
