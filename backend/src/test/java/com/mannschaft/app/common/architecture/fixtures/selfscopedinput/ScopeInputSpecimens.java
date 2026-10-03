package com.mannschaft.app.common.architecture.fixtures.selfscopedinput;

import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.membership.domain.ScopeType;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * {@code SelfScopedEndpointScopeInputGuardTest} の検体群（AC-11〜13、G7）。
 *
 * <p>各クラスは Controller の形だけを真似た素のクラスで、{@code @RestController} を付けない
 * （テストの Spring コンテキストに拾われないため）。番人は {@code @SelfScopedEndpoint} の付与で
 * 対象を見つけるので、Controller 注釈の有無は判定に関係しない。</p>
 */
final class ScopeInputSpecimens {

    private ScopeInputSpecimens() {
    }
}

/** AC-11: 是正前のシフト曜日既定（{@code @RequestParam teamId} → findByUserIdAndTeamId）。赤。 */
class ShiftDefaultsReplicaSpecimenController {

    private SpecimenAvailabilityService service;

    @SelfScopedEndpoint("検体: 是正前のシフト曜日既定の再現（userId と teamId の組で検索する）")
    Object getAvailabilityDefaults(@RequestParam Long teamId) {
        return service.getDefaults(1L, teamId);
    }
}

/** AC-12: 名前を差し替えた {@code @PathVariable("teamId") Long id}。赤。 */
class RenamedPathVariableSpecimenController {

    private SpecimenAvailabilityService service;

    @SelfScopedEndpoint("検体: 引数名は id だが URL 上の名前は teamId である")
    Object get(@PathVariable("teamId") Long id) {
        return service.getDefaults(1L, id);
    }
}

/** AC-3: {@code required = false} の {@code organizationId}。赤。 */
class OptionalRequestParamSpecimenController {

    private SpecimenAvailabilityService service;

    @SelfScopedEndpoint("検体: 省略可能な organizationId を受け取る")
    Object list(@RequestParam(required = false) Long organizationId) {
        return service.getDefaults(1L, organizationId);
    }
}

/** AC-12: 本文の入れ子にある scopeId。赤。 */
class NestedBodySpecimenController {

    @SelfScopedEndpoint("検体: 本文の destination.scopeId を受け取る")
    Object create(@RequestBody SpecimenNestedBody body) {
        return body.title();
    }
}

/** AC-12: 本文の {@code List<UUID> orderedVillageIds}。赤。 */
class ReorderBodySpecimenController {

    @SelfScopedEndpoint("検体: 本文の orderedVillageIds（UUID の列）を受け取る")
    Object reorder(@RequestBody SpecimenReorderBody body) {
        return body.orderedVillageIds();
    }
}

/** G7: {@code @ModelAttribute} の入れ子にある scopeId。赤。 */
class ModelAttributeSpecimenController {

    @SelfScopedEndpoint("検体: 検索フォームの filter.scopeId を受け取る")
    Object search(@ModelAttribute SpecimenSearchForm form) {
        return form;
    }
}

/** AC-3: 入れ子が深さ上限を超える本文。赤（深さ超過）。 */
class DeepBodySpecimenController {

    @SelfScopedEndpoint("検体: 4 段の入れ子の本文を受け取る（深さ上限の超過）")
    Object create(@RequestBody SpecimenDeepBody body) {
        return body;
    }
}

/** AC-3: 型変数のフィールドを持つ本文。赤（型解決不能）。 */
class GenericBodySpecimenController {

    @SelfScopedEndpoint("検体: 型変数 T のフィールドを持つ本文を受け取る")
    Object create(@RequestBody SpecimenGenericBody<String> body) {
        return body;
    }
}

/** AC-13: 台帳と完全一致する行を持つ検体（find → delete）。緑。 */
class LedgeredPinSpecimenController {

    private SpecimenPinService service;

    @SelfScopedEndpoint("検体: 自分のピン行を (userId, villageId) で引いて消すだけ")
    void unpin(@PathVariable UUID villageId) {
        service.unpin(1L, villageId);
    }
}

/** AC-12: 台帳行は上と同じだが、Service に save を足したもの。赤。 */
class DriftedPinSpecimenController {

    private SpecimenDriftedPinService service;

    @SelfScopedEndpoint("検体: 台帳行のあとで Service に save が足された")
    void unpin(@PathVariable UUID villageId) {
        service.unpin(1L, villageId);
    }
}

/** AC-9: 台帳行はあるが、Repository 呼び出しが深さ上限の先にある。赤（未探索）。 */
class DeepChainSpecimenController {

    private SpecimenChainService service;

    @SelfScopedEndpoint("検体: Repository への到達が深さ 6（上限の先）にある")
    long count(@PathVariable UUID villageId) {
        return service.level1(1L);
    }
}

/** AC-13: 入力なし。緑。 */
class NoInputSpecimenController {

    @SelfScopedEndpoint("検体: 入力を受け取らない（認証主体だけで決まる）")
    Object listMine() {
        return null;
    }
}

/** AC-13: スコープ種別だけを受け取る（種別単独は対象外）。緑。 */
class ScopeTypeOnlySpecimenController {

    @SelfScopedEndpoint("検体: スコープ種別だけを受け取り、スコープIDは受け取らない")
    Object listMine(@RequestParam ScopeType scopeType, @RequestParam String scopeKind) {
        return scopeType;
    }
}

/** AC-13: リソースID（messageId）だけを受け取る（リソースIDは対象外）。緑。 */
class MessageIdSpecimenController {

    @SelfScopedEndpoint("検体: リソースIDの messageId だけを受け取る")
    Object get(@PathVariable Long messageId) {
        return messageId;
    }
}
