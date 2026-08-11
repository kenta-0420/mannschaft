package com.mannschaft.app.schedule.authz;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.schedule.ScheduleKeepErrorCode;
import com.mannschaft.app.schedule.ScheduleKeepScopeType;
import com.mannschaft.app.schedule.entity.ScheduleKeepEntity;
import com.mannschaft.app.schedule.repository.ScheduleKeepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * キープ（日付未定の予定）の認可ゲート（F03.17 §4.6）。
 *
 * <p><b>deny-by-default</b>（{@code docs/security/README.md}）。キープを扱うすべての公開入口は
 * 本ゲートを最初に通し、返ってきた {@link ScheduleKeepEntity} だけを業務処理の対象にする。
 * ゲートを通さずに Repository を直接引く経路を作ってはならない。</p>
 *
 * <h2>本ゲートが同時に成立させる 3 つの契約</h2>
 * <ol>
 *   <li><b>未認証は 401</b> — {@code viewerUserId} が無ければ {@link CommonErrorCode#COMMON_000}。</li>
 *   <li><b>スコープ一致（IDOR 防御・§4.6.3）</b> — 検索は必ず「パスのスコープ込み」の finder
 *       （{@code findByIdAndTeamId} 等）で行う。他チームのキープ・組織キープ・個人キープを
 *       チームのパスから引いても不在になるため、比較忘れによる IDOR が原理的に発生しない。</li>
 *   <li><b>存在秘匿</b> — 不在・スコープ不一致・非メンバー・応援者（SUPPORTER）・ゲストは
 *       すべて {@link ScheduleKeepErrorCode#KEEP_NOT_FOUND}（<b>404</b>）に畳む。403 だと
 *       「そのチームにそのキープがある」ことが漏れる。</li>
 * </ol>
 *
 * <h2>操作権限の非対称（§2.1.1）</h2>
 * <p>「前に進める操作は開放し、書き換え・巻き戻しは限定する」という非対称を意図的に採る。</p>
 * <ul>
 *   <li>閲覧・作成・<b>変換（convert）</b> → MEMBER 以上の全員（{@link #requireConvertible}）。
 *       言い出しっぺが不在でも、話がまとまったら誰かが日程を入れられることが本機能の価値である。</li>
 *   <li>編集（PATCH）・削除（DELETE）・revert・archive・restore →
 *       <b>作成者本人＋当該スコープ ADMIN のみ</b>（{@link #requireEditable}）。
 *       違反は {@link ScheduleKeepErrorCode#KEEP_FORBIDDEN}（<b>403</b>）。
 *       ここは閲覧できることが前提の操作であり、存在はすでに開示されているため 403 でよい。</li>
 * </ul>
 *
 * <p>可視性判定は独自述語を書かず、F00 の {@link ContentVisibilityChecker} へ委譲する
 * （{@code ReferenceType.SCHEDULE_KEEP} → {@code ScheduleKeepVisibilityResolver}）。
 * 応援者・ゲストの遮断はそこで {@code StandardVisibility.MEMBERS_AND_ABOVE} により行われる。</p>
 *
 * <p>設計: {@code docs/features/F03.17_schedule_keep.md} §4.6 / §2.1.1</p>
 */
@Component
@RequiredArgsConstructor
public class ScheduleKeepAccessGuard {

    /** キープの閲覧・作成・変換に必要なロール閾値（応援者・ゲストを除外する）。 */
    private static final String REQUIRED_ROLE_MEMBER = "MEMBER";

    private final ScheduleKeepRepository scheduleKeepRepository;
    private final ContentVisibilityChecker contentVisibilityChecker;
    private final AccessControlService accessControlService;

    /**
     * 当該スコープでキープを扱えるか（閲覧・作成の入口）を検証する。
     *
     * <p>個々のキープを伴わない操作（一覧取得・新規作成・reorder の入口）で用いる。
     * チーム／組織スコープは MEMBER 以上、個人スコープは本人であることを要求し、
     * 満たさなければ 404 でスコープの存在ごと秘匿する。</p>
     *
     * @param scope        パスが指すスコープ
     * @param viewerUserId 実行者の users.id
     * @throws BusinessException 未認証なら 401、権限が無ければ 404
     */
    public void requireScopeAccess(ScheduleKeepScope scope, Long viewerUserId) {
        Objects.requireNonNull(scope, "scope must not be null");
        requireAuthenticated(viewerUserId);

        if (scope.type() == ScheduleKeepScopeType.PERSONAL) {
            // 個人スコープは他人の領域を覗けない（scopeId は本人でなければならない）。
            if (!Objects.equals(scope.id(), viewerUserId)) {
                throw notFound();
            }
            return;
        }

        // 応援者（SUPPORTER）・ゲストはここで落ちる（isMember ではなく MEMBER 閾値で判定する）。
        boolean allowed = accessControlService.hasRoleOrAbove(
                viewerUserId, scope.id(), scope.type().membershipScopeType(), REQUIRED_ROLE_MEMBER);
        if (!allowed) {
            throw notFound();
        }
    }

    /**
     * 閲覧可能なキープを取得する（GET / by-schedule 等の参照系、および操作系の共通前段）。
     *
     * @param scope        パスが指すスコープ
     * @param keepId       キープの UUIDv7
     * @param viewerUserId 実行者の users.id
     * @return スコープが一致し、かつ閲覧可能なキープ
     * @throws BusinessException 未認証なら 401、それ以外の不可なら 404
     */
    public ScheduleKeepEntity requireViewable(
            ScheduleKeepScope scope, UUID keepId, Long viewerUserId) {
        Objects.requireNonNull(scope, "scope must not be null");
        requireAuthenticated(viewerUserId);
        if (keepId == null) {
            throw notFound();
        }

        // ① スコープ一致（IDOR 防御）: 検索クエリ自体にスコープを織り込む。
        ScheduleKeepEntity keep = findWithinScope(scope, keepId, viewerUserId)
                .orElseThrow(ScheduleKeepAccessGuard::notFound);

        // ② 可視性: F00 正準へ委譲（応援者・ゲストはここで落ちる）。
        if (!contentVisibilityChecker.canViewUuid(ReferenceType.SCHEDULE_KEEP, keepId, viewerUserId)) {
            throw notFound();
        }
        return keep;
    }

    /**
     * 変換（convert）が可能なキープを取得する（§2.1.1 の非対称・MEMBER 全員に開放）。
     *
     * <p>閲覧できる者はそのまま変換できる。編集系と<b>意図的に</b>別メソッドにしてあるのは、
     * 呼び出し側が誤って編集系ゲートを流用し「MEMBER が変換できない」退行を起こさないためである。
     * 変換時は作成者へ必ず通知すること（§6.1。「自分の知らないうちに予定になっていた」を作らない）。</p>
     *
     * @param scope        パスが指すスコープ
     * @param keepId       キープの UUIDv7
     * @param viewerUserId 実行者の users.id
     * @return 変換対象のキープ
     * @throws BusinessException 未認証なら 401、それ以外の不可なら 404
     */
    public ScheduleKeepEntity requireConvertible(
            ScheduleKeepScope scope, UUID keepId, Long viewerUserId) {
        return requireViewable(scope, keepId, viewerUserId);
    }

    /**
     * 変換先の予定 ID から由来キープを逆引きする（{@code GET .../by-schedule/{scheduleId}}・§4.5.1）。
     *
     * <p>逆引きも例外なく本ゲートを通す。{@code scheduleId} は<b>クライアントが自由に指定できる
     * 連番の BIGINT</b> であり、スコープを織り込まずに {@code converted_schedule_id} だけで引くと、
     * 総当たりで他チームのキープの<b>存在とタイトルが読み出せる</b>（キープ ID が UUIDv7 で
     * 推測困難であることに頼れなくなる＝逆引きが IDOR の抜け道になる）。</p>
     *
     * <p>該当が複数ある場合は {@code created_at} が最新の1件を返す（§4.5.1 の決定則）。
     * {@code revert} → 再 {@code convert} の履歴で同じ予定 ID を指すキープが複数生まれうるため。</p>
     *
     * @param scope        パスが指すスコープ
     * @param scheduleId   変換先 {@code schedules.id}
     * @param viewerUserId 実行者の users.id
     * @return 由来キープ
     * @throws BusinessException 未認証なら 401、キープ由来でない／閲覧不可なら 404
     */
    public ScheduleKeepEntity requireViewableByConvertedSchedule(
            ScheduleKeepScope scope, Long scheduleId, Long viewerUserId) {
        Objects.requireNonNull(scope, "scope must not be null");
        requireAuthenticated(viewerUserId);
        if (scheduleId == null) {
            throw notFound();
        }

        // ① スコープ一致（IDOR 防御）: 逆引きクエリ自体にスコープを織り込む。
        ScheduleKeepEntity keep = findByConvertedScheduleWithinScope(scope, scheduleId, viewerUserId)
                .orElseThrow(ScheduleKeepAccessGuard::notFound);

        // ② 可視性: 参照系の正準（requireViewable）へ合流させる。ここで独自判定を書くと、
        //    キープ ID 経由では見えないものが予定 ID 経由でだけ見える非対称が生まれる。
        return requireViewable(scope, keep.getId(), viewerUserId);
    }

    /**
     * 編集系（PATCH / DELETE / revert / archive / restore）が可能なキープを取得する。
     *
     * <p>閲覧可能であることを前提に、さらに<b>作成者本人</b>または<b>当該スコープの ADMIN 以上</b>で
     * あることを要求する。閲覧はできるが権限が無い場合は、存在がすでに開示されているため
     * {@link ScheduleKeepErrorCode#KEEP_FORBIDDEN}（403）を返す。</p>
     *
     * @param scope        パスが指すスコープ
     * @param keepId       キープの UUIDv7
     * @param viewerUserId 実行者の users.id
     * @return 操作対象のキープ
     * @throws BusinessException 未認証なら 401、不在・スコープ不一致・不可視なら 404、権限不足なら 403
     */
    public ScheduleKeepEntity requireEditable(
            ScheduleKeepScope scope, UUID keepId, Long viewerUserId) {
        ScheduleKeepEntity keep = requireViewable(scope, keepId, viewerUserId);

        if (Objects.equals(keep.getCreatedBy(), viewerUserId)) {
            return keep;
        }
        if (scope.type() != ScheduleKeepScopeType.PERSONAL
                && accessControlService.isAdminOrAbove(
                        viewerUserId, scope.id(), scope.type().membershipScopeType())) {
            return keep;
        }
        throw new BusinessException(ScheduleKeepErrorCode.KEEP_FORBIDDEN);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * パスのスコープを織り込んだ finder を選択する。
     *
     * <p>個人スコープは {@code viewerUserId} ではなく <b>スコープ ID</b> で引き、
     * その前に「スコープ ID＝実行者」であることを検証する。
     * {@code getCurrentUserId()} の戻り値をそのままスコープ ID として使い回す実装は
     * 常に誤りであり、検証を経ない流用は IDOR の温床になるためである。</p>
     */
    private Optional<ScheduleKeepEntity> findWithinScope(
            ScheduleKeepScope scope, UUID keepId, Long viewerUserId) {
        return switch (scope.type()) {
            case TEAM -> scheduleKeepRepository.findByIdAndTeamId(keepId, scope.id());
            case ORGANIZATION -> scheduleKeepRepository.findByIdAndOrganizationId(keepId, scope.id());
            case PERSONAL -> Objects.equals(scope.id(), viewerUserId)
                    ? scheduleKeepRepository.findByIdAndUserId(keepId, scope.id())
                    : Optional.empty();
        };
    }

    /**
     * 逆引き（{@code converted_schedule_id}）もスコープを織り込んだ finder を選択する。
     *
     * <p>{@link #findWithinScope} と同じ規律。個人スコープは「スコープ ID＝実行者」を先に検証する。</p>
     */
    private Optional<ScheduleKeepEntity> findByConvertedScheduleWithinScope(
            ScheduleKeepScope scope, Long scheduleId, Long viewerUserId) {
        List<ScheduleKeepEntity> candidates = switch (scope.type()) {
            case TEAM -> scheduleKeepRepository
                    .findByTeamIdAndConvertedScheduleIdOrderByCreatedAtDescIdDesc(scope.id(), scheduleId);
            case ORGANIZATION -> scheduleKeepRepository
                    .findByOrganizationIdAndConvertedScheduleIdOrderByCreatedAtDescIdDesc(scope.id(), scheduleId);
            case PERSONAL -> Objects.equals(scope.id(), viewerUserId)
                    ? scheduleKeepRepository
                            .findByUserIdAndConvertedScheduleIdOrderByCreatedAtDescIdDesc(scope.id(), scheduleId)
                    : List.of();
        };
        return candidates.stream().findFirst();
    }

    /** 未認証は 401（{@code COMMON_000}）。認可判定より前に必ず通す。 */
    private void requireAuthenticated(Long viewerUserId) {
        if (viewerUserId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }
    }

    /** 不在・スコープ不一致・非メンバー・応援者をすべて同じ 404 に畳む（存在秘匿）。 */
    private static BusinessException notFound() {
        return new BusinessException(ScheduleKeepErrorCode.KEEP_NOT_FOUND);
    }
}
