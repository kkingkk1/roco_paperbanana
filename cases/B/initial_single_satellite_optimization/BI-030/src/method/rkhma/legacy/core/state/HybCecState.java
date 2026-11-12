package method.rkhma.legacy.core.state;

import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.algorithm.alns.ALNS;
import method.rkhma.legacy.algorithm.workspace.ABCWorkspace;
import method.rkhma.legacy.algorithm.workspace.ACOWorkspace;
import method.rkhma.legacy.algorithm.workspace.DEWorkspace;
import method.rkhma.legacy.algorithm.workspace.EDAWorkspace;
import method.rkhma.legacy.algorithm.workspace.GAWorkspace;
import method.rkhma.legacy.algorithm.workspace.PSOWorkspace;
import method.rkhma.legacy.algorithm.workspace.SAWorkspace;
import method.rkhma.legacy.algorithm.workspace.SHADEWorkspace;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * HybCec State Manager
 *
 * Centralized management of all runtime states of HybCec, including:
 * - population and fitness
 * - best solutions (pbest, gbest)
 * - algorithm-specific states (ABC, SA, SHADE, etc.)
 * - iteration counting and recording
 * - loop-related temporary variables
 *
 * By centralizing state management, you can:
 * 1. Provide a unified state access interface
 * 2. Facilitate state validation and snapshots
 * 3. Reduce the complexity of the HybCec class
 * 4. Facilitate dimension testing
 */
public class HybCecState implements Serializable {

  private static final long serialVersionUID = 1L;

  // ==========================================================
  // Constants
  // ==========================================================

  /** Number of parent algorithms */
  public static final int FLAG_PAR_SIZE = AlgorithmConstants.HYBCEC_PARENT_ALGORITHM_COUNT;

  /** Number of operation levels */
  public static final int OPERATION_LEVEL_SIZE = 2;

  // ==========================================================
  // Population and fitness status
  // ==========================================================

  /** Current population */
  private List<double[]> pop;

  /** Current population fitness */
  private List<Double> popFit;

  /** Primitive mirror of current population fitness for hot loops. */
  private double[] popFitArray;

  /** Current population size */
  private int nPop;

  /** Individual best solution */
  private List<double[]> pbestSolution;

  /** Individual best fitness */
  private List<Double> pbestFit;

  /** Global best solution */
  private double[] gbestSolution;

  /** Global best fitness */
  private double gbestFit;

  /** Initial fitness */
  private double initialGbestFit;

  // ==========================================================
  // Iteration and recording status
  // ==========================================================

  /** Current iteration number */
  private int itera;

  /** Current generation */
  private int gen;

  /** Improvement flag */
  private int flagImprove;

  /** No improvement count */
  private int countNoImprove;

  /** Dispersity flag */
  private int flagDispersity;

  /** Total updated individual count */
  private int totalUpdatedInd;

  /** Ratio of individuals that lie on the current neutral-fitness plateau. */
  private double neutralRatio;

  /** Current sliding-window credit score for OA1. */
  private double creditParent1;

  /** Current sliding-window credit score for OA2. */
  private double creditParent2;

  /** Current routing ratio for OA1 in adaptive-credit mode. */
  private double creditRatioParent1;

  /** Evaluation cost spent by OA1 in the current generation. */
  private int iterationEvalCostParent1;

  /** Evaluation cost spent by OA2 in the current generation. */
  private int iterationEvalCostParent2;

  /** Best fitness record */
  private List<Double> bestFitRecord;

  // ==========================================================
  // Main loop parameters
  // ==========================================================

  /** Enabled Parent Algorithm Flag */
  private List<Boolean> enableParents;

  /** Actual Operation Order */
  private int oOReal;

  /** Hybrid level */
  private int hybLevel;

  /** Actual operation level */
  private int[] oLReal;

  /** Parent algorithm flag array */
  private int[] flagPar;

  // ==========================================================
  // Performance optimization cache
  // ==========================================================

  /** Whether SHADE is activated (cache) */
  private boolean isShadeActive;

  /** Whether DE algorithm is activated (cache) */
  private boolean isDEActive;

  /** Cached current best index for the active population fitness. */
  private transient int cachedBestIndex = -1;

  /** Cached fitness rank in descending order, ties by lower index first. */
  private transient int[] cachedFitnessRankDesc;

  // ==========================================================
  // Algorithm-specific states
  // ==========================================================

  /** PSO velocity matrix */
  private List<double[]> v;

  /** ABC limit list */
  private List<Double> limitABC;

  /** SA sigma list */
  private List<double[]> sigma;

  /** SHADE archive */
  private List<double[]> shadeArchive;

  /** SHADE SF list */
  private List<double[]> shadeSF;

  /** SHADE CR list */
  private List<Double> shadeCR;

  /** SHADE Frequency List */
  private List<Double> shadeFreq;

  /** SHADE Good Fitness List 1 */
  private List<Double> goodFit1All;

  /** SHADE Good Fitness List 2 */
  private List<Double> goodFit2All;

  /** SHADE Bad Fitness List 1 */
  private List<Double> badFit1All;

  /** SHADE Bad Fitness List 2 */
  private List<Double> badFit2All;

  /** SHADE Memory Position */
  private int memoryPos;

  /** SHADE Generation Counter */
  private int shadeGG;

  /** SHADE flag 1 */
  private boolean flag1Shade;

  /** SHADE flag 2 */
  private boolean flag2Shade;

  /** ALNS destroy operator states, persisted within one HybCec run. */
  private transient List<ALNS.OperatorState> alnsDestroyOperators;

  /** ALNS repair operator states, persisted within one HybCec run. */
  private transient List<ALNS.OperatorState> alnsRepairOperators;

  /** ALNS config identity used when destroy operator states were created. */
  private transient ALNS.ALNSConfig alnsDestroyOperatorConfig;

  /** ALNS config identity used when repair operator states were created. */
  private transient ALNS.ALNSConfig alnsRepairOperatorConfig;

  // ==========================================================
  // Algorithm workspaces
  // ==========================================================

  private transient DEWorkspace deWs;
  private transient GAWorkspace gaWs;
  private transient PSOWorkspace psoWs;
  private transient SAWorkspace saWs;
  private transient ACOWorkspace acoWs;
  private transient EDAWorkspace edaWs;
  private transient ABCWorkspace abcWs;
  private transient SHADEWorkspace shadeWs;

  // ==========================================================
  // Loop related temporary variables
  // ==========================================================

  /** Individual mask 1 */
  private List<Boolean> indMask1;

  /** Individual mask 2 */
  private List<Boolean> indMask2;

  /** Current updated individual count */
  private int count;

  /** Unit-level mask 1 */
  private List<boolean[]> unitMask1;

  /** Unit-level mask 1 (reverse) */
  private List<boolean[]> resUnitMask1;

  /** Unit-level mask 2 */
  private List<boolean[]> unitMask2;

  /** Unit-level mask 2 (reverse) */
  private List<boolean[]> resUnitMask2;

  /** Temporary population 1 */
  private List<double[]> X1;

  /** Selected indices for population 1 (replaces popSelect1) */
  private int[] selectIndices1;

  /** Temporary population 2 */
  private List<double[]> X2;

  /** Selected indices for population 2 (replaces popSelect2) */
  private int[] selectIndices2;

  /** Temporary fitness 1 */
  private List<Double> popFit1;

  /** Temporary Fitness 2 */
  private List<Double> popFit2;

  /** Updated population 1 */
  private List<double[]> newPopUpdated1;

  /** Updated population 2 */
  private List<double[]> newPopUpdated2;

  // ==========================================================
  // Constructor
  // ==========================================================

  /**
   * Create state manager with all collections initialized
   */
  public HybCecState() {
    // Initialize all List fields to avoid null pointer exceptions
    this.pop = new ArrayList<>();
    this.popFit = new ArrayList<>();
    this.popFitArray = new double[0];
    this.pbestSolution = new ArrayList<>();
    this.pbestFit = new ArrayList<>();
    this.bestFitRecord = new ArrayList<>();
    this.enableParents = new ArrayList<>();

    // Initialize algorithm-specific state lists
    this.v = new ArrayList<>();
    this.limitABC = new ArrayList<>();
    this.sigma = new ArrayList<>();
    this.shadeArchive = new ArrayList<>();
    this.shadeSF = new ArrayList<>();
    this.shadeCR = new ArrayList<>();
    this.shadeFreq = new ArrayList<>();
    this.goodFit1All = new ArrayList<>();
    this.goodFit2All = new ArrayList<>();
    this.badFit1All = new ArrayList<>();
    this.badFit2All = new ArrayList<>();

    // Initialize loop-related temporary variables
    this.indMask1 = new ArrayList<>();
    this.indMask2 = new ArrayList<>();
    this.unitMask1 = new ArrayList<>();
    this.resUnitMask1 = new ArrayList<>();
    this.unitMask2 = new ArrayList<>();
    this.resUnitMask2 = new ArrayList<>();
    this.X1 = new ArrayList<>();
    this.selectIndices1 = new int[0];
    this.X2 = new ArrayList<>();
    this.selectIndices2 = new int[0];
    this.popFit1 = new ArrayList<>();
    this.popFit2 = new ArrayList<>();
    this.newPopUpdated1 = new ArrayList<>();
    this.newPopUpdated2 = new ArrayList<>();

    // Initialize arrays with constants
    this.oLReal = new int[OPERATION_LEVEL_SIZE];
    this.flagPar = new int[FLAG_PAR_SIZE];

    // Initialize primitive types
    this.nPop = 0;
    this.itera = 0;
    this.gen = 0;
    this.flagImprove = 0;
    this.countNoImprove = 0;
    this.flagDispersity = 0;
    this.totalUpdatedInd = 0;
    this.neutralRatio = 0.0;
    this.creditParent1 = 0.0;
    this.creditParent2 = 0.0;
    this.creditRatioParent1 = 0.0;
    this.iterationEvalCostParent1 = 0;
    this.iterationEvalCostParent2 = 0;
    this.count = 0;
    this.memoryPos = 0;
    this.shadeGG = 0;
    // Initialize flags
    this.isShadeActive = false;
    this.isDEActive = false;
    this.cachedBestIndex = -1;
    this.cachedFitnessRankDesc = null;
    this.flag1Shade = false;
    this.flag2Shade = false;
  }

  // ==========================================================
  // Population and fitness accessor
  // ==========================================================

  public List<double[]> getPop() {
    return pop;
  }

  public void setPop(List<double[]> pop) {
    if (pop == null) {
      throw new IllegalArgumentException("Population cannot be null");
    }
    this.pop = pop;
  }

  public List<Double> getPopFit() {
    return popFit;
  }

  public void setPopFit(List<Double> popFit) {
    if (popFit == null) {
      throw new IllegalArgumentException("Population fitness cannot be null");
    }
    this.popFit = popFit;
    this.popFitArray = toPrimitive(popFit);
    invalidateFitnessCache();
  }

  public double[] getPopFitArray() {
    return popFitArray;
  }

  public void setPopFitArray(double[] popFitArray) {
    if (popFitArray == null) {
      throw new IllegalArgumentException("Population fitness array cannot be null");
    }
    this.popFitArray = popFitArray;
    this.popFit = toList(popFitArray);
    invalidateFitnessCache();
  }

  public void syncPopFitArrayFromList() {
    this.popFitArray = toPrimitive(popFit);
    invalidateFitnessCache();
  }

  public void syncPopFitListFromArray() {
    this.popFit = toList(popFitArray);
    invalidateFitnessCache();
  }

  public void invalidateFitnessCache() {
    cachedBestIndex = -1;
    cachedFitnessRankDesc = null;
  }

  public int getCachedBestIndex() {
    if (cachedBestIndex >= 0) {
      return cachedBestIndex;
    }
    if (popFit == null || popFit.isEmpty()) {
      throw new IllegalArgumentException("Population fitness cannot be null or empty");
    }
    int bestIndex = 0;
    double bestFitness = popFit.get(0);
    for (int i = 1; i < popFit.size(); i++) {
      double fitness = popFit.get(i);
      if (fitness > bestFitness) {
        bestFitness = fitness;
        bestIndex = i;
      }
    }
    cachedBestIndex = bestIndex;
    return bestIndex;
  }

  public int[] getCachedFitnessRankDesc() {
    int size = popFit == null ? 0 : popFit.size();
    if (cachedFitnessRankDesc != null && cachedFitnessRankDesc.length == size) {
      return cachedFitnessRankDesc;
    }
    int[] rank = new int[size];
    boolean[] used = new boolean[size];
    for (int pos = 0; pos < size; pos++) {
      int bestIndex = -1;
      double bestFitness = Double.NEGATIVE_INFINITY;
      for (int i = 0; i < size; i++) {
        if (used[i]) {
          continue;
        }
        double fitness = popFit.get(i);
        if (bestIndex < 0 || fitness > bestFitness) {
          bestFitness = fitness;
          bestIndex = i;
        }
      }
      used[bestIndex] = true;
      rank[pos] = bestIndex;
    }
    cachedFitnessRankDesc = rank;
    return rank;
  }

  public int getNPop() {
    return nPop;
  }

  public void setNPop(int nPop) {
    if (nPop < 0) {
      throw new IllegalArgumentException("Population size must be non-negative, got: " + nPop);
    }
    this.nPop = nPop;
  }

  public List<double[]> getPbestSolution() {
    return pbestSolution;
  }

  public void setPbestSolution(List<double[]> pbestSolution) {
    this.pbestSolution = pbestSolution;
  }

  public List<Double> getPbestFit() {
    return pbestFit;
  }

  public void setPbestFit(List<Double> pbestFit) {
    this.pbestFit = pbestFit;
  }

  public double[] getGbestSolution() {
    return gbestSolution == null ? null : gbestSolution.clone();
  }

  public void setGbestSolution(double[] gbestSolution) {
    this.gbestSolution = gbestSolution == null ? null : gbestSolution.clone();
  }

  public double getGbestFit() {
    return gbestFit;
  }

  public void setGbestFit(double gbestFit) {
    this.gbestFit = gbestFit;
  }

  public double getInitialGbestFit() {
    return initialGbestFit;
  }

  public void setInitialGbestFit(double initialGbestFit) {
    this.initialGbestFit = initialGbestFit;
  }

  // ==========================================================
  // Iteration and recording accessor
  // ==========================================================

  public int getItera() {
    return itera;
  }

  public void setItera(int itera) {
    if (itera < 0) {
      throw new IllegalArgumentException("Iteration count must be non-negative, got: " + itera);
    }
    this.itera = itera;
  }

  public void incrementItera(int increment) {
    if (increment < 0) {
      throw new IllegalArgumentException("Increment must be non-negative, got: " + increment);
    }
    this.itera += increment;
  }

  public int getGen() {
    return gen;
  }

  public void setGen(int gen) {
    if (gen < 0) {
      throw new IllegalArgumentException("Generation must be non-negative, got: " + gen);
    }
    this.gen = gen;
  }

  public void incrementGen() {
    this.gen++;
  }

  public int getFlagImprove() {
    return flagImprove;
  }

  public void setFlagImprove(int flagImprove) {
    this.flagImprove = flagImprove;
  }

  public int getCountNoImprove() {
    return countNoImprove;
  }

  public void setCountNoImprove(int countNoImprove) {
    this.countNoImprove = countNoImprove;
  }

  public void incrementCountNoImprove() {
    this.countNoImprove++;
  }

  public int getFlagDispersity() {
    return flagDispersity;
  }

  public void setFlagDispersity(int flagDispersity) {
    this.flagDispersity = flagDispersity;
  }

  public int getTotalUpdatedInd() {
    return totalUpdatedInd;
  }

  public void setTotalUpdatedInd(int totalUpdatedInd) {
    this.totalUpdatedInd = totalUpdatedInd;
  }

  public void addTotalUpdatedInd(int increment) {
    this.totalUpdatedInd += increment;
  }

  public double getNeutralRatio() {
    return neutralRatio;
  }

  public void setNeutralRatio(double neutralRatio) {
    if (Double.isNaN(neutralRatio) || neutralRatio < 0.0 || neutralRatio > 1.0) {
      throw new IllegalArgumentException("neutralRatio must be within [0, 1], got: " + neutralRatio);
    }
    this.neutralRatio = neutralRatio;
  }

  public double getCreditParent1() {
    return creditParent1;
  }

  public void setCreditParent1(double creditParent1) {
    if (Double.isNaN(creditParent1) || creditParent1 < 0.0) {
      throw new IllegalArgumentException("creditParent1 must be non-negative");
    }
    this.creditParent1 = creditParent1;
  }

  public double getCreditParent2() {
    return creditParent2;
  }

  public void setCreditParent2(double creditParent2) {
    if (Double.isNaN(creditParent2) || creditParent2 < 0.0) {
      throw new IllegalArgumentException("creditParent2 must be non-negative");
    }
    this.creditParent2 = creditParent2;
  }

  public double getCreditRatioParent1() {
    return creditRatioParent1;
  }

  public void setCreditRatioParent1(double creditRatioParent1) {
    if (Double.isNaN(creditRatioParent1) || creditRatioParent1 < 0.0 || creditRatioParent1 > 1.0) {
      throw new IllegalArgumentException("creditRatioParent1 must be within [0, 1]");
    }
    this.creditRatioParent1 = creditRatioParent1;
  }

  public int getIterationEvalCostParent1() {
    return iterationEvalCostParent1;
  }

  public void addIterationEvalCostParent1(int increment) {
    if (increment < 0) {
      throw new IllegalArgumentException("increment must be non-negative");
    }
    this.iterationEvalCostParent1 += increment;
  }

  public int getIterationEvalCostParent2() {
    return iterationEvalCostParent2;
  }

  public void addIterationEvalCostParent2(int increment) {
    if (increment < 0) {
      throw new IllegalArgumentException("increment must be non-negative");
    }
    this.iterationEvalCostParent2 += increment;
  }

  public void resetIterationEvalCosts() {
    this.iterationEvalCostParent1 = 0;
    this.iterationEvalCostParent2 = 0;
  }

  public List<Double> getBestFitRecord() {
    return bestFitRecord;
  }

  public void setBestFitRecord(List<Double> bestFitRecord) {
    this.bestFitRecord = bestFitRecord;
  }

  // ==========================================================
  // Main Loop Parameter Accessor
  // ==========================================================

  public List<Boolean> getEnableParents() {
    return enableParents;
  }

  public void setEnableParents(List<Boolean> enableParents) {
    this.enableParents = enableParents;
  }

  public int getOOReal() {
    return oOReal;
  }

  public void setOOReal(int oOReal) {
    this.oOReal = oOReal;
  }

  public int getHybLevel() {
    return hybLevel;
  }

  public void setHybLevel(int hybLevel) {
    this.hybLevel = hybLevel;
  }

  public int[] getOLReal() {
    return oLReal == null ? null : oLReal.clone();
  }

  public void setOLReal(int[] oLReal) {
    if (oLReal != null && oLReal.length != OPERATION_LEVEL_SIZE) {
      throw new IllegalArgumentException("oLReal array must have length " + OPERATION_LEVEL_SIZE);
    }
    this.oLReal = oLReal == null ? null : oLReal.clone();
  }

  public int[] getFlagPar() {
    return flagPar == null ? null : flagPar.clone();
  }

  public void setFlagPar(int[] flagPar) {
    if (flagPar != null && flagPar.length != FLAG_PAR_SIZE) {
      throw new IllegalArgumentException("flagPar array must have length " + FLAG_PAR_SIZE);
    }
    this.flagPar = flagPar == null ? null : flagPar.clone();
  }

  // ==========================================================
  // Performance Optimization Cache Accessor
  // ==========================================================

  public boolean isShadeActive() {
    return isShadeActive;
  }

  public void setShadeActive(boolean isShadeActive) {
    this.isShadeActive = isShadeActive;
  }

  public boolean isDEActive() {
    return isDEActive;
  }

  public void setDEActive(boolean isDEActive) {
    this.isDEActive = isDEActive;
  }

  // ==========================================================
  // Algorithm-Specific State Accessor
  // ==========================================================

  public List<double[]> getV() {
    return v;
  }

  public void setV(List<double[]> v) {
    this.v = v;
  }

  public List<Double> getLimitABC() {
    return limitABC;
  }

  public void setLimitABC(List<Double> limitABC) {
    this.limitABC = limitABC;
  }

  public List<double[]> getSigma() {
    return sigma;
  }

  public void setSigma(List<double[]> sigma) {
    this.sigma = sigma;
  }

  public List<double[]> getShadeArchive() {
    return shadeArchive;
  }

  public void setShadeArchive(List<double[]> shadeArchive) {
    this.shadeArchive = shadeArchive;
  }

  public List<double[]> getShadeSF() {
    return shadeSF;
  }

  public void setShadeSF(List<double[]> shadeSF) {
    this.shadeSF = shadeSF;
  }

  public List<Double> getShadeCR() {
    return shadeCR;
  }

  public void setShadeCR(List<Double> shadeCR) {
    this.shadeCR = shadeCR;
  }

  public List<Double> getShadeFreq() {
    return shadeFreq;
  }

  public void setShadeFreq(List<Double> shadeFreq) {
    this.shadeFreq = shadeFreq;
  }

  public List<Double> getGoodFit1All() {
    return goodFit1All;
  }

  public void setGoodFit1All(List<Double> goodFit1All) {
    this.goodFit1All = goodFit1All;
  }

  public List<Double> getGoodFit2All() {
    return goodFit2All;
  }

  public void setGoodFit2All(List<Double> goodFit2All) {
    this.goodFit2All = goodFit2All;
  }

  public List<Double> getBadFit1All() {
    return badFit1All;
  }

  public void setBadFit1All(List<Double> badFit1All) {
    this.badFit1All = badFit1All;
  }

  public List<Double> getBadFit2All() {
    return badFit2All;
  }

  public void setBadFit2All(List<Double> badFit2All) {
    this.badFit2All = badFit2All;
  }

  public int getMemoryPos() {
    return memoryPos;
  }

  public void setMemoryPos(int memoryPos) {
    this.memoryPos = memoryPos;
  }

  public int getShadeGG() {
    return shadeGG;
  }

  public void setShadeGG(int shadeGG) {
    this.shadeGG = shadeGG;
  }

  public boolean isFlag1Shade() {
    return flag1Shade;
  }

  public void setFlag1Shade(boolean flag1Shade) {
    this.flag1Shade = flag1Shade;
  }

  public boolean isFlag2Shade() {
    return flag2Shade;
  }

  public void setFlag2Shade(boolean flag2Shade) {
    this.flag2Shade = flag2Shade;
  }

  public DEWorkspace getDEWorkspace() {
    if (deWs == null) {
      deWs = new DEWorkspace();
    }
    return deWs;
  }

  public GAWorkspace getGAWorkspace() {
    if (gaWs == null) {
      gaWs = new GAWorkspace();
    }
    return gaWs;
  }

  public PSOWorkspace getPSOWorkspace() {
    if (psoWs == null) {
      psoWs = new PSOWorkspace();
    }
    return psoWs;
  }

  public SAWorkspace getSAWorkspace() {
    if (saWs == null) {
      saWs = new SAWorkspace();
    }
    return saWs;
  }

  public ACOWorkspace getACOWorkspace() {
    if (acoWs == null) {
      acoWs = new ACOWorkspace();
    }
    return acoWs;
  }

  public EDAWorkspace getEDAWorkspace() {
    if (edaWs == null) {
      edaWs = new EDAWorkspace();
    }
    return edaWs;
  }

  public ABCWorkspace getABCWorkspace() {
    if (abcWs == null) {
      abcWs = new ABCWorkspace();
    }
    return abcWs;
  }

  public SHADEWorkspace getSHADEWorkspace() {
    if (shadeWs == null) {
      shadeWs = new SHADEWorkspace();
    }
    return shadeWs;
  }

  public List<ALNS.OperatorState> getAlnsDestroyOperators(ALNS.ALNSConfig config) {
    if (alnsDestroyOperators == null || alnsDestroyOperatorConfig != config) {
      alnsDestroyOperators = ALNS.createDestroyOperators(config);
      alnsDestroyOperatorConfig = config;
    }
    return alnsDestroyOperators;
  }

  public List<ALNS.OperatorState> getAlnsRepairOperators(ALNS.ALNSConfig config) {
    if (alnsRepairOperators == null || alnsRepairOperatorConfig != config) {
      alnsRepairOperators = ALNS.createRepairOperators(config);
      alnsRepairOperatorConfig = config;
    }
    return alnsRepairOperators;
  }

  // ==========================================================
  // Accessor for Loop-Related Temporary Variables
  // ==========================================================

  public List<Boolean> getIndMask1() {
    return indMask1;
  }

  public void setIndMask1(List<Boolean> indMask1) {
    this.indMask1 = indMask1;
  }

  public List<Boolean> getIndMask2() {
    return indMask2;
  }

  public void setIndMask2(List<Boolean> indMask2) {
    this.indMask2 = indMask2;
  }

  public int getCount() {
    return count;
  }

  public void setCount(int count) {
    this.count = count;
  }

  public List<boolean[]> getUnitMask1() {
    return unitMask1;
  }

  public void setUnitMask1(List<boolean[]> unitMask1) {
    this.unitMask1 = unitMask1;
  }

  public List<boolean[]> getResUnitMask1() {
    return resUnitMask1;
  }

  public void setResUnitMask1(List<boolean[]> resUnitMask1) {
    this.resUnitMask1 = resUnitMask1;
  }

  public List<boolean[]> getUnitMask2() {
    return unitMask2;
  }

  public void setUnitMask2(List<boolean[]> unitMask2) {
    this.unitMask2 = unitMask2;
  }

  public List<boolean[]> getResUnitMask2() {
    return resUnitMask2;
  }

  public void setResUnitMask2(List<boolean[]> resUnitMask2) {
    this.resUnitMask2 = resUnitMask2;
  }

  public List<double[]> getX1() {
    return X1;
  }

  public void setX1(List<double[]> x1) {
    X1 = x1;
  }

  public int[] getSelectIndices1() {
    return selectIndices1;
  }

  public void setSelectIndices1(int[] selectIndices1) {
    this.selectIndices1 = selectIndices1;
  }

  public List<double[]> getX2() {
    return X2;
  }

  public void setX2(List<double[]> x2) {
    X2 = x2;
  }

  public int[] getSelectIndices2() {
    return selectIndices2;
  }

  public void setSelectIndices2(int[] selectIndices2) {
    this.selectIndices2 = selectIndices2;
  }

  public List<Double> getPopFit1() {
    return popFit1;
  }

  public void setPopFit1(List<Double> popFit1) {
    this.popFit1 = popFit1;
  }

  public List<Double> getPopFit2() {
    return popFit2;
  }

  public void setPopFit2(List<Double> popFit2) {
    this.popFit2 = popFit2;
  }

  public List<double[]> getNewPopUpdated1() {
    return newPopUpdated1;
  }

  public void setNewPopUpdated1(List<double[]> newPopUpdated1) {
    this.newPopUpdated1 = newPopUpdated1;
  }

  public List<double[]> getNewPopUpdated2() {
    return newPopUpdated2;
  }

  public void setNewPopUpdated2(List<double[]> newPopUpdated2) {
    this.newPopUpdated2 = newPopUpdated2;
  }

  // ==========================================================
  // State Management Methods
  // ==========================================================

  /**
   * Validate state consistency
   * @throws IllegalStateException if state is inconsistent
   */
  public void validate() throws IllegalStateException {
    // Validate population and fitness consistency
    if (pop != null && popFit != null && pop.size() != popFit.size()) {
      throw new IllegalStateException(
        "Population size (" + pop.size() + ") does not match fitness size (" + popFit.size() + ")"
      );
    }

    // Validate pbest consistency
    if (pbestSolution != null && pbestFit != null && pbestSolution.size() != pbestFit.size()) {
      throw new IllegalStateException(
        "PbestSolution size (" + pbestSolution.size() + ") does not match pbestFit size (" + pbestFit.size() + ")"
      );
    }

    // Validate nPop consistency
    if (pop != null && nPop > 0 && pop.size() != nPop) {
      throw new IllegalStateException(
        "Stored nPop (" + nPop + ") does not match actual population size (" + pop.size() + ")"
      );
    }

    // Validate non-negative counters
    if (itera < 0) {
      throw new IllegalStateException("Iteration count cannot be negative: " + itera);
    }
    if (gen < 0) {
      throw new IllegalStateException("Generation cannot be negative: " + gen);
    }
    if (countNoImprove < 0) {
      throw new IllegalStateException("countNoImprove cannot be negative: " + countNoImprove);
    }
    if (Double.isNaN(neutralRatio) || neutralRatio < 0.0 || neutralRatio > 1.0) {
      throw new IllegalStateException("neutralRatio must be within [0, 1], got: " + neutralRatio);
    }
  }

  /**
   * Create a deep copy snapshot of the current state
   * Useful for rollback or state comparison
   * @return a new HybCecState instance with copied data
   */
  public HybCecState snapshot() {
    HybCecState snapshot = new HybCecState();

    // Copy primitive fields
    snapshot.nPop = this.nPop;
    snapshot.gbestFit = this.gbestFit;
    snapshot.initialGbestFit = this.initialGbestFit;
    snapshot.itera = this.itera;
    snapshot.gen = this.gen;
    snapshot.flagImprove = this.flagImprove;
    snapshot.countNoImprove = this.countNoImprove;
    snapshot.flagDispersity = this.flagDispersity;
    snapshot.totalUpdatedInd = this.totalUpdatedInd;
    snapshot.neutralRatio = this.neutralRatio;
    snapshot.creditParent1 = this.creditParent1;
    snapshot.creditParent2 = this.creditParent2;
    snapshot.creditRatioParent1 = this.creditRatioParent1;
    snapshot.iterationEvalCostParent1 = this.iterationEvalCostParent1;
    snapshot.iterationEvalCostParent2 = this.iterationEvalCostParent2;
    snapshot.oOReal = this.oOReal;
    snapshot.hybLevel = this.hybLevel;
    snapshot.isShadeActive = this.isShadeActive;
    snapshot.isDEActive = this.isDEActive;
    snapshot.memoryPos = this.memoryPos;
    snapshot.shadeGG = this.shadeGG;
    snapshot.flag1Shade = this.flag1Shade;
    snapshot.flag2Shade = this.flag2Shade;
    snapshot.count = this.count;
    // Copy arrays
    snapshot.gbestSolution = this.gbestSolution == null ? null : this.gbestSolution.clone();
    snapshot.oLReal = this.oLReal == null ? null : this.oLReal.clone();
    snapshot.flagPar = this.flagPar == null ? null : this.flagPar.clone();

    // Note: Deep copying Lists of arrays is complex and may not be needed for snapshots
    // In practice, you might want to implement specific copy methods based on usage
    // For now, we share the references (shallow copy for Lists)
    snapshot.pop = this.pop == null ? null : new ArrayList<>(this.pop);
    snapshot.popFit = this.popFit == null ? null : new ArrayList<>(this.popFit);
    snapshot.popFitArray = this.popFitArray == null ? null : this.popFitArray.clone();
    snapshot.pbestSolution = this.pbestSolution == null ? null : new ArrayList<>(this.pbestSolution);
    snapshot.pbestFit = this.pbestFit == null ? null : new ArrayList<>(this.pbestFit);
    snapshot.bestFitRecord = this.bestFitRecord == null ? null : new ArrayList<>(this.bestFitRecord);

    return snapshot;
  }

  /**
   * Reset all state to initial values
   * Useful for reinitializing the algorithm state
   */
  public void reset() {
    // Clear all lists
    if (pop != null) pop.clear();
    if (popFit != null) popFit.clear();
    popFitArray = new double[0];
    if (pbestSolution != null) pbestSolution.clear();
    if (pbestFit != null) pbestFit.clear();
    if (bestFitRecord != null) bestFitRecord.clear();
    if (enableParents != null) enableParents.clear();

    // Clear algorithm-specific states
    if (v != null) v.clear();
    if (limitABC != null) limitABC.clear();
    if (sigma != null) sigma.clear();
    if (shadeArchive != null) shadeArchive.clear();
    if (shadeSF != null) shadeSF.clear();
    if (shadeCR != null) shadeCR.clear();
    if (shadeFreq != null) shadeFreq.clear();
    if (goodFit1All != null) goodFit1All.clear();
    if (goodFit2All != null) goodFit2All.clear();
    if (badFit1All != null) badFit1All.clear();
    if (badFit2All != null) badFit2All.clear();

    // Clear temporary variables
    if (indMask1 != null) indMask1.clear();
    if (indMask2 != null) indMask2.clear();
    if (unitMask1 != null) unitMask1.clear();
    if (resUnitMask1 != null) resUnitMask1.clear();
    if (unitMask2 != null) unitMask2.clear();
    if (resUnitMask2 != null) resUnitMask2.clear();
    if (X1 != null) X1.clear();
    selectIndices1 = new int[0];
    if (X2 != null) X2.clear();
    selectIndices2 = new int[0];
    if (popFit1 != null) popFit1.clear();
    if (popFit2 != null) popFit2.clear();
    if (newPopUpdated1 != null) newPopUpdated1.clear();
    if (newPopUpdated2 != null) newPopUpdated2.clear();

    // Reset primitive values
    nPop = 0;
    gbestFit = 0.0;
    initialGbestFit = 0.0;
    itera = 0;
    gen = 0;
    flagImprove = 0;
    countNoImprove = 0;
    flagDispersity = 0;
    totalUpdatedInd = 0;
    neutralRatio = 0.0;
    creditParent1 = 0.0;
    creditParent2 = 0.0;
    creditRatioParent1 = 0.0;
    iterationEvalCostParent1 = 0;
    iterationEvalCostParent2 = 0;
    oOReal = 0;
    hybLevel = 0;
    memoryPos = 0;
    shadeGG = 0;
    count = 0;

    // Reset arrays
    gbestSolution = null;
    if (oLReal != null) {
      for (int i = 0; i < oLReal.length; i++) {
        oLReal[i] = 0;
      }
    }
    if (flagPar != null) {
      for (int i = 0; i < flagPar.length; i++) {
        flagPar[i] = 0;
      }
    }

    // Reset flags
    isShadeActive = false;
    isDEActive = false;
    invalidateFitnessCache();
    flag1Shade = false;
    flag2Shade = false;
    alnsDestroyOperators = null;
    alnsRepairOperators = null;
    alnsDestroyOperatorConfig = null;
    alnsRepairOperatorConfig = null;

  }

  /**
   * Update cache flags based on current state
   * Call this method when parent algorithm configuration changes
   */
  public void updateCacheFlags() {
    this.isShadeActive = flagPar != null
      && (
        isFlagActive(AlgorithmConstants.ALGORITHM_INDEX_LSHADE)
          || isFlagActive(AlgorithmConstants.ALGORITHM_INDEX_SHADE)
      );

    this.isDEActive = false;
    if (flagPar == null) {
      return;
    }
    for (int i = 0; i < flagPar.length; i++) {
      if (flagPar[i] == 1 && AlgorithmConstants.isDeParentAlgorithm(i)) {
        this.isDEActive = true;
        break;
      }
    }
  }

  private boolean isFlagActive(int index) {
    return flagPar != null && index >= 0 && index < flagPar.length && flagPar[index] == 1;
  }

  private static double[] toPrimitive(List<Double> values) {
    if (values == null || values.isEmpty()) {
      return new double[0];
    }
    double[] result = new double[values.size()];
    for (int i = 0; i < values.size(); i++) {
      result[i] = values.get(i);
    }
    return result;
  }

  private static List<Double> toList(double[] values) {
    List<Double> result = new ArrayList<>(values.length);
    for (double value : values) {
      result.add(value);
    }
    return result;
  }

  // ==========================================================
  // Builder Pattern
  // ==========================================================

  /**
   * Builder for creating HybCecState instances with a fluent API
   *
   * Example usage:
   * <pre>
   * HybCecState state = new HybCecState.Builder()
   *     .nPop(100)
   *     .gen(0)
   *     .itera(0)
   *     .hybLevel(2)
   *     .build();
   * </pre>
   */
  public static class Builder {
    private final HybCecState state;

    public Builder() {
      this.state = new HybCecState();
    }

    public Builder nPop(int nPop) {
      state.setNPop(nPop);
      return this;
    }

    public Builder pop(List<double[]> pop) {
      state.setPop(pop);
      return this;
    }

    public Builder popFit(List<Double> popFit) {
      state.setPopFit(popFit);
      return this;
    }

    public Builder pbestSolution(List<double[]> pbestSolution) {
      state.setPbestSolution(pbestSolution);
      return this;
    }

    public Builder pbestFit(List<Double> pbestFit) {
      state.setPbestFit(pbestFit);
      return this;
    }

    public Builder gbestSolution(double[] gbestSolution) {
      state.setGbestSolution(gbestSolution);
      return this;
    }

    public Builder gbestFit(double gbestFit) {
      state.setGbestFit(gbestFit);
      return this;
    }

    public Builder initialGbestFit(double initialGbestFit) {
      state.setInitialGbestFit(initialGbestFit);
      return this;
    }

    public Builder itera(int itera) {
      state.setItera(itera);
      return this;
    }

    public Builder gen(int gen) {
      state.setGen(gen);
      return this;
    }

    public Builder flagImprove(int flagImprove) {
      state.setFlagImprove(flagImprove);
      return this;
    }

    public Builder countNoImprove(int countNoImprove) {
      state.setCountNoImprove(countNoImprove);
      return this;
    }

    public Builder flagDispersity(int flagDispersity) {
      state.setFlagDispersity(flagDispersity);
      return this;
    }

    public Builder totalUpdatedInd(int totalUpdatedInd) {
      state.setTotalUpdatedInd(totalUpdatedInd);
      return this;
    }

    public Builder neutralRatio(double neutralRatio) {
      state.setNeutralRatio(neutralRatio);
      return this;
    }

    public Builder bestFitRecord(List<Double> bestFitRecord) {
      state.setBestFitRecord(bestFitRecord);
      return this;
    }

    public Builder enableParents(List<Boolean> enableParents) {
      state.setEnableParents(enableParents);
      return this;
    }

    public Builder oOReal(int oOReal) {
      state.setOOReal(oOReal);
      return this;
    }

    public Builder hybLevel(int hybLevel) {
      state.setHybLevel(hybLevel);
      return this;
    }

    public Builder oLReal(int[] oLReal) {
      state.setOLReal(oLReal);
      return this;
    }

    public Builder flagPar(int[] flagPar) {
      state.setFlagPar(flagPar);
      return this;
    }

    public Builder shadeActive(boolean isShadeActive) {
      state.setShadeActive(isShadeActive);
      return this;
    }

    public Builder deActive(boolean isDEActive) {
      state.setDEActive(isDEActive);
      return this;
    }

    /**
     * Build and validate the state
     * @return the constructed HybCecState
     * @throws IllegalStateException if state validation fails
     */
    public HybCecState build() {
      state.validate();
      return state;
    }

    /**
     * Build without validation
     * Use this if you plan to set additional fields later
     * @return the constructed HybCecState
     */
    public HybCecState buildUnchecked() {
      return state;
    }
  }
}
