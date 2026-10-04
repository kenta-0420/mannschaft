package fixture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** canonical番人の登録品質は既Self12で担保し、このfixtureはTest選択と実graphだけを測る。 */
class GuardProbeTest {
    @Test void ownedStoreIsPresent() throws Exception {
        assertEquals("owned\n", Files.readString(Path.of(System.getProperty("fixture.dir"), "store")));
    }
    @Test void explicitFailure() {
        assertFalse(Boolean.getBoolean("guard.fail"), "OWNED_FIXTURE_GUARD_FAILURE");
    }
}
