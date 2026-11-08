package com.mannschaft.app.scopefolder.service;

import com.mannschaft.app.scopefolder.dto.InviteFolderCheck;
import com.mannschaft.app.scopefolder.entity.AssignedVia;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 招待承諾後のマイスコープフォルダ配置窓口（F15.3 §5.1.1・CMP-260826-1457）。
 *
 * <p>role ドメインの承諾手順（{@code InviteAcceptanceFacade}）が、scopefolder の Entity・Repository を
 * 触らずにフォルダ配置を行うための窓口。スコープ種別は文字列（TEAM / ORGANIZATION）で受ける。</p>
 *
 * <p>本クラスは {@code @Transactional} を持たない。{@link MyScopeFolderService} の各メソッドが
 * それぞれ自前のトランザクションで動く（外側にトランザクションがあれば参加する）ので、配置の失敗は
 * 参加（ロール付与）のトランザクションを巻き込まない。Bean（プロキシ）経由で呼ぶこと。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InviteFolderPlacementService {

    private final MyScopeFolderService myScopeFolderService;

    /**
     * 配置先フォルダを事前確認する。例外ではなく結果で返す。
     *
     * @param userId    承諾するユーザー
     * @param folderId  配置先フォルダ
     * @param scopeType 招待先のスコープ種別（TEAM / ORGANIZATION）
     */
    public InviteFolderCheck checkInviteFolder(Long userId, Long folderId, String scopeType) {
        return myScopeFolderService.inspectInviteFolder(userId, folderId, toFolderScope(scopeType));
    }

    /**
     * 参加後のフォルダへ配置する。{@code folderId} 指定時はそのフォルダへ (INVITE)、
     * 未指定時は「未分類」フォルダへ (DEFAULT)。
     */
    public void place(Long userId, Long folderId, String scopeType, Long scopeId) {
        ScopeType folderScope = toFolderScope(scopeType);
        if (folderId != null) {
            myScopeFolderService.addItemWithAssignedVia(userId, folderId, scopeId, AssignedVia.INVITE);
            log.info("招待時フォルダ配置(INVITE): userId={}, folderId={}, scopeId={}", userId, folderId, scopeId);
        } else {
            MyScopeFolderEntity defaultFolder = myScopeFolderService.findOrCreateDefaultInternal(userId, folderScope);
            myScopeFolderService.addItemWithAssignedVia(userId, defaultFolder.getId(), scopeId, AssignedVia.DEFAULT);
            log.info("招待時フォルダ配置(DEFAULT): userId={}, defaultFolderId={}, scopeId={}",
                    userId, defaultFolder.getId(), scopeId);
        }
    }

    private static ScopeType toFolderScope(String scopeType) {
        return "TEAM".equals(scopeType) ? ScopeType.TEAM : ScopeType.ORGANIZATION;
    }
}
