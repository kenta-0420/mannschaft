package fixture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Archルールの代用ではなく、実Test task失敗とfinalizer配線だけを試す。 */
class ArchProbeTest {
    @Test void actualTestTaskFailure() {
        assertFalse(Boolean.getBoolean("arch.fail"), "OWNED_FIXTURE_ARCH_FAILURE");
    }
}
