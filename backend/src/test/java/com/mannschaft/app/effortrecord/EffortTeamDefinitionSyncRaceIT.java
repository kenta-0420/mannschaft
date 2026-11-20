package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.service.ActivityTemplateService;
import com.mannschaft.app.effortrecord.service.EffortTeamDefinitionResolver;
import com.mannschaft.app.effortrecord.service.EffortTeamDefinitionTxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * F06.4.1 PR-2 同期の巻き戻し防止（Codex 検分指摘）。要求 A が activity の旧定義を読んだ後、要求 B が新しい定義を
 * 同期して revision を確定しても、A が旧定義で新しい revision を上書きしてはならない（定義行の行ロック取得後に
 * activity の現定義を読み直す）。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 チーム定義の同期（古い activity 定義による巻き戻し防止）")
class EffortTeamDefinitionSyncRaceIT extends EffortRecordItSupport {

    @Autowired private EffortTeamDefinitionResolver resolver;
    @MockitoSpyBean private ActivityTemplateService activityTemplateSpy;

    @Test
    @DisplayName("旧定義を読んだ要求 A が、後から確定した新 revision を巻き戻さない")
    void 古いactivity定義で新revisionを上書きしない() throws Exception {
        Long team = team("競合同期");
        Long admin = user("競合管理者");
        teamAdmin(admin, team);
        Long template = teamTemplate(admin, team, "練習", List.of(FieldSpec.number("reps", "回数", 0)));
        EffortTeamDefinitionTxService.Current first = resolver.sync(team, template);
        assertThat(first.revision()).isEqualTo(1);

        CountDownLatch readOld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean firstReadOfA = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if ("race-A".equals(Thread.currentThread().getName()) && firstReadOfA.compareAndSet(true, false)) {
                readOld.countDown();
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(activityTemplateSpy).findInScope(anyLong(), any(ActivityScopeType.class), anyLong());

        ExecutorService pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "race-A"));
        try {
            Future<EffortTeamDefinitionTxService.Current> a = pool.submit(() -> resolver.sync(team, template));
            assertThat(readOld.await(30, TimeUnit.SECONDS)).as("A が旧定義を読んだ").isTrue();

            // B: テンプレートに項目を足して同期（revision 2 を確定）。
            updateTeamTemplate(admin, template, "練習", List.of(FieldSpec.number("reps", "回数", 0),
                    FieldSpec.text("memo", "追加メモ", 1)));
            EffortTeamDefinitionTxService.Current b = resolver.sync(team, template);
            assertThat(b.revision()).isEqualTo(2);

            release.countDown();
            EffortTeamDefinitionTxService.Current aResult = a.get(60, TimeUnit.SECONDS);
            assertThat(aResult.revision()).as("A は B の確定内容に収束する").isEqualTo(2);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        String uuid = first.definitionUuid().toString();
        assertThat(revisionCount(uuid)).as("旧定義で revision 3 を発行しない").isEqualTo(2);
        JsonNode latest = storedRevision(uuid, 2);
        assertThat(fieldOf(latest, "追加メモ").path("fieldKey").asText()).isNotEmpty();
        assertThat(resolver.sync(team, template).snapshot().fields()).anyMatch(f -> "追加メモ".equals(f.label()));
    }
}
