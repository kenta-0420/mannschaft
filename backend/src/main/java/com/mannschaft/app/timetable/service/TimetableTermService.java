package com.mannschaft.app.timetable.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import com.mannschaft.app.timetable.TimetableErrorCode;
import com.mannschaft.app.timetable.entity.TimetableTermEntity;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableTermRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学期サービス。学期のCRUDおよびチーム・組織スコープの学期管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimetableTermService {

    private final TimetableTermRepository termRepository;
    private final TimetableRepository timetableRepository;
    private final AccessControlService accessControlService;
    private final TeamOrgMembershipQueryService teamOrgMembershipQueryService;
    /** 論理削除日時を取る壁時計クロック（引数なし now() は時刻の番人違反のため注入する）。 */
    @Qualifier("wallClock")
    private final Clock clock;

    /** 認可根治Wave2: F00.5 メンバーシップ・ロール判定のスコープ種別。 */
    private static final String SCOPE_TEAM = "TEAM";
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    /**
     * 組織の学期一覧を取得する。
     */
    public List<TimetableTermEntity> getOrganizationTerms(Long orgId, Long actorUserId) {
        accessControlService.checkMembership(actorUserId, orgId, SCOPE_ORGANIZATION);
        return termRepository.findByOrganizationIdOrderByAcademicYearDescSortOrder(orgId);
    }

    /**
     * チームの学期一覧を取得する。
     *
     * <p>{@code organizationId} が {@code null}（省略）のときは、チーム固有の学期だけを返す。
     * チームは組織に所属していなくても自分で学期を作り時間割を作れる（CMP-261105-1507 D4）。
     * 指定されたときは、チーム固有の学期と親組織の学期をマージし、チーム固有が優先される。</p>
     *
     * <p>★BOLA厳禁★: {@code organizationId} はクライアント供給の request param であり、
     * teamId のチームメンバーであっても無関係な組織の学期情報を読める余地があるため鵜呑みにしない。
     * {@link TeamOrgMembershipQueryService} でチームの実際の ACTIVE 所属組織一覧と照合し、
     * 一致しない場合は無関係組織の学期を返さず 404 とする（scope 混同防止）。</p>
     */
    public List<TimetableTermEntity> getTeamTerms(Long teamId, Long organizationId, Long actorUserId) {
        accessControlService.checkMembership(actorUserId, teamId, SCOPE_TEAM);

        List<TimetableTermEntity> teamTerms =
                termRepository.findByTeamIdOrderByAcademicYearDescSortOrder(teamId);
        if (organizationId == null) {
            return List.copyOf(teamTerms);
        }

        List<Long> activeOrgIds = teamOrgMembershipQueryService.findActiveOrganizationIds(teamId);
        if (!activeOrgIds.contains(organizationId)) {
            throw new BusinessException(TimetableErrorCode.TERM_NOT_FOUND);
        }

        List<TimetableTermEntity> orgTerms =
                termRepository.findByOrganizationIdOrderByAcademicYearDescSortOrder(organizationId);

        // チーム固有学期を優先してマージ（年度×名前をキーとして重複排除）
        Map<String, TimetableTermEntity> merged = new LinkedHashMap<>();
        for (TimetableTermEntity term : teamTerms) {
            merged.put(termKey(term), term);
        }
        for (TimetableTermEntity term : orgTerms) {
            merged.putIfAbsent(termKey(term), term);
        }
        return List.copyOf(merged.values());
    }

    /**
     * 学期IDで学期を取得する。見つからない場合は例外をスローする。
     */
    public TimetableTermEntity getByTermId(Long termId) {
        return termRepository.findById(termId)
                .orElseThrow(() -> new BusinessException(TimetableErrorCode.TERM_NOT_FOUND));
    }

    /**
     * 学期を行ロック（PESSIMISTIC_WRITE）付きで取得する。論理削除済みは取得できず 404（TIMETABLE_002）。
     *
     * <p>学期を書き換える経路（更新・削除）はすべてこれを通す。先に通常の読み取りでエンティティを持ってから
     * ロックすると、永続性コンテキストに古い状態（{@code deletedAt = null} など）が残り、並行する削除がコミットした後の
     * 全列 UPDATE で削除を取り消してしまう。そのため、必ずロック付きの取得を最初の読み取りにする。
     * この Entity と {@code BaseEntity} に {@code @Version} は無いので、行ロックが更新どうしの競合も防ぐ。</p>
     */
    private TimetableTermEntity getByTermIdForUpdate(Long termId) {
        return termRepository.findByIdForUpdate(termId)
                .orElseThrow(() -> new BusinessException(TimetableErrorCode.TERM_NOT_FOUND));
    }

    /**
     * 学期を作成する。
     *
     * @param scopeId スコープID（チームIDまたは組織ID）
     * @param isTeam  true: チームスコープ、false: 組織スコープ
     * @param data    作成データ
     * @param actorUserId 操作者ユーザーID
     */
    @Transactional
    public TimetableTermEntity createTerm(Long scopeId, boolean isTeam, CreateTermData data, Long actorUserId) {
        // createは作成先スコープ（path scopeId）で checkAdminOrAbove。
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, isTeam ? SCOPE_TEAM : SCOPE_ORGANIZATION);

        validateDateOrder(data.startDate(), data.endDate());
        validateTermUniqueness(scopeId, isTeam, data.academicYear(), data.name(), null);
        validateTermDateRange(scopeId, isTeam, data.academicYear(), data.startDate(), data.endDate(), null);

        TimetableTermEntity entity = TimetableTermEntity.builder()
                .teamId(isTeam ? scopeId : null)
                .organizationId(isTeam ? null : scopeId)
                .academicYear(data.academicYear())
                .name(data.name())
                .startDate(data.startDate())
                .endDate(data.endDate())
                .sortOrder(data.sortOrder() != null ? data.sortOrder() : 0)
                .build();

        return termRepository.save(entity);
    }

    /**
     * 学期を更新する。
     */
    @Transactional
    public TimetableTermEntity updateTerm(Long termId, UpdateTermData data, Long actorUserId) {
        // 並行する削除と直列化する（読み取り後の削除を全列 UPDATE で取り消さない）
        TimetableTermEntity entity = getByTermIdForUpdate(termId);

        boolean isTeam = entity.getTeamId() != null;
        Long scopeId = isTeam ? entity.getTeamId() : entity.getOrganizationId();

        // 変更系は entity 由来 scope（team/organization どちらに属するかも entity 由来）で checkAdminOrAbove。
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, isTeam ? SCOPE_TEAM : SCOPE_ORGANIZATION);

        // PATCH は部分更新。省略された項目は既存値を保つ（null を NOT NULL 列へ書いて 500 になるのを防ぐ）。
        String name = data.name() != null ? data.name() : entity.getName();
        LocalDate startDate = data.startDate() != null ? data.startDate() : entity.getStartDate();
        LocalDate endDate = data.endDate() != null ? data.endDate() : entity.getEndDate();
        Integer sortOrder = data.sortOrder() != null ? data.sortOrder() : entity.getSortOrder();

        validateDateOrder(startDate, endDate);
        validateTermUniqueness(scopeId, isTeam, entity.getAcademicYear(), name, termId);
        validateTermDateRange(scopeId, isTeam, entity.getAcademicYear(), startDate, endDate, termId);

        // toBuilder().build() で作り直すと id=null の新インスタンスになり INSERT 化するため、
        // managed entity を直接ミューテートして UPDATE に固定する（#1643 同型バグ根治）。
        entity.applyUpdate(name, startDate, endDate, sortOrder);
        return termRepository.save(entity);
    }

    /**
     * 学期を論理削除する。未削除の時間割が紐づく場合は削除不可（409 TIMETABLE_022）。
     */
    @Transactional
    public void deleteTerm(Long termId, Long actorUserId) {
        // 最初の読み取りを行ロック付きにする（古い状態を持ち込まない）。
        // ロック待ちの間に別トランザクションが学期を論理削除していれば取得できず 404（二重削除）。
        TimetableTermEntity locked = getByTermIdForUpdate(termId);

        boolean isTeam = locked.getTeamId() != null;
        Long scopeId = isTeam ? locked.getTeamId() : locked.getOrganizationId();
        // 変更系は entity 由来 scope で checkAdminOrAbove。
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, isTeam ? SCOPE_TEAM : SCOPE_ORGANIZATION);

        // 未削除の時間割が残るときだけ拒否する。論理削除済みの時間割は fk_tm_term(RESTRICT) の参照元として残るが、
        // 学期自体も論理削除（行は残す）なので FK 違反にならず、削除を妨げない（CMP-261105-1507 B1）。
        // チーム・組織どちらの学期でも termId で判定する。
        // 並行した時間割の作成・複製と直列化するため、取得済みの行ロック下で参照を数える。
        if (timetableRepository.countLiveByTermIdLocked(termId) > 0) {
            throw new BusinessException(TimetableErrorCode.TERM_HAS_TIMETABLES);
        }

        locked.softDelete(clock);
        termRepository.save(locked);
    }

    // ---- Validation Helpers ----

    /**
     * 開始日が終了日より前であることを検証する。DB の CHECK（{@code start_date < end_date}）と同じく同日も不可。
     * 違反を DB まで流すと CHECK 違反が 500 で漏れるため、400 の fieldErrors（endDate）で返す（CMP-261105-1507 A2）。
     * PATCH では省略項目を既存値で補ってから呼ぶ。
     */
    private void validateDateOrder(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && !startDate.isBefore(endDate)) {
            throw new BusinessException(CommonErrorCode.COMMON_001,
                    List.of(new ErrorResponse.FieldError("endDate", "終了日は開始日より後の日付にしてください")));
        }
    }

    private void validateTermUniqueness(Long scopeId, boolean isTeam, Integer academicYear,
                                        String name, Long excludeTermId) {
        List<TimetableTermEntity> existing = isTeam
                ? termRepository.findByTeamIdAndAcademicYearOrderBySortOrder(scopeId, academicYear)
                : termRepository.findByOrganizationIdAndAcademicYearOrderBySortOrder(scopeId, academicYear);

        boolean duplicate = existing.stream()
                .filter(t -> !t.getId().equals(excludeTermId))
                .anyMatch(t -> t.getName().equals(name));

        if (duplicate) {
            throw new BusinessException(TimetableErrorCode.DUPLICATE_TERM_NAME);
        }
    }

    private void validateTermDateRange(Long scopeId, boolean isTeam, Integer academicYear,
                                       LocalDate startDate, LocalDate endDate, Long excludeTermId) {
        List<TimetableTermEntity> existing = isTeam
                ? termRepository.findByTeamIdAndAcademicYearOrderBySortOrder(scopeId, academicYear)
                : termRepository.findByOrganizationIdAndAcademicYearOrderBySortOrder(scopeId, academicYear);

        boolean overlap = existing.stream()
                .filter(t -> !t.getId().equals(excludeTermId))
                .anyMatch(t -> !t.getEndDate().isBefore(startDate) && !t.getStartDate().isAfter(endDate));

        if (overlap) {
            throw new BusinessException(TimetableErrorCode.TERM_DATE_OVERLAP);
        }
    }

    private String termKey(TimetableTermEntity term) {
        return term.getAcademicYear() + ":" + term.getName();
    }

    /**
     * 学期作成データ。
     */
    public record CreateTermData(
            Integer academicYear,
            String name,
            LocalDate startDate,
            LocalDate endDate,
            Integer sortOrder
    ) {}

    /**
     * 学期更新データ。
     */
    public record UpdateTermData(
            String name,
            LocalDate startDate,
            LocalDate endDate,
            Integer sortOrder
    ) {}
}
