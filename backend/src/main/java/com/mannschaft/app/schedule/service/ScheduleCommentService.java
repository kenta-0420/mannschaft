package com.mannschaft.app.schedule.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleCommentErrorCode;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.dto.CommentAuthorResponse;
import com.mannschaft.app.schedule.dto.CommentResponse;
import com.mannschaft.app.schedule.dto.CreateCommentRequest;
import com.mannschaft.app.schedule.dto.MentionCandidateResponse;
import com.mannschaft.app.schedule.dto.ThreadMetaResponse;
import com.mannschaft.app.schedule.dto.ThreadSettingsRequest;
import com.mannschaft.app.schedule.dto.ThreadSettingsResponse;
import com.mannschaft.app.schedule.dto.UpdateCommentRequest;
import com.mannschaft.app.schedule.entity.ScheduleCommentEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleCommentRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.visibility.ScheduleCommentViewerFilter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F03.16 予定コメントスレッド サービス（設計書 §4 / §5 / §6）。
 *
 * <p>Controller から呼ばれる公開入口はすべて最初に
 * {@link ScheduleCommentAccessGuard} を通す（§4.5.2）。認可判定を本クラスに
 * 自作しない（写像を二重に持たない・本戦役の一貫方針）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleCommentService {

    private static final int MAX_BODY_LENGTH = 2000;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int EMBEDDED_REPLY_LIMIT = 3;
    private static final int MAX_MENTIONS = 20;
    private static final int MAX_DEPTH = 1;
    private static final String PERMISSION_DELETE_OTHERS_CONTENT = "DELETE_OTHERS_CONTENT";

    private final ScheduleRepository scheduleRepository;
    private final ScheduleCommentRepository scheduleCommentRepository;
    private final ScheduleCommentAccessGuard accessGuard;
    private final ScheduleCommentViewerFilter viewerFilter;
    private final AccessControlService accessControlService;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final ScheduleCommentNotifier notifier;
    private final ScheduleCommentRateLimiter rateLimiter;

    @PersistenceContext
    private EntityManager em;

    // ═════════════════════════════════════════════════════════════════════
    // 一覧
    // ═════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public PagedResponse<CommentResponse> listComments(
            Long scheduleId, int page, int size, String sort, Long viewerId) {
        ScheduleEntity schedule = loadSchedule(scheduleId);
        accessGuard.requireScheduleViewable(viewerId, schedule);

        boolean desc = "createdAt,desc".equalsIgnoreCase(sort);
        int clampedSize = clampSize(size);
        int safePage = Math.max(page, 0);
        String order = desc ? "DESC" : "ASC";

        List<ScheduleCommentEntity> topLevel = em.createQuery(
                        "SELECT c FROM ScheduleCommentEntity c WHERE c.scheduleId = :sid AND c.depth = 0 "
                                + "AND (c.deletedAt IS NULL OR c.replyCount > 0) "
                                + "ORDER BY c.createdAt " + order + ", c.id " + order,
                        ScheduleCommentEntity.class)
                .setParameter("sid", scheduleId)
                .setFirstResult(safePage * clampedSize)
                .setMaxResults(clampedSize)
                .getResultList();

        long total = em.createQuery(
                        "SELECT COUNT(c) FROM ScheduleCommentEntity c WHERE c.scheduleId = :sid AND c.depth = 0 "
                                + "AND (c.deletedAt IS NULL OR c.replyCount > 0)",
                        Long.class)
                .setParameter("sid", scheduleId)
                .getSingleResult();

        Map<UUID, List<ScheduleCommentEntity>> repliesByRoot = fetchEmbeddedReplies(topLevel);
        ModerationContext ctx = buildModerationContext(schedule, viewerId);
        Map<Long, UserEntity> authors = loadAuthors(collectAuthorIds(topLevel, repliesByRoot));

        List<CommentResponse> data = new ArrayList<>(topLevel.size());
        for (ScheduleCommentEntity c : topLevel) {
            data.add(toResponse(c, viewerId, ctx, authors, repliesByRoot.get(c.getId()), true));
        }

        int totalPages = clampedSize == 0 ? 0 : (int) Math.ceil((double) total / clampedSize);
        return PagedResponse.of(data, new PagedResponse.PageMeta(total, safePage, clampedSize, totalPages));
    }

    private Map<UUID, List<ScheduleCommentEntity>> fetchEmbeddedReplies(List<ScheduleCommentEntity> topLevel) {
        if (topLevel.isEmpty()) {
            return Map.of();
        }
        List<UUID> rootIds = topLevel.stream().map(ScheduleCommentEntity::getId).toList();
        List<ScheduleCommentEntity> allReplies = em.createQuery(
                        "SELECT c FROM ScheduleCommentEntity c WHERE c.rootId IN :rootIds AND c.deletedAt IS NULL "
                                + "ORDER BY c.rootId, c.createdAt DESC, c.id DESC",
                        ScheduleCommentEntity.class)
                .setParameter("rootIds", rootIds)
                .getResultList();

        Map<UUID, List<ScheduleCommentEntity>> grouped = new LinkedHashMap<>();
        for (ScheduleCommentEntity reply : allReplies) {
            List<ScheduleCommentEntity> list = grouped.computeIfAbsent(reply.getRootId(), k -> new ArrayList<>());
            if (list.size() < EMBEDDED_REPLY_LIMIT) {
                list.add(reply);
            }
        }
        for (Map.Entry<UUID, List<ScheduleCommentEntity>> e : grouped.entrySet()) {
            Collections.reverse(e.getValue());
        }
        return grouped;
    }

    // ═════════════════════════════════════════════════════════════════════
    // meta
    // ═════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public ThreadMetaResponse getMeta(Long scheduleId, Long viewerId) {
        ScheduleEntity schedule = loadSchedule(scheduleId);
        accessGuard.requireScheduleViewable(viewerId, schedule);

        boolean commentsEnabled = Boolean.TRUE.equals(schedule.getCommentsEnabled());
        boolean cancelled = schedule.getStatus() == ScheduleStatus.CANCELLED;
        String reason = null;
        boolean canPost;
        if (cancelled) {
            canPost = false;
            reason = "CANCELLED";
        } else if (!commentsEnabled) {
            canPost = false;
            reason = "CLOSED";
        } else if (!canPostRole(viewerId, schedule)) {
            canPost = false;
            reason = "ROLE";
        } else {
            canPost = true;
        }

        return ThreadMetaResponse.builder()
                .scheduleId(scheduleId)
                .commentsEnabled(commentsEnabled)
                .canPost(canPost)
                .canPostReason(reason)
                .build();
    }

    private boolean canPostRole(Long viewerId, ScheduleEntity schedule) {
        if (viewerId == null) {
            return false;
        }
        if (accessControlService.isSystemAdmin(viewerId)) {
            return true;
        }
        String roleName = accessControlService.resolveEffectiveRoleName(
                viewerId, ScheduleCommentViewerFilter.scopeIdOf(schedule), ScheduleCommentViewerFilter.scopeTypeOf(schedule));
        return com.mannschaft.app.common.visibility.RolePriority.isAtLeast(roleName, "SUPPORTER");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 返信一覧
    // ═════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public PagedResponse<CommentResponse> listReplies(
            Long scheduleId, String commentIdRaw, int page, int size, String sort, Long viewerId) {
        ScheduleEntity schedule = loadSchedule(scheduleId);
        accessGuard.requireScheduleViewable(viewerId, schedule);

        UUID commentId = parseCommentId(commentIdRaw);
        // トゥームストーン（削除済みトップレベル）でも 200（§4.4）。findByIdAndScheduleId は削除済みも含む。
        ScheduleCommentEntity parent = scheduleCommentRepository.findByIdAndScheduleId(commentId, scheduleId)
                .orElseThrow(() -> new BusinessException(ScheduleCommentErrorCode.COMMENT_NOT_FOUND));
        if (!parent.isTopLevel()) {
            // 返信そのものに対する replies 取得は不正（返信に返信はぶら下がらない）。
            throw new BusinessException(ScheduleCommentErrorCode.INVALID_HIERARCHY);
        }

        boolean desc = "createdAt,desc".equalsIgnoreCase(sort);
        int clampedSize = clampSize(size);
        int safePage = Math.max(page, 0);
        String order = desc ? "DESC" : "ASC";

        List<ScheduleCommentEntity> replies = em.createQuery(
                        "SELECT c FROM ScheduleCommentEntity c WHERE c.rootId = :rid AND c.deletedAt IS NULL "
                                + "ORDER BY c.createdAt " + order + ", c.id " + order,
                        ScheduleCommentEntity.class)
                .setParameter("rid", commentId)
                .setFirstResult(safePage * clampedSize)
                .setMaxResults(clampedSize)
                .getResultList();

        long total = em.createQuery(
                        "SELECT COUNT(c) FROM ScheduleCommentEntity c WHERE c.rootId = :rid AND c.deletedAt IS NULL",
                        Long.class)
                .setParameter("rid", commentId)
                .getSingleResult();

        ModerationContext ctx = buildModerationContext(schedule, viewerId);
        Map<Long, UserEntity> authors = loadAuthors(collectAuthorIds(replies, Map.of()));

        List<CommentResponse> data = new ArrayList<>(replies.size());
        for (ScheduleCommentEntity c : replies) {
            data.add(toResponse(c, viewerId, ctx, authors, null, false));
        }
        int totalPages = clampedSize == 0 ? 0 : (int) Math.ceil((double) total / clampedSize);
        return PagedResponse.of(data, new PagedResponse.PageMeta(total, safePage, clampedSize, totalPages));
    }

    // ═════════════════════════════════════════════════════════════════════
    // メンション候補
    // ═════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<MentionCandidateResponse> mentionCandidates(Long scheduleId, String q, int size, Long viewerId) {
        ScheduleEntity schedule = loadSchedule(scheduleId);
        accessGuard.requireScheduleViewable(viewerId, schedule);
        accessGuard.requirePostableRoleForMentionCandidates(viewerId, schedule);

        int limit = clampSize(size);

        // 1) 母集団の取得（親スコープの直属メンバー）。
        String scopeType = ScheduleCommentViewerFilter.scopeTypeOf(schedule);
        Long scopeId = ScheduleCommentViewerFilter.scopeIdOf(schedule);
        List<Long> populationIds;
        if (scopeType == null || scopeId == null) {
            populationIds = List.of();
        } else {
            ScopeType scope = "TEAM".equals(scopeType) ? ScopeType.TEAM : ScopeType.ORGANIZATION;
            List<MembershipEntity> memberships = membershipRepository.findAllActiveByScope(scope, scopeId);
            Set<Long> ids = new LinkedHashSet<>();
            for (MembershipEntity m : memberships) {
                // 自分自身は候補から除外する（設計書 §4.4・殿の裁定 2026-08-12）。
                // 自分をメンションする意味は薄く、AC-24「自己メンションは通知しない」とも整合する。
                if (m.getUserId() != null && !m.getUserId().equals(viewerId)) {
                    ids.add(m.getUserId());
                }
            }
            populationIds = new ArrayList<>(ids);
        }
        if (populationIds.isEmpty()) {
            return List.of();
        }

        // 2) q による絞り込み・3) 可視性フィルタ（size で切る前に必ず行う・AC-40）。
        Set<Long> visible = viewerFilter.filterViewers(schedule, populationIds);
        if (visible.isEmpty()) {
            return List.of();
        }
        Map<Long, UserEntity> users = loadAuthors(visible);

        String needle = (q == null) ? null : q.trim();
        List<UserEntity> filtered = new ArrayList<>();
        for (Long id : visible) {
            UserEntity user = users.get(id);
            if (user == null) {
                continue;
            }
            if (needle == null || needle.isEmpty()
                    || (user.getDisplayName() != null && user.getDisplayName().contains(needle))) {
                filtered.add(user);
            }
        }

        // 4) 並び替え: 前方一致優先 → 表示名昇順 → user_id 昇順。
        String finalNeedle = (needle == null) ? "" : needle;
        filtered.sort((a, b) -> {
            boolean aPrefix = !finalNeedle.isEmpty() && a.getDisplayName() != null
                    && a.getDisplayName().startsWith(finalNeedle);
            boolean bPrefix = !finalNeedle.isEmpty() && b.getDisplayName() != null
                    && b.getDisplayName().startsWith(finalNeedle);
            if (aPrefix != bPrefix) {
                return aPrefix ? -1 : 1;
            }
            String an = a.getDisplayName() == null ? "" : a.getDisplayName();
            String bn = b.getDisplayName() == null ? "" : b.getDisplayName();
            int byName = an.compareTo(bn);
            if (byName != 0) {
                return byName;
            }
            return a.getId().compareTo(b.getId());
        });

        // 5) size で切る。
        List<MentionCandidateResponse> result = new ArrayList<>();
        for (UserEntity user : filtered) {
            if (result.size() >= limit) {
                break;
            }
            result.add(MentionCandidateResponse.builder()
                    .userId(user.getId())
                    .displayName(user.getDisplayName())
                    .avatarUrl(user.getAvatarUrl())
                    .build());
        }
        return result;
    }

    // ═════════════════════════════════════════════════════════════════════
    // 投稿
    // ═════════════════════════════════════════════════════════════════════

    @Transactional
    public CommentResponse createComment(Long scheduleId, Long userId, CreateCommentRequest request) {
        ScheduleEntity schedule = loadSchedule(scheduleId);
        accessGuard.requirePostable(userId, schedule);
        rateLimiter.requireWithinLimit(userId);

        String body = validateBody(request.getBody());
        List<Long> mentionedUserIds = validateMentions(request.getMentionedUserIds());

        UUID parentId = null;
        UUID rootId = null;
        int depth = 0;
        ScheduleCommentEntity parent = null;
        if (request.getParentId() != null && !request.getParentId().isBlank()) {
            UUID rawParentId = parseCommentIdOrNotFound(request.getParentId());
            parent = scheduleCommentRepository.findByIdAndScheduleIdAndDeletedAtIsNull(rawParentId, scheduleId)
                    .orElseThrow(() -> new BusinessException(ScheduleCommentErrorCode.COMMENT_NOT_FOUND));
            if (!parent.isTopLevel()) {
                throw new BusinessException(ScheduleCommentErrorCode.REPLY_DEPTH_EXCEEDED);
            }
            parentId = parent.getId();
            rootId = parent.getId();
            depth = MAX_DEPTH;
        }

        ScheduleCommentEntity saved = scheduleCommentRepository.save(ScheduleCommentEntity.builder()
                .scheduleId(scheduleId)
                .userId(userId)
                .body(body)
                .parentId(parentId)
                .rootId(rootId)
                .depth(depth)
                .build());

        if (parent != null) {
            parent.incrementReplyCount();
            scheduleCommentRepository.save(parent);
        }

        Long replyRecipientId = (parent != null && parent.getUserId() != null && !parent.getUserId().equals(userId))
                ? parent.getUserId()
                : null;
        String excerpt = ScheduleCommentNotifier.excerpt(body);
        UUID commentId = saved.getId();
        registerNotificationAfterCommit(schedule, commentId, userId, mentionedUserIds, replyRecipientId, excerpt);

        ModerationContext ctx = buildModerationContext(schedule, userId);
        Map<Long, UserEntity> authors = loadAuthors(userId == null ? Set.of() : Set.of(userId));
        return toResponse(saved, userId, ctx, authors, null, depth == 0);
    }

    private void registerNotificationAfterCommit(
            ScheduleEntity schedule, UUID commentId, Long actorId,
            List<Long> mentionedUserIds, Long replyRecipientId, String excerpt) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        Set<Long> mentionSet = new LinkedHashSet<>(mentionedUserIds == null ? List.of() : mentionedUserIds);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    notifier.notify(schedule, commentId, actorId, mentionSet, replyRecipientId, excerpt);
                } catch (Exception e) {
                    log.error("SCHEDULE_COMMENT 通知の発火に失敗（投稿の巻き戻しはしない）: scheduleId={}, commentId={}",
                            schedule.getId(), commentId, e);
                }
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    // 編集
    // ═════════════════════════════════════════════════════════════════════

    @Transactional
    public CommentResponse updateComment(Long scheduleId, String commentIdRaw, Long userId, UpdateCommentRequest request) {
        ScheduleEntity schedule = loadSchedule(scheduleId);
        UUID commentId = parseCommentIdOrNotFound(commentIdRaw);
        ScheduleCommentEntity comment = scheduleCommentRepository.findByIdAndScheduleIdAndDeletedAtIsNull(commentId, scheduleId)
                .orElseThrow(() -> new BusinessException(ScheduleCommentErrorCode.COMMENT_NOT_FOUND));

        accessGuard.requireEditable(userId, schedule, comment);
        comment.editBody(validateBody(request.getBody()));
        ScheduleCommentEntity saved = scheduleCommentRepository.save(comment);

        ModerationContext ctx = buildModerationContext(schedule, userId);
        Map<Long, UserEntity> authors = loadAuthors(userId == null ? Set.of() : Set.of(userId));
        return toResponse(saved, userId, ctx, authors, null, saved.isTopLevel());
    }

    // ═════════════════════════════════════════════════════════════════════
    // 削除
    // ═════════════════════════════════════════════════════════════════════

    @Transactional
    public void deleteComment(Long scheduleId, String commentIdRaw, Long userId) {
        ScheduleEntity schedule = loadSchedule(scheduleId);
        UUID commentId = parseCommentIdOrNotFound(commentIdRaw);
        ScheduleCommentEntity comment = scheduleCommentRepository.findByIdAndScheduleIdAndDeletedAtIsNull(commentId, scheduleId)
                .orElseThrow(() -> new BusinessException(ScheduleCommentErrorCode.COMMENT_NOT_FOUND));

        accessGuard.requireDeletable(userId, schedule, comment);
        comment.softDelete();
        scheduleCommentRepository.save(comment);

        if (!comment.isTopLevel() && comment.getRootId() != null) {
            scheduleCommentRepository.findByIdAndScheduleId(comment.getRootId(), scheduleId)
                    .ifPresent(root -> {
                        root.decrementReplyCount();
                        scheduleCommentRepository.save(root);
                    });
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // スレッド開閉
    // ═════════════════════════════════════════════════════════════════════

    @Transactional
    public ThreadSettingsResponse updateSettings(Long scheduleId, Long userId, ThreadSettingsRequest request) {
        ScheduleEntity schedule = loadSchedule(scheduleId);
        accessGuard.requireThreadSettingsManageable(userId, schedule);

        boolean enabled = Boolean.TRUE.equals(request.getCommentsEnabled());
        schedule.setCommentsEnabled(enabled);
        scheduleRepository.save(schedule);

        return ThreadSettingsResponse.builder().scheduleId(scheduleId).commentsEnabled(enabled).build();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 共通ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private ScheduleEntity loadSchedule(Long scheduleId) {
        if (scheduleId == null) {
            return null;
        }
        return scheduleRepository.findById(scheduleId).orElse(null);
    }

    /**
     * {@code commentId} を UUID としてパースする（存在秘匿の一貫性・§7.3 AC-41）。
     * 形式不正・不在のいずれも 404 に倒す（400 との差から ID 形式を推測させない）。
     */
    private UUID parseCommentId(String raw) {
        return parseCommentIdOrNotFound(raw);
    }

    private UUID parseCommentIdOrNotFound(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (Exception e) {
            throw new BusinessException(ScheduleCommentErrorCode.COMMENT_NOT_FOUND);
        }
    }

    private int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private String validateBody(String rawBody) {
        // trim() は ASCII 空白（  以下）しか除去せず、全角スペース（U+3000）を
        // 残したまま isEmpty()=false になる（AC-21 が撃ち抜く回帰）。strip() は
        // Character.isWhitespace() 基準で判定し、全角スペースも正しく空白として扱う。
        String stripped = rawBody == null ? "" : rawBody.strip();
        if (stripped.isEmpty() || rawBody == null || rawBody.length() > MAX_BODY_LENGTH) {
            throw new BusinessException(ScheduleCommentErrorCode.INVALID_BODY);
        }
        return rawBody;
    }

    private List<Long> validateMentions(List<Long> mentionedUserIds) {
        if (mentionedUserIds == null) {
            return List.of();
        }
        List<Long> dedup = new ArrayList<>(new LinkedHashSet<>(mentionedUserIds));
        if (dedup.size() > MAX_MENTIONS) {
            throw new BusinessException(ScheduleCommentErrorCode.TOO_MANY_MENTIONS);
        }
        return dedup;
    }

    private Set<Long> collectAuthorIds(List<ScheduleCommentEntity> primary, Map<UUID, List<ScheduleCommentEntity>> repliesByRoot) {
        Set<Long> ids = new HashSet<>();
        for (ScheduleCommentEntity c : primary) {
            if (c.getUserId() != null) {
                ids.add(c.getUserId());
            }
        }
        for (List<ScheduleCommentEntity> replies : repliesByRoot.values()) {
            for (ScheduleCommentEntity c : replies) {
                if (c.getUserId() != null) {
                    ids.add(c.getUserId());
                }
            }
        }
        return ids;
    }

    private Map<Long, UserEntity> loadAuthors(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserEntity> map = new LinkedHashMap<>();
        for (UserEntity u : userRepository.findByIdIn(ids)) {
            map.put(u.getId(), u);
        }
        return map;
    }

    private ModerationContext buildModerationContext(ScheduleEntity schedule, Long viewerId) {
        if (viewerId == null || schedule == null) {
            return new ModerationContext(false, false);
        }
        boolean moderator = accessGuard.isModerator(viewerId, schedule);
        boolean deleteOthers = moderator
                || ("TEAM".equals(ScheduleCommentViewerFilter.scopeTypeOf(schedule))
                        && accessControlService.hasPermission(
                                viewerId, ScheduleCommentViewerFilter.scopeIdOf(schedule), "TEAM",
                                PERMISSION_DELETE_OTHERS_CONTENT));
        return new ModerationContext(moderator, deleteOthers);
    }

    private CommentResponse toResponse(
            ScheduleCommentEntity c, Long viewerId, ModerationContext ctx, Map<Long, UserEntity> authors,
            List<ScheduleCommentEntity> embeddedReplies, boolean includeRepliesField) {
        boolean deleted = c.isDeleted();
        // トゥームストーン判定は「表示述語」ではなく個別行の deleted_at そのもの。
        // 一覧クエリは既にトゥームストーン述語を満たす行のみ渡してくるため、ここでは deleted_at の有無だけを見る。
        CommentAuthorResponse author = null;
        String body = null;
        if (!deleted) {
            body = c.getBody();
            if (c.getUserId() != null) {
                UserEntity user = authors.get(c.getUserId());
                if (user != null) {
                    author = CommentAuthorResponse.builder()
                            .userId(user.getId())
                            .displayName(user.getDisplayName())
                            .avatarUrl(user.getAvatarUrl())
                            .build();
                }
            }
        }

        boolean canEdit = !deleted && viewerId != null && c.getUserId() != null && viewerId.equals(c.getUserId());
        boolean canDelete = !deleted && viewerId != null
                && (viewerId.equals(c.getUserId()) || ctx.moderator() || ctx.deleteOthers());

        List<CommentResponse> repliesOut = null;
        if (includeRepliesField && c.isTopLevel()) {
            if (embeddedReplies != null) {
                List<CommentResponse> list = new ArrayList<>(embeddedReplies.size());
                for (ScheduleCommentEntity r : embeddedReplies) {
                    list.add(toResponse(r, viewerId, ctx, authors, null, false));
                }
                repliesOut = list;
            } else {
                repliesOut = List.of();
            }
        }

        return CommentResponse.builder()
                .id(c.getId())
                .scheduleId(c.getScheduleId())
                .parentId(c.getParentId())
                .rootId(c.getRootId())
                .depth(c.getDepth() == null ? 0 : c.getDepth())
                .body(body)
                .edited(Boolean.TRUE.equals(c.getIsEdited()))
                .deleted(deleted)
                .replyCount(c.getReplyCount() == null ? 0 : c.getReplyCount())
                .author(author)
                .canEdit(canEdit)
                .canDelete(canDelete)
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .replies(repliesOut)
                .build();
    }

    private record ModerationContext(boolean moderator, boolean deleteOthers) {
    }
}
