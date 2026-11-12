#include <cstdlib>
#include <cstring>
#include "_util.hpp"


int find_positive_swaps(int n_a, int n_b, int n_min) {
    memset(buf_mask_a, 0, n_a * sizeof(char));
    memset(buf_mask_b, 0, n_b * sizeof(char));
    int actual_swaps = 0;

    float max_a, max_b;
    int max_idx_a, max_idx_b;

    while (actual_swaps < n_min) {
        // Find next maximum in array A that hasn't been used
        int i;
        for (i = 0; i < n_a; i++) {
            if (!buf_mask_a[i]) {
                max_a = buf_gain_a[i];
                max_idx_a = i;
                break;
            }
        }
        if (i == n_a) break; // All elements in A have been used

        for (i++; i < n_a; i++) {
            if (!buf_mask_a[i] && buf_gain_a[i] > max_a) {
                max_a = buf_gain_a[i];
                max_idx_a = i;
            }
        }

        // Find next maximum in array B that hasn't been used
        for (i = 0; i < n_b; i++) {
            if (!buf_mask_b[i]) {
                max_b = buf_gain_b[i];
                max_idx_b = i;
                break;
            }
        }
        if (i == n_b) break; // All elements in B have been used

        for (i++; i < n_b; i++) {
            if (!buf_mask_b[i] && buf_gain_b[i] > max_b) {
                max_b = buf_gain_b[i];
                max_idx_b = i;
            }
        }

        if (max_a + max_b <= 0) {
            break;
        }

        buf_idx_to_swap_a[actual_swaps] = max_idx_a;
        buf_idx_to_swap_b[actual_swaps] = max_idx_b;
        buf_mask_a[max_idx_a] = 1;
        buf_mask_b[max_idx_b] = 1;

        actual_swaps++;
    }
    return actual_swaps;
}

float get_swap_increase(size_t n_cols) {
    float ret = 0.0f;
    for (size_t i = 0; i < n_cols; ++i) {
        ret += gain_per_column[i];
    }
    return ret;
}

float get_current_sum(const int* ar_counts, size_t n_cols) {
    float sum = 0.0f;
    float c = 0.0f;  // Compensation for Kahan summation

    for (size_t k = 0; k < n_cols; ++k) {
        float* ar = data_columns->values[k];
        const int size = ar_counts[k];
        
        for (int i = 0; i < size; i++) {
            float y = ar[i] - c;
            float t = sum + y;
            c = (t - sum) - y;
            sum = t;
        }
    }
    return sum;
}

float get_current_sum_by_idx(float* mat, const int* ar_counts, size_t n_cols) {
    float sum = 0.0f;
    float c = 0.0f;  // Compensation for Kahan summation

    for (size_t k = 0; k < n_cols; ++k) {
        size_t* ar = data_columns->indexes[k];
        const int size = ar_counts[k];

        for (int i = 0; i < size; ++i) {
            float value = mat[ar[i] * n_cols + k];
            float y = value - c;
            float t = sum + y;
            c = (t - sum) - y;
            sum = t;
        }
    }
    return sum;
}
