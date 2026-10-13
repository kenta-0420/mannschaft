package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.ChangeRequestStatus;
import com.mannschaft.app.shift.ChangeRequestType;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.ChangeRequestResponse;
import com.mannschaft.app.shift.dto.CreateChangeRequestRequest;
import com.mannschaft.app.shift.dto.ReviewChangeRequestRequest;
import com.mannschaft.app.shift.entity.ShiftChangeRequestEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftChangeRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * シフト変更依頼の <b>トランザクション本体</b>（自ドメイン = shift の Repository だけに触れる）。
 * A-1確定前変更・A-2個別交代・A-3オープンコールの依頼フローを担当する。
 *
 * <p><b>認可はここに置かない（CMP-260923-0954 W2）:</b> 権限の確認は非トランザクションの
 * {@link ShiftChangeRequestFacade} が行う。{@code AccessControlService} / {@code ScopeConcealingAccessGate}
 * への依存と認可用の private メソッドは持たない（D-3T 番人と {@code ShiftTxFacadeArchTest} が固定）。
 * Facade は認可の前に {@link #resolveHead} / {@link #resolveScheduleTeamId}（readOnly）で scope を解き、
 * 書き込み tx（および一覧・詳細）では<b>同じ経路で読み直して</b>、不在・論理削除済みなら Facade の解決時と
 * 同じコードの 404 を投げる（認可の後・tx の前に親が消える競合）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftChangeRequestService {

    /** オープンコール月次上限件数 */
    private static final long OPEN_CALL_MONTHLY_LIMIT = 3L;

    private final ShiftChangeRequestRepository changeRequestRepository;
    private final ShiftScheduleRepository scheduleRepository;
    private final ShiftSlotRepository slotRepository;

    /**
     * 変更依頼を作成する。
     * - MEMBER: 自チームのスケジュールのみ依頼可
     * - オープンコール（A-3）: 月3件上限チェック
     *
     * <p><b>認可（認可根治 Wave7）:</b> 「MEMBER: 自チームのスケジュールのみ依頼可」という方針を、
     * {@code scheduleId} から解決したチームへの所属をサーバー側で強制することで実効化する
     * （同一クラスの {@code list}（Wave6）・{@code withdraw} と同じ「実体由来 scope」の作法）。</p>
     *
     * <p><b>BOLA 封鎖:</b> {@code slotId} が指定された場合、その枠が当該スケジュールに属することを
     * 検証する。属さない枠は<b>存在を秘匿して 404</b>（{@code SHIFT_SLOT_NOT_FOUND}）とし、
     * 他チームの枠 ID の存在有無を観測させない。</p>
     *
     * @param request 作成リクエスト
     * @param userId  依頼者ユーザーID
     * @return 作成された変更依頼レスポンス
     */
    @Transactional
    public ChangeRequestResponse create(CreateChangeRequestRequest request, Long userId) {
        // 認可は Facade 済み。認可の後・tx の前にスケジュールが消えた競合を 404 にするため読み直す
        // （不在なら Facade の解決時と同じ SHIFT_SCHEDULE_NOT_FOUND・DB 不変）。
        resolveTeamId(request.scheduleId());
        checkSlotBelongsToSchedule(request.slotId(), request.scheduleId());

        // オープンコールの月次上限チェック
        if (request.requestType() == ChangeRequestType.OPEN_CALL) {
            long count = changeRequestRepository.countByRequestedByAndRequestTypeInCurrentMonth(
                    userId, ChangeRequestType.OPEN_CALL);
            if (count >= OPEN_CALL_MONTHLY_LIMIT) {
                throw new BusinessException(ShiftErrorCode.OPEN_CALL_MONTHLY_LIMIT_EXCEEDED);
            }
        }

        ShiftChangeRequestEntity entity = ShiftChangeRequestEntity.builder()
                .scheduleId(request.scheduleId())
                .slotId(request.slotId())
                .requestType(request.requestType())
                .requestedBy(userId)
                .reason(request.reason())
                .build();

        entity = changeRequestRepository.save(entity);
        log.info("シフト変更依頼作成: id={}, scheduleId={}, type={}, requestedBy={}",
                entity.getId(), request.scheduleId(), request.requestType(), userId);
        return toResponse(entity);
    }

    /**
     * 変更依頼一覧を取得する。
     *
     * <p><b>認可（認可根治 Wave6 / 権限昇格の封鎖）:</b> 旧実装は呼び出し側から渡された
     * {@code role} 文字列（実体はクエリパラメータ {@code ?role=}）で全件返却か自分の分のみかを
     * 分岐していた。<b>クライアント入力を認可判断の材料にしていた</b>ため、一般メンバーが
     * {@code ADMIN} を自称するだけで他人の依頼を含む全件を取得できた。
     * 本実装では {@code role} を撤廃し、{@code scheduleId} から解決したチームに対する
     * サーバー側のロール判定でのみ分岐する。</p>
     *
     * <p>粒度は同ドメインの兄弟 API に合わせる。当該チームの ADMIN/DEPUTY_ADMIN（および
     * SYSTEM_ADMIN）はスケジュール全件、一般メンバーは自分の依頼のみ、非メンバーは
     * {@code COMMON_002}（403）とする。非メンバーを 403 とするのは
     * {@code ShiftPdfService#checkMemberAndNotSupporter} と同一方針。</p>
     *
     * @param scheduleId スケジュールID
     * @param userId     操作者ユーザーID
     * @param scopeAdmin Facade の認可結果（SYSTEM_ADMIN または当該チームの ADMIN 以上なら true＝全件）
     * @return 変更依頼一覧
     */
    public List<ChangeRequestResponse> list(Long scheduleId, Long userId, boolean scopeAdmin) {
        // 認可は Facade 済み。認可の後にスケジュールが消えた競合を 404 にするため読み直す。
        resolveTeamId(scheduleId);

        List<ShiftChangeRequestEntity> entities;
        if (scopeAdmin) {
            entities = changeRequestRepository.findAllByScheduleIdOrderByCreatedAtDesc(scheduleId);
        } else {
            entities = changeRequestRepository.findAllByRequestedByAndScheduleId(userId, scheduleId);
        }
        return entities.stream().map(this::toResponse).toList();
    }

    /**
     * 変更依頼詳細を取得する（IDOR チェック付き）。
     *
     * <p><b>認可（認可根治 Wave6 / 死文だった Javadoc の実装）:</b> 旧実装は
     * 「IDOR チェック付き」と Javadoc に明記しながら<b>本体に照合コードが無く</b>、
     * 認証済みであれば任意の ID の変更依頼を閲覧できた。本実装で実際の照合を行う。</p>
     *
     * <p>閲覧を許すのは「依頼者本人」または「当該シフトの所属チームの ADMIN/DEPUTY_ADMIN」
     *（SYSTEM_ADMIN は短絡許可）のみ。それ以外は<b>存在を秘匿するため 404</b>
     *（{@code CHANGE_REQUEST_NOT_FOUND}）を返す。403 と 404 を撃ち分けると
     * ID の存在有無が観測できてしまうため、越境時は未存在と同じ応答に寄せている。</p>
     *
     * @param id     変更依頼ID
     * @param userId 操作者ユーザーID
     * @return 変更依頼レスポンス
     */
    public ChangeRequestResponse get(Long id, Long userId) {
        ShiftChangeRequestEntity entity = findOrThrow(id);

        // 認可は Facade 済み（依頼者本人は親の存在に依存しない）。本人以外は管理者として許可された者なので、
        // 認可の後にスケジュールが消えた競合を 404 にするため読み直す。
        if (!entity.getRequestedBy().equals(userId)) {
            resolveTeamId(entity.getScheduleId());
        }
        return toResponse(entity);
    }

    /**
     * 変更依頼を審査する（ADMIN のみ）。楽観ロックチェックを行う。
     *
     * @param id      変更依頼ID
     * @param request 審査リクエスト
     * @param userId  審査者ユーザーID
     * @return 更新された変更依頼レスポンス
     */
    @Transactional
    public ChangeRequestResponse review(Long id, ReviewChangeRequestRequest request, Long userId) {
        ShiftChangeRequestEntity entity = findOrThrow(id);

        // 認可（SYSTEM_ADMIN 短絡 or 当該チームの ADMIN/DEPUTY_ADMIN）は Facade 済み。
        // 認可の後にスケジュールが消えた競合を 404 にするため読み直す。
        resolveTeamId(entity.getScheduleId());

        if (entity.getStatus() != ChangeRequestStatus.OPEN) {
            throw new BusinessException(ShiftErrorCode.INVALID_CHANGE_REQUEST_STATUS);
        }

        // 楽観ロックチェック
        if (!entity.getVersion().equals(request.version().longValue())) {
            throw new BusinessException(ShiftErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        }

        switch (request.decision()) {
            case ACCEPTED -> entity.accept(userId, request.reviewComment());
            case REJECTED -> entity.reject(userId, request.reviewComment());
            default -> throw new BusinessException(ShiftErrorCode.INVALID_CHANGE_REQUEST_STATUS);
        }

        entity = changeRequestRepository.save(entity);
        changeRequestRepository.flush();
        log.info("シフト変更依頼審査: id={}, decision={}, reviewerId={}", id, request.decision(), userId);
        return toResponse(entity);
    }

    /**
     * 変更依頼を取り下げる（依頼者のみ、OPEN のもの）。
     *
     * @param id     変更依頼ID
     * @param userId 操作者ユーザーID
     */
    @Transactional
    public void withdraw(Long id, Long userId) {
        ShiftChangeRequestEntity entity = findOrThrow(id);

        // 認可は Facade 済み。取下げは依頼者本人のみ（親の存在に依存しない）。
        // Facade を経由しない呼び出しで本人以外が来た場合に備え、同一性だけをここで再確認する。
        if (!entity.getRequestedBy().equals(userId)) {
            throw new BusinessException(ShiftErrorCode.ACCESS_DENIED);
        }

        if (entity.getStatus() != ChangeRequestStatus.OPEN) {
            throw new BusinessException(ShiftErrorCode.INVALID_CHANGE_REQUEST_STATUS);
        }

        entity.withdraw();
        changeRequestRepository.save(entity);
        log.info("シフト変更依頼取下: id={}, userId={}", id, userId);
    }

    // ═════════════════════════════════════════════════════════════════════
    // scope 解決（Facade が認可の前に呼ぶ readOnly の読み取り。戻り値は record で Entity は返さない）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 変更依頼の先頭情報（認可に必要な最小限）。
     *
     * @param requestedBy 依頼者 ID
     * @param scheduleId  対象スケジュール ID
     */
    public record ChangeRequestHead(Long requestedBy, Long scheduleId) { }

    /**
     * 変更依頼の依頼者とスケジュール ID を解決する。不在なら {@code CHANGE_REQUEST_NOT_FOUND}。
     *
     * @param id 変更依頼 ID
     * @return 先頭情報
     */
    public ChangeRequestHead resolveHead(Long id) {
        ShiftChangeRequestEntity entity = findOrThrow(id);
        return new ChangeRequestHead(entity.getRequestedBy(), entity.getScheduleId());
    }

    /**
     * スケジュール ID から所属チーム ID を解決する。不在・論理削除済みなら {@code SHIFT_SCHEDULE_NOT_FOUND}。
     * tx 内の読み直しも同じ経路（{@link #resolveTeamId}）を通るので、コードは Facade の解決時と一致する。
     *
     * @param scheduleId スケジュール ID
     * @return 所属チーム ID
     */
    public Long resolveScheduleTeamId(Long scheduleId) {
        return resolveTeamId(scheduleId);
    }

    /**
     * 指定された枠が当該スケジュールに属することを検証する（BOLA 封鎖）。
     *
     * <p>{@code slotId} は任意項目（NULL = スケジュール全体への依頼）のため、NULL は素通し。
     * 非 NULL のとき、枠が存在しない／別スケジュールの枠である場合はいずれも
     * {@code SHIFT_SLOT_NOT_FOUND}（404）とし、他チームの枠 ID の存在有無を漏らさない。</p>
     *
     * @param slotId     リクエスト由来の枠 ID（null 可）
     * @param scheduleId 対象スケジュール ID
     */
    private void checkSlotBelongsToSchedule(Long slotId, Long scheduleId) {
        if (slotId == null) {
            return;
        }
        ShiftSlotEntity slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND));
        if (!scheduleId.equals(slot.getScheduleId())) {
            throw new BusinessException(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);
        }
    }

    /**
     * スケジュール ID から所属チーム ID を解決する（scope をパス/クエリ入力でなく実体由来にする）。
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
     * 変更依頼を取得する。存在しない場合は例外をスローする。
     */
    private ShiftChangeRequestEntity findOrThrow(Long id) {
        return changeRequestRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.CHANGE_REQUEST_NOT_FOUND));
    }

    /**
     * エンティティをレスポンス DTO に変換する。
     */
    private ChangeRequestResponse toResponse(ShiftChangeRequestEntity entity) {
        return ChangeRequestResponse.builder()
                .id(entity.getId())
                .scheduleId(entity.getScheduleId())
                .slotId(entity.getSlotId())
                .version(entity.getVersion())
                .requestInfo(new ChangeRequestResponse.ChangeRequestTypeDto(
                        entity.getRequestType(), entity.getReason(), entity.getRequestedBy()))
                .reviewInfo(new ChangeRequestResponse.ChangeRequestStatusDto(
                        entity.getStatus(), entity.getReviewerId(), entity.getReviewComment(), entity.getReviewedAt()))
                .timing(new ChangeRequestResponse.ChangeRequestTimingDto(
                        entity.getExpiresAt(), entity.getCreatedAt()))
                .build();
    }
}
