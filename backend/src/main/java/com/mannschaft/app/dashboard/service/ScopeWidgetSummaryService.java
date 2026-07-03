package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * F22.1 第二波: チーム/組織パネルの厳選ウィジェット用サマリ生成サービス（dashboard ドメイン）。
 *
 * <p>ブログ（④）・チャット（⑤）・カレンダー（⑥）のサマリと、組織スコープの
 * 今後の予定（①）・タイムライン（②）・掲示板（③）を生成する。各ドメインの既存
 * Repository / Service を流用し、key は新設・実装は再利用（04 §2.1 判断記録）。</p>
 *
 * <p>JSON 形状は設計書 02 §3.3 と FE 型 {@code dashboard-scope.ts} に合わせる。各 Map の
 * キーは既存ダッシュボードレスポンスと同じ snake_case で揃える。実体が無い場合は
 * 空配列 + 0 を正直に返す（フラグで握り潰さない・障害対応の原則）。</p>
 *
 * <p>設計書: docs/features/F22.1_swipe_scope_dashboard/02_api_design.md §3.3 /
 * 04_widgets.md §3 / §4</p>
 */
@Service
@RequiredArgsConstructor
public class ScopeWidgetSummaryService {

    private final BlogPostRepository blogPostRepository;
    private final ChatChannelRepository chatChannelRepository;
    private final ChatChannelMemberRepository chatChannelMemberRepository;
    private final ScheduleRepository scheduleRepository;
    private final TimelinePostRepository timelinePostRepository;
    private final BulletinThreadRepository bulletinThreadRepository;
    private final BulletinReadStatusRepository bulletinReadStatusRepository;
    private final NameResolverService nameResolverService;

    /** 直近アイテムの件数（02 §3.3: ブログ/タイムライン等 直近 3 件）。 */
    private static final int RECENT_LIMIT = 3;
    /** チャットチャンネルのサマリ件数（02 §3.3: 直近 3 チャンネル）。 */
    private static final int CHAT_CHANNEL_LIMIT = 3;
    /** 掲示板スレッドのスキャン上限。 */
    private static final int BULLETIN_SCAN_LIMIT = 100;

    // ─────────────────────────────────────────────
    // ④ ブログ（チーム / 組織共通形）
    // ─────────────────────────────────────────────

    /**
     * 指定スコープの直近公開ブログ記事サマリ（直近 3 件）を返す。
     *
     * @param scopeType {@code "TEAM"} / {@code "ORGANIZATION"}
     * @param scopeId   スコープ ID
     * @return id / title / author / published_at を含む Map のリスト（記事なしは空配列）
     */
    public List<Map<String, Object>> buildLatestBlogPosts(String scopeType, Long scopeId) {
        PageRequest top = PageRequest.of(0, RECENT_LIMIT);
        Page<BlogPostEntity> page = isOrganization(scopeType)
                ? blogPostRepository.findByOrganizationIdAndStatusOrderByPinnedDescPublishedAtDesc(
                        scopeId, PostStatus.PUBLISHED, top)
                : blogPostRepository.findByTeamIdAndStatusOrderByPinnedDescPublishedAtDesc(
                        scopeId, PostStatus.PUBLISHED, top);

        List<BlogPostEntity> posts = page.getContent();
        // 著者名をバッチ解決（N+1 回避）
        List<Long> authorIds = posts.stream()
                .map(BlogPostEntity::getAuthorId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> authorNames = authorIds.isEmpty()
                ? Map.of()
                : nameResolverService.resolveUserDisplayNames(authorIds);

        List<Map<String, Object>> result = new ArrayList<>(posts.size());
        for (BlogPostEntity p : posts) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", p.getId());
            map.put("title", p.getTitle());
            map.put("author", p.getAuthorId() != null ? authorNames.get(p.getAuthorId()) : null);
            map.put("published_at", p.getPublishedAt());
            result.add(map);
        }
        return result;
    }

    // ─────────────────────────────────────────────
    // ⑤ チャット（チーム / 組織共通形）
    // ─────────────────────────────────────────────

    /**
     * 指定スコープのチャットサマリを返す。
     *
     * <p>形状: {@code { total_unread, channels:[{id,name,unread_count,last_message_preview}]（直近3）}}。
     * 当該ユーザーが参加しているチャンネルのみを未読合計・チャンネル一覧に含める。</p>
     *
     * @param scopeType {@code "TEAM"} / {@code "ORGANIZATION"}
     * @param scopeId   スコープ ID
     * @param userId    閲覧ユーザー ID
     * @return total_unread / channels を含む Map
     */
    public Map<String, Object> buildChatSummary(String scopeType, Long scopeId, Long userId) {
        List<ChatChannelEntity> channels = scopeChannels(isOrganization(scopeType), scopeId);

        long totalUnread = 0;
        List<Map<String, Object>> channelItems = new ArrayList<>();
        for (ChatChannelEntity ch : channels) {
            // 当該ユーザーがメンバーのチャンネルのみ対象（未参加チャンネルは要対応に含めない）
            ChatChannelMemberEntity member =
                    chatChannelMemberRepository.findByChannelIdAndUserId(ch.getId(), userId).orElse(null);
            if (member == null) {
                continue;
            }
            int unread = member.getUnreadCount();
            totalUnread += unread;
            if (channelItems.size() < CHAT_CHANNEL_LIMIT) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", ch.getId());
                map.put("name", ch.getName());
                map.put("unread_count", unread);
                map.put("last_message_preview", ch.getLastMessagePreview());
                channelItems.add(map);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total_unread", totalUnread);
        result.put("channels", channelItems);
        return result;
    }

    // ─────────────────────────────────────────────
    // ⑥ カレンダー（チーム / 組織共通形）
    // ─────────────────────────────────────────────

    /**
     * 指定スコープのカレンダーサマリを返す。
     *
     * <p>形状: {@code { events_today, events_this_week, next_event, days_with_events }}。
     * {@code days_with_events} は当月内でイベントがある日（1〜31）の昇順リスト。
     * {@code next_event} は現在以降の最も近いイベントのタイトル（無ければ null）。</p>
     *
     * @param scopeType {@code "TEAM"} / {@code "ORGANIZATION"}
     * @param scopeId   スコープ ID
     * @param zoneId    閲覧ユーザーのタイムゾーン（並行実行のため呼び出し元で解決して渡す）
     * @return カレンダーサマリ Map
     */
    public Map<String, Object> buildCalendarSummary(String scopeType, Long scopeId, ZoneId zoneId) {
        boolean org = isOrganization(scopeType);
        LocalDate today = LocalDate.now(zoneId != null ? zoneId : ZoneId.of("UTC"));
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.atTime(LocalTime.MAX);
        LocalDateTime weekEnd = todayStart.plusDays(7);
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime monthEnd = monthStart.plusMonths(1);

        long eventsToday = scopeEvents(org, scopeId, todayStart, todayEnd).size();
        long eventsThisWeek = scopeEvents(org, scopeId, todayStart, weekEnd).size();

        // 当月内のイベント日集合
        List<ScheduleEntity> monthEvents = scopeEvents(org, scopeId, monthStart, monthEnd);
        TreeSet<Integer> daysWithEvents = new TreeSet<>();
        for (ScheduleEntity e : monthEvents) {
            if (e.getStartAt() != null) {
                daysWithEvents.add(e.getStartAt().toLocalDate().getDayOfMonth());
            }
        }

        // next_event: 現在以降の最も近いイベントタイトル
        List<ScheduleEntity> upcoming = scopeEvents(org, scopeId, todayStart, todayStart.plusMonths(3));
        String nextEvent = upcoming.stream()
                .filter(e -> e.getStartAt() != null && !e.getStartAt().isBefore(LocalDateTime.now()))
                .findFirst()
                .map(ScheduleEntity::getTitle)
                .orElse(null);

        Map<String, Object> result = new HashMap<>();
        result.put("events_today", eventsToday);
        result.put("events_this_week", eventsThisWeek);
        result.put("next_event", nextEvent);
        result.put("days_with_events", new ArrayList<>(daysWithEvents));
        return result;
    }

    // ─────────────────────────────────────────────
    // ① 今後の予定（組織スコープ・新規）
    // ─────────────────────────────────────────────

    /**
     * 組織スコープの今後の予定（今後 7 日間・最大 10 件）を返す。チーム版と同形。
     *
     * @param orgId 組織 ID
     * @return start_at 昇順の予定 Map リスト
     */
    public List<Map<String, Object>> buildOrgUpcomingEvents(Long orgId) {
        LocalDateTime now = LocalDateTime.now();
        List<ScheduleEntity> events = scheduleRepository
                .findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(orgId, now, now.plusDays(7));
        return events.stream().limit(10).map(this::toScheduleMap).toList();
    }

    // ─────────────────────────────────────────────
    // ② タイムライン（組織スコープ・新規）
    // ─────────────────────────────────────────────

    /**
     * 組織スコープの最新タイムライン投稿（直近 3 件）を返す。チーム版（teamLatestPosts）と同形。
     * 実体が無ければ空配列を正直に返す。
     *
     * @param orgId 組織 ID
     * @return id / content / created_at を含む Map リスト
     */
    public List<Map<String, Object>> buildOrgLatestPosts(Long orgId) {
        List<TimelinePostEntity> posts = timelinePostRepository
                .findFeedByScopeType(PostScopeType.ORGANIZATION, orgId, PageRequest.of(0, RECENT_LIMIT));
        return posts.stream().map(this::toTimelinePostMap).toList();
    }

    // ─────────────────────────────────────────────
    // ③ 掲示板（組織スコープ・新規）
    // ─────────────────────────────────────────────

    /**
     * 組織スコープの未読掲示板スレッド集計を返す。チーム版（teamUnreadThreads）と同形。
     *
     * @param orgId  組織 ID
     * @param userId 閲覧ユーザー ID
     * @return bulletin_count / chat_count を含む Map（chat はスコープ別チャット未読合計）
     */
    public Map<String, Object> buildOrgUnreadThreads(Long orgId, Long userId) {
        Page<BulletinThreadEntity> threads = bulletinThreadRepository
                .findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
                        com.mannschaft.app.bulletin.ScopeType.ORGANIZATION, orgId,
                        PageRequest.of(0, BULLETIN_SCAN_LIMIT));
        long unreadBulletin = 0;
        for (BulletinThreadEntity thread : threads.getContent()) {
            if (!bulletinReadStatusRepository.existsByThreadIdAndUserId(thread.getId(), userId)) {
                unreadBulletin++;
            }
        }

        // 直近スレッド一覧（クエリ順 = isPinned降順→updated_at降順 の先頭3件）を同時に構築する。
        List<Map<String, Object>> threadList = mapRecentThreads(threads.getContent(), userId);

        // 組織スコープのチャット未読合計（当該ユーザーが参加するチャンネルのみ）
        List<ChatChannelEntity> channels = scopeChannels(true, orgId);
        long unreadChat = 0;
        for (ChatChannelEntity ch : channels) {
            ChatChannelMemberEntity member =
                    chatChannelMemberRepository.findByChannelIdAndUserId(ch.getId(), userId).orElse(null);
            if (member != null) {
                unreadChat += member.getUnreadCount();
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bulletin_count", unreadBulletin);
        result.put("chat_count", unreadChat);
        result.put("bulletin_threads", threadList);
        return result;
    }

    /**
     * dashboard-scope-panel-content 第二陣: 指定スコープの直近掲示板スレッド一覧（直近 3 件）を返す。
     *
     * <p>掲示板の「件数のみ」ウィジェットを「直近スレッド一覧」にコンテンツ化するための共通メソッド。
     * 既存クエリ {@code findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc}
     * （isPinned 降順 → updated_at 降順）で取得し、先頭 3 件を {@code {id,title,updated_at,is_read}} の
     * Map へ変換する。nested DTO / record は作らず Map で統一する（同名 record 衝突・OpenAPI nested
     * schema 衝突回避）。実体が無ければ空配列を正直に返す（握り潰さない）。</p>
     *
     * <p>IDOR 注意: {@code scopeType/scopeId} で取得スレッドを当該スコープに限定する。
     * 呼び出し元（DashboardService）が会員コンテキストで動くため、これで当該スコープの会員のみに閉じる。</p>
     *
     * @param scopeType {@code "TEAM"} / {@code "ORGANIZATION"}
     * @param scopeId   スコープ ID
     * @param userId    閲覧ユーザー ID（is_read 判定に使用）
     * @return id / title / updated_at / is_read を含む Map のリスト（直近 3 件・スレッド無しは空配列）
     */
    public List<Map<String, Object>> buildThreadListForScope(String scopeType, Long scopeId, Long userId) {
        com.mannschaft.app.bulletin.ScopeType type = isOrganization(scopeType)
                ? com.mannschaft.app.bulletin.ScopeType.ORGANIZATION
                : com.mannschaft.app.bulletin.ScopeType.TEAM;
        Page<BulletinThreadEntity> threads = bulletinThreadRepository
                .findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
                        type, scopeId, PageRequest.of(0, RECENT_LIMIT));
        return mapRecentThreads(threads.getContent(), userId);
    }

    /**
     * 掲示板スレッドエンティティ列を直近 3 件の表示用 Map リストへ変換する。
     * 入力はクエリ順（isPinned 降順 → updated_at 降順）を前提とし、その順のまま先頭 3 件を採る。
     */
    private List<Map<String, Object>> mapRecentThreads(List<BulletinThreadEntity> threads, Long userId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (BulletinThreadEntity thread : threads) {
            if (result.size() >= RECENT_LIMIT) {
                break;
            }
            Map<String, Object> map = new HashMap<>();
            map.put("id", thread.getId());
            map.put("title", thread.getTitle());
            map.put("updated_at", thread.getUpdatedAt());
            map.put("is_read", bulletinReadStatusRepository.existsByThreadIdAndUserId(thread.getId(), userId));
            result.add(map);
        }
        return result;
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private List<ChatChannelEntity> scopeChannels(boolean org, Long scopeId) {
        return org
                ? chatChannelRepository.findByOrganizationIdAndIsArchivedFalseOrderByLastMessageAtDesc(scopeId)
                : chatChannelRepository.findByTeamIdAndIsArchivedFalseOrderByLastMessageAtDesc(scopeId);
    }

    private List<ScheduleEntity> scopeEvents(boolean org, Long scopeId, LocalDateTime from, LocalDateTime to) {
        return org
                ? scheduleRepository.findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(scopeId, from, to)
                : scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(scopeId, from, to);
    }

    private static boolean isOrganization(String scopeType) {
        return "ORGANIZATION".equalsIgnoreCase(scopeType);
    }

    private Map<String, Object> toScheduleMap(ScheduleEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId());
        map.put("title", entity.getTitle());
        map.put("start_at", entity.getStartAt());
        map.put("end_at", entity.getEndAt());
        map.put("location", entity.getLocation());
        map.put("all_day", entity.getAllDay());
        return map;
    }

    private Map<String, Object> toTimelinePostMap(TimelinePostEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId());
        map.put("content", entity.getContent());
        map.put("created_at", entity.getCreatedAt());
        return map;
    }
}
