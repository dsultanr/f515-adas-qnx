#!/usr/bin/env bash
set -euo pipefail
mkdir -p models/build
python models/fix_conv1d.py models/autospeed_fp32_opset12.onnx models/build/autospeed_snpe.onnx
python models/fix_splits.py models/build/autospeed_snpe.onnx models/build/autospeed_snpe2.onnx
python models/cut2.py models/build/autospeed_snpe2.onnx models/build/autospeed_heads3.onnx
python models/make-calibration.py /calibration models/build/calib_raw
snpe-onnx-to-dlc --input_network models/build/autospeed_heads3.onnx \
    --quantization_overrides models/cls_enc.json --output_path models/build/autospeed_heads3o_fp32.dlc
snpe-dlc-quantize --input_dlc models/build/autospeed_heads3o_fp32.dlc \
    --input_list models/build/calib_raw/list.txt --use_enhanced_quantizer --override_params \
    --output_dlc models/build/autospeed_heads3o_int8.dlc
sha256sum models/build/autospeed_heads3o_int8.dlc
