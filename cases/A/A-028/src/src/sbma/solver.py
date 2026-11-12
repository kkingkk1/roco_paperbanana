"""Solver module."""

import warnings

import numpy as np
from sbma._solver import solve as cpp_solve

__all__ = [
    "DimensionError",
    "ShapeError",
    "assert_results",
    "generate_random_input",
    "solve",
]


class DimensionError(Exception):
    """Exception raised when an array has an incorrect number of dimensions.

    This is raised when an input array has too many or too few dimensions,
    like a 3D array where a 2D array is expected.
    """

    pass


class ShapeError(Exception):
    """Exception raised when arrays have incompatible shapes.

    This is raised in cases such as:
    - Mismatched dimensions between arrays (e.g., matrix rows vs counts length)
    - Insufficient number of columns in input matrix
    - Any other shape-related validation errors
    """

    pass


def assert_results(mat, ar_counts, results, sum_exp) -> None:
    """Assert that results match expectations.

    Args:
        mat: Input matrix (can be C or F contiguous)
        ar_counts: Array of counts per column
        results: Dictionary mapping column index to row indices
        sum_exp: Expected sum to match
    """
    n_assigned_exp = ar_counts.sum()
    n_assigned_chk = 0
    sum_chk: float = 0.0

    for idx_col, ar_row in results.items():
        n_assigned_chk += len(ar_row)
        n_exp: int = ar_counts[idx_col]
        n_found: int = len(ar_row)
        if n_exp != n_found:
            raise AssertionError(f"Expected {n_exp} elements assigned, found {n_found}")
        sum_chk += mat[ar_row, idx_col].sum()
    if n_assigned_chk != n_assigned_exp:
        raise ValueError("n_assigned != n_devices")

    err = 100 * abs(sum_exp - sum_chk) / sum_exp
    if err > 0.01:
        raise AssertionError(f"Expected {sum_exp}, found {sum_chk}")
    print("Results checked!")


def generate_random_input(
        n_cols: int, max_count: int, seed=None, order: str = "C"
) -> "tuple[np.ndarray, np.ndarray]":
    """Generate random test data for assignment solver.

    Args:
        n_cols (int):
            Number of columns (recipients) in the matrix.
        max_count (int):
            Maximum count of items per recipient.
        seed (int | None):
            Random seed for reproducibility. If None, no seed is set.
        order (Literal["C", "F"]):
            Memory layout - 'C' for row-major or 'F' for column-major.

    Returns:
        tuple[np.ndarray, np.ndarray]: A tuple containing:
            - mat: A 2D float32 array of shape (n_rows, n_cols) with random values
            - ar_counts: A 1D int32 array of length n_cols with random counts
              between 1 and max_count. n_rows is the sum of these counts.
    """
    if seed is not None:
        np.random.seed(seed)
    ar_counts: np.ndarray = np.random.randint(1, max_count, n_cols).astype(np.int32)
    n_rows: int = ar_counts.sum()
    mat = np.random.random((n_rows, n_cols)).astype(np.float32, order=order)
    return mat, ar_counts


def solve(
        mat: np.ndarray,
        ar_counts: np.ndarray,
        *,
        max_process_time: float = 300.0,
        error_threshold_abs: float = 0.0,
        error_threshold_pct: float = 0.0,
        max_iterations: int = 100,
        skip_first_phase: bool = False,
        verbose: bool = False,
        check_nan: bool = True,
) -> "tuple[dict[int, np.ndarray], str, float]":
    """Optimize Assignment of items to recipients to maximize total score.

    This solver implements an optimization algorithm that assigns items
    to recipients based on a score matrix of float32. Each recipient
    can receive multiple items according to specified allocation counts.
    The algorithm aims to maximize the total score across all assignments
    while ensuring each item is assigned exactly once.

    Args:
        mat (np.ndarray):
            A 2D float32 array of shape (n_items, n_recipients) where each element
            represents the score of assigning an item to a recipient.
        ar_counts (np.ndarray):
            A 1D integer array of length n_recipients specifying how many items
            should be assigned to each recipient. The sum must equal n_items.
        max_process_time (float | None):
            Maximum runtime in seconds. Default: 300.0
        error_threshold_abs (float | None):
            Absolute improvement threshold for early stopping. If the score
            improvement is below this value, the algorithm stops.
            Default: 0.0
        error_threshold_pct (float | None):
            Percentage improvement threshold for early stopping. If the score
            improvement percentage is below this value, the algorithm stops.
            Default: 0.0
        max_iterations (int | None):
            Maximum number of optimization iterations.
            If the number of columns in the input matrix is 2, the
            'max_iterations' is set to 0 and 'skip_first_phase' to False,
            as the solution is deterministic. Default: 100
        skip_first_phase (bool):
            If True, run the swapping iteration without finding an initial
            optimal point. If the number of columns in the input matrix is 2,
            this step is forced to False and the number of iterations to 0,
            as the solution is deterministic. Default: False.
        verbose (bool, optional):
            If True, prints progress information. Default: False.
        check_nan (bool):
            If True, checks for NaN values in the input matrix before processing.
            This adds some overhead but prevents potential issues. Set to False
            only if you are certain the input matrix contains no NaN values.
            Default: True.

    Returns:
        tuple(dict(int, np.ndarray), str, float):
            - Dictionary mapping recipient indices to arrays of assigned item indices
            - Status message describing the stopping condition and runtime
            - Final total score achieved

    Raises:
        DimensionError: If input arrays have incorrect dimensions
        ShapeError: If input arrays have incompatible shapes
        TypeError: If input arrays have incorrect data types
        ValueError: If ar_counts contains non-positive values
    """
    if mat.ndim != 2:
        raise DimensionError("'mat' must be a 2D float32 array")

    if ar_counts.ndim != 1:
        raise DimensionError("'ar_counts' must be a 1D array")

    n_rows, n_cols = mat.shape
    if n_cols != ar_counts.shape[0]:
        raise ShapeError("'mat.shape[1]' must be equal to ar_counts.shape[0]")

    if n_cols < 2:
        raise ShapeError("'mat' must have at least 2 columns")
    elif n_cols == 2:
        max_iterations = 0
        skip_first_phase = False
        if verbose:
            print("Set 'max_iterations' to 0 and 'skip_first_phase' to "
                  "False, as the solution is deterministic.")

    if ar_counts.dtype.kind not in {"i", "u"}:
        raise TypeError("'ar_counts' must be an integer array")

    if mat.dtype != np.float32:
        raise TypeError("'mat' must be a 2D float32 array")

    s_counts = ar_counts.sum()
    if s_counts != n_rows:
        raise ShapeError("sum('ar_counts') must be equal to mat.shape[0]")

    if (ar_counts <= 0).any():
        raise ValueError("'ar_counts' must contain positive integers")

    if max_process_time < 0:
        raise ValueError("'max_process_time' must be a positive number")

    if error_threshold_abs < 0:
        raise ValueError("'error_threshold_abs' must be a positive number")

    if error_threshold_pct < 0:
        raise ValueError("'error_threshold_pct' must be a positive number")

    if max_iterations < 0:
        raise ValueError("'max_iterations' must be a positive number")

    if check_nan:
        if np.isnan(mat).any():
            raise ValueError("Found NaN in the input matrix")

    if mat.flags["F_CONTIGUOUS"]:
        warnings.warn("Warning: Input matrix is F-contiguous (column-major). "
                      "Calling 'np.ascontiguousarray' to make it C-contiguous. "
                      "For optimal performance, convert to "
                      "row-major using .copy(order='C')")
        mat_c = np.ascontiguousarray(mat)
    else:
        mat_c = mat

    ret = cpp_solve(
        mat_c,
        ar_counts.astype(np.int32),
        float(max_process_time),
        float(error_threshold_abs),
        float(error_threshold_pct),
        int(max_iterations),
        bool(skip_first_phase),
        bool(verbose)
    )

    return ret


if __name__ == "__main__":
    from time import time

    _mat, _ar_counts = generate_random_input(500, 1000, seed=0, order="C")
    print(f"Input: shape={_mat.shape}, size={_mat.size:,d}")
    if _ar_counts.shape[0] < 15:
        print(f"Counts: {_ar_counts}")
    if _mat.size < 50:
        print(_mat.round(3))

    _start = time()

    for _ in range(1):
        _results, _notes, _output_sum = solve(
            _mat,
            _ar_counts,
            max_process_time=float(300),
            error_threshold_abs=0.0,
            error_threshold_pct=0.0,
            max_iterations=int(100),
            skip_first_phase=False,
            verbose=True,
        )

    print(f"Runtime for c++ call: {time() - _start:4f}")
    print(f"{_notes} | sum={_output_sum}")
    if len(_results) < 15:
        print(_results)

    if _mat.size < 100_000_000:
        print("Asserting results ...")
        assert_results(_mat, _ar_counts, _results, _output_sum)

    print("DONE!")
