# Compare retained code in original APKs

This opt-in producer calls classes in the installed `com.openkhub.sensefield` APK through cached reflection handles. It contains no copies of application classes and does not rebuild either production APK. Its package replaces the standard private `.test` APK for a run; the runner restores and reads back the candidate and standard test APK even after failure. It only runs on a verified isolated emulator and uses cover installation without clearing settings.

`tools/apk_contract.py` first checks the mapping's R8 fingerprint against the APK's DEX marker. It binds mapped signatures to actual declared DEX definitions, removes duplicate line/inline entries, rejects ambiguous aliases, and includes unchanged kept members that R8 omits from the map. The producer chooses its packaged contract from the installed APK SHA and resolves classes, methods, constructors and fields before timing.

Build from the repository root, using separately frozen private contracts:

```sh
python tools/apk_contract.py --apk /absolute/public.apk --mapping /absolute/public-mapping.txt --output /private/assets/contracts/public.json
python tools/apk_contract.py --apk /absolute/candidate.apk --mapping /absolute/candidate-mapping.txt --output /private/assets/contracts/candidate.json
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME='/absolute/android-sdk' android/gradlew -p tools/android-apk-comparison -PcontractAssets=/private/assets assembleDebug
```

Freeze the producer bytes, verify the certificate against both target APKs, then use `scripts/compare_original_apks.py`. Every run needs `--baseline`, `--candidate`, `--producer`, `--restore-test`, `--adb`, frozen `--inputs` and a new `--output` directory. Paths are explicit; private images, mappings and APKs stay ignored. No work runs merely by building this project.

The `--phase reference` operation with `--mode quality` exports independent-frame geometry, complete cells and permissions, HUD goals and all ranked actions. Sequence reference runs use `--mode sequence` or `--mode honor`. Review functional differences and freeze both reports before formal costs. `--phase cost` requires `--baseline-reference` and `--candidate-reference`, then checks every measured export against its own reference with APK, contract, producer and input hashes. A reviewed visual improvement may change the versions' references; unexpected output changes still fail. The existing benchmark's requirement for identical sequence outputs is unchanged.

The two cost workloads are deliberately distinct from earlier benchmarks:

- `public-perception-sequence-v1`: actual retained geometry detection, timestamped HUD and sampler calls, and move ranking. It uses the original `sequence-v1` manifest's times, gaps and reset boundaries, with immediate geometry acquisition and ranking of each observed board. It does not emulate the live service's confirmation/admission or hint lifecycle. The public APK optimized some of those methods away, so they cannot be claimed as directly compared through this API.
- `public-honor-sequence-v1`: the loaded production profile, retained JNI processing and work counters, and retained diagnostic/packet parsers. Full raw observations, packets, tracking, locator and relation/event outputs are exported. Only measured cost and freshness proven from recorded engine/last-seen clocks are normalized by the existing `honor_semantics()` comparison.

Both costs use six warmups, 15 measured batches and one call per frame, in A/B, B/A, A/B order. Every pair must keep wall and worker P95 increases at or below 10%; all samples, logs, failures and process CPU remain recorded. This functional comparison does not require a new CPU improvement. Run without simultaneous builds or other benchmarks and retain actual ART compilation state.

These workloads exclude capture, bitmap decoding, UI, sound, recorder writes and physical game heat. The figures do not establish independent release accuracy, player acceptance or completion of a level. A successful reference run means the producer executed; quality review and formal cost gates are separate.
