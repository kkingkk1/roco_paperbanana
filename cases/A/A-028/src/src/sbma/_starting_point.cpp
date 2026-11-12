#include <cstdio>
#include <cfloat>
#include "_util.hpp"

// ---------------------------- QUICKSORT
static void swap(int* a, int* b) {
    int temp = *a;
    *a = *b;
    *b = temp;
}

static int partition(const float* scores, int* indices, int low, int high) {
    float pivot = scores[indices[high]];
    int i = low - 1;

    for (int j = low; j < high; j++) {
        if (scores[indices[j]] > pivot) {
            i++;
            swap(&indices[i], &indices[j]);
        }
    }
    swap(&indices[i + 1], &indices[high]);
    return i + 1;
}

static void quicksort(const float* scores, int* indices, int low, int high) {
    if (low < high) {
        int pi = partition(scores, indices, low, high);
        quicksort(scores, indices, low, pi - 1);
        quicksort(scores, indices, pi + 1, high);
    }
}

static void sort_indices_descending(const float* scores, int* indices, int n) {
    for (int i = 0; i < n; i++) {
        indices[i] = i;
    }
    quicksort(scores, indices, 0, n - 1);
}

// ---------------------------- ROW SCORES
static void calculate_row_scores_scalar(const float* mat, float* scores, size_t n_rows, size_t n_cols) {
    for (size_t idx_row = 0; idx_row < n_rows; idx_row++) {
        const float* row = mat + idx_row * n_cols;
        float max_val = row[0];
        float second_max = -FLT_MAX;

        for (size_t idx_col = 1; idx_col < n_cols; idx_col++) {
            if (row[idx_col] > max_val) {
                second_max = max_val;
                max_val = row[idx_col];
            } else if (row[idx_col] > second_max) {
                second_max = row[idx_col];
            }
        }

        // Special case: if all elements are equal or n_cols == 1
        if (second_max == -FLT_MAX) {
            second_max = max_val;
        }

        scores[idx_row] = max_val + (max_val - second_max);  // best one
        // scores[idx_row] = max_val;
        // scores[idx_row] = max_val - second_max;
    }
}

// ---------------------------- ENTRY POINT
static bool get_optimal_starting_point_scalar(
    const float* mat,
    const int* ar_counts,
    size_t* initial_assign,
    size_t n_rows,
    size_t n_cols
) {
    float* row_scores = (float*)malloc(n_rows * sizeof(float));
    int* sorted_indices = (int*)malloc(n_rows * sizeof(int));
    int* remaining_counts = (int*)malloc(n_cols * sizeof(int));
    char* taken_rows = (char*)calloc(n_rows, sizeof(char));

    if (!row_scores || !sorted_indices || !remaining_counts || !taken_rows) {
        free(row_scores);
        free(sorted_indices);
        free(remaining_counts);
        free(taken_rows);
        return false;
    }

    int n_cols_32 = (int)n_cols;

    calculate_row_scores_scalar(mat, row_scores, n_rows, n_cols);
    sort_indices_descending(row_scores, sorted_indices, (int)n_rows);

    remaining_counts = (int*)memcpy(remaining_counts, ar_counts, n_cols * sizeof(int));

    for (size_t idx_row = 0; idx_row < n_rows; idx_row++) {
        int row_best_idx = sorted_indices[idx_row];

        if (taken_rows[row_best_idx]) {
            continue;
        }

        int best_col = -1;
        float best_val = -FLT_MAX;
        const float* row = mat + row_best_idx * n_cols;

        for (int idx_col = 0; idx_col < n_cols_32; idx_col++) {
            if (remaining_counts[idx_col] > 0 && row[idx_col] > best_val) {
                best_val = row[idx_col];
                best_col = idx_col;
            }
        }

        if (best_col != -1) {
            initial_assign[row_best_idx] = (size_t)best_col;
            remaining_counts[best_col]--;
            taken_rows[row_best_idx] = 1;
        }
    }
    free(row_scores);
    free(sorted_indices);
    free(remaining_counts);
    free(taken_rows);
    return true;
}

bool get_optimal_starting_point(
    float* mat,
    int* ar_counts,
    size_t* initial_assign,
    size_t n_rows,
    size_t n_cols,
    bool verbose
) {
    bool flag;
    flag = get_optimal_starting_point_scalar(mat, ar_counts, initial_assign, n_rows, n_cols);
    return flag;
}


bool get_random_starting_point(
    int* ar_counts,
    size_t* initial_assign,
    size_t n_cols
 ) {
    size_t start = 0;
    size_t idx_col = 0;

    for (size_t idx_count = 0; idx_count < n_cols; idx_count++) {
        size_t count = (size_t)ar_counts[idx_count];
        size_t end = start + count;
        for (size_t i = start; i < end; i++) {
            initial_assign[i] = idx_col;
        }
        idx_col++;
        start += count;
    }

    return true;
}
