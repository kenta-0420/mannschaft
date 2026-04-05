package com.mannschaft.app.contact.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.contact.ContactErrorCode;
import com.mannschaft.app.contact.dto.ContactResponse;
import com.mannschaft.app.contact.dto.ContactUserDto;
import com.mannschaft.app.dashboard.FolderItemType;
import com.mannschaft.app.dashboard.entity.ChatContactFolderEntity;
import com.mannschaft.app.dashboard.entity.ChatContactFolderItemEntity;
import com.mannschaft.app.dashboard.repository.ChatContactFolderItemRepository;
import com.mannschaft.app.dashboard.repository.ChatContactFolderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 連絡先一覧・削除サービス。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ContactService {

    private final ChatContactFolderRepository folderRepository;
    private final ChatContactFolderItemRepository folderItemRepository;
    private final UserRepository userRepository;

    /**
     * 連絡先一覧を取得する。
     * デフォルトフォルダ（全フォルダ）またはフォルダIDを指定して取得。
     *
     * @param userId   取得するユーザーID
     * @param folderId フォルダID（null=全フォルダ）
     * @param q        キーワード検索（null=全件）
     * @return 連絡先一覧
     */
    public List<ContactResponse> listContacts(Long userId, Long folderId, String q) {
        // ユーザーのフォルダ一覧を取得
        List<ChatContactFolderEntity> folders = folderRepository.findByUserIdOrderBySortOrder(userId);

        // 対象フォルダを絞り込み
        List<Long> targetFolderIds = folderId != null
                ? folders.stream().filter(f -> f.getId().equals(folderId)).map(ChatContactFolderEntity::getId).toList()
                : folders.stream().map(ChatContactFolderEntity::getId).toList();

        if (targetFolderIds.isEmpty()) {
            return List.of();
        }

        // フォルダID→フォルダ のマップ
        Map<Long, ChatContactFolderEntity> folderMap = folders.stream()
                .collect(Collectors.toMap(ChatContactFolderEntity::getId, f -> f));

        // 各フォルダの CONTACT アイテムを取得
        List<ChatContactFolderItemEntity> items = targetFolderIds.stream()
                .flatMap(fid -> folderItemRepository.findByFolderIdOrderByLastMessageAt(fid, userId).stream())
                .filter(item -> FolderItemType.CONTACT == item.getItemType())
                .toList();

        if (items.isEmpty()) {
            return List.of();
        }

        // ユーザー情報を一括取得
        List<Long> userIds = items.stream().map(ChatContactFolderItemEntity::getItemId).distinct().toList();
        Map<Long, UserEntity> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, u -> u));

        return items.stream()
                .filter(item -> {
                    if (q == null || q.isBlank()) return true;
                    UserEntity u = userMap.get(item.getItemId());
                    if (u == null) return false;
                    String keyword = q.toLowerCase();
                    return u.getDisplayName().toLowerCase().contains(keyword)
                            || (u.getContactHandle() != null && u.getContactHandle().contains(keyword))
                            || (item.getCustomName() != null && item.getCustomName().toLowerCase().contains(keyword));
                })
                .map(item -> {
                    UserEntity u = userMap.get(item.getItemId());
                    return ContactResponse.builder()
                            .folderItemId(item.getId())
                            .folderId(item.getFolderId())
                            .user(u != null ? ContactUserDto.builder()
                                    .id(u.getId())
                                    .displayName(u.getDisplayName())
                                    .contactHandle(u.getContactHandle())
                                    .avatarUrl(u.getAvatarUrl())
                                    .build() : null)
                            .customName(item.getCustomName())
                            .isPinned(item.getIsPinned())
                            .privateNote(item.getPrivateNote())
                            .addedAt(item.getCreatedAt())
                            .build();
                })
                .toList();
    }

    /**
     * 連絡先を削除する（自分のフォルダからのみ削除）。
     *
     * @param userId       操作するユーザーID
     * @param targetUserId 削除する連絡先のユーザーID
     */
    @Transactional
    public void deleteContact(Long userId, Long targetUserId) {
        // 自分のフォルダに登録されているか確認
        boolean exists = folderItemRepository.existsByFolderOwnerAndItemTypeAndItemId(
                userId, FolderItemType.CONTACT, targetUserId);
        if (!exists) {
            throw new BusinessException(ContactErrorCode.CONTACT_015);
        }
        // 削除
        removeContactFromFolder(userId, targetUserId);
    }

    /**
     * ユーザーAの連絡先フォルダからユーザーBを削除する（内部処理用）。
     */
    @Transactional
    public void removeContactFromFolder(Long ownerId, Long contactUserId) {
        // ownerId のフォルダから contactUserId の CONTACT アイテムを削除
        folderRepository.findByUserIdOrderBySortOrder(ownerId).forEach(folder -> {
            folderItemRepository.findByFolderId(folder.getId()).stream()
                    .filter(item -> FolderItemType.CONTACT == item.getItemType()
                            && contactUserId.equals(item.getItemId()))
                    .forEach(folderItemRepository::delete);
        });
    }

    /**
     * 連絡先を双方のデフォルトフォルダ（sort_order が最小のフォルダ）に追加する。
     * フォルダが存在しない場合はデフォルトフォルダを作成する。
     *
     * @param userA ユーザーAのID
     * @param userB ユーザーBのID
     */
    @Transactional
    public void addContactBidirectional(Long userA, Long userB) {
        addToDefaultFolder(userA, userB);
        addToDefaultFolder(userB, userA);
    }

    /**
     * ユーザーの連絡先フォルダにアイテムを追加する。
     * 既に追加済みの場合は何もしない。
     */
    @Transactional
    public void addToDefaultFolder(Long ownerId, Long contactUserId) {
        // 既に登録されているか確認
        boolean alreadyExists = folderItemRepository.existsByFolderOwnerAndItemTypeAndItemId(
                ownerId, FolderItemType.CONTACT, contactUserId);
        if (alreadyExists) return;

        // デフォルトフォルダを取得または作成
        List<ChatContactFolderEntity> folders = folderRepository.findByUserIdOrderBySortOrder(ownerId);
        ChatContactFolderEntity folder;
        if (folders.isEmpty()) {
            folder = folderRepository.save(ChatContactFolderEntity.builder()
                    .userId(ownerId)
                    .name("連絡先")
                    .sortOrder(0)
                    .build());
        } else {
            folder = folders.get(0);
        }

        folderItemRepository.save(ChatContactFolderItemEntity.builder()
                .folderId(folder.getId())
                .itemType(FolderItemType.CONTACT)
                .itemId(contactUserId)
                .build());
    }
}
