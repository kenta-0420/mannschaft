package com.mannschaft.app.shift.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.ShiftAssignedUserIds;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.SwapRequestStatus;
import com.mannschaft.app.shift.dto.CreateSwapRequestRequest;
import com.mannschaft.app.shift.dto.ResolveSwapRequestRequest;
import com.mannschaft.app.shift.dto.SwapRequestResponse;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * シフト交代リクエストの <b>トランザクション本体</b>（自ドメイン = shift の Entity・Repository だけに触れる）。
 *
 * <p><b>スコープ認可はここに置かない（CMP-260923-0954 W2 / 認可をトランザクションの外へ）:</b>
 * 所属・ロールの確認は非トランザクションの {@link ShiftSwapFacade} が行い、通ったものだけを本クラスの
 * {@code @Transactional} メソッドが実行する。{@code AccessControlService} / {@code ScopeConcealingAccessGate}
 * への依存と認可用の private メソッドは本クラスに持たない（D-3T 番人と {@code ShiftTxFacadeArchTest} が固定）。
 * 作成資格（PUBLISHED・本人担当）は自ドメインの業務不変条件として書き込みTX内で確認する。
 * ADMIN / DEPUTY_ADMIN / SYSTEM_ADMIN もこの資格は免除しない。</p>
 *
 * <p><b>scope の解決と読み直し:</b> Facade は認可の前に {@link #resolveSwapScope} 等（readOnly・自ドメインのみ）で
 * 「対象 → シフト枠 → スケジュール → チーム」をたどって teamId を得る。書き込み tx では必ず
 * <b>同じ経路で読み直し</b>、どこかが不在・論理削除済みなら Facade の解決時と<b>同じコード</b>の 404 を投げて DB を変えない
 * （認可の後・tx の前に親が消える競合。K1/K5）。scope の列（slot→schedule→team）は不変という前提で、
 * 所属（memberships）の変化は従来どおりロックしない。</p>
 *
 * <p>認可の契約（主体 × 結果）は {@link ShiftSwapFacade} の Javadoc を参照。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftSwapService {

    private static final String ACTION_APPROVE = "APPROVE";
    private static final String ACTION_REJECT = "REJECT";

    private final ShiftSwapRequestRepository swapRepository;
    private final ShiftSlotRepository slotRepository;
    private final ShiftScheduleRepository scheduleRepository;
    private final ShiftMapper shiftMapper;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final ShiftSlotService slotService;

    /**
     * 指定チームの交代リクエスト一覧を取得する。
     *
     * <p>取得範囲は必ず単一チームに閉じる（他テナントのデータには到達できない）。
     * そのうえで<b>閲覧者の立場によって可視範囲を BE 側で絞り込む</b>:</p>
     * <ul>
     *   <li><b>SYSTEM_ADMIN / 当該チームの ADMIN・DEPUTY_ADMIN</b>: 当該チームの全件。</li>
     *   <li><b>当該チームの一般メンバー（SUPPORTER 不可）</b>: <b>自分に関係する依頼のみ</b>。
     *       すなわち (1) 自分が指名されている（SPECIFIC かつ targetUserIds に自分が含まれる）、
     *       (2) 指名なし（OPEN_CALL。BE の承諾認可上、同一チームの誰でも承諾できる）、
     *       (3) 自分が申請した、(4) 自分が承諾済み、のいずれか。</li>
     *   <li>上記以外（部外者・SUPPORTER）: 403。</li>
     * </ul>
     *
     * <p><b>絞り込みを BE で行う理由:</b> 交代理由（{@code reason}）には体調・家庭の事情など
     * 私的な内容が書かれうる。FE で表示を隠すだけではレスポンス本文に他人の理由が乗り、
     * 開発者ツールから読めてしまう。したがって関係のない依頼は<b>返さない</b>。</p>
     *
     * <p>認可は {@link ShiftSwapFacade#listSwapRequests} が済ませており、その結果（全件可視か）を
     * {@code privileged} で受け取る。</p>
     *
     * @param teamId     対象チームID（Facade が認可済み）
     * @param status     ステータスフィルタ（省略時は当該チームの全件）
     * @param userId     操作者ユーザーID（一般メンバーの絞り込みに使う）
     * @param privileged SYSTEM_ADMIN または当該チームの ADMIN/DEPUTY_ADMIN なら true（全件可視）
     * @return 交代リクエスト一覧（一般メンバーは自分に関係するもののみ）
     */
    public List<SwapRequestResponse> listSwapRequests(Long teamId, String status, Long userId, boolean privileged) {
        List<ShiftSwapRequestEntity> entities;
        if (status != null) {
            entities = swapRepository.findByTeamIdAndStatusOrderByCreatedAtAsc(
                    teamId, SwapRequestStatus.valueOf(status));
        } else {
            entities = swapRepository.findByTeamIdOrderByCreatedAtAsc(teamId);
        }
        if (!privileged) {
            entities = entities.stream()
                    .filter(entity -> isRelatedToUser(entity, userId))
                    .toList();
        }
        return shiftMapper.toSwapResponseList(entities);
    }

    /**
     * 自分の交代リクエスト一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 交代リクエスト一覧
     */
    public List<SwapRequestResponse> listMySwapRequests(Long userId) {
        List<ShiftSwapRequestEntity> entities = swapRepository.findByRequesterIdOrderByCreatedAtDesc(userId);
        return shiftMapper.toSwapResponseList(entities);
    }

    /**
     * 交代リクエストを作成する。
     *
     * <p>受信者モードは以下のルールで決定する:
     * <ul>
     *   <li>openCall=true → OPEN_CALL（全体公開）</li>
     *   <li>openCall=false → SPECIFIC（特定ユーザー指定）</li>
     * </ul>
     * targetUserIds が指定されている場合は JSON 配列文字列に変換して保存する。
     * 後方互換のため targetUserIds が null でも SPECIFIC として扱う。
     *
     * @param req    作成リクエスト
     * @param userId リクエスターID
     * @return 作成された交代リクエスト
     */
    // 親ロック待ち前のsnapshotを未決照会へ持ち越さず、別スケジュールの作成を妨げるgap lockも避ける。
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SwapRequestResponse createSwapRequest(CreateSwapRequestRequest req, Long userId) {
        // スコープ認可はFacade済み。親→子の順で既存の割当更新・親削除と直列化し、
        // 公開取消や担当変更のcommit後の値で作成資格を確定する（親不在は従来どおりSHIFT_001）。
        ShiftSlotEntity slot = slotRepository.findById(req.getSlotId())
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND));
        ShiftScheduleEntity schedule = scheduleRepository.findByIdForUpdate(slot.getScheduleId())
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));
        try {
            // 親ロック待ち前の1次キャッシュ・MySQLのsnapshotを使わず、現在の割当を読み直す。
            entityManager.refresh(slot, LockModeType.PESSIMISTIC_READ);
        } catch (EntityNotFoundException e) {
            // 親ロック待ち中に枠だけが消えた場合も、既存の不在応答を維持する。
            throw new BusinessException(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);
        }
        if (schedule.getStatus() != ShiftScheduleStatus.PUBLISHED) {
            throw new BusinessException(ShiftErrorCode.INVALID_SCHEDULE_STATUS);
        }
        if (!ShiftAssignedUserIds.parse(slot.getAssignedUserIds()).contains(userId)) {
            throw new BusinessException(ShiftErrorCode.ACCESS_DENIED);
        }

        // 宛先や募集モードにかかわらず同じ担当者の未決依頼は1件に限定する。
        // 親の排他をcommitまで保持するので、同じスケジュールの作成は判定から保存まで直列化される。
        if (swapRepository.existsBySlotIdAndRequesterIdAndStatusIn(
                slot.getId(), userId, List.of(SwapRequestStatus.PENDING, SwapRequestStatus.ACCEPTED))) {
            throw new BusinessException(ShiftErrorCode.INVALID_SWAP_STATUS);
        }

        // 受信者モードの決定
        String recipientMode = req.isOpenCall() ? "OPEN_CALL" : "SPECIFIC";

        ShiftSwapRequestEntity entity = ShiftSwapRequestEntity.builder()
                .slotId(req.getSlotId())
                .requesterId(userId)
                .reason(req.getReason())
                .isOpenCall(req.isOpenCall())
                .recipientMode(recipientMode)
                .build();

        // SPECIFIC モードで targetUserIds が指定されている場合は JSON 文字列に変換して保存
        if (req.getTargetUserIds() != null && !req.getTargetUserIds().isEmpty()) {
            try {
                entity.setTargetUserIds(objectMapper.writeValueAsString(req.getTargetUserIds()));
            } catch (JsonProcessingException e) {
                log.warn("targetUserIds の JSON 変換に失敗しました: {}", e.getMessage());
            }
        }

        entity = swapRepository.save(entity);
        log.info("交代リクエスト作成: id={}, slotId={}, requesterId={}, recipientMode={}",
                entity.getId(), req.getSlotId(), userId, recipientMode);
        return shiftMapper.toSwapResponse(entity);
    }

    /**
     * 交代リクエストを承諾する（交代相手）。
     *
     * @param swapId     交代リクエストID
     * @param accepterId 承諾者ID
     * @return 更新された交代リクエスト
     */
    @Transactional
    public SwapRequestResponse acceptSwapRequest(Long swapId, Long accepterId) {
        ShiftSwapRequestEntity entity = findSwapOrThrow(swapId);
        // 認可は Facade 済み。認可の後に親（枠・スケジュール）が消えた競合を 404 にするため読み直す。
        resolveTeamIdBySwap(entity);
        validatePendingStatus(entity);

        if (entity.getRequesterId().equals(accepterId)) {
            throw new BusinessException(ShiftErrorCode.SWAP_SELF_REQUEST);
        }

        entity.accept(accepterId);
        entity = swapRepository.save(entity);

        log.info("交代リクエスト承諾: id={}, accepterId={}", swapId, accepterId);
        return shiftMapper.toSwapResponse(entity);
    }

    /**
     * 交代リクエストを承認・却下する（管理者）。
     *
     * @param swapId  交代リクエストID
     * @param req     承認・却下リクエスト
     * @param adminId 管理者ID
     * @param expectedAccepterId Facadeが現在資格を確認した受諾者（却下時はnull）
     * @return 更新された交代リクエスト
     */
    @Transactional
    public SwapRequestResponse resolveSwapRequest(
            Long swapId, ResolveSwapRequestRequest req, Long adminId, Long expectedAccepterId) {
        ShiftSwapRequestEntity entity = findSwapOrThrow(swapId);
        // 認可は Facade 済み。認可の後に親（枠・スケジュール）が消えた競合を 404 にするため読み直す。
        resolveTeamIdBySwap(entity);

        ShiftSlotEntity approvalSlot = null;
        ShiftScheduleEntity approvalSchedule = null;
        if (ACTION_APPROVE.equals(req.getAction())) {
            approvalSlot = slotRepository.findById(entity.getSlotId())
                    .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND));
            approvalSchedule = scheduleRepository.findById(approvalSlot.getScheduleId())
                    .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));
            // ここまで親はscope読取だけ。古いmanaged親へロックを追加するとHibernateが先に
            // version不一致を投げるため、未編集の親だけを外して現在値をロック取得する。
            entityManager.detach(approvalSchedule);
            approvalSchedule = scheduleRepository.findByIdForUpdate(approvalSlot.getScheduleId())
                    .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));
            // scope解決で親・枠が既にPCへ載るため、ロック取得だけでは古い状態が残る。
            // 親→枠の順に現在読み取りし、先行commitの公開取消・手動編集・削除を反映する。
            try {
                entityManager.refresh(approvalSchedule, LockModeType.PESSIMISTIC_WRITE);
            } catch (EntityNotFoundException e) {
                throw new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            }
            try {
                entityManager.refresh(approvalSlot, LockModeType.PESSIMISTIC_WRITE);
            } catch (EntityNotFoundException e) {
                throw new BusinessException(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);
            }
            try {
                entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            } catch (EntityNotFoundException e) {
                throw new BusinessException(ShiftErrorCode.SWAP_REQUEST_NOT_FOUND);
            }
        }
        validateAcceptedStatus(entity);

        switch (req.getAction()) {
            case ACTION_APPROVE -> {
                validateApprovalAccepter(entity);
                if (!entity.getAccepterId().equals(expectedAccepterId)) {
                    throw new BusinessException(ShiftErrorCode.OPTIMISTIC_LOCK_CONFLICT);
                }
                if (approvalSchedule.getStatus() != ShiftScheduleStatus.PUBLISHED) {
                    throw new BusinessException(ShiftErrorCode.INVALID_SCHEDULE_STATUS);
                }
                slotService.replaceAssignmentForSwap(
                        approvalSlot.getId(), entity.getRequesterId(), entity.getAccepterId(), adminId);
                entity.approve(adminId, req.getAdminNote());
            }
            case ACTION_REJECT -> entity.reject(adminId, req.getAdminNote());
            default -> throw new BusinessException(ShiftErrorCode.INVALID_SWAP_STATUS);
        }

        entity = swapRepository.save(entity);
        // swapの競合も同じTX中に検出し、枠と割当履歴をまとめてrollbackする。
        swapRepository.flush();
        log.info("交代リクエスト処理: id={}, action={}", swapId, req.getAction());
        return shiftMapper.toSwapResponse(entity);
    }

    /**
     * 交代リクエストをキャンセルする。
     *
     * @param swapId 交代リクエストID
     * @param userId 操作者ID
     */
    @Transactional
    public void cancelSwapRequest(Long swapId, Long userId) {
        ShiftSwapRequestEntity entity = findSwapOrThrow(swapId);
        // 認可は Facade 済み。申請者本人は所属を問わず許可（親の存在に依存しない）ので scope 解決を行わない。
        // 本人以外（当該チームの ADMIN 以上として許可された者）は、親が消えた競合を 404 にするため読み直す。
        if (!entity.getRequesterId().equals(userId)) {
            resolveTeamIdBySwap(entity);
        }
        validatePendingStatus(entity);

        entity.cancel();
        swapRepository.save(entity);
        log.info("交代リクエストキャンセル: id={}", swapId);
    }

    // ═════════════════════════════════════════════════════════════════════
    // scope 解決（Facade が認可の前に呼ぶ readOnly の読み取り。戻り値は record で Entity は返さない）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 交代リクエストの scope（所属チーム ID と申請者 ID）。
     *
     * @param teamId      所属チーム ID（申請者本人の取消経路など、scope が不要なときは null）
     * @param requesterId 申請者 ID（取消の Facade が操作者との同一性を見るために返す。それ以外の EP では参照しない）
     */
    public record SwapScope(Long teamId, Long requesterId) { }

    /** 認可後に承認対象の受諾者を返す。資格確認は非TXのFacadeで行う。 */
    public Long resolveApprovalAccepterId(Long swapId) {
        ShiftSwapRequestEntity entity = findSwapOrThrow(swapId);
        // 認可後に親が消えた場合は、状態・受諾者の不整合より既存の404を優先する。
        resolveTeamIdBySwap(entity);
        validateAcceptedStatus(entity);
        validateApprovalAccepter(entity);
        return entity.getAccepterId();
    }

    private void validateAcceptedStatus(ShiftSwapRequestEntity entity) {
        if (entity.getStatus() != SwapRequestStatus.ACCEPTED) {
            throw new BusinessException(ShiftErrorCode.INVALID_SWAP_STATUS);
        }
    }

    private void validateApprovalAccepter(ShiftSwapRequestEntity entity) {
        if (entity.getAccepterId() == null || entity.getAccepterId().equals(entity.getRequesterId())) {
            throw new BusinessException(ShiftErrorCode.INVALID_SWAP_STATUS);
        }
    }

    /**
     * swapId から scope を解決する（承諾・承認/却下用）。不在なら {@code SWAP_REQUEST_NOT_FOUND}、
     * 親（枠・スケジュール）が不在なら {@code SHIFT_SLOT_NOT_FOUND} / {@code SHIFT_SCHEDULE_NOT_FOUND}
     * （是正前の応答と同一。tx内の読み直しでも同じ不在コードを適用する）。
     *
     * @param swapId 交代リクエスト ID
     * @return scope
     */
    public SwapScope resolveSwapScope(Long swapId) {
        ShiftSwapRequestEntity entity = findSwapOrThrow(swapId);
        return new SwapScope(resolveTeamIdBySwap(entity), entity.getRequesterId());
    }

    /**
     * 取消の scope を解決する。申請者本人なら scope 解決（枠→スケジュールの 2 クエリ）を行わず
     * {@code teamId=null} で返す（是正前と同じクエリ数）。
     *
     * @param swapId 交代リクエスト ID
     * @param userId 操作者 ID
     * @return scope（本人なら teamId は null）
     */
    public SwapScope resolveCancelScope(Long swapId, Long userId) {
        ShiftSwapRequestEntity entity = findSwapOrThrow(swapId);
        if (entity.getRequesterId().equals(userId)) {
            return new SwapScope(null, entity.getRequesterId());
        }
        return new SwapScope(resolveTeamIdBySwap(entity), entity.getRequesterId());
    }

    /**
     * 作成対象のシフト枠から scope を解決する。枠が不在なら {@code SHIFT_SLOT_NOT_FOUND}、
     * スケジュールが不在なら {@code SHIFT_SCHEDULE_NOT_FOUND}。
     *
     * @param slotId シフト枠 ID
     * @return scope（requesterId は null）
     */
    public SwapScope resolveSlotScope(Long slotId) {
        return new SwapScope(resolveTeamIdBySlotId(slotId), null);
    }

    /**
     * 交代リクエスト実体から所属チーム ID を解決する。
     *
     * @param entity 交代リクエスト
     * @return 所属チーム ID
     */
    private Long resolveTeamIdBySwap(ShiftSwapRequestEntity entity) {
        return resolveTeamIdBySlotId(entity.getSlotId());
    }

    /**
     * シフト枠 ID から所属チーム ID を解決する。
     *
     * <p>scope をパス変数・クエリ入力でなく<b>シフト枠→スケジュール実体由来</b>にすることで、
     * 「他チームの swapId を直接指定して越境する」BOLA を封鎖する。</p>
     *
     * @param slotId シフト枠 ID
     * @return 所属チーム ID
     */
    private Long resolveTeamIdBySlotId(Long slotId) {
        Long scheduleId = slotRepository.findById(slotId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND))
                .getScheduleId();
        return scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND))
                .getTeamId();
    }

    /**
     * 当該ユーザーに関係する交代依頼か判定する（一般メンバーの一覧可視範囲）。
     *
     * @param entity 交代リクエスト
     * @param userId 閲覧者ユーザー ID
     * @return 関係する依頼なら true
     */
    private boolean isRelatedToUser(ShiftSwapRequestEntity entity, Long userId) {
        if (userId.equals(entity.getRequesterId()) || userId.equals(entity.getAccepterId())) {
            return true;
        }
        if (isOpenCall(entity)) {
            return true;
        }
        return parseTargetUserIds(entity.getTargetUserIds()).contains(userId);
    }

    /**
     * 指名なし（OPEN_CALL）の依頼か判定する。
     *
     * <p>{@code recipientMode} は後発カラムのため、移行前の行は {@code isOpenCall} だけが
     * 立っている可能性がある。両方を見る。</p>
     *
     * @param entity 交代リクエスト
     * @return 指名なしなら true
     */
    private boolean isOpenCall(ShiftSwapRequestEntity entity) {
        return "OPEN_CALL".equals(entity.getRecipientMode())
                || Boolean.TRUE.equals(entity.getIsOpenCall());
    }

    /**
     * targetUserIds（JSON 配列文字列）を ID リストへ変換する。
     *
     * <p>壊れた値は「指名なし」ではなく「誰も指名されていない」と解釈する（空リスト）。
     * 可視範囲を広げる方向に倒さないための保守的な扱い。</p>
     *
     * @param json JSON 配列文字列（null 可）
     * @return ユーザー ID リスト（変換できない場合は空リスト）
     */
    private List<Long> parseTargetUserIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() { });
        } catch (JsonProcessingException e) {
            log.warn("targetUserIds の JSON 解析に失敗しました: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 交代リクエストを取得する。存在しない場合は例外をスローする。
     */
    private ShiftSwapRequestEntity findSwapOrThrow(Long id) {
        return swapRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SWAP_REQUEST_NOT_FOUND));
    }

    /**
     * PENDINGステータスであることを検証する。
     */
    private void validatePendingStatus(ShiftSwapRequestEntity entity) {
        if (entity.getStatus() != SwapRequestStatus.PENDING) {
            throw new BusinessException(ShiftErrorCode.INVALID_SWAP_STATUS);
        }
    }
}
