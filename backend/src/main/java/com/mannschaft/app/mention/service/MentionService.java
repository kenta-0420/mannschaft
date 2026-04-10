package com.mannschaft.app.mention.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.mention.MentionErrorCode;
import com.mannschaft.app.mention.dto.MentionResponse;
import com.mannschaft.app.mention.dto.MentionResponse.MentionedByUser;
import com.mannschaft.app.mention.entity.MentionEntity;
import com.mannschaft.app.mention.repository.MentionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * メンションサービス。
 *
 * <p>メンション一覧取得・既読化の業務ロジックを提供する。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MentionService {

    private final MentionRepository mentionRepository;
    private final UserRepository userRepository;

    /**
     * 指定ユーザー宛のメンション一覧を取得する。
     *
     * @param userId 認証ユーザー ID
     * @return メンションレスポンス一覧
     */
    @Transactional(readOnly = true)
    public List<MentionResponse> getMentions(Long userId) {
        List<MentionEntity> entities = mentionRepository.findByMentionedUserIdOrderByCreatedAtDesc(userId);

        if (entities.isEmpty()) {
            return List.of();
        }

        // メンションしたユーザーの ID を収集し、一括取得する
        Set<Long> mentionedByIds = entities.stream()
                .map(MentionEntity::getMentionedById)
                .collect(Collectors.toSet());

        Map<Long, UserEntity> userMap = userRepository.findAllById(mentionedByIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, u -> u));

        return entities.stream()
                .map(entity -> toResponse(entity, userMap))
                .toList();
    }

    /**
     * メンションを既読にする。
     *
     * <p>所有者チェック付き。自分宛でないメンションの既読化は MENTION_NOT_FOUND で拒否する（IDOR 対策）。</p>
     *
     * @param mentionId メンション ID
     * @param userId    認証ユーザー ID
     */
    @Transactional
    public void markAsRead(Long mentionId, Long userId) {
        MentionEntity entity = mentionRepository.findById(mentionId)
                .filter(m -> m.getMentionedUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(MentionErrorCode.MENTION_NOT_FOUND));

        if (!entity.getIsRead()) {
            entity.markAsRead();
            mentionRepository.save(entity);
        }
    }

    /**
     * エンティティをレスポンス DTO に変換する。
     */
    private MentionResponse toResponse(MentionEntity entity, Map<Long, UserEntity> userMap) {
        UserEntity mentionedByUser = userMap.get(entity.getMentionedById());

        MentionedByUser mentionedBy;
        if (mentionedByUser != null) {
            mentionedBy = new MentionedByUser(
                    mentionedByUser.getId(),
                    mentionedByUser.getDisplayName(),
                    mentionedByUser.getAvatarUrl()
            );
        } else {
            // ユーザーが削除されている場合のフォールバック
            mentionedBy = new MentionedByUser(
                    entity.getMentionedById(),
                    "退会済みユーザー",
                    null
            );
        }

        // Phase 1: contentType と contentTitle/url は target_type/target_id から文字列生成
        String contentType = resolveContentType(entity.getTargetType());
        String contentTitle = resolveContentTitle(entity.getTargetType());
        String url = resolveUrl(entity.getTargetType(), entity.getTargetId());

        return new MentionResponse(
                entity.getId(),
                mentionedBy,
                contentType,
                entity.getTargetId(),
                contentTitle,
                entity.getContentSnippet(),
                url,
                entity.getIsRead(),
                entity.getCreatedAt()
        );
    }

    /**
     * target_type からフロントエンド向け contentType 文字列を生成する。
     * Phase 1 では単純なマッピング。
     */
    private String resolveContentType(String targetType) {
        return switch (targetType) {
            case "TIMELINE_POST" -> "POST";
            case "CHAT_MESSAGE" -> "MESSAGE";
            case "TIMELINE_COMMENT" -> "COMMENT";
            case "BULLETIN_THREAD" -> "THREAD";
            default -> targetType;
        };
    }

    /**
     * target_type からコンテンツタイトルを生成する。
     * Phase 1 ではシンプルな文字列生成。Phase 2 で実際の JOIN に置き換え。
     */
    private String resolveContentTitle(String targetType) {
        return switch (targetType) {
            case "TIMELINE_POST" -> "タイムライン投稿";
            case "CHAT_MESSAGE" -> "チャットメッセージ";
            case "TIMELINE_COMMENT" -> "コメント";
            case "BULLETIN_THREAD" -> "掲示板スレッド";
            default -> targetType;
        };
    }

    /**
     * target_type + target_id から遷移先 URL を生成する。
     * Phase 1 ではシンプルな文字列生成。Phase 2 で実際のデータ参照に置き換え。
     */
    private String resolveUrl(String targetType, Long targetId) {
        return switch (targetType) {
            case "TIMELINE_POST" -> "/timeline/" + targetId;
            case "CHAT_MESSAGE" -> "/chat?message=" + targetId;
            case "TIMELINE_COMMENT" -> "/timeline/" + targetId;
            case "BULLETIN_THREAD" -> "/bulletin/" + targetId;
            default -> "/";
        };
    }
}
