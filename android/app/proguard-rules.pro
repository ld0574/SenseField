# Native libraries export Java_<package>_<class>_<method> names. Java-only members may shrink.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# AndroidJUnitRunner shares this class with the app's AndroidX dependencies.
# Keep the concrete bridge so a minified Release can be tested without duplicating it.
-keep class androidx.tracing.Trace { *; }

# Stable Kotlin base contracts shared by OkHttp and the separate AndroidX test APK.
# In particular, a test lambda subclass still calls Lambda(int) after app shrinking.
-keep class kotlin.jvm.internal.Lambda { *; }
-keep class kotlin.jvm.internal.Intrinsics { *; }
-keep class kotlin.LazyKt { *; }
-keep class kotlin.LazyKt__LazyJVMKt { *; }
-keep class kotlin.LazyKt__LazyKt { *; }

-keep class androidx.lifecycle.Lifecycle$State { *; }
-keep class androidx.lifecycle.Lifecycle$Event { *; }

# Preserve only the ABI exercised across the separate release-test APK boundary.
# Names may still be obfuscated; unrelated helpers, application code and resources shrink normally.
# Package-private calls from the separate test APK must retain the application's package.
# This preserves access boundaries, not classes or members; shrinking remains enabled.
-keeppackagenames com.openkhub.sensefield
-keep,allowobfuscation class com.openkhub.sensefield.CuePlayer {
    <init>(android.content.Context);
    <init>(android.content.Context, boolean);
    static *** tonePreview(android.content.Context);
    static *** speechProbe(android.content.Context);
    void setAudioAuditListener(java.util.function.Consumer);
    boolean speechPreparationFinished();
    boolean speechReady();
    public boolean speak(...);
    public boolean playTone(...);
    public void stopSpeech();
    public void cancelPendingTone();
    void close();
}
# Terminal-callback injection verifies recovery in the minified app without
# synthesizing audio or changing phone preferences. Retain only this boundary.
-keepclassmembers class com.openkhub.sensefield.CuePlayer {
    private android.speech.tts.TextToSpeech tts;
    private volatile boolean ttsReady;
    private volatile boolean offlineTtsReady;
    private int activeVoiceEngineIndex;
    private java.util.List voiceEngines;
    private final java.util.Map speechCallbacks;
    private final java.util.Map speechRequests;
    private void failDynamicSpeech(java.lang.String,android.speech.tts.TextToSpeech,java.lang.String);
    private void timeoutDynamicSpeech(java.lang.String,com.openkhub.sensefield.CueDispatcher$PlaybackCallback,android.speech.tts.TextToSpeech,java.lang.String);
}
-keep,allowobfuscation class com.openkhub.sensefield.CueRequest { <init>(...); }
-keep enum com.openkhub.sensefield.CueRequest$Category { *; }
-keep,allowobfuscation interface com.openkhub.sensefield.CueDispatcher$PlaybackCallback { *; }
-keep,allowobfuscation class com.openkhub.sensefield.BundledSpeechCatalog {
    static java.lang.String[] VOICES;
    static java.util.List guide();
}
-keep,allowobfuscation class com.openkhub.sensefield.BundledSpeechAssets {
    <init>(android.content.Context, java.lang.String, int);
    void loadIndex();
    short[] load(java.lang.String, boolean);
}

# JNI wrappers and immutable configuration used by the native runtime smoke tests.
-keepclassmembers class com.openkhub.sensefield.NativeBridge {
    static native void nativeSetDetectorCache(long, boolean);
}
-keepclassmembers class com.openkhub.sensefield.NativeDiagnosticPixels { static <methods>; }
-keepclassmembers class com.openkhub.sensefield.VoiceSpeechDetector { <init>(...); <methods>; }
-keepclassmembers class com.openkhub.sensefield.OnDeviceAsr { <init>(...); <methods>; }
-keep,allowobfuscation interface com.openkhub.sensefield.OnDeviceAsr$Listener { *; }
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.AssistantController {
    static boolean isRequest(java.lang.String);
}
-keep,allowobfuscation class com.openkhub.sensefield.AsrModelStore$Spec {
    <fields>;
    static *** read(android.content.Context);
}
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.AsrModelStore {
    static boolean verified(java.io.File, long, java.lang.String);
}
-keep,allowobfuscation class com.openkhub.sensefield.GameProfile {
    <fields>;
    static *** load(android.content.Context);
    static android.content.SharedPreferences settings(android.content.Context);
}
-keep,allowobfuscation class com.openkhub.sensefield.GameProfile$TemplateData { <fields>; }
-keep,allowobfuscation class com.openkhub.sensefield.GameProfile$PlayerLifeData { <fields>; }
-keep,allowobfuscation class com.openkhub.sensefield.GameProfile$RelationData { <fields>; }

# Background-image accounting is checked across the release-test APK boundary.
# Keep only the entry points/fields that test uses; recorder helpers still shrink.
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.CaptureService {
    static boolean isRunning();
}
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3LiveService {
    static boolean isRunning();
}
# Fault-photo checks cross the separate Release test APK boundary. Retain only
# these typed observation/drawing contracts; the rest of Match3 still shrinks.
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3Sampler {
    <init>(android.content.Context, com.openkhub.sensefield.BoardGeometry);
    static com.openkhub.sensefield.BoardGeometry autoDetectGeometry(android.graphics.Bitmap);
    static boolean boardBackground(int);
    static boolean isMovable(char);
    com.openkhub.sensefield.Match3Position samplePosition(android.graphics.Bitmap);
    com.openkhub.sensefield.Match3Position samplePosition(android.graphics.Bitmap,long);
    static char[][] sample(android.graphics.Bitmap,com.openkhub.sensefield.BoardGeometry,java.util.List);
    int[] elementPatch(int,int);
    int[] elementEnvelope(int,int);
    int directAnimals; int inferredAnimals; int uncertainAnimals;
    public void close();
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.BoardGeometry {
    <init>(int,int,int,int,int,int,int,int);
    int frameWidth; int frameHeight; int left; int top; int right; int bottom; int rows; int cols;
    boolean sameGrid(com.openkhub.sensefield.BoardGeometry);
    float cellLeft(int); float cellTop(int);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3Position {
    com.openkhub.sensefield.Match3Position$Cell cell(int,int);
    char[][] matrix();
    boolean sameCells(com.openkhub.sensefield.Match3Position);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3Position$Cell {
    com.openkhub.sensefield.Match3Position$Kind kind;
    char color; boolean swappable;
    int iceLayers;
    com.openkhub.sensefield.Match3Position$SwapPermission swapPermission;
    char code();
}
-keep enum com.openkhub.sensefield.Match3Position$Kind { *; }
-keep enum com.openkhub.sensefield.Match3Position$SwapPermission { *; }
# These enums are compared by the separate rule/state tests. Enum unboxing is
# valid inside the app, but cannot cross the independently compiled test APK.
-keep enum com.openkhub.sensefield.Match3Position$Cover { *; }
-keep enum com.openkhub.sensefield.Match3Position$Effect { *; }
-keep enum com.openkhub.sensefield.Match3Position$Gate { *; }
-keep enum com.openkhub.sensefield.Match3Goals$Kind { *; }
-keep enum com.openkhub.sensefield.Match3Goals$Mode { *; }
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3Position$Cell {
    com.openkhub.sensefield.Match3Position$Cover cover;
    int coverLayers;
    com.openkhub.sensefield.Match3Position$SwapPermission matchPermission;
    java.lang.String coverRuleId;
    int layers; int objectId;
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3Goals {
    boolean hudVerified; int steps; java.util.List targets;
    int remaining(com.openkhub.sensefield.Match3Goals$Kind);
    boolean fullyKnown();
    static com.openkhub.sensefield.Match3Goals unknown(long);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3Goals$Target {
    com.openkhub.sensefield.Match3Goals$Kind kind; int remaining;
}
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3Goals$Target {
    static com.openkhub.sensefield.Match3Goals$Target observed(int,com.openkhub.sensefield.Match3Goals$Kind,int,boolean,java.lang.String);
    com.openkhub.sensefield.Match3Goals$Mode mode;
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3HudReader {
    <init>(android.content.Context);
    com.openkhub.sensefield.Match3Goals read(android.graphics.Bitmap,com.openkhub.sensefield.BoardGeometry,long);
    java.lang.String status();
    int[][] diagnosticGoalPatches();
    java.lang.String[] diagnosticGoalKinds();
    float[] diagnosticGoalAspects();
    public void close();
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3Hint {
    <init>(java.lang.String,long,long,com.openkhub.sensefield.BoardGeometry,com.openkhub.sensefield.Match3Board$Swap);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3Board$Swap {
    <init>(int,int,int,int,int);
}
# The frozen legacy ranker in the paired benchmark consumes the shared board
# API from its own APK. Preserve the returned data and exact entry descriptors.
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3Board {
    static java.util.List findSwaps(com.openkhub.sensefield.Match3Position);
}
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3Board$Swap {
    int fromRow; int fromCol; int toRow; int toCol;
    int runsFormed; int matchedCells; int longestRun; int adjacentIce;
    java.util.BitSet matchedPositions();
    java.util.List formedRuns();
}
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3Board$Run {
    int row; int col; boolean horizontal; int length;
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3HintOverlay$HintView {
    <init>(android.content.Context);
    com.openkhub.sensefield.Match3Hint hint;
    void drawHint(android.graphics.Canvas);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3OverlayCaptureFilter {
    <init>(android.content.Context);
    boolean clean(android.graphics.Bitmap,com.openkhub.sensefield.Match3Hint,float);
}
# Element evidence and mathematical comparisons used by the separate minified
# test APK. Preserve only these contracts, including the copied test sprites.
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3AnimalAppearance$Body {
    <init>(int[]);
    int[] copyPatch();
    float difference(com.openkhub.sensefield.Match3AnimalAppearance$Body);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3AnimalAppearance$Face {
    <init>(char,int[]);
    boolean detailed;
    float difference(com.openkhub.sensefield.Match3AnimalAppearance$Face);
}
-keepclassmembers,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3AnimalAppearance {
    static char recognize(com.openkhub.sensefield.Match3AnimalAppearance$Face,java.util.List,int);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3VisualCatalog {
    java.util.List animals; java.util.List animalFaces; java.util.List animalBodies; java.util.List bodyColors;
    static com.openkhub.sensefield.Match3VisualCatalog get(android.content.Context);
    com.openkhub.sensefield.Match3Position$Cell animal(com.openkhub.sensefield.Match3VisualCatalog$CellCache);
    com.openkhub.sensefield.Match3AnimalAppearance$Reference trustedReference(com.openkhub.sensefield.Match3VisualCatalog$CellCache);
    static java.lang.String recognize(java.util.List,int[],float,float);
    static int[] patch(int[],int,int,int,int,int);
}
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3VisualCatalog {
    static com.openkhub.sensefield.Match3Goals$Target goalTarget(java.util.List,int[],com.openkhub.sensefield.Match3Goals$Target);
    java.lang.String coverCatalogId; java.lang.String coverCatalogSha256; java.lang.String coverCatalogStatus;
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3VisualCatalog$CellCache {
    <init>(); int[] patch; int[] envelope;
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3VisualCatalog$Pattern {
    java.lang.String kind; int[] pixels;
}
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3VisualCatalog$Pattern {
    float difference(int[]);
    float difference(int[],float);
    boolean[] coverEvidence;
}

-keep class com.openkhub.sensefield.Match3NativeMath { static boolean AVAILABLE; native <methods>; }
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3AnimalAppearance$Body {
    float differenceJava(com.openkhub.sensefield.Match3AnimalAppearance$Body);
}
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3AnimalAppearance$Face {
    float differenceJava(com.openkhub.sensefield.Match3AnimalAppearance$Face);
}
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3Sampler {
    static com.openkhub.sensefield.BoardGeometry verifiedGeometry(android.graphics.Bitmap,int[]);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3CellConfirmation {
    <init>();
    com.openkhub.sensefield.Match3CellConfirmation$Snapshot accept(com.openkhub.sensefield.Match3Position,long);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3CellConfirmation$Snapshot {
    com.openkhub.sensefield.Match3Position position;
}
-keepclassmembers,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3MoveRanker {
    static java.util.List rankedMoves(com.openkhub.sensefield.Match3Position,com.openkhub.sensefield.Match3Goals);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3MoveValue {
    com.openkhub.sensefield.Match3Board$Swap swap;
    int directUnits; int relevantHits; java.lang.String reason;
    int collected(com.openkhub.sensefield.Match3Goals$Kind);
}
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3MoveValue {
    int hit(com.openkhub.sensefield.Match3Goals$Kind);
}
# The fixed-input paired benchmark shares these observation/harvest entry
# points with the app. Retain their exact descriptors, not the whole engine.
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3Sampler {
    <init>(android.content.Context,com.openkhub.sensefield.BoardGeometry,com.openkhub.sensefield.Match3VisualCatalog);
    com.openkhub.sensefield.Match3UnknownElements$Observation[][] reviewElements(com.openkhub.sensefield.Match3Position);
}
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3HudReader {
    <init>(com.openkhub.sensefield.Match3VisualCatalog);
    void clear();
}
-keep,allowobfuscation class com.openkhub.sensefield.Match3FlywheelHarvest {
    <init>();
    void collect(com.openkhub.sensefield.Match3Sampler,com.openkhub.sensefield.Match3Position,com.openkhub.sensefield.Match3HudReader,long,java.util.function.Predicate);
    void resetStability();
}
-keepclassmembers,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3HintValidity {
    static boolean valid(com.openkhub.sensefield.Match3Position,com.openkhub.sensefield.Match3Position,com.openkhub.sensefield.Match3MoveValue,java.util.List);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3UnknownElements {
    <init>();
    java.util.List observe(com.openkhub.sensefield.Match3UnknownElements$Observation[][],long);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3UnknownElements$Observation {
    <init>(com.openkhub.sensefield.Match3Position$Cell,int[]);
}
-keepclassmembers,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3UnknownElements$Sample {
    java.lang.String filename();
}
-keep,allowobfuscation class com.openkhub.sensefield.DiagnosticRecorder {
    static com.openkhub.sensefield.DiagnosticRecorder current;
    static java.util.concurrent.ExecutorService IO;
    java.io.File directory;
    java.lang.String failure;
    static *** start(android.content.Context, java.lang.String, long);
    static *** start(android.content.Context, java.lang.String, long, boolean);
    static *** start(android.content.Context, java.lang.String, long, com.openkhub.sensefield.DiagnosticGame);
    void setImagesEnabled(boolean);
    boolean harvestEnabled();
    boolean flywheelSample(org.json.JSONObject);
    void elementSample(android.graphics.Bitmap,com.openkhub.sensefield.BoardGeometry,com.openkhub.sensefield.Match3UnknownElements$Sample);
    static java.io.File root(android.content.Context);
    static org.json.JSONObject object(java.lang.Object[]);
    void frame(com.openkhub.sensefield.NativeFrameResult, com.openkhub.sensefield.DiagnosticSnapshot, java.nio.ByteBuffer, int, int, int, long, long, long);
    long[] imageWorkStats();
    void audit(java.lang.String);
    void match3HintContext(long,boolean);
    void markIssue();
    void finish(java.lang.String);
}
# Mixed-game archive tests cross the separate test-APK boundary. Preserve these
# small contracts, including the enum's identity (R8 enum unboxing is not an ABI).
-keep enum com.openkhub.sensefield.DiagnosticGame { *; }
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.DiagnosticsActivity {
    static android.content.Intent intent(android.content.Context, com.openkhub.sensefield.DiagnosticGame);
}
-keep,allowobfuscation class com.openkhub.sensefield.DiagnosticArchive {
    static java.io.File[] sessions(java.io.File);
    static void writeJson(java.io.File, java.lang.String, java.lang.String);
    static void delete(java.io.File);
    static void export(java.io.File,java.io.File,java.lang.String);
}
-keep,allowobfuscation class com.openkhub.sensefield.NativeFrameResult {
    static *** empty();
    static *** parse(int[],long);
    int locatorState; int locatorScoreMilli;
    android.graphics.RectF minimapRoi;
    java.util.List entities;
    com.openkhub.sensefield.NativeFrameResult$Relation relation;
}
# The sampled detector replay uses the same packet parser as the live service.
# Retain only this cross-test-APK observation contract; parsing helpers shrink.
-keep,allowobfuscation class com.openkhub.sensefield.NativeFrameResult$Relation {
    int state; int event; boolean reliable;
}
-keep,allowobfuscation class com.openkhub.sensefield.DiagnosticSnapshot {
    static *** parse(long[]);
    long engineAtMs;
    long[][] observations;
}
# Activities/services and FileProvider entry points are retained by the manifest/default rules.
# No whole-application keep rule: behavior tests must also exercise the minified artifact.

# Frozen development replay and vine fixtures cross the separate test APK.
# Preserve only their observation/export contracts; rule evaluation internals
# and the event/search implementations remain shrinkable.
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3RuleDiagnostics {
    static org.json.JSONObject action(com.openkhub.sensefield.Match3MoveValue);
}
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3Action {
    static java.util.List enumerate(com.openkhub.sensefield.Match3Position);
    static java.util.Set omittedReasons(com.openkhub.sensefield.Match3Position);
    com.openkhub.sensefield.Match3Board$Swap geometry;
    com.openkhub.sensefield.Match3Action$Type type;
}
-keep enum com.openkhub.sensefield.Match3Action$Type { *; }
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3Goals {
    com.openkhub.sensefield.Match3Goals$Mode mode(com.openkhub.sensefield.Match3Goals$Kind);
    boolean hasTarget(com.openkhub.sensefield.Match3Goals$Kind);
    java.lang.String key();
}
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3Goals$Target {
    int slot; boolean completed; java.lang.String modeEvidence;
}
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3Position$Cell {
    int coverLayers; int absentTurns; int charge; int capacity; int direction; int ruleVersion;
    com.openkhub.sensefield.Match3Position$SwapPermission activationPermission;
    com.openkhub.sensefield.Match3Position$SwapPermission adjacentDamagePermission;
    com.openkhub.sensefield.Match3Position$SwapPermission specialDamagePermission;
    com.openkhub.sensefield.Match3Position$Effect effect;
    com.openkhub.sensefield.Match3Position$Gate gate;
    boolean timedGate; java.lang.String ruleId; java.lang.String floorRuleId;
}
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3VisualCatalog {
    java.lang.String id; java.lang.String galleryId; java.lang.String gallerySha256; java.lang.String galleryStatus;
    int galleryGoalsPendingReview;
    java.lang.String hudGlyphCatalogId; java.lang.String hudGlyphCatalogSha256; java.lang.String hudGlyphCatalogStatus;
    static com.openkhub.sensefield.Match3VisualCatalog withGallery(org.json.JSONObject,byte[]);
    static com.openkhub.sensefield.Match3VisualCatalog withReviewedSupplement(org.json.JSONObject,byte[],byte[],java.lang.String);
    static com.openkhub.sensefield.Match3VisualCatalog withReviewedSupplements(org.json.JSONObject,byte[],java.util.List,java.lang.String);
    static com.openkhub.sensefield.Match3VisualCatalog withHudGlyphSupplement(com.openkhub.sensefield.Match3VisualCatalog,byte[],java.lang.String);
    com.openkhub.sensefield.Match3Position$Cell classifyObstacle(int[]);
    java.lang.String boardFeedbackId; java.lang.String boardFeedbackSha256; java.lang.String boardFeedbackStatus;
    java.util.List effectEnvelopes;
}
