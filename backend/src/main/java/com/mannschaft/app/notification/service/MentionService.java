package com.mannschaft.app.notification.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.notification.dto.MentionResponse;
import com.mannschaft.app.notification.entity.MentionEntity;
import com.mannschaft.app.notification.repository.MentionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * メンション機能サービス。
 * メンションの作成（テキスト解析→対象ユーザーごとに行作成）、一覧取得、既読化を提供する。
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MentionService {

    /** 抜粋に保存する最大文字数（DBカラム長と一致させる） */
    private static final int SNIPPET_MAX_LEN = 500;

    private final MentionRepository mentionRepository;
    private final UserRepository userRepository;
    private final MentionExtractor mentionExtractor;

    // ============================================================
    // 読み取り
    // ============================================================

    /**
     * 指定ユーザー宛のメンション一覧を取得する。
     */
    public List<MentionResponse> listMentions(Long userId) {
        List<MentionEntity> mentions = mentionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (mentions.isEmpty()) {
            return List.of();
        }
        // mentionedBy を一括ロードして N+1 を避ける
        List<Long> byUserIds = mentions.stream()
                .map(MentionEntity::getMentionedByUserId)
                .distinct()
                .toList();
        Map<Long, UserEntity> byUserMap = new HashMap<>();
        userRepository.findAllById(byUserIds).forEach(u -> byUserMap.put(u.getId(), u));

        return mentions.stream()
                .map(m -> toResponse(m, byUserMap.get(m.getMentionedByUserId())))
                .toList();
    }

    /**
     * 指定ユーザー宛の未読メンション件数を取得する。
     */
    public long countUnread(Long userId) {
        return mentionRepository.countByUserIdAndIsReadFalse(userId);
    }

    // ============================================================
    // 既読化
    // ============================================================

    /**
     * 指定ユーザーが自分宛のメンションを既読にする。
     *
     * @throws BusinessException 自分宛でない・存在しない場合
     */
    @Transactional
    public void markAsRead(Long userId, Long mentionId) {
        MentionEntity mention = mentionRepository.findByIdAndUserId(mentionId, userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_001));
        if (Boolean.TRUE.equals(mention.getIsRead())) {
            return;
        }
        mention.markAsRead();
    }

    // ============================================================
    // 作成（各ドメインからのフック用）
    // ============================================================

    /**
     * テキストからメンションを抽出し、対象ユーザーごとにメンションレコードを作成する。
     * 自分自身へのメンションはスキップする。
     *
     * @param mentionedByUserId メンション元のユーザーID（投稿者）
     * @param contentType       POST | MESSAGE | THREAD | COMMENT
     * @param contentId         元コンテンツのID
     * @param contentTitle      タイトル（任意）
     * @param contentBody       本文（解析対象）
     * @param url               遷移先URL
     * @return 作成されたメンション件数
     */
    @Transactional
    public int createMentionsFromText(
            Long mentionedByUserId,
            String contentType,
            Long contentId,
            String contentTitle,
            String contentBody,
            String url) {
        List<UserEntity> mentionedUsers = mentionExtractor.extractMentionedUsers(contentBody);
        if (mentionedUsers.isEmpty()) {
            return 0;
        }

        String snippet = mentionExtractor.buildSnippet(contentBody, SNIPPET_MAX_LEN);
        int created = 0;
        for (UserEntity target : mentionedUsers) {
            // 自分自身へのメンションはスキップ
            if (target.getId().equals(mentionedByUserId)) {
                continue;
            }
            MentionEntity entity = MentionEntity.builder()
                    .userId(target.getId())
                    .mentionedByUserId(mentionedByUserId)
                    .contentType(contentType)
                    .contentId(contentId)
                    .contentTitle(contentTitle)
                    .contentSnippet(snippet)
                    .url(url)
                    .isRead(false)
                    .build();
            mentionRepository.save(entity);
            created++;
        }
        if (created > 0) {
            log.debug("Mentions created: count={}, contentType={}, contentId={}",
                    created, contentType, contentId);
        }
        return created;
    }

    // ============================================================
    // private
    // ============================================================

    private MentionResponse toResponse(MentionEntity entity, UserEntity mentionedBy) {
        MentionResponse.MentionedBy by;
        if (mentionedBy != null) {
            by = MentionResponse.MentionedBy.builder()
                    .id(mentionedBy.getId())
                    .displayName(mentionedBy.getDisplayName())
                    .avatarUrl(mentionedBy.getAvatarUrl())
                    .build();
        } else {
            // ユーザー削除済みなどでロードできなかった場合のフォールバック
            by = MentionResponse.MentionedBy.builder()
                    .id(entity.getMentionedByUserId())
                    .displayName("(削除済みユーザー)")
                    .avatarUrl(null)
                    .build();
        }
        return MentionResponse.builder()
                .id(entity.getId())
                .mentionedBy(by)
                .contentType(entity.getContentType())
                .contentId(entity.getContentId())
                .contentTitle(entity.getContentTitle())
                .contentSnippet(entity.getContentSnippet())
                .url(entity.getUrl())
                .isRead(entity.getIsRead())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
