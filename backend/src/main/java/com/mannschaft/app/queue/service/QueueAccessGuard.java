package com.mannschaft.app.queue.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.queue.QueueErrorCode;
import com.mannschaft.app.queue.QueueScopeType;
import com.mannschaft.app.queue.entity.QueueCategoryEntity;
import com.mannschaft.app.queue.entity.QueueCounterEntity;
import com.mannschaft.app.queue.entity.QueueQrCodeEntity;
import com.mannschaft.app.queue.entity.QueueTicketEntity;
import com.mannschaft.app.queue.repository.QueueCategoryRepository;
import com.mannschaft.app.queue.repository.QueueCounterRepository;
import com.mannschaft.app.queue.repository.QueueQrCodeRepository;
import com.mannschaft.app.queue.repository.QueueTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 順番待ち（F03.7）ドメインの認可ガード（認可根治戦役 Wave5）。
 *
 * <p>本ドメインの Controller 群（カテゴリ / カウンター / チケット / QRコード / 設定 / ステータス）は
 * URL パスに {@code teamId} を持ちながら、membership / ADMIN の検証を一切行っていなかった。
 * scope（{@link QueueScopeType} + scopeId）は Service 層で「一覧を絞り込むデータ条件」としてのみ
 * 用いられており、<b>操作ユーザーが当該スコープに所属するかを判定していなかった</b>。
 * さらにカウンター / チケット / QRコード / カテゴリの各 ID 指定 API は、ID を直接 {@code findById} して
 * おり、URL パスの {@code teamId} と対象データの所属スコープを突合していなかった（BOLA）。
 * 根因はドメイン全体に及ぶため、全 Controller の public 入口でここを経由して認可を敷く
 * （{@code feedback_authz_gate_on_public_entry_not_shared_method}: 認可ガードは public 入口に集約し、
 * バッチ等と共有される内部 finder には敷かない）。</p>
 *
 * <h3>二段防御</h3>
 * <ul>
 *   <li><b>一段目</b>: URL パスの scope に対する membership / ADMIN 検証
 *       （{@link #requireScopeMember} / {@link #requireScopeAdmin}）。非メンバーは 403（COMMON_002）。</li>
 *   <li><b>二段目</b>: ID 指定 API について、対象 entity を先に取得し
 *       <b>entity 由来の scope</b>（カウンター / チケット / QRコードは所属カテゴリの scope）が
 *       URL パスの scope と一致することを検証する（{@link #requireCategoryInScope} 等）。
 *       不一致・不存在はいずれも 404 で存在秘匿する（{@code QUEUE_001/002/003/008}）。</li>
 * </ul>
 * これにより「非メンバーの侵入（一段目 403）」と「メンバーによる越境 ID 参照（二段目 404）」の双方を塞ぐ。
 *
 * <h3>粒度</h3>
 * <ul>
 *   <li>read・発券系（一覧 / 詳細 / ステータス / 設定取得 / チケット発行）＝ {@link #requireScopeMember}。</li>
 *   <li>管理操作（チケット操作・次の呼び出し・全チケット一覧・カテゴリ / カウンター CRUD・
 *       QRコード発行 / 一覧 / 無効化・設定更新）＝ {@link #requireScopeAdmin}。</li>
 *   <li>自己系（{@code GET /tickets/me}）＝ Service が {@code userId} で本人分のみを返すため据え置き。
 *       {@code DELETE /tickets/{ticketId}}（自分のチケット取消）は
 *       {@link #requireOwnTicketInScope} で本人性を検証する。</li>
 * </ul>
 *
 * <p>なお、404 で用いる {@code QUEUE_001/002/003/008} は {@code Severity.WARN} 既定のままだと 400 になるため、
 * {@code GlobalExceptionHandler#ERROR_CODE_STATUS_MAP} に {@code NOT_FOUND} を登録済み。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QueueAccessGuard {

    private final AccessControlService accessControlService;
    private final QueueCategoryRepository categoryRepository;
    private final QueueCounterRepository counterRepository;
    private final QueueTicketRepository ticketRepository;
    private final QueueQrCodeRepository qrCodeRepository;

    /**
     * 指定スコープのメンバーであることを要求する（read・発券系の入口）。非メンバーは 403（COMMON_002）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID（URL パス由来）
     * @param userId    操作ユーザー ID
     */
    public void requireScopeMember(QueueScopeType scopeType, Long scopeId, Long userId) {
        accessControlService.checkMembership(userId, scopeId, scopeType.name());
    }

    /**
     * 指定スコープの ADMIN/DEPUTY_ADMIN であることを要求する（管理操作の入口）。非 ADMIN は 403（COMMON_002）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID（URL パス由来）
     * @param userId    操作ユーザー ID
     */
    public void requireScopeAdmin(QueueScopeType scopeType, Long scopeId, Long userId) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());
    }

    /**
     * カテゴリが URL パスの scope に属することを検証する。属さない / 存在しない場合は 404 で存在秘匿する。
     *
     * @param categoryId カテゴリ ID
     * @param scopeType  スコープ種別（URL パス由来）
     * @param scopeId    スコープ ID（URL パス由来）
     * @return スコープ内のカテゴリ
     */
    public QueueCategoryEntity requireCategoryInScope(Long categoryId, QueueScopeType scopeType, Long scopeId) {
        if (categoryId == null) {
            throw new BusinessException(QueueErrorCode.CATEGORY_NOT_FOUND);
        }
        return categoryRepository.findByIdAndScopeTypeAndScopeId(categoryId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(QueueErrorCode.CATEGORY_NOT_FOUND));
    }

    /**
     * カウンターが URL パスの scope（＝所属カテゴリの scope）に属することを検証する。
     * 属さない / 存在しない場合は 404 で存在秘匿する。
     *
     * @param counterId カウンター ID
     * @param scopeType スコープ種別（URL パス由来）
     * @param scopeId   スコープ ID（URL パス由来）
     * @return スコープ内のカウンター
     */
    public QueueCounterEntity requireCounterInScope(Long counterId, QueueScopeType scopeType, Long scopeId) {
        if (counterId == null) {
            throw new BusinessException(QueueErrorCode.COUNTER_NOT_FOUND);
        }
        QueueCounterEntity counter = counterRepository.findById(counterId)
                .orElseThrow(() -> new BusinessException(QueueErrorCode.COUNTER_NOT_FOUND));
        if (!isCategoryInScope(counter.getCategoryId(), scopeType, scopeId)) {
            throw new BusinessException(QueueErrorCode.COUNTER_NOT_FOUND);
        }
        return counter;
    }

    /**
     * チケットが URL パスの scope（＝所属カテゴリの scope）に属することを検証する。
     * 属さない / 存在しない場合は 404 で存在秘匿する。
     *
     * @param ticketId  チケット ID
     * @param scopeType スコープ種別（URL パス由来）
     * @param scopeId   スコープ ID（URL パス由来）
     * @return スコープ内のチケット
     */
    public QueueTicketEntity requireTicketInScope(Long ticketId, QueueScopeType scopeType, Long scopeId) {
        if (ticketId == null) {
            throw new BusinessException(QueueErrorCode.TICKET_NOT_FOUND);
        }
        QueueTicketEntity ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(QueueErrorCode.TICKET_NOT_FOUND));
        if (!isCategoryInScope(ticket.getCategoryId(), scopeType, scopeId)) {
            throw new BusinessException(QueueErrorCode.TICKET_NOT_FOUND);
        }
        return ticket;
    }

    /**
     * チケットが URL パスの scope に属し、かつ操作ユーザー本人のものであることを検証する
     * （{@code DELETE /tickets/{ticketId}}＝自分のチケット取消の入口）。
     *
     * <p>他人のチケットは「存在しない」ものとして 404 を返す（存在秘匿）。
     * ゲスト発券（{@code userId} が null）のチケットも本人特定ができないため取消対象外とする。
     * 管理者による取消は {@code PATCH /tickets/{ticketId}/action}（ADMIN 経路）を用いる。</p>
     *
     * @param ticketId  チケット ID
     * @param scopeType スコープ種別（URL パス由来）
     * @param scopeId   スコープ ID（URL パス由来）
     * @param userId    操作ユーザー ID
     * @return 本人所有かつスコープ内のチケット
     */
    public QueueTicketEntity requireOwnTicketInScope(Long ticketId, QueueScopeType scopeType,
                                                     Long scopeId, Long userId) {
        QueueTicketEntity ticket = requireTicketInScope(ticketId, scopeType, scopeId);
        if (userId == null || !userId.equals(ticket.getUserId())) {
            throw new BusinessException(QueueErrorCode.TICKET_NOT_FOUND);
        }
        return ticket;
    }

    /**
     * QRコードが URL パスの scope（＝紐づくカテゴリ、またはカウンター経由のカテゴリの scope）に
     * 属することを検証する。属さない / 存在しない場合は 404 で存在秘匿する。
     *
     * @param qrCodeId  QRコード ID
     * @param scopeType スコープ種別（URL パス由来）
     * @param scopeId   スコープ ID（URL パス由来）
     * @return スコープ内の QRコード
     */
    public QueueQrCodeEntity requireQrCodeInScope(Long qrCodeId, QueueScopeType scopeType, Long scopeId) {
        if (qrCodeId == null) {
            throw new BusinessException(QueueErrorCode.QR_CODE_NOT_FOUND);
        }
        QueueQrCodeEntity qrCode = qrCodeRepository.findById(qrCodeId)
                .orElseThrow(() -> new BusinessException(QueueErrorCode.QR_CODE_NOT_FOUND));
        if (!isQrTargetInScope(qrCode.getCategoryId(), qrCode.getCounterId(), scopeType, scopeId)) {
            throw new BusinessException(QueueErrorCode.QR_CODE_NOT_FOUND);
        }
        return qrCode;
    }

    /**
     * QRコードの参照先（カテゴリ XOR カウンター）が URL パスの scope に属することを検証する。
     * 属さない / 存在しない場合は 404 で存在秘匿する（QRコード発行・一覧の入口で用いる）。
     *
     * @param categoryId カテゴリ ID（null 可）
     * @param counterId  カウンター ID（null 可）
     * @param scopeType  スコープ種別（URL パス由来）
     * @param scopeId    スコープ ID（URL パス由来）
     */
    public void requireQrTargetInScope(Long categoryId, Long counterId,
                                       QueueScopeType scopeType, Long scopeId) {
        if (!isQrTargetInScope(categoryId, counterId, scopeType, scopeId)) {
            throw new BusinessException(QueueErrorCode.QR_CODE_NOT_FOUND);
        }
    }

    private boolean isQrTargetInScope(Long categoryId, Long counterId,
                                      QueueScopeType scopeType, Long scopeId) {
        if (categoryId != null) {
            return isCategoryInScope(categoryId, scopeType, scopeId);
        }
        if (counterId != null) {
            return counterRepository.findById(counterId)
                    .map(counter -> isCategoryInScope(counter.getCategoryId(), scopeType, scopeId))
                    .orElse(false);
        }
        // カテゴリ・カウンターのいずれも指定されていない場合は scope を確定できないため拒否する。
        return false;
    }

    private boolean isCategoryInScope(Long categoryId, QueueScopeType scopeType, Long scopeId) {
        if (categoryId == null) {
            return false;
        }
        return categoryRepository.findByIdAndScopeTypeAndScopeId(categoryId, scopeType, scopeId).isPresent();
    }
}
