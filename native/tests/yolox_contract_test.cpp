#include "yolox_contract.h"

#include <cassert>

int main() {
    using namespace mapassist_yolox;

    assert(valid_input_size(320));
    assert(valid_input_size(416));
    assert(anchor_count(320) == 2100);
    assert(anchor_count(416) == 3549);
    assert(valid_output_shape(320, 1, 2100, 6));
    assert(valid_output_shape(416, 2, 3549, 7));

    assert(!valid_input_size(319));
    assert(!valid_input_size(321));
    assert(!valid_input_size(288));
    assert(!valid_input_size(412));
    assert(!valid_input_size(1056));
    assert(anchor_count(321) == 0);
    assert(!valid_output_shape(416, 2, 2100, 7));
    assert(!valid_output_shape(416, 9, 3549, 14));
    assert(!valid_output_shape(416, 2, 3549, 6));
    return 0;
}
