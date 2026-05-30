package com.mannschaft.app.publicview.metrics;

import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import com.mannschaft.app.team.repository.TeamRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F19.1 Phase 5: 公開ページ氏名開示 監視メトリクスサービス。
 *
 * <p>Micrometer の {@link MeterRegistry} に対して以下のメトリクスを登録する:</p>
 * <ul>
 *   <li>{@code public.supporter_name_disclosure.real_name_enabled_rate{scope_type=TEAM}}
 *       — PUBLIC かつ未削除チームの REAL_NAME 有効率（0.0〜1.0）</li>
 *   <li>{@code public.supporter_name_disclosure.real_name_enabled_rate{scope_type=ORGANIZATION}}
 *       — PUBLIC かつ未削除組織の REAL_NAME 有効率</li>
 *   <li>{@code public.supporter_name_disclosure.mode_changes{old_mode=X, new_mode=Y, scope_type=Z}}
 *       — モード変更 Counter（{@link #recordModeChange} から記録）</li>
 * </ul>
 *
 * <p>Prometheus エンドポイント ({@code /actuator/prometheus}) 経由で Grafana に公開する。</p>
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6</p>
 */
// TODO: publicviewドメインからteam/organizationドメインのRepositoryを横断参照。将来のイベント駆動化候補
@Service
public class PublicViewMetricsService {

    private final MeterRegistry meterRegistry;

    // TODO: publicviewドメインからteam/organizationドメインのRepositoryを横断参照。将来のイベント駆動化候補
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;

    /**
     * 明示コンストラクタで両リポジトリを {@code @Lazy} 注入し、早期初期化の連鎖を断つ。
     *
     * <p>認可基盤 Phase 2 の {@code @EnableMethodSecurity} 点火に伴う早期初期化で、本 Bean が
     * JPA リポジトリ登録より前に生成されようとして ApplicationContext 起動が失敗するのを防ぐ。
     * {@code @PostConstruct registerGauges()} は Gauge を登録するだけ（DB 問い合わせはスクレイプ時に
     * lambda が遅延実行）なので、{@code @Lazy} プロキシ注入で何ら問題ない。</p>
     *
     * <p>本プロジェクトには {@code lombok.config} が無く {@code @Lazy} がコンストラクタ引数へ伝播しないため、
     * Lombok ではなく明示コンストラクタを用いる（{@link com.mannschaft.app.actionmemo.ActionMemoMetrics} 同様）。</p>
     */
    public PublicViewMetricsService(MeterRegistry meterRegistry,
                                    @Lazy TeamRepository teamRepository,
                                    @Lazy OrganizationRepository organizationRepository) {
        this.meterRegistry = meterRegistry;
        this.teamRepository = teamRepository;
        this.organizationRepository = organizationRepository;
    }

    /**
     * アプリケーション起動時に Gauge を MeterRegistry に登録する。
     *
     * <p>Gauge の計算は呼び出しのたびに DB へ問い合わせを行う lazy evaluation 方式。
     * PUBLIC チームが 0 件の場合はゼロ除算を避けるため 0.0 を返す。</p>
     */
    @PostConstruct
    void registerGauges() {
        // TEAM: REAL_NAME 有効率 Gauge
        Gauge.builder("public.supporter_name_disclosure.real_name_enabled_rate",
                        this, PublicViewMetricsService::calcTeamRealNameRate)
                .description("PUBLIC かつ未削除チームのうち REAL_NAME モードが有効な割合（0.0〜1.0）")
                .tag("scope_type", "TEAM")
                .register(meterRegistry);

        // ORGANIZATION: REAL_NAME 有効率 Gauge
        Gauge.builder("public.supporter_name_disclosure.real_name_enabled_rate",
                        this, PublicViewMetricsService::calcOrganizationRealNameRate)
                .description("PUBLIC かつ未削除組織のうち REAL_NAME モードが有効な割合（0.0〜1.0）")
                .tag("scope_type", "ORGANIZATION")
                .register(meterRegistry);
    }

    /**
     * サポーター氏名表示モード変更を Counter に記録する。
     *
     * <p>{@link com.mannschaft.app.publicview.event.SupporterNameDisclosureChangedEventListener}
     * から呼ばれる。</p>
     *
     * @param oldMode   変更前のモード
     * @param newMode   変更後のモード
     * @param scopeType スコープ種別（{@code "TEAM"} または {@code "ORGANIZATION"}）
     */
    public void recordModeChange(NameDisclosureMode oldMode, NameDisclosureMode newMode,
                                 String scopeType) {
        Counter.builder("public.supporter_name_disclosure.mode_changes")
                .description("サポーター氏名表示モードの変更回数")
                .tag("old_mode", oldMode != null ? oldMode.name() : "UNKNOWN")
                .tag("new_mode", newMode != null ? newMode.name() : "UNKNOWN")
                .tag("scope_type", scopeType != null ? scopeType : "UNKNOWN")
                .register(meterRegistry)
                .increment();
    }

    // ──── Gauge 計算ロジック ─────────────────────────────

    /**
     * PUBLIC チームの REAL_NAME 有効率を計算する。
     *
     * <p>総件数が 0 の場合は「該当チームなし」とみなして 0.0 を返す（誤検知防止）。</p>
     *
     * @return REAL_NAME 有効率（0.0〜1.0）
     */
    @Transactional(readOnly = true)
    double calcTeamRealNameRate() {
        long total = teamRepository.countPublicTeams();
        if (total == 0) {
            return 0.0;
        }
        long realNameCount = teamRepository.countPublicTeamsWithRealName();
        return (double) realNameCount / total;
    }

    /**
     * PUBLIC 組織の REAL_NAME 有効率を計算する。
     *
     * <p>総件数が 0 の場合は「該当組織なし」とみなして 0.0 を返す（誤検知防止）。</p>
     *
     * @return REAL_NAME 有効率（0.0〜1.0）
     */
    @Transactional(readOnly = true)
    double calcOrganizationRealNameRate() {
        long total = organizationRepository.countPublicOrganizations();
        if (total == 0) {
            return 0.0;
        }
        long realNameCount = organizationRepository.countPublicOrganizationsWithRealName();
        return (double) realNameCount / total;
    }
}
