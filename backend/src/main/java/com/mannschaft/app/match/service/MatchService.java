package com.mannschaft.app.match.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.match.MatchCompletedEvent;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.catalog.VolleyballSetRules;
import com.mannschaft.app.match.catalog.WinMethodCatalog;
import com.mannschaft.app.match.domain.HomeAway;
import com.mannschaft.app.match.domain.MatchKind;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.domain.StateModel;
import com.mannschaft.app.match.dto.MatchSummaryResponse;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.live.MatchLiveUpdateEvent;
import com.mannschaft.app.match.repository.MatchRepository;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * F08.10 試合本体のライフサイクルサービス（作成・更新・status 遷移・スコア確定）。
 *
 * <p>認可は {@link MatchAccessService} へ委譲し、IDOR 帰属チェーンは
 * {@code matchRepository.findByIdAndOrganizationIdAndDeletedAtIsNull}（テナント絞り）で担保する（03 §C.4）。
 * COMPLETED 遷移時に {@link MatchCompletedEvent} を発火する（05 §H.2・受信は Phase 5）。
 * スコア確定・status 遷移・記録モード切替・記録係変更は監査ログに残す（03 §C.7）。</p>
 *
 * <p><b>マスアサインメント防止</b>: {@code team_id}/{@code created_by} は呼び出し主体から導出した値を用い、
 * 外部入力を信頼しない（03 §C.4a）。{@code @Transactional} は match ドメイン内に閉じる（原則 5）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/02・03・05</p>
 */
@Slf4j
// 既存 com.mannschaft.app.tournament.service.MatchService と Spring デフォルト bean 名（"matchService"）が衝突し
// ApplicationContext のロードに失敗するため、明示 bean 名を付与して衝突を回避する（F08.10 P2a）。
// 型注入（@Autowired / コンストラクタ注入）は bean 名に依存しないため影響を受けない。
@Service("matchRecordService")
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MatchService {

    private static final String TEAM = "TEAM";

    private final MatchRepository matchRepository;
    private final MatchAccessService matchAccessService;
    private final PlayingTimeCalculationService playingTimeCalculationService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogService auditLogService;

    // ─────────────────────────────────────────────
    // 取得（IDOR 帰属チェーン）
    // ─────────────────────────────────────────────

    /**
     * テナント絞り込みで試合を取得する（IDOR の 1 段目テナントゲート・03 §C.4）。
     * 不在・テナント越境・削除済みは 404（存在を漏らさない）。
     */
    public MatchEntity getMatchOrThrow(UUID matchId, Long organizationId) {
        return matchRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(matchId, organizationId)
                .orElseThrow(() -> new BusinessException(MatchErrorCode.MATCH_001));
    }

    // ─────────────────────────────────────────────
    // 一覧（コレクション GET・Phase2C）
    // ─────────────────────────────────────────────

    /**
     * チームの試合一覧を取得する（FE 一覧ページ前提・02 §F・03 §C）。
     *
     * <p><b>認可（第一防御・Service 層）</b>: 当該チームのメンバー以上であることを
     * {@link MatchAccessService#assertCanListTeamMatches} で明示検証する
     * （per-scope ロールは JWT に無いため SpEL ベースの判定だけに頼らない・03 §C.3.1）。
     * 非メンバーは 403（{@code MATCH_010}）。</p>
     *
     * <p><b>テナント絞り込み（IDOR）</b>: リポジトリ層で {@code organization_id} ＋ {@code team_id} を強制し、
     * パスの {@code orgId}/{@code teamId} 帰属外の試合は結果に含めない。論理削除は Entity の
     * {@code @SQLRestriction} で常に除外される。</p>
     *
     * @param orgId    テナント organization_id（パス由来）
     * @param teamId   主体チーム team_id（パス由来）
     * @param actorUserId 操作者ユーザー ID（認可・サーバー導出）
     * @param filter   任意フィルタ（status / kind / sport / 期間）
     * @param pageable ページング
     * @return 試合サマリのページ（所有/権限列は出さない・03 §C.2）
     */
    public Page<MatchSummaryResponse> listMatches(Long orgId, Long teamId, Long actorUserId,
                                                  ListFilter filter, Pageable pageable) {
        // 第一防御: 当該チームのメンバー以上であること（非メンバーは 403）
        matchAccessService.assertCanListTeamMatches(actorUserId, teamId);
        ListFilter f = filter != null ? filter : ListFilter.builder().build();
        return matchRepository.findTeamMatches(
                        orgId, teamId, f.getStatus(), f.getKind(), f.getSport(),
                        f.getFrom(), f.getTo(), pageable)
                .map(MatchSummaryResponse::from);
    }

    // ─────────────────────────────────────────────
    // 予定からの解決（入口④・二重起票防止・04 §G.1a-2）
    // ─────────────────────────────────────────────

    /**
     * カレンダー予定（入口④）に紐づく既存試合を解決する（04 §G.1a-2）。
     *
     * <p>FE はカレンダー予定詳細の「この試合を記録」押下時に本メソッドを呼び、
     * <b>既存があれば live 画面を開き・無ければ作成</b>するために用いる（同一予定への二重起票防止）。</p>
     *
     * <p><b>認可（第一防御・Service 層）</b>: 当該チームのメンバー以上であることを
     * {@link MatchAccessService#assertCanListTeamMatches} で明示検証する（一覧と同水準・03 §C.3.1）。
     * <b>テナント絞り込み（IDOR）</b>: リポジトリ層で {@code organization_id} ＋ {@code team_id} を強制し、
     * パス帰属外の予定参照は結果に含めない。論理削除は Entity の {@code @SQLRestriction} で常に除外される。</p>
     *
     * <p><b>ドメイン境界</b>: 本メソッドは match ドメイン内の {@code schedule_id}（保持済み BIGINT 参照）のみを引く。
     * schedule ドメイン（予定本体）は参照しない（原則1）。予定のプリフィル（日時・相手名・種別）は FE が
     * 予定データから createMatch に渡す。</p>
     *
     * @param orgId       テナント organization_id（パス由来）
     * @param teamId      主体チーム team_id（パス由来）
     * @param actorUserId 操作者ユーザー ID（認可・サーバー導出）
     * @param scheduleId  カレンダー予定 ID
     * @return 既存試合のサマリ（無ければ {@link Optional#empty()}）
     */
    public Optional<MatchSummaryResponse> resolveByScheduleId(Long orgId, Long teamId,
                                                              Long actorUserId, Long scheduleId) {
        // 第一防御: 当該チームのメンバー以上であること（非メンバーは 403）
        matchAccessService.assertCanListTeamMatches(actorUserId, teamId);
        return matchRepository
                .findFirstByOrganizationIdAndTeamIdAndScheduleIdOrderByKickoffAtDescIdDesc(orgId, teamId, scheduleId)
                .map(MatchSummaryResponse::from);
    }

    /**
     * 大会の対戦カード（fixture）に紐づく既存試合を解決する（入口①・04 §G.1a-2 / 06 §I.2）。
     *
     * <p>FE は大会の対戦表ページでカード押下時に本メソッドを呼び、<b>既存があれば live 画面を開き・無ければ作成</b>
     * するために用いる（同一カードへの二重起票防止）。入口④の {@link #resolveByScheduleId} と完全対称の解決経路。</p>
     *
     * <p><b>認可（第一防御・Service 層）</b>: 当該チームのメンバー以上であることを
     * {@link MatchAccessService#assertCanListTeamMatches} で明示検証する（一覧・入口④と同水準・03 §C.3.1）。
     * <b>テナント絞り込み（IDOR）</b>: リポジトリ層で {@code organization_id} ＋ {@code team_id} を強制し、
     * パス帰属外のカード参照は結果に含めない。論理削除は Entity の {@code @SQLRestriction} で常に除外される。</p>
     *
     * <p><b>ドメイン境界</b>: 本メソッドは match ドメイン内の {@code tournament_fixture_id}（保持済み BIGINT 参照）
     * のみを引く。tournament ドメイン（fixture 本体・participant）は参照しない（原則1）。カードのプリフィル
     * （相手・日時）は FE が fixture データから createMatch に渡す。</p>
     *
     * @param orgId               テナント organization_id（パス由来）
     * @param teamId              主体チーム team_id（パス由来）
     * @param actorUserId         操作者ユーザー ID（認可・サーバー導出）
     * @param tournamentFixtureId 大会の対戦カード ID
     * @return 既存試合のサマリ（無ければ {@link Optional#empty()}）
     */
    public Optional<MatchSummaryResponse> resolveByFixtureId(Long orgId, Long teamId,
                                                             Long actorUserId, Long tournamentFixtureId) {
        // 第一防御: 当該チームのメンバー以上であること（非メンバーは 403）
        matchAccessService.assertCanListTeamMatches(actorUserId, teamId);
        return matchRepository
                .findFirstByOrganizationIdAndTeamIdAndTournamentFixtureIdOrderByKickoffAtDescIdDesc(
                        orgId, teamId, tournamentFixtureId)
                .map(MatchSummaryResponse::from);
    }

    // ─────────────────────────────────────────────
    // 作成（4 入口の文脈は CreateCommand で引き継ぐ）
    // ─────────────────────────────────────────────

    /**
     * 試合を作成する（最小必須は {@code kind} ＋相手＝opponentTeamId or opponentName・03 §C / 04 §G.1）。
     *
     * <p>{@code team_id}/{@code created_by} はサーバー導出値（{@code command.teamId} は認証主体の所属チーム）。
     * 記録モード（{@code hasScorekeeper}）と {@code scorekeeperUserId} を作成時に決定する（03 §C.1）。</p>
     *
     * @param command  作成コマンド（サーバー導出済みの teamId/createdBy を含む）
     * @param actorUserId 操作者ユーザー ID（監査用）
     * @return 作成された試合
     */
    @Transactional
    public MatchEntity create(CreateCommand command, Long actorUserId) {
        if (command.getKind() == null) {
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }
        // 相手は登録チーム or 手入力名のどちらか必須（共同記録の成立条件・03 §未解決 4 の縮退は別途）
        if (command.getOpponentTeamId() == null
                && (command.getOpponentName() == null || command.getOpponentName().isBlank())) {
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }

        Sport sport = command.getSport() != null ? command.getSport() : Sport.SOCCER;
        MatchEntity match = MatchEntity.builder()
                .organizationId(command.getOrganizationId())
                .teamId(command.getTeamId())
                .sport(sport)
                // state_model は sport から導出する（冪等な分岐用に列保持・01 §D.6）。
                .stateModel(sport.stateModel())
                .kind(command.getKind())
                .tournamentFixtureId(command.getTournamentFixtureId())
                .scheduleId(command.getScheduleId())
                .homeAway(command.getHomeAway() != null ? command.getHomeAway() : HomeAway.HOME)
                .opponentTeamId(command.getOpponentTeamId())
                .opponentName(command.getOpponentName())
                .kickoffAt(command.getKickoffAt())
                .venue(command.getVenue())
                .durationMinutes(command.getDurationMinutes())
                .periodFormat(command.getPeriodFormat())
                .status(MatchStatus.SCHEDULED)
                .hasScorekeeper(command.isHasScorekeeper())
                .scorekeeperUserId(command.getScorekeeperUserId())
                .notes(command.getNotes())
                .createdBy(command.getCreatedBy())
                .build();

        MatchEntity saved = matchRepository.save(match);
        log.info("試合作成: matchId={}, teamId={}, kind={}, actor={}",
                saved.getId(), saved.getTeamId(), saved.getKind(), actorUserId);
        return saved;
    }

    // ─────────────────────────────────────────────
    // メタ情報更新（日時・会場・相手・試合長など・03 §C.2）
    // ─────────────────────────────────────────────

    /**
     * 試合メタ情報（日時・会場・相手・試合長・備考など）を更新する（作成者/記録係/主体チーム ADMIN のみ・03 §C.2）。
     *
     * <p>{@code organization_id}/{@code team_id}/{@code created_by} は変更しない（サーバー保持値・改竄耐性）。
     * status 遷移・スコア確定・記録モード切替は専用メソッドに分離する（責務分界）。</p>
     */
    @Transactional
    public MatchEntity updateMeta(UUID matchId, Long organizationId, Long actorUserId,
                                  UpdateMetaCommand command) {
        MatchEntity match = getMatchOrThrow(matchId, organizationId);
        matchAccessService.assertCanEditMeta(actorUserId, match);

        if (command.getHomeAway() != null) {
            match.setHomeAway(command.getHomeAway());
        }
        // opponentTeamId / opponentName は明示的に上書きする（null も意味を持つ＝相手未登録への切替）。
        match.setOpponentTeamId(command.getOpponentTeamId());
        match.setOpponentName(command.getOpponentName());
        match.setKickoffAt(command.getKickoffAt());
        match.setVenue(command.getVenue());
        if (command.getDurationMinutes() != null) {
            match.setDurationMinutes(command.getDurationMinutes());
        }
        match.setPeriodFormat(command.getPeriodFormat());
        match.setNotes(command.getNotes());

        return matchRepository.save(match);
    }

    // ─────────────────────────────────────────────
    // スコア確定（メタ更新・matches.version 使用・監査）
    // ─────────────────────────────────────────────

    /**
     * 最終スコアを確定する（作成者/記録係/主体チーム ADMIN のみ・03 §C.2）。
     * 本戦・PK 戦スコアを更新し、before/after を監査記録する（03 §C.7）。
     */
    @Transactional
    public MatchEntity finalizeScore(UUID matchId, Long organizationId, Long actorUserId,
                                     Integer homeScore, Integer awayScore,
                                     Integer homePenaltyScore, Integer awayPenaltyScore) {
        MatchEntity match = getMatchOrThrow(matchId, organizationId);
        matchAccessService.assertCanEditMeta(actorUserId, match);

        Integer beforeHome = match.getHomeScore();
        Integer beforeAway = match.getAwayScore();
        Integer beforeHomePk = match.getHomePenaltyScore();
        Integer beforeAwayPk = match.getAwayPenaltyScore();

        match.setHomeScore(homeScore);
        match.setAwayScore(awayScore);
        match.setHomePenaltyScore(homePenaltyScore);
        match.setAwayPenaltyScore(awayPenaltyScore);
        MatchEntity saved = matchRepository.save(match);

        String metadata = String.format(
                "{\"matchId\":\"%s\",\"teamId\":%s,"
                        + "\"before\":{\"home\":%s,\"away\":%s,\"homePk\":%s,\"awayPk\":%s},"
                        + "\"after\":{\"home\":%s,\"away\":%s,\"homePk\":%s,\"awayPk\":%s}}",
                matchId, match.getTeamId(),
                beforeHome, beforeAway, beforeHomePk, beforeAwayPk,
                homeScore, awayScore, homePenaltyScore, awayPenaltyScore);
        auditLogService.record(AuditEventType.MATCH_SCORE_FINALIZED.name(), actorUserId, null,
                match.getTeamId(), organizationId, null, null, null, metadata);
        // 07 §J.2: コミット後にスコア更新を観戦者へ配信する（機微情報を含まないスコアサマリのみ）。
        eventPublisher.publishEvent(MatchLiveUpdateEvent.scoreUpdated(saved));
        return saved;
    }

    // ─────────────────────────────────────────────
    // status 遷移（COMPLETED で MatchCompletedEvent 発火・監査）
    // ─────────────────────────────────────────────

    /**
     * status を遷移する（作成者/記録係/主体チーム ADMIN のみ・03 §C.2）。
     *
     * <ul>
     *   <li>COMPLETED 遷移時は {@code duration_minutes} を必須化（未設定なら 400・02 §E.3）し、
     *       出場記録を確定再計算した後に {@link MatchCompletedEvent} を発火する（05 §H.2）。</li>
     *   <li>全遷移を監査記録する（03 §C.7）。</li>
     * </ul>
     */
    @Transactional
    public MatchEntity changeStatus(UUID matchId, Long organizationId, Long actorUserId,
                                    MatchStatus newStatus) {
        MatchEntity match = getMatchOrThrow(matchId, organizationId);
        matchAccessService.assertCanEditMeta(actorUserId, match);

        MatchStatus before = match.getStatus();
        if (newStatus == MatchStatus.COMPLETED) {
            // COMPLETED 遷移の必須条件は状態モデル類型ごとに異なる（01 §D.6・02 §E.3）。
            // DDL は緩め（NULL 許容）にして Service で締める（症状を隠さず根治）。
            assertCompletable(match);
        }

        match.setStatus(newStatus);
        MatchEntity saved = matchRepository.save(match);

        String metadata = String.format(
                "{\"matchId\":\"%s\",\"teamId\":%s,\"before\":\"%s\",\"after\":\"%s\"}",
                matchId, match.getTeamId(), before, newStatus);
        auditLogService.record(AuditEventType.MATCH_STATUS_CHANGED.name(), actorUserId, null,
                match.getTeamId(), organizationId, null, null, null, metadata);

        // 07 §J.2: 全 status 遷移をコミット後に観戦者へ配信する（順位連携の MatchCompletedEvent とは別関心事）。
        eventPublisher.publishEvent(MatchLiveUpdateEvent.statusChanged(matchId, newStatus));

        if (newStatus == MatchStatus.COMPLETED) {
            // 確定再計算（全 side・記録係/作成者は全 side 編集権あり）
            playingTimeCalculationService.recalculate(saved, null);
            // 順位導出は tournament ドメインがイベントで受ける（原則 5・受信は Phase 5）
            eventPublisher.publishEvent(MatchCompletedEvent.builder()
                    .matchId(saved.getId())
                    .tournamentFixtureId(saved.getTournamentFixtureId())
                    .homeScore(saved.getHomeScore())
                    .awayScore(saved.getAwayScore())
                    .homePenaltyScore(saved.getHomePenaltyScore())
                    .awayPenaltyScore(saved.getAwayPenaltyScore())
                    .status(saved.getStatus())
                    .build());
        }
        return saved;
    }

    /**
     * COMPLETED 遷移の必須条件を状態モデル類型ごとに検証する（01 §D.6・02 §E.3）。
     *
     * <p>DDL はスコア列/duration を NULL 許容にして「器」としては全競技共通にし、
     * 終了に必要な条件は Service で類型別に締める（症状を隠さず根治）。{@code state_model} が
     * 未設定（古いレコード等）の場合は sport から導出してフォールバックする。</p>
     *
     * <ul>
     *   <li><b>CONTINUOUS_TIME</b>（サッカー/フットサル/バスケ）: {@code duration_minutes} 必須
     *       （出場記録の区間を閉じるため・未設定は MATCH_023）。</li>
     *   <li><b>SET_BASED</b>（バレー）: 獲得セット数（home/away_score）が両方確定し、引分けでない
     *       （バレーに D なし）こと。未確定/同数は MATCH_026。</li>
     *   <li><b>TURN_BASED</b>（将棋/囲碁）: 勝敗（home/away_score）が両方確定していること
     *       （個人戦は勝ち 1-0/0-1・引分 0-0・団体戦の親は子ボードの勝ち星集計値）。{@code duration_minutes} は不要。
     *       未確定は MATCH_027。<b>個人戦（子ボード無し）</b>は勝敗あり時に {@code win_method} 必須・引分時は NULL 必須
     *       （MATCH_028）。<b>団体戦の親（{@code countByParentMatchId>0}＝子ボード保有）</b>は勝敗が勝ち星差で決まり
     *       {@code win_method} は常に NULL が正常のため win_method 検証を免除する（§4.3）。</li>
     * </ul>
     */
    private void assertCompletable(MatchEntity match) {
        StateModel stateModel = match.getStateModel() != null
                ? match.getStateModel()
                : (match.getSport() != null ? match.getSport().stateModel() : StateModel.CONTINUOUS_TIME);
        switch (stateModel) {
            case CONTINUOUS_TIME -> {
                if (match.getDurationMinutes() == null) {
                    // duration 未設定では出場記録を確定できない（必須化・症状を隠さない・02 §E.3）
                    throw new BusinessException(MatchErrorCode.MATCH_023);
                }
            }
            case SET_BASED -> {
                // 獲得セット数（matches.home_score/away_score＝match_sets 集計の正本反映・§B.1.2）が両方確定し、
                // 勝者が必要勝ちセット数（best-of-5=3 セット先取）に到達し、かつ引分けでない（バレーに D なし）こと。
                // セット内スコアの正本は match_sets だが、その勝ちセット数は recordSet で matches 列へ集計済み
                // のため、ここでは matches 列に対して VolleyballSetRules で「3 セット先取・引分けなし」を厳密判定する。
                if (!VolleyballSetRules.isMatchCompletable(
                        match.getHomeScore(), match.getAwayScore(), match.getPeriodFormat())) {
                    throw new BusinessException(MatchErrorCode.MATCH_026);
                }
            }
            case TURN_BASED -> {
                // 勝敗（1-0/0-1/0-0 ＝個人戦／勝ち星集計値 ＝団体戦の親）が両方確定していること
                // （引分=同点は許容・§B.1.2）。未確定（スコア NULL）は親/個人戦ともに MATCH_027。
                if (match.getHomeScore() == null || match.getAwayScore() == null) {
                    throw new BusinessException(MatchErrorCode.MATCH_027);
                }
                // 団体戦の親（子ボードを 1 件以上持つ）か個人戦（子ボード無し）かを区別する（§4.3）。
                // 親の勝敗は子ボードの勝ち星集計（home/away_score 勝ち星差）から導出され、win_method は
                // 常に NULL が正常（個別ボードの勝ち方の集合体ゆえ単一の勝ち方に集約できない・§4.3）。
                // よって親は win_method 検証を免除する（症状を隠す回避ではなく、親と個人戦を正しく区別する根治）。
                boolean isTeamMatchParent =
                        match.getParentMatchId() == null
                                && matchRepository.countByParentMatchId(match.getId()) > 0;
                // 団体戦の親: スコア（勝ち星集計）が両方確定していれば COMPLETED 可。
                // 勝ち星差あり（勝者あり）でも win_method=NULL が正常・勝ち星同数（引分）も可ゆえ
                // win_method 検証はスキップする。個人戦（子ボード無し）のみ勝ち方を締める。
                if (!isTeamMatchParent) {
                    // 個人戦（子ボード無し）は勝ち方（win_method）の妥当性も締める（症状を隠さない・§D.7）:
                    //   - 勝敗あり（1-0/0-1＝スコア不一致）: win_method は当該競技の勝ち方列挙値が必須。
                    //   - 引分（0-0＝スコア同点）: win_method は NULL でなければならない（責務分離・§4.2）。
                    boolean draw = match.getHomeScore().equals(match.getAwayScore());
                    if (draw) {
                        if (match.getWinMethod() != null) {
                            // 引分なのに勝ち方が付いている矛盾を弾く（症状を隠さない）
                            throw new BusinessException(MatchErrorCode.MATCH_028);
                        }
                    } else {
                        // 勝敗ありは勝ち方必須かつ当該競技カタログの列挙値であること（NULL/列挙外は 400）
                        if (match.getWinMethod() == null
                                || !WinMethodCatalog.isValid(match.getSport(), match.getWinMethod())) {
                            throw new BusinessException(MatchErrorCode.MATCH_028);
                        }
                    }
                }
            }
            default -> throw new BusinessException(MatchErrorCode.MATCH_024);
        }
    }

    // ─────────────────────────────────────────────
    // 記録モード切替・記録係変更（監査）
    // ─────────────────────────────────────────────

    /**
     * 記録モードを切替える（公式戦⇔共同記録・作成者/主体チーム ADMIN のみ・03 §C.3）。
     */
    @Transactional
    public MatchEntity changeRecordingMode(UUID matchId, Long organizationId, Long actorUserId,
                                           boolean hasScorekeeper, Long scorekeeperUserId) {
        MatchEntity match = getMatchOrThrow(matchId, organizationId);
        matchAccessService.assertCanEditMeta(actorUserId, match);

        boolean beforeMode = match.isHasScorekeeper();
        Long beforeScorekeeper = match.getScorekeeperUserId();

        match.setHasScorekeeper(hasScorekeeper);
        match.setScorekeeperUserId(hasScorekeeper ? scorekeeperUserId : null);
        MatchEntity saved = matchRepository.save(match);

        if (beforeMode != hasScorekeeper) {
            String metadata = String.format(
                    "{\"matchId\":\"%s\",\"before\":%s,\"after\":%s}",
                    matchId, beforeMode, hasScorekeeper);
            auditLogService.record(AuditEventType.MATCH_RECORDING_MODE_CHANGED.name(), actorUserId, null,
                    match.getTeamId(), organizationId, null, null, null, metadata);
        }
        if (!java.util.Objects.equals(beforeScorekeeper, saved.getScorekeeperUserId())) {
            String metadata = String.format(
                    "{\"matchId\":\"%s\",\"before\":%s,\"after\":%s}",
                    matchId, beforeScorekeeper, saved.getScorekeeperUserId());
            auditLogService.record(AuditEventType.MATCH_SCOREKEEPER_CHANGED.name(), actorUserId,
                    saved.getScorekeeperUserId(), match.getTeamId(), organizationId,
                    null, null, null, metadata);
        }
        return saved;
    }

    /**
     * 試合を論理削除する（作成者/主体チーム ADMIN のみ）。
     */
    @Transactional
    public void softDelete(UUID matchId, Long organizationId, Long actorUserId) {
        MatchEntity match = getMatchOrThrow(matchId, organizationId);
        matchAccessService.assertCanEditMeta(actorUserId, match);
        match.softDelete();
        matchRepository.save(match);
    }

    /**
     * 試合作成コマンド。
     *
     * <p>{@code teamId}/{@code createdBy} は呼び出し側（Controller）が認証主体から導出してセットする
     * （クライアントの詐称を信頼しない・マスアサインメント防止・03 §C.4a）。
     * {@code organizationId} は認証テナント。4 入口の文脈（{@code scheduleId}/{@code tournamentFixtureId}）も引き継ぐ。</p>
     */
    @Getter
    @Builder
    public static class CreateCommand {
        private final Long organizationId;
        private final Long teamId;
        private final Long createdBy;
        private final Sport sport;
        private final MatchKind kind;
        private final Long tournamentFixtureId;
        private final Long scheduleId;
        private final HomeAway homeAway;
        private final Long opponentTeamId;
        private final String opponentName;
        private final java.time.LocalDateTime kickoffAt;
        private final String venue;
        private final Integer durationMinutes;
        private final String periodFormat;
        private final boolean hasScorekeeper;
        private final Long scorekeeperUserId;
        private final String notes;
    }

    /**
     * 試合メタ情報更新コマンド（日時・会場・相手・試合長など・03 §C.2）。
     *
     * <p>{@code organizationId}/{@code teamId}/{@code createdBy} は更新対象外（サーバー保持値）。</p>
     */
    @Getter
    @Builder
    public static class UpdateMetaCommand {
        private final HomeAway homeAway;
        private final Long opponentTeamId;
        private final String opponentName;
        private final java.time.LocalDateTime kickoffAt;
        private final String venue;
        private final Integer durationMinutes;
        private final String periodFormat;
        private final String notes;
    }

    /**
     * 試合一覧の任意フィルタ（Phase2C・いずれも NULL は無効化＝絞り込まない）。
     *
     * <p>テナント（organization_id）・チーム（team_id）はパス由来でリポジトリに直接渡すため、
     * 本フィルタには含めない（クライアントから受け取らない・IDOR/越境防止）。</p>
     */
    @Getter
    @Builder
    public static class ListFilter {
        private final MatchStatus status;
        private final MatchKind kind;
        private final Sport sport;
        private final LocalDateTime from;
        private final LocalDateTime to;
    }
}
