package com.mannschaft.app.jobmatching.policy;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobCheckInType;
import com.mannschaft.app.jobmatching.enums.JobContractStatus;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.role.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * F13.1 求人マッチング機能の認可ポリシー。
 *
 * <p>Phase 13.1.1 MVP で必要となる求人・応募・契約に対する操作権限判定を一元化する。</p>
 *
 * <p>判定は既存の {@link AccessControlService}（メンバーシップ・ロール）と
 * {@link RoleService#hasPermission(Long, Long, String, String)}
 * （権限文字列コード: {@link #PERMISSION_MANAGE_JOBS}）を組み合わせる。</p>
 *
 * <p>MVP 公開範囲は {@link VisibilityScope#TEAM_MEMBERS} および
 * {@link VisibilityScope#TEAM_MEMBERS_SUPPORTERS} のみ扱う。それ以外は
 * サービス層で {@code JOB_VIS_NOT_SUPPORTED} を返す責務となる。</p>
 */
@Component
@RequiredArgsConstructor
public class JobPolicy {

    /**
     * 求人管理権限を表すパーミッション名（permissions テーブル name カラムの値）。
     * ADMIN は既定で保有、DEPUTY_ADMIN は権限グループ経由で付与される想定。
     */
    public static final String PERMISSION_MANAGE_JOBS = "jobs.manage";

    private static final String SCOPE_TEAM = "TEAM";
    private static final String ROLE_SUPPORTER = "SUPPORTER";

    private final AccessControlService accessControlService;
    private final RoleService roleService;

    /**
     * 求人作成権限。
     * <p>対象チームの ADMIN、または {@link #PERMISSION_MANAGE_JOBS} を保有するユーザー
     * （典型的には DEPUTY_ADMIN への権限グループ付与）に許可する。</p>
     *
     * @param userId 操作ユーザー
     * @param teamId 投稿先チーム
     * @return 許可される場合 true
     */
    public boolean canCreatePosting(Long userId, Long teamId) {
        if (userId == null || teamId == null) {
            return false;
        }
        if (accessControlService.isAdmin(userId, teamId, SCOPE_TEAM)) {
            return true;
        }
        return roleService.hasPermission(userId, teamId, SCOPE_TEAM, PERMISSION_MANAGE_JOBS);
    }

    /**
     * 求人編集権限。
     * <p>投稿者本人、または対象チームの ADMIN に許可する。</p>
     *
     * @param userId  操作ユーザー
     * @param posting 対象求人
     * @return 許可される場合 true
     */
    public boolean canEditPosting(Long userId, JobPostingEntity posting) {
        if (userId == null || posting == null) {
            return false;
        }
        if (userId.equals(posting.getCreatedByUserId())) {
            return true;
        }
        return accessControlService.isAdmin(userId, posting.getTeamId(), SCOPE_TEAM);
    }

    /**
     * 応募権限。
     * <p>自分の投稿した求人への応募は不可。公開範囲に応じて以下を判定する:</p>
     * <ul>
     *   <li>{@link VisibilityScope#TEAM_MEMBERS}: チームの任意のメンバー（SUPPORTER 含む）。
     *       ただし SUPPORTER は除外する設計方針。</li>
     *   <li>{@link VisibilityScope#TEAM_MEMBERS_SUPPORTERS}: メンバーに加え SUPPORTER も許可。</li>
     *   <li>それ以外の公開範囲: MVP 未対応のため false を返す（サービス層で {@code JOB_VIS_NOT_SUPPORTED} 相当のエラー送出）。</li>
     * </ul>
     *
     * @param userId  操作ユーザー
     * @param posting 対象求人
     * @return 許可される場合 true
     */
    public boolean canApply(Long userId, JobPostingEntity posting) {
        if (userId == null || posting == null) {
            return false;
        }
        if (userId.equals(posting.getCreatedByUserId())) {
            return false;
        }
        Long teamId = posting.getTeamId();
        VisibilityScope scope = posting.getVisibilityScope();
        if (scope == null) {
            return false;
        }
        return switch (scope) {
            case TEAM_MEMBERS -> isNonSupporterMember(userId, teamId);
            case TEAM_MEMBERS_SUPPORTERS -> accessControlService.isMember(userId, teamId, SCOPE_TEAM);
            default -> false;
        };
    }

    /**
     * 応募採否（採用/不採用）権限。
     * <p>求人投稿先チームの ADMIN、または {@link #PERMISSION_MANAGE_JOBS} 保有者に許可する。</p>
     *
     * @param userId  操作ユーザー
     * @param posting 対象求人
     * @return 許可される場合 true
     */
    public boolean canDecideApplication(Long userId, JobPostingEntity posting) {
        if (userId == null || posting == null) {
            return false;
        }
        Long teamId = posting.getTeamId();
        if (accessControlService.isAdmin(userId, teamId, SCOPE_TEAM)) {
            return true;
        }
        return roleService.hasPermission(userId, teamId, SCOPE_TEAM, PERMISSION_MANAGE_JOBS);
    }

    /**
     * 完了報告権限。契約の Worker 本人のみ許可する。
     *
     * @param userId   操作ユーザー
     * @param contract 対象契約
     * @return 許可される場合 true
     */
    public boolean canReportCompletion(Long userId, JobContractEntity contract) {
        if (userId == null || contract == null) {
            return false;
        }
        return userId.equals(contract.getWorkerUserId());
    }

    /**
     * 完了承認（差し戻しを含む判断）権限。契約の Requester 本人のみ許可する。
     *
     * @param userId   操作ユーザー
     * @param contract 対象契約
     * @return 許可される場合 true
     */
    public boolean canApproveCompletion(Long userId, JobContractEntity contract) {
        if (userId == null || contract == null) {
            return false;
        }
        return userId.equals(contract.getRequesterUserId());
    }

    // ------------------------------------------------------------------
    // Phase 13.1.2: QR チェックイン／アウト
    // ------------------------------------------------------------------

    /**
     * QR トークン発行権限（Requester 本人のみ許可）。
     *
     * <p>Requester 側デバイスでのみ QR コードを画面表示するため、発行できるのは
     * Requester 本人に限定する。SYSTEM_ADMIN による代理発行は本メソッドのスコープ外で、
     * 別途管理画面経由の例外ルートで扱う（設計書 §5.4 備考）。</p>
     *
     * @param contract 対象契約
     * @param userId   発行要求ユーザー
     * @return 許可される場合 true
     */
    public boolean canIssueQrToken(JobContractEntity contract, Long userId) {
        if (contract == null || userId == null) {
            return false;
        }
        return Objects.equals(contract.getRequesterUserId(), userId);
    }

    /**
     * QR チェックイン／アウト記録権限。
     *
     * <p>判定条件（設計書 §2.3.1 / §5.4）:</p>
     * <ul>
     *   <li>操作ユーザーが契約の Worker 本人であること</li>
     *   <li>{@code type=IN} の場合、契約ステータスが {@link JobContractStatus#MATCHED} または
     *       {@link JobContractStatus#CHECKED_IN}（冪等性担保用、実際の再送は DB UNIQUE で弾かれる）</li>
     *   <li>{@code type=OUT} の場合、契約ステータスが {@link JobContractStatus#IN_PROGRESS} または
     *       {@link JobContractStatus#CHECKED_OUT}（同上）</li>
     * </ul>
     *
     * <p>本判定は認可層のゲートであり、実際の重複検知は Service 層で
     * {@code JobCheckInRepository.findByJobContractIdAndType} により行う。</p>
     *
     * @param contract 対象契約
     * @param userId   操作ユーザー（Worker）
     * @param type     IN / OUT
     * @return 許可される場合 true
     */
    public boolean canRecordCheckIn(JobContractEntity contract, Long userId, JobCheckInType type) {
        if (contract == null || userId == null || type == null) {
            return false;
        }
        if (!Objects.equals(contract.getWorkerUserId(), userId)) {
            return false;
        }
        JobContractStatus status = contract.getStatus();
        if (status == null) {
            return false;
        }
        return switch (type) {
            case IN -> status == JobContractStatus.MATCHED
                    || status == JobContractStatus.CHECKED_IN;
            case OUT -> status == JobContractStatus.IN_PROGRESS
                    || status == JobContractStatus.CHECKED_OUT;
        };
    }

    // ------------------------------------------------------------------
    // 内部ヘルパー
    // ------------------------------------------------------------------

    /**
     * SUPPORTER 以外のチームメンバーかを判定する。
     * {@link VisibilityScope#TEAM_MEMBERS}（サポーター除外）用。
     */
    private boolean isNonSupporterMember(Long userId, Long teamId) {
        if (!accessControlService.isMember(userId, teamId, SCOPE_TEAM)) {
            return false;
        }
        String roleName = accessControlService.getRoleName(userId, teamId, SCOPE_TEAM);
        return !ROLE_SUPPORTER.equals(roleName);
    }
}
