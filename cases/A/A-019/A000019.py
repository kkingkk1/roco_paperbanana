"""参考MTG的广义指派分支定界。

Silvano Martello and Paolo Toth, Knapsack Problems: Algorithms and Computer
Implementations, Wiley, 1990. 来源使用条件保存在案例后台。
"""

import math
from dataclasses import dataclass
from functools import cmp_to_key


@dataclass(frozen=True)
class Assignment:
    value: int
    resource_by_task: tuple[int, ...]
    bound: int
    optimal: bool
    explored_nodes: int


class _Search:
    def __init__(self, profit, weight, capacity, max_nodes):
        self.profit, self.weight = profit, weight
        self.remaining = list(capacity)
        self.max_nodes = max_nodes
        self.assignment = [-1] * len(profit[0])
        self.best_value, self.best_assignment = -1, None
        self.visited, self.truncated = 0, False

    def _choose_task(self, partial, room):
        chosen, fewest, largest_regret = -1, 2**31 - 1, -1
        for task, owner in enumerate(partial):
            if owner != -1:
                continue
            count = best = second = 0
            for resource in range(len(room)):
                if self.weight[resource][task] <= room[resource]:
                    count += 1
                    value = self.profit[resource][task]
                    if value > best:
                        second, best = best, value
                    elif value > second:
                        second = value
            regret = best - second
            if count < fewest or (count == fewest and regret > largest_regret):
                chosen, fewest, largest_regret = task, count, regret
        return chosen

    def greedy_incumbent(self):
        room, owners = list(self.remaining), [-1] * len(self.assignment)
        total = 0
        for _ in owners:
            task = self._choose_task(owners, room)
            if task < 0:
                return
            best = -1
            for resource in range(len(room)):
                if (self.weight[resource][task] <= room[resource]
                        and (best < 0 or self.profit[resource][task] > self.profit[best][task])):
                    best = resource
            if best < 0:
                return
            owners[task] = best
            room[best] -= self.weight[best][task]
            total += self.profit[best][task]
        self.best_value, self.best_assignment = total, owners

    def upper_bound(self, current):
        per_task = current
        for task, owner in enumerate(self.assignment):
            if owner != -1:
                continue
            maximum = -1
            for resource, room in enumerate(self.remaining):
                if self.weight[resource][task] <= room:
                    maximum = max(maximum, self.profit[resource][task])
            if maximum < 0:
                return -1
            per_task += maximum
        knapsack = float(current)
        for resource, capacity in enumerate(self.remaining):
            tasks = [task for task, owner in enumerate(self.assignment)
                     if owner == -1 and self.weight[resource][task] <= capacity]

            def compare(first, second):
                left = self.profit[resource][first] * self.weight[resource][second]
                right = self.profit[resource][second] * self.weight[resource][first]
                return -1 if left > right else 1 if left < right else first - second

            tasks.sort(key=cmp_to_key(compare))
            room = capacity
            for task in tasks:
                if self.weight[resource][task] <= room:
                    knapsack = math.nextafter(knapsack + self.profit[resource][task], math.inf)
                    room -= self.weight[resource][task]
                else:
                    product = math.nextafter(float(self.profit[resource][task]) * room, math.inf)
                    fraction = math.nextafter(product / self.weight[resource][task], math.inf)
                    knapsack = math.nextafter(knapsack + fraction, math.inf)
                    break
        return min(per_task, knapsack)

    def visit(self, depth, value):
        if self.max_nodes >= 0 and self.visited >= self.max_nodes:
            self.truncated = True
            return
        self.visited += 1
        if depth == len(self.assignment):
            if value > self.best_value:
                self.best_value, self.best_assignment = value, list(self.assignment)
            return
        upper = self.upper_bound(value)
        if upper < 0 or (self.best_value >= 0 and upper < float(self.best_value) + 1.0):
            return
        task = self._choose_task(self.assignment, self.remaining)
        options = [resource for resource in range(len(self.remaining))
                   if self.weight[resource][task] <= self.remaining[resource]]
        options.sort(key=lambda resource: (-self.profit[resource][task], resource))
        for resource in options:
            self.assignment[task] = resource
            self.remaining[resource] -= self.weight[resource][task]
            self.visit(depth + 1, value + self.profit[resource][task])
            self.remaining[resource] += self.weight[resource][task]
            self.assignment[task] = -1
            if self.truncated:
                return


def solve(profit, weight, capacity, minimize, max_nodes):
    """max_nodes为-1时不限节点；非负值限制实际访问节点数。"""
    if max_nodes < -1:
        raise ValueError("maxNodes must be -1 or nonnegative")
    resources = len(capacity)
    if not 2 <= resources <= 10 or len(profit) != resources or len(weight) != resources:
        raise ValueError("the number of resources must be 2..10")
    tasks = len(profit[0])
    if not 2 <= tasks <= 100:
        raise ValueError("the number of tasks must be 2..100")
    maximum = 0
    for resource in range(resources):
        if capacity[resource] <= 0 or len(profit[resource]) != tasks or len(weight[resource]) != tasks:
            raise ValueError("matrix dimensions and capacities must be valid")
        usable = False
        for task in range(tasks):
            if profit[resource][task] <= 0 or weight[resource][task] <= 0:
                raise ValueError("values must be positive")
            maximum = max(maximum, profit[resource][task])
            if weight[resource][task] <= capacity[resource]:
                usable = True
        if not usable:
            raise ValueError("every resource must fit at least one task")
    if (maximum + 1) * tasks > 2**31 - 1:
        raise ValueError("objective exceeds the source arithmetic range")
    transform = maximum + 1
    adjusted = [[transform - value if minimize else value for value in row] for row in profit]
    search = _Search(adjusted, weight, capacity, max_nodes)
    search.greedy_incumbent()
    root_bound = search.upper_bound(0)
    if root_bound >= 0:
        search.visit(0, 0)
    complete = not search.truncated
    if search.best_value < 0 and complete:
        return Assignment(0, tuple([-1] * tasks), 0, True, search.visited)
    value = (0 if search.best_value < 0 else
             tasks * transform - search.best_value if minimize else search.best_value)
    if complete and search.best_value >= 0:
        bound = value
    elif root_bound < 0:
        bound = 0
    else:
        upper = math.floor(root_bound)
        bound = tasks * transform - upper if minimize else upper
    owners = tuple([-1] * tasks if search.best_value < 0 else search.best_assignment)
    return Assignment(value, owners, bound, complete, search.visited)
