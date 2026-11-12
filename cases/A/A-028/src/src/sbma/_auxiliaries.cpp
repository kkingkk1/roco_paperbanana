#define PY_SSIZE_T_CLEAN
#include <Python.h>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include "_util.hpp"

#define MAX_MSG_LENGTH 256


/*
 * Write to Python's sys.stdout instead of the C stdout.
 * The two streams are buffered independently, so plain printf() output ends up
 * interleaved in the wrong order with print()/logging on the Python side as
 * soon as stdout is not a terminal (pipe, file, pytest capture, notebook).
 * Going through sys.stdout keeps everything in a single buffer and makes the
 * output follow any redirection done from Python.
 * Safe to call here: the extension never releases the GIL.
 */
void py_print(const char* fmt, ...) {
    char buf[MAX_MSG_LENGTH * 2];
    va_list args;

    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);

    PySys_WriteStdout("%s", buf);
}


void clear_buffers(void) {
    free(buf_gain_a);
    free(buf_gain_b);
    free(buf_idx_to_swap_a);
    free(buf_idx_to_swap_b);
    free(buf_mask_a);
    free(buf_mask_b);
    free(gain_per_column);
    free(previously_unchanged);
    free(previously_unchanged_temp);

    data_columns = nullptr;
    buf_gain_a = nullptr;
    buf_gain_b = nullptr;
    buf_idx_to_swap_a = nullptr;
    buf_idx_to_swap_b = nullptr;
    buf_mask_a = nullptr;
    buf_mask_b = nullptr;
    gain_per_column = nullptr;
    previously_unchanged = nullptr;
    previously_unchanged_temp = nullptr;
}

void clear_support_data(size_t n_cols) {
    if (!data_columns) return;

    if (data_columns->indexes) {
        free(data_columns->indexes);
    }

    if (data_columns->values) {
        for (size_t i = 0; i < n_cols; i++) {
            if (data_columns->values[i]) {
                free(data_columns->values[i]);
            }
        }
        free(data_columns->values);
    }

    free(data_columns);
    clear_buffers();
}

char* get_status_msg(const char* t, int n_loop, float t_run, bool verbose) {
    char* msg = (char*)malloc(MAX_MSG_LENGTH * sizeof(char));
    if (!msg) return nullptr;

    int iterations = n_loop + 1;
    msg[0] = '\0';

    if (strcmp(t, "first_step") == 0) {
        snprintf(msg, MAX_MSG_LENGTH,
            "Max process time reached after the first step. "
            "Do not enter into the swapping process");
    }
    else if (strcmp(t, "no_swap") == 0) {
        snprintf(msg, MAX_MSG_LENGTH,
            "No swaps requested. Runtime: %.3fs", t_run);
    }
    else if (strcmp(t, "time") == 0) {
        snprintf(msg, MAX_MSG_LENGTH,
            "Max process time reached, stop after %d iterations",
            iterations);
    }
    else if (strcmp(t, "loop") == 0) {
        snprintf(msg, MAX_MSG_LENGTH,
            "The solver halted due to reaching the iteration limit (%d) in %.3fs. Try increasing the maximum iterations to improve the result.",
            iterations, t_run);
    }
    else if (strcmp(t, "tol") == 0) {
        snprintf(msg, MAX_MSG_LENGTH,
             "Stop condition reached in %.3fs after %d iteration%s",
             t_run, iterations, (iterations == 1) ? "" : "s");
    }
    if (verbose && strlen(msg) > 0) {
        py_print("%s\n", msg);
    }

    return msg;
}
