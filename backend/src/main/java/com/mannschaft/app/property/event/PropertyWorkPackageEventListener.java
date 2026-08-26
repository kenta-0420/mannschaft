package com.mannschaft.app.property.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.incident.entity.IncidentEntity;
import com.mannschaft.app.incident.event.IncidentStatusChangedEvent;
import com.mannschaft.app.incident.repository.IncidentRepository;
import com.mannschaft.app.property.service.PropertyWorkPackageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * F09.13 物件履歴台帳 — F07.6 Incident イベントリスナー。
 *
 * <p>F07.6 が発行する {@link IncidentStatusChangedEvent} を受信し、ステータスが
 * {@code "CONFIRMED"} に遷移したタイミングで物件履歴パッケージを自動生成する。
 * 設計書 §5.2「F07.6 Incident との連携」に準拠する。</p>
 *
 * <p><strong>依存方向</strong>（設計書 §8）:</p>
 * <ul>
 *   <li>F07.6 → F09.13 の単方向依存を維持（F07.6 から F09.13 のクラスは参照しない）</li>
 *   <li>F09.13 側のリスナーがイベントを購読し、F07.6 IncidentService を変更しない</li>
 * </ul>
 *
 * <p><strong>判定条件</strong>:</p>
 * <ul>
 *   <li>{@code event.newStatus()} が {@code "CONFIRMED"} の場合のみ動作</li>
 *   <li>{@link PropertyWorkPackageService#createFromIncident} が重複チェック
 *       （同一 incidentId のパッケージが既存ならスキップ）を担う</li>
 * </ul>
 *
 * <p><strong>独自判断</strong>: 設計書 §5.2 では {@code incidentDate=incident.reportedAt}・
 * {@code incidentNarrative=incident.summary} を要求しているが、現行 {@link IncidentEntity}
 * には {@code reportedAt}/{@code summary} のフィールドが存在しない。代替として:</p>
 * <ul>
 *   <li>{@code incidentDate} ← {@code incident.getCreatedAt().toLocalDate()}（BaseEntity の作成日時）</li>
 *   <li>{@code incidentNarrative} ← {@code incident.getDescription()}（インシデント詳細記述）</li>
 * </ul>
 * <p>を採用する。これは現行 Entity 構造での最も意味的に近い代替であり、後続フェーズで
 * F07.6 Entity に {@code reportedAt}/{@code summary} カラムが追加された際に
 * 本リスナーを更新する想定である。</p>
 *
 * <p><strong>トランザクション分離</strong>: {@code REQUIRES_NEW} を採用し、F07.6 の
 * IncidentService トランザクションとは別トランザクションでパッケージ生成を行う。
 * これにより F09.13 側の例外が F07.6 の status 変更をロールバックさせない（疎結合）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PropertyWorkPackageEventListener {

    /** F07.6 IncidentStatus.CONFIRMED の文字列値（IncidentService.changeStatus は文字列を受け取るため）。 */
    private static final String INCIDENT_STATUS_CONFIRMED = "CONFIRMED";

    /**
     * F07.6 が確定済（CONFIRMED）になった事故のみ作成元として扱う旨を示すシステムユーザー ID。
     *
     * <p>本来はリスナー駆動の自動生成のため、created_by に「システムユーザー」
     * 専用の固定 ID を割り当てたいが、本プロジェクトに専用システムユーザー ID 定数は
     * 確認できなかった。代替として {@code event.getChangedBy()}（status 変更を実行した
     * ユーザー = 通常は ADMIN）を {@code created_by} として記録する。
     * この方針は {@code property_work_packages.created_by} の FK 制約 ON DELETE RESTRICT
     * とも整合する（実在ユーザー ID を確実に渡せる）。</p>
     */
    private final PropertyWorkPackageService propertyWorkPackageService;
    private final IncidentRepository incidentRepository;

    /**
     * F07.6 Incident のステータス変更イベントを受信し、CONFIRMED への遷移時のみ
     * パッケージを自動生成する。
     *
     * <p>例外が発生した場合はログに記録するのみで、F07.6 側の処理を阻害しない
     * （{@code REQUIRES_NEW} + 内部 try-catch）。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
            gateKeys = "FEATURE_PROPERTY_REPAIRPLAN_ENABLED",
            reason = "失われるのは工事パッケージの自動生成のみで、元のインシデントは正本として残るため再開後に手動または再発火で生成できる")
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onIncidentStatusChanged(IncidentStatusChangedEvent event) {
        if (event == null || !INCIDENT_STATUS_CONFIRMED.equals(event.getNewStatus())) {
            return;
        }
        try {
            handleConfirmed(event);
        } catch (RuntimeException e) {
            log.error("F07.6 Incident 確定 → パッケージ自動生成 失敗: incidentId={}",
                    event.getIncidentId(), e);
            // F07.6 側のトランザクションは独立しているため、ここでの例外は呑む（伝播させない）。
        }
    }

    /**
     * 実際のパッケージ生成処理。Incident 取得 → 重複チェック → 自動生成。
     */
    private void handleConfirmed(IncidentStatusChangedEvent event) {
        Long incidentId = event.getIncidentId();
        Optional<IncidentEntity> incidentOpt = incidentRepository.findByIdAndDeletedAtIsNull(incidentId);
        if (incidentOpt.isEmpty()) {
            // 既に削除済 or 不存在の Incident は何もしない（fail-safe）
            log.warn("F07.6 Incident 確定イベント受信したが Incident が見つからない: incidentId={}",
                    incidentId);
            return;
        }
        IncidentEntity incident = incidentOpt.get();

        // §5.2 の要求項目で現行 Entity に存在しないものは createdAt / description で代替（本クラス JavaDoc 参照）
        Optional<com.mannschaft.app.property.entity.PropertyWorkPackageEntity> created =
                propertyWorkPackageService.createFromIncident(
                        incidentId,
                        incident.getScopeType(),
                        incident.getScopeId(),
                        event.getChangedBy(),
                        incident.getTitle(),
                        incident.getCreatedAt() != null ? incident.getCreatedAt().toLocalDate() : null,
                        incident.getDescription());

        if (created.isPresent()) {
            log.info("F07.6 Incident → 物件履歴パッケージ自動生成成功: incidentId={}, packageId={}",
                    incidentId, created.get().getId());
        }
    }
}
