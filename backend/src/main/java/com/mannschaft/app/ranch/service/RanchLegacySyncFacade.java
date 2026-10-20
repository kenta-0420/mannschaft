package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.gamification.service.GamificationRanchBadgeQueryService;
import com.mannschaft.app.ranch.dto.RanchLegacySyncRequest;
import com.mannschaft.app.ranch.dto.RanchLegacySyncResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** auth users lock中に source read、次にRanch writeを順次行い二接続を超えない。 */
@Service
@RequiredArgsConstructor
public class RanchLegacySyncFacade {
    private static final String TYPE = "LEGACY_SYNC";
    private static final String RESOURCE = "/api/v1/me/ranch/collectibles/sync";

    private final UserOperationGuard guard;
    private final RanchLegacySyncReplayReader replay;
    private final GamificationRanchBadgeQueryService badges;
    private final RanchLegacySyncWriter writer;
    private final ObjectMapper json;
    private final RanchCommandHasher hasher = new RanchCommandHasher();

    public RanchLegacySyncResult sync(Long userId, UUID key, RanchLegacySyncRequest request) {
        if (key == null || request == null) throw new IllegalArgumentException("取込命令が不正です");
        byte[] hash = hasher.hash(TYPE, RESOURCE, null, json.valueToTree(request));
        return guard.withActiveUser(userId, () -> {
            var saved = replay.saved(userId, key, hash);
            if (saved.isPresent()) return saved.orElseThrow();
            var source = badges.page(userId, request.afterId());
            return writer.sync(userId, key, hash, request.afterId(), source);
        });
    }
}
