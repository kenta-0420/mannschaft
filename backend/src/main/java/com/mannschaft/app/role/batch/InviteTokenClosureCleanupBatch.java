package com.mannschaft.app.role.batch;

import org.springframework.stereotype.Component;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.service.InviteScopeStateService;
import com.mannschaft.app.role.service.InviteTokenClosureCleanupRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.HashMap;
import java.util.Map;

/** 個人招待の閉鎖イベント取りこぼしを回復する、role書込TXを持たない入口。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InviteTokenClosureCleanupBatch {
    public static final String BATCH_NAME = "invite-token-closure-cleanup";
    private static final int PAGE_SIZE = 500;
    private final InviteTokenRepository tokens;
    private final InviteScopeStateService scopes;
    private final InviteTokenClosureCleanupRunner cleanup;

    private record Scope(String type, Long id) { }

    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "inviteTokenClosureCleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @BatchEndpoint(name = BATCH_NAME, description = "閉鎖前の個人招待の失効取りこぼしを回復する（日次03:40）")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "閉鎖前の招待を解除後も復元せず、イベント未配送時の物理失効を回復するため")
    public void cleanUp() {
        // 開始時の上限IDまでkeysetで進める。期限/使用上限は候補条件にせず、発行継続中も有限で終える。
        long upperId = tokens.findTopByOrderByIdDesc().map(InviteTokenEntity::getId).orElse(0L);
        long afterId = 0;
        while (afterId < upperId) {
            var page = tokens.findByRevokedAtIsNullAndIdGreaterThanAndIdLessThanEqualOrderByIdAsc(
                    afterId, upperId, PageRequest.of(0, PAGE_SIZE));
            if (page.isEmpty()) {
                return;
            }
            Map<Scope, Long> oldest = new HashMap<>();
            for (InviteTokenEntity token : page) {
                Long id = token.getTeamId() != null ? token.getTeamId() : token.getOrganizationId();
                if (id != null) {
                    Scope scope = new Scope(token.getTeamId() != null ? "TEAM" : "ORGANIZATION", id);
                    oldest.merge(scope, token.getScopeClosureGeneration(), Math::min);
                }
            }
            for (var entry : oldest.entrySet()) {
                Scope scope = entry.getKey();
                try {
                    // 自身はTXを持たず、親専属read TX終了後にrole専属write TXへ世代値だけを渡す。
                    scopes.read(scope.type(), scope.id()).ifPresent(state -> {
                        if (entry.getValue() < state.closureGeneration()) {
                            cleanup.revokeBeforeGeneration(scope.type(), scope.id(), state.closureGeneration());
                        }
                    });
                } catch (RuntimeException failure) {
                    log.error("個人招待の回復失効に失敗（次batchで再実行）: scopeType={}, scopeId={}",
                            scope.type(), scope.id(), failure);
                }
            }
            afterId = page.getLast().getId();
        }
    }
}
