package com.mannschaft.app.shift.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.SwapRequestStatus;
import com.mannschaft.app.shift.dto.CreateSwapRequestRequest;
import com.mannschaft.app.shift.dto.ResolveSwapRequestRequest;
import com.mannschaft.app.shift.dto.SwapRequestResponse;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * シフト交代リクエストの <b>トランザクション本体</b>（自ドメイン = shift の Repository だけに触れる）。
 *
 * <p><b>認可はここに置かない（CMP-260923-0954 W2 / 認可をトランザクションの外へ）:</b>
 * 権限の確認は非トランザクションの {@link ShiftSwapFacade} が行い、通ったものだけを本クラスの
 * {@code @Transactional} メソッドが実行する。{@code AccessControlService} / {@code ScopeConcealingAccessGate}
 * への依存と認可用の private メソッドは本クラスに持たない（D-3T 番人と {@code ShiftTxFacadeArchTest} が固定）。</p>
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
    @Transactional
    public SwapRequestResponse createSwapRequest(CreateSwapRequestRequest req, Long userId) {
        // 認可は Facade 済み。認可の後・tx の前にシフト枠やスケジュールが消えた競合を拾うため、
        // 解決と同じ経路（枠→スケジュール）で読み直す（不在なら Facade の解決時と同じコードの 404・DB 不変）。
        resolveTeamIdBySlotId(req.getSlotId());

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
     * @return 更新された交代リクエスト
     */
    @Transactional
    public SwapRequestResponse resolveSwapRequest(Long swapId, ResolveSwapRequestRequest req, Long adminId) {
        ShiftSwapRequestEntity entity = findSwapOrThrow(swapId);
        // 認可は Facade 済み。認可の後に親（枠・スケジュール）が消えた競合を 404 にするため読み直す。
        resolveTeamIdBySwap(entity);

        if (entity.getStatus() != SwapRequestStatus.ACCEPTED) {
            throw new BusinessException(ShiftErrorCode.INVALID_SWAP_STATUS);
        }

        switch (req.getAction()) {
            case ACTION_APPROVE -> entity.approve(adminId, req.getAdminNote());
            case ACTION_REJECT -> entity.reject(adminId, req.getAdminNote());
            default -> throw new BusinessException(ShiftErrorCode.INVALID_SWAP_STATUS);
        }

        entity = swapRepository.save(entity);
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

    /**
     * swapId から scope を解決する（承諾・承認/却下用）。不在なら {@code SWAP_REQUEST_NOT_FOUND}、
     * 親（枠・スケジュール）が不在なら {@code SHIFT_SLOT_NOT_FOUND} / {@code SHIFT_SCHEDULE_NOT_FOUND}
     * （是正前の応答と同一。tx 内の読み直しも同じメソッドで同じコードになる）。
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
