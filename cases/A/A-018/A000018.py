"""参考MTHG的广义指派启发式。

Silvano Martello and Paolo Toth, Knapsack Problems: Algorithms and Computer
Implementations, Wiley, 1990. 来源使用条件保存在案例后台。
"""

import struct
from dataclasses import dataclass


@dataclass(frozen=True)
class Assignment:
    value: int
    resource_by_task: tuple[int, ...]


def _float32(value):
    return struct.unpack(">f", struct.pack(">f", value))[0]


def _greedy_profit(profit, weight, capacity):
    resources, tasks = len(capacity), len(profit[0])
    remaining, max_weight = list(capacity), [0] * resources
    first, second, penalty, pending = [0] * tasks, [-1] * tasks, [0] * tasks, list(range(tasks))
    upper = threshold = 0
    infinity = max(capacity)
    for task in range(tasks):
        minimum = best = profit[0][task]
        runner_up, best_resource, second_resource = 0, 0, -1
        for resource in range(1, resources):
            value = profit[resource][task]
            minimum = min(minimum, value)
            if runner_up >= value:
                continue
            if best >= value:
                runner_up, second_resource = value, resource
            else:
                runner_up, second_resource, best, best_resource = best, best_resource, value, resource
        threshold += minimum
        first[task], second[task] = best_resource, second_resource
        penalty[task] = -1 if runner_up == 0 else best - runner_up
        upper += best
        max_weight[best_resource] = max(max_weight[best_resource], weight[best_resource][task])
        if second_resource >= 0:
            max_weight[second_resource] = max(max_weight[second_resource], weight[second_resource][task])
    if threshold > 0:
        threshold -= 1
    infinity = max(infinity, upper)
    penalty = [infinity if value == -1 else value for value in penalty]
    value, count, assignment = 0, tasks, [-1] * tasks
    while count > 0:
        selected = 0
        for index in range(1, count):
            if penalty[pending[index]] > penalty[pending[selected]]:
                selected = index
        task, resource = pending[selected], first[pending[selected]]
        value += profit[resource][task]
        assignment[task] = resource
        count -= 1
        pending[selected] = pending[count]
        if count == 0:
            break
        remaining[resource] -= weight[resource][task]
        if max_weight[resource] <= remaining[resource]:
            continue
        for index in range(count):
            task = pending[index]
            if weight[resource][task] <= remaining[resource]:
                continue
            if first[task] == resource:
                if penalty[task] == infinity:
                    return threshold, assignment, upper, threshold, infinity
                first[task] = second[task]
            elif second[task] != resource:
                continue
            best, second_resource = 0, -1
            for other in range(resources):
                if (other != first[task] and weight[other][task] <= remaining[other]
                        and profit[other][task] > best):
                    best, second_resource = profit[other][task], other
            if second_resource < 0:
                penalty[task] = infinity
            else:
                second[task], penalty[task] = second_resource, profit[first[task]][task] - best
                max_weight[second_resource] = max(max_weight[second_resource], weight[second_resource][task])
    return value, assignment, upper, threshold, infinity


def _update_options(profit, weight, remaining, reserve, max_weight, first, second, penalty,
                    task, infinity, parameters):
    float_infinity = _float32(infinity)
    best = runner_up = -float_infinity
    best_resource = second_resource = -1
    for resource, capacity in enumerate(remaining):
        if weight[resource][task] > capacity:
            continue
        if weight[resource][task] > reserve[resource]:
            weight[resource][task] = capacity
        w = _float32(weight[resource][task])
        value = _float32(profit[resource][task])
        room = _float32(capacity)
        numerator = _float32(_float32(-parameters[0] * w) + _float32(parameters[1] * value))
        denominator = _float32(_float32(_float32(parameters[2] * room)
                                        + _float32(parameters[3] * w)) + parameters[4])
        score = _float32(numerator / denominator)
        if runner_up >= score:
            continue
        if best >= score:
            runner_up, second_resource = score, resource
        else:
            runner_up, second_resource, best, best_resource = best, best_resource, score, resource
    if best_resource < 0:
        return False
    first[task], second[task] = best_resource, second_resource
    penalty[task] = _float32(best - runner_up) if runner_up > -float_infinity else float_infinity
    max_weight[best_resource] = max(max_weight[best_resource], weight[best_resource][task])
    if second_resource >= 0:
        max_weight[second_resource] = max(max_weight[second_resource], weight[second_resource][task])
    return True


def _alternative(profit, weight, capacity, infinity, parameters):
    resources, tasks = len(capacity), len(profit[0])
    remaining, max_weight = list(capacity), [0] * resources
    adjusted = [list(row) for row in weight]
    min_weight = [min(infinity, *row) for row in adjusted]
    reserve = [remaining[i] - min_weight[i] for i in range(resources)]
    first, second, pending, assignment = [-1] * tasks, [-1] * tasks, list(range(tasks)), [-1] * tasks
    penalty = [0.0] * tasks

    def update(task):
        return _update_options(profit, adjusted, remaining, reserve, max_weight, first, second,
                               penalty, task, infinity, parameters)

    for task in range(tasks):
        if not update(task):
            return 0, assignment
    count, value = tasks, 0
    while count > 0:
        selected = 0
        for index in range(1, count):
            if penalty[pending[index]] > penalty[pending[selected]]:
                selected = index
        task, resource = pending[selected], first[pending[selected]]
        value += profit[resource][task]
        assignment[task] = resource
        count -= 1
        pending[selected] = pending[count]
        remaining[resource] -= weight[resource][task]
        if count == 0:
            break
        changed, any_changed = [False] * resources, False
        for other in range(resources):
            if adjusted[other][task] <= min_weight[other]:
                min_weight[other] = min([infinity] + [adjusted[other][pending[index]] for index in range(count)])
                if min_weight[other] + max_weight[other] > remaining[other]:
                    changed[other], any_changed = True, True
            reserve[other] = remaining[other] - min_weight[other]
        if max_weight[resource] > remaining[resource]:
            changed[resource], any_changed = True, True
        if not any_changed:
            continue
        for index in range(count):
            task = pending[index]
            best = first[task]
            if penalty[task] >= _float32(infinity):
                if adjusted[best][task] > remaining[best]:
                    return 0, assignment
                continue
            needs_update = changed[best] and adjusted[best][task] > reserve[best]
            following = second[task]
            if not needs_update and following >= 0 and changed[following] and adjusted[following][task] > reserve[following]:
                needs_update = True
            if needs_update and not update(task):
                return 0, assignment
    for task in range(tasks):
        old = best = assignment[task]
        best_profit = profit[old][task]
        for resource in range(resources):
            if weight[resource][task] <= remaining[resource] and profit[resource][task] > best_profit:
                best, best_profit = resource, profit[resource][task]
        if best != old:
            assignment[task] = best
            value += profit[best][task] - profit[old][task]
            remaining[old] += weight[old][task]
            remaining[best] -= weight[best][task]
    return value, assignment


def solve(profit, weight, capacity, minimize):
    resources = len(capacity)
    if not 2 <= resources <= 50 or len(profit) != resources or len(weight) != resources:
        raise ValueError("the number of resources must be 2..50")
    tasks = len(profit[0])
    if not 2 <= tasks <= 500:
        raise ValueError("the number of tasks must be 2..500")
    largest_profit = largest_weight = largest_capacity = 0
    for resource in range(resources):
        if capacity[resource] <= 0 or len(profit[resource]) != tasks or len(weight[resource]) != tasks:
            raise ValueError("matrix dimensions and capacities must be valid")
        largest_capacity = max(largest_capacity, capacity[resource])
        usable = False
        for task in range(tasks):
            if profit[resource][task] <= 0 or weight[resource][task] <= 0:
                raise ValueError("values must be positive")
            largest_profit = max(largest_profit, profit[resource][task])
            largest_weight = max(largest_weight, weight[resource][task])
            if weight[resource][task] <= capacity[resource]:
                usable = True
        if not usable:
            raise ValueError("every resource must fit at least one task")
    if (largest_profit + 1) * tasks + max(largest_weight, largest_capacity) > 2**31 - 1:
        raise ValueError("integer values exceed the source arithmetic range")
    transform = largest_profit + 1
    adjusted = [[0] * tasks for _ in range(resources)]
    for task in range(tasks):
        fits = False
        for resource in range(resources):
            if weight[resource][task] <= capacity[resource]:
                adjusted[resource][task] = transform - profit[resource][task] if minimize else profit[resource][task]
                fits = True
        if not fits:
            return Assignment(0, tuple([-1] * tasks))
    best_value, best, upper, threshold, infinity = _greedy_profit(adjusted, weight, capacity)
    if best_value != upper:
        for parameters in ((1, 0, 0, 0, 1), (1, 0, 1, 0, 0), (0, 1, 0, 1, 0)):
            value, assignment = _alternative(adjusted, weight, capacity, infinity, parameters)
            if value > best_value:
                best_value, best = value, assignment
    if best_value <= threshold:
        return Assignment(0, tuple([-1] * tasks))
    value = tasks * transform - best_value if minimize else best_value
    return Assignment(value, tuple(best))
