package com.mannschaft.app.common.architecture.fixtures.selfscopedinput;

import com.mannschaft.app.common.security.SelfScopedEndpoint;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * AC-1 / AC-12 の検体: 完全修飾名で書いた {@code @SelfScopedEndpoint}。
 *
 * <p>ソースの文字列走査（{@code SelfScopedEndpointMarkerGuardTest#extractTargets}）は
 * {@code @SelfScopedEndpoint} というトークンしか拾わないため、完全修飾名の付与を見落とす。
 * バイトコードでは両方とも見えるので、件数の突き合わせで食い違いとして赤になる。
 * 比較のため、単純名で付与したメソッドも1つ置く（こちらは両方の走査に出る）。</p>
 */
class FqnAnnotatedSpecimenController {

    private SpecimenAvailabilityService service;

    @com.mannschaft.app.common.security.SelfScopedEndpoint("検体: 完全修飾名で付与し、teamId を受け取る")
    Object byFqn(@RequestParam Long teamId) {
        return service.getDefaults(1L, teamId);
    }

    @SelfScopedEndpoint("検体: 単純名で付与し、入力を受け取らない")
    Object bySimpleName() {
        return null;
    }
}
