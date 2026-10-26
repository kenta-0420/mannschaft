package com.mannschaft.app.team.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.dto.TeamSearchCriteria;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.metrics.TeamSearchMetrics;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@code team-search} キャッシュの<b>キー式が実際に評価される経路</b>を固定する回帰テスト
 * （issue #2544 検分指摘 must-fix 2）。
 *
 * <h2>なぜこのテストが要るのか — 既存の網は全部すり抜ける</h2>
 * <p>
 * 当初の実装はキー式に<b>合成済み {@code Specification}</b> を混ぜていた。
 * {@code Specification.where(...).and(...)} が返すのはラムダで {@code hashCode} を持たないため
 * <b>identity hash</b> がキーに入り、キャッシュは 100% ミスし（TTL 60 秒のゴミだけが積む）、
 * 衝突時には {@code orgId} も可視性スコープもキーに現れないという危険な形だった。
 * ところがこの欠陥は<b>どの網にもかからなかった</b>:
 * </p>
 * <ul>
 *   <li>{@code TeamSearchServiceTest}（純 Mockito）は {@code self} に自分自身を注入するため
 *       <b>AOP を迂回する＝キー式が一度も評価されない</b></li>
 *   <li>{@code config.CacheValueSerializationRoundTripTest} は<b>値の形</b>しか見ない</li>
 *   <li>{@code common.architecture.CacheableReturnValueShapeGuardTest} は<b>キーを見ない</b></li>
 * </ul>
 * <p>
 * そこで本テストは {@code @EnableCaching} を効かせた実プロキシ越しに {@link TeamSearchService#search}
 * を叩き、<b>キー SpEL が実評価される経路を 1 本作る</b>。
 * 「同一条件を 2 回 → リポジトリ 1 回」でキャッシュが効くことを、
 * 「条件を 1 つ変えれば別エントリ」でキーが値を正しく識別していることを固定する。
 * とりわけ {@code isMember}（可視性スコープ）の識別は、
 * 未ログイン者が組織メンバー向けの結果を掴まないための<b>認可境界</b>そのものである。
 * </p>
 *
 * <p>実 Redis / Docker は不要（{@link ConcurrentMapCacheManager} を使う。
 * 本テストが見たいのは<b>キー式の意味論</b>であって直列化ではない。
 * 直列化は {@code config.CacheValueSerializationRoundTripTest} が実シリアライザで担保する）。</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = TeamSearchCacheSemanticsTest.TestConfig.class)
@DisplayName("team-search キャッシュキー意味論 回帰テスト (issue #2544)")
class TeamSearchCacheSemanticsTest {

    private static final String CACHE_NAME = "team-search";
    private static final Long ORG_ID = 100L;
    private static final Long OTHER_ORG_ID = 200L;
    private static final Long MEMBER_USER_ID = 9001L;

    /** キャッシュプロキシを効かせるための最小構成。 */
    @Configuration
    @EnableCaching
    static class TestConfig {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(CACHE_NAME);
        }

        @Bean
        OrganizationRepository organizationRepository() {
            return mock(OrganizationRepository.class);
        }

        @Bean
        TeamRepository teamRepository() {
            return mock(TeamRepository.class);
        }

        @Bean
        AccessControlService accessControlService() {
            return mock(AccessControlService.class);
        }

        @Bean
        TeamSearchMetrics teamSearchMetrics() {
            return mock(TeamSearchMetrics.class);
        }

        @Bean
        TeamSearchService teamSearchService(OrganizationRepository organizationRepository,
                                            TeamRepository teamRepository,
                                            AccessControlService accessControlService,
                                            TeamSearchMetrics teamSearchMetrics) {
            return new TeamSearchService(organizationRepository, teamRepository,
                    accessControlService, teamSearchMetrics);
        }
    }

    @Autowired
    private TeamSearchService service;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private AccessControlService accessControlService;

    @Autowired
    private CacheManager cacheManager;

    private final Pageable defaultPageable = PageRequest.of(0, 20, Sort.by("name"));

    private static TeamEntity team() {
        return TeamEntity.builder()
                .name("公開店舗")
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(true)
                .build();
    }

    /** 実体を組む（mock の中で mock を作ると Mockito の UnfinishedStubbing になるため）。 */
    private static OrganizationEntity publicOrg() {
        return OrganizationEntity.builder()
                .name("テスト組織")
                .orgType(OrganizationEntity.OrgType.COMPANY)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.BASIC)
                .supporterEnabled(true)
                .build();
    }

    private static TeamSearchCriteria criteria(String keyword) {
        return new TeamSearchCriteria(keyword, null, null, null);
    }

    @BeforeEach
    void setUp() {
        reset(organizationRepository, teamRepository, accessControlService);
        cacheManager.getCache(CACHE_NAME).clear();

        OrganizationEntity org = publicOrg();
        lenient().when(organizationRepository.findById(any()))
                .thenReturn(Optional.of(org));
        lenient().when(accessControlService.isMember(any(), any(), any()))
                .thenReturn(false);
        // findAll は「検索クエリ」、findAllById は「実体の引き直し」。前者だけがキャッシュ対象。
        lenient().when(teamRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(team())));
        lenient().when(teamRepository.findAllById(any()))
                .thenReturn(List.of(team()));
    }

    // ============================================================
    // キャッシュが実際に効くこと（キー式が安定していること）
    // ============================================================

    @Test
    @DisplayName("同一条件で2回検索すると検索クエリは1回だけ（キーが毎回変わっていない＝Specification 混入の検出）")
    void 同一条件ならキャッシュヒットする() {
        service.search(ORG_ID, criteria("焼肉"), null, defaultPageable);
        service.search(ORG_ID, criteria("焼肉"), null, defaultPageable);

        // 旧実装（合成済み Specification の identity hash をキーに混ぜる）では
        // リクエストごとにキーが変わるため 2 回呼ばれる。
        verify(teamRepository, times(1)).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("キャッシュヒットしても検索結果（件数・総件数）は壊れない")
    void キャッシュヒットしても結果は同じ() {
        var first = service.search(ORG_ID, criteria("焼肉"), null, defaultPageable);
        var second = service.search(ORG_ID, criteria("焼肉"), null, defaultPageable);

        assertThat(first.getContent()).hasSize(1);
        assertThat(second.getContent()).hasSize(1);
        assertThat(second.getTotalElements()).isEqualTo(first.getTotalElements());
    }

    @Test
    @DisplayName("キーに orgId と isMember が literal で現れる（衝突しても組織境界・可視性境界を越えない）")
    void キーに組織IDと可視性スコープが含まれる() {
        service.search(ORG_ID, criteria("焼肉"), null, defaultPageable);

        Cache cache = cacheManager.getCache(CACHE_NAME);
        @SuppressWarnings("unchecked")
        ConcurrentMap<Object, Object> store =
                (ConcurrentMap<Object, Object>) cache.getNativeCache();

        assertThat(store.keySet()).hasSize(1);
        String key = String.valueOf(store.keySet().iterator().next());
        assertThat(key)
                .as("キーは値ベースの文字列であること（identity hash の int ではない）")
                .startsWith(ORG_ID + ":false:")
                .contains("TeamSearchCriteria")
                .contains("焼肉");
    }

    // ============================================================
    // キーが値を正しく識別すること（別条件が同じエントリを掴まない）
    // ============================================================

    @Test
    @DisplayName("組織が違えば別エントリ（別組織の ID 列を掴まない）")
    void 組織IDが違えば別キー() {
        service.search(ORG_ID, criteria("焼肉"), null, defaultPageable);
        service.search(OTHER_ORG_ID, criteria("焼肉"), null, defaultPageable);

        verify(teamRepository, times(2)).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("検索条件が違えば別エントリ（キーワード差が潰れない）")
    void 検索条件が違えば別キー() {
        service.search(ORG_ID, criteria("焼肉"), null, defaultPageable);
        service.search(ORG_ID, criteria("寿司"), null, defaultPageable);

        verify(teamRepository, times(2)).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("可視性スコープ（isMember）が違えば別エントリ — 未ログイン者がメンバー向け結果を掴まない")
    void 可視性スコープが違えば別キー() {
        // 未ログイン（isMember=false）で温める
        service.search(ORG_ID, criteria("焼肉"), null, defaultPageable);

        // 組織メンバー（isMember=true）で同一条件
        given(accessControlService.isMember(MEMBER_USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        service.search(ORG_ID, criteria("焼肉"), MEMBER_USER_ID, defaultPageable);

        // 認可境界そのもの。ここが 1 回になると非公開チームが未ログイン者へ漏れる形になる
        verify(teamRepository, times(2)).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("ページングが違えば別エントリ（2ページ目が1ページ目を掴まない）")
    void ページングが違えば別キー() {
        service.search(ORG_ID, criteria("焼肉"), null, PageRequest.of(0, 20, Sort.by("name")));
        service.search(ORG_ID, criteria("焼肉"), null, PageRequest.of(1, 20, Sort.by("name")));

        verify(teamRepository, times(2)).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("同値の別インスタンス（criteria / pageable を作り直す）は同一エントリを掴む＝値等価が効いている")
    void 同値の別インスタンスは同一キー() {
        service.search(ORG_ID, new TeamSearchCriteria("焼肉", null, null, null),
                null, PageRequest.of(0, 20, Sort.by("name")));
        service.search(ORG_ID, new TeamSearchCriteria("焼肉", null, null, null),
                null, PageRequest.of(0, 20, Sort.by("name")));

        // record / PageRequest の値等価が効いていなければ 2 回になる
        verify(teamRepository, times(1)).findAll(any(Specification.class), any(Pageable.class));
    }
}
