package com.mannschaft.app.effortrecord.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.effortrecord.EffortErrorCode;
import com.mannschaft.app.effortrecord.dto.CreateEffortRecordRequest;
import com.mannschaft.app.effortrecord.dto.EffortDeleteResponse;
import com.mannschaft.app.effortrecord.dto.EffortOwnerRecordResponse;
import com.mannschaft.app.effortrecord.dto.EffortRecordListCriteria;
import com.mannschaft.app.effortrecord.dto.EffortRecordPage;
import com.mannschaft.app.effortrecord.dto.UpdateEffortRecordRequest;
import com.mannschaft.app.effortrecord.entity.EffortRecordEntity;
import com.mannschaft.app.effortrecord.event.EffortRecordDeletedEvent;
import com.mannschaft.app.effortrecord.repository.EffortRecordQueryRepository;
import com.mannschaft.app.effortrecord.repository.EffortRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * F06.4.1 個人原本の TX 本体（{@link EffortRecordOwnerService} から呼ぶ）。全操作は認証主体 ownerUserId に束縛される。
 *
 * <p>他人・不在・削除済み・purged は {@code EFFORT_001}（404）で区別しない。
 * 存在秘匿のため、<b>入力検証より先に所有確認（404）</b>を行う（他人の UUID に対して 400 と 404 の差で存在を探れない）。
 * create / delete は Idempotency-Key の receipt を同一 TX で確保・確定する（{@link EffortReceiptService}）。
 * ログには操作・原本 ID・version・結果だけを書き、体重・科目・メニュー・note・snapshot は一切書かない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EffortRecordTxService {

    private static final String BASE_PATH = "/api/v1/effort-records/me";
    private static final int DEFAULT_MAX_LIMIT = 100;

    private final EffortRecordRepository records;
    private final EffortRecordQueryRepository queries;
    private final EffortDefinitionService definitions;
    private final EffortValueValidator validator;
    private final EffortSnapshotCodec codec;
    private final EffortRecordMapper mapper;
    private final EffortReceiptService receipts;
    private final EffortCursorCodec cursors;
    private final EffortRecordMetrics metrics;
    private final DomainEventPublisher events;
    private final ObjectMapper objectMapper;
    @Qualifier("utcClock")
    private final Clock clock;

    /**
     * 原本を作成する（Idempotency-Key 必須）。TEAM 定義は Facade で検証済みの teamToken が必須
     * （標準プリセットは null）。snapshot はサーバ保存の revision を複写する。
     */
    @Transactional
    public EffortOwnerRecordResponse create(Long ownerUserId, String idempotencyKey, CreateEffortRecordRequest request,
                                            ZoneId userZone, EffortDefinitionTokenCodec.Claims teamToken) {
        return metrics.measure("create", () -> {
            UUID key = parseKey(idempotencyKey);
            String path = "POST " + BASE_PATH;
            EffortReceiptService.Handle handle = receipts.begin(ownerUserId, path, key,
                    receipts.requestHash(path, toJson(request)));
            if (handle.replay() != null) {
                return fromJson(handle.replay().json());
            }

            EffortDefinitionService.ResolvedDefinition definition =
                    definitions.resolveForCreate(request.definitionUuid(), request.definitionRevision(), teamToken);
            if (!EffortValueValidator.isIanaZone(request.recordTimezone())) {
                throw invalid("recordTimezone", "IANA タイムゾーンを指定してください");
            }
            validateRecordedOn(userZone, request.recordedOn());
            EffortValueValidator.Validated validated = validator.validate(definition.snapshot(), request.values(),
                    request.privateNote(), false);

            Instant now = now();
            EffortRecordEntity saved = records.saveAndFlush(EffortRecordEntity.builder()
                    .ownerUserId(ownerUserId)
                    .definitionId(request.definitionUuid())
                    .definitionRevision(definition.revision())
                    .definitionSnapshot(definition.snapshotJson())
                    .recordedOn(request.recordedOn())
                    .recordTimezone(request.recordTimezone())
                    .valuesJson(codec.valuesJson(validated.values()))
                    .privateNote(validated.privateNote())
                    .contentRevision(1L)
                    .shareVersion(0L)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            EffortOwnerRecordResponse response = mapper.toResponse(saved);
            receipts.complete(handle.receiptId(), 201, toJson(response));
            log.info("頑張り記録を作成: ownerUserId={}, recordId={}, version={}", ownerUserId, saved.getId(), saved.getVersion());
            return response;
        });
    }

    /**
     * 同じ Idempotency-Key・同じ本文の作成が完了済みなら、その応答を返す（書込なし）。同じ key・別の本文は 409 COMMON_003。
     * TEAM 定義 token の期限切れ後の再送でも、完了済みの作成は元の 201 を返すために Facade が先に呼ぶ。
     */
    @Transactional(readOnly = true)
    public java.util.Optional<EffortOwnerRecordResponse> findCompletedCreate(Long ownerUserId, String idempotencyKey,
                                                                            CreateEffortRecordRequest request) {
        UUID key = parseKey(idempotencyKey);
        String path = "POST " + BASE_PATH;
        return receipts.findCompleted(ownerUserId, path, key, receipts.requestHash(path, toJson(request)))
                .map(stored -> fromJson(stored.json()));
    }

    /** 本人の原本一覧（recordedOn DESC, createdAt DESC, id DESC のカーソルページング）。 */
    @Transactional(readOnly = true)
    public EffortRecordPage list(Long ownerUserId, EffortRecordListCriteria criteria) {
        return metrics.measure("list", () -> {
            if (criteria.limit() < 1 || criteria.limit() > DEFAULT_MAX_LIMIT) {
                throw invalid("limit", "limit は 1〜" + DEFAULT_MAX_LIMIT + " で指定してください");
            }
            if ((criteria.dateFrom() == null) != (criteria.dateTo() == null)) {
                throw invalid("date_from", "date_from と date_to は対で指定してください");
            }
            if (criteria.dateFrom() != null && criteria.dateFrom().isAfter(criteria.dateTo())) {
                throw invalid("date_from", "date_from は date_to 以前で指定してください");
            }
            String filterHash = cursors.filterHash(criteria.dateFrom(), criteria.dateTo(), criteria.definitionUuid());
            EffortRecordQueryRepository.After after = null;
            if (criteria.cursor() != null) {
                EffortCursorCodec.Position position = cursors.decode(ownerUserId, filterHash, criteria.cursor());
                after = new EffortRecordQueryRepository.After(position.recordedOn(), position.createdAt(), position.id());
            }
            List<EffortRecordEntity> fetched = queries.findPage(ownerUserId, criteria.dateFrom(), criteria.dateTo(),
                    criteria.definitionUuid(), after, criteria.limit() + 1);
            boolean hasNext = fetched.size() > criteria.limit();
            List<EffortRecordEntity> page = hasNext ? fetched.subList(0, criteria.limit()) : fetched;
            List<EffortOwnerRecordResponse> items = new ArrayList<>(page.size());
            page.forEach(entity -> items.add(mapper.toResponse(entity)));
            String nextCursor = null;
            if (hasNext) {
                EffortRecordEntity last = page.get(page.size() - 1);
                nextCursor = cursors.encode(ownerUserId, filterHash,
                        new EffortCursorCodec.Position(last.getRecordedOn(), last.getCreatedAt(), last.getId()));
            }
            return new EffortRecordPage(items, nextCursor, hasNext, criteria.limit());
        });
    }

    /** 本人の原本詳細。 */
    @Transactional(readOnly = true)
    public EffortOwnerRecordResponse get(Long ownerUserId, UUID id) {
        return metrics.measure("get", () -> mapper.toResponse(owned(ownerUserId, id)));
    }

    /** 原本を全置換更新する（version 一致必須。不一致は COMMON_003 / 409）。 */
    @Transactional
    public EffortOwnerRecordResponse update(Long ownerUserId, UUID id, UpdateEffortRecordRequest request, ZoneId userZone) {
        return metrics.measure("update", () -> {
            EffortRecordEntity entity = owned(ownerUserId, id);
            if (entity.getVersion() != request.version().longValue()) {
                throw new BusinessException(CommonErrorCode.COMMON_003);
            }
            validateRecordedOn(userZone, request.recordedOn());
            EffortValueValidator.Validated validated = validator.validate(
                    codec.parseSnapshot(entity.getDefinitionSnapshot()), request.values(), request.privateNote(), true);

            entity.setRecordedOn(request.recordedOn());
            entity.setValuesJson(codec.valuesJson(validated.values()));
            entity.setPrivateNote(validated.privateNote());
            entity.setContentRevision(entity.getContentRevision() + 1);
            entity.setUpdatedAt(now());
            EffortRecordEntity saved = records.saveAndFlush(entity);
            log.info("頑張り記録を更新: ownerUserId={}, recordId={}, version={}", ownerUserId, saved.getId(), saved.getVersion());
            return mapper.toResponse(saved);
        });
    }

    /** 原本を論理削除する（Idempotency-Key 必須、version 一致必須）。 */
    @Transactional
    public EffortDeleteResponse delete(Long ownerUserId, UUID id, long version, String idempotencyKey) {
        return metrics.measure("delete", () -> {
            UUID key = parseKey(idempotencyKey);
            if (version < 0) {
                throw invalid("version", "version は 0 以上で指定してください");
            }
            String path = "DELETE " + BASE_PATH + "/" + id;
            EffortReceiptService.Handle handle = receipts.begin(ownerUserId, path, key,
                    receipts.requestHash(path, "version=" + version));
            if (handle.replay() != null) {
                return fromDeleteJson(handle.replay().json());
            }

            EffortRecordEntity entity = owned(ownerUserId, id);
            if (entity.getVersion() != version) {
                throw new BusinessException(CommonErrorCode.COMMON_003);
            }
            Instant now = now();
            entity.setDeletedAt(now);
            entity.setUpdatedAt(now);
            EffortRecordEntity saved = records.saveAndFlush(entity);
            EffortDeleteResponse response = new EffortDeleteResponse(saved.getId(), saved.getVersion(), "DELETED");
            receipts.complete(handle.receiptId(), 200, toJson(response));
            events.publish(new EffortRecordDeletedEvent(ownerUserId, saved.getId(), saved.getVersion()));
            log.info("頑張り記録を削除: ownerUserId={}, recordId={}, version={}", ownerUserId, saved.getId(), saved.getVersion());
            return response;
        });
    }

    // ───────────────────────────── 内部 ─────────────────────────────

    /** 本人の原本（不在・他人・削除済み・purged は区別せず EFFORT_001）。 */
    private EffortRecordEntity owned(Long ownerUserId, UUID id) {
        return records.findByIdAndOwnerUserId(id, ownerUserId)
                .orElseThrow(() -> new BusinessException(EffortErrorCode.EFFORT_001));
    }

    /** 記録日は 1900-01-01〜本人 TZ の今日（親設計 §3.1）。 */
    private void validateRecordedOn(ZoneId userZone, LocalDate recordedOn) {
        if (recordedOn.isBefore(EffortValueValidator.MIN_RECORDED_ON)) {
            throw invalid("recordedOn", "記録日は 1900-01-01 以降で指定してください");
        }
        LocalDate today = LocalDate.now(clock.withZone(userZone));
        if (recordedOn.isAfter(today)) {
            throw invalid("recordedOn", "未来の日付は記録できません");
        }
    }

    /** DATETIME(6) と同じ精度へ丸める（応答の時刻が再読込と一致するように）。 */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static UUID parseKey(String raw) {
        if (raw == null || raw.length() != 36) {
            throw invalid("Idempotency-Key", "Idempotency-Key は UUID で指定してください");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw invalid("Idempotency-Key", "Idempotency-Key は UUID で指定してください");
        }
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(CommonErrorCode.COMMON_001, List.of(new ErrorResponse.FieldError(field, message)));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("直列化に失敗しました", e);
        }
    }

    private EffortOwnerRecordResponse fromJson(String json) {
        try {
            return objectMapper.readValue(json, EffortOwnerRecordResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("保存済みの応答を読み込めません", e);
        }
    }

    private EffortDeleteResponse fromDeleteJson(String json) {
        try {
            return objectMapper.readValue(json, EffortDeleteResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("保存済みの応答を読み込めません", e);
        }
    }
}
