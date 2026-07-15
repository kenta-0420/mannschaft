package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.CalendarEventCreateRequest;
import com.mannschaft.app.village.dto.CalendarEventListResponse;
import com.mannschaft.app.village.dto.CalendarEventResponse;
import com.mannschaft.app.village.dto.CalendarEventUpdateRequest;
import com.mannschaft.app.village.entity.VillageCalendarEventEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageCalendarEventRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * F17.1 Phase 2 U4 — 村歳時記カレンダー Service（設計書 §2.2）。
 *
 * <p>桃の節句・七夕・年越し等の年中行事および単発イベントを村単位で管理する。
 * RFC 5545 RRULE は導入せず「毎年繰返／単発」の二択のみ。</p>
 *
 * <p>担当 API:</p>
 * <ul>
 *   <li>{@code POST   /api/v1/villages/{vid}/calendar-events} — 作成（HEADMAN / ELDER のみ）</li>
 *   <li>{@code PATCH  /api/v1/villages/{vid}/calendar-events/{eid}} — 更新（同上）</li>
 *   <li>{@code DELETE /api/v1/villages/{vid}/calendar-events/{eid}} — 論理削除（同上）</li>
 *   <li>{@code GET    /api/v1/villages/{vid}/calendar-events?year=&month=} — 月別取得（村人）</li>
 *   <li>{@code GET    /api/v1/villages/{vid}/calendar-events/{eid}} — 詳細取得（村人）</li>
 * </ul>
 *
 * <p>アーキテクチャ原則:</p>
 * <ul>
 *   <li>原則1: {@code created_by_user_id} に FK を張らない（U2 既対応）</li>
 *   <li>原則5: {@code @Transactional} は village ドメイン内に閉じる</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageCalendarService {

    /** カラーコード形式（#RRGGBB の 16 進）。 */
    private static final Pattern COLOR_HEX_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final VillageRepository villageRepository;
    private final VillageCalendarEventRepository calendarRepository;
    private final VillageMembershipRepository membershipRepository;

    // ========================================================================
    // 作成
    // ========================================================================

    /**
     * 歳時記イベントを作成する。HEADMAN / ELDER のみ実行可。
     *
     * <ul>
     *   <li>期間検証: {@code eventEndDate < eventDate} なら {@link VillageErrorCode#CALENDAR_EVENT_INVALID_DATE_RANGE}</li>
     *   <li>カラー検証: {@code #RRGGBB} 以外なら {@link VillageErrorCode#CALENDAR_EVENT_INVALID_COLOR}</li>
     * </ul>
     */
    @Transactional
    public CalendarEventResponse createEvent(UUID villageId,
                                              CalendarEventCreateRequest request,
                                              Long actorUserId) {
        loadActiveVillage(villageId);
        requireModerator(villageId, actorUserId);

        validateDateRange(request.eventDate(), request.eventEndDate());
        validateColor(request.colorHex());

        VillageCalendarEventEntity entity = VillageCalendarEventEntity.builder()
                .villageId(villageId)
                .title(request.title())
                .description(request.description())
                .eventDate(request.eventDate())
                .eventEndDate(request.eventEndDate())
                .isAnnualRecurring(Boolean.TRUE.equals(request.isAnnualRecurring()))
                .iconEmoji(request.iconEmoji())
                .colorHex(request.colorHex())
                .createdByUserId(actorUserId)
                .build();
        VillageCalendarEventEntity saved = calendarRepository.save(entity);
        log.info("Village calendar event created: villageId={} eventId={} title={} recurring={}",
                villageId, saved.getId(), request.title(), saved.getIsAnnualRecurring());
        return CalendarEventResponse.from(saved);
    }

    // ========================================================================
    // 更新
    // ========================================================================

    /**
     * 歳時記イベントを部分更新する。HEADMAN / ELDER のみ実行可。
     * リクエストで {@code null} が指定されたフィールドは変更しない。
     */
    @Transactional
    public CalendarEventResponse updateEvent(UUID villageId,
                                              UUID eventId,
                                              CalendarEventUpdateRequest request,
                                              Long actorUserId) {
        loadActiveVillage(villageId);
        requireModerator(villageId, actorUserId);

        VillageCalendarEventEntity entity = loadActiveEvent(villageId, eventId);

        // 日付検証は更新後の値で行う（部分更新後の整合性チェック）
        LocalDate newStart = request.eventDate() != null ? request.eventDate() : entity.getEventDate();
        LocalDate newEnd = request.eventEndDate() != null ? request.eventEndDate() : entity.getEventEndDate();
        validateDateRange(newStart, newEnd);

        if (request.colorHex() != null) {
            validateColor(request.colorHex());
        }

        if (request.title() != null) {
            entity.setTitle(request.title());
        }
        if (request.description() != null) {
            entity.setDescription(request.description());
        }
        if (request.eventDate() != null) {
            entity.setEventDate(request.eventDate());
        }
        if (request.eventEndDate() != null) {
            entity.setEventEndDate(request.eventEndDate());
        }
        if (request.isAnnualRecurring() != null) {
            entity.setIsAnnualRecurring(request.isAnnualRecurring());
        }
        if (request.iconEmoji() != null) {
            entity.setIconEmoji(request.iconEmoji());
        }
        if (request.colorHex() != null) {
            entity.setColorHex(request.colorHex());
        }

        VillageCalendarEventEntity saved = calendarRepository.save(entity);
        log.info("Village calendar event updated: villageId={} eventId={}", villageId, eventId);
        return CalendarEventResponse.from(saved);
    }

    // ========================================================================
    // 削除（論理）
    // ========================================================================

    /**
     * 歳時記イベントを論理削除する。HEADMAN / ELDER のみ実行可。
     */
    @Transactional
    public void deleteEvent(UUID villageId, UUID eventId, Long actorUserId) {
        loadActiveVillage(villageId);
        requireModerator(villageId, actorUserId);

        VillageCalendarEventEntity entity = loadActiveEvent(villageId, eventId);
        entity.setDeletedAt(LocalDateTime.now());
        calendarRepository.save(entity);
        log.info("Village calendar event deleted: villageId={} eventId={}", villageId, eventId);
    }

    // ========================================================================
    // 取得
    // ========================================================================

    /**
     * 指定月の歳時記イベント一覧を取得する。
     *
     * <p>該当条件:</p>
     * <ul>
     *   <li>{@code is_annual_recurring=true}: {@code MONTH(event_date)=:month} のみで判定</li>
     *   <li>{@code is_annual_recurring=false}: {@code YEAR(event_date)=:year AND MONTH(event_date)=:month}</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public CalendarEventListResponse listEventsByMonth(UUID villageId, int year, int month) {
        loadActiveVillage(villageId);

        if (month < 1 || month > 12) {
            throw new BusinessException(VillageErrorCode.VILLAGE_FIELD_INVALID);
        }

        List<VillageCalendarEventEntity> events = calendarRepository.findByMonth(villageId, year, month);
        List<CalendarEventResponse> items = events.stream().map(CalendarEventResponse::from).toList();
        return new CalendarEventListResponse(items, year, month);
    }

    /** 歳時記イベント詳細を取得する。論理削除済みは {@link VillageErrorCode#CALENDAR_EVENT_NOT_FOUND}。 */
    @Transactional(readOnly = true)
    public CalendarEventResponse getEvent(UUID villageId, UUID eventId) {
        loadActiveVillage(villageId);
        VillageCalendarEventEntity entity = loadActiveEvent(villageId, eventId);
        return CalendarEventResponse.from(entity);
    }

    // ========================================================================
    // バリデーション
    // ========================================================================

    /** 開始日 > 終了日のときに {@link VillageErrorCode#CALENDAR_EVENT_INVALID_DATE_RANGE}。 */
    private void validateDateRange(LocalDate eventDate, LocalDate eventEndDate) {
        if (eventEndDate != null && eventDate != null && eventEndDate.isBefore(eventDate)) {
            throw new BusinessException(VillageErrorCode.CALENDAR_EVENT_INVALID_DATE_RANGE);
        }
    }

    /** {@code #RRGGBB} 形式以外のとき {@link VillageErrorCode#CALENDAR_EVENT_INVALID_COLOR}。null は許容。 */
    private void validateColor(String colorHex) {
        if (colorHex == null || colorHex.isEmpty()) {
            return;
        }
        if (!COLOR_HEX_PATTERN.matcher(colorHex).matches()) {
            throw new BusinessException(VillageErrorCode.CALENDAR_EVENT_INVALID_COLOR);
        }
    }

    // ========================================================================
    // 共通ヘルパ
    // ========================================================================

    /** 有効な村を取得する（削除/凍結済みは VILLAGE_001 / VILLAGE_027 で扱う）。 */
    private VillageEntity loadActiveVillage(UUID villageId) {
        VillageEntity v = villageRepository.findById(villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));
        if (v.getDeletedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
        if (v.getArchivedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_ALREADY_ARCHIVED);
        }
        return v;
    }

    /**
     * 当該イベントを取得する。村跨ぎ IDOR を防ぐため {@code villageId} 一致まで検証する。
     * 論理削除済み（{@code deletedAt!=null}）も 404 扱いとする。
     */
    private VillageCalendarEventEntity loadActiveEvent(UUID villageId, UUID eventId) {
        VillageCalendarEventEntity entity = calendarRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.CALENDAR_EVENT_NOT_FOUND));
        if (entity.getDeletedAt() != null || !entity.getVillageId().equals(villageId)) {
            throw new BusinessException(VillageErrorCode.CALENDAR_EVENT_NOT_FOUND);
        }
        return entity;
    }

    /**
     * 当該ユーザーが対象村の<strong>現役</strong>モデレーター（HEADMAN / ELDER）であることを要求する。
     * 一般村人・非村人・退村済み・BAN 済みは {@link VillageErrorCode#MODERATION_FORBIDDEN}（403）。
     *
     * <p>BAN / 退村の検査は {@code findActiveByVillageIdAndSubject} のクエリに委譲する（#2284 §12）。
     * 従来はここで手書きの {@code bannedAt != null} 分岐を持っていたが、同じ判定が村ドメイン全体に
     * コピーされ 5 実装で書き忘れられていた。述語をクエリ 1 箇所に寄せ、書き忘れの余地を無くす。</p>
     */
    private VillageMembershipEntity requireModerator(UUID villageId, Long actorUserId) {
        VillageMembershipEntity m = membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, actorUserId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN));
        if (m.getRole() != VillageRole.HEADMAN && m.getRole() != VillageRole.ELDER) {
            throw new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN);
        }
        return m;
    }
}
