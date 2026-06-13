package com.mannschaft.app.match.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.catalog.ReasonCodeCatalog;
import com.mannschaft.app.match.catalog.SportEventCatalog;
import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.PeriodType;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.entity.MatchEventEntity;
import com.mannschaft.app.match.live.MatchLiveUpdateEvent;
import com.mannschaft.app.match.repository.MatchEventRepository;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * F08.10 タイムラインイベントの記録/更新/削除サービス（03 §C.4a/C.4b・02 §E）。
 *
 * <p>記録の都度、当該 match の出場記録を {@link PlayingTimeCalculationService} でフル再計算する（02 §E.2）。
 * 認可は {@link MatchAccessService} へ委譲し、IDOR 帰属チェーン（親 match テナント取得 → 子は match_id スコープ・
 * 子 ID 直引き禁止）を通す（03 §C.4）。</p>
 *
 * <h3>サーバー側の検証・導出（03 §C.4a/C.4b）</h3>
 * <ul>
 *   <li><b>recorded_by_team_id はサーバー導出</b>: 認証主体の所属チームから決定し、外部入力を信頼しない
 *       （マスアサインメント防止）。共同記録では自サイド以外を自名義で記録できない。</li>
 *   <li><b>team_side ↔ recorded_by_team_id の整合不変条件（03 §C.4a）</b>:
 *       HOME イベントの {@code recorded_by_team_id} は {@code match.teamId} と一致、
 *       AWAY イベントは登録相手（{@code opponent_team_id}≠NULL）なら {@code opponent_team_id} と一致、
 *       未登録相手（{@code opponent_team_id}=NULL）はホーム/記録係が代行記録するため {@code match.teamId} を許容。
 *       矛盾は 403（MATCH_025・相手サイドを自名義で捏造する余地を塞ぐドメイン二重防御）。
 *       <b>「認証主体（principal）→所属チーム の導出」と「その principal がそのサイドを記録してよいか」の認可は
 *       Controller（Phase 2-D）が {@link MatchAccessService#canRecordTimeline} /
 *       {@link MatchAccessService#canEditTeamData} で実施</b>する（責務分界）。本不変条件は
 *       {@code recorded_by_team_id} が既にサーバー導出済みである前提での整合性検証である。</li>
 *   <li><b>linked_event_id の同一 match 帰属検証</b>: 連鎖先が同一 match の既存イベントであることを確認
 *       （別試合・他テナント ID の指定は 404・親子不一致統一）。連鎖相手の team_side が記録中イベントと
 *       同一サイドであることも確認する（03 §C.4a）。</li>
 *   <li><b>event_type のカタログ検証</b>: {@code match.sport} のカタログに含まれない event_type は 400。</li>
 *   <li><b>card_reason_code の二段検証</b>: カタログ列挙値かつ event_type 整合（警告→C 系/退場→S 系/CS・
 *       非対象イベントへの付与は 400）。</li>
 *   <li><b>note/custom_label のサニタイズ</b>: 制御文字除去＋trim＋HTML 不可（{@link MatchTextSanitizer}）。
 *       最大長は @Size（255/64）で Request DTO（Phase 2-D）が担保するが、ここでも防御的に切り詰める。</li>
 * </ul>
 *
 * <p>{@code @Transactional} は match ドメイン内に閉じる（原則 5）。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MatchEventService {

    /** note の最大長（01 §B.2・@Size(max=255)）。 */
    private static final int NOTE_MAX = 255;
    /** custom_label の最大長（01 §B.2・@Size(max=64)）。 */
    private static final int CUSTOM_LABEL_MAX = 64;
    /** player_name 系の最大長（01 §B.2）。 */
    private static final int NAME_MAX = 128;

    /** minute / stoppage の業務範囲上限（03 §C.4b）。 */
    private static final int MINUTE_MAX = 150;
    /** jersey_number の業務範囲上限（03 §C.4b）。 */
    private static final int JERSEY_MAX = 999;

    /** 退場系（out 確定）。 */
    private static final Set<MatchEventType> OUT_EVENTS =
            EnumSet.of(MatchEventType.SUB_OUT, MatchEventType.RED_CARD, MatchEventType.SECOND_YELLOW);

    private final MatchEventRepository matchEventRepository;
    private final MatchService matchService;
    private final MatchAccessService matchAccessService;
    private final PlayingTimeCalculationService playingTimeCalculationService;
    /** F08.10 / 07 §J.2 ライブ配信トリガー（AFTER_COMMIT で {@code MatchLiveBroadcastListener} が受ける）。 */
    private final ApplicationEventPublisher eventPublisher;

    // ─────────────────────────────────────────────
    // 記録
    // ─────────────────────────────────────────────

    /**
     * タイムラインイベントを記録する（03 §C）。
     *
     * @param matchId        親 match ID（UUIDv7）
     * @param organizationId 認証テナント
     * @param actorUserId    操作者ユーザー ID
     * @param command        記録コマンド（recorded_by_team_id はサーバー導出値）
     * @return 作成イベント
     */
    @Transactional
    public MatchEventEntity record(UUID matchId, Long organizationId, Long actorUserId,
                                   EventCommand command) {
        MatchEntity match = matchService.getMatchOrThrow(matchId, organizationId);
        matchAccessService.assertCanRecordTimeline(actorUserId, match);

        validateEventType(match, command.getEventType());
        validateCardReasonCode(match, command.getEventType(), command.getCardReasonCode());
        validateNumericRanges(command);
        validateSideOwnership(match, command.getTeamSide(), command.getRecordedByTeamId());
        validateLinkedEvent(matchId, command.getTeamSide(), command.getLinkedEventId());

        // recorded_by_team_id はサーバー導出値（command.recordedByTeamId は呼び出し側が認証主体所属から決定）
        MatchEventEntity event = MatchEventEntity.builder()
                .matchId(matchId)
                .minute(command.getMinute())
                .stoppageMinute(command.getStoppageMinute())
                .period(command.getPeriod())
                .eventType(command.getEventType())
                .cardReasonCode(command.getCardReasonCode())
                .customLabel(truncate(MatchTextSanitizer.sanitize(command.getCustomLabel()), CUSTOM_LABEL_MAX))
                .teamSide(command.getTeamSide())
                .playerUserId(command.getPlayerUserId())
                .playerName(truncate(MatchTextSanitizer.sanitize(command.getPlayerName()), NAME_MAX))
                .jerseyNumber(command.getJerseyNumber())
                .relatedPlayerUserId(command.getRelatedPlayerUserId())
                .relatedPlayerName(truncate(MatchTextSanitizer.sanitize(command.getRelatedPlayerName()), NAME_MAX))
                .note(truncate(MatchTextSanitizer.sanitize(command.getNote()), NOTE_MAX))
                .linkedEventId(command.getLinkedEventId())
                .detail(command.getDetail())
                .recordedByTeamId(command.getRecordedByTeamId())
                .sortSeq(command.getSortSeq())
                .build();

        MatchEventEntity saved = matchEventRepository.save(event);
        triggerRecalculation(match, actorUserId);
        // 07 §J.2: コミット後に観戦者へ差分配信する（publish のみ・配信は AFTER_COMMIT リスナーが担う）。
        eventPublisher.publishEvent(MatchLiveUpdateEvent.eventAdded(matchId, saved));
        return saved;
    }

    // ─────────────────────────────────────────────
    // 更新
    // ─────────────────────────────────────────────

    /**
     * タイムラインイベントを更新する。親子 match_id 不一致は 404（IDOR・03 §C.4）。
     */
    @Transactional
    public MatchEventEntity update(UUID matchId, UUID eventId, Long organizationId, Long actorUserId,
                                   EventCommand command) {
        MatchEntity match = matchService.getMatchOrThrow(matchId, organizationId);
        MatchEventEntity event = getEventInMatchOrThrow(matchId, eventId);
        matchAccessService.assertCanRecordTimeline(actorUserId, match);

        validateEventType(match, command.getEventType());
        validateCardReasonCode(match, command.getEventType(), command.getCardReasonCode());
        validateNumericRanges(command);
        // 更新では recorded_by_team_id は不変（作成時のサーバー導出値を維持）。
        // 新しい team_side が既存名義と整合するか検証し、サイドの付け替えによる相手分捏造を遮断する（03 §C.4a）。
        validateSideOwnership(match, command.getTeamSide(), event.getRecordedByTeamId());
        validateLinkedEvent(matchId, command.getTeamSide(), command.getLinkedEventId());

        event.setMinute(command.getMinute());
        event.setStoppageMinute(command.getStoppageMinute());
        event.setPeriod(command.getPeriod());
        event.setEventType(command.getEventType());
        event.setCardReasonCode(command.getCardReasonCode());
        event.setCustomLabel(truncate(MatchTextSanitizer.sanitize(command.getCustomLabel()), CUSTOM_LABEL_MAX));
        event.setTeamSide(command.getTeamSide());
        event.setPlayerUserId(command.getPlayerUserId());
        event.setPlayerName(truncate(MatchTextSanitizer.sanitize(command.getPlayerName()), NAME_MAX));
        event.setJerseyNumber(command.getJerseyNumber());
        event.setRelatedPlayerUserId(command.getRelatedPlayerUserId());
        event.setRelatedPlayerName(truncate(MatchTextSanitizer.sanitize(command.getRelatedPlayerName()), NAME_MAX));
        event.setNote(truncate(MatchTextSanitizer.sanitize(command.getNote()), NOTE_MAX));
        event.setLinkedEventId(command.getLinkedEventId());
        event.setDetail(command.getDetail());
        event.setSortSeq(command.getSortSeq());

        MatchEventEntity saved = matchEventRepository.save(event);
        triggerRecalculation(match, actorUserId);
        // 07 §J.2: コミット後に観戦者へ差分配信する。
        eventPublisher.publishEvent(MatchLiveUpdateEvent.eventUpdated(matchId, saved));
        return saved;
    }

    // ─────────────────────────────────────────────
    // 削除
    // ─────────────────────────────────────────────

    /**
     * タイムラインイベントを削除する。親子 match_id 不一致は 404（IDOR・03 §C.4）。
     * 削除後に出場記録を再計算する。
     */
    @Transactional
    public void delete(UUID matchId, UUID eventId, Long organizationId, Long actorUserId) {
        MatchEntity match = matchService.getMatchOrThrow(matchId, organizationId);
        MatchEventEntity event = getEventInMatchOrThrow(matchId, eventId);
        matchAccessService.assertCanRecordTimeline(actorUserId, match);

        matchEventRepository.delete(event);
        triggerRecalculation(match, actorUserId);
        // 07 §J.2: コミット後に観戦者へ削除を配信する（ID のみ・機微情報を載せない）。
        eventPublisher.publishEvent(MatchLiveUpdateEvent.eventDeleted(matchId, eventId));
    }

    // ─────────────────────────────────────────────
    // IDOR 帰属チェーン（子 ID 直引き禁止・必ず match_id スコープ）
    // ─────────────────────────────────────────────

    /**
     * 親子 match_id 帰属を検証してイベントを取得する。
     *
     * <p>{@code findById}（JpaRepository 由来）で取得した後に <b>match_id 一致を必ず検証</b>する。
     * 不一致（別 match のイベント ID 指定）は 404 で統一し存在を漏らさない（03 §C.4・親子不一致 404）。
     * これにより推測 ID による越境（IDOR）を遮断する。</p>
     */
    private MatchEventEntity getEventInMatchOrThrow(UUID matchId, UUID eventId) {
        MatchEventEntity event = matchEventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(MatchErrorCode.MATCH_002));
        if (!matchId.equals(event.getMatchId())) {
            // 親子不一致は 404（存在を漏らさない・IDOR 統一）
            throw new BusinessException(MatchErrorCode.MATCH_002);
        }
        return event;
    }

    // ─────────────────────────────────────────────
    // 検証
    // ─────────────────────────────────────────────

    /** event_type が当該競技カタログで利用可能か（03 §C.4b・400）。 */
    private void validateEventType(MatchEntity match, MatchEventType eventType) {
        if (eventType == null) {
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }
        if (!SportEventCatalog.isAllowed(match.getSport(), eventType)) {
            throw new BusinessException(MatchErrorCode.MATCH_020);
        }
    }

    /**
     * card_reason_code の二段検証（03 §C.4b・<b>競技別ディスパッチ</b>）。
     *
     * <p>理由コードの体系は競技ごとに異なる（サッカー/フットサル＝C/S コード、バスケ＝FIBA ファウルコード）。
     * {@code match.sport} に応じて {@link ReasonCodeCatalog} が当該競技のカタログへ委譲し、
     * 競技間の流用（サッカー C/S をバスケへ／バスケファウルコードをサッカーへ）を弾く（03 §5・症状を隠さず根治）。</p>
     *
     * <ul>
     *   <li>{@code code==null}: 理由コードは任意ゆえ常に OK（後から補完可能）。</li>
     *   <li>{@code code!=null}: 当該競技カタログの列挙値かつ event_type 整合（非整合は 400・MATCH_021）。</li>
     * </ul>
     */
    private void validateCardReasonCode(MatchEntity match, MatchEventType eventType, String code) {
        if (!ReasonCodeCatalog.isValid(match.getSport(), eventType, code)) {
            throw new BusinessException(MatchErrorCode.MATCH_021);
        }
    }

    /** minute/stoppage/jersey の業務範囲（03 §C.4b・400）。 */
    private void validateNumericRanges(EventCommand command) {
        checkRange(command.getMinute(), 0, MINUTE_MAX);
        checkRange(command.getStoppageMinute(), 0, MINUTE_MAX);
        checkRange(command.getJerseyNumber(), 0, JERSEY_MAX);
    }

    private void checkRange(Integer value, int min, int max) {
        if (value != null && (value < min || value > max)) {
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }
    }

    /**
     * team_side ↔ recorded_by_team_id の整合不変条件（03 §C.4a・自名義捏造防止のドメイン二重防御）。
     *
     * <ul>
     *   <li>{@code recordedByTeamId==null}: 名義未確定（縮退・後段補完）として整合検証はスキップ。</li>
     *   <li>{@code team_side=HOME}: {@code recordedByTeamId == match.teamId} のみ許容。</li>
     *   <li>{@code team_side=AWAY} かつ {@code opponent_team_id≠NULL}（登録相手）:
     *       {@code recordedByTeamId == opponent_team_id} のみ許容。</li>
     *   <li>{@code team_side=AWAY} かつ {@code opponent_team_id=NULL}（未登録相手）:
     *       ホーム/記録係が相手分を代行記録するため {@code recordedByTeamId == match.teamId} を許容。</li>
     * </ul>
     *
     * <p>矛盾は 403（MATCH_025）。なお「principal がそのサイドを記録してよいか」の認可は Controller が
     * {@link MatchAccessService#canRecordTimeline} / {@link MatchAccessService#canEditTeamData} で実施し、
     * 本検証は recorded_by_team_id がサーバー導出済みである前提でのドメイン整合性チェックである。</p>
     */
    private void validateSideOwnership(MatchEntity match, TeamSide teamSide, Long recordedByTeamId) {
        if (teamSide == null) {
            // team_side は @NotNull で Request DTO（Phase 2-D）が担保するが、防御的に弾く
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }
        if (recordedByTeamId == null) {
            // 名義が未確定の縮退ケースは整合検証の対象外（後段補完）
            return;
        }
        Long homeTeamId = match.getTeamId();
        Long awayTeamId = match.getOpponentTeamId();
        boolean ok;
        if (teamSide == TeamSide.HOME) {
            ok = recordedByTeamId.equals(homeTeamId);
        } else { // AWAY
            if (awayTeamId != null) {
                // 登録相手: 相手名義のみ許容（自チームが相手サイドを自名義で捏造するのを遮断）
                ok = recordedByTeamId.equals(awayTeamId);
            } else {
                // 未登録相手: ホーム/記録係が代行記録 → 主体チーム名義を許容
                ok = recordedByTeamId.equals(homeTeamId);
            }
        }
        if (!ok) {
            throw new BusinessException(MatchErrorCode.MATCH_025);
        }
    }

    /**
     * linked_event_id の同一 match 帰属検証＋連鎖相手 side 整合（03 §C.4a）。
     *
     * <ul>
     *   <li>連鎖先が同一 match の既存イベントでない場合は 404（越境・親子不一致統一）。</li>
     *   <li>連鎖相手の {@code team_side} が記録中イベントと同一サイドであることを確認する。
     *       異サイドへの連鎖（例: 自サイドのアシストを相手サイドのゴールに紐づける）を遮断し、
     *       相手サイドの集計を汚染する余地を塞ぐ（自名義捏造防止と同趣旨）。不一致は 404（連鎖先不一致統一）。</li>
     * </ul>
     *
     * @param matchId       親 match ID
     * @param teamSide      記録中イベントの team_side
     * @param linkedEventId 連鎖相手イベント ID（NULL 可）
     */
    private void validateLinkedEvent(UUID matchId, TeamSide teamSide, UUID linkedEventId) {
        if (linkedEventId == null) {
            return;
        }
        MatchEventEntity linked = matchEventRepository.findById(linkedEventId).orElse(null);
        if (linked == null || !matchId.equals(linked.getMatchId())) {
            // 別試合・他テナントのイベント ID を連鎖相手に指定する越境を遮断（404 統一）
            throw new BusinessException(MatchErrorCode.MATCH_022);
        }
        if (teamSide != null && linked.getTeamSide() != null && teamSide != linked.getTeamSide()) {
            // 連鎖は同一サイド内で完結すべき（異サイド連鎖は相手集計を汚染しうる・404 統一）
            throw new BusinessException(MatchErrorCode.MATCH_022);
        }
    }

    private void triggerRecalculation(MatchEntity match, Long actorUserId) {
        // 編集権限スコープ内（記録した側の side）に削除同期を限定する（相手分破壊防止・02 §E.5a）。
        // 公式戦（記録係）は全 side 編集権を持つため null（全 side 同期）。
        // 共同記録は記録した自チームの side に限定する。
        Set<TeamSide> editable = resolveEditableSides(match, actorUserId);
        playingTimeCalculationService.recalculate(match, editable);
    }

    /**
     * 操作者が削除同期できる team_side を解決する（02 §E.5a）。
     *
     * <ul>
     *   <li>公式戦（記録係）: 全 side 編集権 → {@code null}（全 side 同期）。</li>
     *   <li>共同記録: 操作者が ADMIN/DEPUTY のチーム side のみ（相手分は破壊しない）。</li>
     * </ul>
     */
    private Set<TeamSide> resolveEditableSides(MatchEntity match, Long actorUserId) {
        if (match.isHasScorekeeper()) {
            return null; // 記録係は全 side 編集権
        }
        Set<TeamSide> sides = EnumSet.noneOf(TeamSide.class);
        // HOME=主体チーム / AWAY=相手チームの固定対応（NEUTRAL でも team_side は HOME/AWAY 2 値・01 §未解決 4）
        if (matchAccessService.canEditTeamData(actorUserId, match, match.getTeamId())) {
            sides.add(TeamSide.HOME);
        }
        if (match.getOpponentTeamId() != null
                && matchAccessService.canEditTeamData(actorUserId, match, match.getOpponentTeamId())) {
            sides.add(TeamSide.AWAY);
        }
        return sides;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    /**
     * イベント記録/更新コマンド。
     *
     * <p>{@code recordedByTeamId} は呼び出し側（Controller・Phase 2-D）が認証主体の所属から導出してセットする
     * （クライアントの詐称を信頼しない・マスアサインメント防止・03 §C.4a）。
     * <b>「principal → 所属チームの導出」「その principal が当該サイドを記録してよいか」の認可は Controller が
     * {@link MatchAccessService#canRecordTimeline} / {@link MatchAccessService#canEditTeamData} で実施</b>し、
     * Service 側は導出済みの {@code recordedByTeamId} と {@code teamSide} の<b>整合不変条件</b>を
     * {@code validateSideOwnership} で二重防御する（責務分界）。</p>
     */
    @Getter
    @Builder
    public static class EventCommand {
        private final Integer minute;
        private final Integer stoppageMinute;
        private final PeriodType period;
        private final MatchEventType eventType;
        private final String cardReasonCode;
        private final String customLabel;
        private final TeamSide teamSide;
        private final Long playerUserId;
        private final String playerName;
        private final Integer jerseyNumber;
        private final Long relatedPlayerUserId;
        private final String relatedPlayerName;
        private final String note;
        private final UUID linkedEventId;
        private final String detail;
        private final Long recordedByTeamId;
        private final int sortSeq;
    }
}
