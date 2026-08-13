package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.reservation.ApprovalMode;
import com.mannschaft.app.reservation.CancelledBy;
import com.mannschaft.app.reservation.RecurringWeekSkipReason;
import com.mannschaft.app.reservation.ReservationCancelScope;
import com.mannschaft.app.reservation.ReservationConfirmScope;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.ReservationMapper;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.dto.AdminNoteRequest;
import com.mannschaft.app.reservation.dto.CancelReservationRequest;
import com.mannschaft.app.reservation.dto.CreateReservationRequest;
import com.mannschaft.app.reservation.dto.RescheduleRequest;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.dto.ReservationStatsResponse;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationRecurringBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.event.ReservationCancelledByMemberEvent;
import com.mannschaft.app.reservation.event.ReservationConfirmedEvent;
import com.mannschaft.app.reservation.event.ReservationCreatedEvent;
import com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationRecurringBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 予約サービス。予約のCRUD・ステータス遷移・統計を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private static final List<ReservationStatus> ACTIVE_STATUSES =
            List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);

    private static final DateTimeFormatter BOOKED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("M月d日 HH:mm");

    private final ReservationRepository reservationRepository;
    private final ReservationSlotRepository slotRepository;
    private final ReservationLineRepository lineRepository;
    private final ReservationSlotService slotService;
    private final ReservationMapper reservationMapper;
    private final NameResolverService nameResolverService;
    private final ApplicationEventPublisher eventPublisher;
    private final AccessControlService accessControlService;
    /** 予約閲覧の view ゲート（会員 or 公開）。機能C グリッドと同一述語を共有する（§4.C）。 */
    private final ReservationViewAccessGuard viewAccessGuard;
    private final ReservationPolicyService reservationPolicyService;
    /** 機能B: 予約作成時に対象枠と overlap する予約不可枠を検出するためのブロック時間参照。 */
    private final ReservationBlockedTimeRepository blockedTimeRepository;
    /** F03.4.5 §4 W2-2: 定期予約不可枠（週次繰り返し）の active ルール参照。 */
    private final ReservationRecurringBlockedTimeRepository recurringBlockedTimeRepository;
    /** 機能B: 予約不可枠の overlap 判定を共有する単一ユーティリティ（§5.B / §4.2）。 */
    private final ReservationUnavailabilityChecker unavailabilityChecker;
    /** F03.4.3: 一覧のグループ要約（GroupSummaryDto）を一括解決するコンポーネント（§5.6 #10）。 */
    private final ReservationGroupSummaryResolver groupSummaryResolver;
    /** F03.4.5 §6.1: 予約成立時に同一 (slot, user) のキャンセル待ちを CONVERTED へ消し込む。 */
    private final ReservationWaitlistService waitlistService;
    /** F03.4.5 §6.4: 予約作成のレートリミット（グループ作成と同一バケットを共有・§6.4）。 */
    private final ReservationCreateRateLimiter createRateLimiter;
    private final Clock clock;

    /**
     * チームの予約一覧をページング取得する。
     *
     * @param teamId   チームID
     * @param status   ステータスフィルタ（null の場合は全件）
     * @param pageable ページング情報
     * @return 予約レスポンスのページ
     */
    public Page<ReservationResponse> listTeamReservations(Long teamId, String status, Pageable pageable) {
        // F03.4.3 §5.6 #10: グループは代表行 1 件に折りたたむ（単枠は is_group_primary=TRUE で従来どおり）。
        Page<ReservationEntity> page;
        if (status != null) {
            ReservationStatus reservationStatus = ReservationStatus.valueOf(status);
            page = reservationRepository.findByTeamIdAndStatusAndIsGroupPrimaryTrueOrderByBookedAtDesc(
                    teamId, reservationStatus, pageable);
        } else {
            page = reservationRepository.findByTeamIdAndIsGroupPrimaryTrueOrderByBookedAtDesc(teamId, pageable);
        }
        return new PageImpl<>(enrichList(page.getContent()), pageable, page.getTotalElements());
    }

    /**
     * 予約詳細を取得する。
     *
     * <p><strong>認可（F03.4 認可漏れ根治）:</strong> 管理者・副管理者（ADMIN + DEPUTY_ADMIN／SYSTEM_ADMIN）
     * <em>または</em> 予約の本人（所有者）のみ閲覧可能。それ以外（同一チームの一般会員が他人の予約を覗く等）は
     * {@link ReservationErrorCode#RESERVATION_PERMISSION_DENIED}（HTTP 403）を投げる。</p>
     *
     * <p>この所有権ゲートは public な read 入口（本メソッド）に置く。共有 private mapper に置くと
     * バッチ/リスナー（SecurityContext 無し）を巻き添えにするため。</p>
     *
     * @param teamId        チームID
     * @param reservationId 予約ID
     * @return 予約レスポンス
     */
    public ReservationResponse getReservation(Long teamId, Long reservationId) {
        ReservationEntity entity = findReservationOrThrow(teamId, reservationId);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        boolean isAdmin = accessControlService.isAdminOrAbove(currentUserId, teamId, "TEAM");
        boolean isOwner = currentUserId.equals(entity.getUserId());
        if (!isAdmin && !isOwner) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_PERMISSION_DENIED);
        }
        return enrich(entity);
    }

    /**
     * 予約を作成する。
     *
     * @param teamId  チームID
     * @param userId  ユーザーID
     * @param request 作成リクエスト
     * @return 作成された予約レスポンス
     */
    @Transactional
    public ReservationResponse createReservation(Long teamId, Long userId, CreateReservationRequest request) {
        // 予約認可ゲート（Service 一本化）。機能C グリッドと同一述語（会員 or 公開）を共有する（§4.C）。
        // 既定（allow_public_reservation=false）→ チーム所属（SUPPORTER 以上＝memberships 存在）必須。
        // 裏設定 ON → 所属チェックをスキップ（匿名は呼出元の認証層で 401 担保）。
        viewAccessGuard.assertCanView(teamId, userId);

        // F03.4.5 §6.4: 予約作成のレートリミット（1 ユーザー 1 分 5 回）。グループ作成
        // （ReservationGroupService.createGroup）と同一 zone を共有し、単枠 5 回＋グループ 5 回の
        // 買い占めを防ぐ。認可（view ゲート）通過後に消費する順序はキャンセル待ち登録と揃えている。
        createRateLimiter.assertNotRateLimited(userId);

        return doCreateReservation(teamId, userId, request, null);
    }

    /**
     * 定期予約（F03.4.5 §6.2 W2-5）の 1 週分を作成する。
     *
     * <p><b>⚠ 認可ゲートとレートリミットを持たない。</b>{@link ReservationRecurringService} が
     * series 単位で 1 回だけ適用したうえで本メソッドを週ごとに呼ぶ設計であり（AC-5-11:
     * 1 series = 1 消費）、<b>Controller や他ドメインから直接呼んではならない</b>。
     * 誤用を検知するため、{@code ReservationRecurringServiceTest} が
     * 「{@code createRecurring} が assertCanView とレートリミッタをそれぞれ 1 回だけ呼ぶ」ことを固定している。</p>
     *
     * <p>public なのは Spring AOP プロキシ経由で<b>週ごとに独立したトランザクション</b>を開くためである
     * （{@link ReservationRecurringService} は非トランザクションのオーケストレーターなので、
     * ここが REQUIRED で入っても新規トランザクションになる）。1 週の失敗が他の週を巻き込まない。</p>
     *
     * @param teamId   チームID
     * @param userId   予約者ユーザーID
     * @param request  作成リクエスト（当該週の {@code reservationSlotId} を指す）
     * @param seriesId 付与する series ID（単発として作る場合は null）
     * @return 作成された予約レスポンス
     */
    @Transactional
    public ReservationResponse createReservationForSeries(
            Long teamId, Long userId, CreateReservationRequest request, java.util.UUID seriesId) {
        return doCreateReservation(teamId, userId, request, seriesId);
    }

    /**
     * 予約作成の本体（認可ゲート・レートリミットを<b>含まない</b>）。
     *
     * @param seriesId 定期予約の series ID（単発は null）
     */
    private ReservationResponse doCreateReservation(
            Long teamId, Long userId, CreateReservationRequest request, java.util.UUID seriesId) {
        // Issue #2538: reservationSlotId はリクエスト由来（利用者が任意に指定できる）のため、
        // teamId スコープの finder で解決する。他チームの枠 id を渡した場合は SLOT_NOT_FOUND（404）で秘匿する。
        ReservationSlotEntity slot = slotService.getSlotEntity(teamId, request.getReservationSlotId());

        if (!slot.isAvailable()) {
            throw new BusinessException(
                    slot.getSlotStatus() == com.mannschaft.app.reservation.SlotStatus.FULL
                            ? ReservationErrorCode.SLOT_FULL
                            : ReservationErrorCode.SLOT_CLOSED);
        }

        // 機能B（§5.B）＋F03.4.5 §4.2: 対象枠が単発/定期いずれかの予約不可枠と overlap するなら
        // 予約作成を拒否（RESERVATION_009・400）。判定は空き枠除外・グリッドと共有の
        // 単一 overlap ユーティリティを用いる（別実装厳禁）。
        List<ReservationBlockedTimeEntity> blocks =
                blockedTimeRepository.findByTeamIdAndBlockedDateOrderByStartTimeAsc(teamId, slot.getSlotDate());
        List<ReservationRecurringBlockedTimeEntity> recurringRules =
                recurringBlockedTimeRepository.findByTeamIdAndIsActiveTrue(teamId);
        if (unavailabilityChecker.isBlockedByAny(slot, blocks, recurringRules)) {
            throw new BusinessException(ReservationErrorCode.BLOCKED_TIME_CONFLICT);
        }

        boolean exists = reservationRepository.existsByReservationSlotIdAndUserIdAndStatusIn(
                request.getReservationSlotId(), userId, ACTIVE_STATUSES);
        if (exists) {
            throw new BusinessException(ReservationErrorCode.DUPLICATE_RESERVATION);
        }

        // F03.4.2 §5.6/§3.1: 枠のライン整合検証。
        //   ライン軸枠（slot.line_id 非 NULL）= そのライン専用の枠。予約行のラインは枠から自動決定され、
        //   request.lineId が指定されていて枠と食い違う場合は 400（RESERVATION_038・枠の帰属と矛盾する予約を防ぐ）。
        //   共通枠（slot.line_id NULL）は従来どおりユーザー選択の lineId をそのまま保存（挙動後退ゼロ。
        //   共通枠の有効ライン検証は F03.4.3 で追加予定）。
        Long effectiveLineId;
        if (slot.getLineId() != null) {
            if (request.getLineId() != null && !slot.getLineId().equals(request.getLineId())) {
                throw new BusinessException(ReservationErrorCode.SLOT_LINE_MISMATCH);
            }
            effectiveLineId = slot.getLineId();
        } else {
            effectiveLineId = request.getLineId();
        }

        ReservationEntity entity = ReservationEntity.builder()
                .reservationSlotId(request.getReservationSlotId())
                .lineId(effectiveLineId)
                .teamId(teamId)
                .userId(userId)
                .userNote(request.getUserNote())
                // F03.4.5 §6.2 W2-5: 定期予約の series ID（単発は null = 従来と完全同一・AC-5-2）
                .recurringSeriesId(seriesId)
                .build();

        ReservationEntity saved = reservationRepository.save(entity);
        slotService.incrementAndCheckFull(slot);

        // F03.4.5 §6.1: 予約成立時、同一 (slot, user) のキャンセル待ち WAITING を CONVERTED へ消し込む
        // （同一 tx・reservation ドメイン内）。WAITING が無ければ何もしない（べき等）。
        waitlistService.markConvertedIfExists(slot.getId(), userId);

        // 承認モードを解決する（枠値→チーム設定→AUTO の優先順で必ず非 null）。
        ApprovalMode mode = reservationPolicyService.resolveApprovalMode(teamId, slot);

        // AUTO の場合は同一トランザクション内で即時確定し、確定イベントを発行する。
        // MANUAL の場合は PENDING のまま維持し、管理者の手動承認（confirmReservation）を待つ。
        if (mode == ApprovalMode.AUTO) {
            saved.confirm();
            saved = reservationRepository.save(saved);
            publishConfirmedEvent(saved, slot, userId);
        }

        String bookedAtFormatted = saved.getBookedAt().format(BOOKED_AT_FORMATTER);
        // 管理者通知の出し分けには「実効承認モード」を渡す（生の slot 値ではなく解決後の mode）。
        eventPublisher.publishEvent(new ReservationCreatedEvent(
                saved.getTeamId(),
                saved.getId(),
                userId,
                mode,
                slot.getTitle(),
                bookedAtFormatted
        ));

        log.info("予約作成: teamId={}, reservationId={}, userId={}, approvalMode={}",
                teamId, saved.getId(), userId, mode);
        return enrich(saved);
    }

    /**
     * 予約を確定する（単票・従来契約）。
     *
     * @param teamId        チームID
     * @param reservationId 予約ID
     * @return 更新された予約レスポンス
     */
    @Transactional
    public ReservationResponse confirmReservation(Long teamId, Long reservationId) {
        return confirmReservation(teamId, reservationId, ReservationConfirmScope.THIS_ONLY);
    }

    /**
     * 予約を確定する（F03.4.5 §6.2 W2-5: {@code scope=SERIES} で series 一括承認）。
     *
     * <p>{@link ReservationConfirmScope#SERIES} のときは、当該予約を確定したうえで同一 series の
     * 残りの PENDING も確定する。対象行は<b>当該チームに属する行のみ</b>
     * （{@code findByRecurringSeriesIdAndTeamIdOrderById}）で、PENDING でない行はスキップして
     * 明細（{@code recurringConfirm}）を返す（AC-5-9）。</p>
     *
     * <p>MANUAL 承認チームで 12 週分の PENDING が並ぶ問題への対処であり、単票承認は従来どおり可能。</p>
     *
     * @param teamId        チームID
     * @param reservationId 予約ID（series の起点として扱う行）
     * @param scope         承認範囲（null は {@code THIS_ONLY} と同義）
     * @return 更新された予約レスポンス
     */
    @Transactional
    public ReservationResponse confirmReservation(
            Long teamId, Long reservationId, ReservationConfirmScope scope) {
        ReservationEntity entity = findReservationOrThrow(teamId, reservationId);
        assertNotGroupRow(entity);

        if (!entity.isConfirmable()) {
            throw new BusinessException(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }

        entity.confirm();
        ReservationEntity saved = reservationRepository.save(entity);

        // 手動承認時もリマインド対象（設計書 §3）。確定が実際に起きた経路のみで発行する。
        // isConfirmable() を満たした PENDING のみがここに到達するため、二重発行は起きない。
        ReservationSlotEntity slot = slotService.getSlotEntity(saved.getReservationSlotId());
        publishConfirmedEvent(saved, slot, saved.getUserId());

        log.info("予約確定: teamId={}, reservationId={}, scope={}", teamId, reservationId, scope);

        ReservationResponse response = enrich(saved);
        if (scope == ReservationConfirmScope.SERIES && saved.getRecurringSeriesId() != null) {
            response = response.toBuilder()
                    .recurringConfirm(confirmFollowingInSeries(teamId, saved, slot))
                    .build();
        }
        return response;
    }

    /**
     * 管理者として予約をキャンセルする。
     *
     * @param teamId        チームID
     * @param reservationId 予約ID
     * @param request       キャンセルリクエスト
     * @return 更新された予約レスポンス
     */
    @Transactional
    public ReservationResponse cancelByAdmin(Long teamId, Long reservationId, CancelReservationRequest request) {
        ReservationEntity entity = findReservationOrThrow(teamId, reservationId);
        assertNotGroupRow(entity);

        if (!entity.isCancellable()) {
            throw new BusinessException(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }

        entity.cancel(request.getReason(), CancelledBy.ADMIN);
        ReservationEntity saved = reservationRepository.save(entity);

        ReservationSlotEntity slot = slotService.getSlotEntity(entity.getReservationSlotId());
        slotService.decrementAndReopen(slot);

        log.info("予約キャンセル(管理者): teamId={}, reservationId={}", teamId, reservationId);
        return enrich(saved);
    }

    /**
     * ユーザーとして予約をキャンセルする。
     *
     * @param userId        ユーザーID
     * @param reservationId 予約ID
     * @param request       キャンセルリクエスト
     * @return 更新された予約レスポンス
     */
    @Transactional
    public ReservationResponse cancelByUser(Long userId, Long reservationId, CancelReservationRequest request) {
        ReservationEntity entity = reservationRepository.findByIdAndUserId(reservationId, userId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
        assertNotGroupRow(entity);

        if (!entity.isCancellable()) {
            throw new BusinessException(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }

        ReservationSlotEntity slot = slotService.getSlotEntity(entity.getReservationSlotId());

        // F03.4.5 §6.2 W2-5: 「以降すべて」を選んだ定期予約は、起点回も含めて<b>全回を同じ規則</b>で
        // 処理する（AC-5-7・検分 MUST④）。起点回だけ締切超過で例外にすると
        // 「今日の回は締切だが来週以降はまとめて消したい」という当然の操作ができない。
        // 単発予約（series NULL）に THIS_AND_FOLLOWING を指定した場合は従来経路（無害）。
        if (request.getScope() == ReservationCancelScope.THIS_AND_FOLLOWING
                && entity.getRecurringSeriesId() != null) {
            return cancelSeriesFromBase(entity, slot, request.getReason());
        }

        if (!entity.isCancellable()) {
            throw new BusinessException(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }

        // F03.4 ⑤: 会員（USER）キャンセルは締切（cancel_deadline_hours・既定 24）を実適用する。
        // 枠開始時刻の deadline 時間前を過ぎていればキャンセルを拒否する（管理者キャンセルは対象外）。
        // 判定基準は注入 Clock（LocalDateTime.now() 直書きは CI 破壊地雷のため禁止）。
        if (isCancelDeadlinePassed(entity.getTeamId(), slot, new HashMap<>())) {
            throw new BusinessException(ReservationErrorCode.CANCEL_DEADLINE_PASSED);
        }

        entity.cancel(request.getReason(), CancelledBy.USER);
        ReservationEntity saved = reservationRepository.save(entity);

        slotService.decrementAndReopen(slot);

        publishMemberCancelledEvent(saved, slot, userId);

        log.info("予約キャンセル(ユーザー): userId={}, reservationId={}, scope={}",
                userId, reservationId, request.getScope());

        return enrich(saved);
    }

    /**
     * キャンセル締切（{@code cancel_deadline_hours}）を過ぎているかを判定する（単票・series 共通）。
     *
     * <p>ポリシーは<b>行のチーム</b>で引く。同一チームの行が並んでもクエリを増やさないよう
     * 呼び出し側が持つ {@code deadlineHoursByTeam} にメモ化する。</p>
     */
    private boolean isCancelDeadlinePassed(
            Long teamId, ReservationSlotEntity slot, Map<Long, Integer> deadlineHoursByTeam) {
        int deadlineHours = deadlineHoursByTeam.computeIfAbsent(teamId,
                t -> reservationPolicyService.getOrDefault(t).getCancelDeadlineHours());
        LocalDateTime deadline =
                LocalDateTime.of(slot.getSlotDate(), slot.getStartTime()).minusHours(deadlineHours);
        // Issue #2526: deadline は slot_date/start_time（業務ローカル時刻）由来のため、
        // Clock（UTC固定）の瞬間を JVM 既定ゾーンで解釈し直してから比較する
        // （ReservationPendingExpireService#findExpirableUnits と同型）。
        return LocalDateTime.now(clock.withZone(UserZoneLocalDateTimeParser.SERVER_ZONE)).isAfter(deadline);
    }

    /** 会員キャンセルの通知イベントを発行する（管理者キャンセルは従来どおりイベントなし）。 */
    private void publishMemberCancelledEvent(
            ReservationEntity saved, ReservationSlotEntity slot, Long actorUserId) {
        eventPublisher.publishEvent(new ReservationCancelledByMemberEvent(
                saved.getTeamId(), saved.getId(), actorUserId, slot.getTitle()));
    }

    /**
     * series 内の「当該日より後」の自分の予約を続けてキャンセルする（F03.4.5 §6.2・AC-5-7 / AC-5-8）。
     *
     * <p><b>IDOR（AC-5-8）</b>: 対象行は {@code findByRecurringSeriesIdAndUserIdOrderById} で
     * <b>本人所有の行だけ</b>を引く。series ID を知っていても他人の予約はキャンセルできない。</p>
     *
     * <p><b>過去回は不変</b>: 起点の枠日付より後の回のみを対象にする。既に来店済み・キャンセル済みの回を
     * 遡って書き換えない。</p>
     *
     * <p><b>ロック順序（AC-5-6）</b>: {@code slot_date} 昇順に処理する。同一 series を複数セッションが
     * 同時に触ったときのデッドロックを避けるため、順序を実装依存（id 順や取得順）にしない。</p>
     *
     * <p><b>締切超過はスキップ</b>: 各行に既存のキャンセル検証（{@code isCancellable} と
     * {@code cancel_deadline_hours}）を<b>行ごとに</b>適用し、通らない回は例外にせず明細に記録する。
     * 「以降すべて」は複数回に対する 1 操作であり、直近 1 回が締切超過だからといって
     * 2 ヶ月先の回まで残す挙動は利用者の意図に反する。</p>
     *
     * <p><b>起点回も同じ規則で扱う（検分 MUST④）</b>: 起点回が締切超過・状態不整合であっても
     * 例外にせずスキップ明細に載せ、以降の回の処理を続ける。起点回だけ例外にすると
     * 「今日の回は締切だが来週以降はまとめて消したい」という当然の操作ができない。
     * 全回がスキップされて 0 件になってもエラーにせず明細を返す（AC-5-13 と同じ思想）。</p>
     *
     * @param base       起点予約（当該回・まだキャンセルしていない）
     * @param baseSlot   起点予約の枠
     * @param reason     キャンセル理由（全行に同一文言を記録）
     * @return 起点予約のレスポンス（{@code recurringCancel} に結果明細を含む）
     */
    private ReservationResponse cancelSeriesFromBase(
            ReservationEntity base, ReservationSlotEntity baseSlot, String reason) {
        java.util.UUID seriesId = base.getRecurringSeriesId();

        // IDOR（AC-5-8）: series ID は「知っていれば操作できる鍵」ではない。userId をクエリ条件に含めた
        // finder 以外から series を引かないことで、他人の行に触れる経路を構造的に消す。
        List<ReservationEntity> siblings = reservationRepository
                .findByRecurringSeriesIdAndUserIdOrderById(seriesId, base.getUserId()).stream()
                .filter(r -> !r.getId().equals(base.getId()))
                .toList();

        Map<Long, ReservationSlotEntity> slotById = siblings.isEmpty()
                ? Map.of()
                : slotRepository.findAllById(siblings.stream()
                                .map(ReservationEntity::getReservationSlotId).toList()).stream()
                        .collect(Collectors.toMap(ReservationSlotEntity::getId, s -> s));

        List<ReservationResponse.RecurringWeekOutcomeDto> cancelled = new java.util.ArrayList<>();
        List<ReservationResponse.RecurringWeekOutcomeDto> skipped = new java.util.ArrayList<>();

        // 起点回を先頭に、以降は「当該日より後」だけを対象にする（過去回は不変）。
        // 並びは slot_date 昇順に固定する（AC-5-6 のロック順序）。
        List<SeriesRow> targets = new java.util.ArrayList<>();
        targets.add(new SeriesRow(base, baseSlot));
        for (ReservationEntity row : siblings) {
            ReservationSlotEntity slot = slotById.get(row.getReservationSlotId());
            if (slot == null) {
                // 枠が解決できないと締切判定も枠復帰もできない。握り潰さず明細に載せる。
                log.warn("定期予約の以降キャンセル: 枠が解決できずスキップ reservationId={}, slotId={}",
                        row.getId(), row.getReservationSlotId());
                skipped.add(new ReservationResponse.RecurringWeekOutcomeDto(
                        null, RecurringWeekSkipReason.NOT_CANCELLABLE, row.getId()));
                continue;
            }
            if (!slot.getSlotDate().isAfter(baseSlot.getSlotDate())) {
                continue;
            }
            targets.add(new SeriesRow(row, slot));
        }
        targets.sort(Comparator.comparing((SeriesRow t) -> t.slot().getSlotDate())
                .thenComparing(t -> t.slot().getStartTime()));

        // 締切は<b>行ごとに</b>（その行のチームのポリシーで）判定する。1 回だけ判定して全行に流用すると
        // AC-5-7 の「各行に既存のキャンセル検証を適用」を満たさない。チーム単位でメモ化して
        // 同一チームの行が並んでもクエリを増やさない。
        Map<Long, Integer> deadlineHoursByTeam = new HashMap<>();
        List<ReservationEntity> toSave = new java.util.ArrayList<>();
        List<ReservationSlotEntity> slotsToReopen = new java.util.ArrayList<>();
        boolean baseCancelled = false;

        for (SeriesRow target : targets) {
            ReservationEntity row = target.row();
            ReservationSlotEntity slot = target.slot();
            if (!row.isCancellable()) {
                skipped.add(new ReservationResponse.RecurringWeekOutcomeDto(
                        slot.getSlotDate(), RecurringWeekSkipReason.NOT_CANCELLABLE, row.getId()));
                continue;
            }
            if (isCancelDeadlinePassed(row.getTeamId(), slot, deadlineHoursByTeam)) {
                // 締切超過は例外にしない。「以降すべて」は複数回に対する 1 操作であり、直近 1 回が
                // 締切超過だからといって 2 ヶ月先の回まで残す挙動は利用者の意図に反する。
                skipped.add(new ReservationResponse.RecurringWeekOutcomeDto(
                        slot.getSlotDate(), RecurringWeekSkipReason.CANCEL_DEADLINE_PASSED, row.getId()));
                continue;
            }
            row.cancel(reason, CancelledBy.USER);
            toSave.add(row);
            slotsToReopen.add(slot);
            cancelled.add(new ReservationResponse.RecurringWeekOutcomeDto(
                    slot.getSlotDate(), null, row.getId()));
            if (row.getId().equals(base.getId())) {
                baseCancelled = true;
            }
        }

        if (!toSave.isEmpty()) {
            reservationRepository.saveAll(toSave);
            // 枠復帰は decrementAndReopen を必ず経由する（§6.1 キャンセル待ち通知の唯一の統合点）。
            slotsToReopen.forEach(slotService::decrementAndReopen);
        }

        // 会員キャンセル通知は「起点回が実際にキャンセルされたとき」だけ従来どおり 1 回発行する
        // （スキップされた回について「キャンセルされました」と通知しない）。
        if (baseCancelled) {
            publishMemberCancelledEvent(base, baseSlot, base.getUserId());
        }

        log.info("定期予約 以降すべてキャンセル: userId={}, seriesId={}, キャンセル={}件, スキップ={}件",
                base.getUserId(), seriesId, cancelled.size(), skipped.size());

        return enrich(base).toBuilder()
                .recurringCancel(new ReservationResponse.RecurringCancelDto(
                        seriesId, cancelled.size(), List.copyOf(cancelled), List.copyOf(skipped)))
                .build();
    }

    /** series 内の 1 行と対応する枠のペア（並び替え・判定のための内部レコード）。 */
    private record SeriesRow(ReservationEntity row, ReservationSlotEntity slot) {
    }

    /**
     * series 内の残りの PENDING を一括承認する（F03.4.5 §6.2・AC-5-9）。
     *
     * <p><b>認可</b>: 対象行は {@code findByRecurringSeriesIdAndTeamIdOrderById} で
     * <b>URL の {@code teamId} に属する行だけ</b>を引く。呼び出し元 EP の
     * {@code @PreAuthorize isScopeAdmin(#teamId)} は {@code teamId} の管理者性しか見ないため、
     * 「その teamId の管理者が触れる行の集合」をクエリ側で閉じることで各行に認可を効かせる。</p>
     *
     * <p>PENDING 以外（既に確定・キャンセル済み）はスキップして明細に記録する。
     * グループ所属行（{@code group_id} 非 NULL）は単票遷移が禁止されているためスキップ対象とする。</p>
     *
     * @param teamId   チームID（認可スコープ）
     * @param base     起点となる確定済み予約（当該回）
     * @param baseSlot 起点予約の枠
     * @return 承認結果の明細
     */
    private ReservationResponse.RecurringConfirmDto confirmFollowingInSeries(
            Long teamId, ReservationEntity base, ReservationSlotEntity baseSlot) {
        java.util.UUID seriesId = base.getRecurringSeriesId();

        // 認可（AC-5-9）: teamId をクエシー条件に含めた finder のみを使い、当該チームの行しか掴めないようにする。
        List<ReservationEntity> siblings = reservationRepository
                .findByRecurringSeriesIdAndTeamIdOrderById(seriesId, teamId).stream()
                .filter(r -> !r.getId().equals(base.getId()))
                .toList();

        Map<Long, ReservationSlotEntity> slotById = siblings.isEmpty()
                ? Map.of()
                : slotRepository.findAllById(siblings.stream()
                                .map(ReservationEntity::getReservationSlotId).toList()).stream()
                        .collect(Collectors.toMap(ReservationSlotEntity::getId, s -> s));

        List<ReservationResponse.RecurringWeekOutcomeDto> confirmed = new java.util.ArrayList<>();
        List<ReservationResponse.RecurringWeekOutcomeDto> skipped = new java.util.ArrayList<>();
        confirmed.add(new ReservationResponse.RecurringWeekOutcomeDto(
                baseSlot.getSlotDate(), null, base.getId()));

        List<SeriesRow> ordered = new java.util.ArrayList<>();
        for (ReservationEntity row : siblings) {
            ReservationSlotEntity slot = slotById.get(row.getReservationSlotId());
            if (slot == null) {
                log.warn("定期予約の一括承認: 枠が解決できずスキップ reservationId={}, slotId={}",
                        row.getId(), row.getReservationSlotId());
                skipped.add(new ReservationResponse.RecurringWeekOutcomeDto(
                        null, RecurringWeekSkipReason.NOT_PENDING, row.getId()));
                continue;
            }
            ordered.add(new SeriesRow(row, slot));
        }
        ordered.sort(Comparator.comparing((SeriesRow t) -> t.slot().getSlotDate())
                .thenComparing(t -> t.slot().getStartTime()));

        List<ReservationEntity> toSave = new java.util.ArrayList<>();
        List<SeriesRow> newlyConfirmed = new java.util.ArrayList<>();
        for (SeriesRow target : ordered) {
            ReservationEntity row = target.row();
            // グループ所属行は単票遷移が禁止（F03.4.3 §4）。series 一括承認でも例外にせずスキップし、
            // グループはグループ API で承認させる（部分遷移によるグループ状態の分裂を作らない）。
            if (row.getGroupId() != null || row.getStatus() != ReservationStatus.PENDING) {
                skipped.add(new ReservationResponse.RecurringWeekOutcomeDto(
                        target.slot().getSlotDate(), RecurringWeekSkipReason.NOT_PENDING, row.getId()));
                continue;
            }
            row.confirm();
            toSave.add(row);
            newlyConfirmed.add(target);
            confirmed.add(new ReservationResponse.RecurringWeekOutcomeDto(
                    target.slot().getSlotDate(), null, row.getId()));
        }

        if (!toSave.isEmpty()) {
            reservationRepository.saveAll(toSave);
            // 確定リマインドは行ごとに 1 セット必要（来店は週ごとに別々に発生する）。
            newlyConfirmed.forEach(t -> publishConfirmedEvent(t.row(), t.slot(), t.row().getUserId()));
        }

        log.info("定期予約 series 一括承認: teamId={}, seriesId={}, 承認={}件, スキップ={}件",
                teamId, seriesId, confirmed.size(), skipped.size());

        return new ReservationResponse.RecurringConfirmDto(
                seriesId, confirmed.size(), List.copyOf(confirmed), List.copyOf(skipped));
    }

    /**
     * 予約の series 所属を解除する（F03.4.5 §6.2・AC-5-13）。
     *
     * <p>「毎週繰り返す」で 2 週目以降が全てスキップされ、成立が起点週 1 件だけになったときに
     * {@link ReservationRecurringService} が呼ぶ。1 行だけの series は単発予約と区別する意味がないため
     * NULL に戻す。</p>
     *
     * <p>独立した {@code @Transactional} メソッドにしているのは、呼び出し元が
     * 非トランザクションのオーケストレーターであるため（週ごとの作成 tx はすでにコミット済み）。</p>
     *
     * @param reservationId 対象予約ID
     */
    @Transactional
    public void clearRecurringSeries(Long reservationId) {
        reservationRepository.findById(reservationId).ifPresent(entity -> {
            entity.clearRecurringSeries();
            reservationRepository.save(entity);
        });
    }

    /**
     * 予約を完了する。
     *
     * @param teamId        チームID
     * @param reservationId 予約ID
     * @return 更新された予約レスポンス
     */
    @Transactional
    public ReservationResponse completeReservation(Long teamId, Long reservationId) {
        ReservationEntity entity = findReservationOrThrow(teamId, reservationId);
        assertNotGroupRow(entity);
        entity.complete();
        ReservationEntity saved = reservationRepository.save(entity);
        log.info("予約完了: teamId={}, reservationId={}", teamId, reservationId);
        return enrich(saved);
    }

    /**
     * ノーショーとしてマークする。
     *
     * @param teamId        チームID
     * @param reservationId 予約ID
     * @return 更新された予約レスポンス
     */
    @Transactional
    public ReservationResponse markNoShow(Long teamId, Long reservationId) {
        ReservationEntity entity = findReservationOrThrow(teamId, reservationId);
        assertNotGroupRow(entity);
        entity.noShow();
        ReservationEntity saved = reservationRepository.save(entity);
        log.info("予約ノーショー: teamId={}, reservationId={}", teamId, reservationId);
        return enrich(saved);
    }

    /**
     * 予約をリスケジュールする。
     *
     * @param teamId        チームID
     * @param reservationId 予約ID
     * @param request       リスケジュールリクエスト
     * @return 更新された予約レスポンス
     */
    @Transactional
    public ReservationResponse rescheduleReservation(Long teamId, Long reservationId, RescheduleRequest request) {
        ReservationEntity entity = findReservationOrThrow(teamId, reservationId);
        assertNotGroupRow(entity);

        // oldSlot: entity は findReservationOrThrow で既に teamId 検証済みのため、その reservationSlotId は
        // 自チームの枠であることが保証されている（teamId スコープ不要）。
        ReservationSlotEntity oldSlot = slotService.getSlotEntity(entity.getReservationSlotId());
        slotService.decrementAndReopen(oldSlot);

        // newSlot: request.newSlotId はリクエスト由来（利用者が任意に指定できる）のため、
        // teamId スコープの finder で解決する（Issue #2538）。他チームの枠 id は 404 で秘匿する。
        ReservationSlotEntity newSlot = slotService.getSlotEntity(teamId, request.getNewSlotId());
        if (!newSlot.isAvailable()) {
            throw new BusinessException(ReservationErrorCode.SLOT_FULL);
        }

        entity.reschedule(request.getNewSlotId());
        ReservationEntity saved = reservationRepository.save(entity);
        slotService.incrementAndCheckFull(newSlot);

        log.info("予約リスケジュール: teamId={}, reservationId={}, newSlotId={}", teamId, reservationId, request.getNewSlotId());
        return enrich(saved);
    }

    /**
     * 管理者メモを更新する。
     *
     * @param teamId        チームID
     * @param reservationId 予約ID
     * @param request       メモリクエスト
     * @return 更新された予約レスポンス
     */
    @Transactional
    public ReservationResponse updateAdminNote(Long teamId, Long reservationId, AdminNoteRequest request) {
        ReservationEntity entity = findReservationOrThrow(teamId, reservationId);
        // F03.4.3 §4: 非代表行のメモは一覧（代表行のみ返す）に浮上せず事実上消失するため 400=042 で拒否する。
        // 代表行への更新は許可（グループのメモは代表行に集約）。
        if (entity.getGroupId() != null && !Boolean.TRUE.equals(entity.getIsGroupPrimary())) {
            throw new BusinessException(ReservationErrorCode.GROUP_ROW_DIRECT_OPERATION_NOT_ALLOWED);
        }
        entity.updateAdminNote(request.getNote());
        ReservationEntity saved = reservationRepository.save(entity);
        log.info("管理者メモ更新: teamId={}, reservationId={}", teamId, reservationId);
        return enrich(saved);
    }

    /**
     * スロットに紐付く予約一覧を取得する。
     *
     * @param slotId スロットID
     * @return 予約レスポンスリスト
     */
    public List<ReservationResponse> listReservationsBySlot(Long slotId) {
        List<ReservationEntity> reservations =
                reservationRepository.findByReservationSlotIdOrderByBookedAtAsc(slotId);
        return enrichList(reservations);
    }

    /**
     * ユーザーの予約一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 予約レスポンスリスト
     */
    public List<ReservationResponse> listMyReservations(Long userId) {
        // F03.4.3 §5.6 #10: グループは代表行 1 件に折りたたむ。
        List<ReservationEntity> reservations =
                reservationRepository.findByUserIdAndIsGroupPrimaryTrueOrderByBookedAtDesc(userId);
        return enrichList(reservations);
    }

    /**
     * ユーザーの直近の予約一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 予約レスポンスリスト
     */
    public List<ReservationResponse> listUpcomingReservations(Long userId) {
        // 直近予約は「申込時刻（booked_at）」ではなく「来店日時（枠の日付＋開始時刻）」で判定する。
        // Issue #2526（表に無い同型バグとして監査で発見）: 来店日時は業務ローカル時刻のため、
        // Clock の瞬間を JVM 既定ゾーンで解釈し直してから比較する（cancel_deadline 等と同様）。
        LocalDateTime now = LocalDateTime.now(clock.withZone(UserZoneLocalDateTimeParser.SERVER_ZONE));
        List<ReservationEntity> reservations =
                reservationRepository.findUpcomingByUserId(userId, now.toLocalDate(), now.toLocalTime());
        return enrichList(reservations);
    }

    /**
     * チームの予約統計を取得する。
     *
     * @param teamId チームID
     * @return 予約統計レスポンス
     */
    public ReservationStatsResponse getStats(Long teamId) {
        // F03.4.3 §5.6 #4: 「グループ=1予約」で数える（代表行絞り。単枠は常に TRUE のため従来どおり）。
        long pending = reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(teamId, ReservationStatus.PENDING);
        long confirmed = reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(teamId, ReservationStatus.CONFIRMED);
        long cancelled = reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(teamId, ReservationStatus.CANCELLED);
        long completed = reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(teamId, ReservationStatus.COMPLETED);
        long noShow = reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(teamId, ReservationStatus.NO_SHOW);
        long total = pending + confirmed + cancelled + completed + noShow;

        return new ReservationStatsResponse(total, pending, confirmed, cancelled, completed, noShow);
    }

    /**
     * 予約確定イベントを発行する。スロットの日付・開始時刻を合成した {@code slotStartAt} を保持させる。
     *
     * @param reservation 確定済み予約エンティティ
     * @param slot        対象スロット（タイトル・開始日時の取得元）
     * @param actorUserId 確定を引き起こした操作者のユーザーID
     */
    private void publishConfirmedEvent(ReservationEntity reservation, ReservationSlotEntity slot, Long actorUserId) {
        LocalDateTime slotStartAt = LocalDateTime.of(slot.getSlotDate(), slot.getStartTime());
        eventPublisher.publishEvent(new ReservationConfirmedEvent(
                reservation.getTeamId(),
                reservation.getId(),
                actorUserId,
                slotStartAt,
                slot.getTitle()
        ));
    }

    /**
     * グループ所属行への単票状態遷移を 400 = RESERVATION_042 で拒否する（F03.4.3 §4 / §5.1）。
     *
     * <p>部分キャンセル・部分承認による booked_count 不整合とグループ状態の分裂を構造的に防ぐ。
     * 対象 6 メソッド: cancelByUser / cancelByAdmin / confirmReservation / completeReservation /
     * markNoShow / rescheduleReservation。グループ操作は {@link ReservationGroupService} の
     * 一括 API で行う。単票 GET（読み取り）は全行許可のためガードしない。</p>
     */
    private void assertNotGroupRow(ReservationEntity entity) {
        if (entity.getGroupId() != null) {
            throw new BusinessException(ReservationErrorCode.GROUP_ROW_DIRECT_OPERATION_NOT_ALLOWED);
        }
    }

    /**
     * 予約が指定チームに属することを検証する（越境 BOLA 防止の軽量ゲート）。
     *
     * <p>リマインダー等、{@code reservationId} のみを引数に取る下流サービス
     * （{@link ReservationReminderService}）へ委譲する前に、URL のスコープ（{@code teamId}）に
     * 予約が属することを確かめる。属さない／不在なら
     * {@link ReservationErrorCode#RESERVATION_NOT_FOUND}（404・存在秘匿）を投げる。</p>
     *
     * <p><b>なぜ必要か:</b> リマインダー系 EP の {@code @PreAuthorize} は {@code #teamId} の
     * 管理者性だけを見る。{@code reservationId} と {@code teamId} の帰属を結ぶこの検証が無いと、
     * あるチームの正規管理者が別チームの {@code reservationId} を推測して当該予約のリマインダーを
     * 読み書きできる（テナント境界越えの BOLA）。GET 詳細（{@link #getReservation}）や
     * 状態遷移 6 メソッドは {@link #findReservationOrThrow} で既に帰属を検証しているが、
     * リマインダー系は {@code reservationId} のみを下流へ渡していたため本ゲートで補う。</p>
     *
     * @param teamId        URL 上のチームID（認可スコープ）
     * @param reservationId 予約ID
     * @throws BusinessException 予約が当該チームに属さない／存在しない場合
     */
    public void assertReservationInTeam(Long teamId, Long reservationId) {
        findReservationOrThrow(teamId, reservationId);
    }

    /**
     * 予約を取得する。存在しない場合は例外をスローする。
     */
    private ReservationEntity findReservationOrThrow(Long teamId, Long reservationId) {
        return reservationRepository.findByIdAndTeamId(reservationId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
    }

    /**
     * 予約リストにスロット・ラインのサマリを付与して変換する。
     * スロット/ラインはバッチ取得し N+1 を回避する。
     *
     * @param entities 予約エンティティリスト
     * @return スロットサマリを含む予約レスポンスリスト
     */
    private List<ReservationResponse> enrichList(List<ReservationEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        Set<Long> slotIds = entities.stream()
                .map(ReservationEntity::getReservationSlotId)
                .collect(Collectors.toSet());
        Set<Long> lineIds = entities.stream()
                .map(ReservationEntity::getLineId)
                .collect(Collectors.toSet());
        Map<Long, ReservationSlotEntity> slots = slotRepository.findAllById(slotIds).stream()
                .collect(Collectors.toMap(ReservationSlotEntity::getId, s -> s));
        Map<Long, ReservationLineEntity> lines = lineRepository.findAllById(lineIds).stream()
                .collect(Collectors.toMap(ReservationLineEntity::getId, l -> l));
        Set<Long> userIds = entities.stream()
                .map(ReservationEntity::getUserId)
                .collect(Collectors.toSet());
        Map<Long, String> userNames = nameResolverService.resolveUserFullNames(userIds);
        // F03.4.3 §5.6 #10: グループ所属行にはグループ要約（枠数・末尾終了時刻・メニュー名）を後付けする。
        Map<Long, ReservationResponse.GroupSummaryDto> groupSummaries = groupSummaryResolver.resolve(entities);
        return entities.stream()
                .map(e -> withGroupSummary(
                        withUserName(
                                reservationMapper.toReservationResponse(
                                        e, slots.get(e.getReservationSlotId()), lines.get(e.getLineId())),
                                e.getUserId(),
                                userNames.getOrDefault(e.getUserId(), "不明なユーザー")),
                        groupSummaryFor(groupSummaries, e)))
                .toList();
    }

    /**
     * 単一の予約にスロット・ラインのサマリを付与して変換する。
     *
     * @param entity 予約エンティティ
     * @return スロットサマリを含む予約レスポンス
     */
    private ReservationResponse enrich(ReservationEntity entity) {
        ReservationSlotEntity slot = slotRepository.findById(entity.getReservationSlotId()).orElse(null);
        ReservationLineEntity line = lineRepository.findById(entity.getLineId()).orElse(null);
        String userName = nameResolverService.resolveUserFullName(entity.getUserId());
        ReservationResponse response = withUserName(
                reservationMapper.toReservationResponse(entity, slot, line),
                entity.getUserId(), userName);
        // F03.4.3 §5.6 #10: グループ所属行にはグループ要約を後付けする（単枠は null 維持）。
        return withGroupSummary(response,
                groupSummaryFor(groupSummaryResolver.resolve(List.of(entity)), entity));
    }

    /**
     * グループ要約マップから該当エントリを null 安全に引く
     * （未採番 ID の場合 {@code Map.of()} は {@code get(null)} で NPE を投げるため明示ガード）。
     */
    private ReservationResponse.GroupSummaryDto groupSummaryFor(
            Map<Long, ReservationResponse.GroupSummaryDto> summaries, ReservationEntity entity) {
        if (entity.getId() == null || summaries.isEmpty()) {
            return null;
        }
        return summaries.get(entity.getId());
    }

    /**
     * グループ要約を後付けする（null の場合は元のレスポンスをそのまま返す＝単枠の既存契約不変）。
     */
    private ReservationResponse withGroupSummary(
            ReservationResponse response, ReservationResponse.GroupSummaryDto group) {
        if (group == null) {
            return response;
        }
        return response.toBuilder().group(group).build();
    }

    /**
     * 既存レスポンスの identifier を会員実名（userName）付きで再構築する。
     *
     * @param response 元のレスポンス
     * @param userId   ユーザーID
     * @param userName 会員実名
     * @return userName を含む identifier を持つレスポンス
     */
    private ReservationResponse withUserName(ReservationResponse response, Long userId, String userName) {
        ReservationResponse.ReservationIdentifierDto base = response.getIdentifier();
        return response.toBuilder()
                .identifier(new ReservationResponse.ReservationIdentifierDto(
                        base.reservationSlotId(), base.lineId(), base.teamId(), userId, userName))
                .build();
    }
}
