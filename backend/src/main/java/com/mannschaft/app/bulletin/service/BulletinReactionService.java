package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.TargetType;
import com.mannschaft.app.bulletin.dto.CreateReactionRequest;
import com.mannschaft.app.bulletin.dto.ReactionResponse;
import com.mannschaft.app.bulletin.dto.ReactionSummaryResponse;
import com.mannschaft.app.bulletin.entity.BulletinReactionEntity;
import com.mannschaft.app.bulletin.entity.BulletinReplyEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinReactionRepository;
import com.mannschaft.app.bulletin.repository.BulletinReplyRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.service.TournamentContactAccessService;
import com.mannschaft.app.village.service.VillageBulletinAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 掲示板リアクションサービス。リアクションの追加・削除・集計を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BulletinReactionService {

    /** 許可される絵文字（プリセット 5 種。設計書 §6 / DB 備考）。 */
    private static final Set<String> ALLOWED_EMOJIS = Set.of("👍", "👏", "🙏", "😊", "❤️");

    private final BulletinReactionRepository reactionRepository;
    private final BulletinThreadRepository threadRepository;
    private final BulletinReplyRepository replyRepository;
    private final BulletinMapper bulletinMapper;
    private final BulletinAccessGuard accessGuard;
    /** F08.7.1 連絡機能: 大会/ディビジョンスコープの閲覧認可を委譲する（クロスドメイン・原則1）。 */
    private final TournamentContactAccessService tournamentContactAccessService;
    /** F17.1 村掲示板: 村スコープの閲覧認可を委譲する（BulletinAccessGuard は村を判定できない）。 */
    private final VillageBulletinAccessService villageBulletinAccessService;

    /**
     * リアクションを追加する。所属メンバーのみ + プリセット絵文字のみ。
     *
     * @param userId  ユーザーID
     * @param request 作成リクエスト
     * @return 作成されたリアクションレスポンス
     */
    @Transactional
    public ReactionResponse addReaction(Long userId, CreateReactionRequest request) {
        TargetType targetType = TargetType.valueOf(request.getTargetType());

        // 絵文字ホワイトリスト検証（設計書 §6）
        if (!ALLOWED_EMOJIS.contains(request.getEmoji())) {
            throw new BusinessException(BulletinErrorCode.INVALID_EMOJI);
        }

        // target → スレッド → スコープを解決して所属メンバー検証
        checkTargetMembership(userId, targetType, request.getTargetId());

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
        checkTargetMembership(userId, type, targetId);
        BulletinReactionEntity entity = reactionRepository
                .findByTargetTypeAndTargetIdAndUserIdAndEmoji(type, targetId, userId, emoji)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.REACTION_NOT_FOUND));

        reactionRepository.delete(entity);
        log.info("リアクション削除: targetType={}, targetId={}, userId={}, emoji={}",
                targetType, targetId, userId, emoji);
    }

    /**
     * ターゲットのリアクション一覧を取得する。所属メンバーのみ。
     *
     * @param userId     操作ユーザーID
     * @param targetType ターゲット種別
     * @param targetId   ターゲットID
     * @return リアクションレスポンスリスト
     */
    public List<ReactionResponse> listReactions(Long userId, String targetType, Long targetId) {
        TargetType type = TargetType.valueOf(targetType);
        checkTargetMembership(userId, type, targetId);
        List<BulletinReactionEntity> reactions = reactionRepository.findByTargetTypeAndTargetId(type, targetId);
        return bulletinMapper.toReactionResponseList(reactions);
    }

    /**
     * ターゲットのリアクション集計を取得する。所属メンバーのみ。
     *
     * @param userId     操作ユーザーID
     * @param targetType ターゲット種別
     * @param targetId   ターゲットID
     * @return リアクション集計レスポンスリスト
     */
    public List<ReactionSummaryResponse> getReactionSummary(Long userId, String targetType, Long targetId) {
        TargetType type = TargetType.valueOf(targetType);
        checkTargetMembership(userId, type, targetId);
        List<Object[]> results = reactionRepository.countByTargetGroupedByEmoji(type, targetId);
        return results.stream()
                .map(row -> new ReactionSummaryResponse((String) row[0], (Long) row[1]))
                .toList();
    }

    /**
     * リアクション対象（スレッド/返信）から所属スコープを解決し、所属メンバーであることを検証する。
     *
     * <p>対象が存在しない場合は {@link BulletinErrorCode#THREAD_NOT_FOUND} /
     * {@link BulletinErrorCode#REPLY_NOT_FOUND} を投げる。</p>
     */
    private void checkTargetMembership(Long userId, TargetType targetType, Long targetId) {
        BulletinThreadEntity thread;
        if (targetType == TargetType.THREAD) {
            thread = threadRepository.findById(targetId)
                    .orElseThrow(() -> new BusinessException(BulletinErrorCode.THREAD_NOT_FOUND));
        } else {
            BulletinReplyEntity reply = replyRepository.findById(targetId)
                    .orElseThrow(() -> new BusinessException(BulletinErrorCode.REPLY_NOT_FOUND));
            thread = threadRepository.findById(reply.getThreadId())
                    .orElseThrow(() -> new BusinessException(BulletinErrorCode.THREAD_NOT_FOUND));
        }
        // F08.7.1: 大会/ディビジョン連絡はリアクション付与/削除/閲覧とも canView に委譲する。
        // リアクションは「閲覧者が押せる」のが自然（設計書 §4.1 で閲覧は参加チーム全メンバーに開放）であり、
        // canPost（代表/主催者のみ）まで絞ると一般メンバーがリアクションできず不自然になるため canView を採用する。
        // checkMembership は membership.domain.ScopeType に TOURNAMENT が無く 500 になるため通さない。
        if (isTournamentScope(thread.getScopeType())) {
            tournamentContactAccessService.checkView(
                    toContactScope(thread.getScopeType()), thread.getScopeId(), ContactSpaceKind.BULLETIN, userId);
            return;
        }
        // 村スコープは村ドメインの可視性認可へ委譲する（BulletinAccessGuard は村を判定できない）。
        // リアクションは「閲覧できる者が押せる」のが自然なため、閲覧認可（checkVillageBulletinViewAccess）
        // を採る。近隣の BulletinAttachmentService#checkViewAuthorization と同一の型。
        if (thread.getScopeType() == ScopeType.VILLAGE) {
            villageBulletinAccessService.checkVillageBulletinViewAccess(thread.getScopeVillageId(), userId);
            return;
        }
        accessGuard.checkMembership(userId, thread.getScopeType(), thread.getScopeId());
    }

    /** リアクション対象スレッドが大会/ディビジョン連絡スペースか。 */
    private static boolean isTournamentScope(ScopeType scopeType) {
        return scopeType == ScopeType.TOURNAMENT || scopeType == ScopeType.TOURNAMENT_DIVISION;
    }

    /** bulletin {@link ScopeType} を連絡スペースの {@link ContactSpaceScopeType} に変換する。 */
    private static ContactSpaceScopeType toContactScope(ScopeType scopeType) {
        return scopeType == ScopeType.TOURNAMENT
                ? ContactSpaceScopeType.TOURNAMENT
                : ContactSpaceScopeType.TOURNAMENT_DIVISION;
    }
}
