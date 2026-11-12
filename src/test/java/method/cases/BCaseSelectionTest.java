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
    for (int i = 1; i <= initial; i++) cases.add(candidate(String.format("BI-%03d", i), "initial"));
    for (int i = 1; i <= post; i++) cases.add(candidate(String.format("BR-%03d", i), "post_reassignment"));
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
    assertDoesNotThrow(() -> BCaseSelection.validateSelection(cases, List.of("BI-001", "BI-002", "BR-001")));
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.validateSelection(cases, List.of("BI-001", "BI-002", "BI-003")));
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.validateSelection(cases, List.of("BI-001", "BI-001", "BR-001")));
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.validateSelection(cases, List.of("BI-001", "BI-002", "BR-999")));
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.validateSelection(cases, List.of("BI-001", "BR-001")));
    assertDoesNotThrow(() -> BCaseSelection.validateSelection(candidates(50, 50),
        List.of("BI-001", "BI-002", "BI-003", "BR-001", "BR-002")));
  }

  @Test void rejectsUnclassifiedWrongModuleAndDuplicateCandidates() {
    var initial = candidate("BI-001", "initial");
    var post = candidate("BR-001", "post_reassignment");
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.requiredCount(List.of(initial, post, candidate("BI-003", null))));
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.requiredCount(List.of(initial, post, candidate("A-001", "initial"))));
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.requiredCount(List.of(initial, post, candidate("BI-001", "initial"))));
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.requiredCount(List.of(initial, post, candidate("BI-002", "post_reassignment"))));
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.requiredCount(List.of(initial, post, candidate("BR-002", "initial"))));
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.requiredCount(List.of(initial, post, candidate("B-004", "initial"))));
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.requiredCount(List.of(initial, post, candidate("BI-000", "initial"))));
  }

  @Test void worksWithLocallyReadStageCasesAndRejectsUnclassifiedMaterial() throws Exception {
    var initial = CaseReader.read(Path.of("cases", "B", "initial_single_satellite_optimization",
        "BI-001", "BI-001.json"));
    var post = CaseReader.read(Path.of("cases", "B", "post_reassignment_adjustment",
        "BR-001", "BR-001.json"));
    var reference = CaseReader.read(Path.of("cases", "backend", "legacy_B_cases", "B-001", "B-001.json"));
    var localFixtures = List.of(initial, post);
    assertEquals(2, BCaseSelection.requiredCount(localFixtures));
    assertDoesNotThrow(() -> BCaseSelection.validateSelection(localFixtures, List.of("BI-001", "BR-001")));
    assertThrows(IllegalArgumentException.class,
        () -> BCaseSelection.requiredCount(List.of(initial, post, reference)));
  }
}
