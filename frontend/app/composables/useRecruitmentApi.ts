import { useRecruitmentApplications } from './recruitment/useRecruitmentApplications'
import { useRecruitmentCancellationFee } from './recruitment/useRecruitmentCancellationFee'
import { useRecruitmentCrud } from './recruitment/useRecruitmentCrud'
import { useRecruitmentMatching } from './recruitment/useRecruitmentMatching'

/**
 * F03.11 募集型予約 API クライアント (Phase 1+5a) — ファサード。
 *
 * 実装は責務単位で 3 ファイルに分割している:
 *   - useRecruitmentCrud          : カテゴリ・サブカテゴリ・募集枠 CRUD・キャンセルポリシー・検索・キャンセル料見積
 *   - useRecruitmentApplications  : 参加申込・配信対象設定・マイページ・フィード・申込確定
 *   - useRecruitmentMatching      : NO_SHOW・ペナルティ・異議申立 (Phase 5b)
 *
 * 既存呼び出し側との互換のため、本ファサードは従来通り単一フラットな関数群を返す。
 */
export function useRecruitmentApi() {
  const crud = useRecruitmentCrud()
  const applications = useRecruitmentApplications()
  const matching = useRecruitmentMatching()
  const cancellationFee = useRecruitmentCancellationFee()

  return {
    // ----- CRUD・カタログ系 (useRecruitmentCrud) -----
    listCategories: crud.listCategories,
    listTeamSubcategories: crud.listTeamSubcategories,
    createTeamSubcategory: crud.createTeamSubcategory,
    archiveTeamSubcategory: crud.archiveTeamSubcategory,
    listTeamListings: crud.listTeamListings,
    createListing: crud.createListing,
    createOrgListing: crud.createOrgListing,
    getListing: crud.getListing,
    updateListing: crud.updateListing,
    publishListing: crud.publishListing,
    cancelListing: crud.cancelListing,
    archiveListing: crud.archiveListing,
    estimateCancellationFee: crud.estimateCancellationFee,
    // ----- 参加申込・マイページ系 (useRecruitmentApplications) -----
    applyToListing: applications.applyToListing,
    cancelMyApplication: applications.cancelMyApplication,
    listListingParticipants: applications.listListingParticipants,
    markParticipantAttended: applications.markParticipantAttended,
    listMyActiveParticipations: applications.listMyActiveParticipations,
    // Phase 2
    getMyListings: applications.getMyListings,
    getMyFeed: applications.getMyFeed,
    getDistributionTargets: applications.getDistributionTargets,
    setDistributionTargets: applications.setDistributionTargets,
    confirmApplication: applications.confirmApplication,
    // ----- キャンセルポリシー・検索 (useRecruitmentCrud) -----
    listTeamCancellationPolicies: crud.listTeamCancellationPolicies,
    createCancellationPolicy: crud.createCancellationPolicy,
    getCancellationPolicy: crud.getCancellationPolicy,
    updateCancellationPolicy: crud.updateCancellationPolicy,
    archiveCancellationPolicy: crud.archiveCancellationPolicy,
    searchListings: crud.searchListings,
    // ----- Phase 5b: NO_SHOW・ペナルティ (useRecruitmentMatching) -----
    markNoShow: matching.markNoShow,
    getNoShowsByScope: matching.getNoShowsByScope,
    getMyNoShows: matching.getMyNoShows,
    disputeNoShow: matching.disputeNoShow,
    resolveDispute: matching.resolveDispute,
    getPenaltySetting: matching.getPenaltySetting,
    upsertPenaltySetting: matching.upsertPenaltySetting,
    getScopePenalties: matching.getScopePenalties,
    liftPenalty: matching.liftPenalty,
    getMyPenalties: matching.getMyPenalties,
    // ----- F03.11.1 キャンセル料の免除 (useRecruitmentCancellationFee) -----
    listCancellationRecords: cancellationFee.listCancellationRecords,
    waiveCancellationFee: cancellationFee.waiveCancellationFee,
  }
}
