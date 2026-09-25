package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationRecipientPageResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationRecipientResponse;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.UnconfirmedVisibility;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * F04.9 確認通知の参照・一覧取得を担当するサービス（読込専用）。
 *
 * <p>ファサード {@link ConfirmableNotificationService} から委譲される参照系処理を実装する。
 * 全メソッドが {@code @Transactional(readOnly=true)} で動作する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConfirmableNotificationQueryService {

    private final ConfirmableNotificationRepository notificationRepository;
    private final ConfirmableNotificationRecipientRepository recipientRepository;
    /**
     * CMP-260920-1040: 受信者一覧ページング（{@link #getRecipientsPage}）で viewerRole（ADMIN/CREATOR/MEMBER）
     * を判定するために使う。旧コンストラクタ（2引数）を使う既存呼び出し元はいないため、
     * {@code @RequiredArgsConstructor} でコンストラクタに追加する（骨格試練の
     * {@code new ConfirmableNotificationQueryService(null, null)} はIT差し替えで解消する）。
     */
    private final AccessControlService accessControlService;

    /**
     * 確認通知の詳細を取得する。
     *
     * @param notificationId 確認通知ID
     * @return 確認通知エンティティ
     */
    public ConfirmableNotificationEntity getDetail(Long notificationId) {
        return notificationRepository.findById(notificationId)
                .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.NOT_FOUND));
    }

    /**
     * 確認通知の受信者一覧を取得する（ADMIN+ 用・全件）。
     *
     * <p>呼び出し側で ADMIN+ 権限チェック済みであること。
     * F04.9 Phase D の MEMBER 視点アクセスは
     * {@link #getRecipientsForMember(Long, Long)} を使用すること。</p>
     *
     * <p><b>CMP-260920-1040是正</b>: 従来は Entity を返し、Controller 側で
     * {@code ConfirmableNotificationMapper#toRecipientResponseList} を通していたが、そのマッピングが
     * LAZY な {@code recipient.getUser()} を関連経由で読むため、退会者（{@code UserEntity} の
     * {@code @SQLRestriction("deleted_at IS NULL")}）が1人でも含まれると
     * {@code EntityNotFoundException} になり一覧全体が 500 化していた（家老の検出・殿の確認）。
     * 本メソッドはネイティブ投影（{@link ConfirmableNotificationRecipientRepository#findRecipientRowsByNotificationId}）
     * から直接 DTO を組み立てることでこれを根治し、退会者は {@code withdrawn=true}
     * （表示名・アバターURLはNULL）の行として返す。</p>
     *
     * @param notificationId 確認通知ID
     * @return 受信者レスポンスリスト（除外者・確認済みも含む全件・退会者は withdrawn=true）
     */
    public List<ConfirmableNotificationRecipientResponse> getRecipients(Long notificationId) {
        // 通知の存在確認
        if (!notificationRepository.existsById(notificationId)) {
            throw new BusinessException(ConfirmableNotificationErrorCode.NOT_FOUND);
        }
        return recipientRepository.findRecipientRowsByNotificationId(notificationId).stream()
                .map(ConfirmableNotificationQueryService::toRecipientResponse)
                .collect(Collectors.toList());
    }

    /**
     * CMP-260920-1040是正: ネイティブ投影の1行（id, user_id, display_name, avatar_url,
     * user_deleted_at, is_confirmed, confirmed_at, confirmed_via, excluded_at, created_at）を
     * {@link ConfirmableNotificationRecipientResponse} に変換する。退会者
     * （{@code user_deleted_at IS NOT NULL} または {@code LEFT JOIN} で該当行が無い）は
     * {@code withdrawn=true} とし、表示名・アバターURLは個人情報保護のため NULL のまま返す。
     */
    private static ConfirmableNotificationRecipientResponse toRecipientResponse(Object[] row) {
        return toRecipientResponse(row, false);
    }

    /**
     * @param maskConfirmationDetails true の場合 {@code confirmedAt}/{@code confirmedVia}/{@code excludedAt}
     *                                を NULL マスクする（{@code toRecipientPublicResponse} と同じ MEMBER 視点契約）。
     */
    private static ConfirmableNotificationRecipientResponse toRecipientResponse(
            Object[] row, boolean maskConfirmationDetails) {
        Object userDeletedAt = row[4];
        boolean withdrawn = userDeletedAt != null || row[2] == null;
        return ConfirmableNotificationRecipientResponse.builder()
                .id(((Number) row[0]).longValue())
                .userId(row[1] == null ? null : ((Number) row[1]).longValue())
                .displayName(withdrawn ? null : (String) row[2])
                .avatarUrl(withdrawn ? null : (String) row[3])
                .withdrawn(withdrawn)
                .isConfirmed(toBoolean(row[5]))
                .confirmedAt(maskConfirmationDetails ? null : (java.time.LocalDateTime) row[6])
                .confirmedVia(maskConfirmationDetails || row[7] == null ? null
                        : com.mannschaft.app.notification.confirmable.entity.ConfirmedVia.valueOf((String) row[7]))
                .excludedAt(maskConfirmationDetails ? null : (java.time.LocalDateTime) row[8])
                .createdAt((java.time.LocalDateTime) row[9])
                .build();
    }

    /** JDBC ドライバによって Boolean/Byte/Integer と型が揺れる TINYINT(1) を安全に真偽値化する。 */
    private static Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return Boolean.parseBoolean(value.toString());
    }

    /**
     * MEMBER 視点で確認通知の未確認者一覧を取得する（F04.9 Phase D）。
     *
     * <p>認可判定:
     * <ol>
     *   <li>通知が存在し、{@code unconfirmedVisibility = ALL_MEMBERS} であること</li>
     *   <li>呼び出しユーザーが当通知の受信者であること（除外者は不可）</li>
     * </ol>
     * いずれかを満たさない場合は {@link CommonErrorCode#COMMON_002}（403）を投げる。</p>
     *
     * <p>戻り値は <b>未確認かつ非除外</b> の受信者のみ。Mapper の
     * {@code toRecipientPublicResponseList} で confirmedAt / confirmedVia / excludedAt をマスクして返すこと。</p>
     *
     * @param notificationId  確認通知ID
     * @param requesterUserId リクエスト元ユーザーID
     * @return 未確認受信者エンティティリスト（マスク前）
     */
    public List<ConfirmableNotificationRecipientEntity> getRecipientsForMember(
            Long notificationId, Long requesterUserId) {
        // 通知の存在確認
        ConfirmableNotificationEntity notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.NOT_FOUND));

        // 公開範囲チェック: ALL_MEMBERS 以外は 403
        if (notification.getUnconfirmedVisibility() != UnconfirmedVisibility.ALL_MEMBERS) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        // 呼び出しユーザーが受信者かつ非除外であることを確認
        List<ConfirmableNotificationRecipientEntity> allRecipients =
                recipientRepository.findByConfirmableNotificationId(notificationId);
        boolean isRecipient = allRecipients.stream()
                .anyMatch(r -> r.getUser().getId().equals(requesterUserId) && !r.isExcluded());
        if (!isRecipient) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        // 未確認かつ非除外の受信者のみ返す
        return allRecipients.stream()
                .filter(r -> !Boolean.TRUE.equals(r.getIsConfirmed()))
                .filter(r -> !r.isExcluded())
                .collect(Collectors.toList());
    }

    /** 受信者一覧ページングの上限件数（AC-30）。 */
    public static final int MAX_RECIPIENT_PAGE_SIZE = 100;

    /**
     * CMP-260920-1040 受信者一覧をページングして取得する（軍議第8版確定稿 §9.5・AC-30・AC-59・AC-60）。
     *
     * <p><b>骨格のみ（試練A）。出陣で実装する。</b> {@code size} は上限 {@value #MAX_RECIPIENT_PAGE_SIZE}
     * に丸めるか 400 とする（試練で1つに固定する）。件数・{@code viewerRole} は API が明示的に返す。</p>
     *
     * @param notificationId  確認通知ID
     * @param requesterUserId リクエスト元ユーザーID
     * @param page            ページ番号（0始まり）
     * @param size            ページサイズ
     * @param unconfirmedOnly 未確認者のみに絞り込むか
     * @return ページング済み受信者一覧の応答契約
     */
    public ConfirmableNotificationRecipientPageResponse getRecipientsPage(
            Long notificationId, Long requesterUserId, int page, int size, boolean unconfirmedOnly) {
        ConfirmableNotificationEntity notification = getDetail(notificationId);

        // AC-30: size は上限 MAX_RECIPIENT_PAGE_SIZE に丸める（400 にはしない方式で固定）。
        int safeSize = size <= 0 ? MAX_RECIPIENT_PAGE_SIZE : Math.min(size, MAX_RECIPIENT_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        boolean isAdmin = accessControlService.isAdminOrAbove(
                requesterUserId, notification.getScopeId(), notification.getScopeType().name());
        boolean isCreator = notification.getCreatedBy() != null
                && notification.getCreatedBy().getId().equals(requesterUserId);

        ConfirmableNotificationRecipientPageResponse.ViewerRole viewerRole;
        if (isAdmin) {
            viewerRole = ConfirmableNotificationRecipientPageResponse.ViewerRole.ADMIN;
        } else if (isCreator) {
            viewerRole = ConfirmableNotificationRecipientPageResponse.ViewerRole.CREATOR;
        } else {
            viewerRole = ConfirmableNotificationRecipientPageResponse.ViewerRole.MEMBER;
        }

        // AC-59: 総件数・確認済み・未確認件数は「通知全体の値」（ページの中身によらず一定）。
        long totalElements = recipientRepository.countByConfirmableNotificationIdAndExcludedAtIsNull(notificationId);
        long confirmedCount = recipientRepository.countByConfirmableNotificationIdAndIsConfirmedTrue(notificationId);
        long unconfirmedCount = Math.max(0, totalElements - confirmedCount);

        List<ConfirmableNotificationRecipientResponse> items;
        if (viewerRole == ConfirmableNotificationRecipientPageResponse.ViewerRole.MEMBER) {
            // AC-60: MEMBER は unconfirmedVisibility=ALL_MEMBERS かつ自身が非除外の受信者のときだけ、
            // 未確認・非除外の一覧をページングで返す（範囲の外の人は漏れない）。
            if (notification.getUnconfirmedVisibility() != UnconfirmedVisibility.ALL_MEMBERS) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
            boolean isRecipient = recipientRepository
                    .findByConfirmableNotificationIdAndUserId(notificationId, requesterUserId)
                    .filter(r -> !r.isExcluded())
                    .isPresent();
            if (!isRecipient) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
            // CMP-260920-1040是正: mapper 経由の Entity→DTO（user 関連の LAZY 読み）は退会者を含むと
            // EntityNotFoundException で 500 化するため、ネイティブ投影から直接 DTO を組み立てる。
            Page<Object[]> recipientRowPage = recipientRepository
                    .findUnconfirmedRecipientRowsPage(notificationId, pageable);
            items = recipientRowPage.getContent().stream()
                    .map(row -> toRecipientResponse(row, true))
                    .collect(Collectors.toList());
            // MEMBER 視点で見える範囲は「未確認・非除外」だけ（自分が見てよい範囲の外は漏らさない）。
            return ConfirmableNotificationRecipientPageResponse.builder()
                    .items(items)
                    .page(safePage)
                    .size(safeSize)
                    .totalElements(unconfirmedCount)
                    .confirmedCount(0L)
                    .unconfirmedCount(unconfirmedCount)
                    .viewerRole(viewerRole)
                    .build();
        }

        // CMP-260920-1040是正: ADMIN+/CREATOR 視点も同じ理由でネイティブ投影経由に揃える。
        Page<Object[]> recipientRowPage = unconfirmedOnly
                ? recipientRepository.findUnconfirmedRecipientRowsPage(notificationId, pageable)
                : recipientRepository.findRecipientRowsPage(notificationId, pageable);
        items = recipientRowPage.getContent().stream()
                .map(ConfirmableNotificationQueryService::toRecipientResponse)
                .collect(Collectors.toList());

        return ConfirmableNotificationRecipientPageResponse.builder()
                .items(items)
                .page(safePage)
                .size(safeSize)
                .totalElements(totalElements)
                .confirmedCount(confirmedCount)
                .unconfirmedCount(unconfirmedCount)
                .viewerRole(viewerRole)
                .build();
    }

    /**
     * スコープ内の確認通知一覧を取得する（作成日時降順）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 確認通知エンティティリスト
     */
    public List<ConfirmableNotificationEntity> listByScope(ScopeType scopeType, Long scopeId) {
        return notificationRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(scopeType, scopeId);
    }

    /**
     * ユーザーの未確認通知一覧を取得する（受信者視点）。
     *
     * @param userId ユーザーID
     * @return 未確認受信者エンティティリスト
     */
    public List<ConfirmableNotificationRecipientEntity> listPending(Long userId) {
        return recipientRepository.findByUserIdAndIsConfirmedFalseAndExcludedAtIsNull(userId);
    }
}
