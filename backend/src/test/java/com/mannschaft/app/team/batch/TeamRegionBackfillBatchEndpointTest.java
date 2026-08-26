package com.mannschaft.app.team.batch;

import com.mannschaft.app.admin.batch.BatchEndpointDescriptor;
import com.mannschaft.app.admin.batch.BatchEndpointRegistry;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.service.TeamRegionNormalizer;
import com.mannschaft.app.team.service.TeamRegionNormalizer.MatchStage;
import com.mannschaft.app.team.service.TeamRegionNormalizer.ResolvedRegion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F22.1 市 Phase 2 足場C 第三陣: {@link TeamRegionBackfillService} の
 * バッチ管理基盤（{@code @BatchEndpoint}）への登録を検証するテスト。
 *
 * <p>{@link BatchEndpointRegistry} の最小コンテナに実サービス（リポジトリ/ノーマライザは Mockito モック）を
 * 登録し、(1) ドライラン/本実行の 2 エンドポイントが名前で登録されること、
 * (2) 両エンドポイントが引数なし（{@link BatchEndpointRegistry#invoke(String)} の制約を満たす）で起動可能なこと、
 * (3) ドライラン起動では {@code save} が一切呼ばれないこと、を検証する。</p>
 *
 * <p>認可（SYSTEM_ADMIN 以外 403）は {@code SecurityConfig} の
 * {@code /api/v1/system-admin/** -> hasRole("SYSTEM_ADMIN")} で保証されるため、
 * ここではバッチ基盤への正しい登録と引数なし制約のみを対象とする。</p>
 */
@DisplayName("TeamRegionBackfill バッチエンドポイント登録テスト（F22.1 第三陣）")
class TeamRegionBackfillBatchEndpointTest {

    @Test
    @DisplayName("ドライラン・本実行の 2 バッチが name で登録される")
    void shouldRegisterBothEndpoints() {
        try (AnnotationConfigApplicationContext ctx = newContext()) {
            BatchEndpointRegistry registry = ctx.getBean(BatchEndpointRegistry.class);

            assertThat(registry.find("team-region-backfill-dryrun")).isPresent();
            assertThat(registry.find("team-region-backfill")).isPresent();

            BatchEndpointDescriptor dryRun = registry.find("team-region-backfill-dryrun").orElseThrow();
            assertThat(dryRun.method().getName()).isEqualTo("runDryRunBatch");
            // @BatchEndpoint は引数なしでなければ invoke できない（運用制約）。
            assertThat(dryRun.method().getParameterCount()).isZero();

            BatchEndpointDescriptor real = registry.find("team-region-backfill").orElseThrow();
            assertThat(real.method().getName()).isEqualTo("runBatch");
            assertThat(real.method().getParameterCount()).isZero();
        }
    }

    @Test
    @DisplayName("ドライラン起動（invoke）では save を一切呼ばない")
    void dryRunBatch_invoke_doesNotSave() {
        try (AnnotationConfigApplicationContext ctx = newContext()) {
            TeamRepository teamRepository = ctx.getBean(TeamRepository.class);
            TeamRegionNormalizer normalizer = ctx.getBean(TeamRegionNormalizer.class);
            BatchEndpointRegistry registry = ctx.getBean(BatchEndpointRegistry.class);

            TeamEntity cityHit = TeamEntity.builder()
                    .name("team-1")
                    .prefecture("北海道")
                    .city("函館市")
                    .visibility(TeamEntity.Visibility.PUBLIC)
                    .supporterEnabled(Boolean.FALSE)
                    .build();
            given(teamRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                    .willReturn(new org.springframework.data.domain.PageImpl<>(List.of(cityHit)));
            given(normalizer.normalize("北海道", "函館市"))
                    .willReturn(new ResolvedRegion("01", "01202", MatchStage.CITY));

            registry.invoke("team-region-backfill-dryrun");

            verify(teamRepository, never()).save(any());
        }
    }

    @Test
    @DisplayName("本実行起動（invoke）では解決できた行のみ save する")
    void realBatch_invoke_savesResolved() {
        try (AnnotationConfigApplicationContext ctx = newContext()) {
            TeamRepository teamRepository = ctx.getBean(TeamRepository.class);
            TeamRegionNormalizer normalizer = ctx.getBean(TeamRegionNormalizer.class);
            BatchEndpointRegistry registry = ctx.getBean(BatchEndpointRegistry.class);

            TeamEntity cityHit = TeamEntity.builder()
                    .name("team-1")
                    .prefecture("北海道")
                    .city("函館市")
                    .visibility(TeamEntity.Visibility.PUBLIC)
                    .supporterEnabled(Boolean.FALSE)
                    .build();
            given(teamRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                    .willReturn(new org.springframework.data.domain.PageImpl<>(List.of(cityHit)));
            given(normalizer.normalize("北海道", "函館市"))
                    .willReturn(new ResolvedRegion("01", "01202", MatchStage.CITY));

            registry.invoke("team-region-backfill");

            verify(teamRepository).save(cityHit);
            assertThat(cityHit.getPrefectureCode()).isEqualTo("01");
            assertThat(cityHit.getCityCode()).isEqualTo("01202");
        }
    }

    private static AnnotationConfigApplicationContext newContext() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(TestConfig.class);
        ctx.refresh();
        return ctx;
    }

    /** Registry + 実サービス（依存はモック）を登録する最小コンテナ設定。 */
    @Configuration
    static class TestConfig {
        @Bean
        public BatchEndpointRegistry batchEndpointRegistry(GenericApplicationContext context) {
            return new BatchEndpointRegistry(context);
        }

        @Bean
        public TeamRepository teamRepository() {
            return Mockito.mock(TeamRepository.class);
        }

        @Bean
        public TeamRegionNormalizer teamRegionNormalizer() {
            return Mockito.mock(TeamRegionNormalizer.class);
        }

        @Bean
        public TeamRegionBackfillService teamRegionBackfillService(
                TeamRepository teamRepository, TeamRegionNormalizer normalizer) {
            return new TeamRegionBackfillService(teamRepository, normalizer);
        }
    }
}
