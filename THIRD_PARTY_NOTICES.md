# Third-party notices

This repository and its Android build use the following third-party projects.
Their licenses apply to the respective third-party components and are not
replaced by the repository's Apache License 2.0.

## ncnn

- Project: [Tencent/ncnn](https://github.com/Tencent/ncnn)
- Version used by the Android build: `20260526`
- License: BSD 3-Clause
- Use: statically linked on-device neural network inference runtime. The
  Android archive is downloaded from the official GitHub release and verified
  against a pinned SHA-256 value during the native build.

Copyright (C) 2017 Tencent. All rights reserved.

## YOLOX

- Project: [Megvii-BaseDetection/YOLOX](https://github.com/Megvii-BaseDetection/YOLOX)
- Training revision: `6ddff4824372906469a7fae2dc3206c7aa4bbaee`
- License: Apache License 2.0
- Use: model architecture, base checkpoint, training and export tooling. The
  Android Focus compatibility layer follows the YOLOX ncnn deployment example.

Copyright (c) 2021 Megvii, Inc. All rights reserved.

## pnnx

- Project: [Tencent/pnnx](https://github.com/Tencent/ncnn/tree/master/tools/pnnx)
- Conversion version: `20260526`
- License: BSD 3-Clause as distributed with ncnn
- Use: build-time TorchScript-to-ncnn model conversion. It is not packaged in
  the Android application.

The complete upstream ncnn/pnnx and YOLOX license texts are bundled in the APK
and tracked at
[`android/app/src/main/assets/THIRD_PARTY_NOTICES.txt`](android/app/src/main/assets/THIRD_PARTY_NOTICES.txt).
