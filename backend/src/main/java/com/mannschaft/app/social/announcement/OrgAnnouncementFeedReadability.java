package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 組織告知の一覧に出してよい行を、<b>本文プレビューが 404 にならない行だけ</b>に絞る（一覧と本文の可否を一致させる。CMP-261011-0035）。
 * <b>トランザクションを持たず、{@code @Transactional} のクラス・メソッドからは呼ばない</b>
 * （{@link OrgAnnouncementAudienceAccess} と同じ。ArchUnit F04）。
 *
 * <p>プレビュー（{@link AnnouncementPreviewService}）と同じ 2 段で判定する。</p>
 * <ol>
 *   <li><b>告知の資格</b> — {@link OrgAnnouncementAudienceAccess#evaluateAll}（直属資格 Qd／宛先資格 Qa）。</li>
 *   <li><b>元コンテンツの可視性</b> — 元がブログ・掲示板の告知は、直属経路なら閲覧者本人の役割で、委任経路なら委任役割
 *       （"MEMBER" / "SUPPORTER"）に差し替えて F00 を評価する。直属で読めなければ、委任でも読めるときだけ委任経路で出す
 *       （プレビューの「直属が既知の拒否で、Qa でも読めるなら委任経路で読み直す」と同じ）。F00 の判定は
 *       {@link ContentVisibilityChecker#filterAccessible}／{@link ContentVisibilityChecker#filterAccessibleDelegated} の一括版で行い、
 *       SQL 本数は告知の件数に比例しない（元の種別 2 × （直属 1＋委任役割 2）回の F00 一括評価が上限）。</li>
 * </ol>
 *
 * <p>元がブログ・掲示板以外の告知は、プレビューが 404 ではなく別のエラーになる種別であり、元コンテンツの可視性による絞り込みは
 * 行わない（告知の資格だけで判定する）。</p>
 */
@Component
@RequiredArgsConstructor
public class OrgAnnouncementFeedReadability {

    private final OrgAnnouncementAudienceAccess audienceAccess;
    private final ContentVisibilityChecker visibilityChecker;

    /**
     * 閲覧者が本文プレビューを開ける行だけを、入力の順序のまま返す。
     *
     * @param feeds    同じ組織の組織告知
     * @param viewerId 閲覧者
     */
    public List<AnnouncementFeedEntity> filterReadable(List<AnnouncementFeedEntity> feeds, Long viewerId) {
        if (feeds == null || feeds.isEmpty()) {
            return List.of();
        }
        Map<Long, OrgAnnouncementAudienceAccess.Access> access = audienceAccess.evaluateAll(feeds, viewerId);

        // 元コンテンツの可視性を評価する対象（元の種別 × 経路）を集める
        Map<ReferenceType, Set<Long>> directIds = new HashMap<>();
        Map<String, Map<ReferenceType, Set<Long>>> delegatedIds = new HashMap<>();
        for (AnnouncementFeedEntity feed : feeds) {
            OrgAnnouncementAudienceAccess.Access a = access.get(feed.getId());
            ReferenceType type = referenceTypeOf(feed);
            if (a == null || !a.allowed() || type == null || feed.getSourceId() == null) {
                continue;
            }
            if (a.directAllowed()) {
                directIds.computeIfAbsent(type, k -> new HashSet<>()).add(feed.getSourceId());
            }
            if (a.delegatedAllowed() && a.delegatedRole() != null) {
                delegatedIds.computeIfAbsent(a.delegatedRole(), k -> new HashMap<>())
                        .computeIfAbsent(type, k -> new HashSet<>()).add(feed.getSourceId());
            }
        }
        Map<ReferenceType, Set<Long>> directReadable = new HashMap<>();
        directIds.forEach((type, ids) ->
                directReadable.put(type, visibilityChecker.filterAccessible(type, ids, viewerId)));
        Map<String, Map<ReferenceType, Set<Long>>> delegatedReadable = new HashMap<>();
        delegatedIds.forEach((role, byType) -> byType.forEach((type, ids) ->
                delegatedReadable.computeIfAbsent(role, k -> new HashMap<>())
                        .put(type, visibilityChecker.filterAccessibleDelegated(type, ids, viewerId, role))));

        List<AnnouncementFeedEntity> readable = new ArrayList<>();
        for (AnnouncementFeedEntity feed : feeds) {
            OrgAnnouncementAudienceAccess.Access a = access.get(feed.getId());
            // 結果に含まれない ID は不許可（fail-closed）
            if (a == null || !a.allowed()) {
                continue;
            }
            ReferenceType type = referenceTypeOf(feed);
            if (type == null) {
                readable.add(feed);
                continue;
            }
            Long sourceId = feed.getSourceId();
            boolean direct = a.directAllowed()
                    && directReadable.getOrDefault(type, Set.of()).contains(sourceId);
            boolean delegated = a.delegatedAllowed() && a.delegatedRole() != null
                    && delegatedReadable.getOrDefault(a.delegatedRole(), Map.of())
                            .getOrDefault(type, Set.of()).contains(sourceId);
            if (direct || delegated) {
                readable.add(feed);
            }
        }
        return readable;
    }

    /** 元コンテンツの F00 種別。プレビューが本文を返す種別（ブログ・掲示板）以外は null。 */
    private static ReferenceType referenceTypeOf(AnnouncementFeedEntity feed) {
        if (feed.getSourceType() == AnnouncementSourceType.BLOG_POST) {
            return ReferenceType.BLOG_POST;
        }
        if (feed.getSourceType() == AnnouncementSourceType.BULLETIN_THREAD) {
            return ReferenceType.BULLETIN_THREAD;
        }
        return null;
    }
}
