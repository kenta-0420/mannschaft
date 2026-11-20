package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.common.timezone.UserTimezoneCache;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.effortrecord.dto.CreateEffortRecordRequest;
import com.mannschaft.app.effortrecord.dto.EffortDeleteResponse;
import com.mannschaft.app.effortrecord.dto.EffortOwnerRecordResponse;
import com.mannschaft.app.effortrecord.dto.EffortRecordListCriteria;
import com.mannschaft.app.effortrecord.dto.EffortRecordPage;
import com.mannschaft.app.effortrecord.dto.UpdateEffortRecordRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;

/**
 * F06.4.1 個人原本の Owner Service（非 TX の入口）。全操作は認証主体 ownerUserId に束縛される。
 *
 * <p>他人・不在・削除済み・purged は {@code EFFORT_001}（404）で区別しない。業務 TX は
 * {@link EffortRecordTxService} が持つ。本クラスは TX の外で本人 TZ（記録日の未来判定に使う）を解決して渡す
 * （TX 入口から他ドメイン Repository へ到達させない。ArchUnit D-3T）。</p>
 */
@Service
@RequiredArgsConstructor
public class EffortRecordOwnerService {

    private static final ZoneId FALLBACK_ZONE = UserZoneLocalDateTimeParser.SERVER_ZONE;

    private final EffortRecordTxService tx;
    private final UserTimezoneCache userTimezones;
    private final EffortDefinitionTokenCodec tokens;
    private final EffortTeamAccessGuard teamAccess;

    /**
     * 原本を作成する（Idempotency-Key 必須）。
     *
     * <p>TEAM 定義（definitionToken あり）は、token の署名・期限・本人・定義 UUID・revision の束縛を検証し
     * （不正は 400 definitionToken）、token が束縛するチームへの<b>現在所属</b>を再検証してから
     * （脱退済みは 403 COMMON_002）TX へ渡す。所属照会は TX の外で行う（TX 入口から他ドメインへ到達させない）。
     * 同じ Idempotency-Key・同じ本文の作成が完了済みなら、token の期限より先に元の応答を返し、同じ key・別の本文なら
     * token 検証より先に 409 COMMON_003 を返す（API 契約 §4.1）。</p>
     */
    public EffortOwnerRecordResponse create(Long ownerUserId, String idempotencyKey, CreateEffortRecordRequest request) {
        EffortDefinitionTokenCodec.Claims teamToken = null;
        if (request.definitionToken() != null) {
            java.util.Optional<EffortOwnerRecordResponse> completed = tx.findCompletedCreate(ownerUserId, idempotencyKey, request);
            if (completed.isPresent()) {
                return completed.get();
            }
            teamToken = tokens.verify(request.definitionToken(), ownerUserId, request.definitionUuid(),
                    request.definitionRevision());
            teamAccess.requireMember(ownerUserId, teamToken.teamId());
        }
        return tx.create(ownerUserId, idempotencyKey, request, userZone(ownerUserId), teamToken);
    }

    /** 本人の原本一覧（recordedOn DESC, createdAt DESC, id DESC のカーソルページング）。 */
    public EffortRecordPage list(Long ownerUserId, EffortRecordListCriteria criteria) {
        return tx.list(ownerUserId, criteria);
    }

    /** 本人の原本詳細。 */
    public EffortOwnerRecordResponse get(Long ownerUserId, UUID id) {
        return tx.get(ownerUserId, id);
    }

    /** 原本を全置換更新する（version 一致必須。不一致は COMMON_003 / 409）。 */
    public EffortOwnerRecordResponse update(Long ownerUserId, UUID id, UpdateEffortRecordRequest request) {
        return tx.update(ownerUserId, id, request, userZone(ownerUserId));
    }

    /** 原本を論理削除する（Idempotency-Key 必須、version 一致必須）。 */
    public EffortDeleteResponse delete(Long ownerUserId, UUID id, long version, String idempotencyKey) {
        return tx.delete(ownerUserId, id, version, idempotencyKey);
    }

    private ZoneId userZone(Long ownerUserId) {
        try {
            return ZoneId.of(userTimezones.getTimezone(ownerUserId));
        } catch (DateTimeException | NullPointerException e) {
            return FALLBACK_ZONE;
        }
    }
}
