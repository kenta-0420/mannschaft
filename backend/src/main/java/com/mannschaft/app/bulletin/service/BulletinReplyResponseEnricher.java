package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.dto.ReplyAuthorResponse;
import com.mannschaft.app.bulletin.dto.ReplyResponse;
import com.mannschaft.app.common.NameResolverService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 認可済み返信の投稿者表示情報を業務トランザクションの外で補完する。
 * Controllerが既存Serviceの正常応答に対して呼び、親子をまとめて一括解決する。
 * 他ドメインの名前解決を業務TXへ取り込まないため、Transactionalを付けない。
 */
@Service
@RequiredArgsConstructor
public class BulletinReplyResponseEnricher {
    private static final String UNKNOWN_USER_DISPLAY_NAME = "不明なユーザー";
    private static final ReplyAuthorResponse ANONYMOUS_AUTHOR =
            new ReplyAuthorResponse(null, UNKNOWN_USER_DISPLAY_NAME, null);
    private final NameResolverService nameResolverService;

    public Page<ReplyResponse> enrich(Page<ReplyResponse> replies) {
        if (replies.isEmpty()) return replies;
        Map<Long, ReplyAuthorResponse> authors = resolveAuthors(replies.getContent());
        return replies.map(reply -> enrich(reply, authors));
    }

    public ReplyResponse enrich(ReplyResponse reply) {
        return enrich(reply, resolveAuthors(List.of(reply)));
    }

    /** 同梱される子返信までIDを重複除去し、表示名とアバターをそれぞれ一度だけ取得する。 */
    private Map<Long, ReplyAuthorResponse> resolveAuthors(List<ReplyResponse> replies) {
        Set<Long> authorIds = new LinkedHashSet<>();
        collectAuthorIds(replies, authorIds);
        Map<Long, ReplyAuthorResponse> authors = new HashMap<>();
        if (authorIds.isEmpty()) return authors;
        Map<Long, String> names = nameResolverService.resolveUserDisplayNames(authorIds);
        Map<Long, String> avatars = nameResolverService.resolveUserAvatarUrls(authorIds);
        for (Long id : authorIds) {
            String name = names.get(id);
            // 不在・退会・未設定の投稿者でも返信本文を表示できる既存の代替名を使う。
            if (name == null || name.isBlank()) name = UNKNOWN_USER_DISPLAY_NAME;
            authors.put(id, new ReplyAuthorResponse(id, name, avatars.get(id)));
        }
        return authors;
    }

    private void collectAuthorIds(List<ReplyResponse> replies, Set<Long> authorIds) {
        for (ReplyResponse reply : replies) {
            if (reply.getAuthorId() != null) authorIds.add(reply.getAuthorId());
            if (reply.getChildren() != null) collectAuthorIds(reply.getChildren(), authorIds);
        }
    }

    private ReplyResponse enrich(ReplyResponse reply, Map<Long, ReplyAuthorResponse> authors) {
        List<ReplyResponse> children = reply.getChildren() == null ? List.of()
                : reply.getChildren().stream().map(child -> enrich(child, authors)).toList();
        return reply.toBuilder()
                .author(authors.getOrDefault(reply.getAuthorId(), ANONYMOUS_AUTHOR))
                .children(children)
                .build();
    }
}
