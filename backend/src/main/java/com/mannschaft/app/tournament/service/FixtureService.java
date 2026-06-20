package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.service.MatchService;
import com.mannschaft.app.tournament.LeagueRoundType;
import com.mannschaft.app.tournament.FixtureResult;
import com.mannschaft.app.tournament.FixtureSlot;
import com.mannschaft.app.tournament.FixtureStatus;
import com.mannschaft.app.tournament.RankingsRecalculationEvent;
import com.mannschaft.app.tournament.StandingsRecalculationEvent;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.TournamentFormat;
import com.mannschaft.app.tournament.TournamentMapper;
import com.mannschaft.app.tournament.dto.BatchScoreRequest;
import com.mannschaft.app.tournament.dto.CreateMatchdayRequest;
import com.mannschaft.app.tournament.dto.CreateRosterRequest;
import com.mannschaft.app.tournament.dto.FixtureResponse;
import com.mannschaft.app.tournament.dto.FixtureSetRequest;
import com.mannschaft.app.tournament.dto.FixtureSetResponse;
import com.mannschaft.app.tournament.dto.MatchdayResponse;
import com.mannschaft.app.tournament.dto.PlayerStatBatchRequest;
import com.mannschaft.app.tournament.dto.PlayerStatRequest;
import com.mannschaft.app.tournament.dto.PlayerStatResponse;
import com.mannschaft.app.tournament.dto.RosterResponse;
import com.mannschaft.app.tournament.dto.ScoreUpdateRequest;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentFixtureEntity;
import com.mannschaft.app.tournament.entity.TournamentFixturePlayerStatEntity;
import com.mannschaft.app.tournament.entity.TournamentFixtureRosterEntity;
import com.mannschaft.app.tournament.entity.TournamentFixtureSetEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchdayEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.repository.TournamentFixturePlayerStatRepository;
import com.mannschaft.app.tournament.repository.TournamentFixtureRepository;
import com.mannschaft.app.tournament.repository.TournamentFixtureRosterRepository;
import com.mannschaft.app.tournament.repository.TournamentFixtureSetRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchdayRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.repository.TournamentStatDefRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 対戦カード・スコア・出場メンバー・個人成績管理サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FixtureService {

    private final TournamentRepository tournamentRepository;
    private final TournamentMatchdayRepository matchdayRepository;
    private final TournamentFixtureRepository matchRepository;
    private final TournamentFixtureSetRepository matchSetRepository;
    private final TournamentFixtureRosterRepository rosterRepository;
    private final TournamentFixturePlayerStatRepository playerStatRepository;
    private final TournamentParticipantRepository participantRepository;
    private final TournamentStatDefRepository statDefRepository;
    private final TournamentMapper mapper;
    private final ApplicationEventPublisher eventPublisher;
    /**
     * match ドメインのライフサイクル Service（Phase5b-2'・系統B の match 正本化）。
     * tournament → match はエンティティ直参照せず本 Service メソッド経由で正本を作る（原則 1/5・D-1・05 §H.5）。
     */
    private final MatchService matchService;

    // ===== Matchday =====

    public List<MatchdayResponse> listMatchdays(Long divisionId) {
        return matchdayRepository.findByDivisionIdOrderByMatchdayNumberAsc(divisionId)
                .stream()
                .map(md -> {
                    List<FixtureResponse> matches = matchRepository.findByMatchdayIdOrderByMatchNumberAsc(md.getId())
                            .stream().map(m -> mapper.toMatchResponse(m, List.of(), List.of())).toList();
                    return mapper.toMatchdayResponse(md, matches);
                })
                .toList();
    }

    @Transactional
    public MatchdayResponse createMatchday(Long divisionId, CreateMatchdayRequest request) {
        Integer matchdayNumber = request.getMatchdayNumber();
        if (matchdayNumber == null) {
            matchdayNumber = matchdayRepository.findTopByDivisionIdOrderByMatchdayNumberDesc(divisionId)
                    .map(md -> md.getMatchdayNumber() + 1).orElse(1);
        }
        TournamentMatchdayEntity matchday = TournamentMatchdayEntity.builder()
                .divisionId(divisionId)
                .name(request.getName())
                .matchdayNumber(matchdayNumber)
                .scheduledDate(request.getScheduledDate())
                .build();
        matchday = matchdayRepository.save(matchday);
        return mapper.toMatchdayResponse(matchday, List.of());
    }

    // ===== Match Generation =====

    @Transactional
    public List<MatchdayResponse> generateMatchdays(Long tournamentId, Long divisionId) {
        TournamentEntity tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND));

        List<TournamentParticipantEntity> participants =
                participantRepository.findByDivisionIdOrderBySeedAsc(divisionId);
        if (participants.size() < 2) {
            throw new BusinessException(TournamentErrorCode.INSUFFICIENT_PARTICIPANTS);
        }

        if (tournament.getFormat() == TournamentFormat.KNOCKOUT) {
            return generateKnockoutBracket(divisionId, participants);
        } else {
            return generateLeagueMatchdays(divisionId, participants, tournament.getLeagueRoundType());
        }
    }

    private List<MatchdayResponse> generateLeagueMatchdays(Long divisionId,
                                                            List<TournamentParticipantEntity> participants,
                                                            LeagueRoundType roundType) {
        int n = participants.size();
        boolean hasBye = (n % 2 != 0);
        List<TournamentParticipantEntity> teamList = new ArrayList<>(participants);
        if (hasBye) {
            teamList.add(null); // BYE用
            n = teamList.size();
        }

        int rounds = n - 1;
        List<MatchdayResponse> result = new ArrayList<>();

        for (int round = 0; round < rounds; round++) {
            TournamentMatchdayEntity matchday = matchdayRepository.save(
                    TournamentMatchdayEntity.builder()
                            .divisionId(divisionId)
                            .name("第" + (round + 1) + "節")
                            .matchdayNumber(round + 1)
                            .build());

            List<FixtureResponse> matches = new ArrayList<>();
            int matchNum = 1;
            for (int i = 0; i < n / 2; i++) {
                TournamentParticipantEntity home = teamList.get(i);
                TournamentParticipantEntity away = teamList.get(n - 1 - i);
                TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                        .matchdayId(matchday.getId())
                        .homeParticipantId(home != null ? home.getId() : null)
                        .awayParticipantId(away != null ? away.getId() : null)
                        .matchNumber(matchNum++)
                        .result(home == null || away == null ? FixtureResult.BYE : FixtureResult.PENDING)
                        .build();
                match = matchRepository.save(match);
                matches.add(mapper.toMatchResponse(match, List.of(), List.of()));
            }

            result.add(mapper.toMatchdayResponse(matchday, matches));

            // ラウンドロビン回転
            TournamentParticipantEntity last = teamList.remove(teamList.size() - 1);
            teamList.add(1, last);
        }

        // DOUBLE: ホーム&アウェイ入替で第2ラウンド
        if (roundType == LeagueRoundType.DOUBLE) {
            for (int round = 0; round < rounds; round++) {
                int mdNum = rounds + round + 1;
                TournamentMatchdayEntity matchday = matchdayRepository.save(
                        TournamentMatchdayEntity.builder()
                                .divisionId(divisionId)
                                .name("第" + mdNum + "節")
                                .matchdayNumber(mdNum)
                                .build());

                List<FixtureResponse> matches = new ArrayList<>();
                int matchNum = 1;
                for (int i = 0; i < n / 2; i++) {
                    TournamentParticipantEntity away = teamList.get(i);
                    TournamentParticipantEntity home = teamList.get(n - 1 - i);
                    TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                            .matchdayId(matchday.getId())
                            .homeParticipantId(home != null ? home.getId() : null)
                            .awayParticipantId(away != null ? away.getId() : null)
                            .matchNumber(matchNum++)
                            .result(home == null || away == null ? FixtureResult.BYE : FixtureResult.PENDING)
                            .build();
                    match = matchRepository.save(match);
                    matches.add(mapper.toMatchResponse(match, List.of(), List.of()));
                }

                result.add(mapper.toMatchdayResponse(matchday, matches));
                TournamentParticipantEntity last = teamList.remove(teamList.size() - 1);
                teamList.add(1, last);
            }
        }

        return result;
    }

    private List<MatchdayResponse> generateKnockoutBracket(Long divisionId,
                                                            List<TournamentParticipantEntity> participants) {
        int n = participants.size();
        int totalSlots = 1;
        while (totalSlots < n) totalSlots *= 2;
        int totalRounds = (int) (Math.log(totalSlots) / Math.log(2));

        List<MatchdayResponse> result = new ArrayList<>();

        // 最終戦から作成して next_match_id を設定
        List<List<TournamentFixtureEntity>> roundMatches = new ArrayList<>();
        for (int round = totalRounds; round >= 1; round--) {
            int matchCount = totalSlots / (int) Math.pow(2, round);
            String roundName = switch (matchCount) {
                case 1 -> "決勝";
                case 2 -> "準決勝";
                default -> round + "回戦";
            };

            TournamentMatchdayEntity matchday = matchdayRepository.save(
                    TournamentMatchdayEntity.builder()
                            .divisionId(divisionId)
                            .name(roundName)
                            .matchdayNumber(totalRounds - round + 1)
                            .build());

            List<TournamentFixtureEntity> matches = new ArrayList<>();
            for (int i = 0; i < matchCount; i++) {
                TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                        .matchdayId(matchday.getId())
                        .matchNumber(i + 1)
                        .build();
                match = matchRepository.save(match);
                matches.add(match);
            }
            roundMatches.add(0, matches);
        }

        // next_match_id の設定
        for (int round = 0; round < roundMatches.size() - 1; round++) {
            List<TournamentFixtureEntity> current = roundMatches.get(round);
            List<TournamentFixtureEntity> next = roundMatches.get(round + 1);
            for (int i = 0; i < current.size(); i++) {
                TournamentFixtureEntity match = current.get(i);
                match.setNextMatch(next.get(i / 2).getId(),
                        i % 2 == 0 ? FixtureSlot.HOME : FixtureSlot.AWAY);
                matchRepository.save(match);
            }
        }

        // 1回戦に参加チームを配置
        List<TournamentFixtureEntity> firstRound = roundMatches.get(0);
        for (int i = 0; i < firstRound.size(); i++) {
            TournamentFixtureEntity match = firstRound.get(i);
            Long homeId = (i * 2 < n) ? participants.get(i * 2).getId() : null;
            Long awayId = (i * 2 + 1 < n) ? participants.get(i * 2 + 1).getId() : null;
            match = match.toBuilder()
                    .homeParticipantId(homeId)
                    .awayParticipantId(awayId)
                    .result(homeId == null || awayId == null ? FixtureResult.BYE : FixtureResult.PENDING)
                    .build();
            matchRepository.save(match);
        }

        // レスポンス構築
        for (int round = 0; round < roundMatches.size(); round++) {
            List<TournamentFixtureEntity> matches = roundMatches.get(round);
            if (!matches.isEmpty()) {
                TournamentMatchdayEntity md = matchdayRepository.findById(matches.get(0).getMatchdayId()).orElse(null);
                if (md != null) {
                    List<FixtureResponse> matchResponses = matches.stream()
                            .map(m -> mapper.toMatchResponse(m, List.of(), List.of())).toList();
                    result.add(mapper.toMatchdayResponse(md, matchResponses));
                }
            }
        }

        return result;
    }

    // ===== Score =====

    public FixtureResponse getMatch(Long matchId) {
        TournamentFixtureEntity match = findMatchOrThrow(matchId);
        List<FixtureSetResponse> sets = matchSetRepository.findByMatchIdOrderBySetNumberAsc(match.getId())
                .stream().map(mapper::toMatchSetResponse).toList();
        List<PlayerStatResponse> stats = playerStatRepository.findByMatchId(match.getId())
                .stream().map(mapper::toPlayerStatResponse).toList();
        return mapper.toMatchResponse(match, sets, stats);
    }

    @Transactional
    public FixtureResponse updateScore(Long tournamentId, Long matchId, ScoreUpdateRequest request) {
        TournamentFixtureEntity match = findMatchOrThrow(matchId);

        // 楽観ロック: client が最後に見た版とロードした版を突合し、stale client を弾く（F08.7 Wave3a）
        checkOptimisticLock(match, request.getVersion());

        // スコアのバリデーション
        if (request.getHomeScore() != null && request.getHomeScore() < 0) {
            throw new BusinessException(TournamentErrorCode.INVALID_SCORE);
        }
        if (request.getAwayScore() != null && request.getAwayScore() < 0) {
            throw new BusinessException(TournamentErrorCode.INVALID_SCORE);
        }
        // セット別スコアの検証（記録のみ・MVP）: home/away を非負整数のみ検証する。
        // 25点上限/デュース等は競技ごとに可変のため検証しない（F08.7 セット制①）。
        validateSets(request.getSets());

        // 大会フラグ（hasSets/setsToWin/hasDraw）を取得して結果判定へ渡す（F08.7 セット制①）。
        // 大会未取得（通常は起こらないが防御的）の場合は hasSets=false 相当（従来の本戦合算判定）に倒れる。
        TournamentEntity tournament = tournamentRepository.findById(tournamentId).orElse(null);

        // 結果判定
        FixtureResult result = determineResult(request, match, tournament);

        Long winnerId = null;
        if (result == FixtureResult.HOME_WIN || result == FixtureResult.FORFEIT_HOME_WIN) {
            winnerId = match.getHomeParticipantId();
        } else if (result == FixtureResult.AWAY_WIN || result == FixtureResult.FORFEIT_AWAY_WIN) {
            winnerId = match.getAwayParticipantId();
        }

        match.updateScore(request.getHomeScore(), request.getAwayScore(),
                request.getHomePenaltyScore(), request.getAwayPenaltyScore(),
                winnerId, result, request.getNotes());
        matchRepository.save(match);

        // 系統B の match 正本化（Phase5b-2'・05 §H.1〜H.2.3）: fixture スナップショット書込と同一 TX 内で
        // matches を正本として作成/更新する。fixture 列は派生スナップショット（H.2.3）であり、正本は matches。
        recordMatchCanonical(matchId, match, tournament, request.getHomeScore(), request.getAwayScore(),
                request.getHomePenaltyScore(), request.getAwayPenaltyScore());

        // セット別スコアの保存
        if (request.getSets() != null) {
            matchSetRepository.deleteByMatchId(matchId);
            request.getSets().forEach(setReq -> matchSetRepository.save(
                    TournamentFixtureSetEntity.builder()
                            .matchId(matchId)
                            .setNumber(setReq.getSetNumber())
                            .homeScore(setReq.getHomeScore())
                            .awayScore(setReq.getAwayScore())
                            .build()));
        }

        // ディビジョンIDを取得して順位表再計算イベント発火
        TournamentMatchdayEntity matchday = matchdayRepository.findById(match.getMatchdayId()).orElse(null);
        if (matchday != null) {
            eventPublisher.publishEvent(
                    new StandingsRecalculationEvent(this, matchday.getDivisionId(), tournamentId));
        }

        return getMatch(matchId);
    }

    @Transactional
    public void batchUpdateScores(Long tournamentId, Long divisionId, Long matchdayId,
                                  BatchScoreRequest request) {
        // 大会フラグ（hasSets/setsToWin/hasDraw）はバッチ内で不変のため一括で1回取得する（F08.7 セット制①）。
        TournamentEntity tournament = tournamentRepository.findById(tournamentId).orElse(null);

        for (BatchScoreRequest.MatchScoreEntry entry : request.getScores()) {
            TournamentFixtureEntity match = findMatchOrThrow(entry.getMatchId());

            // 楽観ロック: 各 fixture ごとに client 版とロード版を突合。1件でも不一致なら
            // 例外を投げ、@Transactional により一括ロールバック（部分適用しない）（F08.7 Wave3a）
            checkOptimisticLock(match, entry.getVersion());

            // セット別スコアの検証（記録のみ・MVP・F08.7 セット制①）
            validateSets(entry.getSets());

            ScoreUpdateRequest scoreReq = new ScoreUpdateRequest(
                    entry.getHomeScore(), entry.getAwayScore(),
                    entry.getHomePenaltyScore(), entry.getAwayPenaltyScore(),
                    entry.getNotes(), entry.getVersion(), entry.getSets());
            FixtureResult result = determineResult(scoreReq, match, tournament);
            Long winnerId = null;
            if (result == FixtureResult.HOME_WIN) winnerId = match.getHomeParticipantId();
            else if (result == FixtureResult.AWAY_WIN) winnerId = match.getAwayParticipantId();

            match.updateScore(entry.getHomeScore(), entry.getAwayScore(),
                    entry.getHomePenaltyScore(), entry.getAwayPenaltyScore(),
                    winnerId, result, entry.getNotes());
            matchRepository.save(match);

            // 系統B の match 正本化（Phase5b-2'・05 §H.1〜H.2.3）: 各 fixture の正本を matches へ反映する。
            recordMatchCanonical(entry.getMatchId(), match, tournament, entry.getHomeScore(), entry.getAwayScore(),
                    entry.getHomePenaltyScore(), entry.getAwayPenaltyScore());

            if (entry.getSets() != null) {
                matchSetRepository.deleteByMatchId(entry.getMatchId());
                entry.getSets().forEach(setReq -> matchSetRepository.save(
                        TournamentFixtureSetEntity.builder()
                                .matchId(entry.getMatchId())
                                .setNumber(setReq.getSetNumber())
                                .homeScore(setReq.getHomeScore())
                                .awayScore(setReq.getAwayScore())
                                .build()));
            }
        }

        // 順位表再計算イベントは1回だけ発火
        eventPublisher.publishEvent(new StandingsRecalculationEvent(this, divisionId, tournamentId));
    }

    @Transactional
    public void changeMatchStatus(Long matchId, FixtureStatus newStatus) {
        TournamentFixtureEntity match = findMatchOrThrow(matchId);
        match.changeStatus(newStatus);
        matchRepository.save(match);
    }

    // ===== Roster =====

    public List<RosterResponse> listRosters(Long matchId) {
        return rosterRepository.findByMatchIdOrderByParticipantIdAscJerseyNumberAsc(matchId)
                .stream().map(mapper::toRosterResponse).toList();
    }

    @Transactional
    public List<RosterResponse> createRosters(Long matchId, CreateRosterRequest request) {
        List<TournamentFixtureRosterEntity> rosters = request.getEntries().stream()
                .map(entry -> (TournamentFixtureRosterEntity) TournamentFixtureRosterEntity.builder()
                        .matchId(matchId)
                        .participantId(entry.getParticipantId())
                        .userId(entry.getUserId())
                        .isStarter(entry.getIsStarter() != null ? entry.getIsStarter() : true)
                        .jerseyNumber(entry.getJerseyNumber())
                        .position(entry.getPosition())
                        .build())
                .toList();
        return rosterRepository.saveAll(rosters).stream()
                .map(mapper::toRosterResponse).toList();
    }

    @Transactional
    public void deleteRoster(Long rosterId) {
        rosterRepository.deleteById(rosterId);
    }

    // ===== Player Stats =====

    @Transactional
    public FixtureResponse updatePlayerStats(Long tournamentId, Long matchId,
                                           PlayerStatBatchRequest request) {
        findMatchOrThrow(matchId);

        for (PlayerStatRequest stat : request.getStats()) {
            // stat_key のバリデーション
            statDefRepository.findByTournamentIdAndStatKey(tournamentId, stat.getStatKey())
                    .orElseThrow(() -> new BusinessException(TournamentErrorCode.INVALID_STAT_KEY));

            TournamentFixturePlayerStatEntity existing =
                    playerStatRepository.findByMatchIdAndUserIdAndStatKey(matchId, stat.getUserId(), stat.getStatKey())
                            .orElse(null);

            if (existing != null) {
                existing.updateValue(
                        stat.getValueInt(),
                        stat.getValueDecimal(),
                        stat.getValueTime() != null ? LocalTime.parse(stat.getValueTime()) : null);
                playerStatRepository.save(existing);
            } else {
                playerStatRepository.save(TournamentFixturePlayerStatEntity.builder()
                        .matchId(matchId)
                        .participantId(stat.getParticipantId())
                        .userId(stat.getUserId())
                        .statKey(stat.getStatKey())
                        .valueInt(stat.getValueInt())
                        .valueDecimal(stat.getValueDecimal())
                        .valueTime(stat.getValueTime() != null ? LocalTime.parse(stat.getValueTime()) : null)
                        .build());
            }
        }

        // 個人ランキング再計算イベント発火
        eventPublisher.publishEvent(new RankingsRecalculationEvent(this, tournamentId));

        return getMatch(matchId);
    }

    // ===== Private =====

    /**
     * 試合結果（勝敗）を判定する（F08.7 セット制①）。
     *
     * <p><b>セット制（hasSets=true）</b>: バレーボール等のセット制大会では本戦合計点ではなく
     * <b>勝セット数</b>で勝敗を決める。各セットの home/away を比較して勝セット数を数え、多い方を勝者とする。
     * 同数（勝敗つかず）の場合は {@code hasDraw} に従い、DRAW を許容するなら DRAW、許容しないなら
     * セットの合計点で判定する安全な既定に倒れる（バレー等は通常 hasDraw=false 運用ゆえ基本的に発生しない）。</p>
     *
     * <p><b>入口①非破壊（最重要回帰）</b>: hasSets=true 大会であっても、{@code sets} が null/空の場合
     * （例: {@code MatchScoreFixtureListener} はサッカー前提で sets=null で委譲する）は<b>セット判定を行わず</b>、
     * 従来どおり本戦スコア→PK→DRAW のスコアベース判定にフォールバックする。例外は投げない。</p>
     *
     * <p><b>非セット制（hasSets=false / 大会未取得）</b>: 本戦スコア（home/away_score）で判定し、同点なら PK、
     * それでも同点なら DRAW を返す（PK ロジックは温存・#1473）。<b>延長得点は本戦スコアへ合算済み</b>であり
     * 延長別列は Phase 5b-3 で廃止した（05 §H.1 移行表・sports/01_soccer.md §4.1）。</p>
     */
    private FixtureResult determineResult(ScoreUpdateRequest request, TournamentFixtureEntity match,
                                        TournamentEntity tournament) {
        boolean hasSets = tournament != null && Boolean.TRUE.equals(tournament.getHasSets());
        List<FixtureSetRequest> sets = request.getSets();

        // セット制かつセット入力がある場合のみ勝セット数で判定する。
        // sets が null/空のときは入口①（サッカー委譲）等の非破壊フォールバックとしてスコアベース判定へ落とす。
        if (hasSets && sets != null && !sets.isEmpty()) {
            return determineResultBySets(sets, tournament);
        }

        // 以下、非セット制（または sets 未入力）の従来ロジック。
        if (request.getHomeScore() == null || request.getAwayScore() == null) {
            return FixtureResult.PENDING;
        }

        // 延長得点は本戦スコア（homeScore/awayScore）へ合算済みのため、本戦スコアのみで勝敗を判定する
        // （延長別列は Phase 5b-3 で廃止・05 §H.1 移行表）。同点なら PK、それでも同点なら DRAW。
        int totalHome = request.getHomeScore();
        int totalAway = request.getAwayScore();

        if (totalHome > totalAway) return FixtureResult.HOME_WIN;
        if (totalAway > totalHome) return FixtureResult.AWAY_WIN;

        // PK戦
        if (request.getHomePenaltyScore() != null && request.getAwayPenaltyScore() != null) {
            if (request.getHomePenaltyScore() > request.getAwayPenaltyScore()) return FixtureResult.HOME_WIN;
            if (request.getAwayPenaltyScore() > request.getHomePenaltyScore()) return FixtureResult.AWAY_WIN;
        }

        return FixtureResult.DRAW;
    }

    /**
     * セット制大会の勝敗を勝セット数で判定する（F08.7 セット制① MVP）。
     *
     * <p>各セットの home/away を比較して勝セット数を集計し、多い方を勝者とする。
     * {@code setsToWin}（先取制の目標セット数）が設定されている場合、いずれかが到達すれば勝者を確定する。</p>
     *
     * <p>勝セット数が同数の場合は {@code hasDraw} に従う。hasDraw=true なら DRAW を返す。
     * hasDraw=false（バレー等）なら本来発生しないが、安全な既定としてセット内の合計得点で判定し、
     * それも同点なら DRAW を返す（症状を隠さず、確定不能を握りつぶさない）。</p>
     */
    private FixtureResult determineResultBySets(List<FixtureSetRequest> sets, TournamentEntity tournament) {
        int homeSetsWon = 0;
        int awaySetsWon = 0;
        for (FixtureSetRequest set : sets) {
            if (set.getHomeScore() == null || set.getAwayScore() == null) continue;
            if (set.getHomeScore() > set.getAwayScore()) homeSetsWon++;
            else if (set.getAwayScore() > set.getHomeScore()) awaySetsWon++;
        }

        // 先取制（setsToWin 到達で確定）。多い方が勝者である点は素朴な比較と一致するが、
        // 設計意図（先取制）を明示するために setsToWin 到達を優先評価する。
        Integer setsToWin = tournament.getSetsToWin();
        if (setsToWin != null && setsToWin > 0) {
            if (homeSetsWon >= setsToWin && homeSetsWon > awaySetsWon) return FixtureResult.HOME_WIN;
            if (awaySetsWon >= setsToWin && awaySetsWon > homeSetsWon) return FixtureResult.AWAY_WIN;
        }

        if (homeSetsWon > awaySetsWon) return FixtureResult.HOME_WIN;
        if (awaySetsWon > homeSetsWon) return FixtureResult.AWAY_WIN;

        // 勝セット数同数: hasDraw を許容するなら DRAW、そうでなければセット内合計点で判定する。
        if (Boolean.TRUE.equals(tournament.getHasDraw())) {
            return FixtureResult.DRAW;
        }
        int homePoints = 0;
        int awayPoints = 0;
        for (FixtureSetRequest set : sets) {
            if (set.getHomeScore() != null) homePoints += set.getHomeScore();
            if (set.getAwayScore() != null) awayPoints += set.getAwayScore();
        }
        if (homePoints > awayPoints) return FixtureResult.HOME_WIN;
        if (awayPoints > homePoints) return FixtureResult.AWAY_WIN;
        return FixtureResult.DRAW;
    }

    /**
     * セット別スコアを検証する（記録のみ・MVP・F08.7 セット制①）。
     *
     * <p>home/away が負値の場合のみ {@link TournamentErrorCode#INVALID_SCORE} を投げる。
     * 25点上限・デュース等の競技固有ルールは可変のため検証しない。{@code sets} が null の場合は何もしない
     * （入口①のセット非対応経路を壊さない）。</p>
     */
    private void validateSets(List<FixtureSetRequest> sets) {
        if (sets == null) return;
        for (FixtureSetRequest set : sets) {
            if (set.getHomeScore() != null && set.getHomeScore() < 0) {
                throw new BusinessException(TournamentErrorCode.INVALID_SCORE);
            }
            if (set.getAwayScore() != null && set.getAwayScore() < 0) {
                throw new BusinessException(TournamentErrorCode.INVALID_SCORE);
            }
        }
    }

    /**
     * 大会のブラケット（トーナメント表）データを取得する。
     * 全試合をラウンド順（matchNumber昇順）で返し、nextMatchId/nextMatchSlot でツリー構造を表現する。
     */
    public List<FixtureResponse> getBracket(Long tournamentId) {
        List<TournamentFixtureEntity> matches = matchRepository.findByTournamentId(tournamentId);
        return matches.stream()
                .sorted((a, b) -> {
                    int cmp = Integer.compare(
                            a.getMatchNumber() != null ? a.getMatchNumber() : 0,
                            b.getMatchNumber() != null ? b.getMatchNumber() : 0);
                    if (cmp != 0) return cmp;
                    return Long.compare(a.getId(), b.getId());
                })
                .map(m -> mapper.toMatchResponse(m, List.of(), List.of()))
                .toList();
    }

    private TournamentFixtureEntity findMatchOrThrow(Long matchId) {
        return matchRepository.findById(matchId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.MATCH_NOT_FOUND));
    }

    /**
     * 系統B（直接スコア入力）を <b>match レコードへ正本化</b>する（Phase5b-2'・05 §H.1〜H.2.3）。
     *
     * <p>fixture のスコア列は派生スナップショット（H.2.3）であり、その正本がここで作る match である。
     * {@link MatchService#recordTournamentScore} を Service 経由で呼び（エンティティ越境せず・原則 1/D-1）、
     * 同一 fixture の match を冪等に作成/更新する（二重起票防止）。本メソッドは fixture スナップショット書込と
     * <b>同一 TX 内</b>で動き、{@code MatchCompletedEvent} は発火しない（系統B は fixture を同期書込済みのため
     * AFTER_COMMIT リスナーによる二重書込/二重 StandingsRecalc を避ける・{@link MatchService#recordTournamentScore} Javadoc）。</p>
     *
     * <h3>participant ⇔ side / team / org 解決（H.1.2）</h3>
     * <ul>
     *   <li><b>team</b>: fixture の <b>home participant の team_id</b>（HOME 固定）。participant 経由で解決し
     *       team_id 単独逆引きはしない（同一 team が複数 participant になり得るため・H.1.2）。</li>
     *   <li><b>相手</b>: away participant の team_id（無ければ participant の displayName を手入力名として渡す）。</li>
     *   <li><b>org</b>: 大会の {@code organization_id}。sport は大会に sport 列が無いため SOCCER 既定
     *       （多競技対応は将来の tournament.sport 列追加時に拡張・現状の F08.7 はサッカー前提）。</li>
     * </ul>
     *
     * <p><b>正本化をスキップする条件</b>: home participant 未割当（BYE・トーナメント未確定枠）や、
     * home participant の team_id が解決できない場合は <b>match を作らない</b>（matches.team_id は NOT NULL ゆえ）。
     * その場合も fixture スナップショットは既に書かれており順位導出は従来どおり成立する（症状を隠さず、
     * 正本化できない構造的ケースを明示的にスキップする）。</p>
     *
     * @param fixtureId        fixture の ID（{@code tournament_matches.id}・matches.tournament_fixture_id へ）
     * @param fixture          スコア反映済みの fixture（スナップショット）
     * @param tournament       大会（org/sport 解決元・null の場合は正本化スキップ）
     * @param homeScore        本戦ホームスコア（延長合算済み）
     * @param awayScore        本戦アウェイスコア（延長合算済み）
     * @param homePenaltyScore PK 戦ホームスコア（NULL=PK なし）
     * @param awayPenaltyScore PK 戦アウェイスコア（NULL=PK なし）
     */
    private void recordMatchCanonical(Long fixtureId, TournamentFixtureEntity fixture, TournamentEntity tournament,
                                      Integer homeScore, Integer awayScore,
                                      Integer homePenaltyScore, Integer awayPenaltyScore) {
        if (tournament == null) {
            // 大会未取得（防御的・通常起こらない）では org が引けないため正本化をスキップ（fixture は書込済み）。
            log.warn("match 正本化スキップ: tournament 未取得 fixtureId={}", fixtureId);
            return;
        }
        Long homeParticipantId = fixture.getHomeParticipantId();
        if (homeParticipantId == null) {
            // BYE / 未確定枠は HOME チームが無く matches.team_id（NOT NULL）を満たせないため正本化しない。
            return;
        }
        TournamentParticipantEntity homeParticipant =
                participantRepository.findById(homeParticipantId).orElse(null);
        if (homeParticipant == null || homeParticipant.getTeamId() == null) {
            log.warn("match 正本化スキップ: home participant の team 解決不能 fixtureId={}, homeParticipantId={}",
                    fixtureId, homeParticipantId);
            return;
        }

        // 相手は away participant 経由で解決する（team_id 単独逆引き禁止・H.1.2）。
        Long opponentTeamId = null;
        String opponentName = null;
        if (fixture.getAwayParticipantId() != null) {
            TournamentParticipantEntity awayParticipant =
                    participantRepository.findById(fixture.getAwayParticipantId()).orElse(null);
            if (awayParticipant != null) {
                opponentTeamId = awayParticipant.getTeamId();
                opponentName = awayParticipant.getDisplayName();
            }
        }

        matchService.recordTournamentScore(MatchService.RecordTournamentScoreCommand.builder()
                .organizationId(tournament.getOrganizationId())
                .teamId(homeParticipant.getTeamId())
                .opponentTeamId(opponentTeamId)
                .opponentName(opponentName)
                // F08.10 多競技対応（🟡-1a）: canonical match の競技は当該 fixture の大会 sport に従う。
                // 大会 sport（String・既定 SOCCER）を Sport enum へ解決し、MatchService がこの sport を
                // canonical match に格納する（多競技大会＝バレー/将棋等で誤った競技の正本 match を作らない）。
                .sport(resolveTournamentSport(tournament))
                .tournamentFixtureId(fixtureId)
                .homeScore(homeScore)
                .awayScore(awayScore)
                .homePenaltyScore(homePenaltyScore)
                .awayPenaltyScore(awayPenaltyScore)
                .build());
    }

    /**
     * 大会の競技種別（{@code tournament.sport}・String）を canonical match 用の {@link Sport} enum へ解決する
     * （F08.10 多競技対応・🟡-1a）。
     *
     * <p>大会作成/更新時に DTO の {@code @Pattern} と Service の {@code resolveSport}（{@code Sport.valueOf} 相当）で
     * 妥当性を担保済みのため、通常は {@code Sport.valueOf} が成功する。万一 DB に不正値（手動投入等）があった場合は
     * 正本化を止めないよう SOCCER にフォールバックしつつ警告ログを残す（症状は隠さず可観測化する）。
     * {@code null}（既存大会の後方互換・DDL DEFAULT 未充填の防御）も SOCCER とみなす。</p>
     *
     * @param tournament 大会（sport 解決元）
     * @return canonical match に格納する {@link Sport}
     */
    private Sport resolveTournamentSport(TournamentEntity tournament) {
        String sport = tournament.getSport();
        if (sport == null) {
            return Sport.SOCCER;
        }
        try {
            return Sport.valueOf(sport);
        } catch (IllegalArgumentException e) {
            log.warn("大会 sport が不正値のため SOCCER にフォールバック: tournamentId={}, sport={}",
                    tournament.getId(), sport);
            return Sport.SOCCER;
        }
    }

    /**
     * 楽観ロックの実効化（F08.7 順位UI Wave3a）。
     *
     * <p>client が最後に見た版（{@code expectedVersion}）と、ロードしたエンティティの現在版を突合する。
     * 不一致なら別の編集者が先に保存して版が進んでいる＝stale client であり、サイレントな上書きを防ぐため
     * {@link ObjectOptimisticLockingFailureException} を投げる。これは
     * {@link com.mannschaft.app.common.GlobalExceptionHandler} で HTTP 409（CONFLICT）に変換され、
     * FE（ScoreEntryGrid）が {@code isConflictError} で検知できる。</p>
     *
     * <p>JPA の {@code @Version} はロード直後の自己版としか比較しないため、これ単独では
     * 「古い版を握ったまま POST してきた並行編集者」を弾けない。client 版との明示突合で根治する。</p>
     *
     * <p>後方互換: {@code expectedVersion} が {@code null}（版を送らない旧来呼出）の場合は版チェックを行わず
     * 従来挙動とする。FE は常に版を送るため実運用では必ずチェックされる（DTO 側 {@code @NotNull} で必須化済み）。</p>
     */
    private void checkOptimisticLock(TournamentFixtureEntity match, Long expectedVersion) {
        if (expectedVersion != null && !Objects.equals(match.getVersion(), expectedVersion)) {
            throw new ObjectOptimisticLockingFailureException(TournamentFixtureEntity.class, match.getId());
        }
    }
}
