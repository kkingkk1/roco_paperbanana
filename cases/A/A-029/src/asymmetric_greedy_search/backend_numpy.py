import numpy as np
from typing import Tuple, List


def initialize(benefit: np.ndarray, shuffle: bool=False) -> Tuple[np.ndarray, ...]:
    """Initializes row-to-column assignments using a greedy approach.
    The `Initial(n,m)` function of the published algorithm.

    Args:
        benefit (numpy.ndarray): The scaled benefit matrix for row-column assignments.
        shuffle (bool): If True, randomizes the order of row assignment
            (default: False).
    """
    n_rows, n_cols = benefit.shape
    assignment = np.empty(n_rows, dtype=np.intp)
    column_mask = np.ones(n_cols, dtype=bool)

    rows = list(range(n_rows))
    if shuffle:
        np.random.shuffle(rows)

    for row_idx in rows:
        available_bm = benefit[row_idx, column_mask]
        max_idx_in_avail = available_bm.argmax()
        max_idx = np.where(column_mask)[0][max_idx_in_avail]
        assignment[row_idx] = max_idx
        column_mask[max_idx] = False

    row_swap_idx = np.full(n_rows, -1, dtype=np.intp)
    row_swap_delta = np.full(n_rows, -np.inf, dtype=np.float32)
    col_swap_idx = np.full(n_rows, -1, dtype=np.intp)
    col_swap_delta = np.full(n_rows, -np.inf, dtype=np.float32)

    return assignment, column_mask, row_swap_idx, row_swap_delta, col_swap_idx, col_swap_delta

def calc_row_swap_delta(benefit: np.ndarray, assignment: np.ndarray, row_idx: int) -> Tuple[int, float]:
    """ Computes the change in benefit from swapping the column assignment
    of a given row with every other row and identifies the best swap.

    Args:
        benefit (numpy.ndarray): The scaled benefit matrix for row-column assignments.
        assignment (numpy.ndarray): Current row-to-column assignment indices.
        row_idx (int): Index of the row to calculate the swap delta for.

    Returns:
        tuple: The index of the best swap row and the corresponding benefit
            delta.
    """
    n_rows, n_cols = benefit.shape
    swap_benefit = benefit[row_idx, assignment] + benefit[:, assignment[row_idx]]
    curr_benefit = benefit[row_idx, assignment[row_idx]] + benefit[np.arange(n_rows), assignment]
    benefit_delta = swap_benefit - curr_benefit
    benefit_delta[row_idx] = -1
    best_swap_idx = int(np.argmax(benefit_delta))
    best_swap_delta = float(benefit_delta[best_swap_idx])
    return best_swap_idx, best_swap_delta

def calc_col_swap_delta(benefit: np.ndarray, assignment: np.ndarray,
                        column_mask: np.ndarray, row_idx: int) -> Tuple[int, float]:
    """Computes the benefit of swapping the column assignment of a given row
    with the best available unassigned column.

    Args:
        benefit (numpy.ndarray): The scaled benefit matrix for row-column assignments.
        assignment (numpy.ndarray): Current row-to-column assignment indices.
        column_mask (numpy.ndarray): Boolean mask indicating unassigned columns.
        row_idx (int): Index of the row to calculate the swap delta for.

    Returns:
        tuple: The index of the best unassigned column and the
            corresponding benefit delta.
    """
    available_benefits = benefit[row_idx, column_mask]
    if available_benefits.size == 0:
        return -1, 0.

    best_avail_idx = available_benefits.argmax()
    best_swap_idx = int(np.flatnonzero(column_mask)[best_avail_idx])
    best_swap_delta = float(
            benefit[row_idx, best_swap_idx] -
            benefit[row_idx, assignment[row_idx]]
    )
    return best_swap_idx, best_swap_delta

def update_row_swap_deltas(benefit: np.ndarray, assignment: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
    """Updates the best row swap options and their corresponding benefit
    deltas for all rows. The `BestRowSwap(B,V)` function of the published
    algorithm.

    Args:
        benefit (numpy.ndarray): The scaled benefit matrix for row-column assignments.
        assignment (numpy.ndarray): Current row-to-column assignment indices.

    Returns:
        tuple: The arrays for best row index to swap and corresponding
            benefit for all rows.
    """
    n_rows, n_cols = benefit.shape
    best_swap_idx, best_swap_delta = np.stack([
        calc_row_swap_delta(benefit, assignment, row_idx)
        for row_idx in range(n_rows)]).T
    row_swap_idx = best_swap_idx.astype(np.intp)
    row_swap_delta = best_swap_delta.astype(np.float32)
    return row_swap_idx, row_swap_delta

def update_col_swap_deltas(benefit: np.ndarray, assignment: np.ndarray,
                           column_mask: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
    """Updates the best unassigned column swap options and their
    corresponding benefit deltas for all rows. The `BestColSwap(B,V)`
    function of the published algorithm.

    Args:
        benefit (numpy.ndarray): The scaled benefit matrix for row-column assignments.
        assignment (numpy.ndarray): Current row-to-column assignment indices.
        column_mask (numpy.ndarray): Boolean mask indicating unassigned columns.

    Returns:
        tuple: The arrays for best unassigned column index to swap and
            corresponding benefit for all rows.
    """
    n_rows, n_cols = benefit.shape
    if n_rows == n_cols:
        return (
            np.full(n_rows, -1, dtype=np.intp),
            np.full(n_rows, -np.inf, dtype=np.float32)
        )
    available_columns = np.where(column_mask)[0]
    available_benefits = benefit[:, available_columns]
    best_indices = np.argmax(available_benefits, axis=1)
    best_swap_idx = available_columns[best_indices]
    best_swap_delta = available_benefits[np.arange(n_rows), best_indices] - \
            benefit[np.arange(n_rows), assignment]
    col_swap_idx = best_swap_idx.astype(int)
    col_swap_delta = best_swap_delta.astype(np.float32)
    return col_swap_idx, col_swap_delta

def apply_row_swap(benefit, assignment, column_mask, row_swap_idx, row_swap_delta, col_swap_idx, col_swap_delta, row_idx):
    """Applies the best row swap for the specified row and updates the swap
    benefit matrices accordingly. The `RowSwap(B,V,r)` function of the
    published algorithm.

    Args:
        benefit (numpy.ndarray): The scaled benefit matrix for row-column assignments.
        assignment (numpy.ndarray): Current row-to-column assignment indices.
        column_mask (numpy.ndarray): Boolean mask indicating unassigned columns.
        row_swap_idx (numpy.ndarray): Best row swap candidate for each row.
        row_swap_delta (numpy.ndarray): Change in benefit for the best row swap.
        col_swap_idx (numpy.ndarray): Best unassigned column swap for each row.
        col_swap_delta (numpy.ndarray): Change in benefit for the best column swap.
        row_idx (int): Index of the row to apply the swap for.
    """
    swap_idx = row_swap_idx[row_idx]
    # switch assignments
    assignment[[row_idx, swap_idx]] = assignment[[swap_idx, row_idx]]
    affected_rows = np.where((row_swap_idx == row_idx) |
                             (row_swap_idx == swap_idx))[0]
    # update row swap best benefits
    for idx in affected_rows:
        row_swap_idx[idx], row_swap_delta[idx] = calc_row_swap_delta(benefit, assignment, idx)
    # update the column swap best benefits
    for idx in [row_idx, swap_idx]:
        col_swap_idx[idx], col_swap_delta[idx] = calc_col_swap_delta(benefit, assignment, column_mask, idx)
    return assignment, row_swap_idx, row_swap_delta, col_swap_idx, col_swap_delta

def apply_col_swap(benefit: np.ndarray, assignment: np.ndarray, column_mask: np.ndarray,
                   row_swap_idx: np.ndarray, row_swap_delta: np.ndarray,
                   col_swap_idx: np.ndarray, col_swap_delta: np.ndarray,
                   row_idx: int) -> Tuple[np.ndarray, ...]:
    """Applies the best column swap for the specified row and updates the
    swap benefit matrices accordingly. The `ColSwap(B,V,r)` function of the
    published algorithm

    Args:
        benefit (numpy.ndarray): The scaled benefit matrix for row-column assignments.
        assignment (numpy.ndarray): Current row-to-column assignment indices.
        column_mask (numpy.ndarray): Boolean mask indicating unassigned columns.
        row_swap_idx (numpy.ndarray): Best row swap candidate for each row.
        row_swap_delta (numpy.ndarray): Change in benefit for the best row swap.
        col_swap_idx (numpy.ndarray): Best unassigned column swap for each row.
        col_swap_delta (numpy.ndarray): Change in benefit for the best column swap.
        row_idx (int): Index of the row to apply the column swap for.
    """
    original_col = assignment[row_idx]
    new_col = col_swap_idx[row_idx]
    # update any rows with the new column as best
    affected_rows_mask = (row_swap_idx == original_col)
    if np.any(affected_rows_mask):
        affected_rows = np.where(affected_rows_mask)[0]
        new_swap_idx, new_swap_delta = np.stack(
            [calc_row_swap_delta(benefit, assignment, row_idx) for row_idx in affected_rows]).T
        row_swap_idx[affected_rows_mask] = new_swap_idx
        row_swap_delta[affected_rows_mask] = new_swap_delta
    # update assignment
    assignment[row_idx] = new_col
    # update any columns with best assigned to new column
    column_mask[original_col] = True
    column_mask[new_col] = False
    affected_cols_mask = (col_swap_idx == new_col)
    affected_cols = np.where(affected_cols_mask)[0]
    new_swap_idx, new_swap_delta = np.stack(
        [calc_col_swap_delta(benefit, assignment, column_mask, row_idx) for row_idx in affected_cols]).T
    col_swap_idx[affected_cols_mask] = new_swap_idx
    col_swap_delta[affected_cols_mask] = new_swap_delta
    return assignment, column_mask, row_swap_idx, row_swap_delta, col_swap_idx, col_swap_delta


def optimize(benefit,
             shuffle = False,
             maximum_iterations: int = 0,
             stagnation_limit: int = 0,
             stagnation_tolerance: float = 1e-3,
             row_batch_size=1) -> Tuple[np.ndarray, np.ndarray]:
    """A python implementation of the algorithm described in 'A heuristic
    for the time constrained asymmetric linear sum assignment problem'

    Args:
        benefit (numpy.ndarray): The scaled benefit matrix for row-column assignments.
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
    brb_max = np.amax(row_swap_delta)
    col_swap_idx, col_swap_delta = update_col_swap_deltas(benefit, assignment, column_mask)
    bcb_max = np.amax(col_swap_delta)
    # Stagnation
    current_max = max(brb_max, bcb_max)
    tolerance = current_max * stagnation_tolerance
    total_iters = 0
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
            row_swap_idx, row_swap_delta = update_row_swap_deltas(benefit, assignment)
            brb_max = np.amax(row_swap_delta)
            col_swap_idx, col_swap_delta = update_col_swap_deltas(benefit, assignment, column_mask)
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
        brb_max = np.amax(row_swap_delta)
        col_swap_idx, col_swap_delta = update_col_swap_deltas(benefit, assignment, column_mask)
        bcb_max = np.amax(col_swap_delta)
        current_max = max(brb_max, bcb_max)
    return rows_idx, assignment
