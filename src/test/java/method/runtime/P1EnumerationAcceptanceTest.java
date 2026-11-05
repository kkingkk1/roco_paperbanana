package method.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class P1EnumerationAcceptanceTest {
  @Test void eightManualCombinationsPassThreeIndependentEnumerationFixtures() {
    assertEquals(24, P1EnumerationAudit.runAll().size());
  }
}
