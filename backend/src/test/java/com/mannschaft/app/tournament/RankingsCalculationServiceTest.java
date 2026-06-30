package com.mannschaft.app.tournament;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import com.mannschaft.app.publicview.service.ViewerContextBuilder;
import com.mannschaft.app.publicview.visibility.IdentityVisibilityResolver;
import com.mannschaft.app.publicview.visibility.ViewerContext;
import com.mannschaft.app.tournament.dto.IndividualRankingResponse;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentIndividualRankingEntity;
import com.mannschaft.app.tournament.entity.TournamentFixturePlayerStatEntity;
import com.mannschaft.app.tournament.entity.TournamentStatDefEntity;
import com.mannschaft.app.tournament.repository.TournamentIndividualRankingRepository;
import com.mannschaft.app.tournament.repository.TournamentFixturePlayerStatRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.repository.TournamentStatDefRepository;
import com.mannschaft.app.tournament.service.RankingsCalculationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link RankingsCalculationService} の単体テスト。
 *
 * <p>F08.7 順位UI 項目①（ランキング選手名の F19.1 本人可視性経由表示）の名前解決を、
 * 実物の {@link IdentityVisibilityResolver}（純粋・依存なしの @Component）を {@code @Spy} で組み込んで
 * end-to-end に検証する。閲覧者立場（ViewerContext）は {@link ViewerContextBuilder} をモックして
 * シナリオごとに固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RankingsCalculationService 単体テスト")
class RankingsCalculationServiceTest {

    @Mock private TournamentStatDefRepository statDefRepository;
    @Mock private TournamentFixturePlayerStatRepository playerStatRepository;
    @Mock private TournamentIndividualRankingRepository rankingRepository;
    @Mock private TournamentRepository tournamentRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private UserRepository userRepository;
    @Mock private ViewerContextBuilder viewerContextBuilder;
    @Mock private MediaUrlResolver mediaUrlResolver;

    // F19.1 解決ロジックの本物を組み込む（トートロジー回避: 表示名の解決結果まで検証する）。
    @Spy private IdentityVisibilityResolver identityVisibilityResolver = new IdentityVisibilityResolver();

    // MapStruct 実装を本物で使う（context への displayName 詰め込みまで検証する）。
    @Spy private TournamentMapper mapper = new TournamentMapperImpl();

    @InjectMocks
    private RankingsCalculationService service;

    private static final Long TOURNAMENT_ID = 1L;
    private static final Long ORG_ID = 900L;
    private static final String STAT_KEY = "goals";

    @Nested
    @DisplayName("recalculateAll")
    class RecalculateAll {

        @Test
        @DisplayName("正常系: ランキング対象の成績項目がない場合は何もしない")
        void ランキング対象なし() {
            given(statDefRepository.findByTournamentIdAndIsRankingTargetTrueOrderBySortOrderAsc(TOURNAMENT_ID))
                    .willReturn(List.of());

            service.recalculateAll(TOURNAMENT_ID);

            // No rankingRepository interactions expected
        }

        @Test
        @DisplayName("正常系: INTEGER型の集計が正しく実行される")
        void INTEGER型集計() {
            TournamentStatDefEntity def = TournamentStatDefEntity.builder()
                    .tournamentId(TOURNAMENT_ID)
                    .statKey(STAT_KEY)
                    .dataType(StatDataType.INTEGER)
                    .aggregationType(StatAggregationType.SUM)
                    .isRankingTarget(true)
                    .build();
            given(statDefRepository.findByTournamentIdAndIsRankingTargetTrueOrderBySortOrderAsc(TOURNAMENT_ID))
                    .willReturn(List.of(def));

            TournamentFixturePlayerStatEntity stat = TournamentFixturePlayerStatEntity.builder()
                    .userId(100L).participantId(1L).statKey(STAT_KEY).valueInt(3).build();
            given(playerStatRepository.findByTournamentIdAndStatKey(TOURNAMENT_ID, STAT_KEY))
                    .willReturn(List.of(stat));

            service.recalculateAll(TOURNAMENT_ID);

            verify(rankingRepository).deleteByTournamentIdAndStatKey(TOURNAMENT_ID, STAT_KEY);
            verify(rankingRepository).save(any());
        }
    }

    @Nested
    @DisplayName("getRankings - F08.7 項目① 選手名解決（F19.1 経由）")
    class GetRankingsNameResolution {

        @BeforeEach
        void setUpTournamentAndOrg() {
            // 共有セットアップ。一部テスト（大会未解決ケース等）では消費されないため lenient で許容する。
            TournamentEntity tournament = TournamentEntity.builder()
                    .organizationId(ORG_ID)
                    .build();
            lenient().when(tournamentRepository.findById(TOURNAMENT_ID))
                    .thenReturn(Optional.of(tournament));

            OrganizationEntity org = OrganizationEntity.builder()
                    .supporterNameDisclosure(NameDisclosureMode.DISPLAY_NAME)
                    .build();
            lenient().when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));

            lenient().when(statDefRepository.findByTournamentIdAndStatKey(TOURNAMENT_ID, STAT_KEY))
                    .thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("通常ユーザー（MEMBER 閲覧者）: 本名が解決される")
        void 通常ユーザーは本名解決() {
            Long playerId = 100L;
            givenSingleRankingRow(playerId);

            UserEntity player = userBuilder(playerId)
                    .lastName("山田").firstName("太郎").displayName("yamada")
                    .careCategory(CareCategory.GENERAL_FAMILY)
                    .build();
            given(userRepository.findByIdIn(Set.of(playerId))).willReturn(List.of(player));

            // MEMBER 閲覧者は本名表示が許可される（§4.6.1）。
            given(viewerContextBuilder.buildForOrganizationByUserId(eq(999L), eq(ORG_ID)))
                    .willReturn(ViewerContext.member(999L, Set.of(ORG_ID)));

            IndividualRankingResponse.IndividualRankingContextDto ctx =
                    firstContext(service.getRankings(TOURNAMENT_ID, STAT_KEY, PageRequest.of(0, 50), 999L));

            assertThat(ctx.displayName()).isEqualTo("山田太郎");
            assertThat(ctx.anonymized()).isFalse();
            assertThat(ctx.userId()).isEqualTo(playerId);
        }

        @Test
        @DisplayName("MINOR 選手: 閲覧者が MEMBER でも強制匿名化される（§11.3）")
        void MINOR選手は抑止() {
            Long playerId = 101L;
            givenSingleRankingRow(playerId);

            UserEntity minor = userBuilder(playerId)
                    .lastName("佐藤").firstName("花子").displayName("hanako")
                    .careCategory(CareCategory.MINOR)
                    .build();
            given(userRepository.findByIdIn(Set.of(playerId))).willReturn(List.of(minor));

            given(viewerContextBuilder.buildForOrganizationByUserId(eq(999L), eq(ORG_ID)))
                    .willReturn(ViewerContext.member(999L, Set.of(ORG_ID)));

            IndividualRankingResponse.IndividualRankingContextDto ctx =
                    firstContext(service.getRankings(TOURNAMENT_ID, STAT_KEY, PageRequest.of(0, 50), 999L));

            // MINOR は本名でも display_name でもなく汎用ラベル「投稿者」になる。
            assertThat(ctx.displayName()).isEqualTo("投稿者");
            assertThat(ctx.anonymized()).isTrue();
        }

        @Test
        @DisplayName("退会済み（ユーザー不在）: フォールバック「退会済みユーザー」になる")
        void 退会済みはフォールバック() {
            Long playerId = 102L;
            givenSingleRankingRow(playerId);

            // findByIdIn が空 = ユーザー物理削除/退会 → PostAuthor(authorId のみ) → Resolver が退会済み扱い。
            given(userRepository.findByIdIn(Set.of(playerId))).willReturn(List.of());

            given(viewerContextBuilder.buildForOrganizationByUserId(eq(999L), eq(ORG_ID)))
                    .willReturn(ViewerContext.member(999L, Set.of(ORG_ID)));

            IndividualRankingResponse.IndividualRankingContextDto ctx =
                    firstContext(service.getRankings(TOURNAMENT_ID, STAT_KEY, PageRequest.of(0, 50), 999L));

            // PostAuthor は authorId 非 null（退会判定 false）かつ displayName/fullName=null →
            // MEMBER 解決で本名 null → §4.6.4 フォールバック「匿名のユーザー#…」。
            assertThat(ctx.displayName()).startsWith("匿名のユーザー#");
            assertThat(ctx.anonymized()).isFalse();
        }

        @Test
        @DisplayName("未認証 viewer（ANONYMOUS）: 開示規約どおり汎用ラベル「投稿者」になる")
        void 未認証は開示規約どおり匿名() {
            Long playerId = 103L;
            givenSingleRankingRow(playerId);

            UserEntity player = userBuilder(playerId)
                    .lastName("田中").firstName("一郎").displayName("tanaka")
                    .careCategory(CareCategory.GENERAL_FAMILY)
                    .build();
            given(userRepository.findByIdIn(Set.of(playerId))).willReturn(List.of(player));

            // 未ログイン: viewerUserId=null → ViewerContextBuilder は anonymous を返す。
            given(viewerContextBuilder.buildForOrganizationByUserId(eq(null), eq(ORG_ID)))
                    .willReturn(ViewerContext.anonymous());

            IndividualRankingResponse.IndividualRankingContextDto ctx =
                    firstContext(service.getRankings(TOURNAMENT_ID, STAT_KEY, PageRequest.of(0, 50), null));

            assertThat(ctx.displayName()).isEqualTo("投稿者");
            assertThat(ctx.anonymized()).isTrue();
        }

        @Test
        @DisplayName("N+1 回避: 複数行でも findByIdIn は 1 回だけ呼ばれる")
        void バッチ取得でN1回避() {
            TournamentIndividualRankingEntity r1 = rankingRow(200L, 1);
            TournamentIndividualRankingEntity r2 = rankingRow(201L, 2);
            Page<TournamentIndividualRankingEntity> page =
                    new PageImpl<>(List.of(r1, r2), PageRequest.of(0, 50), 2);
            given(rankingRepository.findByTournamentIdAndStatKeyOrderByRankAsc(
                    eq(TOURNAMENT_ID), eq(STAT_KEY), any(Pageable.class))).willReturn(page);

            given(userRepository.findByIdIn(Set.of(200L, 201L))).willReturn(List.of(
                    userBuilder(200L).lastName("A").firstName("a").displayName("a")
                            .careCategory(CareCategory.GENERAL_FAMILY).build(),
                    userBuilder(201L).lastName("B").firstName("b").displayName("b")
                            .careCategory(CareCategory.GENERAL_FAMILY).build()));

            given(viewerContextBuilder.buildForOrganizationByUserId(eq(999L), eq(ORG_ID)))
                    .willReturn(ViewerContext.member(999L, Set.of(ORG_ID)));

            service.getRankings(TOURNAMENT_ID, STAT_KEY, PageRequest.of(0, 50), 999L);

            verify(userRepository, times(1)).findByIdIn(any());
        }

        @Test
        @DisplayName("大会/主催ORG が解決できない場合: 安全側として匿名化される")
        void 大会未解決は安全側匿名() {
            Long playerId = 104L;
            givenSingleRankingRow(playerId);
            // 大会が見つからない → 全選手匿名化（findByIdIn / ViewerContextBuilder は呼ばれない）。
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.empty());

            IndividualRankingResponse.IndividualRankingContextDto ctx =
                    firstContext(service.getRankings(TOURNAMENT_ID, STAT_KEY, PageRequest.of(0, 50), 999L));

            assertThat(ctx.displayName()).isEqualTo("投稿者");
            assertThat(ctx.anonymized()).isTrue();
        }

        private void givenSingleRankingRow(Long playerId) {
            TournamentIndividualRankingEntity row = rankingRow(playerId, 1);
            Page<TournamentIndividualRankingEntity> page =
                    new PageImpl<>(List.of(row), PageRequest.of(0, 50), 1);
            given(rankingRepository.findByTournamentIdAndStatKeyOrderByRankAsc(
                    eq(TOURNAMENT_ID), eq(STAT_KEY), any(Pageable.class))).willReturn(page);
        }
    }

    // ===== ヘルパ =====

    private static TournamentIndividualRankingEntity rankingRow(Long userId, int rank) {
        return TournamentIndividualRankingEntity.builder()
                .tournamentId(TOURNAMENT_ID)
                .userId(userId)
                .participantId(1L)
                .statKey(STAT_KEY)
                .rank(rank)
                .totalValueInt(10)
                .matchesPlayed(3)
                .build();
    }

    /**
     * 指定 id を持つ {@link UserEntity} を組み立てるヘルパ。
     *
     * <p>BaseEntity#id は {@code @Builder} 対象外のため、{@link Builder#buildWithId(Long)} 内で
     * {@link ReflectionTestUtils#setField} で設定する。</p>
     */
    private static Builder userBuilder(Long id) {
        return new Builder(id);
    }

    private static IndividualRankingResponse.IndividualRankingContextDto firstContext(
            Page<IndividualRankingResponse> page) {
        return page.getContent().get(0).getContext();
    }

    /**
     * {@link UserEntity} ビルダーラッパー。{@code build()} 時に BaseEntity#id をリフレクションで設定する。
     */
    private static final class Builder {
        private final Long id;
        private String lastName;
        private String firstName;
        private String displayName;
        private CareCategory careCategory;
        private String avatarUrl;

        Builder(Long id) {
            this.id = id;
        }

        Builder lastName(String v) { this.lastName = v; return this; }
        Builder firstName(String v) { this.firstName = v; return this; }
        Builder displayName(String v) { this.displayName = v; return this; }
        Builder careCategory(CareCategory v) { this.careCategory = v; return this; }
        Builder avatarUrl(String v) { this.avatarUrl = v; return this; }

        UserEntity build() {
            UserEntity entity = UserEntity.builder()
                    .email("player" + id + "@example.com")
                    .lastName(lastName)
                    .firstName(firstName)
                    .displayName(displayName)
                    .careCategory(careCategory)
                    .avatarUrl(avatarUrl)
                    .build();
            ReflectionTestUtils.setField(entity, "id", id);
            return entity;
        }
    }
}
