package com.mannschaft.app.team.batch;

import com.mannschaft.app.team.batch.TeamRegionBackfillService.BackfillResult;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.service.TeamRegionNormalizer;
import com.mannschaft.app.team.service.TeamRegionNormalizer.MatchStage;
import com.mannschaft.app.team.service.TeamRegionNormalizer.ResolvedRegion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link TeamRegionBackfillService} の単体テスト（ドライラン基盤）。
 *
 * <p>dryRun=true で UPDATE(save) が呼ばれないこと・集計が正しいことを Mockito で検証する。
 * 本実行（dryRun=false）の save 反映も確認するが、第一陣の運用では呼ばない。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamRegionBackfillService 単体テスト（ドライラン基盤）")
class TeamRegionBackfillServiceTest {

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TeamRegionNormalizer normalizer;

    @InjectMocks
    private TeamRegionBackfillService service;

    private static TeamEntity team(Long id, String prefecture, String city,
                                   String prefCode, String cityCode) {
        return TeamEntity.builder()
                .name("team-" + id)
                .prefecture(prefecture)
                .city(city)
                .prefectureCode(prefCode)
                .cityCode(cityCode)
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(Boolean.FALSE)
                .build();
    }

    private void givenSinglePage(List<TeamEntity> teams) {
        Page<TeamEntity> page = new PageImpl<>(teams, Pageable.ofSize(500), teams.size());
        given(teamRepository.findAll(any(Pageable.class))).willReturn(page);
    }

    @Test
    @DisplayName("dryRun=true: save を一切呼ばず、マッチ段階を集計する")
    void dryRun_doesNotSave_andAggregates() {
        TeamEntity cityHit = team(1L, "北海道", "函館市", null, null);
        TeamEntity prefOnly = team(2L, "東京", null, null, null);
        TeamEntity none = team(3L, "謎県", "謎市", null, null);
        givenSinglePage(List.of(cityHit, prefOnly, none));

        given(normalizer.normalize("北海道", "函館市"))
                .willReturn(new ResolvedRegion("01", "01202", MatchStage.CITY));
        given(normalizer.normalize("東京", null))
                .willReturn(new ResolvedRegion("13", null, MatchStage.PREFECTURE_ONLY));
        given(normalizer.normalize("謎県", "謎市"))
                .willReturn(new ResolvedRegion(null, null, MatchStage.NONE));

        BackfillResult result = service.run(true);

        // dryRun では一切書き込まない。
        verify(teamRepository, never()).save(any());
        assertThat(result.total).isEqualTo(3);
        assertThat(result.alreadyCoded).isZero();
        assertThat(result.matchedCity).isEqualTo(1);
        assertThat(result.matchedPrefectureOnly).isEqualTo(1);
        assertThat(result.unmatched).isEqualTo(1);
        assertThat(result.updated).isZero();
        assertThat(result.processed()).isEqualTo(3);
    }

    @Test
    @DisplayName("冪等性: 既にコードを持つ行はスキップ（normalize も save も呼ばない）")
    void alreadyCoded_skipped() {
        TeamEntity alreadyCoded = team(1L, "北海道", "函館市", "01", "01202");
        givenSinglePage(List.of(alreadyCoded));

        BackfillResult result = service.run(true);

        assertThat(result.total).isEqualTo(1);
        assertThat(result.alreadyCoded).isEqualTo(1);
        assertThat(result.processed()).isZero();
        verify(normalizer, never()).normalize(any(), any());
        verify(teamRepository, never()).save(any());
    }

    @Test
    @DisplayName("dryRun=false: 解決できた行のみ updateRegionCodes して save する")
    void realRun_savesResolvedOnly() {
        TeamEntity cityHit = team(1L, "北海道", "函館市", null, null);
        TeamEntity none = team(2L, "謎県", "謎市", null, null);
        givenSinglePage(List.of(cityHit, none));

        given(normalizer.normalize("北海道", "函館市"))
                .willReturn(new ResolvedRegion("01", "01202", MatchStage.CITY));
        given(normalizer.normalize("謎県", "謎市"))
                .willReturn(new ResolvedRegion(null, null, MatchStage.NONE));

        BackfillResult result = service.run(false);

        // 解決できた 1 件のみ save。未解決(NONE)は書き込まない。
        verify(teamRepository, times(1)).save(eq(cityHit));
        verify(teamRepository, never()).save(eq(none));
        assertThat(result.updated).isEqualTo(1);
        assertThat(cityHit.getPrefectureCode()).isEqualTo("01");
        assertThat(cityHit.getCityCode()).isEqualTo("01202");
    }

    @Test
    @DisplayName("PREFECTURE_ONLY の本実行: 県コードのみ反映して save")
    void realRun_prefectureOnly_savesPrefCode() {
        TeamEntity prefOnly = team(1L, "東京", null, null, null);
        givenSinglePage(List.of(prefOnly));

        given(normalizer.normalize("東京", null))
                .willReturn(new ResolvedRegion("13", null, MatchStage.PREFECTURE_ONLY));

        BackfillResult result = service.run(false);

        verify(teamRepository, times(1)).save(eq(prefOnly));
        assertThat(prefOnly.getPrefectureCode()).isEqualTo("13");
        assertThat(prefOnly.getCityCode()).isNull();
        assertThat(result.updated).isEqualTo(1);
    }
}
