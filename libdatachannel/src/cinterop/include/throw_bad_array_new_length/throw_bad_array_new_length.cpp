#include <new>

namespace std {
    // Weak so this satisfies older MinGW libstdc++ implementations that miss the
    // symbol, while newer libstdc++ (GCC 13+) can provide the strong definition
    // without a duplicate-symbol link error.
    __attribute__((weak)) void __throw_bad_array_new_length() {
        throw std::bad_array_new_length();
    }
}