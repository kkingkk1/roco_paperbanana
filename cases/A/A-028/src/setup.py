import os
import platform
import sys

from setuptools import Extension, setup

machine: str = platform.machine().lower()


def get_ext_modules():
    import numpy

    if sys.platform == "win32":
        # extra_compile_args = ["/O2", "/arch:AVX2", "-std=c++11"]  # "/W4"
        extra_compile_args = ["/std:c++11"]  # "/W4"
    else:
        extra_compile_args = ["-std=c++11"]  # "-Wall"
        if sys.platform == "darwin":  # placeholders
            if "x86_64" in machine:
                # extra_compile_args.extend(["-O2", "-arch", "x86_64"])
                pass
            elif "arm" in machine or "aarch64" in machine:
                # extra_compile_args.extend(["-O2", "-arch", "arm64"])
                pass
        else:
            # extra_compile_args.extend(["-O2", "-march=native"])  # "-Wall"
            pass

    ext_kwargs = {
        "include_dirs": [numpy.get_include()],
        "define_macros": [
            ("NPY_NO_DEPRECATED_API", "NPY_1_7_API_VERSION"),
            # Compile against the NumPy 1.22 C-API even when building with NumPy 2.x
            # headers, so the resulting binaries import against any NumPy >= 1.22.
            # Pinned explicitly because the header default is raised over time,
            # which would silently lift the runtime floor on a later rebuild.
            ("NPY_TARGET_VERSION", "NPY_1_22_API_VERSION"),
        ],
        "extra_compile_args": extra_compile_args,
    }

    main_solver = Extension(
        "sbma._solver",
        language="c++",
        sources=[
            "src/sbma/_auxiliaries.cpp",
            "src/sbma/_util.cpp",
            "src/sbma/_starting_point.cpp",
            "src/sbma/_solver.cpp",
        ],
        **ext_kwargs,
    )

    ret = [main_solver]

    if os.environ.get('BUILD_WITH_BRUTE_FORCE'):
        brute_force_solver = Extension(
            "sbma._brute_force_solver",
            language="c++",
            sources=["src/sbma/_brute_force.cpp"],
            **ext_kwargs,
        )
        ret.append(brute_force_solver)

    return ret


if __name__ == "__main__":
    setup(ext_modules=get_ext_modules())
