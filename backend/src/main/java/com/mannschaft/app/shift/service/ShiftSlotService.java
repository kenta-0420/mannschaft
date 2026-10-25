package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.shift.ShiftAssignedUserIds;
import com.mannschaft.app.shift.ShiftAssignmentStatus;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.BulkCreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.CreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.ShiftAssignmentWarningDto;
import com.mannschaft.app.shift.dto.ShiftSlotResponse;
import com.mannschaft.app.shift.dto.SlotAssignmentMoveRequest;
import com.mannschaft.app.shift.dto.SlotAssignmentMoveResponse;
import com.mannschaft.app.shift.dto.SlotAssignmentPatchRequest;
import com.mannschaft.app.shift.dto.UpdateShiftSlotRequest;
import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * シフト枠サービス（<b>トランザクション本体</b>）。シフト枠のCRUD・一括操作を担当する。
 *
 * <p><b>認可はここに無い（CMP-260923-0954 W6a）:</b> 認可（per-scope・存在オラクル対策・SYSTEM_ADMIN の扱い）は
 * トランザクションの外の {@link ShiftSlotFacade} が行う。本クラスは {@code AccessControlService}・
 * {@code ScopeConcealingAccessGate} に<b>クラスごと依存しない</b>（クラスに {@code @Transactional} があると
 * 認可用の private メソッド・ラムダまで D-3T の入口に数えられるため）。</p>
 *
 * <p><b>scope の解決と読み直し:</b> Facade は認可の前に {@link #resolveScheduleScope} /
 * {@link #resolveSlotTeamId}（readOnly・自ドメインのみ・FOR UPDATE なし）で所属チームを読み、認可の後に
 * 書き込み・参照の tx 本体を呼ぶ。書き込みは tx の中で<b>親スケジュールを {@code findByIdForUpdate} で
 * 読み直してロックし</b>（親削除と子の作成・更新を直列化。ロックは認可の後なので部外者は他チームの行を
 * 掴めない＝K6）、不在・論理削除済みなら<b>対象リソースの不在コード</b>へ揃える（スケジュール起点の
 * 作成は {@code SHIFT_001}、枠起点の更新・割当・削除は親だけが論理削除済みでも {@code SHIFT_002}＝K5）。</p>
 *
 * <p>閲覧の可視性（未公開の秘匿・割当のマスク）は認可の結果（管理者側かどうか）に依存するため、Facade が判定した
 * {@code privileged} の真偽を引数で受け取り、tx の中の最新の状態で再判定する。呼び出し元は Facade のみ。</p>
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
    private final ShiftRequestRepository requestRepository;

    // ═════════════════════════════════════════════════════════════════════
    // scope 解決（Facade が認可の前に呼ぶ readOnly の素の読み取り。戻り値は record で Entity は返さない）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * スケジュール ID から scope（所属チーム ID・公開状態）を解決する。
     * 不在・論理削除済みは {@code SHIFT_001}（404）。
     *
     * @param scheduleId スケジュール ID
     * @return scope
     */
    public ShiftScheduleScope resolveScheduleScope(Long scheduleId) {
        ShiftScheduleEntity schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));
        return new ShiftScheduleScope(
                schedule.getId(), schedule.getTeamId(), schedule.getStatus(),
                schedule.getPublishedAt() != null);
    }

    /**
     * 枠 ID から所属チーム ID を解決する（枠 → 親スケジュール → チーム）。
     *
     * <p>枠が不在、または親スケジュールが不在（論理削除済み）のとき、<b>どちらも {@code SHIFT_002}（404）</b>
     * に揃える（K5: 枠起点の EP は対象リソース＝枠の不在コードで返す。親だけ削除済みでも割れない）。</p>
     *
     * @param slotId 枠 ID
     * @return 所属チーム ID
     */
    public Long resolveSlotTeamId(Long slotId) {
        ShiftSlotEntity slot = findSlotOrThrow(slotId);
        return scheduleRepository.findById(slot.getScheduleId())
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND))
                .getTeamId();
    }

    /**
     * スケジュールのシフト枠一覧を取得する。
     *
     * <p>tx の中で親スケジュールを読み直し、未公開（{@code DRAFT} / {@code ARCHIVED} かつ {@code publishedAt} が
     * NULL）は管理者側（{@code privileged}）以外に 404、{@code COLLECTING} / {@code ADJUSTING} は割当だけを伏せる
     * （CMP-260826-2127 / AC-3・AC-4。判定は {@link ShiftScheduleVisibilityPolicy} に閉じる）。</p>
     *
     * @param scheduleId スケジュールID
     * @param privileged 管理者側（SYSTEM_ADMIN または当該チームの ADMIN 以上）なら true
     * @return シフト枠一覧
     */
    public List<ShiftSlotResponse> listSlots(Long scheduleId, boolean privileged) {
        ShiftScheduleEntity schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));
        boolean masked = false;
        if (!privileged) {
            ShiftScheduleVisibilityPolicy.Visibility visibility = ShiftScheduleVisibilityPolicy
                    .classify(schedule.getStatus(), schedule.getPublishedAt());
            if (visibility.isHidden()) {
                throw new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            }
            masked = visibility.isAssignmentMasked();
        }
        List<ShiftSlotEntity> entities = slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(scheduleId);
        final boolean maskAssignments = masked;
        return entities.stream().map(e -> applyMask(toSlotResponse(e), maskAssignments)).toList();
    }

    /**
     * シフト枠を作成する。
     *
     * @param scheduleId スケジュールID
     * @param req        作成リクエスト
     * @return 作成されたシフト枠
     */
    @Transactional
    public ShiftSlotResponse createSlot(Long scheduleId, CreateShiftSlotRequest req) {
        lockParentForUpdate(scheduleId, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
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
     * @return 作成されたシフト枠一覧
     */
    @Transactional
    public List<ShiftSlotResponse> bulkCreateSlots(Long scheduleId, BulkCreateShiftSlotRequest req) {
        lockParentForUpdate(scheduleId, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
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
        lockParentForUpdate(entity.getScheduleId(), ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);

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

        // 応答に更新後の version を載せるため flush する（CMP-261010-1432 P0）。
        slotRepository.save(entity);
        slotRepository.flush();
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
        lockParentForUpdate(entity.getScheduleId(), ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);

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

        // 重なり判定（設計 F03.5 §11.3.5）。今回“新たに追加された”ユーザーだけを見る
        // （既に入っていたユーザーを毎回警告すると、無関係な差分操作でも警告が湧く）。
        List<Long> addedUserIds = currentUserIds.stream().filter(id -> !before.contains(id)).toList();
        List<ShiftAssignmentWarningDto> warnings = detectOverlapWarnings(entity, addedUserIds);

        // managed entity を直接ミューテート（toBuilder().build() 行重複バグ回避）。
        entity.updateAssignedUserIds(serializeUserIds(currentUserIds));
        // 応答に更新後の version を載せるため flush する（CMP-261010-1432 P0。FE は次の操作でこの値を送る）。
        slotRepository.save(entity);
        slotRepository.flush();
        recordAssignmentHistory(entity, before, currentUserIds, userId);

        log.info("スロット差分割当更新: slotId={}, added={}, removed={}",
                slotId,
                request.addUserIds() != null ? request.addUserIds().size() : 0,
                request.removeUserIds() != null ? request.removeUserIds().size() : 0);
        return toSlotResponse(entity).toBuilder().warnings(warnings).build();
    }

    /**
     * 追加されたユーザーについて、他の枠との勤務時間の重なりを検出する（設計 F03.5 §11.3.5）。
     *
     * <p><b>完全一致</b>（同一日・同一開始・同一終了・同一 endsNextDay の別枠に同じ人物）だけは
     * {@link ShiftErrorCode#DUPLICATE_ASSIGNMENT}（409）で拒否し、それ以外の重なりは警告に留める。
     * 現場では「12:00-15:00 と 14:00-18:00 を承知で掛け持ちさせる」運用が実在するため、
     * 機械的に禁止すると回避不能な行き止まりになる。</p>
     *
     * <p><b>探索範囲は同一スケジュール内の枠に限る。</b>「人間は一人なのだから他のシフト表の枠とも
     * 突き合わせるべきだ」という素朴な拡張は、実際に既存の契約テスト
     * {@code ShiftManualAssignmentSourceContractIT} 7 件を 409 で落とした。理由は 2 つある:</p>
     * <ol>
     *   <li><b>作成中のシフト表は既存表の複製から始まる。</b>翌週分を DRAFT で下書きすれば、
     *       同じ人・同じ時刻の枠が別スケジュールに必ず並ぶ。横断で見ると下書きを作った瞬間に
     *       現行表の割当が編集不能になる（回避手段が無い行き止まり）。</li>
     *   <li><b>未公開シフト表の存在オラクルになる。</b>他人に見えないはずの DRAFT の割当が
     *       409 という観測可能な差として漏れる（CMP-260826-2127 で塞いだ経路と同種）。</li>
     * </ol>
     *
     * @param entity       今まさに割当を書き換えている枠
     * @param addedUserIds 今回新たに追加されたユーザー ID
     * @return 警告一覧（重なりが無ければ空リスト。null は返さない）
     */
    private List<ShiftAssignmentWarningDto> detectOverlapWarnings(ShiftSlotEntity entity, List<Long> addedUserIds) {
        return detectOverlapWarnings(entity, addedUserIds, null);
    }

    /**
     * 承認された交代の申請者だけを同じ位置で受諾者へ置換する。
     *
     * <p>呼出元は認可・受諾者資格確認後、親→枠を排他ロック付きでrefresh済みであること。
     * 交代依頼の更新と同一TXに参加し、人数・他担当・枠の業務値を保持する。</p>
     *
     * @param slot 現在値を取得済みの対象枠
     * @param requesterId 申請者ID
     * @param accepterId 受諾者ID
     * @param operatorId 承認管理者ID
     */
    @Transactional
    public void replaceAssignmentForSwap(
            ShiftSlotEntity slot, Long requesterId, Long accepterId, Long operatorId) {
        List<Long> before = deserializeUserIds(slot.getAssignedUserIds());
        int requesterIndex = before.indexOf(requesterId);
        if (requesterIndex < 0) {
            throw new BusinessException(ShiftErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        }
        if (before.contains(accepterId)) {
            throw new BusinessException(ShiftErrorCode.DUPLICATE_ASSIGNMENT);
        }
        // 同一表の完全一致だけを拒否する。部分重なりを許す既存方針は交代でも維持する。
        detectOverlapWarnings(slot, List.of(accepterId));

        List<Long> after = new ArrayList<>(before);
        after.set(requesterIndex, accepterId);
        slot.updateAssignedUserIds(serializeUserIds(after));
        slotRepository.save(slot);
        slotRepository.flush();

        // 交代承認だけはBの古い提案を成立済みとみなさず、新しい手動CONFIRMEDを残す。
        // snapshotからBのPROPOSEDだけを除き、元のmanaged提案行自体は変更しない。
        List<ShiftAssignmentEntity> existing = assignmentRepository.findAllBySlotId(slot.getId()).stream()
                .filter(a -> !accepterId.equals(a.getUserId()) || a.getStatus() != ShiftAssignmentStatus.PROPOSED)
                .toList();
        recordAssignmentHistory(slot, before, after, operatorId, existing);
    }

    /**
     * {@link #detectOverlapWarnings(ShiftSlotEntity, List)} の本体。{@code excludedSlotId} の枠は突き合わせない
     * （枠間移動では移動元から外す当人を、移動元の枠との重なりで誤って弾かないため）。
     */
    private List<ShiftAssignmentWarningDto> detectOverlapWarnings(
            ShiftSlotEntity entity, List<Long> addedUserIds, Long excludedSlotId) {
        if (addedUserIds.isEmpty()) {
            return List.of();
        }
        Set<Long> conflictingSlotIds = new LinkedHashSet<>();
        // 現在読み取り（FOR SHARE）。親ロック待機中に別操作がコミットした割当を、REPEATABLE READ の
        // スナップショットで見逃さないため。呼び出し元は必ず親スケジュールを FOR UPDATE で取得済みであること。
        for (ShiftSlotEntity other : slotRepository.findByScheduleIdForShare(entity.getScheduleId())) {
            if (other.getId().equals(entity.getId()) || other.getId().equals(excludedSlotId)) {
                continue;
            }
            List<Long> otherUserIds = deserializeUserIds(other.getAssignedUserIds());
            if (addedUserIds.stream().noneMatch(otherUserIds::contains)) {
                continue;
            }
            if (!ShiftAssignmentOverlapDetector.overlaps(
                    entity.getSlotDate(), entity.getStartTime(), entity.getEndTime(), entity.isEndsNextDay(),
                    other.getSlotDate(), other.getStartTime(), other.getEndTime(), other.isEndsNextDay())) {
                continue;
            }
            if (isIdenticalTimeRange(entity, other)) {
                throw new BusinessException(ShiftErrorCode.DUPLICATE_ASSIGNMENT);
            }
            conflictingSlotIds.add(other.getId());
        }
        if (conflictingSlotIds.isEmpty()) {
            return List.of();
        }
        return List.of(new ShiftAssignmentWarningDto(
                ShiftAssignmentWarningDto.ASSIGNMENT_OVERLAP,
                conflictingSlotIds.stream().sorted().toList()));
    }

    /**
     * 割当メンバーを枠 X（移動元）から同じシフト表の枠 Y（移動先）へ <b>1 トランザクションで</b> 移す
     * （CMP-261010-1432 P。AC-P3〜P7）。
     *
     * <p>削除と追加を 2 回の差分割当に分けると、追加が拒否されたときに移動元の割当だけが失われる。
     * 本メソッドはすべての検査（version・在籍・満員・完全一致の重複）を<b>書き換えの前に</b>済ませ、
     * どの拒否でも両枠を変えない。</p>
     *
     * <p><b>ロック順</b>: 親スケジュール（FOR UPDATE）→ 両枠（FOR UPDATE）。既存の枠更新と同じ親→子の順なので
     * 新たなデッドロックを作らない。親の ID はスカラーで読み、枠エンティティは FOR UPDATE で初めて読む
     * （先に通常読みすると、後の FOR UPDATE が永続化コンテキストの古い状態を返すため）。</p>
     *
     * <p><b>存在秘匿</b>: 認可は Facade で移動元に対して済んでいる。移動先が不在・別シフト表（同チーム）・
     * 別チームのいずれでも {@code SHIFT_SLOT_NOT_FOUND}（404）に揃える。移動先の所属は<b>ロックを取る前に</b>
     * スカラーで照合し、他シフト表・他チームの行はロックしない。</p>
     *
     * @param sourceSlotId 移動元の枠 ID
     * @param request      移動リクエスト
     * @param operatorId   操作者ユーザー ID
     * @return 両枠の更新後の状態
     */
    @Transactional
    public SlotAssignmentMoveResponse moveAssignment(
            Long sourceSlotId, SlotAssignmentMoveRequest request, Long operatorId) {
        Long scheduleId = slotRepository.findScheduleIdById(sourceSlotId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND));
        lockParentForUpdate(scheduleId, ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);

        Long targetSlotId = request.targetSlotId();
        if (sourceSlotId.equals(targetSlotId)) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        Long targetScheduleId = slotRepository.findScheduleIdById(targetSlotId).orElse(null);
        if (!scheduleId.equals(targetScheduleId)) {
            throw new BusinessException(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);
        }

        ShiftSlotEntity source = slotRepository.findByIdForUpdate(sourceSlotId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND));
        ShiftSlotEntity target = slotRepository.findByIdForUpdate(targetSlotId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND));

        if (!source.getVersion().equals(request.sourceSlotVersion())
                || !target.getVersion().equals(request.targetSlotVersion())) {
            throw new BusinessException(ShiftErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        }

        Long movingUserId = request.userId();
        List<Long> sourceBefore = deserializeUserIds(source.getAssignedUserIds());
        List<Long> targetBefore = deserializeUserIds(target.getAssignedUserIds());
        if (!sourceBefore.contains(movingUserId)) {
            throw new BusinessException(ShiftErrorCode.MOVE_SOURCE_NOT_ASSIGNED);
        }
        if (targetBefore.contains(movingUserId)) {
            throw new BusinessException(ShiftErrorCode.DUPLICATE_ASSIGNMENT);
        }
        if (targetBefore.size() + 1 > target.getRequiredCount()) {
            throw new BusinessException(ShiftErrorCode.SLOT_ASSIGNMENT_EXCEEDED);
        }
        // 完全一致の重複は 409 で拒否（書き換え前に判定する）。移動元は外す当人なので突き合わせない。
        List<ShiftAssignmentWarningDto> warnings =
                detectOverlapWarnings(target, List.of(movingUserId), source.getId());

        List<Long> sourceAfter = new ArrayList<>(sourceBefore);
        sourceAfter.remove(movingUserId);
        List<Long> targetAfter = new ArrayList<>(targetBefore);
        targetAfter.add(movingUserId);

        source.updateAssignedUserIds(serializeUserIds(sourceAfter));
        target.updateAssignedUserIds(serializeUserIds(targetAfter));
        // 応答に両枠の更新後 version を載せるため flush する。
        slotRepository.saveAll(List.of(source, target));
        slotRepository.flush();
        recordAssignmentHistory(source, sourceBefore, sourceAfter, operatorId);
        recordAssignmentHistory(target, targetBefore, targetAfter, operatorId);

        log.info("枠間移動: scheduleId={}, from={}, to={}, userId={}",
                scheduleId, sourceSlotId, targetSlotId, movingUserId);
        return new SlotAssignmentMoveResponse(
                toSlotResponse(source),
                toSlotResponse(target).toBuilder().warnings(warnings).build());
    }

    /** 2 つの枠が完全一致（同一日・同一開始・同一終了・同一 endsNextDay）か。 */
    private boolean isIdenticalTimeRange(ShiftSlotEntity left, ShiftSlotEntity right) {
        return left.getSlotDate().equals(right.getSlotDate())
                && left.getStartTime().equals(right.getStartTime())
                && left.getEndTime().equals(right.getEndTime())
                && left.isEndsNextDay() == right.isEndsNextDay();
    }

    /**
     * シフト枠を削除する。
     *
     * @param slotId シフト枠ID
     */
    @Transactional
    public void deleteSlot(Long slotId) {
        ShiftSlotEntity entity = findSlotOrThrow(slotId);
        lockParentForUpdate(entity.getScheduleId(), ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);
        // 枠で削除日時を一度だけ確定し、配下の希望・割当へ同じDB値をコピーする。
        if (slotRepository.softDeleteById(slotId) != 1) {
            throw new BusinessException(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);
        }
        assignmentRepository.softDeleteBySlotId(slotId);
        requestRepository.softDeleteBySlotId(slotId);
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
     * 親スケジュールを行ロック付きで読み直す（認可の後・tx の中）。
     *
     * <p>親削除と同じ行を先にロックし、確認後に子だけが作成・更新される競合を防ぐ（CMP-260917-1136）。
     * ロックは認可（Facade）の後なので、部外者の要求は他チームの親行を掴めない（K6）。
     * 親が不在・論理削除済みなら、呼び出し元の対象リソースの不在コード（{@code notFoundCode}）で拒否する
     *（スケジュール起点なら {@code SHIFT_001}、枠起点なら {@code SHIFT_002}＝K5）。</p>
     *
     * @param scheduleId   親スケジュール ID
     * @param notFoundCode 親が不在のときに投げる、対象リソースの不在コード
     */
    private void lockParentForUpdate(Long scheduleId, ShiftErrorCode notFoundCode) {
        scheduleRepository.findByIdForUpdate(scheduleId)
                .orElseThrow(() -> new BusinessException(notFoundCode));
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
                .version(entity.getVersion())
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
        recordAssignmentHistory(slot, before, after, operatorId, existing);
    }

    /** 取得済み履歴を使って記録する。交代でも履歴SELECTを単票1回に保つための共通処理。 */
    private void recordAssignmentHistory(
            ShiftSlotEntity slot, List<Long> before, List<Long> after, Long operatorId,
            List<ShiftAssignmentEntity> existing) {
        List<Long> added = after.stream().filter(id -> !before.contains(id)).distinct().toList();
        List<Long> removed = before.stream().filter(id -> !after.contains(id)).distinct().toList();

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
