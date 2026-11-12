package method.cases;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BCaseSelectionTest {
  private static CaseReader.CaseData candidate(String id, String subLibrary) {
    return new CaseReader.CaseData(id, "test", "description", "code", "hash", subLibrary);
  }

  private static List<CaseReader.CaseData> candidates(int initial, int post) {
    List<CaseReader.CaseData> cases = new ArrayList<>();
    for (int i = 1; i <= initial + post; i++) {
      cases.add(candidate(String.format("B-%03d", i), i <= initial ? "initial" : "post_reassignment"));
    }
    return cases;
  }

  @Test void keepsTotalFivePercentWithAtLeastTwoSelections() {
    assertEquals(2, BCaseSelection.requiredCount(candidates(1, 1)));
    assertEquals(2, BCaseSelection.requiredCount(candidates(19, 1)));
    assertEquals(2, BCaseSelection.requiredCount(candidates(20, 1)));
    assertEquals(2, BCaseSelection.requiredCount(candidates(39, 1)));
    assertEquals(3, BCaseSelection.requiredCount(candidates(40, 1)));
    assertEquals(5, BCaseSelection.requiredCount(candidates(50, 50)));
  }

  @Test void rejectsAnEmptySubLibraryBeforeRetrieval() {
    assertTrue(assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.requiredCount(candidates(0, 1))).getMessage().contains("initial"));
    assertTrue(assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.requiredCount(candidates(1, 0))).getMessage().contains("post_reassignment"));
    assertThrows(IllegalArgumentException.class, () -> BCaseSelection.requiredCount(List.of()));
  }

  @Test void acceptsOnlyDistinctCandidatesWithBothStagesRepresented() {
    var cases = candidates(40, 1);
    assertDoesNotThrow(() -> BCaseSelection.validateSelection(cases, List.of("B-001", "B-002", "B-041")));
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.validateSelection(cases, List.of("B-001", "B-002", "B-003")));
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.validateSelection(cases, List.of("B-001", "B-001", "B-041")));
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.validateSelection(cases, List.of("B-001", "B-002", "B-999")));
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.validateSelection(cases, List.of("B-001", "B-041")));
    assertDoesNotThrow(() -> BCaseSelection.validateSelection(candidates(50, 50),
        List.of("B-001", "B-002", "B-003", "B-051", "B-052")));
  }

  @Test void rejectsUnclassifiedWrongModuleAndDuplicateCandidates() {
    var initial = candidate("B-001", "initial");
    var post = candidate("B-002", "post_reassignment");
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.requiredCount(List.of(initial, post, candidate("B-003", null))));
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.requiredCount(List.of(initial, post, candidate("A-001", "initial"))));
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.requiredCount(List.of(initial, post, candidate("B-001", "post_reassignment"))));
  }

  @Test void worksWithLocallyReadStageCasesAndRejectsUnclassifiedMaterial() throws Exception {
    var initial = CaseReader.read(Path.of("cases", "B", "initial_single_satellite_optimization",
        "B-004", "B-004.json"));
    var post = CaseReader.read(Path.of("cases", "B", "post_reassignment_adjustment",
        "B-031", "B-031.json"));
    var reference = CaseReader.read(Path.of("cases", "backend", "legacy_B_cases", "B-001", "B-001.json"));
    var localFixtures = List.of(initial, post);
    assertEquals(2, BCaseSelection.requiredCount(localFixtures));
    assertDoesNotThrow(() -> BCaseSelection.validateSelection(localFixtures, List.of("B-004", "B-031")));
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.requiredCount(List.of(initial, post, reference)));
  }
}
