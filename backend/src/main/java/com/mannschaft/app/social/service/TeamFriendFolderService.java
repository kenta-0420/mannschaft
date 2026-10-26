package com.mannschaft.app.social.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.CreateFolderRequest;
import com.mannschaft.app.social.dto.TeamFriendFolderView;
import com.mannschaft.app.social.dto.UpdateFolderRequest;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.entity.TeamFriendFolderEntity;
import com.mannschaft.app.social.entity.TeamFriendFolderMemberEntity;
import com.mannschaft.app.social.repository.TeamFriendFolderMemberRepository;
import com.mannschaft.app.social.repository.TeamFriendFolderRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * フレンドフォルダサービス（F01.5 Phase 1）。
 *
 * <p>
 * 自チーム視点の非対称フォルダ（例: 「系列校」「練習試合候補」）の CRUD および
 * フォルダへのフレンドチーム所属管理を担う。
 * </p>
 *
 * <p>
 * 設計書: {@code docs/features/F01.5_team_friend_relationships.md} §5 / §6
 * </p>
 *
 * <p>
 * <b>権限</b>: 全操作 ADMIN または {@code MANAGE_FRIEND_TEAMS} 権限保持者。
 * </p>
 *
 * <p>
 * <b>上限</b>: 1 チームあたり最大 20 フォルダ（設計書 §4.2）。
 * </p>
 *
 * <p>
 * <b>トランザクション境界</b>: クラスレベル {@code readOnly = true} を既定とし、
 * 更新系メソッドに個別 {@link Transactional} を付与する。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamFriendFolderService {

    /** {@code MANAGE_FRIEND_TEAMS} 権限の論理名 */
    private static final String PERM_MANAGE_FRIEND_TEAMS = "MANAGE_FRIEND_TEAMS";

    /** スコープ識別子（チーム） */
    private static final String SCOPE_TEAM = "TEAM";

    /** 1 チームあたりのフォルダ上限 */
    private static final int MAX_FOLDERS_PER_TEAM = 20;

    private final TeamFriendFolderRepository folderRepository;
    private final TeamFriendFolderMemberRepository folderMemberRepository;
    private final TeamFriendRepository teamFriendRepository;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;

    // ═════════════════════════════════════════════════════════════
    // 1. フォルダ一覧取得
    // ═════════════════════════════════════════════════════════════

    /**
     * 指定チームの論理削除されていないフォルダ一覧を並び順で取得する。
     * 自チームメンバー（MEMBER 以上）であれば閲覧可能。
     *
     * @param teamId 自チーム ID
     * @param userId 閲覧者ユーザー ID
     * @return フォルダ View 一覧
     * @throws BusinessException 非メンバー時（403）
     */
    public List<TeamFriendFolderView> listFolders(Long teamId, Long userId) {
        accessControlService.checkMembership(userId, teamId, SCOPE_TEAM);

        List<TeamFriendFolderEntity> folders = folderRepository
                .findByOwnerTeamIdAndDeletedAtIsNullOrderByFolderOrder(teamId);

        return folders.stream()
                .map(this::toView)
                .toList();
    }

    // ═════════════════════════════════════════════════════════════
    // 2. フォルダ作成
    // ═════════════════════════════════════════════════════════════

    /**
     * フォルダを新規作成する。1 チームあたり最大 {@value #MAX_FOLDERS_PER_TEAM} 個まで。
     *
     * @param teamId  自チーム ID
     * @param request 作成リクエスト
     * @param userId  操作実行者のユーザー ID
     * @return 作成されたフォルダの View
     * @throws BusinessException 権限不足・上限超過時
     */
    @Transactional
    public TeamFriendFolderView createFolder(Long teamId, CreateFolderRequest request, Long userId) {
        requireManageFriendTeams(userId, teamId);

        // 上限チェック
        long currentCount = folderRepository.countByOwnerTeamIdAndDeletedAtIsNull(teamId);
        if (currentCount >= MAX_FOLDERS_PER_TEAM) {
            throw new BusinessException(SocialErrorCode.FRIEND_FOLDER_LIMIT_EXCEEDED);
        }

        TeamFriendFolderEntity.TeamFriendFolderEntityBuilder builder = TeamFriendFolderEntity.builder()
                .ownerTeamId(teamId)
                .name(request.getName())
                .description(request.getDescription())
                .isDefault(false)
                .folderOrder((int) currentCount);

        if (request.getColor() != null) {
            builder.color(request.getColor());
        }

        TeamFriendFolderEntity saved = folderRepository.save(builder.build());

        log.info("フレンドフォルダ作成: teamId={}, folderId={}, name={}, userId={}",
                teamId, saved.getId(), saved.getName(), userId);
        recordFolderAudit("FRIEND_FOLDER_CREATED", userId, teamId, saved.getId(),
                String.format(
                        "{\"folder_id\":%d,\"name\":\"%s\"}",
                        saved.getId(), escapeJson(saved.getName())));

        return toView(saved);
    }

    // ═════════════════════════════════════════════════════════════
    // 3. フォルダ更新
    // ═════════════════════════════════════════════════════════════

    /**
     * フォルダを更新する。IDOR 対策のため {@code owner_team_id} が自チームで
     * あることを必ず検証する。
     *
     * @param teamId   自チーム ID
     * @param folderId フォルダ ID
     * @param request  更新リクエスト
     * @param userId   操作実行者のユーザー ID
     * @return 更新後のフォルダ View
     * @throws BusinessException 権限不足・フォルダ不存在時
     */
    @Transactional
    public TeamFriendFolderView updateFolder(Long teamId, Long folderId,
                                             UpdateFolderRequest request, Long userId) {
        requireManageFriendTeams(userId, teamId);

        TeamFriendFolderEntity folder = folderRepository
                .findByIdAndOwnerTeamIdAndDeletedAtIsNull(folderId, teamId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.FRIEND_FOLDER_NOT_FOUND));

        folder.updateFolder(
                request.getName(),
                request.getDescription(),
                request.getColor(),
                request.getSortOrder());
        folderRepository.save(folder);

        log.info("フレンドフォルダ更新: teamId={}, folderId={}, userId={}",
                teamId, folderId, userId);
        recordFolderAudit("FRIEND_FOLDER_UPDATED", userId, teamId, folderId,
                String.format("{\"folder_id\":%d}", folderId));

        return toView(folder);
    }

    // ═════════════════════════════════════════════════════════════
    // 4. フォルダ削除（論理削除）
    // ═════════════════════════════════════════════════════════════

    /**
     * フォルダを論理削除する。関連する {@code team_friend_folder_members} は
     * 直接には削除せず、フォルダ側の {@code deleted_at} 設定のみで非表示化する。
     * 90 日経過後の物理削除時（運用バッチ）に FK CASCADE で中間レコードが削除される。
     *
     * @param teamId   自チーム ID
     * @param folderId フォルダ ID
     * @param userId   操作実行者のユーザー ID
     * @throws BusinessException 権限不足・フォルダ不存在時
     */
    @Transactional
    public void deleteFolder(Long teamId, Long folderId, Long userId) {
        requireManageFriendTeams(userId, teamId);

        TeamFriendFolderEntity folder = folderRepository
                .findByIdAndOwnerTeamIdAndDeletedAtIsNull(folderId, teamId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.FRIEND_FOLDER_NOT_FOUND));

        folder.softDelete();
        folderRepository.save(folder);

        log.info("フレンドフォルダ論理削除: teamId={}, folderId={}, userId={}",
                teamId, folderId, userId);
        recordFolderAudit("FRIEND_FOLDER_DELETED", userId, teamId, folderId,
                String.format("{\"folder_id\":%d}", folderId));
    }

    // ═════════════════════════════════════════════════════════════
    // 5. フォルダへのメンバー追加
    // ═════════════════════════════════════════════════════════════

    /**
     * フォルダにフレンドチームを追加する。
     *
     * <p>
     * 重複追加は {@code uq_tffm_folder_friend} UNIQUE 制約違反で検出し、
     * {@link DataIntegrityViolationException} を
     * {@link SocialErrorCode#FRIEND_FOLDER_MEMBER_ALREADY_EXISTS}（409）に変換する。
     * </p>
     *
     * @param teamId       自チーム ID
     * @param folderId     フォルダ ID
     * @param teamFriendId 追加対象のフレンド関係 ID
     * @param userId       操作実行者のユーザー ID
     * @throws BusinessException 権限不足・フォルダ/フレンド関係不存在・重複登録時
     */
    @Transactional
    public void addMemberToFolder(Long teamId, Long folderId, Long teamFriendId, Long userId) {
        requireManageFriendTeams(userId, teamId);

        // 1. フォルダ所有権確認（IDOR 対策）
        TeamFriendFolderEntity folder = folderRepository
                .findByIdAndOwnerTeamIdAndDeletedAtIsNull(folderId, teamId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.FRIEND_FOLDER_NOT_FOUND));

        // 2. フレンド関係が自チームに属するかチェック（IDOR 対策）
        TeamFriendEntity friend = teamFriendRepository.findById(teamFriendId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.FRIEND_RELATION_NOT_FOUND));
        if (!friend.getTeamAId().equals(teamId) && !friend.getTeamBId().equals(teamId)) {
            throw new BusinessException(SocialErrorCode.FRIEND_RELATION_NOT_FOUND);
        }

        // 3. INSERT（UNIQUE 違反 → 409）
        try {
            folderMemberRepository.save(TeamFriendFolderMemberEntity.builder()
                    .folderId(folder.getId())
                    .teamFriendId(teamFriendId)
                    .addedBy(userId)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(
                    SocialErrorCode.FRIEND_FOLDER_MEMBER_ALREADY_EXISTS, ex);
        }

        log.info("フォルダメンバー追加: teamId={}, folderId={}, teamFriendId={}, userId={}",
                teamId, folderId, teamFriendId, userId);
        recordFolderAudit("FRIEND_FOLDER_MEMBER_ADDED", userId, teamId, folderId,
                String.format(
                        "{\"folder_id\":%d,\"team_friend_id\":%d}",
                        folderId, teamFriendId));
    }

    // ═════════════════════════════════════════════════════════════
    // 6. フォルダからのメンバー削除
    // ═════════════════════════════════════════════════════════════

    /**
     * フォルダからフレンドチームを取り外す。
     *
     * @param teamId       自チーム ID
     * @param folderId     フォルダ ID
     * @param teamFriendId 対象のフレンド関係 ID
     * @param userId       操作実行者のユーザー ID
     * @throws BusinessException 権限不足・フォルダ不存在・未登録時
     */
    @Transactional
    public void removeMemberFromFolder(Long teamId, Long folderId, Long teamFriendId, Long userId) {
        requireManageFriendTeams(userId, teamId);

        // フォルダ所有権確認
        TeamFriendFolderEntity folder = folderRepository
                .findByIdAndOwnerTeamIdAndDeletedAtIsNull(folderId, teamId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.FRIEND_FOLDER_NOT_FOUND));

        if (!folderMemberRepository.existsByFolderIdAndTeamFriendId(folder.getId(), teamFriendId)) {
            throw new BusinessException(SocialErrorCode.FRIEND_FOLDER_MEMBER_NOT_FOUND);
        }

        folderMemberRepository.deleteByFolderIdAndTeamFriendId(folder.getId(), teamFriendId);

        log.info("フォルダメンバー削除: teamId={}, folderId={}, teamFriendId={}, userId={}",
                teamId, folderId, teamFriendId, userId);
        recordFolderAudit("FRIEND_FOLDER_MEMBER_REMOVED", userId, teamId, folderId,
                String.format(
                        "{\"folder_id\":%d,\"team_friend_id\":%d}",
                        folderId, teamFriendId));
    }

    // ═════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════

    /**
     * {@link TeamFriendFolderEntity} を {@link TeamFriendFolderView} に変換する。
     *
     * @param entity フォルダエンティティ
     * @return View
     */
    private TeamFriendFolderView toView(TeamFriendFolderEntity entity) {
        long memberCount = folderMemberRepository.findByFolderId(entity.getId()).size();
        return TeamFriendFolderView.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .color(entity.getColor())
                .sortOrder(entity.getFolderOrder())
                .isDefault(entity.getIsDefault())
                .memberCount(memberCount)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    /**
     * ADMIN または {@code MANAGE_FRIEND_TEAMS} 権限を保持していることを要求する。
     *
     * @param userId ユーザー ID
     * @param teamId チーム ID
     */
    private void requireManageFriendTeams(Long userId, Long teamId) {
        accessControlService.checkPermission(userId, teamId, SCOPE_TEAM, PERM_MANAGE_FRIEND_TEAMS);
    }

    /**
     * フォルダ関連の監査ログを記録する。
     *
     * @param eventType イベント種別（例: {@code FRIEND_FOLDER_CREATED}）
     * @param userId    操作実行者のユーザー ID
     * @param teamId    自チーム ID
     * @param folderId  対象フォルダ ID
     * @param metadata  JSON 文字列化済みメタデータ
     */
    private void recordFolderAudit(String eventType, Long userId,
                                   Long teamId, Long folderId, String metadata) {
        auditLogService.record(
                eventType, userId, null,
                teamId, null,
                null, null, null,
                metadata);
    }

    /**
     * JSON 値として安全に埋め込むためのシンプルなエスケープを行う。
     *
     * @param value 原文字列（{@code null} 可）
     * @return JSON 用にエスケープされた文字列。{@code null} の場合は空文字
     */
    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
