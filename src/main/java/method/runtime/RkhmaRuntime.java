package method.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.Set;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.random.RandomGenerator;
import method.rkhma.bridge.ProblemBridge;
import method.rkhma.legacy.common.RandomProvider;
import method.rkhma.legacy.common.SearchDeadline;
import method.rkhma.legacy.main.TaskAllocationCoordinator;
import method.rkhma.legacy.operator.assignment.MaxWindowCountStrategy;
import method.rkhma.legacy.operator.insertion.InsertionMode;
import model.ProblemInstance;
import model.ScheduledTask;
import model.SchedulingPlan;
import problem.ScheduleValidator;

/** RKHMA 来源组件的组装入口；问题输入只读，来源状态保存在独立工作副本中。 */
public final class RkhmaRuntime {
  private RkhmaRuntime() { }

  public record Config(long seed, int population, int evaluations, int reassignIterations,
      int perturbThreshold, double perturbStrength, long searchNanos, long hardNanos) {
    public Config {
      boolean automatic = population == 0 && evaluations == 0;
      if ((!automatic && (population < 6 || evaluations < population)) || reassignIterations < 0 || perturbThreshold <= 0
          || !Double.isFinite(perturbStrength) || perturbStrength < 0 || searchNanos <= 0 || hardNanos < searchNanos) {
        throw new IllegalArgumentException("invalid RKHMA configuration");
      }
    }
    public static Config defaults(long seed) {
      var limits = TwoStageSolver.Limits.defaults();
      return new Config(seed, 0, 0, 25, 1, .02, limits.searchNanos(), limits.hardNanos());
    }
  }

  public record Result(TwoStageSolver.Status status, SchedulingPlan plan, Long profit,
      Map<Integer, Integer> owners, List<String> history, boolean adaptedGrid, String detail, long elapsedNanos) {
    public Result {
      owners = Collections.unmodifiableMap(new LinkedHashMap<>(owners));
      history = List.copyOf(history);
    }
  }

  private record Context(ProblemInstance problem, ProblemBridge bridge, ProblemAccess access,
      Budget budget, SearchDeadline deadline, Config config, RandomGenerator random) { }

  private interface Allocation {
    Map<Integer, Integer> initialOwners(Context context);
    default void configure(TaskAllocationCoordinator coordinator, Context context) { }
  }
  private interface Search {
    default void configure(TaskAllocationCoordinator coordinator, Context context, Decoder decoder) { }
  }
  private interface Decoder {
    void configure(Context context);
    List<ScheduledTask> build(Context context, int satelliteIndex, SolverContracts.SatelliteView view,
        List<Integer> priorities);
    SolverContracts.LocalResult applyLocal(Context context, int satelliteIndex, SolverContracts.SatelliteView view,
        SolverContracts.LocalContext local, SolverContracts.LocalPlan plan);
    default List<ScheduledTask> rebuild(Context context, int satelliteIndex, SolverContracts.SatelliteView view,
        SolverContracts.LocalContext local, List<Integer> priorities) {
      return build(context, satelliteIndex, view, priorities);
    }
  }
  public record Components(SolverContracts.A allocation, SolverContracts.B search, SolverContracts.C decoder) {
    public Components { Objects.requireNonNull(allocation); Objects.requireNonNull(search); Objects.requireNonNull(decoder); }
  }

  public static SolverContracts.A rkhmaAllocation() { return new RkhmaAllocation(); }

  private static Allocation sourceAllocation(RkhmaAllocation module) {
    return new Allocation() {
      @Override public Map<Integer, Integer> initialOwners(Context context) {
        module.bindSource(context.bridge());
        return RkhmaRuntime.initialOwners(module, context);
      }
      @Override public void configure(TaskAllocationCoordinator coordinator, Context context) {
        module.configureSource(coordinator);
      }
    };
  }
  public static SolverContracts.B rkhmaSearch() { return new RkhmaHybridSearch(); }

  private static Search sourceSearch(RkhmaHybridSearch module) {
    return new Search() {
      @Override public void configure(TaskAllocationCoordinator coordinator, Context context, Decoder decoder) {
        module.configureSource(coordinator);
      }
    };
  }
  public static SolverContracts.C rkhmaDecoder() { return new RkhmaGreedyDecoder(); }

  private static Decoder sourceDecoder(RkhmaGreedyDecoder module) {
    return new Decoder() {
      @Override public void configure(Context context) {
        module.configureSource(context.bridge(), context.deadline()::check);
      }
      @Override public List<ScheduledTask> build(Context context, int satelliteIndex,
          SolverContracts.SatelliteView view, List<Integer> priorities) {
        return module.build(context.problem(), context.access(), view, priorities,
            context.budget(), context.random());
      }
      @Override public SolverContracts.LocalResult applyLocal(Context context, int satelliteIndex,
          SolverContracts.SatelliteView view, SolverContracts.LocalContext local, SolverContracts.LocalPlan plan) {
        return module.applyLocal(context.problem(), context.access(), view, local, plan,
            context.budget(), context.random());
      }
      @Override public List<ScheduledTask> rebuild(Context context, int satelliteIndex,
          SolverContracts.SatelliteView view, SolverContracts.LocalContext local, List<Integer> priorities) {
        return module.rebuild(context.problem(), context.access(), view, local, priorities,
            context.budget(), context.random());
      }
    };
  }

  private static Decoder adaptDecoder(SolverContracts.C module) {
    Objects.requireNonNull(module);
    return new Decoder() {
      @Override public void configure(Context context) {
        for (int index = 0; index < context.bridge().satellites().size(); index++) {
          final int satelliteIndex = index;
          var satellite = context.bridge().satellites().get(index);
          satellite.setScheduleBuilder(new method.rkhma.legacy.model.Satellite.ScheduleBuilder() {
            @Override public List<method.rkhma.legacy.model.ScheduledTask> build(
                method.rkhma.legacy.model.Satellite sat, List<method.rkhma.legacy.model.Task> sequence) {
              return buildWithPreferences(sat, sequence, Set.of());
            }
            @Override public List<method.rkhma.legacy.model.ScheduledTask> buildWithPreferences(
                method.rkhma.legacy.model.Satellite sat, List<method.rkhma.legacy.model.Task> sequence,
                Set<Integer> preferences) {
              return guarded(context, () -> {
                context.budget().check();
                List<Integer> priorities = new ArrayList<>();
                Set<Integer> assigned = new java.util.LinkedHashSet<>();
                for (var task : sequence) if (task != null) {
                  int id = context.bridge().taskId(task.getId());
                  if (assigned.add(id)) priorities.add(id);
                }
                String hash = localHash(satelliteIndex, assigned, List.of(), context);
                var view = new SolverContracts.SatelliteView(context.bridge().satelliteId(satelliteIndex), assigned, List.of(), hash);
                Set<Integer> preferred = preferences.stream().map(context.bridge()::taskId)
                    .collect(java.util.stream.Collectors.toSet());
                var local = new SolverContracts.LocalContext(SolverContracts.Phase.REASSIGN, Set.of(), Set.of(), List.of(), preferred);
                List<ScheduledTask> built;
                synchronized (context.access()) {
                  var random = new SplittableRandom(context.config().seed() ^ satelliteIndex ^ hash.hashCode());
                  built = sat.isReassignmentPhase()
                      ? module.rebuild(context.problem(), context.access(), view, local, priorities, context.budget(), random)
                      : module.build(context.problem(), context.access(), view, priorities, context.budget(), random);
                }
                if (built == null || built.stream().anyMatch(o -> !assigned.contains(o.getTaskId())
                    || o.getSatelliteId() != view.satelliteId())
                    || !ScheduleValidator.evaluate(context.problem(), new SchedulingPlan(built)).feasible()) {
                  throw new IllegalArgumentException("invalid rebuilt timeline");
                }
                return built.stream().map(context.bridge()::toLegacy).toList();
              });
            }
          });
          satellite.setInsertionStrategy((task, ignored, schedule) -> guarded(context, () -> {
            context.budget().check();
            int taskId = context.bridge().taskId(task.getId());
            Set<Integer> assigned = new HashSet<>();
            satellite.getAssignedTasks().forEach(t -> assigned.add(context.bridge().taskId(t.getId())));
            assigned.add(taskId);
            List<ScheduledTask> observations = schedule.stream().map(context.bridge()::fromLegacy).toList();
            observations.forEach(o -> assigned.add(o.getTaskId()));
            String hash = localHash(satelliteIndex, assigned, observations, context);
            var view = new SolverContracts.SatelliteView(context.bridge().satelliteId(satelliteIndex), assigned, observations, hash);
            boolean reassign = satellite.isReassignmentPhase();
            var local = new SolverContracts.LocalContext(reassign ? SolverContracts.Phase.REASSIGN : SolverContracts.Phase.INIT,
                reassign ? Set.of(taskId) : Set.of(), Set.of(), brokenEdges(observations));
            var plan = new SolverContracts.LocalPlan(hash, Set.of(), List.of(taskId));
            var result = applyLocal(context, satelliteIndex, view, local, plan);
            validateEdit(context, view, plan, result);
            if (result.status() == SolverContracts.LocalStatus.NO_FEASIBLE_CHANGE) return null;
            var inserted = result.observations().stream().filter(o -> o.getTaskId() == taskId).findFirst().orElse(null);
            if (inserted == null) return null;
            int position = 0;
            while (position < schedule.size() && schedule.get(position).getStartTime() < inserted.getStartTime()) position++;
            return new method.rkhma.legacy.operator.insertion.InsertionStrategy.InsertionResult(
                context.bridge().toLegacy(inserted), position);
          }));
        }
      }

      @Override public List<ScheduledTask> build(Context context, int satelliteIndex,
          SolverContracts.SatelliteView view, List<Integer> priorities) {
        synchronized (context.access()) {
          return module.build(context.problem(), context.access(), view, priorities,
              context.budget(), new SplittableRandom(context.config().seed() ^ satelliteIndex ^ view.hash().hashCode()));
        }
      }

      @Override public SolverContracts.LocalResult applyLocal(Context context, int satelliteIndex,
          SolverContracts.SatelliteView view, SolverContracts.LocalContext local, SolverContracts.LocalPlan plan) {
        synchronized (context.access()) {
          return module.applyLocal(context.problem(), context.access(), view, local, plan,
              context.budget(), new SplittableRandom(context.config().seed() ^ satelliteIndex ^ view.hash().hashCode()));
        }
      }

      @Override public List<ScheduledTask> rebuild(Context context, int satelliteIndex,
          SolverContracts.SatelliteView view, SolverContracts.LocalContext local, List<Integer> priorities) {
        synchronized (context.access()) {
          return module.rebuild(context.problem(), context.access(), view, local, priorities,
              context.budget(), new SplittableRandom(context.config().seed() ^ satelliteIndex ^ view.hash().hashCode()));
        }
      }
    };
  }
  public static Components rkhma() {
    return new Components(rkhmaAllocation(), rkhmaSearch(), rkhmaDecoder());
  }

  /** A3/B3/C3 使用 RKHMA 来源组件；A3 遵循统一初始化流程。 */
  public static Components combination(String name) {
    if (name == null || !name.matches("A[123]B[123]C[123]") || !name.contains("3")) {
      throw new IllegalArgumentException("RKHMA组合至少包含一个编号3的模块");
    }
    SolverContracts.A a = switch (name.charAt(1)) {
      case '1' -> ManualStrategies.allocationByCandidates();
      case '2' -> ManualStrategies.allocationByScarcityAndLoad();
      default -> rkhmaAllocation();
    };
    SolverContracts.B b = switch (name.charAt(3)) {
      case '1' -> ManualStrategies.priorityByDensity();
      case '2' -> ManualStrategies.priorityByScarcity();
      default -> rkhmaSearch();
    };
    SolverContracts.C c = switch (name.charAt(5)) {
      case '1' -> ManualStrategies.earliestFinish();
      case '2' -> ManualStrategies.minimumAddedTransition();
      default -> rkhmaDecoder();
    };
    return new Components(a, b, c);
  }

  private static Map<Integer, Integer> initialOwners(SolverContracts.A module, Context context) {
    var assignment = module.initialAssign(context.problem(), context.access(), context.budget(), context.random());
    if (assignment == null || assignment.owners().size() != context.problem().tasks().size()) {
      throw new IllegalArgumentException("invalid initial assignment");
    }
    for (var task : context.problem().tasks()) {
      if (!assignment.owners().containsKey(task.getId())) throw new IllegalArgumentException("missing task owner");
    }
    Map<Integer, Integer> owners = new LinkedHashMap<>();
    assignment.owners().forEach((task, satellite) -> {
      if (context.problem().task(task) == null) throw new IllegalArgumentException("unknown task owner");
      if (satellite != null) {
        if (context.problem().satellite(satellite) == null
            || !context.access().hasCandidate(task, satellite, context.budget())) {
          throw new IllegalArgumentException("invalid satellite owner");
        }
        owners.put(context.bridge().taskIndex(task), context.bridge().satelliteIndex(satellite));
      }
    });
    return owners;
  }

  private static Allocation adaptAllocation(SolverContracts.A module, SolverContracts.B search) {
    Objects.requireNonNull(module);
    return new Allocation() {
      @Override public Map<Integer, Integer> initialOwners(Context context) {
        return RkhmaRuntime.initialOwners(module, context);
      }

      @Override public void configure(TaskAllocationCoordinator coordinator, Context context) {
        coordinator.setPlanValidator(plans -> guarded(context, () -> checked(context, plans).feasible()));
        coordinator.setAssignmentPolicy(new TaskAllocationCoordinator.AssignmentPolicy() {
          @Override public TaskAllocationCoordinator.PolicyTrial search(TaskAllocationCoordinator.MoveContext moveContext,
              TaskAllocationCoordinator.PolicyEvaluator sourceEvaluator) {
            return guarded(context, () -> {
              var root = state(context, moveContext.owners(), moveContext.plans());
              var states = new IdentityHashMap<SolverContracts.State, TaskAllocationCoordinator.PolicyTrial>();
              var trials = new IdentityHashMap<SolverContracts.MoveTrial, TaskAllocationCoordinator.PolicyTrial>();
              states.put(root, null);
              try (var guard = new TrialGuard(context.budget())) {
                SolverContracts.MoveEvaluator evaluator = new SolverContracts.MoveEvaluator() {
                  @Override public SolverContracts.MoveTrial evaluate(SolverContracts.State base, SolverContracts.Move move) {
                    return guard.evaluate(() -> {
                      if (!states.containsKey(base)) throw new IllegalArgumentException("A used a state not issued by this evaluator");
                      if (move == null || !base.hash().equals(move.baseStateHash())) {
                        throw new IllegalArgumentException("stale assignment move");
                      }
                      Map<Integer, Integer> edits = new LinkedHashMap<>();
                      move.edits().forEach((task, satellite) -> {
                        if (context.problem().task(task) == null || satellite != null && context.problem().satellite(satellite) == null) {
                          throw new IllegalArgumentException("unknown task or satellite");
                        }
                        edits.put(context.bridge().taskIndex(task),
                            satellite == null ? null : context.bridge().satelliteIndex(satellite));
                      });
                      Set<Integer> repair = new java.util.LinkedHashSet<>();
                      for (int satellite : move.repairSatellites()) {
                        if (context.problem().satellite(satellite) == null) throw new IllegalArgumentException("unknown repair satellite");
                        repair.add(context.bridge().satelliteIndex(satellite));
                      }
                      if (!(search instanceof RkhmaHybridSearch)) {
                        search.onTrialBase(context.problem(), context.access(), base, context.budget());
                      }
                      TaskAllocationCoordinator.PolicyTrial sourceTrial;
                      try {
                        sourceTrial = sourceEvaluator.evaluate(states.get(base),
                            new TaskAllocationCoordinator.PolicyMove(edits, repair, move.fullRecalibration()));
                      } catch (RuntimeException error) {
                        throw error;
                      } catch (Exception error) {
                        throw new IllegalStateException("assignment trial failed", error);
                      }
                      SolverContracts.State candidate = sourceTrial.profit() == null ? null
                          : state(context, sourceTrial.owners(), sourceTrial.plans());
                      var trial = new SolverContracts.MoveTrial(move, candidate,
                          candidate == null ? null : checked(context, sourceTrial.plans()).totalProfit());
                      if (candidate != null) states.put(candidate, sourceTrial);
                      trials.put(trial, sourceTrial);
                      return trial;
                    });
                  }
                  @Override public void checkpoint(SolverContracts.MoveTrial trial) {
                    guard.evaluate(() -> {
                      guard.select(trial, true);
                      if (trial.state() == null) throw new IllegalArgumentException("cannot checkpoint an infeasible trial");
                      sourceEvaluator.checkpoint(trials.get(trial));
                      return trial;
                    });
                  }
                };
                var selected = guard.select(module.searchReassignment(context.problem(), context.access(), root,
                    new SolverContracts.Feedback(moveContext.attempt(), moveContext.previousOutcome()),
                    evaluator, context.budget(), context.random()), false);
                return selected == null ? null : trials.get(selected);
              }
            });
          }

          @Override public void onAccepted(TaskAllocationCoordinator.PolicyTrial trial) {
            if (!(search instanceof RkhmaHybridSearch)) {
              search.onAccepted(context.problem(), context.access(), state(context, trial.owners(), trial.plans()), context.budget());
            }
          }

          @Override public boolean acceptsNonImprovingResult() { return module.acceptsNonImprovingResult(); }
        });
      }
    };
  }

  private static Search adaptSearch(SolverContracts.B module) {
    Objects.requireNonNull(module);
    return new Search() {
      @Override public void configure(TaskAllocationCoordinator coordinator, Context context, Decoder decoder) {
        coordinator.setCalibrationStart((owners, plans) -> guarded(context, () -> {
          module.beginCalibration(context.problem(), context.access(), state(context, owners, plans), context.budget());
          return null;
        }));
        coordinator.setPopulationSearch((population, original) -> guarded(context, () -> {
          boolean reassign = population.satellite().isReassignmentPhase();
          var observations = runManualSearch(context, module, decoder, population.satellite().getId(),
              population.tasks(), population.anchorPlan(), null, reassign, reassign, population.random());
          var plan = legacyPlan(context, population.tasks(), observations);
          double fitness = plan.getTotalProfit();
          double[] vector = vector(context, population.tasks(), observations);
          var result = new method.rkhma.legacy.core.HybCec.HybResult(vector, fitness, fitness,
              List.of(fitness), List.of(1), 0, null);
          return new TaskAllocationCoordinator.PopulationResult(result, plan);
        }));
        coordinator.setLocalRefinement((local, original) -> guarded(context, () -> {
          var observations = runManualSearch(context, module, decoder, local.satellite().getId(),
              local.tasks(), local.anchorPlan(), local.previousTasks(), true, false,
              new SplittableRandom(context.config().seed() ^ local.satellite().getId() ^ local.iteration()));
          return legacyPlan(context, local.tasks(), observations);
        }));
      }
    };
  }

  private static List<ScheduledTask> runManualSearch(Context context, SolverContracts.B module, Decoder decoder,
      int satelliteIndex, List<method.rkhma.legacy.model.Task> tasks,
      method.rkhma.legacy.model.SchedulingPlan anchor, List<method.rkhma.legacy.model.Task> previousTasks,
      boolean reassign, boolean calibration, RandomGenerator random) {
    Set<Integer> assigned = new HashSet<>();
    tasks.forEach(task -> assigned.add(context.bridge().taskId(task.getId())));
    Set<Integer> previous = new HashSet<>();
    var previousSequence = previousTasks != null ? previousTasks : anchor == null ? List.<method.rkhma.legacy.model.Task>of()
        : anchor.getTaskSequence();
    previousSequence.forEach(task -> previous.add(context.bridge().taskId(task.getId())));
    Set<Integer> incoming = new HashSet<>(assigned);
    incoming.removeAll(previous);
    Set<Integer> outgoing = new HashSet<>(previous);
    outgoing.removeAll(assigned);
    List<ScheduledTask> observations = new ArrayList<>();
    if (reassign && anchor != null) {
      for (var observation : anchor.getSchedule()) {
        var converted = context.bridge().fromLegacy(observation);
        if (assigned.contains(converted.getTaskId())) observations.add(converted);
      }
    }
    if (!reassign) {
      String viewHash = localHash(satelliteIndex, assigned, List.of(), context);
      var view = new SolverContracts.SatelliteView(context.bridge().satelliteId(satelliteIndex), assigned, List.of(), viewHash);
      var selected = searchInitial(context, module, decoder, satelliteIndex, view, random);
      observations = new ArrayList<>(selected.observations());
    }
    var limits = TwoStageSolver.Limits.defaults();
    long allowance = reassign ? limits.perSatelliteNanos()
        : limits.initialLocalNanos() / Math.max(1, context.bridge().satellites().size());
    Budget localBudget = reassign && module.usesInstanceBudgetForReassignment()
        ? context.budget() : context.budget().within(allowance);
    Context limited = new Context(context.problem(), context.bridge(), context.access(), localBudget,
        context.deadline(), context.config(), context.random());
    int attempt = 0;
    String previousOutcome = "START";
    try {
      while (true) {
        localBudget.check();
        String viewHash = localHash(satelliteIndex, assigned, observations, limited);
        var view = new SolverContracts.SatelliteView(context.bridge().satelliteId(satelliteIndex), assigned, observations, viewHash);
        var local = new SolverContracts.LocalContext(reassign ? SolverContracts.Phase.REASSIGN : SolverContracts.Phase.INIT,
            reassign ? incoming : Set.of(), reassign ? outgoing : Set.of(), brokenEdges(observations));
        var selected = evaluateLocalSession(limited, module, decoder, satelliteIndex, view, local,
            new SolverContracts.Feedback(attempt, previousOutcome), random, calibration);
        if (selected == null) break;
        var result = selected.result();
        if (result.status() == SolverContracts.LocalStatus.NO_FEASIBLE_CHANGE) {
          previousOutcome = "NO_FEASIBLE_CHANGE";
        } else if (reassign || profit(limited, result.observations()) > profit(limited, observations)) {
          observations = new ArrayList<>(result.observations());
          previousOutcome = "ACCEPTED";
        } else {
          previousOutcome = "NO_IMPROVEMENT";
        }
        attempt++;
      }
    } catch (Budget.SearchExpired localExpired) {
      context.budget().check();
    }
    return List.copyOf(observations);
  }

  private static SolverContracts.InitialTrial searchInitial(Context context, SolverContracts.B module,
      Decoder decoder, int satelliteIndex, SolverContracts.SatelliteView view, RandomGenerator random) {
    try (var guard = new TrialGuard(context.budget())) {
      SolverContracts.InitialEvaluator evaluator = priorities -> guard.evaluate(() -> {
        List<Integer> order = priorities == null ? null : List.copyOf(priorities);
        validatePriorities(view, order);
        var built = decoder.build(context, satelliteIndex, view, order);
        validateBuilt(context, view, order, built);
        return new SolverContracts.InitialTrial(order, built, profit(context, built));
      });
      synchronized (module) {
        return guard.select(module.searchInitial(context.problem(), context.access(), view, evaluator,
            context.budget(), random), true);
      }
    }
  }

  private static SolverContracts.LocalTrial evaluateLocalSession(Context context, SolverContracts.B module,
      Decoder decoder, int satelliteIndex, SolverContracts.SatelliteView view,
      SolverContracts.LocalContext local, SolverContracts.Feedback feedback, RandomGenerator random, boolean calibration) {
    try (var guard = new TrialGuard(context.budget())) {
      SolverContracts.LocalEvaluator evaluator = new SolverContracts.LocalEvaluator() {
        @Override public SolverContracts.LocalTrial evaluate(SolverContracts.LocalPlan plan) {
          return guard.evaluate(() -> {
            validatePlan(view, plan);
            var result = decoder.applyLocal(context, satelliteIndex, view, local, plan);
            validateEdit(context, view, plan, result);
            return new SolverContracts.LocalTrial(plan, result,
                result.status() == SolverContracts.LocalStatus.APPLIED ? profit(context, result.observations()) : null);
          });
        }

        @Override public SolverContracts.LocalTrial rebuild(List<Integer> priorities) {
          return rebuild(priorities, Set.of());
        }

        @Override public SolverContracts.LocalTrial rebuild(List<Integer> priorities, Set<Integer> preferredWindowTasks) {
          return guard.evaluate(() -> {
            List<Integer> order = priorities == null ? null : List.copyOf(priorities);
            validatePriorities(view, order);
            Set<Integer> preferred = Set.copyOf(preferredWindowTasks);
            if (!view.assignedTaskIds().containsAll(preferred)) throw new IllegalArgumentException("invalid window preference tasks");
            Set<Integer> release = new HashSet<>();
            view.observations().forEach(o -> release.add(o.getTaskId()));
            var plan = new SolverContracts.LocalPlan(view.hash(), release, order);
            validatePlan(view, plan);
            String hash = localHash(satelliteIndex, view.assignedTaskIds(), List.of(), context);
            var empty = new SolverContracts.SatelliteView(view.satelliteId(), view.assignedTaskIds(), List.of(), hash);
            var rebuild = new SolverContracts.LocalContext(local.phase(), Set.of(), Set.of(), List.of(), preferred);
            var built = local.phase() == SolverContracts.Phase.INIT
                ? decoder.build(context, satelliteIndex, empty, order)
                : decoder.rebuild(context, satelliteIndex, empty, rebuild, order);
            validateBuilt(context, view, order, built);
            var result = SolverContracts.LocalResult.applied(built);
            validateEdit(context, view, plan, result);
            return new SolverContracts.LocalTrial(plan, result, profit(context, built));
          });
        }
      };
      synchronized (module) {
        var selected = local.phase() == SolverContracts.Phase.INIT
            ? module.searchInitialLocal(context.problem(), context.access(), view, local, feedback,
                evaluator, context.budget(), random)
            : calibration ? module.searchCalibration(context.problem(), context.access(), view, local, feedback,
                evaluator, context.budget(), random)
            : module.searchReassignment(context.problem(), context.access(), view, local, feedback,
                evaluator, context.budget(), random);
        return guard.select(selected, false);
      }
    }
  }

  private static void validatePriorities(SolverContracts.SatelliteView view, List<Integer> priorities) {
    if (priorities == null || priorities.stream().anyMatch(Objects::isNull)
        || new HashSet<>(priorities).size() != priorities.size()
        || !view.assignedTaskIds().containsAll(priorities)) {
      throw new IllegalArgumentException("invalid priority sequence");
    }
  }

  private static void validateBuilt(Context context, SolverContracts.SatelliteView view,
      List<Integer> priorities, List<ScheduledTask> built) {
    Set<Integer> allowed = new HashSet<>(priorities);
    if (built == null || built.stream().anyMatch(o -> o == null || !allowed.contains(o.getTaskId())
        || o.getSatelliteId() != view.satelliteId())
        || !ScheduleValidator.evaluate(context.problem(), new SchedulingPlan(built)).feasible()) {
      throw new IllegalArgumentException("invalid decoded timeline");
    }
  }

  private static long profit(Context context, List<ScheduledTask> observations) {
    long total = 0;
    for (var observation : observations) total += context.problem().task(observation.getTaskId()).getPriority();
    return total;
  }

  private static method.rkhma.legacy.model.SchedulingPlan legacyPlan(Context context,
      List<method.rkhma.legacy.model.Task> tasks, List<ScheduledTask> observations) {
    var plan = new method.rkhma.legacy.model.SchedulingPlan(new ArrayList<>(tasks));
    plan.setSchedule(observations.stream().map(context.bridge()::toLegacy).toList());
    plan.setFitness((double) plan.getTotalProfit());
    return plan;
  }

  private static double[] vector(Context context, List<method.rkhma.legacy.model.Task> tasks,
      List<ScheduledTask> observations) {
    Map<Integer, Integer> rank = new LinkedHashMap<>();
    observations.stream().sorted(java.util.Comparator.comparingDouble(ScheduledTask::getStartTime))
        .forEach(o -> rank.putIfAbsent(o.getTaskId(), rank.size()));
    tasks.forEach(task -> rank.putIfAbsent(context.bridge().taskId(task.getId()), rank.size()));
    double[] values = new double[tasks.size()];
    for (int i = 0; i < tasks.size(); i++) {
      values[i] = 1.0 - 2.0 * rank.get(context.bridge().taskId(tasks.get(i).getId())) / Math.max(1, tasks.size());
    }
    return values;
  }

  private static ScheduleValidator.Result checked(Context context,
      Map<Integer, method.rkhma.legacy.model.SchedulingPlan> plans) {
    List<ScheduledTask> observations = new ArrayList<>();
    for (var plan : plans.values()) for (var observation : plan.getSchedule()) {
      observations.add(context.bridge().fromLegacy(observation));
    }
    return ScheduleValidator.evaluate(context.problem(), new SchedulingPlan(observations));
  }

  private static <T> T guarded(Context context, java.util.function.Supplier<T> action) {
    try {
      return action.get();
    } catch (Budget.SearchExpired expired) {
      context.deadline().check();
      throw expired;
    }
  }

  private static String localHash(int satelliteIndex, Set<Integer> assigned, List<ScheduledTask> observations,
      Context context) {
    StringBuilder value = new StringBuilder("S").append(satelliteIndex).append(':');
    assigned.stream().sorted().forEach(id -> value.append('T').append(id).append(';'));
    observations.stream().sorted(java.util.Comparator.comparingDouble(ScheduledTask::getStartTime)
        .thenComparingInt(ScheduledTask::getTaskId)).forEach(o -> value.append(o.getTaskId()).append('/')
            .append(context.access().windowIndex(o.timeWindow())).append('/').append(o.getStartTime()).append('/')
            .append(o.getEndTime()).append(';'));
    return hash(value.toString());
  }

  private static String hash(String value) {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static List<SolverContracts.BrokenEdge> brokenEdges(List<ScheduledTask> observations) {
    var sorted = observations.stream().sorted(java.util.Comparator.comparingDouble(ScheduledTask::getStartTime)).toList();
    List<SolverContracts.BrokenEdge> result = new ArrayList<>();
    for (int i = 1; i < sorted.size(); i++) {
      var before = sorted.get(i - 1);
      var after = sorted.get(i);
      if (!utils.TimeUtils.checkTransformationTime(before.endRecord(), after.startRecord())) {
        result.add(new SolverContracts.BrokenEdge(before.getTaskId(), after.getTaskId()));
      }
    }
    return List.copyOf(result);
  }

  private static boolean sameObservation(ScheduledTask left, ScheduledTask right) {
    return left.task() == right.task() && left.timeWindow() == right.timeWindow()
        && left.startRecord() == right.startRecord() && left.endRecord() == right.endRecord();
  }

  private static void validateEdit(Context context, SolverContracts.SatelliteView view,
      SolverContracts.LocalPlan plan, SolverContracts.LocalResult result) {
    validatePlan(view, plan);
    if (result == null) {
      throw new IllegalArgumentException("stale plan or null local result");
    }
    Map<Integer, ScheduledTask> before = new LinkedHashMap<>();
    for (var o : view.observations()) before.put(o.getTaskId(), o);
    if (result.status() == SolverContracts.LocalStatus.NO_FEASIBLE_CHANGE) {
      if (!result.observations().isEmpty()) throw new IllegalArgumentException("invalid no-change result");
      return;
    }
    Map<Integer, ScheduledTask> after = new LinkedHashMap<>();
    for (var o : result.observations()) {
      if (o.getSatelliteId() != view.satelliteId() || !view.assignedTaskIds().contains(o.getTaskId())
          || after.putIfAbsent(o.getTaskId(), o) != null) throw new IllegalArgumentException("invalid local observation");
    }
    for (var entry : before.entrySet()) {
      var next = after.get(entry.getKey());
      if (next == null && !plan.release().contains(entry.getKey())
          || next != null && !sameObservation(entry.getValue(), next)
              && (!plan.release().contains(entry.getKey()) || !plan.reconsider().contains(entry.getKey()))) {
        throw new IllegalArgumentException("unauthorized local edit");
      }
    }
    for (int task : after.keySet()) if (!before.containsKey(task) && !plan.reconsider().contains(task)) {
      throw new IllegalArgumentException("unauthorized local insertion");
    }
    var check = ScheduleValidator.evaluate(context.problem(), new SchedulingPlan(result.observations()));
    if (!check.feasible()) throw new IllegalArgumentException("infeasible local edit: " + check.violations());
  }

  private static void validatePlan(SolverContracts.SatelliteView view, SolverContracts.LocalPlan plan) {
    if (plan == null || !view.hash().equals(plan.baseViewHash())) throw new IllegalArgumentException("stale local plan");
    Set<Integer> before = new HashSet<>();
    view.observations().forEach(o -> before.add(o.getTaskId()));
    if (!before.containsAll(plan.release()) || !view.assignedTaskIds().containsAll(plan.reconsider())
        || new HashSet<>(plan.reconsider()).size() != plan.reconsider().size()) {
      throw new IllegalArgumentException("invalid local plan");
    }
    for (int task : plan.reconsider()) if (before.contains(task) && !plan.release().contains(task)) {
      throw new IllegalArgumentException("scheduled task not released");
    }
  }

  private static SolverContracts.State state(Context context, Map<Integer, Integer> legacyOwners,
      Map<Integer, method.rkhma.legacy.model.SchedulingPlan> plans) {
    Map<Integer, Integer> owners = new LinkedHashMap<>();
    context.problem().tasks().forEach(task -> owners.put(task.getId(), null));
    legacyOwners.forEach((task, sat) -> owners.put(context.bridge().taskId(task), context.bridge().satelliteId(sat)));
    Map<Integer, List<ScheduledTask>> timelines = new LinkedHashMap<>();
    for (var sat : context.problem().satellites()) timelines.put(sat.getId(), List.of());
    plans.forEach((sat, plan) -> timelines.put(context.bridge().satelliteId(sat),
        plan.getSchedule().stream().map(context.bridge()::fromLegacy).toList()));
    return TwoStageSolver.state(new SolverContracts.Assignment(owners), timelines, context.access());
  }

  public static Result solve(ProblemInstance problem, Components components, Config config) throws Exception {
    Objects.requireNonNull(problem);
    Objects.requireNonNull(components);
    Objects.requireNonNull(config);
    long started = System.nanoTime();
    Budget budget = new Budget(System::nanoTime, started, config.hardNanos(), config.searchNanos());
    SearchDeadline deadline = SearchDeadline.startingAt(Duration.ofNanos(config.searchNanos()), System::nanoTime, started);
    Map<Integer, Integer> emptyOwners = new LinkedHashMap<>();
    problem.tasks().forEach(task -> emptyOwners.put(task.getId(), null));
    try {
      if (problem.tasks().isEmpty() || problem.satellites().isEmpty()) {
        return new Result(TwoStageSolver.Status.SUCCESS, new SchedulingPlan(List.of()), 0L, emptyOwners,
            List.of(), false, "COMPLETED", System.nanoTime() - started);
      }
      ProblemAccess access = new ProblemAccess(problem, budget);
      ProblemBridge bridge = new ProblemBridge(problem, budget::check);
      budget.check();
      boolean adaptedGrid = false;
      for (int i = 0; i < bridge.satellites().size(); i++) adaptedGrid |= !bridge.sourceCompatible(i);
      Context context = new Context(problem, bridge, access, budget, deadline, config, new SplittableRandom(config.seed()));
      RandomProvider.reset(config.seed());
      Decoder decoder = components.decoder() instanceof RkhmaGreedyDecoder source
          ? sourceDecoder(source) : adaptDecoder(components.decoder());
      decoder.configure(context);
      Allocation allocation = components.allocation() instanceof RkhmaAllocation source
          ? sourceAllocation(source) : adaptAllocation(components.allocation(), components.search());
      Map<Integer, Integer> initial = allocation.initialOwners(context);
      budget.check();
      bridge.satellites().forEach(method.rkhma.legacy.model.Satellite::clearTasks);
      for (var entry : initial.entrySet()) {
        if (entry.getValue() != null) {
          if (entry.getKey() < 0 || entry.getKey() >= bridge.tasks().size()
              || entry.getValue() < 0 || entry.getValue() >= bridge.satellites().size()) {
            throw new IllegalArgumentException("invalid initial assignment");
          }
          bridge.satellites().get(entry.getValue()).addTask(bridge.tasks().get(entry.getKey()));
        }
      }
      int population = config.population();
      int evaluations = config.evaluations();
      if (population == 0) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        initial.values().forEach(satellite -> { if (satellite != null) counts.merge(satellite, 1, Integer::sum); });
        int maximum = Math.max(1, counts.values().stream().mapToInt(Integer::intValue).max().orElse(0));
        long proposedPopulation = Math.max(6, Math.round(2.5 * maximum));
        long proposedEvaluations = Math.max(proposedPopulation, Math.round(2.5 * maximum * 100));
        if (proposedPopulation > Integer.MAX_VALUE || proposedEvaluations > Integer.MAX_VALUE) {
          throw new IllegalArgumentException("RKHMA population or evaluation count is too large");
        }
        population = (int) proposedPopulation;
        evaluations = (int) proposedEvaluations;
      }
      var coordinator = new TaskAllocationCoordinator(bridge.tasks(), bridge.satellites(), new MaxWindowCountStrategy(),
          config.reassignIterations(), config.perturbThreshold(), config.perturbStrength());
      Search search = components.search() instanceof RkhmaHybridSearch source
          ? sourceSearch(source) : adaptSearch(components.search());
      if (!(components.allocation() instanceof RkhmaAllocation) || !(components.search() instanceof RkhmaHybridSearch)) {
        Map<Integer, Integer> commonOwners = new LinkedHashMap<>(emptyOwners);
        initial.forEach((task, satellite) ->
            commonOwners.put(bridge.taskId(task), satellite == null ? null : bridge.satelliteId(satellite)));
        var assignment = new SolverContracts.Assignment(commonOwners);
        if (!(components.allocation() instanceof RkhmaAllocation)) {
          components.allocation().prepare(problem, access, assignment, budget, config.seed());
        }
        if (!(components.search() instanceof RkhmaHybridSearch)) {
          components.search().prepare(problem, access, assignment, budget, config.seed());
        }
      }
      search.configure(coordinator, context, decoder);
      allocation.configure(coordinator, context);
      var legacyConfig = new TaskAllocationCoordinator.HybCecConfig(new int[]{4, 1}, new int[]{0, 0}, 0, 0, 0,
          population, evaluations, InsertionMode.GREEDY, config.seed());
      var plans = coordinator.scheduleWithReassignment(legacyConfig, bridge.satellites(), initial, deadline);
      budget.checkHard();
      List<ScheduledTask> observations = new ArrayList<>();
      for (var plan : plans.values()) for (var observation : plan.getSchedule()) observations.add(bridge.fromLegacy(observation));
      SchedulingPlan output = new SchedulingPlan(observations);
      var checked = ScheduleValidator.evaluate(problem, output);
      budget.checkHard();
      if (!checked.feasible()) throw new IllegalStateException("RKHMA produced infeasible output: " + checked.violations());
      Map<Integer, Integer> owners = new LinkedHashMap<>(emptyOwners);
      coordinator.getBestTaskAssignmentSnapshot().forEach((task, satellite) ->
          owners.put(bridge.taskId(task), bridge.satelliteId(satellite)));
      return new Result(TwoStageSolver.Status.SUCCESS, output, checked.totalProfit(), owners,
          coordinator.getStrategyHistorySnapshot(), adaptedGrid, deadline.expired() ? "SEARCH_EXPIRED" : "COMPLETED",
          System.nanoTime() - started);
    } catch (Budget.SearchExpired | SearchDeadline.Expired expired) {
      return new Result(TwoStageSolver.Status.SUCCESS, new SchedulingPlan(List.of()), 0L, emptyOwners,
          List.of(), false, "SEARCH_EXPIRED", System.nanoTime() - started);
    } catch (Budget.HardTimeout timeout) {
      return new Result(TwoStageSolver.Status.HARD_TIMEOUT, null, null, emptyOwners, List.of(), false, "HARD_TIMEOUT",
          System.nanoTime() - started);
    } catch (Exception failure) {
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof Budget.HardTimeout) {
          return new Result(TwoStageSolver.Status.HARD_TIMEOUT, null, null, emptyOwners, List.of(), false, "HARD_TIMEOUT",
              System.nanoTime() - started);
        }
      }
      try { budget.checkHard(); }
      catch (Budget.HardTimeout timeout) {
        return new Result(TwoStageSolver.Status.HARD_TIMEOUT, null, null, emptyOwners, List.of(), false, "HARD_TIMEOUT",
            System.nanoTime() - started);
      }
      return new Result(TwoStageSolver.Status.FAILURE, null, null, emptyOwners, List.of(), false,
          failure.getClass().getSimpleName() + ": " + failure.getMessage(), System.nanoTime() - started);
    }
  }
}
