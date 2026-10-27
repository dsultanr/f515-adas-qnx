#pragma once
// One-shot camera capture, with no SNPE initialization, PPS writes or ADAS lifecycle changes.
int calibration_snapshot(const char* path, int input);
