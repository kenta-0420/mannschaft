package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.TargetType;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinCategoryRepository;
import com.mannschaft.app.bulletin.repository.BulletinReactionRepository;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 組織告知の本文プレビューで、宛先チームのメンバー（組織での役割なし）に掲示板スレッドの本文を渡す<b>トランザクションなしの読取部品</b>
 * （ALIC-1 §2.3）。
 *
 * <p>通常の {@link BulletinThreadService#getThread} はクラス全体が {@code @Transactional} で、他ドメインの Repository の組を
 * 凍結ストアに持ち、所属チェック（{@code checkMembership}）も行うため使えない。そこで bulletin 自身の Repository と
 * 投稿者名の公開 Service（{@link NameResolverService}）だけで同じ形のレスポンスを組み立てる。<b>認可は行わない</b>
 * （呼び出し元の {@link BulletinReadFacade} が委任役割での F00 評価と準備中判定を済ませてから呼ぶ）。
 * 呼び出しは {@link BulletinReadFacade} に限る（ArchUnit F02）。スレッドが所有スコープに属さない・不在なら
 * {@link BulletinErrorCode#THREAD_NOT_FOUND}。添付は返さない。</p>
 */
@Component
@RequiredArgsConstructor
public class BulletinAnnouncementPreviewReader {

    private static final String UNKNOWN_USER_DISPLAY_NAME = "不明なユーザー";

    private final BulletinThreadRepository threadRepository;
    private final BulletinCategoryRepository categoryRepository;
    private final BulletinReadStatusRepository readStatusRepository;
    private final BulletinReactionRepository reactionRepository;
    private final BulletinMapper bulletinMapper;
    private final NameResolverService nameResolverService;

    /** 所有スコープ一致のスレッドを投稿者・カテゴリ・リアクション付きで返す（添付なし）。 */
    public ThreadResponse readThread(ScopeType scopeType, Long scopeId, Long threadId, Long viewerId) {
        BulletinThreadEntity entity = threadRepository.findByIdAndScopeTypeAndScopeId(threadId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.THREAD_NOT_FOUND));
        Long authorId = entity.getAuthorId();
        Set<Long> authorIds = authorId == null ? Set.of() : Set.of(authorId);
        Map<Long, String> displayNames = nameResolverService.resolveUserDisplayNames(authorIds);
        Map<Long, String> avatarUrls = nameResolverService.resolveUserAvatarUrls(authorIds);
        BulletinCategoryEntity category = entity.getCategoryId() == null ? null
                : categoryRepository.findById(entity.getCategoryId()).orElse(null);
        boolean read = viewerId != null
                && !readStatusRepository.findReadThreadIds(Set.of(threadId), viewerId).isEmpty();
        Map<String, Integer> reactionSummary = new LinkedHashMap<>();
        for (Object[] row : reactionRepository.countByTargetIdsGroupedByEmoji(TargetType.THREAD, Set.of(threadId))) {
            reactionSummary.put((String) row[1], ((Number) row[2]).intValue());
        }
        List<String> myReactions = new ArrayList<>();
        if (viewerId != null) {
            for (Object[] row : reactionRepository.findUserReactionsByTargetIds(
                    TargetType.THREAD, Set.of(threadId), viewerId)) {
                myReactions.add((String) row[1]);
            }
        }
        ThreadResponse.AuthorDto author = authorId != null
                ? new ThreadResponse.AuthorDto(authorId,
                        displayNames.getOrDefault(authorId, UNKNOWN_USER_DISPLAY_NAME), avatarUrls.get(authorId))
                : new ThreadResponse.AuthorDto(null, null, null);
        return bulletinMapper.toThreadResponse(entity).toBuilder()
                .author(author)
                .categoryName(category != null ? category.getName() : null)
                .categoryColor(category != null ? category.getColor() : null)
                .isRead(read)
                .reactionSummary(reactionSummary)
                .myReactions(myReactions)
                .build();
    }
}
