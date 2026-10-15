package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.recruitment.RecruitmentDistributionTargetType;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.ApplyToRecruitmentRequest;
import com.mannschaft.app.recruitment.dto.CancelRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentDistributionTargetResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentParticipantResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentTemplateResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentTemplateUpdateRequest;
import com.mannschaft.app.recruitment.dto.UpdateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.service.RecruitmentListingService.ListingAccessScope;
import com.mannschaft.app.recruitment.service.RecruitmentTemplateService.TemplateAccessScope;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * recruitment の募集・参加者管理・テンプレート EP の<b>認可ファサード</b>（トランザクションの外）。
 *
 * <p>CMP-260923-0954 W5。認可（{@code AccessControlService}）と「403 か 404 か」の振り分けを
 * {@code @Transactional} の外へ出す。本クラスは {@code @Transactional} を<b>付けない</b>（クラスにもメソッドにも）。
 * 流れは共通で「tx 本体 Service の readOnly な scope 解決（自ドメインのみ・素の読み取り）→ 認可 →
 * tx 本体（中で対象→親をたどり直し、FOR UPDATE は認可の後にだけ取る）」。Controller は本クラスだけを呼ぶ。
 * 名前を {@code *AccessService}・{@code *AccessGate} にしないのは、呼んだだけで認可シグナル扱いになり
 * 本クラス内の認可漏れを AuthzControllerGuard が見逃すのを避けるため。W4 の金銭・制裁は
 * {@link RecruitmentMoneyFacade}（別クラス）。</p>
 *
 * <p>{@code ScopeConcealingAccessGate} の共通形は<b>使わない</b>。Gate は冒頭で SYSTEM_ADMIN を通すが、
 * 本クラスの対象 EP は是正前から SYSTEM_ADMIN を通していない（403 のまま。マスター裁可 2026-09-30）。
 * 許可経路では是正前と同じ判定クエリだけを発行し（管理者系は {@code isAdminOrAbove} 1 本、
 * テンプレート詳細は {@code isMember} 1 本）、追加の判定（SYSTEM_ADMIN・在籍）は拒否経路でだけ行う。</p>
 *
 * <h3>EP × 主体の応答表（status は HTTP。募集は R001、テンプレートは R313 を不在コードとする）</h3>
 * <pre>
 * 主体＼対象                         非公開物の書込 8EP        公開物(PUBLIC 公開中)の書込  GET DRAFT        テンプレ GET   テンプレ PATCH/archive
 * 部外者・他チームADMIN               404 R001                  403 C002                     404 R001         404 R313       404 R313
 * 同スコープ一般メンバー              403 C002                  403 C002                     403 R020         許可(200)      403 C002
 * スコープ管理者（user_roles）        許可                      許可                         許可             403 C002 ※    許可
 * 非メンバー/メンバー SYSTEM_ADMIN    403 C002                  403 C002                     403 R020         403 / 200      403 C002
 * 対象不在・論理削除・親だけ不在      404 R001                  404 R001                     404 R001         404 R313       404 R313
 * PERSONAL 募集（本人）               404 R001（publish・配信対象は是正前どおり tx 本体の判定）
 * PERSONAL 募集（部外者）             404 R001
 * PERSONAL 募集（SYSTEM_ADMIN）       404 R001（publish・配信対象のみ 403 C002）
 * ※ user_roles のみの ADMIN（在籍なし）のテンプレ GET は是正前から 403（isMember だけで判定。AC-3）。
 * </pre>
 * <p>状態判定（中止済み・公開範囲・締切など）はすべて認可の後（tx 本体）。拒否・不在では DB は 1 列も変わらない。</p>
 */
@Service
@RequiredArgsConstructor
public class RecruitmentListingFacade {

    private final RecruitmentListingService listingService;
    private final RecruitmentParticipantService participantService;
    private final RecruitmentTemplateService templateService;
    private final AccessControlService accessControlService;
    private final ContentVisibilityChecker visibilityChecker;

    // ===========================================
    // 募集の書込系
    // ===========================================

    /**
     * 募集を編集する。許可は募集スコープの ADMIN/DEPUTY_ADMIN（SYSTEM_ADMIN は不可）。
     *
     * @param listingId 募集 ID
     * @param userId    操作者
     * @param request   更新内容
     * @return 更新後の募集
     */
    public RecruitmentListingResponse update(Long listingId, Long userId, UpdateRecruitmentListingRequest request) {
        requireListingAdmin(userId, listingService.resolveListingScope(listingId), false);
        return listingService.update(listingId, userId, request);
    }

    /**
     * 募集を公開する（DRAFT → OPEN）。許可は {@link #update} と同じ。個人札の本人は是正前どおり tx 本体の判定へ進む。
     *
     * @param listingId 募集 ID
     * @param userId    操作者
     * @return 公開後の募集
     */
    public RecruitmentListingResponse publish(Long listingId, Long userId) {
        requireListingAdmin(userId, listingService.resolveListingScope(listingId), true);
        return listingService.publish(listingId, userId);
    }

    /**
     * 募集を主催者として中止する。許可は {@link #update} と同じ。
     *
     * @param listingId 募集 ID
     * @param userId    操作者
     * @param request   中止理由（任意）
     * @return 中止後の募集
     */
    public RecruitmentListingResponse cancel(Long listingId, Long userId, CancelRecruitmentListingRequest request) {
        requireListingAdmin(userId, listingService.resolveListingScope(listingId), false);
        return listingService.cancelByAdmin(listingId, userId, request);
    }

    /**
     * 募集を論理削除する。許可は {@link #update} と同じ。
     *
     * @param listingId 募集 ID
     * @param userId    操作者
     */
    public void archive(Long listingId, Long userId) {
        requireListingAdmin(userId, listingService.resolveListingScope(listingId), false);
        listingService.archive(listingId, userId);
    }

    /**
     * 配信対象を取得する。許可は {@link #update} と同じ。個人札の本人は是正前どおり tx 本体の判定へ進む。
     *
     * @param listingId 募集 ID
     * @param userId    操作者
     * @return 配信対象
     */
    public List<RecruitmentDistributionTargetResponse> getDistributionTargets(Long listingId, Long userId) {
        requireListingAdmin(userId, listingService.resolveListingScope(listingId), true);
        return listingService.getDistributionTargets(listingId);
    }

    /**
     * 配信対象を設定する（全削除→再設定）。許可は {@link #getDistributionTargets} と同じ。
     *
     * @param listingId   募集 ID
     * @param userId      操作者
     * @param targetTypes 配信対象種別
     * @return 設定後の配信対象
     */
    public List<RecruitmentDistributionTargetResponse> setDistributionTargets(
            Long listingId, Long userId, List<RecruitmentDistributionTargetType> targetTypes) {
        requireListingAdmin(userId, listingService.resolveListingScope(listingId), true);
        return listingService.setDistributionTargets(listingId, targetTypes);
    }

    /**
     * 参加者一覧を返す。許可は {@link #update} と同じ。認可は募集単位で 1 回（件数に依らない）。
     *
     * @param listingId 募集 ID
     * @param userId    操作者
     * @param pageable  ページ指定
     * @return 参加者のページ
     */
    public Page<RecruitmentParticipantResponse> listParticipants(Long listingId, Long userId, Pageable pageable) {
        requireListingAdmin(userId, listingService.resolveListingScope(listingId), false);
        return participantService.listParticipants(listingId, pageable);
    }

    /**
     * 出席を記録する。許可は {@link #update} と同じ。参加者の不在・他募集の参加者は認可の後（tx 本体）の 404。
     *
     * @param listingId     募集 ID
     * @param participantId 参加者 ID
     * @param userId        操作者
     * @return 更新後の参加者
     */
    public RecruitmentParticipantResponse markAttended(Long listingId, Long participantId, Long userId) {
        requireListingAdmin(userId, listingService.resolveListingScope(listingId), false);
        return participantService.markAttended(listingId, participantId, userId);
    }

    // ===========================================
    // 募集の詳細・申込
    // ===========================================

    /**
     * 募集詳細を返す。下書き（DRAFT）は作成者・スコープ管理者のみ。個人札は公開後を返さない。
     *
     * <p>拒否時、操作者が存在を知り得る者（下書きなら SYSTEM_ADMIN・スコープ在籍者）なら 403
     * （{@code DRAFT_VIEW_DENIED}）、それ以外は不在と同一の 404（{@code LISTING_NOT_FOUND}）。
     * 限定公開の非 DRAFT の可視性（F00 の 403 / 404）は範囲外で、是正前の判定に委ねる。</p>
     *
     * @param listingId 募集 ID
     * @param userId    閲覧者
     * @return 募集詳細
     */
    public RecruitmentListingResponse getListing(Long listingId, Long userId) {
        ListingAccessScope scope = listingService.resolveListingScope(listingId);
        if (scope.scopeType() == RecruitmentScopeType.PERSONAL && scope.status() != RecruitmentListingStatus.DRAFT) {
            // 汎用の詳細は個人札の公開後を返さない（/public/market に一本化）。不在と同じコードで隠す。
            throw new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND);
        }
        if (scope.status() == RecruitmentListingStatus.DRAFT) {
            requireDraftViewer(userId, scope);
            // 閲覧は判定済み。tx 本体で管理者判定を繰り返さない（許可経路の認可クエリを是正前と同数に保つ）。
            // 判定の後に公開されていた場合だけ、通常の経路（F00 の可視性判定）へ切り替える。
            return listingService.findAuthorizedDraftListing(listingId)
                    .orElseGet(() -> listingService.getListing(listingId, userId));
        }
        return listingService.getListing(listingId, userId);
    }

    /**
     * 募集へ申し込む。可視性の判定を状態の判定（締切・下書き・中止）より前に置く。
     *
     * <p>募集を閲覧できず、かつスコープの在籍者・管理者でもない者には、状態に依らず不在と同一の 404
     * （{@code LISTING_NOT_FOUND}）。閲覧できない在籍者・管理者には、ファサードが是正前の応答（下書きは 409
     * {@code DRAFT_NOT_APPLICABLE}、それ以外は可視性の 403 {@code VISIBILITY_001}）を返す。tx 本体へ進むのは
     * 閲覧できる者（と個人札の本人）だけで、FOR UPDATE は tx 本体だけ（拒否の経路は募集の行をロックしない）。</p>
     *
     * @param listingId 募集 ID
     * @param userId    申込者
     * @param request   申込内容
     * @return 申込結果
     */
    public RecruitmentParticipantResponse apply(Long listingId, Long userId, ApplyToRecruitmentRequest request) {
        ListingAccessScope scope = listingService.resolveListingScope(listingId);
        boolean ownPersonalListing = scope.scopeType() == RecruitmentScopeType.PERSONAL
                && Objects.equals(scope.scopeId(), userId);
        if (!ownPersonalListing && !visibilityChecker.canView(ReferenceType.RECRUITMENT_LISTING, listingId, userId)) {
            // 閲覧できない。ここでは募集の行をロックせず、是正前の応答をファサードで決める（tx 本体へ進まない）。
            if (!isScopeInsider(userId, scope)) {
                throw new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND);
            }
            if (scope.status() == RecruitmentListingStatus.DRAFT) {
                // 是正前は状態の判定（下書き）が可視性の判定より先だった。
                throw new BusinessException(RecruitmentErrorCode.DRAFT_NOT_APPLICABLE);
            }
            // 是正前と同じ拒否（assertCanView: deny は VISIBILITY_001 の 403、不在は VISIBILITY_004 の 404）。
            visibilityChecker.assertCanView(ReferenceType.RECRUITMENT_LISTING, listingId, userId);
        }
        return participantService.apply(listingId, userId, request);
    }

    // ===========================================
    // テンプレート
    // ===========================================

    /**
     * テンプレート詳細を返す。許可はテンプレートスコープの在籍メンバー（{@code isMember} 1 本。SUPPORTER 含む）。
     *
     * @param templateId テンプレート ID
     * @param userId     閲覧者
     * @return テンプレート詳細
     */
    public RecruitmentTemplateResponse getTemplate(Long templateId, Long userId) {
        TemplateAccessScope scope = templateService.resolveTemplateScope(templateId);
        String accessScope = accessScopeName(scope.scopeType());
        if (accessScope != null && accessControlService.isMember(userId, scope.scopeId(), accessScope)) {
            return templateService.getTemplate(templateId);
        }
        // 拒否: SYSTEM_ADMIN・スコープ管理者（user_roles）は存在を知り得るので 403、それ以外は不在と同一の 404。
        if (accessControlService.isSystemAdmin(userId)
                || (accessScope != null && accessControlService.isAdminOrAbove(userId, scope.scopeId(), accessScope))) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        throw new BusinessException(RecruitmentErrorCode.TEMPLATE_NOT_FOUND);
    }

    /**
     * テンプレートを編集する。許可はテンプレートスコープの ADMIN/DEPUTY_ADMIN（SYSTEM_ADMIN は不可）。
     *
     * @param templateId テンプレート ID
     * @param userId     操作者
     * @param request    更新内容
     * @return 更新後のテンプレート
     */
    public RecruitmentTemplateResponse updateTemplate(
            Long templateId, Long userId, RecruitmentTemplateUpdateRequest request) {
        requireTemplateAdmin(userId, templateService.resolveTemplateScope(templateId));
        return templateService.update(templateId, request);
    }

    /**
     * テンプレートを論理削除（アーカイブ）する。許可は {@link #updateTemplate} と同じ。
     *
     * @param templateId テンプレート ID
     * @param userId     操作者
     */
    public void archiveTemplate(Long templateId, Long userId) {
        requireTemplateAdmin(userId, templateService.resolveTemplateScope(templateId));
        templateService.archive(templateId);
    }

    // ===========================================
    // 共通
    // ===========================================

    /**
     * 募集スコープの ADMIN/DEPUTY_ADMIN（user_roles）を要求する。是正前の {@code checkAdminOrAbove} と同じ判定
     * （{@code isAdminOrAbove} 1 本。SYSTEM_ADMIN は通さない）で、許可経路では追加のクエリを発行しない。
     *
     * <p>拒否時だけ追加の判定を行う。公開物（PUBLIC かつ公開中）は誰でも存在を知っているので 403、
     * それ以外は SYSTEM_ADMIN・スコープ在籍者なら 403、越境は不在と同一の 404。
     * 個人札・GLOBAL は管理者判定の対象外（在籍の概念が無い）なので、不在と同一の 404 で隠す。
     * ただし {@code personalOwnerPasses} の EP（公開・配信対象）は、是正前と同じく本人は tx 本体の判定へ進め、
     * SYSTEM_ADMIN は 403 のままにする。</p>
     */
    private void requireListingAdmin(Long userId, ListingAccessScope scope, boolean personalOwnerPasses) {
        String accessScope = accessScopeName(scope.scopeType());
        if (accessScope == null) {
            if (personalOwnerPasses && scope.scopeType() == RecruitmentScopeType.PERSONAL) {
                if (Objects.equals(scope.scopeId(), userId) && Objects.equals(scope.createdBy(), userId)) {
                    return;
                }
                if (accessControlService.isSystemAdmin(userId)) {
                    throw new BusinessException(CommonErrorCode.COMMON_002);
                }
            }
            throw new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND);
        }
        if (accessControlService.isAdminOrAbove(userId, scope.scopeId(), accessScope)) {
            return;
        }
        if (scope.isPublicObject()
                || accessControlService.isSystemAdmin(userId)
                || accessControlService.isMember(userId, scope.scopeId(), accessScope)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        throw new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND);
    }

    /**
     * 下書き募集の閲覧者を判定する。是正前の判定（作成者または管理者。個人札は本人のみ）のまま、拒否時だけ
     * 403 と 404 を分ける。
     */
    private void requireDraftViewer(Long userId, ListingAccessScope scope) {
        boolean creator = Objects.equals(scope.createdBy(), userId);
        String accessScope = accessScopeName(scope.scopeType());
        if (accessScope == null) {
            if (scope.scopeType() == RecruitmentScopeType.PERSONAL) {
                if (creator && Objects.equals(scope.scopeId(), userId)) {
                    return;
                }
                if (accessControlService.isSystemAdmin(userId)) {
                    throw new BusinessException(RecruitmentErrorCode.DRAFT_VIEW_DENIED);
                }
            }
            throw new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND);
        }
        if (creator || accessControlService.isAdminOrAbove(userId, scope.scopeId(), accessScope)) {
            return;
        }
        if (accessControlService.isSystemAdmin(userId)
                || accessControlService.isMember(userId, scope.scopeId(), accessScope)) {
            throw new BusinessException(RecruitmentErrorCode.DRAFT_VIEW_DENIED);
        }
        throw new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND);
    }

    /** 募集スコープの在籍者または管理者（user_roles）か。TEAM / ORGANIZATION 以外は在籍の概念が無いので false。 */
    private boolean isScopeInsider(Long userId, ListingAccessScope scope) {
        String accessScope = accessScopeName(scope.scopeType());
        return accessScope != null
                && (accessControlService.isMember(userId, scope.scopeId(), accessScope)
                || accessControlService.isAdminOrAbove(userId, scope.scopeId(), accessScope));
    }

    /**
     * テンプレートスコープの ADMIN/DEPUTY_ADMIN（user_roles）を要求する。許可経路は {@code isAdminOrAbove} 1 本。
     * 拒否時だけ SYSTEM_ADMIN・在籍者なら 403、それ以外は不在と同一の 404。
     */
    private void requireTemplateAdmin(Long userId, TemplateAccessScope scope) {
        String accessScope = accessScopeName(scope.scopeType());
        if (accessScope != null && accessControlService.isAdminOrAbove(userId, scope.scopeId(), accessScope)) {
            return;
        }
        if (accessControlService.isSystemAdmin(userId)
                || (accessScope != null && accessControlService.isMember(userId, scope.scopeId(), accessScope))) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        throw new BusinessException(RecruitmentErrorCode.TEMPLATE_NOT_FOUND);
    }

    /** AccessControlService に渡せるスコープ名。TEAM/ORGANIZATION 以外は null。 */
    private static String accessScopeName(RecruitmentScopeType scopeType) {
        if (scopeType == RecruitmentScopeType.TEAM || scopeType == RecruitmentScopeType.ORGANIZATION) {
            return scopeType.name();
        }
        return null;
    }
}
