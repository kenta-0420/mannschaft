package com.mannschaft.app.schedule.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * F03.16 予定コメントの GDPR エクスポート用エントリ（設計書 §3.3 / AC-35）。
 *
 * <p>{@code gdpr} ドメインの {@code PersonalDataCollector} が {@code schedule} ドメインの
 * {@code ScheduleCommentEntity} を直接参照すると ArchUnit 番人 {@code CrossDomainEntityImportArchTest}
 * （D-1）に抵触するため、本 DTO を介して受け渡す（DTO は {@code ..entity..} パッケージ外のため対象外）。
 * {@code ScheduleCommentService} が保有するデータのみをプリミティブ／DTO として渡し、Entity は
 * ドメイン外へ一切漏らさない。</p>
 *
 * @param id        コメントID
 * @param scheduleId 親予定ID
 * @param body      本文
 * @param isEdited  編集済みフラグ
 * @param createdAt 作成日時
 * @param updatedAt 更新日時
 */
public record ScheduleCommentPersonalDataEntry(
        UUID id, Long scheduleId, String body, Boolean isEdited, Instant createdAt, Instant updatedAt) {
}
