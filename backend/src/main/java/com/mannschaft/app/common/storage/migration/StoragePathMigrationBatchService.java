package com.mannschaft.app.common.storage.migration;

import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatMessageAttachmentRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.circulation.entity.CirculationAttachmentEntity;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.repository.CirculationAttachmentRepository;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.schedule.entity.ScheduleMediaUploadEntity;
import com.mannschaft.app.schedule.repository.ScheduleMediaUploadRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteAttachmentEntity;
import com.mannschaft.app.timetable.notes.repository.TimetableSlotUserNoteAttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * F13 Phase 5-b ストレージパス移行バッチサービス。
 *
 * <p>Phase 5-a で導入したスコープ別パス命名規則（{@code {feature}/{scopeType}/{scopeId}/...}）に合わせて、
 * 旧パスで保存されている既存ファイルを新パスへ一括移行する。</p>
 *
 * <h2>移行対象</h2>
 * <ul>
 *   <li>CHAT: {@code chat/{uuid}/{filename}} → {@code chat/{TEAM|ORGANIZATION|PERSONAL}/{scopeId}/{uuid}/{filename}}</li>
 *   <li>FILE_SHARING: {@code files/{uuid}.{ext}} → {@code files/{TEAM|ORGANIZATION|PERSONAL}/{scopeId}/{uuid}.{ext}}</li>
 *   <li>CIRCULATION: {@code circulation/{documentId}/{uuid}} → {@code circulation/{TEAM|ORGANIZATION|PERSONAL}/{scopeId}/{documentId}/{uuid}}</li>
 *   <li>SCHEDULE_MEDIA: {@code schedules/{scheduleId}/{uuid}.{ext}} → {@code schedules/{TEAM|ORGANIZATION|PERSONAL}/{scopeId}/{scheduleId}/{uuid}.{ext}}</li>
 *   <li>PERSONAL_TIMETABLE_NOTES: {@code user/{userId}/timetable-notes/} → {@code user/PERSONAL/{userId}/timetable-notes/}</li>
 * </ul>
 *
 * <h2>設計方針</h2>
 * <ul>
 *   <li>CopyObject のみ実行。旧パスの削除は R2 ライフサイクルルールで 30 日後に自動削除される。</li>
 *   <li>1件ずつ {@code REQUIRES_NEW} トランザクションで処理し、1件失敗しても他が巻き戻らない。</li>
 *   <li>エラーは {@code storage_migration_errors} に記録してスキップ（バッチ続行）。</li>
 *   <li>ページング処理: 一度に最大 500 件ずつ処理してメモリ枯渇を防ぐ。</li>
 * </ul>
 *
 * <p>本バッチは手動トリガー専用。{@code @Scheduled} は付けない。
 * 管理者が {@code POST /api/v1/system-admin/storage-migration/run} で起動する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoragePathMigrationBatchService {

    private static final int PAGE_SIZE = 500;

    private final R2StorageService r2StorageService;
    private final StorageMigrationErrorRepository errorRepository;
    private final ChatMessageAttachmentRepository chatMessageAttachmentRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatChannelRepository chatChannelRepository;
    private final SharedFileRepository sharedFileRepository;
    private final SharedFolderRepository sharedFolderRepository;
    private final CirculationAttachmentRepository circulationAttachmentRepository;
    private final CirculationDocumentRepository circulationDocumentRepository;
    private final ScheduleMediaUploadRepository scheduleMediaUploadRepository;
    private final ScheduleRepository scheduleRepository;
    private final TimetableSlotUserNoteAttachmentRepository timetableNoteAttachmentRepository;

    // ==================== 公開API ====================

    /**
     * 全機能の旧パスファイルを新パスへ一括移行する。
     *
     * @return 機能ごとの移行件数マップ
     */
    public Map<String, Long> migrateAll() {
        log.info("ストレージパス移行バッチ開始");
        Map<String, Long> results = new HashMap<>();
        results.put("CHAT", migrateChatAttachments());
        results.put("FILE_SHARING", migrateSharedFiles());
        results.put("CIRCULATION", migrateCirculationAttachments());
        results.put("SCHEDULE_MEDIA", migrateScheduleMedia());
        results.put("PERSONAL_TIMETABLE_NOTES", migrateTimetableNoteAttachments());
        log.info("ストレージパス移行バッチ完了: results={}", results);
        return results;
    }

    /**
     * CHAT 機能の添付ファイルを移行する。
     *
     * @return 移行件数
     */
    public long migrateChatAttachments() {
        log.info("CHAT 添付ファイル移行開始");
        long migrated = 0L;
        int pageNum = 0;
        Page<ChatMessageAttachmentEntity> page;
        do {
            Pageable pageable = PageRequest.of(pageNum, PAGE_SIZE);
            page = chatMessageAttachmentRepository.findAll(pageable);
            for (ChatMessageAttachmentEntity attachment : page.getContent()) {
                if (!isOldChatPath(attachment.getFileKey())) {
                    continue;
                }
                try {
                    String newKey = buildNewChatPath(attachment.getFileKey(), attachment.getMessageId());
                    migrateOneChatAttachment(attachment.getId(), attachment.getFileKey(), newKey);
                    migrated++;
                } catch (Exception e) {
                    log.warn("CHAT 添付移行スキップ: id={}, error={}", attachment.getId(), e.getMessage());
                    recordError("chat_message_attachments", attachment.getId(),
                            attachment.getFileKey(), "", e.getMessage());
                }
            }
            pageNum++;
        } while (page.hasNext());
        log.info("CHAT 添付ファイル移行完了: migrated={}", migrated);
        return migrated;
    }

    /**
     * FILE_SHARING 機能の共有ファイルを移行する。
     *
     * @return 移行件数
     */
    public long migrateSharedFiles() {
        log.info("FILE_SHARING 共有ファイル移行開始");
        long migrated = 0L;
        int pageNum = 0;
        Page<SharedFileEntity> page;
        do {
            Pageable pageable = PageRequest.of(pageNum, PAGE_SIZE);
            page = sharedFileRepository.findAll(pageable);
            for (SharedFileEntity file : page.getContent()) {
                if (!isOldFilesPath(file.getFileKey())) {
                    continue;
                }
                try {
                    String newKey = buildNewSharedFilePath(file.getFileKey(), file.getFolderId());
                    migrateOneSharedFile(file.getId(), file.getFileKey(), newKey);
                    migrated++;
                } catch (Exception e) {
                    log.warn("FILE_SHARING 移行スキップ: id={}, error={}", file.getId(), e.getMessage());
                    recordError("shared_files", file.getId(),
                            file.getFileKey(), "", e.getMessage());
                }
            }
            pageNum++;
        } while (page.hasNext());
        log.info("FILE_SHARING 共有ファイル移行完了: migrated={}", migrated);
        return migrated;
    }

    /**
     * CIRCULATION 機能の添付ファイルを移行する。
     *
     * @return 移行件数
     */
    public long migrateCirculationAttachments() {
        log.info("CIRCULATION 添付ファイル移行開始");
        long migrated = 0L;
        int pageNum = 0;
        Page<CirculationAttachmentEntity> page;
        do {
            Pageable pageable = PageRequest.of(pageNum, PAGE_SIZE);
            page = circulationAttachmentRepository.findAll(pageable);
            for (CirculationAttachmentEntity attachment : page.getContent()) {
                if (!isOldCirculationPath(attachment.getFileKey())) {
                    continue;
                }
                try {
                    String newKey = buildNewCirculationPath(attachment.getFileKey(), attachment.getDocumentId());
                    migrateOneCirculationAttachment(attachment.getId(), attachment.getFileKey(), newKey);
                    migrated++;
                } catch (Exception e) {
                    log.warn("CIRCULATION 移行スキップ: id={}, error={}", attachment.getId(), e.getMessage());
                    recordError("circulation_attachments", attachment.getId(),
                            attachment.getFileKey(), "", e.getMessage());
                }
            }
            pageNum++;
        } while (page.hasNext());
        log.info("CIRCULATION 添付ファイル移行完了: migrated={}", migrated);
        return migrated;
    }

    /**
     * SCHEDULE_MEDIA 機能のメディアファイルを移行する。
     *
     * @return 移行件数
     */
    public long migrateScheduleMedia() {
        log.info("SCHEDULE_MEDIA 移行開始");
        long migrated = 0L;
        int pageNum = 0;
        Page<ScheduleMediaUploadEntity> page;
        do {
            Pageable pageable = PageRequest.of(pageNum, PAGE_SIZE);
            page = scheduleMediaUploadRepository.findAll(pageable);
            for (ScheduleMediaUploadEntity media : page.getContent()) {
                if (!isOldSchedulesPath(media.getR2Key())) {
                    continue;
                }
                try {
                    String newKey = buildNewScheduleMediaPath(media.getR2Key(), media.getScheduleId());
                    migrateOneScheduleMedia(media.getId(), media.getR2Key(), newKey);
                    migrated++;
                } catch (Exception e) {
                    log.warn("SCHEDULE_MEDIA 移行スキップ: id={}, error={}", media.getId(), e.getMessage());
                    recordError("schedule_media_uploads", media.getId(),
                            media.getR2Key(), "", e.getMessage());
                }
            }
            pageNum++;
        } while (page.hasNext());
        log.info("SCHEDULE_MEDIA 移行完了: migrated={}", migrated);
        return migrated;
    }

    /**
     * PERSONAL_TIMETABLE_NOTES 機能の添付ファイルを移行する。
     *
     * @return 移行件数
     */
    public long migrateTimetableNoteAttachments() {
        log.info("PERSONAL_TIMETABLE_NOTES 移行開始");
        long migrated = 0L;
        int pageNum = 0;
        Page<TimetableSlotUserNoteAttachmentEntity> page;
        do {
            Pageable pageable = PageRequest.of(pageNum, PAGE_SIZE);
            page = timetableNoteAttachmentRepository.findAll(pageable);
            for (TimetableSlotUserNoteAttachmentEntity attachment : page.getContent()) {
                if (!isOldTimetableNotePath(attachment.getR2ObjectKey())) {
                    continue;
                }
                try {
                    String newKey = buildNewTimetableNotePath(attachment.getR2ObjectKey(), attachment.getUserId());
                    migrateOneTimetableNoteAttachment(attachment.getId(), attachment.getR2ObjectKey(), newKey);
                    migrated++;
                } catch (Exception e) {
                    log.warn("PERSONAL_TIMETABLE_NOTES 移行スキップ: id={}, error={}", attachment.getId(), e.getMessage());
                    recordError("timetable_slot_user_note_attachments", attachment.getId(),
                            attachment.getR2ObjectKey(), "", e.getMessage());
                }
            }
            pageNum++;
        } while (page.hasNext());
        log.info("PERSONAL_TIMETABLE_NOTES 移行完了: migrated={}", migrated);
        return migrated;
    }

    /**
     * 移行進捗状況を集計して返す。
     *
     * @return 機能別の移行ステータス
     */
    public StorageMigrationStatus getStatus() {
        Map<String, Long> total = new HashMap<>();
        Map<String, Long> migrated = new HashMap<>();
        Map<String, Long> pending = new HashMap<>();

        countChatStatus(total, migrated, pending);
        countSharedFilesStatus(total, migrated, pending);
        countCirculationStatus(total, migrated, pending);
        countScheduleMediaStatus(total, migrated, pending);
        countTimetableNotesStatus(total, migrated, pending);

        long errorCount = errorRepository.countByResolvedAtIsNull();
        long totalPending = pending.values().stream().mapToLong(Long::longValue).sum();
        String status = totalPending == 0L ? "COMPLETED" : "PENDING";

        return new StorageMigrationStatus(total, migrated, pending, errorCount, status);
    }

    // ==================== 個別移行処理（REQUIRES_NEW） ====================

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void migrateOneChatAttachment(Long attachmentId, String oldKey, String newKey) {
        r2StorageService.copyObject(oldKey, newKey);
        chatMessageAttachmentRepository.findById(attachmentId).ifPresent(attachment -> {
            ChatMessageAttachmentEntity updated = attachment.toBuilder().fileKey(newKey).build();
            chatMessageAttachmentRepository.save(updated);
        });
        log.debug("CHAT 添付移行完了: id={}, {} → {}", attachmentId, oldKey, newKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void migrateOneSharedFile(Long fileId, String oldKey, String newKey) {
        r2StorageService.copyObject(oldKey, newKey);
        sharedFileRepository.findById(fileId).ifPresent(file -> {
            SharedFileEntity updated = file.toBuilder().fileKey(newKey).build();
            sharedFileRepository.save(updated);
        });
        log.debug("FILE_SHARING 移行完了: id={}, {} → {}", fileId, oldKey, newKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void migrateOneCirculationAttachment(Long attachmentId, String oldKey, String newKey) {
        r2StorageService.copyObject(oldKey, newKey);
        circulationAttachmentRepository.findById(attachmentId).ifPresent(attachment -> {
            CirculationAttachmentEntity updated = attachment.toBuilder().fileKey(newKey).build();
            circulationAttachmentRepository.save(updated);
        });
        log.debug("CIRCULATION 移行完了: id={}, {} → {}", attachmentId, oldKey, newKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void migrateOneScheduleMedia(Long mediaId, String oldKey, String newKey) {
        r2StorageService.copyObject(oldKey, newKey);
        scheduleMediaUploadRepository.findById(mediaId).ifPresent(media -> {
            ScheduleMediaUploadEntity updated = media.toBuilder().r2Key(newKey).build();
            scheduleMediaUploadRepository.save(updated);
        });
        log.debug("SCHEDULE_MEDIA 移行完了: id={}, {} → {}", mediaId, oldKey, newKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void migrateOneTimetableNoteAttachment(Long attachmentId, String oldKey, String newKey) {
        r2StorageService.copyObject(oldKey, newKey);
        timetableNoteAttachmentRepository.findById(attachmentId).ifPresent(attachment -> {
            TimetableSlotUserNoteAttachmentEntity updated = attachment.toBuilder()
                    .r2ObjectKey(newKey).build();
            timetableNoteAttachmentRepository.save(updated);
        });
        log.debug("PERSONAL_TIMETABLE_NOTES 移行完了: id={}, {} → {}", attachmentId, oldKey, newKey);
    }

    // ==================== 旧パス判定ロジック ====================

    /**
     * CHAT の旧パスかどうかを判定する。
     *
     * <p>旧: {@code chat/{uuid}/{filename}} — 2番目セグメントがUUID形式<br>
     * 新: {@code chat/{TEAM|ORGANIZATION|PERSONAL}/{scopeId}/...} — 2番目セグメントがenum値</p>
     *
     * @param fileKey R2オブジェクトキー
     * @return 旧パスの場合 true
     */
    public boolean isOldChatPath(String fileKey) {
        if (fileKey == null || !fileKey.startsWith("chat/")) return false;
        String[] parts = fileKey.split("/");
        if (parts.length < 3) return false;
        String second = parts[1];
        return !second.equals("TEAM") && !second.equals("ORGANIZATION") && !second.equals("PERSONAL");
    }

    /**
     * FILE_SHARING の旧パスかどうかを判定する。
     *
     * <p>旧: {@code files/{uuid}.{ext}} — 2番目セグメントがUUID+拡張子<br>
     * 新: {@code files/{TEAM|ORGANIZATION|PERSONAL}/{scopeId}/...}</p>
     *
     * @param fileKey R2オブジェクトキー
     * @return 旧パスの場合 true
     */
    public boolean isOldFilesPath(String fileKey) {
        if (fileKey == null || !fileKey.startsWith("files/")) return false;
        String[] parts = fileKey.split("/");
        if (parts.length < 2) return false;
        String second = parts[1];
        return !second.equals("TEAM") && !second.equals("ORGANIZATION") && !second.equals("PERSONAL");
    }

    /**
     * CIRCULATION の旧パスかどうかを判定する。
     *
     * <p>旧: {@code circulation/{documentId}/{uuid}} — 2番目セグメントが数値<br>
     * 新: {@code circulation/{TEAM|ORGANIZATION|PERSONAL}/{scopeId}/...}</p>
     *
     * @param fileKey R2オブジェクトキー
     * @return 旧パスの場合 true
     */
    public boolean isOldCirculationPath(String fileKey) {
        if (fileKey == null || !fileKey.startsWith("circulation/")) return false;
        String[] parts = fileKey.split("/");
        if (parts.length < 3) return false;
        String second = parts[1];
        return !second.equals("TEAM") && !second.equals("ORGANIZATION") && !second.equals("PERSONAL");
    }

    /**
     * SCHEDULE_MEDIA の旧パスかどうかを判定する。
     *
     * <p>旧: {@code schedules/{scheduleId}/{uuid}.{ext}} — 2番目セグメントが数値<br>
     * 新: {@code schedules/{TEAM|ORGANIZATION|PERSONAL}/{scopeId}/...}</p>
     *
     * @param fileKey R2オブジェクトキー
     * @return 旧パスの場合 true
     */
    public boolean isOldSchedulesPath(String fileKey) {
        if (fileKey == null || !fileKey.startsWith("schedules/")) return false;
        String[] parts = fileKey.split("/");
        if (parts.length < 3) return false;
        String second = parts[1];
        return !second.equals("TEAM") && !second.equals("ORGANIZATION") && !second.equals("PERSONAL");
    }

    /**
     * PERSONAL_TIMETABLE_NOTES の旧パスかどうかを判定する。
     *
     * <p>旧: {@code user/{userId}/timetable-notes/...} — 2番目セグメントが数値<br>
     * 新: {@code user/PERSONAL/{userId}/timetable-notes/...}</p>
     *
     * @param fileKey R2オブジェクトキー
     * @return 旧パスの場合 true
     */
    public boolean isOldTimetableNotePath(String fileKey) {
        if (fileKey == null || !fileKey.startsWith("user/")) return false;
        String[] parts = fileKey.split("/");
        if (parts.length < 3) return false;
        String second = parts[1];
        return !second.equals("PERSONAL");
    }

    // ==================== 新パス生成ロジック ====================

    /**
     * CHAT の新パスを生成する。
     *
     * @param oldKey    旧パス ({@code chat/{uuid}/{filename}})
     * @param messageId チャットメッセージID（チャンネル解決用）
     * @return 新パス ({@code chat/{scopeType}/{scopeId}/{uuid}/{filename}})
     */
    String buildNewChatPath(String oldKey, Long messageId) {
        // "chat/" プレフィックスを除いた残り: "{uuid}/{filename}"
        String remainder = oldKey.substring("chat/".length());
        String[] scopeParts = resolveChatScope(messageId);
        return "chat/" + scopeParts[0] + "/" + scopeParts[1] + "/" + remainder;
    }

    /**
     * FILE_SHARING の新パスを生成する。
     *
     * @param oldKey   旧パス ({@code files/{uuid}.{ext}})
     * @param folderId フォルダID（スコープ解決用）
     * @return 新パス ({@code files/{scopeType}/{scopeId}/{uuid}.{ext}})
     */
    String buildNewSharedFilePath(String oldKey, Long folderId) {
        // "files/" プレフィックスを除いた残り: "{uuid}.{ext}"
        String remainder = oldKey.substring("files/".length());
        String[] scopeParts = resolveFileSharingScope(folderId);
        return "files/" + scopeParts[0] + "/" + scopeParts[1] + "/" + remainder;
    }

    /**
     * CIRCULATION の新パスを生成する。
     *
     * @param oldKey     旧パス ({@code circulation/{documentId}/{uuid}})
     * @param documentId 文書ID（スコープ解決用）
     * @return 新パス ({@code circulation/{scopeType}/{scopeId}/{documentId}/{uuid}})
     */
    String buildNewCirculationPath(String oldKey, Long documentId) {
        // "circulation/" プレフィックスを除いた残り: "{documentId}/{uuid}"
        String remainder = oldKey.substring("circulation/".length());
        String[] scopeParts = resolveCirculationScope(documentId);
        return "circulation/" + scopeParts[0] + "/" + scopeParts[1] + "/" + remainder;
    }

    /**
     * SCHEDULE_MEDIA の新パスを生成する。
     *
     * @param oldKey     旧パス ({@code schedules/{scheduleId}/{uuid}.{ext}})
     * @param scheduleId スケジュールID（スコープ解決用）
     * @return 新パス ({@code schedules/{scopeType}/{scopeId}/{scheduleId}/{uuid}.{ext}})
     */
    String buildNewScheduleMediaPath(String oldKey, Long scheduleId) {
        // "schedules/" プレフィックスを除いた残り: "{scheduleId}/{uuid}.{ext}"
        String remainder = oldKey.substring("schedules/".length());
        String[] scopeParts = resolveScheduleScope(scheduleId);
        return "schedules/" + scopeParts[0] + "/" + scopeParts[1] + "/" + remainder;
    }

    /**
     * PERSONAL_TIMETABLE_NOTES の新パスを生成する。
     *
     * @param oldKey 旧パス ({@code user/{userId}/timetable-notes/...})
     * @param userId ユーザーID
     * @return 新パス ({@code user/PERSONAL/{userId}/timetable-notes/...})
     */
    String buildNewTimetableNotePath(String oldKey, Long userId) {
        // 旧: "user/{userId}/timetable-notes/..."
        // 新: "user/PERSONAL/{userId}/timetable-notes/..."
        String remainder = oldKey.substring("user/".length()); // "{userId}/timetable-notes/..."
        return "user/PERSONAL/" + remainder;
    }

    // ==================== スコープ解決ヘルパー ====================

    /**
     * チャットメッセージIDからチャンネルを辿ってスコープを解決する。
     *
     * @param messageId チャットメッセージID
     * @return [scopeType, scopeId] の配列
     */
    private String[] resolveChatScope(Long messageId) {
        return chatMessageRepository.findById(messageId)
                .flatMap(message -> chatChannelRepository.findById(message.getChannelId()))
                .map(channel -> {
                    if (channel.getTeamId() != null) {
                        return new String[]{"TEAM", String.valueOf(channel.getTeamId())};
                    } else if (channel.getOrganizationId() != null) {
                        return new String[]{"ORGANIZATION", String.valueOf(channel.getOrganizationId())};
                    } else {
                        // DM チャンネルの場合はチャンネル作成者をスコープIDとする
                        Long createdBy = channel.getCreatedBy();
                        Long dmScopeId = createdBy != null ? createdBy : messageId;
                        return new String[]{"PERSONAL", String.valueOf(dmScopeId)};
                    }
                })
                .orElseThrow(() -> new IllegalStateException(
                        "チャットメッセージが見つからないため CHAT スコープを解決できません: messageId=" + messageId));
    }

    /**
     * フォルダIDからスコープを解決する。
     *
     * @param folderId フォルダID
     * @return [scopeType, scopeId] の配列
     */
    private String[] resolveFileSharingScope(Long folderId) {
        return sharedFolderRepository.findById(folderId)
                .map(folder -> {
                    if (folder.getTeamId() != null) {
                        return new String[]{"TEAM", String.valueOf(folder.getTeamId())};
                    } else if (folder.getOrganizationId() != null) {
                        return new String[]{"ORGANIZATION", String.valueOf(folder.getOrganizationId())};
                    } else if (folder.getUserId() != null) {
                        return new String[]{"PERSONAL", String.valueOf(folder.getUserId())};
                    } else {
                        throw new IllegalStateException(
                                "フォルダのスコープを解決できません: folderId=" + folderId);
                    }
                })
                .orElseThrow(() -> new IllegalStateException(
                        "フォルダが見つからないため FILE_SHARING スコープを解決できません: folderId=" + folderId));
    }

    /**
     * 回覧文書IDからスコープを解決する。
     *
     * @param documentId 文書ID
     * @return [scopeType, scopeId] の配列
     */
    private String[] resolveCirculationScope(Long documentId) {
        return circulationDocumentRepository.findById(documentId)
                .map(document -> new String[]{document.getScopeType(), String.valueOf(document.getScopeId())})
                .orElseThrow(() -> new IllegalStateException(
                        "回覧文書が見つからないため CIRCULATION スコープを解決できません: documentId=" + documentId));
    }

    /**
     * スケジュールIDからスコープを解決する。
     *
     * @param scheduleId スケジュールID
     * @return [scopeType, scopeId] の配列
     */
    private String[] resolveScheduleScope(Long scheduleId) {
        if (scheduleId == null) {
            throw new IllegalStateException("scheduleId が NULL のためスコープを解決できません");
        }
        return scheduleRepository.findById(scheduleId)
                .map(schedule -> {
                    if (schedule.getTeamId() != null) {
                        return new String[]{"TEAM", String.valueOf(schedule.getTeamId())};
                    } else if (schedule.getOrganizationId() != null) {
                        return new String[]{"ORGANIZATION", String.valueOf(schedule.getOrganizationId())};
                    } else if (schedule.getUserId() != null) {
                        return new String[]{"PERSONAL", String.valueOf(schedule.getUserId())};
                    } else {
                        throw new IllegalStateException(
                                "スケジュールのスコープを解決できません: scheduleId=" + scheduleId);
                    }
                })
                .orElseThrow(() -> new IllegalStateException(
                        "スケジュールが見つからないため SCHEDULE_MEDIA スコープを解決できません: scheduleId=" + scheduleId));
    }

    // ==================== エラー記録 ====================

    /**
     * 移行エラーを {@code storage_migration_errors} に記録する。
     *
     * @param referenceType 対象テーブル名
     * @param referenceId   対象レコードID
     * @param oldKey        移行前R2キー
     * @param newKey        移行先R2キー（構築失敗時は空文字）
     * @param errorMessage  エラーメッセージ
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordError(String referenceType, Long referenceId,
                            String oldKey, String newKey, String errorMessage) {
        StorageMigrationErrorEntity error = StorageMigrationErrorEntity.builder()
                .referenceType(referenceType)
                .referenceId(referenceId)
                .oldFileKey(oldKey)
                .newFileKey(newKey)
                .errorMessage(errorMessage)
                .build();
        errorRepository.save(error);
        log.warn("ストレージ移行エラー記録: referenceType={}, referenceId={}, error={}",
                referenceType, referenceId, errorMessage);
    }

    // ==================== ステータス集計ヘルパー ====================

    private void countChatStatus(Map<String, Long> total, Map<String, Long> migrated, Map<String, Long> pending) {
        long[] counts = countByOldPath(chatMessageAttachmentRepository.findAll()
                .stream().map(ChatMessageAttachmentEntity::getFileKey).toList(),
                "chat/");
        total.put("CHAT", counts[0]);
        pending.put("CHAT", counts[1]);
        migrated.put("CHAT", counts[0] - counts[1]);
    }

    private void countSharedFilesStatus(Map<String, Long> total, Map<String, Long> migrated, Map<String, Long> pending) {
        long[] counts = countByOldPath(sharedFileRepository.findAll()
                .stream().map(SharedFileEntity::getFileKey).toList(),
                "files/");
        total.put("FILE_SHARING", counts[0]);
        pending.put("FILE_SHARING", counts[1]);
        migrated.put("FILE_SHARING", counts[0] - counts[1]);
    }

    private void countCirculationStatus(Map<String, Long> total, Map<String, Long> migrated, Map<String, Long> pending) {
        List<String> keys = circulationAttachmentRepository.findAll()
                .stream().map(CirculationAttachmentEntity::getFileKey).toList();
        long totalCount = keys.size();
        long pendingCount = keys.stream().filter(this::isOldCirculationPath).count();
        total.put("CIRCULATION", totalCount);
        pending.put("CIRCULATION", pendingCount);
        migrated.put("CIRCULATION", totalCount - pendingCount);
    }

    private void countScheduleMediaStatus(Map<String, Long> total, Map<String, Long> migrated, Map<String, Long> pending) {
        List<String> keys = scheduleMediaUploadRepository.findAll()
                .stream().map(ScheduleMediaUploadEntity::getR2Key).toList();
        long totalCount = keys.size();
        long pendingCount = keys.stream().filter(this::isOldSchedulesPath).count();
        total.put("SCHEDULE_MEDIA", totalCount);
        pending.put("SCHEDULE_MEDIA", pendingCount);
        migrated.put("SCHEDULE_MEDIA", totalCount - pendingCount);
    }

    private void countTimetableNotesStatus(Map<String, Long> total, Map<String, Long> migrated, Map<String, Long> pending) {
        List<String> keys = timetableNoteAttachmentRepository.findAll()
                .stream().map(TimetableSlotUserNoteAttachmentEntity::getR2ObjectKey).toList();
        long totalCount = keys.size();
        long pendingCount = keys.stream().filter(this::isOldTimetableNotePath).count();
        total.put("PERSONAL_TIMETABLE_NOTES", totalCount);
        pending.put("PERSONAL_TIMETABLE_NOTES", pendingCount);
        migrated.put("PERSONAL_TIMETABLE_NOTES", totalCount - pendingCount);
    }

    /**
     * 旧パスと新パスの件数を集計する汎用ヘルパー。
     *
     * @param keys   R2キーの一覧
     * @param prefix フィーチャープレフィックス（例: "chat/"）
     * @return [総件数, 旧パス件数] の配列
     */
    private long[] countByOldPath(List<String> keys, String prefix) {
        long totalCount = keys.size();
        long oldCount;
        if ("chat/".equals(prefix)) {
            oldCount = keys.stream().filter(this::isOldChatPath).count();
        } else {
            oldCount = keys.stream().filter(this::isOldFilesPath).count();
        }
        return new long[]{totalCount, oldCount};
    }

}
