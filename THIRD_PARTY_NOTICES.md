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

## SpeexDSP

- Project: [Xiph.Org/speexdsp](https://github.com/xiph/speexdsp)
- Version used by the Android build: `SpeexDSP-1.2.1`
- License: BSD-style license in the upstream `COPYING` file
- Use: optional software echo cancellation for assistant voice input and
  SpeexDSP PCM resampling for the offline playback reference. The Android
  build downloads the official source archive and verifies its pinned SHA-256.

Copyright 2002-2008 Xiph.org Foundation and Jean-Marc Valin.
Copyright 2005-2007 Analog Devices Inc.
Copyright 2005-2008 Commonwealth Scientific and Industrial Research Organisation (CSIRO).
Copyright 1993, 2002, 2006 David Rowe.
Copyright 2003 EpicGames.
Copyright 1992-1994 Jutta Degener and Carsten Bormann.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the name of the Xiph.org Foundation nor the names of its
   contributors may be used to endorse or promote products derived from this
   software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE FOUNDATION OR CONTRIBUTORS BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## WebRTC Voice Activity Detector

- Project: WebRTC Common Audio VAD C implementation
- Source carrier: `webrtcvad` / `py-webrtcvad` 2.0.10 source distribution
- Source archive SHA-256: `f1bed2fb25b63fb7b1a55d64090c993c9c9167b28485ae0bcdd81cf6ede96aea`
- License: WebRTC BSD-style license from the source distribution's `LICENSE`
  (the Python wrapper is not compiled or included in the APK)
- Use: local 16 kHz mono, 10 ms speech activity decisions. The Android build
  downloads the source archive and verifies its pinned SHA-256 before compiling.

Copyright (c) 2011, 2012, and 2013 The WebRTC project authors. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

  * Redistributions of source code must retain the above copyright notice,
    this list of conditions and the following disclaimer.
  * Redistributions in binary form must reproduce the above copyright notice,
    this list of conditions and the following disclaimer in the documentation
    and/or other materials provided with the distribution.
  * Neither the name of Google nor the names of its contributors may be used
    to endorse or promote products derived from this software without specific
    prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

The source headers additionally refer to the WebRTC patent grant in its
`PATENTS` file; see the upstream [WebRTC PATENTS file](https://webrtc.googlesource.com/src/+/refs/heads/main/PATENTS).
The complete WebRTC notice is also bundled in the APK at
[`android/app/src/main/assets/THIRD_PARTY_NOTICES.txt`](android/app/src/main/assets/THIRD_PARTY_NOTICES.txt).

## Website

The static website uses Three.js (MIT), Lucide (ISC), GSAP (standard no-charge
license), and the Manrope and Instrument Serif fonts (SIL Open Font License
1.1). Its editorial photograph is used under the Unsplash License. Website
asset provenance and upstream license links are listed in
[website/ASSETS.md](website/ASSETS.md).

## Bundled fixed-prompt and guide speech (audio provenance limitation)

The `.ogg` cue and guide audio bundled under
`android/app/src/main/assets/speech/fixed/` and
`android/app/src/main/assets/speech/guide/` are packaged assets. They are not
licensed under this repository's Apache License 2.0, and they should not be
read as already cleared for redistribution or commercial use. This section only
relocates an existing, already-written limitation into the notices a reader
sees after cloning; it does not make any new authorization judgement.

- Point, do not copy: the per-batch source records and verification values are
  kept in `android/app/src/main/assets/speech/manifest.json` (top-level keys
  `sources` and `source_rights`), and the full pipeline is documented in
  `docs/development/offline-speech.md`. The manifest is deliberately referenced
  rather than duplicated here, so a single copy stays authoritative and the
  two records cannot drift apart.

- License limitation, quoted verbatim from `docs/development/offline-speech.md`
  (reproduced exactly, neither softened into "authorized / freely
  redistributable" nor strengthened into "infringing"):
  "Fish 的免费导出成功不证明 APK 分发或商业使用授权，具体范围尚未独立验证。"
  "后续商业使用需换入具有相应授权的素材并重新构建、验证。"
  Whether this batch may be used commercially is not decided in this notice.

- Default voice: when no voice is set, or a stored value is unrecognized,
  playback falls back to the male narration voice. The selectable in-game
  voices are labelled by gender only; the current UI no longer advertises any
  third-party engine voice name as the default, and any legacy engine
  voice-name preference values are retained solely as backward-compatible
  aliases. The current default voice must not be described with those engine
  voice names.

- Scope: this limitation applies to the built-in fixed-prompt cues and the
  complete-guide audio under `speech/fixed/` and `speech/guide/`. Speech
  synthesized from dynamic text is generated at run time by the offline speech
  engine running on the user's own device, which is a different path with
  different terms. The two must not be merged into one statement such as "all
  voice is available offline".
