package com.mannschaft.app.loyalty;

import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.AccessControlService.TeamUserKey;
import com.mannschaft.app.membership.service.CheckinLocationService;
import com.mannschaft.app.team.service.TeamService;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

/** ページ内のIDを重複排除し、各公開契約を一度ずつ照会する非TX部品。 */
@Component
@RequiredArgsConstructor
public class LoyaltyQueryExternalStates {
    private final TeamService teams;
    private final UserService users;
    private final CheckinLocationService locations;
    private final AccessControlService memberships;
    private final MessageSource messages;

    public Snapshot read(Collection<Long> teamIds, Collection<Long> userIds,
            Collection<Long> locationIds, Collection<TeamUserKey> keys) {
        var locale = LocaleContextHolder.getLocale();
        return new Snapshot(teamIds.isEmpty() ? Map.of() : teams.getActiveStates(Set.copyOf(teamIds)),
                userIds.isEmpty() ? Map.of() : users.getActiveDisplayStates(Set.copyOf(userIds)),
                locationIds.isEmpty() ? Map.of() : locations.getTeamLocationStates(Set.copyOf(locationIds)),
                keys.isEmpty() ? Map.of() : memberships.getTeamMembershipStates(Set.copyOf(keys)),
                messages.getMessage("loyalty.requests.closedTeam", null, locale),
                messages.getMessage("loyalty.requests.deletedUser", null, locale),
                messages.getMessage("loyalty.requests.closedLocation", null, locale));
    }

    public record Snapshot(Map<Long, TeamService.ActiveState> teams,
            Map<Long, UserService.ActiveDisplayState> users,
            Map<Long, CheckinLocationService.TeamLocationState> locations,
            Map<TeamUserKey, AccessControlService.TeamMembershipState> memberships,
            String closedTeamName, String deletedUserName, String closedLocationName) {
        public boolean teamActive(Long id) { var s = teams.get(id); return s != null && s.active(); }
        public boolean userActive(Long id) { var s = users.get(id); return s != null && s.active(); }
        public boolean member(Long team, Long user) {
            var s = memberships.get(new TeamUserKey(team, user)); return s != null && s.active();
        }
        public boolean regular(Long team, Long user) {
            var s = memberships.get(new TeamUserKey(team, user));
            return s != null && s.active() && s.regularMemberOrAbove();
        }
        public String teamName(Long id) {
            var s = teams.get(id);
            return label(s != null && s.active() ? s.displayName() : null, closedTeamName);
        }
        public String userName(Long id) {
            var s = users.get(id);
            return label(s != null && s.active() ? s.displayName() : null, deletedUserName);
        }
        public String locationName(Long id) {
            var s = locations.get(id);
            return label(s != null && s.active() ? s.name() : null, closedLocationName);
        }
        private String label(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    }
}
