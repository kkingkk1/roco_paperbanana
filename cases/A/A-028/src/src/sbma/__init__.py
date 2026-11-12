from importlib.metadata import PackageNotFoundError, version

from .solver import *

try:
    __version__ = version("sbma")
except PackageNotFoundError:  # running from a source tree, not installed
    __version__ = "0.0.0"

__all__ = [
    "DimensionError",
    "ShapeError",
    "assert_results",
    "generate_random_input",
    "solve",
]
