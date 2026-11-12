package method.cases;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 已过滤的B候选集的数量与子库覆盖检查；不判断来源使用条件。 */
public final class BCaseSelection {
  private BCaseSelection() { }

  /** 在两个子库均非空时计算B总配额；调用方须先完成实际候选过滤。 */
  public static int requiredCount(List<CaseReader.CaseData> eligibleCandidates) {
    return analyze(eligibleCandidates).requiredCount();
  }

  /** 校验实际响应；不补选、截断或接受候选集之外的ID。 */
  public static void validateSelection(List<CaseReader.CaseData> eligibleCandidates, List<String> selectedIds) {
    CandidateSet candidates = analyze(eligibleCandidates);
    if (selectedIds == null || selectedIds.size() != candidates.requiredCount()) {
      throw new IllegalArgumentException("B selection count does not match required count");
    }
    Set<String> seen = new HashSet<>();
    boolean initial = false;
    boolean postReassignment = false;
    for (String id : selectedIds) {
      if (id == null || !candidates.subLibraries().containsKey(id)) {
        throw new IllegalArgumentException("B selection contains an ID outside the candidate set");
      }
      if (!seen.add(id)) throw new IllegalArgumentException("B selection contains a duplicate ID: " + id);
      String subLibrary = candidates.subLibraries().get(id);
      if (subLibrary.equals("initial")) initial = true;
      else postReassignment = true;
    }
    if (!initial || !postReassignment) {
      throw new IllegalArgumentException("B selection must cover both sub-libraries");
    }
  }

  private static CandidateSet analyze(List<CaseReader.CaseData> eligibleCandidates) {
    if (eligibleCandidates == null) throw new IllegalArgumentException("B candidate list is null");
    Map<String, String> subLibraries = new LinkedHashMap<>();
    int initial = 0;
    int postReassignment = 0;
    for (CaseReader.CaseData candidate : eligibleCandidates) {
      if (candidate == null || candidate.caseId() == null || !candidate.caseId().matches("B-\\d{3,}")) {
        throw new IllegalArgumentException("B candidate has an invalid case ID");
      }
      String subLibrary = candidate.subLibrary();
      if (!"initial".equals(subLibrary) && !"post_reassignment".equals(subLibrary)) {
        throw new IllegalArgumentException("B candidate is not assigned to an eligible sub-library: "
            + candidate.caseId());
      }
      if (subLibraries.putIfAbsent(candidate.caseId(), subLibrary) != null) {
        throw new IllegalArgumentException("B candidate ID is duplicated: " + candidate.caseId());
      }
      if (subLibrary.equals("initial")) initial++;
      else postReassignment++;
    }
    if (initial == 0 || postReassignment == 0) {
      String missing = initial == 0 && postReassignment == 0 ? "initial, post_reassignment"
          : initial == 0 ? "initial" : "post_reassignment";
      throw new IllegalArgumentException("B sub-library has no eligible candidates: " + missing);
    }
    int required = (int) Math.max(2L, (subLibraries.size() + 19L) / 20L);
    return new CandidateSet(subLibraries, required);
  }

  private record CandidateSet(Map<String, String> subLibraries, int requiredCount) { }
}
