package com.mannschaft.app.match.service;

import com.mannschaft.app.match.domain.HomeAway;
import com.mannschaft.app.match.domain.MatchKind;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.domain.StateModel;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.repository.MatchRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase5b-2' 幽霊重複根治（away team 帰属 match 経路）の <b>実 DB 経路番人</b> 結合テスト。
 *
 * <h3>このテストが守るもの（05 §H.2.3 / Phase5b-2' 冪等キー堅牢化）</h3>
 * <p>入口①（match ドメイン UI）でaway participant の team（{@code team_id=awayTeamId}）が主体の match が
 * 先に作られた状態で、系統B（{@link MatchService#recordTournamentScore}）が同一 fixture に
 * スコアを入力する場合、<b>1 fixture に対して canonical match が 1 件のまま</b>であること
 * （二重作成＝幽霊重複が生じないこと）を実 MySQL（Testcontainers）＋実 JPA 経由で担保する。</p>
 *
 * <h3>なぜ純 Mockito UT では守れないのか（false-green 問題）</h3>
 * <p>既存の {@link MatchServiceTest} 内
 * {@code resolvesAwayTeamAttributedExistingMatchWithoutDuplicate} は Mockito で
 * {@code findFirstByOrganizationIdAndTournamentFixtureIdOrderByKickoffAtDescIdDesc} の戻り値を
 * stub している。そのため冪等キー（{@code organization_id, tournament_fixture_id}）の実クエリが
 * 本当に team_id 依存なしで既存レコードを引き当てるかどうかは検証できない。
 * <b>実 DB 経路では、JPA 派生クエリが誤ったシグネチャ（team_id 絞り込み付き）に戻っていれば
 * lookup が 0 件を返し home 帰属 match を新規作成する→{@code count > 1} でアサート失敗する</b>。</p>
 *
 * <h3>テストが検証するシナリオ（4 点）</h3>
 * <ol>
 *   <li><b>幽霊重複なし</b>: {@code recordTournamentScore} 後も同一 fixture の matches が <b>1 件のまま</b>。</li>
 *   <li><b>team 帰属不変</b>: 既存 away 帰属 match の {@code team_id} / {@code home_away} が維持される。</li>
 *   <li><b>スコア正本化</b>: home/away スコアが系統B 入力値へ置換・status が COMPLETED に確定。</li>
 *   <li><b>冪等性</b>: 同一 fixture へ 2 回呼んでも match が 1 件のまま（訂正入力の冪等）。</li>
 * </ol>
 *
 * <h3>実行可否</h3>
 * <p>Docker（Testcontainers MySQL）が利用可能な環境（CI / WSL native の Docker）でのみ実行される
 * （{@link AbstractMySqlIntegrationTest#isDockerAvailable()} の {@code @EnabledIf}）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/05_tournament_integration.md §H.2.3</p>
 */
@DisplayName("Phase5b-2' 幽霊重複根治 — away 帰属 match 経路の実 DB 番人テスト")
// JUnit 5 の @EnabledIf は @Inherited ではないため派生クラスでも再宣言が必須。
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MatchRecordTournamentScoreAwayDuplicateIT extends AbstractMySqlIntegrationTest {

    private static final Long ORG_ID = 42L;
    private static final Long HOME_TEAM = 1001L;
    private static final Long AWAY_TEAM = 1002L;
    private static final Long FIXTURE_ID = 8888L;
    private static final long ACTOR_USER = 99L;

    @Autowired
    private MatchService matchService;

    @Autowired
    private MatchRepository matchRepository;

    /**
     * 入口①（away team 帰属 match が先行）→ 系統B（recordTournamentScore）後、
     * 同一 fixture の canonical match が 1 件のまま（幽霊重複しない）。
     *
     * <p>テストメソッドに {@code @Transactional} を付けない（実コミットさせないと JPA キャッシュ外から
     * 参照できず、二重作成の検知が不可能になる）。Testcontainers コンテナが JVM ライフサイクルで
     * 破棄されるため、テスト間分離は {@code organizationId} × {@code tournamentFixtureId} の
     * 一意な組み合わせで担保する（テストクラスごとに異なる値を使う）。</p>
     */
    @Test
    @DisplayName("away 帰属 match が先行しても recordTournamentScore は二重作成せず既存を更新する（canonical match 1 件・team 帰属不変）")
    void recordTournamentScore_awayAttributedMatchExists_noGhostDuplicate() {
        // ── Given: 入口①で away team が主体（team_id=AWAY_TEAM, home_away=AWAY）の match を実 DB に保存 ──
        // これが「away 帰属の既存 match」として実 DB に存在する状態を再現する。
        // 系統B は常に home participant の team_id（HOME_TEAM）で lookup するため、
        // team_id ベースの lookup なら 0 件→新規作成→幽霊重複が生じる（Phase5b-2' 以前の不具合）。
        MatchEntity preExistingAwayMatch = MatchEntity.builder()
                .organizationId(ORG_ID)
                .teamId(AWAY_TEAM)              // away team が主体（入口①の典型）
                .sport(Sport.SOCCER)
                .stateModel(StateModel.CONTINUOUS_TIME)
                .kind(MatchKind.TOURNAMENT)
                .tournamentFixtureId(FIXTURE_ID)
                .homeAway(HomeAway.AWAY)        // away 側として記録（入口①の side 帰属）
                .opponentTeamId(HOME_TEAM)
                .opponentName("ホームFC")
                .homeScore(0)
                .awayScore(0)
                .status(MatchStatus.IN_PROGRESS)
                .hasScorekeeper(false)
                .createdBy(ACTOR_USER)
                .build();
        MatchEntity savedAway = matchRepository.save(preExistingAwayMatch);
        UUID preExistingId = savedAway.getId();
        assertThat(preExistingId).isNotNull();

        // 前提: 同一 fixture に match が 1 件存在すること（away 帰属）
        List<MatchEntity> beforeList = matchRepository
                .findByOrganizationIdAndStatus(ORG_ID, MatchStatus.IN_PROGRESS);
        // この fixture のものだけ抽出（同一 org の他テストレコードを除外）
        long countBefore = beforeList.stream()
                .filter(m -> FIXTURE_ID.equals(m.getTournamentFixtureId()))
                .count();
        assertThat(countBefore).as("事前に away 帰属 match が 1 件存在する").isEqualTo(1);

        // ── When: 系統B（recordTournamentScore）が home participant の team_id（HOME_TEAM）でスコア入力 ──
        // fixtureId 基準の冪等キーで既存 away 帰属 match を引き当て、スコア更新に徹する。
        // team_id ベース lookup の場合は 0 件→新規作成→幽霊重複となる（旧不具合）。
        MatchService.RecordTournamentScoreCommand cmd = MatchService.RecordTournamentScoreCommand.builder()
                .organizationId(ORG_ID)
                .teamId(HOME_TEAM)          // 系統B は常に home participant の teamId を渡す（H.1.2）
                .opponentTeamId(AWAY_TEAM)
                .sport(Sport.SOCCER)
                .tournamentFixtureId(FIXTURE_ID)
                .homeScore(3)
                .awayScore(1)
                .homePenaltyScore(null)
                .awayPenaltyScore(null)
                .actorUserId(ACTOR_USER)
                .build();

        UUID resultId = matchService.recordTournamentScore(cmd);

        // ── Then 1: 同一 fixture の canonical match が 1 件のまま（幽霊重複なし）──
        // fixture の全 match を取得: COMPLETED になっているはずの結果を count する。
        List<MatchEntity> allOrgMatches = matchRepository
                .findByOrganizationIdAndStatus(ORG_ID, MatchStatus.COMPLETED);
        long countAfterCompleted = allOrgMatches.stream()
                .filter(m -> FIXTURE_ID.equals(m.getTournamentFixtureId()))
                .count();
        assertThat(countAfterCompleted)
                .as("recordTournamentScore 後、同一 fixture の COMPLETED match が 1 件のみ（幽霊重複しない）")
                .isEqualTo(1);

        // 元の away 帰属 match が IN_PROGRESS→COMPLETED になったため IN_PROGRESS は 0 件であること
        List<MatchEntity> stillInProgress = matchRepository
                .findByOrganizationIdAndStatus(ORG_ID, MatchStatus.IN_PROGRESS);
        long countInProgressForFixture = stillInProgress.stream()
                .filter(m -> FIXTURE_ID.equals(m.getTournamentFixtureId()))
                .count();
        assertThat(countInProgressForFixture)
                .as("更新前の IN_PROGRESS match は COMPLETED へ遷移し、0 件になる")
                .isEqualTo(0);

        // ── Then 2: 戻り値が既存 away 帰属 match の ID と一致する（新規作成していない）──
        assertThat(resultId)
                .as("recordTournamentScore の戻り値が既存 away 帰属 match の ID と一致する（新規作成していない）")
                .isEqualTo(preExistingId);

        // ── Then 3: 実 DB からリロードして team 帰属・スコア・status を厳密アサート ──
        MatchEntity persisted = matchRepository.findById(preExistingId).orElseThrow(
                () -> new AssertionError("既存 away 帰属 match が消えている（予期しない削除）"));

        // team 帰属は系統B に上書きされない（side 固定・H.1.2）
        assertThat(persisted.getTeamId())
                .as("team_id は away team のまま維持される（系統B に上書きされない・H.1.2）")
                .isEqualTo(AWAY_TEAM);
        assertThat(persisted.getHomeAway())
                .as("home_away は AWAY のまま維持される（系統B はスコア更新に徹する）")
                .isEqualTo(HomeAway.AWAY);

        // スコアは系統B 入力値へ正本として置換される
        assertThat(persisted.getHomeScore())
                .as("home_score が系統B 入力値（3）に置換される")
                .isEqualTo(3);
        assertThat(persisted.getAwayScore())
                .as("away_score が系統B 入力値（1）に置換される")
                .isEqualTo(1);
        assertThat(persisted.getHomePenaltyScore())
                .as("home_penalty_score は null のまま（PK 戦なし）")
                .isNull();
        assertThat(persisted.getAwayPenaltyScore())
                .as("away_penalty_score は null のまま（PK 戦なし）")
                .isNull();

        // status が COMPLETED に確定している
        assertThat(persisted.getStatus())
                .as("status が COMPLETED に確定する（大会直接スコア入力の確定）")
                .isEqualTo(MatchStatus.COMPLETED);
    }

    /**
     * 冪等性検証: 同一 fixture へ 2 回 {@code recordTournamentScore} を呼んでも match は 1 件のまま
     * （訂正入力の冪等・05 §H.2 (d)）。
     *
     * <p>2 回目の呼び出しは 1 回目が作成/更新した match を引き当て、スコアのみ上書きする
     * （全列置換・冪等）。</p>
     */
    @Test
    @DisplayName("冪等性: 同一 fixture へ 2 回呼んでも canonical match は 1 件のまま（訂正入力でも幽霊重複しない）")
    void recordTournamentScore_calledTwiceOnSameFixture_idempotentSingleMatch() {
        // 別 fixture ID を使ってテスト間汚染を防ぐ
        final Long fixtureId2 = 7777L;

        // 1 回目: home 帰属がなく、新規作成される
        MatchService.RecordTournamentScoreCommand first = MatchService.RecordTournamentScoreCommand.builder()
                .organizationId(ORG_ID)
                .teamId(HOME_TEAM)
                .opponentTeamId(AWAY_TEAM)
                .sport(Sport.SOCCER)
                .tournamentFixtureId(fixtureId2)
                .homeScore(1)
                .awayScore(0)
                .actorUserId(ACTOR_USER)
                .build();
        UUID firstId = matchService.recordTournamentScore(first);

        // 2 回目: 訂正入力（3-2 に変更）
        MatchService.RecordTournamentScoreCommand second = MatchService.RecordTournamentScoreCommand.builder()
                .organizationId(ORG_ID)
                .teamId(HOME_TEAM)
                .opponentTeamId(AWAY_TEAM)
                .sport(Sport.SOCCER)
                .tournamentFixtureId(fixtureId2)
                .homeScore(3)
                .awayScore(2)
                .actorUserId(ACTOR_USER)
                .build();
        UUID secondId = matchService.recordTournamentScore(second);

        // 同一 ID が返される（新規作成していない・冪等）
        assertThat(secondId)
                .as("2 回目の recordTournamentScore は 1 回目と同じ match ID を返す（新規作成しない・冪等）")
                .isEqualTo(firstId);

        // 実 DB 上で同一 fixture の COMPLETED match が 1 件のみ
        List<MatchEntity> completed = matchRepository
                .findByOrganizationIdAndStatus(ORG_ID, MatchStatus.COMPLETED);
        long countForFixture2 = completed.stream()
                .filter(m -> fixtureId2.equals(m.getTournamentFixtureId()))
                .count();
        assertThat(countForFixture2)
                .as("2 回呼んでも同一 fixture の COMPLETED match は 1 件のみ（幽霊重複しない）")
                .isEqualTo(1);

        // 訂正後のスコアが反映されている
        MatchEntity persisted = matchRepository.findById(firstId).orElseThrow();
        assertThat(persisted.getHomeScore()).as("訂正後 home_score = 3").isEqualTo(3);
        assertThat(persisted.getAwayScore()).as("訂正後 away_score = 2").isEqualTo(2);
        assertThat(persisted.getStatus()).as("status は COMPLETED のまま").isEqualTo(MatchStatus.COMPLETED);
    }

    /**
     * PK 戦スコア分離の確認: PK 戦あり（home_penalty_score / away_penalty_score）が
     * 実 DB に正しく保存・更新されること（01 §B.1 PK 戦は本戦と分離して保持）。
     */
    @Test
    @DisplayName("PK 戦スコア分離: home/away_penalty_score が実 DB に正しく保存される（01 §B.1）")
    void recordTournamentScore_withPenaltyScores_savedSeparately() {
        final Long fixtureId3 = 6666L;

        MatchService.RecordTournamentScoreCommand cmdWithPk = MatchService.RecordTournamentScoreCommand.builder()
                .organizationId(ORG_ID)
                .teamId(HOME_TEAM)
                .opponentTeamId(AWAY_TEAM)
                .sport(Sport.SOCCER)
                .tournamentFixtureId(fixtureId3)
                .homeScore(1)   // 本戦（延長合算済み）
                .awayScore(1)
                .homePenaltyScore(5)  // PK 戦（本戦と分離）
                .awayPenaltyScore(4)
                .actorUserId(ACTOR_USER)
                .build();

        UUID matchId = matchService.recordTournamentScore(cmdWithPk);

        MatchEntity persisted = matchRepository.findById(matchId).orElseThrow();
        assertThat(persisted.getHomeScore()).as("本戦 home_score").isEqualTo(1);
        assertThat(persisted.getAwayScore()).as("本戦 away_score").isEqualTo(1);
        assertThat(persisted.getHomePenaltyScore()).as("PK 戦 home_penalty_score = 5").isEqualTo(5);
        assertThat(persisted.getAwayPenaltyScore()).as("PK 戦 away_penalty_score = 4").isEqualTo(4);
        assertThat(persisted.getStatus()).as("status = COMPLETED").isEqualTo(MatchStatus.COMPLETED);
        assertThat(persisted.getKind()).as("kind = TOURNAMENT").isEqualTo(MatchKind.TOURNAMENT);
        assertThat(persisted.isHasScorekeeper()).as("大会直接入力は記録係なし（共同記録扱い）").isFalse();
    }
}
