package com.mannschaft.app.reservation.service;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.reservation.CancelledBy;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 仮押さえ(PENDING)自動失効の実処理サービス（F03.4.5 §6.3・W2-6）。
 *
 * <p>スケジュール宣言（{@code @BatchEndpoint} / {@code @Scheduled} / {@code @SchedulerLock}）は
 * {@link ReservationPendingExpireBatchService} が持ち、本クラスは「対象抽出」と「1 単位の失効」だけを担う
 * （{@link ReservationWaitlistCleanupBatchService} → {@link ReservationWaitlistService} と同じ役割分担。
 * 注入 {@code Clock} も実処理側である本クラスが保持する）。</p>
 *
 * <h2>なぜ 2 クラスに分けるのか（トランザクション境界の根治）</h2>
 * <p>バッチ全体を 1 つの {@code @Transactional} で囲むと、行単位 try/catch は<b>機能しない</b>。
 * 内側の {@code @Transactional} メソッド（{@link ReservationSlotService#decrementAndReopen} 等）から
 * 例外が抜けた時点で Spring は参加中トランザクションを rollback-only にマークするため、
 * 呼び出し元が例外を握っても最終コミットが {@code UnexpectedRollbackException} で失敗し、
 * 「1 件の失敗が全件を巻き込む」ことになる。
 * 失効 1 単位を {@link Propagation#REQUIRES_NEW} の独立トランザクションにすることで、
 * <b>単位内は原子的（部分失効なし）・単位間は独立（1 件の失敗が他を巻き込まない）</b>を両立させる
 * （AC-6-6 / AC-6-9）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationPendingExpireService {

    /** 申込者へ送る通知の種別（{@code NotificationType.RESERVATION_PENDING_EXPIRED}）。 */
    static final String NOTIFICATION_TYPE = "RESERVATION_PENDING_EXPIRED";

    /** 通知 sourceType（F00 visibility / 受信権の判定キー・予約ドメイン共通）。 */
    static final String SOURCE_TYPE = "RESERVATION";

    /** {@code cancel_reason} に入れる定型文（DB 保存用・FE の翻訳対象ではないため i18n しない）。 */
    static final String CANCEL_REASON = "承認期限切れのため自動キャンセルされました";

    private static final DateTimeFormatter SLOT_AT_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    private final ReservationRepository reservationRepository;
    private final ReservationSlotRepository slotRepository;
    private final ReservationSlotService slotService;
    private final NotificationHelper notificationHelper;
    private final Clock clock;

    // ────────────────────────────────────────────────────────────
    // 対象抽出
    // ────────────────────────────────────────────────────────────

    /**
     * 失効対象を「失効単位」（単枠予約 = 1 行 / グループ = 兄弟行全部）のリストとして抽出する。
     *
     * <p>クエリは<b>最大 3 本固定</b>（候補の代表行 / グループ兄弟行 / 枠）で、対象件数に比例した
     * クエリを出さない（AC-6-17）。グループが 1 件も無ければ兄弟行クエリは走らない。</p>
     *
     * <h2>「現在時刻」の時間基準について（実測で判明した罠）</h2>
     * <p>{@code Clock} Bean は <b>UTC 固定</b>（{@code ClockConfig#utcClock}）である一方、
     * {@code ReservationEntity} の {@code bookedAt} は {@code LocalDateTime.now()}
     * （<b>JVM 既定ゾーン</b>）で書かれる。両者をそのまま比較すると、サーバ既定ゾーンが UTC でない環境
     * （開発機の JST 等）ではオフセット分（+9h）だけ経過時間が短く見積もられ、
     * 「24 時間で自動キャンセル」の設定が実際には 33 時間になる。
     * 「{@code booked_at} からの経過時間」は <b>{@code booked_at} と同じ時間基準</b>で測る必要があるため、
     * 注入 {@code Clock} の<b>瞬間</b>（テストで固定可能）を JVM 既定ゾーンで解釈する。</p>
     *
     * <p>なお、予約ドメインには「枠の日時（{@code slot_date}/{@code start_time}）は業務ローカル時刻だが
     * テナントのタイムゾーンを持たない」という既存の設計負債があり（{@code ReservationWaitlistService}
     * などの既存判定も同じ基準に依存している）、本メソッドはその既存基準に揃えている。
     * テナント TZ の導入は本 PR のスコープ外（別途起票）。</p>
     *
     * @return 失効単位のリスト（対象なしなら空）
     */
    @Transactional(readOnly = true)
    public List<PendingExpireUnit> findExpirableUnits() {
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneId.systemDefault()));

        // 1 本目: slot・policy を join して代表行のみ抽出する（グループは代表行基準で判定）。
        List<ReservationEntity> primaries = reservationRepository.findExpirablePendingPrimaryRows(
                ReservationStatus.PENDING, now, now.toLocalDate(), now.toLocalTime(),
                ReservationPolicyEntity.DEFAULT_PENDING_EXPIRE_HOURS);
        if (primaries.isEmpty()) {
            return List.of();
        }

        // 2 本目: グループ代表行の兄弟行を一括取得する（部分失効を作らないための単位化）。
        Set<UUID> groupIds = primaries.stream()
                .map(ReservationEntity::getGroupId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, List<ReservationEntity>> siblingsByGroup = groupIds.isEmpty()
                ? Map.of()
                : reservationRepository.findByGroupIdInAndStatus(groupIds, ReservationStatus.PENDING).stream()
                        .collect(Collectors.groupingBy(ReservationEntity::getGroupId));

        // 単位を組み立てる（単枠は自身 1 行・グループは兄弟行全部）。
        List<List<ReservationEntity>> rowsPerUnit = new ArrayList<>(primaries.size());
        Set<Long> slotIds = new HashSet<>();
        for (ReservationEntity primary : primaries) {
            List<ReservationEntity> rows = primary.getGroupId() == null
                    ? List.of(primary)
                    : siblingsByGroup.getOrDefault(primary.getGroupId(), List.of(primary));
            rowsPerUnit.add(rows);
            rows.forEach(r -> slotIds.add(r.getReservationSlotId()));
        }

        // 3 本目: 枠を一括取得する（枠復帰 decrementAndReopen 用）。
        Map<Long, ReservationSlotEntity> slotById = slotRepository.findAllById(slotIds).stream()
                .collect(Collectors.toMap(ReservationSlotEntity::getId, s -> s));

        List<PendingExpireUnit> units = new ArrayList<>(primaries.size());
        for (int i = 0; i < primaries.size(); i++) {
            units.add(new PendingExpireUnit(primaries.get(i), rowsPerUnit.get(i), slotById));
        }
        return units;
    }

    // ────────────────────────────────────────────────────────────
    // 1 単位の失効（独立トランザクション）
    // ────────────────────────────────────────────────────────────

    /**
     * 失効単位 1 件を独立トランザクションで失効させる。
     *
     * <p>処理順序: 状態の再確認 → 全行 CANCELLED 化 → 枠復帰 → 申込者へ通知。
     * 通知まで含めて 1 トランザクションのため、途中で失敗すれば全て巻き戻り
     * 「キャンセル済みだが通知されない」中途半端な状態を残さない。失効条件は時刻経過なので、
     * 失敗した単位は次回起動でも対象に残り自己修復する。</p>
     *
     * <p><b>枠復帰は {@link ReservationSlotService#decrementAndReopen} を必ず経由する。</b>
     * DB が実際に FULL→AVAILABLE 遷移を起こしたときのみ {@code ReservationSlotReopenedEvent} が
     * 発行され、{@code ReservationWaitlistNotificationEventListener} が AFTER_COMMIT で購読して
     * キャンセル待ち全員へ通知する（§6.1 の統合点・独自にイベントを撃たない）。</p>
     *
     * @param unit 失効単位
     * @return 失効させた予約行数（既に他経路で状態が変わっていた場合は 0）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int expireUnit(PendingExpireUnit unit) {
        // 抽出は別トランザクションのため、この tx で managed な最新状態を取り直す。
        // 併せて「抽出後に承認/キャンセルされた行」を除外し、booked_count の二重減算を防ぐ。
        List<Long> ids = unit.rows().stream().map(ReservationEntity::getId).toList();
        List<ReservationEntity> rows = reservationRepository.findAllById(ids).stream()
                .filter(r -> r.getStatus() == ReservationStatus.PENDING)
                .toList();
        if (rows.isEmpty()) {
            log.debug("仮押さえ自動失効スキップ: 抽出後に状態が変化していた reservationIds={}", ids);
            return 0;
        }
        if (rows.size() != ids.size()) {
            // グループの一部だけが PENDING でなくなっている＝別経路で部分遷移が起きた異常。
            // ここで残りを失効させると部分失効を追認することになるため、握り潰さず記録して見送る。
            log.warn("仮押さえ自動失効スキップ: グループの一部だけ状態が異なる（部分失効を作らない）"
                    + " groupId={}, 期待={}件, PENDING={}件", unit.primary().getGroupId(), ids.size(), rows.size());
            return 0;
        }

        for (ReservationEntity row : rows) {
            row.cancel(CANCEL_REASON, CancelledBy.SYSTEM);
        }
        reservationRepository.saveAll(rows);

        for (ReservationEntity row : rows) {
            ReservationSlotEntity slot = unit.slotsById().get(row.getReservationSlotId());
            if (slot == null) {
                // 枠が解決できないと booked_count を戻せない。握り潰さず記録する（次回も対象に残る）。
                log.warn("仮押さえ自動失効: 枠が解決できず枠復帰をスキップ reservationId={}, slotId={}",
                        row.getId(), row.getReservationSlotId());
                continue;
            }
            slotService.decrementAndReopen(slot);
        }

        notifyApplicant(unit.primary(), unit.slotsById().get(unit.primary().getReservationSlotId()));

        log.info("仮押さえ自動失効: teamId={}, reservationId={}, groupId={}, {}行",
                unit.primary().getTeamId(), unit.primary().getId(), unit.primary().getGroupId(), rows.size());
        return rows.size();
    }

    /**
     * 申込者本人へ「仮予約が期限切れになった」通知を送る（グループは代表行基準で 1 通のみ）。
     *
     * <p>{@code sourceType/sourceId} は {@code RESERVATION}/予約ID、{@code scopeType/scopeId} は
     * {@code TEAM}/チームID、{@code actorId} はシステム発のため {@code null}
     * （{@link ReservationReminderDispatchBatchService} と同じ決め方）。</p>
     */
    private void notifyApplicant(ReservationEntity primary, ReservationSlotEntity slot) {
        String title = "仮予約が期限切れになりました";
        String body;
        if (slot != null) {
            String slotAt = LocalDateTime.of(slot.getSlotDate(), slot.getStartTime()).format(SLOT_AT_FORMAT);
            String slotTitle = slot.getTitle() != null ? slot.getTitle() : "ご予約";
            body = String.format("%s の「%s」は承認期限を過ぎたため自動的にキャンセルされました。",
                    slotAt, slotTitle);
        } else {
            body = "お申し込みの仮予約は承認期限を過ぎたため自動的にキャンセルされました。";
        }
        String actionUrl = "/teams/" + primary.getTeamId() + "/reservations";

        notificationHelper.notify(
                primary.getUserId(),
                NOTIFICATION_TYPE,
                title,
                body,
                SOURCE_TYPE,
                primary.getId(),
                NotificationScopeType.TEAM,
                primary.getTeamId(),
                actionUrl,
                null);
    }

    /**
     * 失効単位。単枠予約は {@code rows} が 1 行、グループ予約は兄弟行全部を含む。
     *
     * @param primary   代表行（{@code is_group_primary = TRUE}）。通知の宛先・本文解決に使う
     * @param rows      失効させる全行（グループは兄弟行全部＝部分失効を作らないための単位）
     * @param slotsById 枠の一括取得結果（枠復帰 {@code decrementAndReopen} に渡す）
     */
    public record PendingExpireUnit(
            ReservationEntity primary,
            List<ReservationEntity> rows,
            Map<Long, ReservationSlotEntity> slotsById) {
    }
}
