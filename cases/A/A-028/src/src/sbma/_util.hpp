#ifndef UTILS_HPP
#define UTILS_HPP

/*
 * Implementation Notes:
 * ----------------------------------------
 * This implementation deliberately uses C-style code and avoids C++ features like:
 * - Classes/inheritance
 * - STL containers (std::vector, etc.)
 * - RAII patterns
 * - Exceptions
 * 
 * Advantages:
 * - Direct memory management with malloc/free has less overhead
 * - Simpler ABI
 * - Fewer indirections and virtual table lookups
 * - Closer to hardware for this performance-critical numerical code
 * 
 * While this makes the code less "modern C++", benchmarking showed a 5%
 * performance increase with this approach for this specific numerical
 * optimization task.
 */

#include <cstddef>
#include <cstdlib>
#include <cstring>
#define _USE_MATH_DEFINES
#include <cmath>

struct DataColumn {
    size_t** indexes;
    float** values;
};

inline DataColumn* create_data_column(int n) {
    DataColumn* d = (DataColumn*)malloc(sizeof(DataColumn));
    if (!d) return nullptr;

    d->indexes = nullptr;
    d->values = nullptr;

    d->indexes = (size_t**)malloc(n * sizeof(size_t*));
    if (!d->indexes) {
        free(d);
        return nullptr;
    }

    d->values = (float**)malloc(n * sizeof(float*));
    if (!d->values) {
        free(d->indexes);
        free(d);
        return nullptr;
    }

    return d;
}

extern DataColumn* data_columns;
extern bool* previously_unchanged;
extern bool* previously_unchanged_temp;

// Buffer arrays
extern float* buf_gain_a;
extern float* buf_gain_b;
extern int* buf_idx_to_swap_a;
extern int* buf_idx_to_swap_b;
extern char* buf_mask_a;
extern char* buf_mask_b;
extern float* gain_per_column;

// Auxiliaries
void py_print(const char* fmt, ...);
void clear_buffers(void);
void clear_support_data(size_t n_cols);
char* get_status_msg(const char* t, int n_loop, float t_run, bool verbose);

// Utils
int find_positive_swaps(int n_a, int n_b, int n_min);
float get_current_sum(const int* ar_counts, size_t n_cols);
float get_current_sum_by_idx(float* mat, const int* ar_counts, size_t n_cols);
float get_swap_increase(size_t n_cols);

// Starting point functions
bool get_optimal_starting_point(
    float* mat,
    int* ar_counts,
    size_t* initial_assign,
    size_t n_rows,
    size_t n_cols,
    bool verbose
);
bool get_random_starting_point(
    int* ar_counts,
    size_t* initial_assign,
    size_t n_cols
);

#endif
