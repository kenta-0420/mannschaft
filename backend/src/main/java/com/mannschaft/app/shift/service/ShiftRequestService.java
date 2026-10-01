package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EnumInputParser;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.ShiftPreference;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.dto.CreateShiftRequestRequest;
import com.mannschaft.app.shift.dto.ShiftRequestResponse;
import com.mannschaft.app.shift.dto.ShiftRequestSummaryResponse;
import com.mannschaft.app.shift.dto.UpdateShiftRequestRequest;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * シフト希望の <b>トランザクション本体</b>（自ドメイン = shift の Repository と代理入力記録だけに触れる）。
 *
 * <p><b>認可はここに置かない（CMP-260923-0954 W1 の作り替え / 認可をトランザクションの外へ）:</b>
 * 権限の確認は非トランザクションの {@link ShiftRequestFacade} が行い、通ったものだけを本クラスの
 * メソッドが実行する。{@code AccessControlService} / {@code ScopeConcealingAccessGate} への依存と
 * 認可用の private メソッドは本クラスに持たない（D-3T 番人と {@code ShiftTxFacadeArchTest} が固定）。
 * 認可の契約（主体 × 結果）は {@link ShiftRequestFacade} の Javadoc を参照。</p>
 *
 * <p><b>scope の解決と読み直し:</b> Facade は認可の前に {@link #resolveScheduleScope} /
 * {@link #resolveRequestScope}（readOnly・自ドメインのみ）で teamId を得る。書き込み tx では必ず
 * <b>同じ経路で読み直し</b>（希望→親スケジュール）、どこかが不在・論理削除済みなら解決時と<b>同じコード</b>
 * （scheduleId 指定系は {@code SHIFT_001}、希望 ID 指定系は {@code SHIFT_003}）の 404 を投げて DB を変えない
 * （認可の後・tx の前に親が消える競合。K1/K5）。親スケジュール行の {@code FOR UPDATE} は<b>認可の後</b>
 * （tx の中）で取る（K6。部外者が他チームの行をロックできない）。scope の列
 * （希望→スケジュール→チーム）は不変という前提で、所属（memberships）の変化は従来どおりロックしない。</p>
 *
 * <p>{@code @SelfScopedEndpoint} の {@code listMyRequests} は呼び出し元 userId だけを検索条件に使う
 * 構造的な自己スコープなので、Facade を介さず Controller が直接呼ぶ。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftRequestService {

    private final ShiftRequestRepository requestRepository;
    private final ShiftSlotRepository slotRepository;
    private final ShiftScheduleService scheduleService;
    private final ShiftMapper shiftMapper;
    private final UserRoleRepository userRoleRepository;
    private final ProxyInputContext proxyInputContext;
    private final ProxyInputRecordRepository proxyInputRecordRepository;
    @Qualifier("wallClock")
    private final Clock wallClock;

    /**
     * シフト希望の所属スケジュールの scope（所属チーム ID）。Entity は返さない。
     *
     * @param teamId 所属チーム ID
     */
    public record ScheduleScope(Long teamId) { }

    /**
     * シフト希望の scope（所属チーム ID と提出者 ID）。Entity は返さない。
     *
     * @param teamId      親スケジュール経由の所属チーム ID
     * @param ownerUserId 提出者 ID（更新・削除の「本人」判定に Facade が使う）
     */
    public record RequestScope(Long teamId, Long ownerUserId) { }

    /**
     * scheduleId から scope を解決する（一覧・サマリー・提出用。Facade が認可の前に呼ぶ readOnly の読み取り）。
     * 不在・論理削除済みは {@code SHIFT_001}（404）。
     *
     * @param scheduleId スケジュールID
     * @return scope
     */
    public ScheduleScope resolveScheduleScope(Long scheduleId) {
        return new ScheduleScope(scheduleService.findScheduleOrThrow(scheduleId).getTeamId());
    }

    /**
     * 希望 ID から scope を解決する（更新・削除用。Facade が認可の前に呼ぶ readOnly の読み取り）。
     * 希望が不在、または親スケジュールが不在・論理削除済みなら<b>希望の不在コード</b>（{@code SHIFT_003}・404）。
     * 認可（本人・SYSTEM_ADMIN の短絡を含む）より前に親の生存を確認する（CMP-260917-1136）ため、
     * ロックは取らない（ロックは認可の後の tx 本体で取る）。
     *
     * @param requestId 希望ID
     * @return scope
     */
    public RequestScope resolveRequestScope(Long requestId) {
        ShiftRequestEntity entity = findRequestOrThrow(requestId);
        ShiftScheduleEntity schedule = scheduleService.findSchedule(entity.getScheduleId())
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND));
        return new RequestScope(schedule.getTeamId(), entity.getUserId());
    }

    /**
     * スケジュールのシフト希望一覧を取得する（認可は {@link ShiftRequestFacade} 済み）。
     *
     * @param scheduleId スケジュールID
     * @return シフト希望一覧
     * @throws BusinessException 認可の後にスケジュールが消えた競合（SHIFT_001 / 404）
     */
    public List<ShiftRequestResponse> listRequests(Long scheduleId) {
        // 認可の後・tx の前に親が消えた競合を、解決時と同じコードの 404 にするため読み直す。
        scheduleService.findScheduleOrThrow(scheduleId);
        List<ShiftRequestEntity> entities = requestRepository.findByScheduleIdOrderBySlotDateAsc(scheduleId);
        return shiftMapper.toRequestResponseList(entities);
    }

    /**
     * 自分のシフト希望一覧を取得する。
     *
     * @param userId ユーザーID
     * @return シフト希望一覧
     */
    public List<ShiftRequestResponse> listMyRequests(Long userId) {
        List<ShiftRequestEntity> entities = requestRepository.findHistoryByUserIdIncludingDeleted(userId);
        List<ShiftRequestResponse> responses = shiftMapper.toRequestResponseList(entities);

        // 案C（CMP-260917-1136）: 親スケジュールが論理削除済みでも提出履歴は一覧から消さず、
        // 削除済みフラグだけを立てる（詳細取得は 404 のまま）。
        java.util.Set<Long> scheduleIds = responses.stream()
                .map(ShiftRequestResponse::getScheduleId)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<Long> existingScheduleIds = scheduleService.findExistingScheduleIds(scheduleIds);

        return responses.stream()
                .map(r -> r.toBuilder()
                        .scheduleDeleted(!existingScheduleIds.contains(r.getScheduleId()))
                        .build())
                .toList();
    }

    /**
     * シフト希望を提出する。
     *
     * @param req    提出リクエスト
     * @param userId ユーザーID
     * @return 提出されたシフト希望
     */
    // TODO: shiftドメインとproxyドメインをまたいでいる（ProxyInputRecordRepositoryを直接参照）。将来はProxyInputServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09
    @Transactional
    public ShiftRequestResponse submitRequest(CreateShiftRequestRequest req, Long userId) {
        // 認可（在籍メンバーのみ。越境は不在と同一の SHIFT_001）は Facade 済み。ここは認可の後に取る
        // 親スケジュール行のロック兼読み直し（不在・論理削除済みなら Facade の解決時と同じ SHIFT_001・DB 不変）。
        ShiftScheduleEntity schedule = scheduleService.findScheduleForUpdateOrThrow(req.getScheduleId());
        // slotId の実体整合検証は BOLA 封鎖そのものなので、tx 本体の入口のここで行う。
        validateSlotIdentity(req);
        validateCollectingStatus(schedule);
        validateRequestDeadline(schedule);

        // 重複チェック（枠単位。slotId が NULL の日単位希望のみ従来どおり「同一日 1 件」で判定する）
        findDuplicateRequest(req, userId).ifPresent(existing -> {
            throw new BusinessException(ShiftErrorCode.REQUEST_ALREADY_EXISTS);
        });

        ShiftRequestEntity entity = ShiftRequestEntity.builder()
                .scheduleId(req.getScheduleId())
                .userId(userId)
                .slotId(req.getSlotId())
                .slotDate(req.getSlotDate())
                .preference(EnumInputParser.parse(ShiftPreference.class, req.getPreference(), "preference"))
                .note(req.getNote())
                .build();

        // アプリ層の事前チェックは原子的でない（同時リクエストは両方とも通過しうる）。
        // 最後の砦は DB の UNIQUE 制約であり、その違反は握りつぶさず 409 へ写像する（設計 §11.5.1.2）。
        try {
            entity = requestRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            log.info("シフト希望の一意性制約違反（同時提出）: scheduleId={}, userId={}, slotId={}",
                    req.getScheduleId(), userId, req.getSlotId());
            throw new BusinessException(ShiftErrorCode.REQUEST_ALREADY_EXISTS);
        }

        // 代理入力の場合: proxy_input_records を作成し、フラグをセット
        if (proxyInputContext.isProxy()) {
            ProxyInputRecordEntity proxyRecord = buildAndSaveProxyInputRecord("SHIFT_REQUEST", entity.getId());
            entity = requestRepository.save(entity.toBuilder()
                    .isProxyInput(true)
                    .proxyInputRecordId(proxyRecord.getId())
                    .build());
        }

        log.info("シフト希望提出: id={}, scheduleId={}, userId={}", entity.getId(), req.getScheduleId(), userId);
        return shiftMapper.toRequestResponse(entity);
    }

    /**
     * シフト希望を更新する。
     *
     * @param requestId リクエストID
     * @param req       更新リクエスト
     * @return 更新されたシフト希望
     */
    @Transactional
    public ShiftRequestResponse updateRequest(Long requestId, UpdateShiftRequestRequest req) {
        // 認可は Facade 済み。希望→親スケジュールを読み直し（親行は認可の後にここで FOR UPDATE）、
        // 認可の後に親が消えた競合は解決時と同じ SHIFT_003 の 404・DB 不変にする。
        ShiftRequestEntity entity = findRequestOrThrow(requestId);
        ShiftScheduleEntity schedule = findParentScheduleOrConceal(entity);

        validateCollectingStatus(schedule);
        validateRequestDeadline(schedule);

        entity.updatePreference(EnumInputParser.parse(ShiftPreference.class, req.getPreference(), "preference"), req.getNote());
        entity = requestRepository.save(entity);

        log.info("シフト希望更新: id={}", requestId);
        return shiftMapper.toRequestResponse(entity);
    }

    /**
     * シフト希望を削除する。
     *
     * @param requestId リクエストID
     * @throws BusinessException 不在・親削除済み（SHIFT_003 / 404）
     */
    @Transactional
    public void deleteRequest(Long requestId) {
        // 認可は Facade 済み（親スケジュールの生存確認も認可より先に Facade の解決時に行っている）。
        // ここは希望→親の読み直しと、認可の後の親行ロック。
        ShiftRequestEntity entity = findRequestOrThrow(requestId);
        findParentScheduleOrConceal(entity);
        requestRepository.softDeleteById(requestId);
        log.info("シフト希望削除: id={}", requestId);
    }

    /**
     * シフト希望提出サマリーを取得する。
     *
     * <p>v2 拡張: 5 段階 preference 別カウント（PREFERRED / AVAILABLE / WEAK_REST /
     * STRONG_REST / ABSOLUTE_REST）を 1 クエリで集計して返却する。</p>
     *
     * @param scheduleId  スケジュールID
     * @param actorUserId 操作者ユーザーID（認可は {@link ShiftRequestFacade} 済み。監査ログ用）
     * @return 提出サマリー
     * @throws BusinessException 認可の後にスケジュールが消えた競合（SHIFT_001 / 404）
     */
    // TODO: shiftドメインとroleドメインをまたいでいる（UserRoleRepositoryを直接参照）。将来はUserRoleQueryServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09
    public ShiftRequestSummaryResponse getRequestSummary(Long scheduleId, Long actorUserId) {
        // 認可は Facade 済み。スケジュールの読み直し（不在なら解決時と同じ SHIFT_001）が teamId の取得を兼ねる。
        ShiftScheduleEntity schedule = scheduleService.findScheduleOrThrow(scheduleId);
        log.debug("シフト希望サマリー取得: scheduleId={}, actorUserId={}", scheduleId, actorUserId);
        List<Long> memberIds = userRoleRepository.findMemberCandidateIdsByTeam(schedule.getTeamId())
                .stream().distinct().toList();
        long submittedCount = memberIds.isEmpty()
                ? 0
                : requestRepository.countSubmittedMembersByScheduleId(scheduleId, memberIds);
        long totalMembers = memberIds.size();
        long pendingCount = Math.max(0, totalMembers - submittedCount);

        Map<ShiftPreference, Long> preferenceCounts = aggregatePreferenceCounts(scheduleId);

        return new ShiftRequestSummaryResponse(
                scheduleId,
                totalMembers,
                submittedCount,
                pendingCount,
                preferenceCounts.getOrDefault(ShiftPreference.PREFERRED, 0L),
                preferenceCounts.getOrDefault(ShiftPreference.AVAILABLE, 0L),
                preferenceCounts.getOrDefault(ShiftPreference.WEAK_REST, 0L),
                preferenceCounts.getOrDefault(ShiftPreference.STRONG_REST, 0L),
                preferenceCounts.getOrDefault(ShiftPreference.ABSOLUTE_REST, 0L));
    }

    /**
     * スケジュール単位の 5 段階 preference 別件数を集計する。
     *
     * <p>DB への 1 クエリで取得した結果を {@link EnumMap} に詰め替えて返却。
     * 集計対象が存在しない preference は Map に含まれず、呼び出し側で {@code 0} として扱う。</p>
     */
    private Map<ShiftPreference, Long> aggregatePreferenceCounts(Long scheduleId) {
        Map<ShiftPreference, Long> counts = new EnumMap<>(ShiftPreference.class);
        List<Object[]> rows = requestRepository.countByPreferenceForSchedule(scheduleId);
        for (Object[] row : rows) {
            ShiftPreference preference = (ShiftPreference) row[0];
            Long count = (Long) row[1];
            if (preference != null && count != null) {
                counts.put(preference, count);
            }
        }
        return counts;
    }

    /**
     * 代理入力記録を作成して保存する（冪等性チェック付き）。
     *
     * @param targetEntityType 対象エンティティ種別
     * @param targetEntityId   対象エンティティID
     * @return 保存済みの代理入力記録エンティティ
     */
    private ProxyInputRecordEntity buildAndSaveProxyInputRecord(String targetEntityType, Long targetEntityId) {
        Long proxyUserId = SecurityUtils.getCurrentUserIdOrNull();
        // 冪等性チェック（紙運用での二重登録防止）
        return proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                proxyInputContext.getConsentId(), targetEntityType, targetEntityId)
                .orElseGet(() -> proxyInputRecordRepository.save(
                        ProxyInputRecordEntity.builder()
                                .proxyInputConsentId(proxyInputContext.getConsentId())
                                .subjectUserId(proxyInputContext.getSubjectUserId())
                                .proxyUserId(proxyUserId)
                                .featureScope("SHIFT_REQUEST")
                                .targetEntityType(targetEntityType)
                                .targetEntityId(targetEntityId)
                                .inputSource(ProxyInputRecordEntity.InputSource.valueOf(
                                        proxyInputContext.getInputSource()))
                                .originalStorageLocation(proxyInputContext.getOriginalStorageLocation())
                                .build()));
    }

    /**
     * {@code slotId} の実体整合を検証する（設計 §11.5.1.1 / CMP-260909-1143）。
     *
     * <p>枠を<b>実体で引き</b>、リクエストが名乗る {@code scheduleId} / {@code slotDate} と突き合わせる。
     * 検証しないと他チームの枠 ID を自チームの {@code scheduleId} に紐付けられる（BOLA）。</p>
     *
     * <ul>
     *   <li>枠が存在しない → <b>403</b>（{@link ShiftErrorCode#ACCESS_DENIED}）。
     *       404 と畳まないのは <b>ID の存否を漏らさない</b>ため（存在オラクルの封鎖）。</li>
     *   <li>{@code slot.scheduleId} 不一致 → <b>403</b>（越境）。</li>
     *   <li>{@code slot.slotDate} 不一致 → <b>400</b>（{@link ShiftErrorCode#REQUEST_SLOT_DATE_MISMATCH}。
     *       越境ではなくクライアントの自己矛盾）。</li>
     * </ul>
     *
     * @param req 提出リクエスト
     */
    private void validateSlotIdentity(CreateShiftRequestRequest req) {
        Long slotId = req.getSlotId();
        if (slotId == null) {
            return;
        }
        ShiftSlotEntity slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.ACCESS_DENIED));
        if (!Objects.equals(slot.getScheduleId(), req.getScheduleId())) {
            throw new BusinessException(ShiftErrorCode.ACCESS_DENIED);
        }
        if (!Objects.equals(slot.getSlotDate(), req.getSlotDate())) {
            throw new BusinessException(ShiftErrorCode.REQUEST_SLOT_DATE_MISMATCH);
        }
    }

    /**
     * 既存の重複希望を引く（設計 §11.5.1）。
     *
     * <p>枠指定（{@code slotId} 非 null）は {@code (scheduleId, userId, slotId)}、
     * 日単位（{@code slotId} null）は {@code (scheduleId, userId, slotDate)} が一意性の単位。</p>
     *
     * @param req    提出リクエスト
     * @param userId ユーザーID
     * @return 既存の希望（無ければ空）
     */
    private Optional<ShiftRequestEntity> findDuplicateRequest(CreateShiftRequestRequest req, Long userId) {
        if (req.getSlotId() != null) {
            return requestRepository.findByScheduleIdAndUserIdAndSlotId(
                    req.getScheduleId(), userId, req.getSlotId());
        }
        return requestRepository.findByScheduleIdAndUserIdAndSlotIdIsNullAndSlotDate(
                req.getScheduleId(), userId, req.getSlotDate());
    }

    /**
     * 希望の親スケジュールを引く。不在・論理削除済みなら<b>希望の不在コード</b>（SHIFT_003 / 404）で拒否する。
     *
     * <p>親のコード（SHIFT_001）を投げると「この希望 ID は実在し、親だけが消えている」ことが
     * 越境者にも判ってしまう（存在オラクル）。希望 ID 指定系の応答は不在・越境・親削除済みで一致させる。</p>
     */
    private ShiftScheduleEntity findParentScheduleOrConceal(ShiftRequestEntity entity) {
        return scheduleService.findScheduleForUpdate(entity.getScheduleId())
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND));
    }

    /**
     * シフト希望を取得する。存在しない場合は例外をスローする。
     */
    private ShiftRequestEntity findRequestOrThrow(Long id) {
        return requestRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND));
    }

    /**
     * スケジュールが希望収集中であることを検証する。
     */
    private void validateCollectingStatus(ShiftScheduleEntity schedule) {
        if (schedule.getStatus() != ShiftScheduleStatus.COLLECTING) {
            throw new BusinessException(ShiftErrorCode.INVALID_SCHEDULE_STATUS);
        }
    }

    /**
     * 希望提出期限を過ぎていないことを検証する。
     */
    private void validateRequestDeadline(ShiftScheduleEntity schedule) {
        if (schedule.getRequestDeadline() != null
                && LocalDateTime.now(wallClock).isAfter(schedule.getRequestDeadline())) {
            throw new BusinessException(ShiftErrorCode.REQUEST_DEADLINE_PASSED);
        }
    }
}
