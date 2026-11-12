package method.rkhma.legacy.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Scheduling Plan
 * Represents a complete scheduling solution (chromosome in GA).
 * Contains a task sequence and the resulting schedule.
 */
public class SchedulingPlan {

  private static final double DEFAULT_FITNESS = 0.0;

  private List<Task> taskSequence;  // Chromosome: task execution order
  private List<ScheduledTask> schedule; // Actual scheduled tasks
  private Double fitness;
  private boolean fitnessCalculated;

  /**
   * Constructor
   * 
   * @param taskSequence Task execution order (must not be null)
   * @throws IllegalArgumentException if taskSequence is null
   */
  public SchedulingPlan(List<Task> taskSequence) {
    if (taskSequence == null) {
      throw new IllegalArgumentException("Task sequence cannot be null");
    }
    this.taskSequence = new ArrayList<>(taskSequence);
    this.schedule = new ArrayList<>();
    this.fitness = DEFAULT_FITNESS;
    this.fitnessCalculated = false;
  }

  /**
   * Deep copy constructor
   * Creates a deep copy of another scheduling plan
   * 
   * @param other The scheduling plan to copy
   * @throws IllegalArgumentException if other is null
   */
  public SchedulingPlan(SchedulingPlan other) {
    if (other == null) {
      throw new IllegalArgumentException("Other scheduling plan cannot be null");
    }
    this.taskSequence = new ArrayList<>(other.taskSequence);
    this.schedule = new ArrayList<>(other.schedule);
    this.fitness = other.fitness;
    this.fitnessCalculated = other.fitnessCalculated;
  }

  /**
   * Deep copy
   * 
   * @return A deep copy of this scheduling plan
   */
  public SchedulingPlan copy() {
    return new SchedulingPlan(this);
  }

  /**
   * Clear schedule and reset fitness
   */
  public void clearSchedule() {
    this.schedule.clear();
    this.fitness = DEFAULT_FITNESS;
    this.fitnessCalculated = false;
  }

  /**
   * Set the complete schedule
   * This will invalidate the fitness calculation
   * 
   * @param schedule List of scheduled tasks (defensive copy is made)
   */
  public void setSchedule(List<ScheduledTask> schedule) {
    this.schedule = schedule != null ? new ArrayList<>(schedule) : new ArrayList<>();
    this.fitnessCalculated = false;
  }

  /**
   * Set the schedule by reference for hot paths that create and own the list.
   * The caller must not mutate the list after handing it to this plan.
   */
  public void setScheduleUnsafe(List<ScheduledTask> schedule) {
    this.schedule = schedule != null ? schedule : new ArrayList<>();
    this.fitnessCalculated = false;
  }

  /**
   * Add a scheduled task to the schedule
   * 
   * @param scheduledTask Task to add (must not be null)
   * @throws IllegalArgumentException if scheduledTask is null
   */
  public void addScheduledTask(ScheduledTask scheduledTask) {
    if (scheduledTask == null) {
      throw new IllegalArgumentException("Scheduled task cannot be null");
    }
    this.schedule.add(scheduledTask);
    this.fitnessCalculated = false;
  }

  /**
   * Remove a scheduled task from the schedule
   * 
   * @param scheduledTask Task to remove
   * @return true if the task was removed, false otherwise
   */
  public boolean removeScheduledTask(ScheduledTask scheduledTask) {
    boolean removed = this.schedule.remove(scheduledTask);
    if (removed) {
      this.fitnessCalculated = false;
    }
    return removed;
  }

  /**
   * Get number of scheduled tasks
   * 
   * @return Number of scheduled tasks
   */
  public int getScheduledTaskCount() {
    return schedule.size();
  }

  /**
   * Get number of tasks in the sequence
   * 
   * @return Number of tasks in the sequence
   */
  public int getTaskSequenceSize() {
    return taskSequence.size();
  }

  /**
   * Get total profit of scheduled tasks
   * 
   * @return Total profit (sum of priorities)
   */
  public int getTotalProfit() {
    int totalProfit = 0;
    for (int i = 0; i < schedule.size(); i++) {
      totalProfit += schedule.get(i).getPriority();
    }
    return totalProfit;
  }

  /**
   * Check if the plan has any scheduled tasks
   * 
   * @return true if there are scheduled tasks, false otherwise
   */
  public boolean hasScheduledTasks() {
    return !schedule.isEmpty();
  }

  /**
   * Check if the plan is empty (no tasks in sequence)
   * 
   * @return true if the task sequence is empty, false otherwise
   */
  public boolean isEmpty() {
    return taskSequence.isEmpty();
  }

  // Getters
  /**
   * Get task sequence (defensive copy)
   * 
   * @return Unmodifiable view of the task sequence
   */
  public List<Task> getTaskSequence() {
    return Collections.unmodifiableList(taskSequence);
  }

  /**
   * Get schedule (defensive copy)
   * 
   * @return Unmodifiable view of the schedule
   */
  public List<ScheduledTask> getSchedule() {
    return Collections.unmodifiableList(schedule);
  }

  /**
   * Get fitness value
   * 
   * @return Fitness value, or DEFAULT_FITNESS if not calculated
   */
  public Double getFitness() {
    return fitness;
  }

  /**
   * Check if fitness has been calculated
   * 
   * @return true if fitness is calculated, false otherwise
   */
  public boolean isFitnessCalculated() {
    return fitnessCalculated;
  }

  // Setters
  /**
   * Set task sequence
   * This will invalidate the fitness calculation
   * 
   * @param sequence New task sequence (defensive copy is made)
   * @throws IllegalArgumentException if sequence is null
   */
  public void setTaskSequence(List<Task> sequence) {
    if (sequence == null) {
      throw new IllegalArgumentException("Task sequence cannot be null");
    }
    this.taskSequence = new ArrayList<>(sequence);
    this.fitnessCalculated = false;
  }

  /**
   * Set fitness value
   * This will mark fitness as calculated
   * 
   * @param fitness Fitness value
   */
  public void setFitness(Double fitness) {
    this.fitness = fitness != null ? fitness : DEFAULT_FITNESS;
    this.fitnessCalculated = true;
  }

  /**
   * Invalidate fitness calculation
   * Call this when the plan is modified in a way that affects fitness
   */
  public void invalidateFitness() {
    this.fitnessCalculated = false;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SchedulingPlan that = (SchedulingPlan) o;
    return Objects.equals(taskSequence, that.taskSequence) &&
    Objects.equals(schedule, that.schedule);
  }

  @Override
  public int hashCode() {
    return Objects.hash(taskSequence, schedule);
  }

  @Override
  public String toString() {
    return String.format("SchedulingPlan{sequenceSize=%d, scheduledCount=%d, fitness=%.3f, calculated=%s}",
      taskSequence.size(), schedule.size(), fitness, fitnessCalculated);
  }
}
