#define NPY_NO_DEPRECATED_API NPY_1_7_API_VERSION
#define PY_SSIZE_T_CLEAN
#include <Python.h>
#include <numpy/arrayobject.h>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include "_util.hpp"

DataColumn* data_columns = nullptr;
bool* previously_unchanged = nullptr;
bool* previously_unchanged_temp = nullptr;
float* buf_gain_a = nullptr;
float* buf_gain_b = nullptr;
int* buf_idx_to_swap_a = nullptr;
int* buf_idx_to_swap_b = nullptr;
char* buf_mask_a = nullptr;
char* buf_mask_b = nullptr;
float* gain_per_column = nullptr;
float* max_gains_matrix = nullptr;

// To measure times
float t_gains = 0.0f;
float t_swaps = 0.0f;
float t_update = 0.0f;

float STORED_SUM = 0.0f;

static inline size_t triangular_index(size_t i, size_t j, size_t n) {
    // "i" always less than "j"
    // return (n * i) - ((i * (i + 1)) / 2) + j - i - 1;
    return (i * (2 * n - i - 1)) / 2 + (j - i - 1);
}

static inline float get_max_gain(size_t i, size_t j, size_t n_cols) {
    return max_gains_matrix[triangular_index(i, j, n_cols)];
}

static inline void set_max_gain(size_t i, size_t j, size_t n_cols, float value) {
    max_gains_matrix[triangular_index(i, j, n_cols)] = value;
}

static bool initialize_support_data(
    const float* mat,
    const int* ar_counts,
    const size_t n_rows,
    const size_t n_cols,
    const size_t* initial_assign
) {
    clear_support_data(n_cols);
    int n_cols_32 = (int)n_cols;
    int max_count = 0;

    int count;
    size_t idx_row, idx_col, cur_idx;
    float value;
    size_t* ar_idx;
    float* ar_val;

    size_t* buf_counter = (size_t*)calloc(n_cols, sizeof(size_t));
    if (!buf_counter) goto cleanup;

    // Initialize main structures
    data_columns = create_data_column(n_cols_32);
    if (!data_columns) goto cleanup;

    gain_per_column = (float*)malloc(n_cols_32 * sizeof(float));
    if (!gain_per_column) goto cleanup;

    previously_unchanged = (bool*)malloc(n_cols * sizeof(bool));
    if (!previously_unchanged) goto cleanup;
    
    // Initialize all to false (all need checking initially)
    memset(previously_unchanged, 0, n_cols * sizeof(bool));

    previously_unchanged_temp = (bool*)malloc(n_cols * sizeof(bool));
    if (!previously_unchanged_temp) goto cleanup;

    max_gains_matrix = (float*)malloc(n_cols * n_cols * sizeof(float));
    if (!max_gains_matrix) goto cleanup;

    for (idx_col = 0; idx_col < n_cols; ++idx_col) {
        count = ar_counts[idx_col];
        if (count > max_count) {
            max_count = count;
        }

        ar_idx = (size_t*)malloc(count * sizeof(size_t));
        if (!ar_idx) goto cleanup;
        data_columns->indexes[idx_col] = ar_idx;

        ar_val = (float*)malloc(count * sizeof(float));
        if (!ar_val) goto cleanup;
        data_columns->values[idx_col] = ar_val;
    }

    for (idx_row = 0; idx_row < n_rows; idx_row++) {
        idx_col = initial_assign[idx_row];
        cur_idx = buf_counter[idx_col];
        data_columns->indexes[idx_col][cur_idx] = idx_row;

        value = mat[idx_row * n_cols + idx_col];
        data_columns->values[idx_col][cur_idx] = value;

        buf_counter[idx_col]++;
    }

    free(buf_counter);

    // Allocate buffers based on MAX_COUNT
    buf_idx_to_swap_a = (int*)malloc(max_count * sizeof(int));
    if (!buf_idx_to_swap_a) goto cleanup;

    buf_idx_to_swap_b = (int*)malloc(max_count * sizeof(int));
    if (!buf_idx_to_swap_b) goto cleanup;

    buf_mask_a = (char*)calloc(max_count, sizeof(char));
    if (!buf_mask_a) goto cleanup;

    buf_mask_b = (char*)calloc(max_count, sizeof(char));
    if (!buf_mask_b) goto cleanup;

    buf_gain_a = (float*)malloc(max_count * sizeof(float));
    if (!buf_gain_a) goto cleanup;

    buf_gain_b = (float*)malloc(max_count * sizeof(float));
    if (!buf_gain_b) goto cleanup;

    return true;

cleanup:
    if (data_columns) {
        if (data_columns->indexes) {
            for (size_t i = 0; i < n_cols; i++) {
                free(data_columns->indexes[i]);
            }
            free(data_columns->indexes);
        }
    }
    
    clear_support_data(n_cols);
    return false;
}

static bool compile_output_dictionary(
    const int* ar_counts,
    const size_t n_rows,
    const size_t n_cols,
    const size_t* initial_assign
) {
    clear_support_data(n_cols);
    int n_cols_32 = (int)n_cols;
    size_t idx_row, idx_col, cur_idx;
    size_t* ar_idx;
    int* buf_counter;
    int count;

    data_columns = create_data_column(n_cols_32);
    if (!data_columns) goto cleanup;

    for (idx_col = 0; idx_col < n_cols; ++idx_col) {
        count = ar_counts[idx_col];
        ar_idx = (size_t*)malloc(count * sizeof(size_t));
        if (!ar_idx) goto cleanup;
        data_columns->indexes[idx_col] = ar_idx;
    }

    buf_counter = (int*)calloc(n_cols, sizeof(int));
    if (!buf_counter) goto cleanup;

    for (idx_row = 0; idx_row < n_rows; idx_row++) {
        idx_col = initial_assign[idx_row];
        cur_idx = buf_counter[idx_col];
        // printf("idx_row: %zu, idx_col: %zu, cur_idx: %zu\n", idx_row, idx_col, cur_idx);
        data_columns->indexes[idx_col][cur_idx] = idx_row;
        buf_counter[idx_col]++;
    }

    free(buf_counter);
    return true;

cleanup:
    clear_support_data(n_cols);
    return false;
}

static float calculate_gains(
    const float* mat,
    const size_t* ar_idx,
    const float* ar_val,
    float* ar_gain,
    const int count,
    const size_t n_cols,
    const size_t idx_col_other
) {
    // 15% / 20% faster than the equivalent
    float max_gain = -INFINITY;
    for (int i = 0; i < count; i++) {
        float v = mat[ar_idx[i] * n_cols + idx_col_other] - ar_val[i];
        ar_gain[i] = v;
        max_gain = (v > max_gain) ? v : max_gain;
    }

//    float max_gain = -INFINITY;
//    int i = 0;
//    float gain0, gain1, gain2, gain3;
//
//    if (count < 4) {
//        goto handle_remainder;
//    }
//
//    for (int j = 0; j < 4 && j < count; j++) {
//        PREFETCH(&mat[ar_idx[j] * n_cols + idx_col_other]);
//        PREFETCH(&ar_val[j]);
//    }
//
//    for (; i < count - 3; i += 4) {
//        if (i + 8 < count) {
//            PREFETCH(&mat[ar_idx[i + 8] * n_cols + idx_col_other]);
//            PREFETCH(&ar_val[i + 8]);
//        }
//
//        gain0 = mat[ar_idx[i] * n_cols + idx_col_other] - ar_val[i];
//        gain1 = mat[ar_idx[i+1] * n_cols + idx_col_other] - ar_val[i+1];
//        gain2 = mat[ar_idx[i+2] * n_cols + idx_col_other] - ar_val[i+2];
//        gain3 = mat[ar_idx[i+3] * n_cols + idx_col_other] - ar_val[i+3];
//
//        ar_gain[i] = gain0;
//        max_gain = gain0 > max_gain ? gain0 : max_gain;
//
//        ar_gain[i+1] = gain1;
//        max_gain = gain1 > max_gain ? gain1 : max_gain;
//
//        ar_gain[i+2] = gain2;
//        max_gain = gain2 > max_gain ? gain2 : max_gain;
//
//        ar_gain[i+3] = gain3;
//        max_gain = gain3 > max_gain ? gain3 : max_gain;
//    }
//
//handle_remainder:
//    for (; i < count; i++) {
//        ar_gain[i] = mat[ar_idx[i] * n_cols + idx_col_other] - ar_val[i];
//        max_gain = ar_gain[i] > max_gain ? ar_gain[i] : max_gain;
//    }
    return max_gain;
}

void update_swaps(
    const float* mat,
    const size_t idx_col_a,
    const size_t idx_col_b,
    size_t* ar_idx_a,
    size_t* ar_idx_b,
    float* ar_val_a,
    float* ar_val_b,
    const size_t n_cols,
    const int actual_swaps
) {
    for (int i = 0; i < actual_swaps; i++) {
        int idx_swap_a = buf_idx_to_swap_a[i];
        int idx_swap_b = buf_idx_to_swap_b[i];
        size_t swap_b = ar_idx_a[idx_swap_a];
        size_t swap_a = ar_idx_b[idx_swap_b];

        ar_idx_a[idx_swap_a] = swap_a;
        ar_idx_b[idx_swap_b] = swap_b;

        float new_v_a = mat[swap_a * n_cols + idx_col_a];
        float new_v_b = mat[swap_b * n_cols + idx_col_b];

        gain_per_column[idx_col_a] += (new_v_a - ar_val_a[idx_swap_a]);
        gain_per_column[idx_col_b] += (new_v_b - ar_val_b[idx_swap_b]);

        ar_val_a[idx_swap_a] = new_v_a;
        ar_val_b[idx_swap_b] = new_v_b;
    }
}

void swap_initial(
    const float* mat,
    const int* ar_counts,
    size_t n_cols
) {
    memset(previously_unchanged_temp, 1, n_cols * sizeof(bool));
    // int counter_checks = 0;
    // clock_t t_start;

    for (size_t idx_col_a = 0; idx_col_a < n_cols - 1; idx_col_a++) {
        float* ar_val_a = data_columns->values[idx_col_a];
        size_t* ar_idx_a = data_columns->indexes[idx_col_a];
        int count_a = ar_counts[idx_col_a];
        int n_a = ar_counts[idx_col_a];

        for (size_t idx_col_b = idx_col_a + 1; idx_col_b < n_cols; idx_col_b++) {
            float* ar_val_b = data_columns->values[idx_col_b];
            size_t* ar_idx_b = data_columns->indexes[idx_col_b];
            int count_b = ar_counts[idx_col_b];

            // t_start = clock();
            float max_gain_a = calculate_gains(mat, ar_idx_a, ar_val_a, buf_gain_a, count_a, n_cols, idx_col_b);
            set_max_gain(idx_col_a, idx_col_b, n_cols, max_gain_a);
            float max_gain_b = calculate_gains(mat, ar_idx_b, ar_val_b, buf_gain_b, count_b, n_cols, idx_col_a);
            set_max_gain(idx_col_b, idx_col_a, n_cols, max_gain_b);
            // t_gains += (float)(clock() - t_start) / CLOCKS_PER_SEC;

            if (max_gain_a + max_gain_b <= 0) {
                continue;
            }

            int n_b = ar_counts[idx_col_b];
            int n_min = (n_a < n_b) ? n_a : n_b;
            int actual_swaps = find_positive_swaps(n_a, n_b, n_min);
            // t_swaps += (float)(clock() - t_start) / CLOCKS_PER_SEC;

            if (actual_swaps > 0) {
                // t_start = clock();
                // printf("Update assignments\n");
                update_swaps(
                    mat,
                    idx_col_a,
                    idx_col_b,
                    ar_idx_a,
                    ar_idx_b,
                    ar_val_a,
                    ar_val_b,
                    n_cols,
                    actual_swaps
                );
                previously_unchanged_temp[idx_col_a] = false;
                previously_unchanged_temp[idx_col_b] = false;
                // t_update += (float)(clock() - t_start) / CLOCKS_PER_SEC;
            }
        }
    }
    memcpy(previously_unchanged, previously_unchanged_temp, n_cols * sizeof(bool));
    // printf("t_gains: %f, t_swaps: %f, t_update: %f\n", t_gains, t_swaps, t_update);
}

void swap(
    const float* mat,
    const int* ar_counts,
    size_t n_cols
) {
    memset(previously_unchanged_temp, 1, n_cols * sizeof(bool));
    float max_gain_a, max_gain_b;
    // int counter_checks = 0;
    // clock_t t_start;

    for (size_t idx_col_a = 0; idx_col_a < n_cols - 1; idx_col_a++) {
        float* ar_val_a = data_columns->values[idx_col_a];
        size_t* ar_idx_a = data_columns->indexes[idx_col_a];
        int count_a = ar_counts[idx_col_a];
        bool prev_unchanged_a = previously_unchanged[idx_col_a];
        int n_a = ar_counts[idx_col_a];

        for (size_t idx_col_b = idx_col_a + 1; idx_col_b < n_cols; idx_col_b++) {
            // Skip if both columns were unchanged in the previous iteration
            if (prev_unchanged_a && previously_unchanged[idx_col_b]) {
                continue;
            }

            float* ar_val_b = data_columns->values[idx_col_b];
            size_t* ar_idx_b = data_columns->indexes[idx_col_b];
            int count_b = ar_counts[idx_col_b];

            // t_start = clock();
            // Using cached gains to asses whether continuing or
            // not gives 20% improvement after 3/4 iterations
            // float max_gain_a = calculate_gains(mat, ar_idx_a, ar_val_a, buf_gain_a, count_a, n_cols, idx_col_b);
            // float max_gain_b = calculate_gains(mat, ar_idx_b, ar_val_b, buf_gain_b, count_b, n_cols, idx_col_a);

            bool gain_a_to_calculate = true;
            bool gain_b_to_calculate = true;
            if (prev_unchanged_a) {
                max_gain_a = get_max_gain(idx_col_a, idx_col_b, n_cols);
                max_gain_b = calculate_gains(mat, ar_idx_b, ar_val_b, buf_gain_b, count_b, n_cols, idx_col_a);
                set_max_gain(idx_col_b, idx_col_a, n_cols, max_gain_b);
                gain_b_to_calculate = false;
                if ((max_gain_a + max_gain_b) <= 0) {
                    continue;
                }
            } else if (previously_unchanged[idx_col_b]) {
                max_gain_b = get_max_gain(idx_col_b, idx_col_a, n_cols);
                max_gain_a = calculate_gains(mat, ar_idx_a, ar_val_a, buf_gain_a, count_a, n_cols, idx_col_b);
                set_max_gain(idx_col_a, idx_col_b, n_cols, max_gain_a);
                gain_a_to_calculate = false;
                if ((max_gain_a + max_gain_b) <= 0) {
                    continue;
                }
            }

            if (gain_a_to_calculate) {
                max_gain_a = calculate_gains(mat, ar_idx_a, ar_val_a, buf_gain_a, count_a, n_cols, idx_col_b);
                set_max_gain(idx_col_a, idx_col_b, n_cols, max_gain_a);
            }
            if (gain_b_to_calculate) {
                max_gain_b = calculate_gains(mat, ar_idx_b, ar_val_b, buf_gain_b, count_b, n_cols, idx_col_a);
                set_max_gain(idx_col_b, idx_col_a, n_cols, max_gain_b);
            }
            // t_gains += (float)(clock() - t_start) / CLOCKS_PER_SEC;

            if (max_gain_a + max_gain_b <= 0) {
                continue;
            }

            int n_b = ar_counts[idx_col_b];
            int n_min = (n_a < n_b) ? n_a : n_b;
            int actual_swaps = find_positive_swaps(n_a, n_b, n_min);
            // t_swaps += (float)(clock() - t_start) / CLOCKS_PER_SEC;

            if (actual_swaps > 0) {
                // t_start = clock();
                update_swaps(
                    mat, 
                    idx_col_a,
                    idx_col_b,
                    ar_idx_a,
                    ar_idx_b,
                    ar_val_a,
                    ar_val_b,
                    n_cols,
                    actual_swaps
                );
                previously_unchanged_temp[idx_col_a] &= (max_gain_a < 0);
                previously_unchanged_temp[idx_col_b] &= (max_gain_b < 0);
                // t_update += (float)(clock() - t_start) / CLOCKS_PER_SEC;
            }
        }
    }
    memcpy(previously_unchanged, previously_unchanged_temp, n_cols * sizeof(bool));
    // printf("t_gains: %f, t_swaps: %f, t_update: %f\n", t_gains, t_swaps, t_update);
    // printf("counter_checks: %d\n", counter_checks);
}

extern "C" {

static PyObject* solve(PyObject* self, PyObject* args) {
    PyArrayObject *py_mat, *py_allocation_counts;
    double max_process_time;
    double error_threshold_abs;
    double error_threshold_pct;
    int max_iterations = 0;  // Initialize with default !!!
    int skip_first_phase_int = 0;     // Use int instead of bool for PyArg_ParseTuple
    int verbose_int = 0;     // Use int instead of bool for PyArg_ParseTuple

    if (!PyArg_ParseTuple(args, "O!O!dddi|i|i",
                         &PyArray_Type, &py_mat,
                         &PyArray_Type, &py_allocation_counts,
                         &max_process_time,
                         &error_threshold_abs,
                         &error_threshold_pct,
                         &max_iterations,      // Direct int parsing !!!
                         &skip_first_phase_int,      // Parse bool as int
                         &verbose_int)) {      // Parse bool as int
        return nullptr;
    }

    bool skip_first_phase = (skip_first_phase_int != 0);  // Convert to bool after parsing !!!
    bool verbose = (verbose_int != 0);  // Convert to bool after parsing !!!

    // printf("Size of int on this platform: %zu bytes\n", sizeof(int));
    // printf("Size of long on this platform: %zu bytes\n", sizeof(long));

    // printf("error_threshold_abs: %f\n", error_threshold_abs);
    // printf("error_threshold_abs: %f\n", error_threshold_abs);

    size_t n_rows = (size_t)PyArray_DIM(py_mat, 0);
    size_t n_cols = (size_t)PyArray_DIM(py_mat, 1);

    float* mat = (float*)PyArray_DATA(py_mat);
    int* ar_counts = (int*)PyArray_DATA(py_allocation_counts);

    size_t* initial_assign = (size_t*)malloc(n_rows * sizeof(size_t));
    if (!initial_assign) {
        PyErr_SetString(PyExc_MemoryError, "Failed to allocate memory for arrays");
        return nullptr;
    }

    clock_t start = clock();

    if (skip_first_phase) {
        get_random_starting_point(ar_counts, initial_assign, n_cols);
    } else {
        if (!get_optimal_starting_point(mat, ar_counts, initial_assign, n_rows, n_cols, verbose)) {
            PyErr_SetString(PyExc_MemoryError, "Failed to get the optimal starting point");
            free(initial_assign);
            return nullptr;
        }
    }

    clock_t end = clock();
    float time_spent = (float)(end - start) / CLOCKS_PER_SEC;
    char* status_msg = nullptr;

    bool flag_stop_abs, flag_stop_pct;
    int n_loop;
    float current_sum, t_run, t_loop, increase;
    bool early_exit = false;

    if ((max_iterations == 0) || (time_spent > max_process_time)) {
        early_exit = compile_output_dictionary(ar_counts, n_rows, n_cols, initial_assign);
        if (!early_exit) {
            PyErr_SetString(PyExc_MemoryError, "Failed to compile output dictionary");
            free(initial_assign);
            return nullptr;
        }

        current_sum = get_current_sum_by_idx(mat, ar_counts, n_cols);

        if (max_iterations == 0) {
            status_msg = get_status_msg("no_swap", 0, 0.0f, verbose); 
        } else { 
            status_msg = get_status_msg("first_step", 0, time_spent, verbose); 
        }

        goto exit_solver;
    }

    if (verbose && !skip_first_phase) {
        py_print("Time spent to get the starting point: %fs\n", time_spent);
    }

    if (!initialize_support_data(mat, ar_counts, n_rows, n_cols, initial_assign)) {
        PyErr_SetString(PyExc_MemoryError, "Failed to initialize support data");
        free(initial_assign);
        return nullptr;
    }

    current_sum = get_current_sum(ar_counts, n_cols);
    if (verbose) {
        py_print("Total sum of selected values: %f\n", current_sum);
    }
    STORED_SUM = current_sum;

    t_run = time_spent;

    for (n_loop = 0; n_loop < max_iterations; n_loop++) {
        clock_t t_start = clock();

        memset(gain_per_column, 0, n_cols * sizeof(float));
        if (n_loop == 0) {
            swap_initial(mat, ar_counts, n_cols);
        } else {
            swap(mat, ar_counts, n_cols);
        }
        increase = get_swap_increase(n_cols);

        clock_t t_end = clock();
        t_loop = (float)(t_end - t_start) / CLOCKS_PER_SEC;
        t_run += t_loop;

        current_sum += increase;

        if (verbose) {
            py_print("Time for iteration %d: %.3fs. Current score sum: %.6f, increase: %.6f\n",
                   n_loop, t_loop, current_sum, increase);
        }

        if (max_process_time > 0 && t_run > max_process_time) {
            status_msg = get_status_msg("time", n_loop, t_run, verbose);
            break;
        }

        if (increase <= 0) {
            status_msg = get_status_msg("tol", n_loop, t_run, verbose);
            break;
        }

        flag_stop_abs = false;
        if (error_threshold_abs > 0) {
            if (increase < error_threshold_abs) {
                flag_stop_abs = true;
            }
        }

        flag_stop_pct = false;
        if (error_threshold_pct > 0) {
            if ((100 * increase / current_sum) < error_threshold_pct) {
                flag_stop_pct = true;
            }
        }

        if (flag_stop_abs || flag_stop_pct) {
            status_msg = get_status_msg("tol", n_loop, t_run, verbose);
            break;
        }
    }

    if (n_loop == max_iterations) {
        n_loop = max_iterations - 1;
        status_msg = get_status_msg("loop", n_loop, t_run, verbose);
    }

    free(max_gains_matrix);

exit_solver:
    free(initial_assign);

    PyObject* result_dict = PyDict_New();
    if (!result_dict) return nullptr;

    PyObject* result_tuple = PyTuple_New(3);
    if (!result_tuple) {
        Py_DECREF(result_dict);
        return nullptr;
    }

    PyTuple_SET_ITEM(result_tuple, 1, PyUnicode_FromString(status_msg));
    PyTuple_SET_ITEM(result_tuple, 2, PyFloat_FromDouble(current_sum));
    free(status_msg);

    for (size_t k = 0; k < n_cols; ++k) {
        PyObject* key = PyLong_FromSize_t(k);
        if (!key) {
            Py_DECREF(result_dict);
            Py_DECREF(result_tuple);
            return nullptr;
        }

        npy_intp dims[] = {ar_counts[k]};
        PyObject* array = PyArray_SimpleNewFromData(1, dims, NPY_UINTP, data_columns->indexes[k]);
        if (!array) {
            Py_DECREF(key);
            Py_DECREF(result_dict);
            Py_DECREF(result_tuple);
            return nullptr;
        }
        PyArray_ENABLEFLAGS((PyArrayObject*)array, NPY_ARRAY_OWNDATA);

        if (PyDict_SetItem(result_dict, key, array) < 0) {
            Py_DECREF(key);
            Py_DECREF(array);
            Py_DECREF(result_dict);
            Py_DECREF(result_tuple);
            return nullptr;
        }
        Py_DECREF(key);
        Py_DECREF(array);
    }

    PyTuple_SET_ITEM(result_tuple, 0, result_dict);
    if (early_exit) {
        free(data_columns->indexes);
        clear_buffers();
    } else {
        clear_support_data(n_cols);
    }
    return result_tuple;
}

static PyMethodDef methods[] = {
    {"solve", (PyCFunction)solve, METH_VARARGS, "C++ implementation of the solver"},
    {nullptr, nullptr, 0, nullptr}
};

static struct PyModuleDef module = {
    PyModuleDef_HEAD_INIT,
    "_solver",
    nullptr,
    -1,
    methods
};

PyMODINIT_FUNC PyInit__solver(void) {
    import_array();  // Must be called for NumPy support
    return PyModule_Create(&module);
}

} // extern "C"
