package com.mannschaft.app.config;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.landing.dto.PublicStatsResponse;
import com.mannschaft.app.team.service.TeamSearchService;
import com.mannschaft.app.template.dto.LevelAvailabilityResponse;
import com.mannschaft.app.template.dto.ModuleResponse;
import com.mannschaft.app.template.dto.ModuleSummaryResponse;
import com.mannschaft.app.template.dto.OrgModuleResponse;
import com.mannschaft.app.template.dto.TeamModuleResponse;
import com.mannschaft.app.template.dto.TemplateResponse;
import com.mannschaft.app.template.dto.TemplateSummaryResponse;
import com.mannschaft.app.todo.dto.TodoStatusLabelView;
import com.mannschaft.app.visibility.VisibilityTemplateRuleType;
import com.mannschaft.app.visibility.dto.VisibilityTemplateRuleView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Cacheable} の戻り値が<b>本番と同一の Redis(Valkey) シリアライザ</b>で
 * 往復（write → read）しても値が保たれることを検証する番人（issue #2544 の二段番人 (b)）。
 *
 * <h2>なぜこの番人が要るのか — 既存テストでは原理的に捕まらない</h2>
 * <p>
 * {@code application-test.yml} 配下のテストは {@link RedisConfig#testInMemoryCacheManager()}
 * （{@code ConcurrentMapCacheManager}）を使う。これは値の<b>参照をそのまま保持</b>し
 * シリアライズを一度も通らないため、「JSON 往復で値が化ける／復元できない」種類の破綻は
 * 統合テストでも契約テストでも<b>原理的に検出できない</b>。
 * さらに本番では {@link LoggingCacheErrorHandler} が fail-open で復元失敗を WARN に握り潰すので、
 * 「効かないキャッシュ」に静かに戻るだけで誰も気づかない。
 * </p>
 * <p>
 * そこで本番の {@link RedisConfig#redisCacheConfiguration()} から<b>実物の
 * {@link RedisSerializationContext.SerializationPair}</b> を取り出し、
 * キャッシュ対象の戻り値そのものを {@code write()} → {@code read()} して
 * 値の同一性を assert する（実 Redis は不要）。前例は
 * {@code dashboard.service.WidgetVisibilityCacheSerializationTest} および
 * {@code social.dto.TeamFriendViewCacheSerializationTest}。
 * </p>
 *
 * <h2>「型」ではなく「実際に返す具象インスタンス」を往復させること</h2>
 * <p>
 * {@code List<X>} を {@code new ArrayList<>()} で作って往復させれば当然通る。
 * 壊れるのは <b>具象型</b>（{@code java.util.ImmutableCollections$ListN} 等）である。
 * よって本番コードは可変の {@code ArrayList} / {@code LinkedHashMap} を返すよう是正済みであり、
 * 「不変実装を返してはいけない」という<b>静的な</b>不変条件は姉妹番人
 * {@code com.mannschaft.app.common.architecture.CacheableReturnValueShapeGuardTest} が担保する。
 * 本番人は残る「復元はできるが値が化ける／null になる」型（issue #2544 の C 群・D 群）を
 * 実測で潰す役割を持つ。
 * </p>
 *
 * <h2>positive テストとして書く理由</h2>
 * <p>
 * 「旧実装が壊れることを断定する negative テスト」は、{@code new RedisConfig()} 単体
 * （非 Spring 結線）では環境差で再現が一定しないことが前例テストに記録されている。
 * よって本クラスは全て「往復後も値が保たれる」という正方向の不変条件で書く。
 * </p>
 */
@DisplayName("@Cacheable 戻り値の実シリアライザ往復番人 (issue #2544)")
class CacheValueSerializationRoundTripTest {

    /** 本番と同一構成の値シリアライズペアを {@link RedisConfig} から取得する。 */
    private static RedisSerializationContext.SerializationPair<Object> valuePair() {
        return new RedisConfig().redisCacheConfiguration().getValueSerializationPair();
    }

    /** 値ペアでオブジェクトをシリアライズ→デシリアライズする（キャッシュ往復の再現）。 */
    private static Object roundTrip(Object value) {
        RedisSerializationContext.SerializationPair<Object> pair = valuePair();
        ByteBuffer buffer = pair.write(value);
        return pair.read(buffer);
    }

    // ============================================================
    // 前提の実測: なぜ不変コレクションを返してはいけないのか
    // ============================================================

    @Test
    @DisplayName("Stream#toList() の実行時型は ArrayList ではない（B 群是正の根拠）")
    void streamToList_の実行時型は不変実装である() {
        List<String> viaToList = Stream.of("a", "b", "c").toList();

        // ReferencePipeline#toList() は java.util.ImmutableCollections$ListN を返す。
        // javap で java.util.stream.Stream を読むと ArrayList → unmodifiableList に見えるが、
        // それはインタフェースの default 実装であり実 Stream は到達しない。
        assertThat(viaToList).isNotInstanceOf(ArrayList.class);
        assertThat(viaToList.getClass().getName()).startsWith("java.util.ImmutableCollections$");
    }

    @Test
    @DisplayName("可変 ArrayList / LinkedHashMap は往復しても中身が保たれる")
    void 可変コレクションは往復できる() {
        // role-permissions / careCategory 等が返す形
        List<String> permissions = new ArrayList<>(List.of("TEAM_READ", "TEAM_WRITE"));
        assertThat(roundTrip(permissions)).isEqualTo(permissions);

        List<Long> watcherIds = new ArrayList<>(List.of(10L, 20L, 30L));
        assertThat(roundTrip(watcherIds)).isEqualTo(watcherIds);

        Map<String, String> map = new LinkedHashMap<>();
        map.put("k1", "v1");
        map.put("k2", "v2");
        assertThat(roundTrip(map)).isEqualTo(map);
    }

    // ============================================================
    // C 群: 値が既定値へ化けないこと
    // ============================================================

    @Test
    @DisplayName("public-stats: PublicStatsResponse は往復後も件数が 0 に化けない")
    void publicStatsResponse_往復後も値が保たれる() {
        PublicStatsResponse original = PublicStatsResponse.builder()
                .totalUsers(1234L)
                .totalTeams(56L)
                .totalOrganizations(7L)
                .countryBreakdown(null)
                .build();

        Object restored = roundTrip(original);

        assertThat(restored).isInstanceOf(PublicStatsResponse.class);
        PublicStatsResponse r = (PublicStatsResponse) restored;
        // 旧実装（setter なし・@JsonCreator なし・private フィールド）ではここが全て 0 になる
        assertThat(r.getTotalUsers()).isEqualTo(1234L);
        assertThat(r.getTotalTeams()).isEqualTo(56L);
        assertThat(r.getTotalOrganizations()).isEqualTo(7L);
    }

    @Test
    @DisplayName("public-stats: 入れ子の CountryStats も往復後に値が保たれる")
    void publicStatsResponse_国別内訳も往復できる() {
        Map<String, PublicStatsResponse.CountryStats> breakdown = new LinkedHashMap<>();
        breakdown.put("JP", PublicStatsResponse.CountryStats.builder()
                .users(100L).teams(20L).organizations(3L).build());

        PublicStatsResponse original = PublicStatsResponse.builder()
                .totalUsers(100L).totalTeams(20L).totalOrganizations(3L)
                .countryBreakdown(breakdown)
                .build();

        PublicStatsResponse r = (PublicStatsResponse) roundTrip(original);

        assertThat(r.getCountryBreakdown()).containsKey("JP");
        assertThat(r.getCountryBreakdown().get("JP").getUsers()).isEqualTo(100L);
        assertThat(r.getCountryBreakdown().get("JP").getTeams()).isEqualTo(20L);
        assertThat(r.getCountryBreakdown().get("JP").getOrganizations()).isEqualTo(3L);
    }

    // ============================================================
    // A 群: Page を載せない形になっていること
    // ============================================================

    @Test
    @DisplayName("team-search: ID ページ（record + ArrayList）は往復後も順序と総件数が保たれる")
    void teamSearchIdPage_往復できる() {
        TeamSearchService.TeamSearchIdPage original =
                new TeamSearchService.TeamSearchIdPage(new ArrayList<>(List.of(3L, 1L, 2L)), 42L);

        Object restored = roundTrip(original);

        assertThat(restored).isInstanceOf(TeamSearchService.TeamSearchIdPage.class);
        TeamSearchService.TeamSearchIdPage r = (TeamSearchService.TeamSearchIdPage) restored;
        // ソート順が壊れると検索結果の並びが崩れるため順序も検証する
        assertThat(r.teamIds()).containsExactly(3L, 1L, 2L);
        assertThat(r.totalElements()).isEqualTo(42L);
    }

    // ============================================================
    // D 群: エンティティ直載せをやめた View が往復できること
    // ============================================================

    @Test
    @DisplayName("visibilityTemplate: ルール View は往復後も全項目が保たれる（null 化しない）")
    void visibilityTemplateRuleView_往復できる() {
        List<VisibilityTemplateRuleView> original = new ArrayList<>(List.of(
                new VisibilityTemplateRuleView(1L, 1000L,
                        VisibilityTemplateRuleType.EXPLICIT_USER, 55L, null),
                new VisibilityTemplateRuleView(2L, 1000L,
                        VisibilityTemplateRuleType.TEAM_FRIEND_OF, null, "@USER_PRIMARY_TEAM")));

        @SuppressWarnings("unchecked")
        List<VisibilityTemplateRuleView> r = (List<VisibilityTemplateRuleView>) roundTrip(original);

        assertThat(r).hasSize(2);
        // 認可の中核なので「ruleType が null に化けない」ことが最重要
        assertThat(r.get(0).ruleType()).isEqualTo(VisibilityTemplateRuleType.EXPLICIT_USER);
        assertThat(r.get(0).ruleTargetId()).isEqualTo(55L);
        assertThat(r.get(0).templateId()).isEqualTo(1000L);
        assertThat(r.get(1).ruleType()).isEqualTo(VisibilityTemplateRuleType.TEAM_FRIEND_OF);
        assertThat(r.get(1).ruleTargetText()).isEqualTo("@USER_PRIMARY_TEAM");
    }

    @Test
    @DisplayName("systemDefaultLabels: ラベル View マップは往復後も全項目が保たれる")
    void todoStatusLabelView_往復できる() {
        Map<String, TodoStatusLabelView> original = new LinkedHashMap<>();
        original.put("TODO", new TodoStatusLabelView(1L, "未着手", "TODO", "#cccccc"));
        original.put("DONE", new TodoStatusLabelView(2L, "完了", "DONE", "#00aa00"));

        @SuppressWarnings("unchecked")
        Map<String, TodoStatusLabelView> r = (Map<String, TodoStatusLabelView>) roundTrip(original);

        assertThat(r).hasSize(2);
        assertThat(r.get("TODO").name()).isEqualTo("未着手");
        assertThat(r.get("TODO").color()).isEqualTo("#cccccc");
        assertThat(r.get("DONE").id()).isEqualTo(2L);
    }

    // ============================================================
    // B 群: template / module 系の戻り値
    // ============================================================

    @Test
    @DisplayName("templates: TemplateSummaryResponse リストは往復後も値が保たれる")
    void templateSummaryList_往復できる() {
        List<TemplateSummaryResponse> original = new ArrayList<>(List.of(
                new TemplateSummaryResponse(1L, "少年野球", "youth-baseball", "SPORTS", 5)));

        @SuppressWarnings("unchecked")
        List<TemplateSummaryResponse> r = (List<TemplateSummaryResponse>) roundTrip(original);

        assertThat(r).hasSize(1);
        assertThat(r.get(0).getName()).isEqualTo("少年野球");
        assertThat(r.get(0).getSlug()).isEqualTo("youth-baseball");
        assertThat(r.get(0).getModuleCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("templateDetail: ApiResponse でラップしても内包 modules が保たれる")
    void apiResponseTemplateDetail_往復できる() {
        List<ModuleSummaryResponse> modules = new ArrayList<>(List.of(
                new ModuleSummaryResponse(10L, "出欠", "attendance", "DEFAULT")));
        TemplateResponse detail = new TemplateResponse(
                1L, "少年野球", "youth-baseball", "説明", "https://example.test/i.png",
                "SPORTS", Boolean.TRUE, modules, LocalDateTime.of(2026, 1, 2, 3, 4, 5));

        Object restored = roundTrip(ApiResponse.of(detail));

        assertThat(restored).isInstanceOf(ApiResponse.class);
        @SuppressWarnings("unchecked")
        ApiResponse<TemplateResponse> r = (ApiResponse<TemplateResponse>) restored;
        assertThat(r.getData()).isInstanceOf(TemplateResponse.class);
        assertThat(r.getData().getName()).isEqualTo("少年野球");
        assertThat(r.getData().getIsActive()).isTrue();
        assertThat(r.getData().getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        assertThat(r.getData().getModules()).hasSize(1);
        assertThat(r.getData().getModules().get(0).getSlug()).isEqualTo("attendance");
    }

    @Test
    @DisplayName("moduleCatalog / moduleDetail: ModuleResponse は入れ子リストごと往復できる")
    void moduleResponse_往復できる() {
        ModuleResponse original = new ModuleResponse(
                10L, "出欠", "attendance", "説明", "OPTIONAL", 3,
                Boolean.FALSE, 14, Boolean.TRUE,
                new ArrayList<>(List.of(new LevelAvailabilityResponse("TEAM", Boolean.TRUE, null))),
                new ArrayList<>(List.of(new ModuleSummaryResponse(11L, "連絡", "notice", "OPTIONAL"))));

        ModuleResponse r = (ModuleResponse) roundTrip(original);

        assertThat(r.getName()).isEqualTo("出欠");
        assertThat(r.getTrialDays()).isEqualTo(14);
        assertThat(r.getLevelAvailability()).hasSize(1);
        assertThat(r.getLevelAvailability().get(0).getLevel()).isEqualTo("TEAM");
        assertThat(r.getRecommendations()).hasSize(1);
        assertThat(r.getRecommendations().get(0).getSlug()).isEqualTo("notice");
    }

    @Test
    @DisplayName("teamModules / orgModules: 日時込みの要素が往復できる")
    void teamOrgModules_往復できる() {
        LocalDateTime at = LocalDateTime.of(2026, 3, 4, 5, 6, 7);

        List<TeamModuleResponse> teamModules = new ArrayList<>(List.of(
                new TeamModuleResponse(10L, "出欠", "attendance", Boolean.TRUE, at, null)));
        @SuppressWarnings("unchecked")
        List<TeamModuleResponse> tr = (List<TeamModuleResponse>) roundTrip(teamModules);
        assertThat(tr).hasSize(1);
        assertThat(tr.get(0).getIsEnabled()).isTrue();
        assertThat(tr.get(0).getEnabledAt()).isEqualTo(at);

        List<OrgModuleResponse> orgModules = new ArrayList<>(List.of(
                new OrgModuleResponse(10L, "出欠", "attendance", Boolean.FALSE, at)));
        @SuppressWarnings("unchecked")
        List<OrgModuleResponse> or = (List<OrgModuleResponse>) roundTrip(orgModules);
        assertThat(or).hasSize(1);
        assertThat(or.get(0).getIsEnabled()).isFalse();
        assertThat(or.get(0).getEnabledAt()).isEqualTo(at);
    }
}
