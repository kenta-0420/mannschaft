package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.TargetType;
import com.mannschaft.app.bulletin.dto.CreateReactionRequest;
import com.mannschaft.app.bulletin.dto.ReactionResponse;
import com.mannschaft.app.bulletin.dto.ReactionSummaryResponse;
import com.mannschaft.app.bulletin.entity.BulletinReactionEntity;
import com.mannschaft.app.bulletin.repository.BulletinReactionRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 掲示板リアクションサービス。リアクションの追加・削除・集計を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BulletinReactionService {

    private final BulletinReactionRepository reactionRepository;
    private final BulletinMapper bulletinMapper;

    /**
     * リアクションを追加する。
     *
     * @param userId  ユーザーID
     * @param request 作成リクエスト
     * @return 作成されたリアクションレスポンス
     */
    @Transactional
    public ReactionResponse addReaction(Long userId, CreateReactionRequest request) {
        TargetType targetType = TargetType.valueOf(request.getTargetType());

        if (reactionRepository.existsByTargetTypeAndTargetIdAndUserIdAndEmoji(
                targetType, request.getTargetId(), userId, request.getEmoji())) {
            throw new BusinessException(BulletinErrorCode.DUPLICATE_REACTION);
        }

        BulletinReactionEntity entity = BulletinReactionEntity.builder()
                .targetType(targetType)
                .targetId(request.getTargetId())
                .userId(userId)
                .emoji(request.getEmoji())
                .build();

        BulletinReactionEntity saved = reactionRepository.save(entity);
        log.info("リアクション追加: targetType={}, targetId={}, userId={}, emoji={}",
                targetType, request.getTargetId(), userId, request.getEmoji());
        return bulletinMapper.toReactionResponse(saved);
    }

    /**
     * リアクションを削除する。
     *
     * @param userId     ユーザーID
     * @param targetType ターゲット種別
     * @param targetId   ターゲットID
     * @param emoji      絵文字
     */
    @Transactional
    public void removeReaction(Long userId, String targetType, Long targetId, String emoji) {
        TargetType type = TargetType.valueOf(targetType);
        BulletinReactionEntity entity = reactionRepository
                .findByTargetTypeAndTargetIdAndUserIdAndEmoji(type, targetId, userId, emoji)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.REACTION_NOT_FOUND));

        reactionRepository.delete(entity);
        log.info("リアクション削除: targetType={}, targetId={}, userId={}, emoji={}",
                targetType, targetId, userId, emoji);
    }

    /**
     * ターゲットのリアクション一覧を取得する。
     *
     * @param targetType ターゲット種別
     * @param targetId   ターゲットID
     * @return リアクションレスポンスリスト
     */
    public List<ReactionResponse> listReactions(String targetType, Long targetId) {
        TargetType type = TargetType.valueOf(targetType);
        List<BulletinReactionEntity> reactions = reactionRepository.findByTargetTypeAndTargetId(type, targetId);
        return bulletinMapper.toReactionResponseList(reactions);
    }

    /**
     * ターゲットのリアクション集計を取得する。
     *
     * @param targetType ターゲット種別
     * @param targetId   ターゲットID
     * @return リアクション集計レスポンスリスト
     */
    public List<ReactionSummaryResponse> getReactionSummary(String targetType, Long targetId) {
        TargetType type = TargetType.valueOf(targetType);
        List<Object[]> results = reactionRepository.countByTargetGroupedByEmoji(type, targetId);
        return results.stream()
                .map(row -> new ReactionSummaryResponse((String) row[0], (Long) row[1]))
                .toList();
    }
}
