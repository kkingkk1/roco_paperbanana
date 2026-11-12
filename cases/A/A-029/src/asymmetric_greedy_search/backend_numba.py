import random
import numpy as np
import numba as nb
from typing import Tuple, List

@nb.njit
def nb_random_shuffle(arr: np.ndarray) -> np.ndarray:
    n = arr.size
    k = arr.size
    result = arr.copy()
    for i in range(k):
        j = random.randint(i, n - 1)
        result[i], result[j] = result[j], result[i]
    return result[:k].copy()


@nb.njit
def initialize(benefit: np.ndarray, shuffle=False) -> Tuple[np.ndarray, ...]:
    """Initializes row-to-column assignments using a greedy approach.
    The `Initial(n,m)` function of the published algorithm.

    Args:
        benefit: (numpy.ndarray): The scaled benefit (or cost) matrix
            for row-column assignments.
        shuffle (bool): If True, randomizes the order of row assignment
            (default: False).
    """
    n_rows, n_cols = benefit.shape
    assignment = np.empty(n_rows, dtype=np.intp)
    column_mask = np.ones(n_cols, dtype=np.uint8)

    row_order = np.arange(n_rows)
    if shuffle:
        row_order = nb_random_shuffle(row_order)

    for row_idx in row_order:
        best_val = float('-inf')
        best_col = -1
        for col_idx in range(n_cols):
            if column_mask[col_idx]:
                val = benefit[row_idx, col_idx]
                if val > best_val:
                    best_val = val
                    best_col = col_idx
        assignment[row_idx] = best_col
        column_mask[best_col] = 0  # mark as used

    row_swap_idx = np.full(n_rows, -1, dtype=np.intp)
    row_swap_delta = np.full(n_rows, float('-inf'), dtype=np.float32)
    col_swap_idx = np.full(n_rows, -1, dtype=np.intp)
    col_swap_delta = np.full(n_rows, float('-inf'), dtype=np.float32)

    return (assignment, column_mask, row_swap_idx, row_swap_delta,
            col_swap_idx, col_swap_delta)


@nb.njit(parallel=True)
def calc_row_swap_delta(benefit: np.ndarray, assignment: np.ndarray, row_idx: int) -> Tuple[int, float]:
    """ Computes the change in benefit from swapping the column assignment
    of a given row with every other row and identifies the best swap.
    """
    n_rows = benefit.shape[0]
    benefit_delta = np.empty(n_rows)
    for r in nb.prange(n_rows):
        # benefit[row_idx, assignment[r]]
        b1 = benefit[row_idx, assignment[r]]
        # benefit[r, assignment[row_idx]]
        b2 = benefit[r, assignment[row_idx]]
        # benefit[row_idx, assignment[row_idx]]
        b3 = benefit[row_idx, assignment[row_idx]]
        # benefit[r, assignment[r]]
        b4 = benefit[r, assignment[r]]
        swap_benefit = b1 + b2
        curr_benefit = b3 + b4
        benefit_delta[r] = swap_benefit - curr_benefit
    benefit_delta[row_idx] = -1
    best_swap_idx = int(np.argmax(benefit_delta))
    best_swap_delta = float(benefit_delta[best_swap_idx])
    return best_swap_idx, best_swap_delta

@nb.njit
def calc_col_swap_delta(benefit: np.ndarray, assignment: np.ndarray, column_mask: np.ndarray,
                        row_idx: int) -> Tuple[int, float]:
    """Computes the benefit of swapping the column assignment of a given row
    with the best available unassigned column."""
    n_cols = benefit.shape[1]
    best_val = float('-inf')
    best_swap_idx = -1

    for col_idx in range(n_cols):
        if column_mask[col_idx]:
            val = benefit[row_idx, col_idx]
            if val > best_val:
                best_val = val
                best_swap_idx = col_idx

    curr_val = benefit[row_idx, assignment[row_idx]]
    best_swap_delta = best_val - curr_val
    return int(best_swap_idx), float(best_swap_delta)

@nb.njit
def update_row_swap_deltas(benefit: np.ndarray, assignment: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
    """Updates the best row swap options and their corresponding benefit
    deltas for all rows. The `BestRowSwap(B,V)` function of the published
    algorithm.

    Returns:
        tuple: (row_swap_idx, row_swap_delta)
    """
    n_rows = assignment.shape[0]
    row_swap_idx = np.empty(n_rows, dtype=np.intp)
    row_swap_delta = np.empty(n_rows, dtype=np.float32)

    for row in range(n_rows):
        idx, delta = calc_row_swap_delta(benefit, assignment, row)
        row_swap_idx[row] = idx
        row_swap_delta[row] = delta
    return row_swap_idx, row_swap_delta


@nb.njit(parallel=True)
def update_col_swap_deltas(benefit: np.ndarray, assignment: np.ndarray,
                           column_mask: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
    """Updates the best unassigned column swap options and their
    corresponding benefit deltas for all rows. The `BestColSwap(B,V)`
    function of the published algorithm."""
    n_rows, n_cols = benefit.shape

    col_swap_idx = np.full(n_rows, -1, dtype=np.intp)
    col_swap_delta = np.full(n_rows, float('-inf'), dtype=np.float32)

    if n_rows == n_cols:
        return col_swap_idx, col_swap_delta

    for row in nb.prange(n_rows):
        best_val = float('-inf')
        best_col = -1

        for col in range(n_cols):
            if column_mask[col]:
                val = benefit[row, col]
                if val > best_val:
                    best_val = val
                    best_col = col

        col_swap_idx[row] = best_col
        if best_col != -1:
            col_swap_delta[row] = best_val - benefit[row, assignment[row]]

    return col_swap_idx, col_swap_delta

@nb.njit
def apply_row_swap(benefit: np.ndarray, assignment: np.ndarray, column_mask: np.ndarray,
                   row_swap_idx: np.ndarray, row_swap_delta: np.ndarray,
                   col_swap_idx: np.ndarray, col_swap_delta: np.ndarray,
                   row_idx: int) -> Tuple[np.ndarray, ...]:
    """Applies the best row swap for the specified row and updates the swap
    benefit matrices accordingly. The `RowSwap(B,V,r)` function of the
    published algorithm."""

    swap_idx = row_swap_idx[row_idx]

    # Swap assignments (avoid fancy indexing)
    temp = assignment[row_idx]
    assignment[row_idx] = assignment[swap_idx]
    assignment[swap_idx] = temp

    # Find affected rows: those whose best swap involves row_idx or swap_idx
    n_rows = row_swap_idx.shape[0]
    for i in range(n_rows):
        if row_swap_idx[i] == row_idx or row_swap_idx[i] == swap_idx:
            new_swap_idx, new_swap_delta = calc_row_swap_delta(benefit, assignment, i)
            row_swap_idx[i] = new_swap_idx
            row_swap_delta[i] = new_swap_delta

    # Update column swap deltas for affected rows
    for idx in (row_idx, swap_idx):
        new_col_idx, new_col_delta = calc_col_swap_delta(benefit, assignment, column_mask, idx)
        col_swap_idx[idx] = new_col_idx
        col_swap_delta[idx] = new_col_delta

    return assignment, row_swap_idx, row_swap_delta, col_swap_idx, col_swap_delta

@nb.njit
def apply_col_swap(benefit: np.ndarray, assignment: np.ndarray, column_mask: np.ndarray,
                   row_swap_idx: np.ndarray, row_swap_delta: np.ndarray,
                   col_swap_idx: np.ndarray, col_swap_delta: np.ndarray,
                   row_idx: int) -> Tuple[np.ndarray, ...]:
    """Numba-compatible version of ColSwap(B,V,r)."""
    original_col = assignment[row_idx]
    new_col = col_swap_idx[row_idx]

    # Update any rows with the new column as best
    affected_rows_count = 0
    for i in range(row_swap_idx.shape[0]):
        if row_swap_idx[i] == original_col:
            affected_rows_count += 1

    affected_rows = np.empty(affected_rows_count, dtype=np.intp)
    idx = 0
    for i in range(row_swap_idx.shape[0]):
        if row_swap_idx[i] == original_col:
            affected_rows[idx] = i
            idx += 1

    for i in range(affected_rows_count):
        r = int(affected_rows[i])
        new_idx, new_delta = calc_row_swap_delta(benefit, assignment, r)
        row_swap_idx[r] = new_idx
        row_swap_delta[r] = new_delta

    # Update assignment
    assignment[row_idx] = new_col

    # Update column mask
    column_mask[original_col] = 1
    column_mask[new_col] = 0

    # Update any rows with best unassigned column as new_col
    affected_cols_count = 0
    for i in range(col_swap_idx.shape[0]):
        if col_swap_idx[i] == new_col:
            affected_cols_count += 1

    affected_cols = np.empty(affected_cols_count, dtype=np.intp)
    idx = 0
    for i in range(col_swap_idx.shape[0]):
        if col_swap_idx[i] == new_col:
            affected_cols[idx] = i
            idx += 1

    for i in range(affected_cols_count):
        r = int(affected_cols[i])
        new_idx, new_delta = calc_col_swap_delta(benefit, assignment, column_mask, r)
        col_swap_idx[r] = new_idx
        col_swap_delta[r] = new_delta

    return assignment, column_mask, row_swap_idx, row_swap_delta, col_swap_idx, col_swap_delta

@nb.njit
def optimize(benefit: np.ndarray,
             shuffle=False,
             maximum_iterations: int = 0,
             stagnation_limit: int = 0,
             stagnation_tolerance: float = 1e-3,
             row_batch_size=1) -> Tuple[np.ndarray, np.ndarray]:
    """A python implementation of the algorithm described in 'A heuristic
    for the time constrained asymmetric linear sum assignment problem'

    Args:
        benefit (numpy.ndarray): The benefit matrix for row-column
            assignments.
        shuffle (bool): If True, rows are shuffled before greedy
            initialization (default: False).
        maximum_iterations (int): Maximum number of iterations to
            run the optimization. If 0, no limit is applied (default: 0).
        stagnation_limit (int): Number of consecutive iterations
            allowed where the maximum improvement (delta) remains
            below a given tolerance before termination. If 0,
            stagnation detection is disabled. (default: 0).
        stagnation_tolerance (float): Relative improvement
            threshold used to detect stagnation. Stagnation is
            triggered if the maximum benefit improvement (delta)
            stays below `initial_max_delta * stagnation_tolerance`
            for `stagnation_limit` consecutive iterations (default: 1e-3).
        row_batch_size (int): Number of rows to swap in each iteration
            (default: 1).

    Returns:
        tuple: A tuple of row indices and their assigned column indices.
    """
    n_rows, n_cols = benefit.shape
    rows_idx = np.arange(n_rows)
    (assignment, column_mask, row_swap_idx, row_swap_delta,
     col_swap_idx, col_swap_delta) = initialize(benefit, shuffle=shuffle)
    row_swap_idx, row_swap_delta = update_row_swap_deltas(benefit, assignment)
    brb_max = float(np.amax(row_swap_delta))
    col_swap_idx, col_swap_delta = update_col_swap_deltas(benefit, assignment, column_mask)
    bcb_max = float(np.amax(col_swap_delta))
    total_iters = 0
    # Stagnation
    current_max = max(brb_max, bcb_max)
    tolerance = current_max * stagnation_tolerance
    stale_iters = 0

    while current_max > 0:
        while current_max > 0:
            if brb_max > bcb_max:
                # Batched update
                candidate_swaps = [(row_swap_delta[r], int(r)) for r in rows_idx if row_swap_delta[r] > 0]
                candidate_swaps.sort(key=lambda x: (-x[0], x[1]))
                locked = set()
                batch: List[int] = []
                for delta, r in candidate_swaps:
                    if len(batch) >= row_batch_size:
                        break
                    s = row_swap_idx[r]
                    if r in locked or s in locked:
                        continue
                    batch.append(r)
                    locked.update({r, s})
                for row_idx in batch:
                    (assignment, row_swap_idx, row_swap_delta, col_swap_idx,
                     col_swap_delta) = apply_row_swap(benefit, assignment, column_mask, row_swap_idx,
                                                      row_swap_delta, col_swap_idx, col_swap_delta, row_idx)
            else:
                row_idx = int(np.argmax(col_swap_delta))
                (assignment, column_mask, row_swap_idx, row_swap_delta, col_swap_idx,
                 col_swap_delta) = apply_col_swap(benefit, assignment, column_mask, row_swap_idx,
                                                  row_swap_delta, col_swap_idx, col_swap_delta, row_idx)
            brb_max = np.amax(row_swap_delta)
            bcb_max = np.amax(col_swap_delta)
            current_max = max(brb_max, bcb_max)
            # max iterations
            total_iters += 1
            if 0 < maximum_iterations <= total_iters:
                return rows_idx, assignment
            # stagnation
            if stagnation_limit > 0 and current_max <= tolerance:
                stale_iters += 1
            else:
                stale_iters = 0
            if 0 < stagnation_limit <= stale_iters:
                return rows_idx, assignment

        row_swap_idx, row_swap_delta = update_row_swap_deltas(benefit, assignment)
        brb_max = float(np.amax(row_swap_delta))
        col_swap_idx, col_swap_delta = update_col_swap_deltas(benefit, assignment, column_mask)
        bcb_max = float(np.amax(col_swap_delta))
        current_max = max(brb_max, bcb_max)
    return rows_idx, assignment
