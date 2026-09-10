package com.mannschaft.app.shift.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
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
 * シフト交代リクエストサービス。メンバー間のシフト交代申請・承認フローを担当する。
 *
 * <p><b>認可（認可根治 Wave6）:</b> 本サービスの全 public メソッドは操作者 {@code userId} を受け取り、
 * <b>交代申請が指すシフト枠 → スケジュール実体から解決した teamId</b> に対して per-scope 認可する
 * （パス変数・クエリの scope 値を鵜呑みにしないことで BOLA を封鎖する）。</p>
 *
 * <p>粒度は同ドメインの既存実装（{@code ShiftSlotService} / {@code ShiftScheduleService}）に合わせる:</p>
 * <ul>
 *   <li><b>管理操作</b>（承認/却下）: ADMIN/DEPUTY_ADMIN 以上（SYSTEM_ADMIN 短絡）。</li>
 *   <li><b>一覧</b>: 当該チームのメンバー（SUPPORTER 不可）。ただし ADMIN 未満は
 *       <b>自分に関係する依頼のみ</b>に BE 側で絞り込む（CMP-260908-2116）。</li>
 *   <li><b>メンバー操作</b>（申請・承諾・手挙げ）: 当該チームのメンバー、ただし SUPPORTER は不可。</li>
 *   <li><b>本人操作</b>（取消）: 申請者本人、または当該チームの ADMIN 以上。</li>
 *   <li><b>候補者選定</b>: 従来どおり<b>申請者本人のみ</b>（本改修で緩和していない）。
 *       これに加えて当該チームのメンバーであることを前置きで検証する。</li>
 * </ul>
 *
 * <p>認可失敗は {@code COMMON_002}（403）とする。越境を 404 に寄せず 403 とするのは、
 * 同ドメインの既存契約テスト {@code ShiftScheduleScopeContractIT}（Wave3-B6）および
 * {@code ShiftSlotScopeContractIT} が別 scope ADMIN に 403 を期待しており、そちらへ揃えるため。</p>
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
    private final AccessControlService accessControlService;
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
     * @param teamId 対象チームID
     * @param status ステータスフィルタ（省略時は当該チームの全件）
     * @param userId 操作者ユーザーID
     * @return 交代リクエスト一覧（一般メンバーは自分に関係するもののみ）
     * @throws BusinessException 当該チームのメンバーでない、または SUPPORTER の場合（COMMON_002 / 403）
     */
    public List<SwapRequestResponse> listSwapRequests(Long teamId, String status, Long userId) {
        boolean privileged = checkListAccessAndIsPrivileged(teamId, userId);
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
        // 対象シフト枠の属するチームのメンバーのみ申請できる（SUPPORTER 不可）
        checkTeamMemberAccess(resolveTeamIdBySlotId(req.getSlotId()), userId);

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
        checkTeamMemberAccess(resolveTeamIdBySwap(entity), accepterId);
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
        checkTeamAdminAccess(resolveTeamIdBySwap(entity), adminId);

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
        checkRequesterOrTeamAdmin(entity, userId);
        validatePendingStatus(entity);

        entity.cancel();
        swapRepository.save(entity);
        log.info("交代リクエストキャンセル: id={}", swapId);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 認可ヘルパー（認可根治 Wave6）
    // ═════════════════════════════════════════════════════════════════════

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
     * 一覧 API の per-scope 認可を行い、「全件を見てよい立場か」を返す。
     *
     * <p>AccessControlService をこのメソッドから直接呼ぶ（番人 AuthzControllerGuardArchTest の
     * 委譲探索は深さ2までのため、認可クラスへの到達を1ホップ内に収める）。</p>
     *
     * @param teamId 対象チーム ID
     * @param userId 操作者ユーザー ID
     * @return SYSTEM_ADMIN または当該チームの ADMIN/DEPUTY_ADMIN なら true（全件可視）
     * @throws BusinessException 当該チームのメンバーでない、または SUPPORTER の場合（COMMON_002 / 403）
     */
    private boolean checkListAccessAndIsPrivileged(Long teamId, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        if (accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            return true;
        }
        // 一般メンバー（SUPPORTER は不可）。承諾できる立場と同じ条件に揃える
        //（checkTeamMemberAccess と同一方針）。
        if (!accessControlService.isMember(userId, teamId, "TEAM")
                || accessControlService.isSupporter(userId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return false;
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
     * 管理操作の per-scope 認可（SYSTEM_ADMIN 短絡 or 当該チームの ADMIN/DEPUTY_ADMIN）。
     *
     * @param teamId 対象チーム ID
     * @param userId 操作者ユーザー ID
     * @throws BusinessException 権限が無い場合（COMMON_002 / 403）
     */
    private void checkTeamAdminAccess(Long teamId, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
    }

    /**
     * メンバー操作の per-scope 認可（当該チームのメンバー、ただし SUPPORTER は不可）。
     *
     * <p>SUPPORTER を除外するのは、同ドメインの {@code ShiftSlotService#checkScheduleReadAccess} /
     * {@code ShiftPdfService} と同一方針（シフト枠の割当情報を SUPPORTER に見せない）。</p>
     *
     * @param teamId 対象チーム ID
     * @param userId 操作者ユーザー ID
     * @throws BusinessException メンバーでない場合、または SUPPORTER の場合（COMMON_002 / 403）
     */
    private void checkTeamMemberAccess(Long teamId, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (!accessControlService.isMember(userId, teamId, "TEAM")
                || accessControlService.isSupporter(userId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * 本人操作の per-scope 認可（申請者本人、または当該チームの ADMIN 以上）。
     *
     * @param entity 交代リクエスト
     * @param userId 操作者ユーザー ID
     * @throws BusinessException 申請者でも当該チームの ADMIN 以上でもない場合（COMMON_002 / 403）
     */
    private void checkRequesterOrTeamAdmin(ShiftSwapRequestEntity entity, Long userId) {
        // AccessControlService をこのメソッドから直接呼ぶ（番人 AuthzControllerGuardArchTest の
        // 委譲探索は深さ2までのため、認可クラスへの到達を1ホップ内に収める）
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (entity.getRequesterId().equals(userId)) {
            return;
        }
        accessControlService.checkAdminOrAbove(userId, resolveTeamIdBySwap(entity), "TEAM");
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
