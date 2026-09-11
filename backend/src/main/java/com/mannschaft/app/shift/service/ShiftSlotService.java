package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.shift.ShiftAssignedUserIds;
import com.mannschaft.app.shift.ShiftAssignmentStatus;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.BulkCreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.CreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.ShiftSlotResponse;
import com.mannschaft.app.shift.dto.SlotAssignmentPatchRequest;
import com.mannschaft.app.shift.dto.UpdateShiftSlotRequest;
import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * シフト枠サービス。シフト枠のCRUD・一括操作を担当する。
 *
 * <p><b>認可（認可根治 Wave6）:</b> 本サービスはかつて {@code AccessControlService} を
 * import すらしておらず、操作者を受け取る口が無かった。結果として
 * {@code ShiftSlotController} の全エンドポイントが無認可で、任意チームのシフト枠を
 * 閲覧・改変・割当できる状態だった。本改修で全 public メソッドが操作者 {@code userId} を
 * 受け取り、<b>シフト枠の所属スケジュール実体から解決した teamId</b> に対して per-scope 認可する
 *（パス変数・クエリの scope 値を鵜呑みにしないことで BOLA を封鎖する）。</p>
 *
 * <p>粒度は同ドメインの既存実装に合わせる:</p>
 * <ul>
 *   <li><b>参照</b>（{@code listSlots} / {@code getSlot}）: 当該チームのメンバー、ただし
 *       SUPPORTER は不可。{@code ShiftPdfService#checkMemberAndNotSupporter} と同一方針
 *       （PDF で SUPPORTER に伏せている情報を生 API から取れては意味がないため）。</li>
 *   <li><b>更新・割当</b>（作成/一括作成/更新/削除/差分割当）: ADMIN/DEPUTY_ADMIN 以上
 *       （SYSTEM_ADMIN 短絡）。{@code ShiftScheduleService#checkScheduleAdminAccess} と同一方針。</li>
 * </ul>
 *
 * <p>認可失敗は参照・更新とも {@code COMMON_002}（403）とする。越境を 404 に寄せず 403 とするのは
 * 同ドメインの既存契約テスト {@code ShiftScheduleScopeContractIT}（Wave3-B6）が別 scope ADMIN に
 * 403 を期待しており、そちらへ揃えるため。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftSlotService {

    private final ShiftSlotRepository slotRepository;
    private final ShiftPositionRepository positionRepository;
    private final ShiftScheduleRepository scheduleRepository;
    /** 手動割当の操作履歴（誰がいつ割り当て・解除したか）を記録するための履歴表。 */
    private final ShiftAssignmentRepository assignmentRepository;
    private final AccessControlService accessControlService;

    /**
     * スケジュールのシフト枠一覧を取得する。
     *
     * @param scheduleId スケジュールID
     * @param userId     操作者ユーザーID
     * @return シフト枠一覧
     */
    public List<ShiftSlotResponse> listSlots(Long scheduleId, Long userId) {
        // 未公開は認可結果より先に 404 へ正規化し、実在 ID の 403 と
        // 非存在 ID の 404 を比較する存在オラクルを防ぐ。
        boolean masked = resolveAssignmentMasked(scheduleId, userId);
        checkScheduleReadAccess(scheduleId, userId);
        List<ShiftSlotEntity> entities = slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(scheduleId);
        return entities.stream().map(e -> applyMask(toSlotResponse(e), masked)).toList();
    }

    /**
     * シフト枠を単体取得する。
     *
     * @param slotId シフト枠ID
     * @param userId 操作者ユーザーID
     * @return シフト枠
     */
    public ShiftSlotResponse getSlot(Long slotId, Long userId) {
        ShiftSlotEntity entity = findSlotOrThrow(slotId);
        checkScheduleReadAccess(entity.getScheduleId(), userId);
        boolean masked = resolveAssignmentMasked(entity.getScheduleId(), userId);
        return applyMask(toSlotResponse(entity), masked);
    }

    /**
     * シフト枠を作成する。
     *
     * @param scheduleId スケジュールID
     * @param req        作成リクエスト
     * @param userId     操作者ユーザーID
     * @return 作成されたシフト枠
     */
    @Transactional
    public ShiftSlotResponse createSlot(Long scheduleId, CreateShiftSlotRequest req, Long userId) {
        checkScheduleAdminAccess(scheduleId, userId);
        // 枠時刻の検証（設計 F03.5 §11.2.5・単一の検証点）。
        ShiftSlotTimeValidator.validateTimeRange(
                req.getStartTime(), req.getEndTime(), req.endsNextDayOrFalse());
        ShiftSlotEntity entity = ShiftSlotEntity.builder()
                .scheduleId(scheduleId)
                .slotDate(req.getSlotDate())
                .startTime(req.getStartTime())
                .endTime(req.getEndTime())
                .positionId(req.getPositionId())
                .requiredCount(req.getRequiredCount() != null ? req.getRequiredCount() : 1)
                .note(req.getNote())
                .endsNextDay(req.endsNextDayOrFalse())
                .build();

        entity = slotRepository.save(entity);
        log.info("シフト枠作成: id={}, scheduleId={}", entity.getId(), scheduleId);
        return toSlotResponse(entity);
    }

    /**
     * シフト枠を一括作成する。
     *
     * @param scheduleId スケジュールID
     * @param req        一括作成リクエスト
     * @param userId     操作者ユーザーID
     * @return 作成されたシフト枠一覧
     */
    @Transactional
    public List<ShiftSlotResponse> bulkCreateSlots(Long scheduleId, BulkCreateShiftSlotRequest req, Long userId) {
        checkScheduleAdminAccess(scheduleId, userId);
        // 一括作成も単体作成と同じ検証点を通す（1 件でも不正ならトランザクションごと拒否）。
        req.getSlots().forEach(slotReq -> ShiftSlotTimeValidator.validateTimeRange(
                slotReq.getStartTime(), slotReq.getEndTime(), slotReq.endsNextDayOrFalse()));
        List<ShiftSlotEntity> entities = req.getSlots().stream()
                .map(slotReq -> (ShiftSlotEntity) ShiftSlotEntity.builder()
                        .scheduleId(scheduleId)
                        .slotDate(slotReq.getSlotDate())
                        .startTime(slotReq.getStartTime())
                        .endTime(slotReq.getEndTime())
                        .positionId(slotReq.getPositionId())
                        .requiredCount(slotReq.getRequiredCount() != null ? slotReq.getRequiredCount() : 1)
                        .note(slotReq.getNote())
                        .endsNextDay(slotReq.endsNextDayOrFalse())
                        .build())
                .toList();

        entities = slotRepository.saveAll(entities);
        log.info("シフト枠一括作成: scheduleId={}, count={}", scheduleId, entities.size());
        return entities.stream().map(this::toSlotResponse).toList();
    }

    /**
     * シフト枠を更新する。
     *
     * @param slotId シフト枠ID
     * @param req    更新リクエスト
     * @param userId 操作者ユーザーID
     * @return 更新されたシフト枠
     */
    @Transactional
    public ShiftSlotResponse updateSlot(Long slotId, UpdateShiftSlotRequest req, Long userId) {
        ShiftSlotEntity entity = findSlotOrThrow(slotId);
        checkScheduleAdminAccess(entity.getScheduleId(), userId);

        // 枠時刻の検証（設計 F03.5 §11.2.5）。部分更新のため、リクエストで指定されなかった側は
        // 既存値と合成して検証する。時刻・日跨ぎのいずれも指定されていない更新（note のみ等）は
        // 検証しない — 既存の不正時刻行を理由に拒否しないための後方互換（§11.2.5「書き込み時のみ検証」）。
        if (req.getStartTime() != null || req.getEndTime() != null || req.getEndsNextDay() != null) {
            ShiftSlotTimeValidator.validateTimeRange(
                    req.getStartTime() != null ? req.getStartTime() : entity.getStartTime(),
                    req.getEndTime() != null ? req.getEndTime() : entity.getEndTime(),
                    req.getEndsNextDay() != null ? req.getEndsNextDay() : entity.isEndsNextDay());
        }

        // 履歴記録のため更新前の割当を控える（CMP-260908-2117）。
        List<Long> before = deserializeUserIds(entity.getAssignedUserIds());

        // managed entity を直接ミューテート（toBuilder().build() でなくドメインメソッドで更新）。
        // ShiftSlotEntity は @Builder(toBuilder=true) / @SuperBuilder でない / BaseEntity継承(自前id無)
        // の3条件が揃うため、toBuilder().build()→save では id=null の新インスタンスが生成され
        // UPDATE でなく INSERT が走る行重複バグになる。
        entity.applyUpdate(
                req.getSlotDate(),
                req.getStartTime(),
                req.getEndTime(),
                req.getPositionId(),
                req.getRequiredCount(),
                req.getAssignedUserIds() != null ? serializeUserIds(req.getAssignedUserIds()) : null,
                req.getNote(),
                req.getEndsNextDay()
        );

        slotRepository.save(entity);
        recordAssignmentHistory(entity, before, deserializeUserIds(entity.getAssignedUserIds()), userId);
        log.info("シフト枠更新: id={}", slotId);
        return toSlotResponse(entity);
    }

    /**
     * スロットの割当ユーザーを差分更新する（楽観ロック付き）。
     *
     * @param slotId  シフト枠ID
     * @param request 差分割当リクエスト
     * @param userId  操作者ユーザーID
     * @return 更新後のシフト枠レスポンス
     */
    @Transactional
    public ShiftSlotResponse patchSlotAssignments(Long slotId, SlotAssignmentPatchRequest request, Long userId) {
        ShiftSlotEntity entity = findSlotOrThrow(slotId);
        checkScheduleAdminAccess(entity.getScheduleId(), userId);

        // 楽観ロックチェック: version が一致しない場合は 409
        if (!entity.getVersion().equals(request.slotVersion().longValue())) {
            throw new BusinessException(ShiftErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        }

        // 現在の割当ユーザーリストを取得
        List<Long> before = deserializeUserIds(entity.getAssignedUserIds());
        List<Long> currentUserIds = new ArrayList<>(before);

        // ユーザーを追加（ループ変数は操作者 userId と衝突しないよう addUserId とする）
        if (request.addUserIds() != null) {
            for (Long addUserId : request.addUserIds()) {
                if (!currentUserIds.contains(addUserId)) {
                    currentUserIds.add(addUserId);
                }
            }
        }

        // ユーザーを削除
        if (request.removeUserIds() != null) {
            currentUserIds.removeAll(request.removeUserIds());
        }

        // 必要人数超過チェック
        if (currentUserIds.size() > entity.getRequiredCount()) {
            throw new BusinessException(ShiftErrorCode.SLOT_ASSIGNMENT_EXCEEDED);
        }

        // managed entity を直接ミューテート（toBuilder().build() 行重複バグ回避）。
        entity.updateAssignedUserIds(serializeUserIds(currentUserIds));
        slotRepository.save(entity);
        recordAssignmentHistory(entity, before, currentUserIds, userId);

        log.info("スロット差分割当更新: slotId={}, added={}, removed={}",
                slotId,
                request.addUserIds() != null ? request.addUserIds().size() : 0,
                request.removeUserIds() != null ? request.removeUserIds().size() : 0);
        return toSlotResponse(entity);
    }

    /**
     * シフト枠を削除する。
     *
     * @param slotId シフト枠ID
     * @param userId 操作者ユーザーID
     */
    @Transactional
    public void deleteSlot(Long slotId, Long userId) {
        ShiftSlotEntity entity = findSlotOrThrow(slotId);
        checkScheduleAdminAccess(entity.getScheduleId(), userId);
        slotRepository.delete(entity);
        log.info("シフト枠削除: id={}", slotId);
    }

    /**
     * シフト枠を取得する。存在しない場合は例外をスローする。
     */
    ShiftSlotEntity findSlotOrThrow(Long id) {
        return slotRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND));
    }

    /**
     * スケジュール ID から所属チーム ID を解決する。
     *
     * <p>scope をパス変数・クエリ入力でなく<b>スケジュール実体由来</b>にすることで、
     * 「他チームの slotId / scheduleId を直接指定して越境する」BOLA を封鎖する。</p>
     *
     * @param scheduleId スケジュール ID
     * @return 所属チーム ID
     */
    private Long resolveTeamId(Long scheduleId) {
        return scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND))
                .getTeamId();
    }

    /**
     * シフト枠の参照認可（当該チームのメンバー、ただし SUPPORTER は不可）。
     *
     * @param scheduleId スケジュール ID
     * @param userId     操作者ユーザー ID
     * @throws BusinessException メンバーでない場合、または SUPPORTER の場合（COMMON_002 / 403）
     */
    private void checkScheduleReadAccess(Long scheduleId, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        Long teamId = resolveTeamId(scheduleId);
        if (!accessControlService.isMember(userId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        if (accessControlService.isSupporter(userId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * シフト枠の更新・割当認可（SYSTEM_ADMIN 短絡 or 当該チームの ADMIN/DEPUTY_ADMIN）。
     *
     * @param scheduleId スケジュール ID
     * @param userId     操作者ユーザー ID
     * @throws BusinessException 権限が無い場合（COMMON_002 / 403）
     */
    private void checkScheduleAdminAccess(Long scheduleId, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        accessControlService.checkAdminOrAbove(userId, resolveTeamId(scheduleId), "TEAM");
    }

    /**
     * 閲覧者に対する枠一覧の可視性を解決する（CMP-260826-2127 / AC-3・AC-4）。
     *
     * <p>未公開（{@code DRAFT} / {@code ARCHIVED} かつ {@code publishedAt} が NULL）なら 404、
     * {@code COLLECTING} / {@code ADJUSTING} なら割当だけを伏せる。判定は
     * {@link ShiftScheduleVisibilityPolicy} に閉じる（設計 G-1）。</p>
     *
     * <p><b>マスクを {@code toSlotResponse} 側で行わない理由</b>: 同メソッドは管理系
     *（{@code createSlot} / {@code updateSlot} / 差分割当）からも呼ばれており、
     * そこで伏せると管理画面の D&D 編集が空になる。</p>
     *
     * @param scheduleId スケジュール ID
     * @param userId     閲覧者ユーザー ID
     * @return 割当を伏せるべきなら true
     * @throws BusinessException 未公開の場合（SHIFT_SCHEDULE_NOT_FOUND / 404）
     */
    private boolean resolveAssignmentMasked(Long scheduleId, Long userId) {
        // SYSTEM_ADMIN 短絡は schedule の取得より前に置く（checkScheduleReadAccess と同じ順序）。
        if (accessControlService.isSystemAdmin(userId)) {
            return false;
        }
        ShiftScheduleEntity schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));
        if (accessControlService.isAdminOrAbove(userId, schedule.getTeamId(), "TEAM")) {
            return false;
        }
        ShiftScheduleVisibilityPolicy.Visibility visibility = ShiftScheduleVisibilityPolicy
                .classify(schedule.getStatus(), schedule.getPublishedAt());
        if (visibility.isHidden()) {
            throw new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        }
        return visibility.isAssignmentMasked();
    }

    /**
     * 割当を伏せる（空配列 + {@code assignmentMasked=true}）。
     *
     * <p>{@code null} ではなく空配列にするのは、FE が {@code assignedUserIds.length} と
     * {@code .forEach} を null チェック無しで呼んでいるためである（null にすると TypeError で落ちる）。</p>
     *
     * @param response 変換済みレスポンス
     * @param masked   伏せるか
     * @return 伏せた（あるいはそのままの）レスポンス
     */
    private ShiftSlotResponse applyMask(ShiftSlotResponse response, boolean masked) {
        if (!masked) {
            return response;
        }
        return response.toBuilder()
                .assignedUserIds(List.of())
                .assignmentMasked(true)
                .build();
    }

    /**
     * エンティティをレスポンスDTOに変換する。
     */
    private ShiftSlotResponse toSlotResponse(ShiftSlotEntity entity) {
        String positionName = null;
        if (entity.getPositionId() != null) {
            positionName = positionRepository.findById(entity.getPositionId())
                    .map(ShiftPositionEntity::getName)
                    .orElse(null);
        }

        return ShiftSlotResponse.builder()
                .id(entity.getId())
                .scheduleId(entity.getScheduleId())
                .time(new ShiftSlotResponse.ShiftSlotTimeDto(
                        entity.getSlotDate(), entity.getStartTime(), entity.getEndTime(),
                        entity.isEndsNextDay()))
                .position(new ShiftSlotResponse.ShiftSlotPositionDto(
                        entity.getPositionId(), positionName, entity.getRequiredCount()))
                .assignedUserIds(deserializeUserIds(entity.getAssignedUserIds()))
                .note(entity.getNote())
                .build();
    }

    /**
     * 手動割当の操作履歴を {@code shift_assignments} に記録する（CMP-260908-2117）。
     *
     * <p><b>役割の分離</b>: 現在の割当状態の正本は {@code shift_slots.assigned_user_ids} であり、
     * 本表は「誰がいつ割り当て・解除したか」を残す<b>監査・履歴用</b>である。
     * 読み出し（自分のシフト・今後の予定・充足サマリー）は本表を参照しない。
     * 設計 {@code docs/features/F03.5_shift/01_db_design.md} が
     * 「手動割当も自動割当も同じテーブルに記録する」と定めており、実装がそれに追いついていなかった。</p>
     *
     * <p><b>解除と再割当の表現</b>: 1 行 = 1 回の割当（{@code created_at} が割当時刻、
     * {@code REVOKED} への遷移時の {@code updated_at} が解除時刻）とし、次のように扱う。</p>
     * <ul>
     *   <li><b>追加</b>: 当該 (slot, user) に非 REVOKED の行が無ければ
     *       {@code CONFIRMED} 行を 1 件 INSERT する（{@code run_id} は NULL = 手動、
     *       {@code assigned_by} は操作者）。既にあれば何もしない（冪等）。</li>
     *   <li><b>解除</b>: 当該 (slot, user) の非 REVOKED 行をすべて {@code REVOKED} に遷移させる。
     *       自動割当由来の行も対象に含める — 外したという事実は割当の出自によらないうえ、
     *       残したままだと履歴表が「まだ入っている」と主張して現状と食い違うためである。</li>
     *   <li><b>外して再度入れる</b>: 上記の結果、REVOKED 行と新しい CONFIRMED 行が並ぶ。
     *       「追記のみ（解除イベント行を別に足す）」を採らないのは、{@code status} 列が
     *       PROPOSED→CONFIRMED→REVOKED という<b>状態</b>を表す ENUM であり
     *       （{@code ShiftAssignmentEntity#revoke()} も状態遷移として実装されている）、
     *       同じ列にイベント種別を混ぜると自動割当側の解釈と衝突するためである。
     *       UNIQUE KEY {@code (slot_id, user_id, run_id)} は run_id が NULL のとき
     *       MySQL では重複を許すため、行の追加は制約違反にならない。</li>
     * </ul>
     *
     * <p><b>既知の限界</b>: 解除した操作者は記録されない（{@code assigned_by} は割り当てた者を保持する）。
     * 記録するには列追加（Flyway）が要るため、本 CMP の射程外とした。</p>
     *
     * @param slot       対象スロット（保存済み）
     * @param before     更新前の割当ユーザー ID
     * @param after      更新後の割当ユーザー ID
     * @param operatorId 操作者ユーザー ID
     */
    private void recordAssignmentHistory(
            ShiftSlotEntity slot, List<Long> before, List<Long> after, Long operatorId) {
        List<Long> added = after.stream().filter(id -> !before.contains(id)).distinct().toList();
        List<Long> removed = before.stream().filter(id -> !after.contains(id)).distinct().toList();
        if (added.isEmpty() && removed.isEmpty()) {
            return;
        }

        List<ShiftAssignmentEntity> existing = assignmentRepository.findAllBySlotId(slot.getId());

        List<ShiftAssignmentEntity> toSave = new ArrayList<>();
        for (Long addedUserId : added) {
            boolean alreadyActive = existing.stream()
                    .anyMatch(a -> addedUserId.equals(a.getUserId())
                            && a.getStatus() != ShiftAssignmentStatus.REVOKED);
            if (alreadyActive) {
                continue;
            }
            toSave.add(ShiftAssignmentEntity.builder()
                    .slotId(slot.getId())
                    .userId(addedUserId)
                    .runId(null)
                    .status(ShiftAssignmentStatus.CONFIRMED)
                    .assignedBy(operatorId)
                    .note("手動割当")
                    .build());
        }
        for (Long removedUserId : removed) {
            existing.stream()
                    .filter(a -> removedUserId.equals(a.getUserId())
                            && a.getStatus() != ShiftAssignmentStatus.REVOKED)
                    .forEach(a -> {
                        a.revoke();
                        toSave.add(a);
                    });
        }

        if (!toSave.isEmpty()) {
            assignmentRepository.saveAll(toSave);
        }
        log.info("手動割当履歴を記録: slotId={}, operator={}, added={}, revoked={}",
                slot.getId(), operatorId, added.size(), removed.size());
    }

    /**
     * ユーザーIDリストをJSON文字列にシリアライズする。
     *
     * <p>実体は {@link ShiftAssignedUserIds}（割当 JSON の読み書きの唯一の定義）に委譲する。</p>
     */
    private String serializeUserIds(List<Long> userIds) {
        return ShiftAssignedUserIds.serialize(userIds);
    }

    /**
     * JSON文字列からユーザーIDリストをデシリアライズする。
     *
     * <p>実体は {@link ShiftAssignedUserIds} に委譲する。</p>
     */
    private List<Long> deserializeUserIds(String json) {
        return ShiftAssignedUserIds.parse(json);
    }
}
