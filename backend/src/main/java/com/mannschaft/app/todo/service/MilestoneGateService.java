package com.mannschaft.app.todo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.entity.ProjectMilestoneEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.event.MilestoneUnlockedEvent;
import com.mannschaft.app.todo.repository.ProjectMilestoneRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import com.mannschaft.app.todo.service.internal.MilestoneGateEvaluator;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * マイルストーンゲート（関所）機能の中核サービス（F02.7）。
 *
 * <p>責務:
 * <ul>
 *   <li>TODO ステータス変更時の進捗率再計算・自動完了判定・後続アンロック</li>
 *   <li>マイルストーン手動完了時の後続アンロック</li>
 *   <li>ADMIN による強制アンロック</li>
 *   <li>既存プロジェクトのゲート初期化（移行用）</li>
 *   <li>sort_order 変更・マイルストーン削除後のロック連鎖再構築</li>
 * </ul>
 * </p>
 *
 * <p>競合制御: {@link jakarta.persistence.Version} による楽観的ロック + 50ms 後のリトライ1回。
 * 2回目も失敗する場合は {@link IllegalStateException} を送出し上位で 409 Conflict を返す。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MilestoneGateService {

    /** 楽観的ロック競合時のリトライ待機時間（ミリ秒） */
    private static final long OPTIMISTIC_RETRY_SLEEP_MS = 50L;

    private final ProjectMilestoneRepository milestoneRepository;
    private final TodoRepository todoRepository;
    private final MilestoneGateEvaluator evaluator;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogService auditLogService;

    /**
     * TODO のステータス変更を受けて所属マイルストーンの進捗率を更新し、AUTO モードの
     * 全完了条件を満たす場合は自動完了＋後続アンロックを実行する。
     *
     * @param todoId    ステータス変更された TODO ID
     * @param newStatus 新しいステータス
     */
    @Transactional
    public void evaluateOnTodoStatusChanged(Long todoId, TodoStatus newStatus) {
        TodoEntity todo = todoRepository.findByIdAndDeletedAtIsNull(todoId).orElse(null);
        if (todo == null || todo.getMilestoneId() == null) {
            return;
        }

        Long milestoneId = todo.getMilestoneId();
        runWithOptimisticRetry(() -> {
            ProjectMilestoneEntity milestone = milestoneRepository.findById(milestoneId).orElse(null);
            if (milestone == null) {
                return null;
            }

            long total = todoRepository.countByMilestoneIdAndDeletedAtIsNull(milestoneId);
            long completed = todoRepository.countByMilestoneIdAndStatusAndDeletedAtIsNull(
                    milestoneId, TodoStatus.COMPLETED);

            milestone.updateProgressRate(total, completed);
            milestoneRepository.save(milestone);

            if (evaluator.shouldAutoComplete(milestone, total, completed)) {
                milestone.complete();
                milestoneRepository.save(milestone);
                log.info("マイルストーン自動完了: milestoneId={}, projectId={}, total={}, completed={}",
                        milestone.getId(), milestone.getProjectId(), total, completed);
                // 監査ログ: MILESTONE_AUTO_COMPLETED
                auditLogService.record(
                        "MILESTONE_AUTO_COMPLETED",
                        null, null, null, null, null, null, null,
                        String.format("{\"projectId\":%d,\"milestoneId\":%d,\"total\":%d,\"completed\":%d}",
                                milestone.getProjectId(), milestone.getId(), total, completed));
                unlockSuccessorsInternal(milestone.getId(), milestone.getProjectId());
            }
            return null;
        });
        // newStatus 引数は現状ゲート評価ロジック本体では使用していないが、
        // 将来的な拡張（例: COMPLETED 遷移時のみ通知抑制）のためシグネチャに残す
        log.debug("ゲート評価完了: todoId={}, newStatus={}", todoId, newStatus);
    }

    /**
     * 完了したマイルストーンの後続マイルストーン（locked_by_milestone_id = completedMilestoneId）を
     * 一括アンロックする。
     *
     * @param completedMilestoneId 完了したマイルストーン ID
     */
    @Transactional
    public void unlockSuccessors(Long completedMilestoneId) {
        ProjectMilestoneEntity milestone = milestoneRepository.findById(completedMilestoneId).orElse(null);
        if (milestone == null) {
            log.warn("unlockSuccessors: マイルストーン見当たらず milestoneId={}", completedMilestoneId);
            return;
        }
        unlockSuccessorsInternal(completedMilestoneId, milestone.getProjectId());
    }

    /**
     * ADMIN による強制アンロック。force_unlocked = TRUE として冪等性を保証し、
     * 前マイルストーンが未完了に戻っても再ロックしない。
     *
     * @param milestoneId アンロック対象マイルストーン ID
     * @param userId      実行ユーザー ID（監査ログ用）
     * @param reason      アンロック理由（100 文字以内。監査ログに記録）
     */
    @Transactional
    public void forceUnlock(Long milestoneId, Long userId, String reason) {
        runWithOptimisticRetry(() -> {
            ProjectMilestoneEntity milestone = milestoneRepository.findById(milestoneId)
                    .orElseThrow(() -> new IllegalArgumentException("マイルストーンが見つかりません: " + milestoneId));

            milestone.forceUnlock();
            milestoneRepository.save(milestone);

            // 配下 TODO のロック解除
            List<TodoEntity> todos = todoRepository.findByMilestoneIdAndDeletedAtIsNull(milestoneId);
            for (TodoEntity t : todos) {
                if (Boolean.TRUE.equals(t.getMilestoneLocked())) {
                    t.unlockByMilestone();
                    todoRepository.save(t);
                }
            }

            log.info("マイルストーン強制アンロック: milestoneId={}, projectId={}, userId={}, reason={}",
                    milestoneId, milestone.getProjectId(), userId, reason);

            // 監査ログ: MILESTONE_FORCE_UNLOCKED（reason 必須、F10.3 保持期間に従って自動削除）
            auditLogService.record(
                    "MILESTONE_FORCE_UNLOCKED",
                    userId, null, null, null, null, null, null,
                    String.format("{\"projectId\":%d,\"milestoneId\":%d,\"reason\":\"%s\"}",
                            milestone.getProjectId(), milestoneId, escapeJson(reason)));

            eventPublisher.publishEvent(new MilestoneUnlockedEvent(
                    milestone.getProjectId(), milestoneId, null, true));
            return null;
        });
    }

    /**
     * 指定マイルストーン以降のゲートを初期化する（既存プロジェクト移行用）。冪等。
     *
     * <p>指定マイルストーン（およびそれ以降）について sort_order 昇順で連鎖を評価し、
     * 前マイルストーンが未完了ならロック・完了済みならアンロック。force_unlocked = TRUE は保持。</p>
     *
     * @param milestoneId 初期化開始マイルストーン ID
     */
    @Transactional
    public void initializeGate(Long milestoneId) {
        ProjectMilestoneEntity target = milestoneRepository.findById(milestoneId)
                .orElseThrow(() -> new IllegalArgumentException("マイルストーンが見つかりません: " + milestoneId));

        List<ProjectMilestoneEntity> all = milestoneRepository
                .findByProjectIdOrderBySortOrderAsc(target.getProjectId());

        ChainRebuildResult result = applyGateChain(all, target.getSortOrder(), target.getProjectId());
        log.info("ゲート初期化完了: projectId={}, startSortOrder={}, locked={}, unlocked={}",
                target.getProjectId(), target.getSortOrder(),
                result.lockedIds.size(), result.unlockedIds.size());

        // 監査ログ: 初期化で初回 LOCK された各マイルストーンに MILESTONE_LOCKED
        for (Long lockedId : result.lockedIds) {
            auditLogService.record(
                    "MILESTONE_LOCKED",
                    null, null, null, null, null, null, null,
                    String.format("{\"projectId\":%d,\"milestoneId\":%d,\"trigger\":\"INITIALIZE_GATE\"}",
                            target.getProjectId(), lockedId));
        }
        // 監査ログ: MILESTONE_CHAIN_REBUILT（影響を受けた全マイルストーン ID 一覧）
        List<Long> affected = new ArrayList<>(result.lockedIds);
        affected.addAll(result.unlockedIds);
        if (!affected.isEmpty()) {
            auditLogService.record(
                    "MILESTONE_CHAIN_REBUILT",
                    null, null, null, null, null, null, null,
                    String.format("{\"projectId\":%d,\"trigger\":\"INITIALIZE_GATE\",\"affectedMilestoneIds\":[%s]}",
                            target.getProjectId(), affected.stream().map(String::valueOf).collect(Collectors.joining(","))));
        }
    }

    /**
     * プロジェクト内のマイルストーンロック連鎖を再構築する（sort_order 変更・削除後用）。
     *
     * @param projectId プロジェクト ID
     */
    @Transactional
    public void rebuildChain(Long projectId) {
        List<ProjectMilestoneEntity> all = milestoneRepository
                .findByProjectIdOrderBySortOrderAsc(projectId);
        ChainRebuildResult result = applyGateChain(all, (short) 0, projectId);
        log.info("ゲート連鎖再構築完了: projectId={}, locked={}, unlocked={}",
                projectId, result.lockedIds.size(), result.unlockedIds.size());

        // 監査ログ: MILESTONE_CHAIN_REBUILT
        List<Long> affected = new ArrayList<>(result.lockedIds);
        affected.addAll(result.unlockedIds);
        if (!affected.isEmpty()) {
            auditLogService.record(
                    "MILESTONE_CHAIN_REBUILT",
                    null, null, null, null, null, null, null,
                    String.format("{\"projectId\":%d,\"trigger\":\"REBUILD_CHAIN\",\"affectedMilestoneIds\":[%s]}",
                            projectId, affected.stream().map(String::valueOf).collect(Collectors.joining(","))));
        }
    }

    // --- 内部処理 ---

    /**
     * ロック連鎖再評価の結果（監査ログ用の差分情報）。
     *
     * @param lockedIds   新たにロック状態になったマイルストーン ID 一覧
     * @param unlockedIds 新たにアンロック状態になったマイルストーン ID 一覧
     */
    private record ChainRebuildResult(List<Long> lockedIds, List<Long> unlockedIds) {
    }

    /**
     * マイルストーン連鎖をインデックス startSortOrder 以降について再評価し、DB と TODO に反映する。
     *
     * @return 変化があったマイルストーン ID の内訳
     */
    private ChainRebuildResult applyGateChain(List<ProjectMilestoneEntity> allSorted, short startSortOrder, Long projectId) {
        List<ProjectMilestoneEntity> sorted = new ArrayList<>(allSorted);
        sorted.sort(Comparator.comparing(ProjectMilestoneEntity::getSortOrder));

        List<MilestoneGateEvaluator.GateState> states = evaluator.evaluateChain(sorted);

        List<Long> locked = new ArrayList<>();
        List<Long> unlocked = new ArrayList<>();

        for (int i = 0; i < sorted.size(); i++) {
            ProjectMilestoneEntity m = sorted.get(i);
            if (m.getSortOrder() < startSortOrder) {
                continue;
            }
            MilestoneGateEvaluator.GateState state = states.get(i);

            boolean wasLocked = Boolean.TRUE.equals(m.getIsLocked());
            boolean shouldBeLocked = state.isLocked();

            // force_unlocked = TRUE は再ロックしない（evaluator 側で考慮済みだが念のためガード）
            if (Boolean.TRUE.equals(m.getForceUnlocked()) && shouldBeLocked) {
                continue;
            }

            if (shouldBeLocked && !wasLocked) {
                m.lockByMilestone(state.lockedByMilestoneId());
                milestoneRepository.save(m);
                syncTodosLockState(m.getId(), true);
                locked.add(m.getId());
            } else if (!shouldBeLocked && wasLocked) {
                m.unlock();
                milestoneRepository.save(m);
                syncTodosLockState(m.getId(), false);
                eventPublisher.publishEvent(new MilestoneUnlockedEvent(
                        projectId, m.getId(), state.lockedByMilestoneId(), false));
                unlocked.add(m.getId());
                // 監査ログ: MILESTONE_UNLOCKED（連鎖再構築による自動アンロック）
                auditLogService.record(
                        "MILESTONE_UNLOCKED",
                        null, null, null, null, null, null, null,
                        String.format("{\"projectId\":%d,\"milestoneId\":%d,\"triggeredByMilestoneId\":%s,\"trigger\":\"CHAIN_REBUILD\"}",
                                projectId, m.getId(),
                                state.lockedByMilestoneId() == null ? "null" : state.lockedByMilestoneId().toString()));
            }
        }
        return new ChainRebuildResult(locked, unlocked);
    }

    /**
     * 指定マイルストーン配下の TODO ロック状態を同期する。
     */
    private void syncTodosLockState(Long milestoneId, boolean locked) {
        List<TodoEntity> todos = todoRepository.findByMilestoneIdAndDeletedAtIsNull(milestoneId);
        for (TodoEntity t : todos) {
            if (locked && !Boolean.TRUE.equals(t.getMilestoneLocked())) {
                t.lockByMilestone();
                todoRepository.save(t);
            } else if (!locked && Boolean.TRUE.equals(t.getMilestoneLocked())) {
                t.unlockByMilestone();
                todoRepository.save(t);
            }
        }
    }

    /**
     * 完了マイルストーン直下の後続群をアンロックする内部処理。
     */
    private void unlockSuccessorsInternal(Long completedMilestoneId, Long projectId) {
        List<ProjectMilestoneEntity> successors = milestoneRepository
                .findByLockedByMilestoneId(completedMilestoneId);

        for (ProjectMilestoneEntity n : successors) {
            if (Boolean.TRUE.equals(n.getForceUnlocked())) {
                // 既に強制アンロック済み → 状態維持
                continue;
            }
            n.unlock();
            milestoneRepository.save(n);

            syncTodosLockState(n.getId(), false);

            eventPublisher.publishEvent(new MilestoneUnlockedEvent(
                    projectId, n.getId(), completedMilestoneId, false));

            log.info("マイルストーン自動アンロック: milestoneId={}, triggeredBy={}, projectId={}",
                    n.getId(), completedMilestoneId, projectId);

            // 監査ログ: MILESTONE_UNLOCKED（前マイルストーン達成による自動アンロック）
            auditLogService.record(
                    "MILESTONE_UNLOCKED",
                    null, null, null, null, null, null, null,
                    String.format("{\"projectId\":%d,\"milestoneId\":%d,\"triggeredByMilestoneId\":%d,\"trigger\":\"PREDECESSOR_COMPLETED\"}",
                            projectId, n.getId(), completedMilestoneId));
        }
    }

    /**
     * 楽観的ロック例外を検知した際に一度だけリトライするヘルパー。
     *
     * @param action 実行アクション
     */
    private void runWithOptimisticRetry(Runnable0 action) {
        try {
            action.run();
        } catch (OptimisticLockException | ObjectOptimisticLockingFailureException e) {
            log.warn("楽観的ロック競合を検知。50ms 待機後にリトライします", e);
            try {
                Thread.sleep(OPTIMISTIC_RETRY_SLEEP_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("ゲート更新中に割り込みを検知", ie);
            }
            try {
                action.run();
            } catch (OptimisticLockException | ObjectOptimisticLockingFailureException retryEx) {
                log.error("ゲート更新の楽観的ロックリトライ失敗", retryEx);
                throw new IllegalStateException(
                        "マイルストーン状態の更新が他処理と競合しました。画面を再読み込みしてください。", retryEx);
            }
        }
    }

    /** 戻り値を持たず例外の伝播のみ受ける関数型インターフェース（void 向け）。 */
    @FunctionalInterface
    private interface Runnable0 {
        Object run();
    }

    /**
     * 監査ログ metadata JSON 文字列内に埋め込む文字列を JSON 仕様に沿ってエスケープする。
     * reason 等のユーザー入力を安全に metadata 格納するため。
     *
     * @param raw エスケープ対象文字列（null 許容）
     * @return JSON 文字列リテラル内に埋め込み可能な形式
     */
    private static String escapeJson(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
