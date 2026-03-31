package com.mannschaft.app.gamification.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.gamification.ActionType;
import com.mannschaft.app.gamification.GamificationErrorCode;
import com.mannschaft.app.gamification.TransactionType;
import com.mannschaft.app.gamification.entity.GamificationConfigEntity;
import com.mannschaft.app.gamification.entity.PointRuleEntity;
import com.mannschaft.app.gamification.entity.PointTransactionEntity;
import com.mannschaft.app.gamification.repository.GamificationConfigRepository;
import com.mannschaft.app.gamification.repository.PointRuleRepository;
import com.mannschaft.app.gamification.repository.PointTransactionQueryRepository;
import com.mannschaft.app.gamification.repository.PointTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * ゲーミフィケーション・ポイントサービス。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GamificationPointService {

    private final GamificationConfigRepository gamificationConfigRepository;
    private final PointRuleRepository pointRuleRepository;
    private final PointTransactionRepository pointTransactionRepository;
    private final PointTransactionQueryRepository pointTransactionQueryRepository;

    /**
     * ポイントサマリー結果レコード。
     */
    public record PointSummaryResult(
            int totalPoints,
            int weeklyPoints,
            int monthlyPoints,
            int yearlyPoints
    ) {}

    /**
     * ユーザーのポイントサマリーを取得する。
     *
     * @param userId    ユーザーID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return ポイントサマリー
     */
    public ApiResponse<PointSummaryResult> getMyPointSummary(
            Long userId, String scopeType, Long scopeId) {

        LocalDate today = LocalDate.now();

        // 週間（月曜始まり）
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));

        // 月間
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.with(TemporalAdjusters.lastDayOfMonth());

        // 年間
        LocalDate yearStart = today.withDayOfYear(1);
        LocalDate yearEnd = LocalDate.of(today.getYear(), 12, 31);

        // 全期間（累計）
        LocalDate epoch = LocalDate.of(2000, 1, 1);

        int totalPoints = pointTransactionQueryRepository.sumPointsByUserAndPeriod(
                userId, scopeType, scopeId, epoch, today);
        int weeklyPoints = pointTransactionQueryRepository.sumPointsByUserAndPeriod(
                userId, scopeType, scopeId, weekStart, weekEnd);
        int monthlyPoints = pointTransactionQueryRepository.sumPointsByUserAndPeriod(
                userId, scopeType, scopeId, monthStart, monthEnd);
        int yearlyPoints = pointTransactionQueryRepository.sumPointsByUserAndPeriod(
                userId, scopeType, scopeId, yearStart, yearEnd);

        return ApiResponse.of(new PointSummaryResult(totalPoints, weeklyPoints, monthlyPoints, yearlyPoints));
    }

    /**
     * ユーザーのポイント履歴をカーソルベースで取得する。
     *
     * @param userId    ユーザーID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param cursor    カーソル（前回取得の最後のID文字列。nullの場合は先頭から取得）
     * @param limit     取得件数
     * @return カーソルページネーション付きポイント履歴
     */
    public CursorPagedResponse<PointTransactionEntity> getMyPointHistory(
            Long userId, String scopeType, Long scopeId, String cursor, int limit) {

        // cursor はトランザクションIDの文字列表現
        Long cursorId = (cursor != null && !cursor.isBlank()) ? Long.parseLong(cursor) : null;

        // JPA クエリ: id < cursorId の条件でID降順取得
        List<PointTransactionEntity> transactions;
        int fetchSize = limit + 1; // hasNext判定のため1件多く取得

        if (cursorId == null) {
            transactions = pointTransactionRepository.findAll(
                    PageRequest.of(
                            0, fetchSize,
                            Sort.by(Sort.Direction.DESC, "id"))
            ).getContent().stream()
                    .filter(t -> t.getUserId().equals(userId)
                            && t.getScopeType().equals(scopeType)
                            && t.getScopeId().equals(scopeId))
                    .limit(fetchSize)
                    .toList();
        } else {
            transactions = pointTransactionRepository.findAll(
                    PageRequest.of(
                            0, fetchSize,
                            Sort.by(Sort.Direction.DESC, "id"))
            ).getContent().stream()
                    .filter(t -> t.getUserId().equals(userId)
                            && t.getScopeType().equals(scopeType)
                            && t.getScopeId().equals(scopeId)
                            && t.getId() < cursorId)
                    .limit(fetchSize)
                    .toList();
        }

        boolean hasNext = transactions.size() > limit;
        List<PointTransactionEntity> pageData = hasNext
                ? transactions.subList(0, limit)
                : transactions;

        String nextCursor = hasNext
                ? String.valueOf(pageData.get(pageData.size() - 1).getId())
                : null;

        return CursorPagedResponse.of(
                pageData,
                new CursorPagedResponse.CursorMeta(nextCursor, hasNext, limit)
        );
    }

    /**
     * ポイントを付与する。GamificationPointListenerから呼び出される非公開メソッド。
     *
     * 処理フロー:
     * 1. GamificationConfigが存在かつisEnabled=trueか確認
     * 2. PointRuleが存在かつisActive=trueか確認
     * 3. referenceType/referenceIdが指定されている場合、二重付与防止チェック
     * 4. daily_limitチェック（0=無制限）
     * 5. PointTransactionEntity INSERT
     *
     * @param userId        ユーザーID
     * @param scopeType     スコープ種別
     * @param scopeId       スコープID
     * @param actionType    アクション種別
     * @param referenceType 参照元種別（nullable）
     * @param referenceId   参照元ID（nullable）
     */
    @Transactional
    public void addPoint(Long userId, String scopeType, Long scopeId,
                         ActionType actionType, String referenceType, Long referenceId) {

        // 1. ゲーミフィケーション設定チェック
        GamificationConfigEntity config = gamificationConfigRepository
                .findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElse(null);

        if (config == null || !Boolean.TRUE.equals(config.getIsEnabled())) {
            log.debug("ゲーミフィケーション無効のためポイント付与をスキップ: scopeType={}, scopeId={}", scopeType, scopeId);
            return;
        }

        // 2. ポイントルール取得チェック
        PointRuleEntity rule = pointRuleRepository
                .findByScopeTypeAndScopeIdAndActionTypeAndIsActiveTrueAndDeletedAtIsNull(
                        scopeType, scopeId, actionType)
                .orElse(null);

        if (rule == null) {
            log.debug("ポイントルールが見つからないためスキップ: scopeType={}, scopeId={}, actionType={}",
                    scopeType, scopeId, actionType);
            return;
        }

        // 3. 二重付与防止チェック
        if (referenceType != null && referenceId != null) {
            boolean alreadyGranted = pointTransactionRepository
                    .findByUserIdAndScopeTypeAndScopeIdAndReferenceTypeAndReferenceId(
                            userId, scopeType, scopeId, referenceType, referenceId)
                    .isPresent();
            if (alreadyGranted) {
                log.debug("二重付与防止: userId={}, referenceType={}, referenceId={}",
                        userId, referenceType, referenceId);
                return;
            }
        }

        // 4. daily_limitチェック
        LocalDate today = LocalDate.now();
        if (rule.getDailyLimit() > 0) {
            int todayCount = pointTransactionQueryRepository.countTodayByUserAndActionType(
                    userId, scopeType, scopeId, actionType, today);
            if (todayCount >= rule.getDailyLimit()) {
                log.debug("daily_limit到達のためスキップ: userId={}, actionType={}, count={}/{}",
                        userId, actionType, todayCount, rule.getDailyLimit());
                return;
            }
        }

        // 5. PointTransactionEntity INSERT
        PointTransactionEntity transaction = PointTransactionEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .pointRuleId(rule.getId())
                .transactionType(TransactionType.EARN)
                .points(rule.getPoints())
                .actionType(actionType)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .earnedOn(today)
                .build();

        pointTransactionRepository.save(transaction);

        log.info("ポイント付与完了: userId={}, scopeType={}, scopeId={}, actionType={}, points={}",
                userId, scopeType, scopeId, actionType, rule.getPoints());
    }

    /**
     * 管理者によるスコープ全ユーザーのポイントリセット。
     * 各ユーザーの現在ポイント累計に対してRESTETトランザクションをINSERTする。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param adminId   管理者ユーザーID
     */
    @Transactional
    public void adminResetPoints(String scopeType, Long scopeId, Long adminId) {
        LocalDate today = LocalDate.now();
        LocalDate epoch = LocalDate.of(2000, 1, 1);

        // スコープ内のユーザーIDを取得
        List<PointTransactionEntity> allTransactions = pointTransactionRepository.findAll();
        List<Long> userIds = allTransactions.stream()
                .filter(t -> t.getScopeType().equals(scopeType) && t.getScopeId().equals(scopeId))
                .map(PointTransactionEntity::getUserId)
                .distinct()
                .toList();

        for (Long userId : userIds) {
            int total = pointTransactionQueryRepository.sumPointsByUserAndPeriod(
                    userId, scopeType, scopeId, epoch, today);
            if (total == 0) {
                continue;
            }
            PointTransactionEntity resetTx = PointTransactionEntity.builder()
                    .userId(userId)
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .transactionType(TransactionType.RESET)
                    .points(-total)
                    .actionType(ActionType.ADMIN_ADJUST)
                    .earnedOn(today)
                    .build();
            pointTransactionRepository.save(resetTx);
        }

        log.info("管理者ポイントリセット完了: scopeType={}, scopeId={}, adminId={}, 対象ユーザー数={}",
                scopeType, scopeId, adminId, userIds.size());
    }

    /**
     * 管理者によるポイント手動調整。
     * ADMIN_ADJUST TypeのPointTransactionを直接INSERTする。
     *
     * @param userId    対象ユーザーID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param points    調整ポイント数（負数も可）
     * @param adminId   管理者ユーザーID
     */
    @Transactional
    public void adminAdjustPoint(
            Long userId, String scopeType, Long scopeId, int points, Long adminId) {

        // 1日あたりの管理者調整上限チェック（10件/ユーザー/スコープ/日）
        LocalDate today = LocalDate.now();
        int adjustCountToday = pointTransactionQueryRepository.countAdminAdjustsByUserAndDate(
                userId, scopeType, scopeId, today);
        if (adjustCountToday >= 10) {
            log.warn("管理者ポイント調整が1日の上限に達しました: userId={}, scopeType={}, scopeId={}, count={}",
                    userId, scopeType, scopeId, adjustCountToday);
            throw new BusinessException(GamificationErrorCode.GAMIFICATION_010);
        }

        PointTransactionEntity transaction = PointTransactionEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .transactionType(TransactionType.ADMIN_ADJUST)
                .points(points)
                .actionType(ActionType.ADMIN_ADJUST)
                .earnedOn(LocalDate.now())
                .build();

        pointTransactionRepository.save(transaction);

        log.info("管理者ポイント調整完了: userId={}, scopeType={}, scopeId={}, points={}, adminId={}",
                userId, scopeType, scopeId, points, adminId);
    }
}
