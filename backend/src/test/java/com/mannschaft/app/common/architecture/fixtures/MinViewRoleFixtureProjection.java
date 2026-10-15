package com.mannschaft.app.common.architecture.fixtures;

import com.mannschaft.app.schedule.MinViewRole;

/**
 * fixture: {@code min_view_role} 列を<b>射影に持っている</b>予定射影の複製。
 *
 * <p>CMP-017b 番人のメタテスト用。blind / aware いずれの fixture Resolver も本射影を受け取るため、
 * 「列が無いから読めない」ではなく「<b>列はあるのに読まない</b>」を切り分けて検証できる。</p>
 *
 * @param id          予定 ID
 * @param minViewRole 閲覧閾値
 */
public record MinViewRoleFixtureProjection(Long id, MinViewRole minViewRole) {
}
