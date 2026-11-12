import numpy as np
from typing import Tuple


class AsymmetricGreedySearch:
    """A Numpy/Numba implementation of the Asymmetric Greedy Search (AGS) algorithm
    for solving the linear sum assignment problem, as described in "A heuristic
    for the time constrained asymmetric linear sum assignment problem" by Brown
    et al. (2017) [DOI:10.1007/s10878-015-9979-2].

    This implementation efficiently optimizes row-to-column assignments to
    maximize the overall benefit (or minimize costs). The algorithm includes
    greedy initialization, iterative row and column swaps, and dynamic updates
    to swap benefit calculations.

    """

    def __init__(self, backend: str = "numpy"):
        """Initializes the AsymmetricGreedySearch algorithm.

        Args:
            backend (str): which backend to use for Asymmetric Greedy Search.
                Options are "numpy" or "numba" (Default: "numpy").
        """
        self.backend = backend
        if backend == 'numpy':
            from .backend_numpy import optimize as numpy_optimize
            self._optimize = numpy_optimize
        elif backend == 'numba':
            try:
                from .backend_numba import optimize as numba_optimize
                self._optimize = numba_optimize
            except ImportError as e:
                raise ImportError("Numba backend selected but numba is not installed.") from e
        else:
            raise ValueError(f"Unsupported backend: {backend}")


    @staticmethod
    def min_max_scale(a: np.ndarray) -> np.ndarray:
        """Scales an array to the range [0, 1] using min-max normalization.

        Args:
            a (numpy.ndarray): The array to scale.

        Returns:
            numpy.ndarray: The scaled array.
        """
        min_val = a.min()
        range_val = np.ptp(a)
        return (a - min_val) / range_val if range_val > 0 else np.zeros_like(a)

    def optimize(self,
                 benefit: np.ndarray,
                 minimize: bool = False,
                 shuffle: bool = False,
                 maximum_iterations: int = 0,
                 stagnation_limit: int = 0,
                 stagnation_tolerance: float = 1e-3,
                 row_batch_size: int = 1) -> Tuple[np.ndarray, np.ndarray]:
        """A python implementation of the algorithm described in 'A heuristic
        for the time constrained asymmetric linear sum assignment problem'

        Args:
            benefit (numpy.ndarray): The benefit matrix for row-column
                assignments.
            minimize (bool): If True, minimize the assignment costs
                (default: False).
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
        if benefit.shape[0] > benefit.shape[1]:
            raise ValueError(f"Number of rows ({benefit.shape[0]}) must not exceed"
                             " number of columns ({benefit.shape[1]}) in the benefit matrix")
        benefit = self.min_max_scale(benefit)
        if minimize:
            benefit = 1 - benefit
        rows_idx, assignment = self._optimize(
            benefit=benefit,
            shuffle=shuffle,
            maximum_iterations=maximum_iterations,
            stagnation_limit=stagnation_limit,
            stagnation_tolerance=stagnation_tolerance,
            row_batch_size=row_batch_size)

        if np.unique(assignment).size != assignment.size:
            raise ValueError("Internal error: duplicate column assignments detected. Please report"
                             " this issue at https://github.com/kaboroevich/asymmetric-greedy-search/issues.")

        return rows_idx, assignment
