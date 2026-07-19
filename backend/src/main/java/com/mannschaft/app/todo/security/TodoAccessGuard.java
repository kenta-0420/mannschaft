package com.mannschaft.app.todo.security;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * TODO アクセス認可ガード（認可根治戦役 Wave5・todo 硬化 PR-A / IDOR・BOLA 対策）。
 *
 * <p>背景: {@code TodoService} は {@code AccessControlService} を注入しておらず、
 * TEAM / ORGANIZATION スコープの TODO EP（一覧・詳細・更新・ステータス変更・担当者・削除・復元・
 * 一括変更）が <b>membership 認可を一切行っていなかった</b>。Controller が呼ぶ
 * {@code assertTodoScope} は「TODO が指定 scope に属するか」の束縛（IDOR 秘匿 404）だけを行い、
 * 「操作ユーザーが当該 scope のメンバーか」を検証しないため、TODO の内部 id や scopeId を推測できる
 * 任意の認証ユーザーが所属外チーム/組織の TODO を越境操作できる BOLA/IDOR が成立していた。</p>
 *
 * <p>本ガードは既存 {@link com.mannschaft.app.todo.security.ProjectAccessGuard} と同格・同居・同流儀で、
 * {@code TeamTodoController} / {@code OrgTodoController} の各 EP 入口から呼び出す。
 * {@code #2354} で {@code TodoCommentService#verifyScopeAndMembership} が確立した
 * 「scope 束縛（404 秘匿）＋ membership（403）」を昇格し、todo CRUD 全体に敷く。</p>
 *
 * <p>秘匿方針: 他 scope での ID 存在を漏らさないため、scope 不一致は 403 ではなく
 * {@link TodoErrorCode#TODO_NOT_FOUND}（{@code TODO_010} → 404）にまとめる。非メンバーは
 * {@code AccessControlService.checkMembership}（{@code COMMON_002} → 403）で弾く。</p>
 */
@Component
@RequiredArgsConstructor
public class TodoAccessGuard {

    private final TodoRepository todoRepository;
    private final AccessControlService accessControlService;

    /**
     * スコープ級の membership のみを検証する（todoId を持たない EP 用）。
     *
     * <p>一覧（listTodos）・ガント（getGanttTodos）・作成（createTodo）・一括変更（bulkChangeStatus）など、
     * 対象が特定の TODO ではなく scope 全体に及ぶ EP で使用する。非メンバーは 403（{@code COMMON_002}）。</p>
     *
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   内部スコープ ID（内部 teamId / orgId）
     * @param userId    操作ユーザー ID
     */
    public void requireScopeMember(TodoScopeType scopeType, Long scopeId, Long userId) {
        accessControlService.checkMembership(userId, scopeId, scopeType.name());
    }

    /**
     * 対象 TODO が path scope に属することを束縛し、操作ユーザーが当該 scope のメンバーであることを
     * 検証する（{@code #2354} {@code TodoCommentService#verifyScopeAndMembership} の昇格）。
     *
     * <p>検証順:</p>
     * <ol>
     *   <li><b>存在＋scope 束縛</b>: {@code findByIdAndDeletedAtIsNull} で TODO を取得し、
     *       {@code scopeType}/{@code scopeId} が path と一致しなければ
     *       {@link TodoErrorCode#TODO_NOT_FOUND}（404 秘匿）。</li>
     *   <li><b>membership 認可</b>: 非メンバーを 403（{@code COMMON_002}）にする。</li>
     * </ol>
     *
     * @param todoId    Todo ID
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   内部スコープ ID
     * @param userId    操作ユーザー ID
     */
    public void verifyScopeAndMembership(Long todoId, TodoScopeType scopeType, Long scopeId, Long userId) {
        TodoEntity todo = todoRepository.findByIdAndDeletedAtIsNull(todoId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.TODO_NOT_FOUND));
        if (todo.getScopeType() != scopeType
                || !Objects.equals(todo.getScopeId(), scopeId)) {
            // IDOR 秘匿: 他 scope の TODO id を推測して叩くケースを 404 にまとめる。
            throw new BusinessException(TodoErrorCode.TODO_NOT_FOUND);
        }
        if (scopeType == TodoScopeType.PERSONAL) {
            // PERSONAL スコープは membership 概念を持たない（AccessControlService に PERSONAL を渡すと 500。
            // project_scopetype_cross_domain_personal_mismatch）。所有権＝scopeId(=userId) 一致で認可し、
            // 上の scope 束縛が既に「todo.scopeId == scopeId」を保証しているため、呼び出し元が scopeId=userId を
            // 渡す限り所有者本人であることは担保済み。念のため明示照合して越境（scopeId≠userId 呼び出し）も 404 に落とす。
            if (!Objects.equals(scopeId, userId)) {
                throw new BusinessException(TodoErrorCode.TODO_NOT_FOUND);
            }
            return;
        }
        accessControlService.checkMembership(userId, scopeId, scopeType.name());
    }

    /**
     * 対象 TODO が path scope に属することを束縛し、操作ユーザーが <b>作成者本人または当該 scope の
     * ADMIN/DEPUTY_ADMIN</b> であることを検証する（削除・復元 EP 用・マスター御裁可）。
     *
     * <p>削除（deleteTodo）は活性 TODO、復元（restoreTodo）は論理削除済み TODO を対象とするため、
     * 両者を 1 メソッドで扱えるよう {@code findById}（削除状態非依存）で取得する。scope 不一致・不存在は
     * {@link TodoErrorCode#TODO_NOT_FOUND}（404 秘匿）。作成者でなければ
     * {@code checkAdminOrAbove}（非該当は {@code COMMON_002} → 403）。</p>
     *
     * @param todoId    Todo ID
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   内部スコープ ID
     * @param userId    操作ユーザー ID
     */
    public void verifyScopeAndOwnerOrAdmin(Long todoId, TodoScopeType scopeType, Long scopeId, Long userId) {
        TodoEntity todo = todoRepository.findById(todoId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.TODO_NOT_FOUND));
        if (todo.getScopeType() != scopeType
                || !Objects.equals(todo.getScopeId(), scopeId)) {
            // IDOR 秘匿: 他 scope の TODO id を推測して叩くケースを 404 にまとめる。
            throw new BusinessException(TodoErrorCode.TODO_NOT_FOUND);
        }
        if (!Objects.equals(todo.getCreatedBy(), userId)) {
            // 作成者以外は ADMIN/DEPUTY_ADMIN のみ許可（非該当は 403）。
            accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());
        }
    }
}
