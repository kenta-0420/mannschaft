package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.ReadTrackingMode;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.ReadStatusResponse;
import com.mannschaft.app.bulletin.entity.BulletinReadStatusEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.service.TournamentContactAccessService;
import com.mannschaft.app.village.service.VillageBulletinAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 掲示板既読ステータスサービス。既読マーク・既読者一覧を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BulletinReadStatusService {

    private final BulletinReadStatusRepository readStatusRepository;
    private final BulletinThreadRepository threadRepository;
    private final BulletinThreadService threadService;
    private final BulletinMapper bulletinMapper;
    private final BulletinAccessGuard accessGuard;
    /** F17.1 村掲示板グローバル方式: 村スコープの閲覧認可を委譲する。 */
    private final VillageBulletinAccessService villageBulletinAccessService;
    /** F08.7.1 連絡機能: 大会/ディビジョンスコープの閲覧認可を委譲する（既読＝閲覧可能なら可・クロスドメイン原則1）。 */
    private final TournamentContactAccessService tournamentContactAccessService;

    /**
     * スレッドを既読にする。既に既読の場合は何もしない。所属メンバーのみ。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @param userId    ユーザーID
     */
    @Transactional
    public void markAsRead(ScopeType scopeType, Long scopeId, Long threadId, Long userId) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        BulletinThreadEntity thread = threadService.findThreadOrThrow(scopeType, scopeId, threadId);

        if (readStatusRepository.existsByThreadIdAndUserId(threadId, userId)) {
            return;
        }

        BulletinReadStatusEntity entity = BulletinReadStatusEntity.builder()
                .threadId(threadId)
                .userId(userId)
                .build();
        readStatusRepository.save(entity);

        thread.incrementReadCount();
        threadRepository.save(thread);

        log.info("既読マーク: threadId={}, userId={}", threadId, userId);
    }

    /**
     * スレッドの既読者一覧を取得する。所属メンバーのみ。
     *
     * <p>設計書 §6（既読プライバシー）に従い、{@code read_tracking_mode} で個人情報の返却を制御する:</p>
     * <ul>
     *   <li>{@code INDIVIDUAL}（= 設計書 SHOW_READERS）: 既読者の配列をフル返却</li>
     *   <li>{@code COUNT_ONLY}（および NONE 相当）: 既読者の配列は返さず、件数のみを表示用に許容
     *       （本メソッドは空リストを返す。件数は {@link #getReadCount(Long)} で取得）</li>
     * </ul>
     *
     * <p>{@code filter=unread}（未読者一覧）は ADMIN のみ許可する（CRITICAL スレッドの確認漏れチェック用）。
     * 非 ADMIN が unread を指定した場合は 403。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @param userId    操作ユーザーID
     * @param filter    フィルタ（{@code "unread"} 指定時は ADMIN のみ）
     * @return 既読ステータスレスポンスリスト（プライバシーモードにより空配列の場合あり）
     */
    public List<ReadStatusResponse> listReadUsers(ScopeType scopeType, Long scopeId, Long threadId,
                                                  Long userId, String filter) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        BulletinThreadEntity thread = threadService.findThreadOrThrow(scopeType, scopeId, threadId);

        // filter=unread は ADMIN のみ
        boolean unreadFilter = "unread".equalsIgnoreCase(filter);
        if (unreadFilter && !accessGuard.isAdmin(userId, scopeType, scopeId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        // 既読プライバシー: INDIVIDUAL（SHOW_READERS）のみ readers をフル返却。
        // COUNT_ONLY / NONE は個人情報を返さない（件数は read_count / getReadCount で取得）。
        // ADMIN は確認漏れチェックの責務があるため、モードに関わらず参照可能とする。
        boolean canSeeReaders = thread.getReadTrackingMode() == ReadTrackingMode.INDIVIDUAL
                || accessGuard.isAdmin(userId, scopeType, scopeId);
        if (!canSeeReaders) {
            return List.of();
        }

        List<BulletinReadStatusEntity> readStatuses = readStatusRepository.findByThreadIdOrderByReadAtDesc(threadId);
        return bulletinMapper.toReadStatusResponseList(readStatuses);
    }

    /**
     * スレッドの既読数を取得する。
     *
     * @param threadId スレッドID
     * @return 既読数
     */
    public long getReadCount(Long threadId) {
        return readStatusRepository.countByThreadId(threadId);
    }

    // ========================================================================
    // F17.1 村掲示板グローバル方式 — 既読系（グローバル経路）
    // ========================================================================

    /**
     * グローバル方式でスレッドを既読にする（F17.1 村掲示板グローバル方式）。
     *
     * <p>{@code threadId} のみで叩かれるため、スレッドの {@code scopeType} を逆引きして認可経路を分岐する。
     * VILLAGE は村可視性認可（閲覧可能 = 既読可能）、ORG/TEAM/PERSONAL は所属認可。既読済みなら何もしない。</p>
     *
     * @param threadId スレッド ID
     * @param userId   ユーザー ID
     */
    @Transactional
    public void markAsReadGlobal(Long threadId, Long userId) {
        BulletinThreadEntity thread = findThreadByIdOrThrow(threadId);
        if (thread.getScopeType() == ScopeType.VILLAGE) {
            villageBulletinAccessService.checkVillageBulletinViewAccess(thread.getScopeVillageId(), userId);
        } else if (isTournamentScope(thread.getScopeType())) {
            // F08.7.1: 既読化は閲覧可能なら可＝canView。checkMembership(500) に落ちないよう根治。
            tournamentContactAccessService.checkView(
                    toContactScope(thread.getScopeType()), thread.getScopeId(), ContactSpaceKind.BULLETIN, userId);
        } else {
            accessGuard.checkMembership(userId, thread.getScopeType(), thread.getScopeId());
        }
        markReadInternal(thread, userId);
    }

    /**
     * グローバル方式でスコープ内の全スレッドを一括既読にする（F17.1 村掲示板グローバル方式）。
     *
     * <p>FE は {@code POST /api/v1/bulletin/threads/read-all} に {@code scopeType / scopeId / scopeVillageId}
     * を渡す。VILLAGE は村可視性認可、ORG/TEAM/PERSONAL は所属認可を行ったうえで、スコープ内の未読スレッドを
     * すべて既読化する（既読済みは件数を二重計上しない）。</p>
     *
     * @param scopeType      スコープ種別
     * @param scopeId        スコープ ID（VILLAGE 時は 0）
     * @param scopeVillageId 村 ID（VILLAGE 時必須）
     * @param userId         ユーザー ID
     * @return 新たに既読化したスレッド件数
     */
    @Transactional
    public int markAllAsReadGlobal(ScopeType scopeType, Long scopeId, UUID scopeVillageId, Long userId) {
        List<Long> threadIds;
        if (scopeType == ScopeType.VILLAGE) {
            if (scopeVillageId == null) {
                throw new BusinessException(CommonErrorCode.COMMON_001);
            }
            villageBulletinAccessService.checkVillageBulletinViewAccess(scopeVillageId, userId);
            threadIds = threadRepository.findIdsByScopeVillageId(scopeVillageId);
        } else if (isTournamentScope(scopeType)) {
            // F08.7.1: 一括既読も閲覧可能なら可＝canView。checkMembership(500) に落ちないよう根治。
            tournamentContactAccessService.checkView(
                    toContactScope(scopeType), scopeId, ContactSpaceKind.BULLETIN, userId);
            threadIds = threadRepository.findIdsByScopeTypeAndScopeId(scopeType, scopeId);
        } else {
            accessGuard.checkMembership(userId, scopeType, scopeId);
            threadIds = threadRepository.findIdsByScopeTypeAndScopeId(scopeType, scopeId);
        }
        if (threadIds.isEmpty()) {
            return 0;
        }

        // 既読済みを 1 クエリで差し引き、未読のみを既読化（N+1 回避・件数二重計上防止）
        Set<Long> alreadyRead = new HashSet<>(readStatusRepository.findReadThreadIds(threadIds, userId));
        int marked = 0;
        for (Long threadId : threadIds) {
            if (alreadyRead.contains(threadId)) {
                continue;
            }
            readStatusRepository.save(BulletinReadStatusEntity.builder()
                    .threadId(threadId)
                    .userId(userId)
                    .build());
            threadRepository.findById(threadId).ifPresent(t -> {
                t.incrementReadCount();
                threadRepository.save(t);
            });
            marked++;
        }
        log.info("一括既読: scopeType={}, scopeId={}, villageId={}, userId={}, marked={}",
                scopeType, scopeId, scopeVillageId, userId, marked);
        return marked;
    }

    /**
     * グローバル方式でスレッドの既読者一覧を取得する（F17.1 村掲示板グローバル方式）。
     *
     * <p>{@code threadId} のみで叩かれるため、スレッドの {@code scopeType} を逆引きして認可経路を分岐する。
     * VILLAGE は村可視性認可、ORG/TEAM/PERSONAL は所属認可。既読プライバシー（{@code read_tracking_mode}）と
     * {@code filter=unread}（ADMIN/村モデレーターのみ）の制御は既存スコープ経路と同等とする。</p>
     *
     * @param threadId スレッド ID
     * @param userId   操作ユーザーID
     * @param filter   フィルタ（{@code "unread"} 指定時は ADMIN / 村モデレーターのみ）
     * @return 既読ステータスレスポンスリスト（プライバシーモードにより空配列の場合あり）
     */
    public List<ReadStatusResponse> listReadUsersGlobal(Long threadId, Long userId, String filter) {
        BulletinThreadEntity thread = findThreadByIdOrThrow(threadId);
        if (isTournamentScope(thread.getScopeType())) {
            // F08.7.1: 閲覧=canView。checkMembership(500) に落ちないよう根治。
            ContactSpaceScopeType cs = toContactScope(thread.getScopeType());
            tournamentContactAccessService.checkView(cs, thread.getScopeId(), ContactSpaceKind.BULLETIN, userId);

            // モデレーター相当＝canPost（チーム代表/主催者）。確認漏れチェック（unread）と
            // プライバシー無視の readers 参照に使う（村実装の村モデレーターに対応）。
            boolean isModerator = isTournamentModerator(cs, thread.getScopeId(), userId);
            boolean unreadFilter = "unread".equalsIgnoreCase(filter);
            if (unreadFilter && !isModerator) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
            boolean canSeeReaders = thread.getReadTrackingMode() == ReadTrackingMode.INDIVIDUAL || isModerator;
            if (!canSeeReaders) {
                return List.of();
            }
            return bulletinMapper.toReadStatusResponseList(
                    readStatusRepository.findByThreadIdOrderByReadAtDesc(threadId));
        }
        if (thread.getScopeType() != ScopeType.VILLAGE) {
            return listReadUsers(thread.getScopeType(), thread.getScopeId(), threadId, userId, filter);
        }
        // VILLAGE: 閲覧認可
        villageBulletinAccessService.checkVillageBulletinViewAccess(thread.getScopeVillageId(), userId);

        boolean unreadFilter = "unread".equalsIgnoreCase(filter);
        // filter=unread（未読者一覧）は村モデレーターのみ（確認漏れチェック用）。非モデレーターは 403。
        boolean isModerator = isVillageModerator(thread.getScopeVillageId(), userId);
        if (unreadFilter && !isModerator) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        // 既読プライバシー: INDIVIDUAL（SHOW_READERS）または村モデレーターのみ readers をフル返却。
        boolean canSeeReaders = thread.getReadTrackingMode() == ReadTrackingMode.INDIVIDUAL || isModerator;
        if (!canSeeReaders) {
            return List.of();
        }
        List<BulletinReadStatusEntity> readStatuses = readStatusRepository.findByThreadIdOrderByReadAtDesc(threadId);
        return bulletinMapper.toReadStatusResponseList(readStatuses);
    }

    /**
     * 既読レコードを作成し、未既読の場合のみスレッドの既読数をインクリメントする。
     */
    private void markReadInternal(BulletinThreadEntity thread, Long userId) {
        if (readStatusRepository.existsByThreadIdAndUserId(thread.getId(), userId)) {
            return;
        }
        readStatusRepository.save(BulletinReadStatusEntity.builder()
                .threadId(thread.getId())
                .userId(userId)
                .build());
        thread.incrementReadCount();
        threadRepository.save(thread);
        log.info("既読マーク（グローバル）: threadId={}, userId={}", thread.getId(), userId);
    }

    /**
     * 村モデレーター判定（例外を投げずに boolean で返す）。{@code filter=unread} 可否判定に使う。
     */
    private boolean isVillageModerator(UUID villageId, Long userId) {
        try {
            villageBulletinAccessService.checkVillageBulletinModerator(villageId, userId);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    /**
     * 大会連絡モデレーター判定（例外を投げずに boolean で返す）。canPost（チーム代表/主催者）相当。
     * {@code filter=unread} 可否・プライバシー無視の readers 参照可否の判定に使う。
     */
    private boolean isTournamentModerator(ContactSpaceScopeType scopeType, Long scopeId, Long userId) {
        try {
            tournamentContactAccessService.checkPost(scopeType, scopeId, userId);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    /**
     * スレッドを ID のみで取得する（グローバル方式の逆引き）。存在しなければ 404。
     */
    private BulletinThreadEntity findThreadByIdOrThrow(Long threadId) {
        return threadRepository.findById(threadId)
                .orElseThrow(() -> new BusinessException(
                        com.mannschaft.app.bulletin.BulletinErrorCode.THREAD_NOT_FOUND));
    }

    /** スレッドが大会/ディビジョン連絡スペースか。 */
    private static boolean isTournamentScope(ScopeType scopeType) {
        return scopeType == ScopeType.TOURNAMENT || scopeType == ScopeType.TOURNAMENT_DIVISION;
    }

    /** bulletin {@link ScopeType} を連絡スペースの {@link ContactSpaceScopeType} に変換する。 */
    private static ContactSpaceScopeType toContactScope(ScopeType scopeType) {
        return scopeType == ScopeType.TOURNAMENT
                ? ContactSpaceScopeType.TOURNAMENT
                : ContactSpaceScopeType.TOURNAMENT_DIVISION;
    }
}
