package com.mannschaft.app.tournament.service;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.tournament.MatchResult;
import com.mannschaft.app.tournament.MatchStatus;
import com.mannschaft.app.tournament.TournamentFormat;
import com.mannschaft.app.tournament.dto.ScoreUpdateRequest;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchdayEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entity.TournamentStandingEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchdayRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.repository.TournamentStandingRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F08.10 入口① 第三陣の根治（順位自動反映のレース条件根治）を CI で守る AFTER_COMMIT 番人 結合テスト。
 *
 * <h3>このテストが守るもの（05 §H.0.1）</h3>
 * <p>{@link StandingsCalculationService#onStandingsRecalculation} は第三陣で
 * {@code @Async @TransactionalEventListener(phase = AFTER_COMMIT)} ＋ {@code @Transactional(REQUIRES_NEW)}
 * に切り替えられた。これにより、{@link com.mannschaft.app.tournament.service.MatchService#updateScore}
 * の {@code @Transactional} が<b>コミットした後</b>に、別スレッド（{@code @Async event-pool}）が
 * <b>新規 TX で確定済みスコアを読んで</b>順位表を再計算する。手動再計算なしで自動反映される。</p>
 *
 * <h3>なぜ純 Mockito UT では守れないのか（false-green 問題）</h3>
 * <p>既存の {@link StandingsCalculationServiceTest} はリスナーメソッドを直接呼ぶ純 Mockito UT のため、
 * トランザクション境界もスレッド境界も存在せず、{@code @TransactionalEventListener(AFTER_COMMIT)} を
 * 通常の {@code @EventListener}（＝コミット前・同期）に <b>revert しても緑のまま</b>になる（CI 不可視の
 * 順序回帰）。本結合テストは実 MySQL（Testcontainers）＋実トランザクション＋実 {@code @Async} スレッドで
 * 配線を踏むため、この回帰を検知できる。</p>
 *
 * <h3>revert を検知する仕組み（番人として機能する設計意図）</h3>
 * <p>もし {@code onStandingsRecalculation} を {@code @Async @EventListener}（非 AFTER_COMMIT）へ戻すと、
 * イベントは {@code updateScore} の TX 内（publish 時点・<b>コミット前</b>）に発火し、{@code @Async} で
 * 即座に別スレッドへ逃げる。その別スレッドが張る {@code REQUIRES_NEW} TX からは、まだコミットされていない
 * 試合更新（{@code status=COMPLETED}）が<b>見えない</b>ため、{@code findByDivisionIdAndStatus(.., COMPLETED)}
 * が 0 件を返し、順位表は {@code played=0 / wins=0 / points=0} のまま確定する。本テストの bounded polling は
 * {@code played=1 / wins=1 / points=3} を待つので、その場合<b>タイムアウトしてアサート失敗</b>する＝番人として機能する。
 * 逆に AFTER_COMMIT 配線が正しければ、コミット後に確定スコアを読んで {@code played=1 / wins=1 / points=3} に
 * なり、テストは緑になる。</p>
 *
 * <h3>トートロジー回避</h3>
 * <p>サービスの戻り値や呼び出し回数ではなく、{@code @Async} 再計算後に実 DB へ書き込まれた
 * {@code tournament_standings} の実値（{@code played/wins/points/scoreFor/scoreAgainst/rank}）をアサートする。</p>
 *
 * <h3>実行可否</h3>
 * <p>Docker（Testcontainers MySQL）が利用可能な環境（CI / WSL native の Docker など）でのみ実行される
 * （{@link AbstractMySqlIntegrationTest#isDockerAvailable()} の {@code @EnabledIf}）。Docker 不可の開発機では
 * skip され、CI で必ず走る。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/05_tournament_integration.md §H.0.1</p>
 */
@DisplayName("StandingsCalculation AFTER_COMMIT 番人 結合テスト (F08.10 入口① 第三陣)")
// JUnit 5 の @EnabledIf は @Inherited ではないため、派生クラスでも明示的に再宣言する必要がある
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class StandingsRecalculationAfterCommitIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MatchService matchService;

    @Autowired
    private TournamentRepository tournamentRepository;

    @Autowired
    private TournamentDivisionRepository divisionRepository;

    @Autowired
    private TournamentMatchdayRepository matchdayRepository;

    @Autowired
    private TournamentParticipantRepository participantRepository;

    @Autowired
    private TournamentMatchRepository matchRepository;

    @Autowired
    private TournamentStandingRepository standingRepository;

    /**
     * updateScore（@Transactional）のコミット後、@Async AFTER_COMMIT リスナーが確定データで順位を自動反映する。
     *
     * <p>本テストメソッドには {@code @Transactional} を付けない（付けるとテストの TX がロールバックされ
     * AFTER_COMMIT が発火しないため・実コミットさせるのが必須）。セットアップで保存した行は、明示的な
     * クリーンアップを置かず Testcontainers の JVM ライフサイクル終了でコンテナごと破棄させる
     * （AbstractMySqlIntegrationTest の singleton container パターン）。除外可視性のために UUID 由来の
     * 一意な大会・参加者を作るので、他テストの順位表とは独立する。</p>
     */
    @Test
    @DisplayName("updateScore コミット後に @Async AFTER_COMMIT が確定スコアで順位を自動反映する（手動再計算なし）")
    void afterCommit_recalculatesStandings_homeWinPoints3() {
        // ── Given: 実 DB に tournament + division + matchday + participant×2 + fixture を保存（実コミット） ──
        TournamentEntity tournament = tournamentRepository.save(TournamentEntity.builder()
                .organizationId(1L)
                .name("AFTER_COMMIT番人大会")
                .format(TournamentFormat.LEAGUE)
                .winPoints(3)
                .drawPoints(1)
                .lossPoints(0)
                .createdBy(1L)
                .build());

        TournamentDivisionEntity division = divisionRepository.save(TournamentDivisionEntity.builder()
                .tournamentId(tournament.getId())
                .name("1部")
                .build());

        TournamentMatchdayEntity matchday = matchdayRepository.save(TournamentMatchdayEntity.builder()
                .divisionId(division.getId())
                .name("第1節")
                .matchdayNumber(1)
                .build());

        TournamentParticipantEntity home = participantRepository.save(TournamentParticipantEntity.builder()
                .divisionId(division.getId())
                .teamId(1001L)
                .seed(1)
                .displayName("ホームチーム")
                .build());

        TournamentParticipantEntity away = participantRepository.save(TournamentParticipantEntity.builder()
                .divisionId(division.getId())
                .teamId(1002L)
                .seed(2)
                .displayName("アウェイチーム")
                .build());

        TournamentMatchEntity fixture = matchRepository.save(TournamentMatchEntity.builder()
                .matchdayId(matchday.getId())
                .homeParticipantId(home.getId())
                .awayParticipantId(away.getId())
                .matchNumber(1)
                .result(MatchResult.PENDING)
                .status(MatchStatus.SCHEDULED)
                .build());

        // 前提: まだ順位表は存在しない（再計算が未実行）
        assertThat(standingRepository.findByDivisionIdAndParticipantId(division.getId(), home.getId()))
                .isEmpty();

        // ── When: updateScore（@Transactional）を呼ぶ。返ればコミット → AFTER_COMMIT 発火 → @Async 再計算 ──
        // ホーム 2 - 0 アウェイ（HOME_WIN）。テストメソッド自体は @Transactional にしない（実コミットさせる）。
        ScoreUpdateRequest req = new ScoreUpdateRequest(
                2, 0, null, null, null, null, null, fixture.getVersion(), null);
        matchService.updateScore(tournament.getId(), fixture.getId(), req);

        // ── Then: bounded polling（最大 ~10 秒）で順位表が確定スコアで自動反映されるのを待つ ──
        // @Async ゆえ即時ではなく数百 ms〜数秒で反映される前提。AFTER_COMMIT 配線が壊れていれば
        // played=0 のまま確定し、ここでタイムアウトしてアサート失敗する＝番人として機能する。
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Optional<TournamentStandingEntity> homeStanding =
                            standingRepository.findByDivisionIdAndParticipantId(division.getId(), home.getId());
                    assertThat(homeStanding)
                            .as("AFTER_COMMIT 後に @Async 再計算でホームの順位表が確定スコアで作成される")
                            .isPresent();
                    TournamentStandingEntity s = homeStanding.get();
                    assertThat(s.getPlayed()).as("played").isEqualTo(1);
                    assertThat(s.getWins()).as("wins").isEqualTo(1);
                    assertThat(s.getPoints()).as("points（winPoints=3）").isEqualTo(3);
                    assertThat(s.getScoreFor()).as("scoreFor").isEqualTo(2);
                    assertThat(s.getScoreAgainst()).as("scoreAgainst").isEqualTo(0);
                    assertThat(s.getRank()).as("rank（勝者が1位）").isEqualTo(1);
                });

        // アウェイ側も同じ再計算で確定していること（敗者・勝点0・2位）を実値でアサート
        Optional<TournamentStandingEntity> awayStanding =
                standingRepository.findByDivisionIdAndParticipantId(division.getId(), away.getId());
        assertThat(awayStanding).as("アウェイの順位表も作成される").isPresent();
        TournamentStandingEntity a = awayStanding.get();
        assertThat(a.getPlayed()).as("away played").isEqualTo(1);
        assertThat(a.getLosses()).as("away losses").isEqualTo(1);
        assertThat(a.getPoints()).as("away points（lossPoints=0）").isEqualTo(0);
        assertThat(a.getRank()).as("away rank（敗者が2位）").isEqualTo(2);

        // 実 DB 上の試合もコミット済み（status=COMPLETED / result=HOME_WIN）であることを確認
        TournamentMatchEntity persisted = matchRepository.findById(fixture.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(MatchStatus.COMPLETED);
        assertThat(persisted.getResult()).isEqualTo(MatchResult.HOME_WIN);
    }
}
