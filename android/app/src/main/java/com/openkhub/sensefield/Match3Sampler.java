package com.openkhub.sensefield;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 消消乐识别采样器：Bitmap＋标定 → 颜色矩阵；含特殊棋子模板匹配。
 *  Activity（截图式）与 Match3LiveService（实时式）共用，保证两条链路行为一致。 */
final class Match3Sampler implements AutoCloseable {
    private int[][] largeObjectPatches;
    private boolean[] largeObjectMatches;
    static final char UNKNOWN = '.';
    /** 确认的空格（格心与四角同色）。与 UNKNOWN 分开，否则「没棋子」和「认不出」在播报里同一个词。 */
    static final char EMPTY_CELL = ' ';

    /** 确认的布局洞/下落空位，不是未识别。 */
    static final char GAP_CELL = 'H';   // 空位：露出棋盘底的格（下落中/布局洞），非棋子
    /** Coloured surface without an individual animal's board-backed footprint. */
    static final char NON_SWAP_CELL = '#';
    static final char COIN_CELL = 'C', EGG_CELL = 'E';

    static boolean isMovable(char c) {
        return c == 'R' || c == 'O' || c == 'Y' || c == 'G' || c == 'B' || c == 'P';
    }

    static boolean isTemplateCode(char c) {
        return c >= '1' && c <= '9' || c >= 'a' && c <= 'z';
    }

    static boolean isUnknown(char c) {
        return !isMovable(c) && !isTemplateCode(c) && c != EMPTY_CELL && c != 'I'
                && c != GAP_CELL && c != NON_SWAP_CELL && c != COIN_CELL && c != EGG_CELL;
    }

    /** Legacy non-piece predicate. Readability must use isUnknown, not this method. */
    static boolean isUnreadable(char c) {
        return !isMovable(c);
    }

    /** 特殊棋子模板：一张 32×32 裁剪图＋名字。 */
    static final class SpecialTemplate {
        final String name;
        final Bitmap thumb;
        final int[] comparisonPixels = new int[16 * 16];
        char code;

        SpecialTemplate(String name, Bitmap thumb) {
            this.name = name;
            this.thumb = thumb;
            code = nameToLetter(name);
            Bitmap small = Bitmap.createScaledBitmap(thumb, 16, 16, true);
            try { small.getPixels(comparisonPixels, 0, 16, 0, 0, 16, 16); }
            finally { if (small != thumb) small.recycle(); }
        }
    }

    /* ---------- 实例封装：Activity/Service 持有一个实例，模板只加载一次 ---------- */

    private final android.content.Context context;
    private final int rows;
    private final int cols;
    private final int lPct;
    private final int tPct;
    private final int rPct;
    private final int bPct;
    private final List<SpecialTemplate> templates;
    private final BoardGeometry geometry;
    private boolean closed;
    private Match3VisualCatalog observations;
    private int[] observationPixels;
    private Match3VisualCatalog.CellCache[][] observationCache;
    private final Match3AppearanceMotion motion=new Match3AppearanceMotion();
    private boolean frameMotion;
    private List<Match3AnimalAppearance.Reference> lastTrusted=Collections.emptyList();
    private long trustedRevision;
    int directAnimals, inferredAnimals, uncertainAnimals, unfamiliarCells;

    Match3Sampler(android.content.Context context, int rows, int cols,
                  int lPct, int tPct, int rPct, int bPct) {
        this.context = context;
        this.rows = rows;
        this.cols = cols;
        this.lPct = lPct;
        this.tPct = tPct;
        this.rPct = rPct;
        this.bPct = bPct;
        this.templates = loadTemplates(context);
        this.geometry = null;
    }

    Match3Sampler(android.content.Context context, BoardGeometry geometry) {
        this.context = context;
        this.geometry = geometry;
        rows = geometry.rows; cols = geometry.cols;
        int[] pct = geometry.percentages();
        lPct = pct[0]; tPct = pct[1]; rPct = pct[2]; bPct = pct[3];
        templates = loadTemplates(context);
    }

    private BoardGeometry geometryFor(Bitmap frame) {
        if (geometry != null) {
            if (geometry.frameWidth != frame.getWidth() || geometry.frameHeight != frame.getHeight())
                throw new IllegalArgumentException("Capture size changed");
            return geometry;
        }
        return BoardGeometry.fromPercent(frame.getWidth(), frame.getHeight(), rows, cols,
                new int[]{lPct, tPct, rPct, bPct});
    }

    /** 按实例标定采样整盘（含特殊棋子模板匹配）。 */
    char[][] sample(Bitmap bitmap) {
        requireOpen();
        return sample(bitmap, geometryFor(bitmap), templates);
    }

    /** Automatic task reasoning uses observed pixels, never the names of player-learned templates. */
    Match3Position samplePosition(Bitmap bitmap) {
        return samplePosition(bitmap,android.os.SystemClock.elapsedRealtime());
    }
    boolean frameMotion() { return frameMotion; }
    Match3Position samplePosition(Bitmap bitmap,long at) {
        requireOpen(); BoardGeometry g=geometryFor(bitmap);
        if(observations==null)observations=Match3VisualCatalog.get(context);
        int capacity=(g.cellWidth()+2)*(g.cellHeight()+2);
        if(observationPixels==null || observationPixels.length<capacity)observationPixels=new int[capacity];
        if(observationCache==null) {
            observationCache=new Match3VisualCatalog.CellCache[g.rows][g.cols];
            for(int r=0;r<g.rows;r++)for(int c=0;c<g.cols;c++)observationCache[r][c]=new Match3VisualCatalog.CellCache();
        }
        Match3Position.Cell[][] cells=new Match3Position.Cell[g.rows][g.cols];
        List<Match3AnimalAppearance.Reference> trusted=new ArrayList<>();
        boolean[][] comparable=new boolean[g.rows][g.cols];
        directAnimals=inferredAnimals=uncertainAnimals=unfamiliarCells=0;
        int w=g.cellWidth(),h=g.cellHeight(),half=Math.max(3,Math.min(w,h)/8);
        for(int r=0;r<g.rows;r++)for(int c=0;c<g.cols;c++) {
            Match3Position.Cell known=observations.obstacle(bitmap,g,r,c,observationPixels,observationCache[r][c]);
            // Bright sky in a rectangular envelope is not a white task block.
            // Require current board-edge support, using the existing dark/cold board domain.
            if(known!=null && known.kind==Match3Position.Kind.SNOW && !boardEdgeSupported(bitmap,g,r,c))
                known=Match3Position.Cell.obstacle(Match3Position.Kind.SURFACE,-1);
            // Known object identity already takes precedence over hue. Avoid
            // re-reading those same pixels through an unused colour classifier.
            if(known!=null) { cells[r][c]=known;continue; }
            // Hue and board corners can detect vacancies; neither grants animal identity or permission.
            known=observations.animal(observationCache[r][c]);
            if(known!=null)cells[r][c]=known;
            else {
                int left=(int)g.cellLeft(c),top=(int)g.cellTop(r);
                int width=(int)g.cellLeft(c+1)-left,height=(int)g.cellTop(r+1)-top;
                // Known animal identity already wins over the unused hue path.
                // For remaining cells reuse the exact pixels read by obstacle().
                CellAppearance footprint=observations.available?bufferedAppearance(bitmap,observationPixels,width,height,
                        g.centerX(c)-left,g.centerY(r)-top,w,h,half,g.centerX(c),g.centerY(r))
                        :cellAppearance(bitmap,g.centerX(c),g.centerY(r),w,h);
                char legacy=classifyAppearance(bitmap,g.centerX(c),g.centerY(r),half,footprint);
                cells[r][c]=legacy==EMPTY_CELL || legacy==GAP_CELL
                        ?Match3Position.Cell.obstacle(Match3Position.Kind.EMPTY,0)
                        :Match3Position.Cell.obstacle(legacy==UNKNOWN?Match3Position.Kind.UNKNOWN:Match3Position.Kind.SURFACE,-1);
            }
            comparable[r][c]=known==null && cells[r][c].kind!=Match3Position.Kind.EMPTY
                    && observations.iceLayers(observationCache[r][c])!=1;
            if(cells[r][c].kind==Match3Position.Kind.ANIMAL && cells[r][c].swappable
                    && observationCache[r][c].patch!=null) {
                Match3AnimalAppearance.Reference reference=observations.trustedReference(observationCache[r][c]);
                if(reference!=null)trusted.add(reference);
                directAnimals++;
            }
        }
        recognizeLargeObjects(g,cells,comparable);
        // Freeze direct-catalog + plain-sprite evidence before the fallback pass.
        // Inferred animals and uncertain covers cannot seed or confirm another candidate.
        if(!sameReferences(lastTrusted,trusted)) { lastTrusted=trusted;trustedRevision++; }
        for(int r=0;r<g.rows;r++)for(int c=0;c<g.cols;c++)
            if(comparable[r][c] && observationCache[r][c].patch!=null) {
                Match3VisualCatalog.CellCache cache=observationCache[r][c];
                // Both candidate patches were read from this frame and checked
                // exactly by obstacle(). Frozen references are compared by
                // descriptor identity, so any source change invalidates fallback.
                if(!cache.inferredChecked || cache.inferredRevision!=trustedRevision) {
                    cache.inferred=Match3AnimalAppearance.infer(Match3VisualCatalog.face(cache),
                            Match3VisualCatalog.body(cache),trusted);
                    cache.inferredChecked=true;cache.inferredRevision=trustedRevision;
                }
                Match3Position.Cell inferred=cache.inferred;
                if(inferred!=null) {
                    cells[r][c]=inferred;if(inferred.swappable)inferredAnimals++;
                }
            }
        Match3AnimalAppearance.Face[][] faces=new Match3AnimalAppearance.Face[g.rows][g.cols];
        for(int r=0;r<g.rows;r++)for(int c=0;c<g.cols;c++) {
            if(observations.iceLayers(observationCache[r][c])==1 && cells[r][c].iceLayers!=1)
                cells[r][c]=cells[r][c].withIce(1);
            if(observationCache[r][c].patch!=null)faces[r][c]=Match3VisualCatalog.face(observationCache[r][c]);
            if(cells[r][c].kind==Match3Position.Kind.ANIMAL && !cells[r][c].swappable)uncertainAnimals++;
            else if(cells[r][c].kind==Match3Position.Kind.UNKNOWN || cells[r][c].kind==Match3Position.Kind.SURFACE)unfamiliarCells++;
        }
        frameMotion=motion.accept(faces,at);
        return new Match3Position(cells);
    }
    private void recognizeLargeObjects(BoardGeometry g,Match3Position.Cell[][] cells,boolean[][] comparable) {
        if(observations.largeObjects.isEmpty())return;
        if(largeObjectPatches==null) {
            largeObjectPatches=new int[g.rows*g.cols][];largeObjectMatches=new boolean[g.rows*g.cols];
        }
        for(int r=0;r+1<g.rows;r++)for(int c=0;c+1<g.cols;c++) {
            boolean eligible=true;
            for(int rr=r;rr<=r+1;rr++)for(int cc=c;cc<=c+1;cc++) {
                Match3Position.Kind kind=cells[rr][cc].kind;
                if(kind!=Match3Position.Kind.UNKNOWN && kind!=Match3Position.Kind.SURFACE)eligible=false;
            }
            if(!eligible)continue;
            int anchor=r*g.cols+c;
            // Reuse this frame's complete per-cell envelopes. No new capture,
            // native pixel reads, learned prototype or partial-face permission.
            int[] patch=new int[256];
            for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
                int[] envelope=observationCache[r+y/8][c+x/8].envelope;
                patch[y*16+x]=envelope[(2*(y%8)+1)*16+2*(x%8)+1];
            }
            if(!java.util.Arrays.equals(largeObjectPatches[anchor],patch)) {
                largeObjectPatches[anchor]=patch;
                largeObjectMatches[anchor]="cookie".equals(Match3VisualCatalog.recognize(observations.largeObjects,patch,.12f,.025f));
            }
            if(!largeObjectMatches[anchor])continue;
            Match3Position.Cell object=Match3Position.Cell.cookie(anchor);
            for(int rr=r;rr<=r+1;rr++)for(int cc=c;cc<=c+1;cc++) {
                cells[rr][cc]=object;comparable[rr][cc]=false;
            }
        }
    }
    private static boolean sameReferences(List<Match3AnimalAppearance.Reference> a,
                                          List<Match3AnimalAppearance.Reference> b) {
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(a.get(i).face!=b.get(i).face || a.get(i).body!=b.get(i).body)return false;
        return true;
    }
    int[] elementPatch(int row,int col) { return observationCache[row][col].patch; }
    int[] elementEnvelope(int row,int col) { return observationCache[row][col].envelope; }
    Match3UnknownElements.Observation[][] reviewElements(Match3Position position) {
        Match3UnknownElements.Observation[][] out=new Match3UnknownElements.Observation[rows][cols];
        for(int r=0;r<rows;r++)for(int c=0;c<cols;c++)if(Match3UnknownElements.needsReview(position.cell(r,c))
                && observationCache[r][c].patch!=null)
            out[r][c]=new Match3UnknownElements.Observation(position.cell(r,c),observationCache[r][c].envelope);
        return out;
    }

    private static char classifyBufferedCell(Bitmap bitmap,int[] pixels,int width,int height,
                                            int x,int y,int cellW,int cellH,int half,int frameX,int frameY) {
        return classifyAppearance(bitmap,frameX,frameY,half,bufferedAppearance(bitmap,pixels,width,height,
                x,y,cellW,cellH,half,frameX,frameY));
    }
    private static char classifyAppearance(Bitmap bitmap,int x,int y,int half,CellAppearance appearance) {
        if(appearance.distance<=EMPTY_COLOR_DISTANCE)return EMPTY_CELL;
        char piece=classifyCell(bitmap,x,y,half,Collections.emptyList(),appearance.centre);
        return isMovable(piece) && appearance.backed<3?NON_SWAP_CELL:piece;
    }
    private static CellAppearance bufferedAppearance(Bitmap bitmap,int[] pixels,int width,int height,
                                            int x,int y,int cellW,int cellH,int half,int frameX,int frameY) {
        int ox=cellW*42/100,oy=cellH*42/100,probe=Math.max(2,Math.min(cellW,cellH)/16);
        // Tiny/manual cells may cross a tile edge; preserve the original full-frame sampling there.
        if(x-ox-probe<0 || y-oy-probe<0 || x+ox+probe>=width || y+oy+probe>=height)
            return cellAppearance(bitmap,frameX,frameY,cellW,cellH);
        int centre=avgPixels(pixels,width,x,y,half);
        int[][] points={{x-ox,y-oy},{x+ox,y-oy},{x-ox,y+oy},{x+ox,y+oy}};
        long distance=0;int backed=0;float[] hsv=new float[3];
        for(int[] point:points) {
            int color=avgPixels(pixels,width,point[0],point[1],probe);
            distance+=Math.abs(Color.red(centre)-Color.red(color))+Math.abs(Color.green(centre)-Color.green(color))
                    +Math.abs(Color.blue(centre)-Color.blue(color));
            Color.colorToHSV(color,hsv);
            if(hsv[2]<.5f && hsv[0]>=170f && hsv[0]<=300f)backed++;
        }
        return new CellAppearance(centre,(int)(distance/(4*3L)),backed);
    }
    private static int avgPixels(int[] pixels,int width,int x,int y,int half) {
        long r=0,g=0,b=0,n=0;
        for(int yy=y-half;yy<=y+half;yy++)for(int xx=x-half;xx<=x+half;xx++) {
            int color=pixels[yy*width+xx];r+=color>>16&255;g+=color>>8&255;b+=color&255;n++;
        }
        return Color.rgb((int)(r/n),(int)(g/n),(int)(b/n));
    }

    private static boolean boardEdgeSupported(Bitmap frame,BoardGeometry g,int row,int col) {
        int l=(int)g.cellLeft(col),r=(int)g.cellLeft(col+1)-1;
        int t=(int)g.cellTop(row),b=(int)g.cellTop(row+1)-1;
        int x=g.centerX(col),y=g.centerY(row);float[] hsv=new float[3];
        int[][] points={{l+1,y},{r-1,y},{x,t+1},{x,b-1}};int sides=0;
        for(int[] point:points) {
            boolean supported=false;
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++) {
                int xx=point[0]+dx,yy=point[1]+dy;
                if(xx<=l || yy<=t || xx>=r || yy>=b)continue;
                Color.colorToHSV(frame.getPixel(xx,yy),hsv);
                if(hsv[2]<.5f && hsv[0]>=170f && hsv[0]<=300f)supported=true;
            }
            if(supported && ++sides>=2)return true;
        }
        return false;
    }

    /** 按标定采样整个棋盘。templates 可为 null/空。 */
    /** 单点报点：对截图中任意一点做颜色分类（预览点击报点用）。 */
    static char classifyPoint(Bitmap bitmap, int cx, int cy) {
        int half = Math.max(6, bitmap.getWidth() / 130);
        return classifyCell(bitmap, cx, cy, half, null);
    }

    /** 横向均匀取 n 个槽位的颜色（道具栏播报用）。 */
    static char[] classifyStrip(Bitmap bitmap, int yPctFrom, int yPctTo, int slots) {
        char[] out = new char[slots];
        int y = bitmap.getHeight() * (yPctFrom + yPctTo) / 200;
        int slotW = bitmap.getWidth() / slots;
        for (int i = 0; i < slots; i++) {
            out[i] = classifyCell(bitmap, slotW * i + slotW / 2, y, slotW / 6, null);
        }
        return out;
    }

    static char[][] sample(Bitmap bitmap, int rows, int cols,
                           int lPct, int tPct, int rPct, int bPct,
                           List<SpecialTemplate> templates) {
        return sample(bitmap, BoardGeometry.fromPercent(bitmap.getWidth(), bitmap.getHeight(),
                rows, cols, new int[]{lPct, tPct, rPct, bPct}), templates);
    }

    static char[][] sample(Bitmap bitmap, BoardGeometry geometry, List<SpecialTemplate> templates) {
        int rows = geometry.rows, cols = geometry.cols;
        char[][] board = new char[rows][cols];
        int cellW = geometry.cellWidth(), cellH = geometry.cellHeight();
        int half = Math.max(3, Math.min(cellW, cellH) / 8);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int cx = geometry.centerX(col);
                int cy = geometry.centerY(row);
                board[row][col] = classifyBoardCell(bitmap, cx, cy, cellW, cellH, half, templates);
            }
        }
        return board;
    }

    int rowCount() { return rows; }

    int colCount() { return cols; }

    /** Names/codes belong to this loaded catalog, not another screen's latest load. */
    String pieceName(char code) {
        requireOpen();
        if (isTemplateCode(code)) {
            for (SpecialTemplate template : templates) if (template.code == code) return template.name;
            return "未识别";
        }
        return Match3Coach.pieceName(code);
    }

    /** 触屏点读：把屏幕坐标映射到格子并分类该格（模板优先）。返回 {row,col,piece}，null=点在棋盘外。 */
    int[] touchRead(Bitmap frame, int px, int py) {
        requireOpen();
        BoardGeometry g = geometryFor(frame);
        int[] hit = g.cellAt(px, py);
        if (hit == null) return null;
        int row = hit[0], col = hit[1];
        int cellW = g.cellWidth(), cellH = g.cellHeight();
        int cx = g.centerX(col), cy = g.centerY(row);
        int half = Math.max(3, Math.min(cellW, cellH) / 8);
        char piece = classifyBoardCell(frame, cx, cy, cellW, cellH, half, templates);
        return new int[]{row, col, piece};
    }

    private static char classifyBoardCell(Bitmap frame, int cx, int cy, int cellW, int cellH,
                                          int half, List<SpecialTemplate> templates) {
        CellAppearance appearance = cellAppearance(frame, cx, cy, cellW, cellH);
        if (appearance.distance <= EMPTY_COLOR_DISTANCE) return EMPTY_CELL;
        char piece = classifyCell(frame, cx, cy, half, templates, appearance.centre);
        if (!isMovable(piece)) return piece;
        // A hue describes appearance, not whether a cell is an ordinary animal.
        // Large orange/purple obstacles and cyan layout holes used to become O/B,
        // causing permanent false runs and a session that never offers any hint.
        // An ordinary animal exposes board background at its cell corners. Reuse
        // the existing GAP colour domain and corner stencil; do not tune HSV bins.
        return appearance.backed >= 3 ? piece : NON_SWAP_CELL;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Sampler is closed");
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        recycleTemplates(templates);
    }

    static void recycleTemplates(List<SpecialTemplate> templates) {
        for (SpecialTemplate template : templates)
            if (template.thumb != null && !template.thumb.isRecycled()) template.thumb.recycle();
    }

    static char classifyCell(Bitmap bitmap, int cx, int cy, int half,
                             List<SpecialTemplate> templates) {
        return classifyCell(bitmap, cx, cy, half, templates, avgColor(bitmap, cx, cy, half));
    }

    private static char classifyCell(Bitmap bitmap, int cx, int cy, int half,
                                    List<SpecialTemplate> templates, int rgb) {
        /* 模板优先：玩家对真实画面学习过的棋子（基础动物＋特殊棋子）最可信，
         * 先比模板（对狐狸红与棕熊棕这类相近色相远比 HSV 桶可靠），HSV 桶只做兜底。 */
        if (templates != null && !templates.isEmpty()) {
            char byTemplate = matchTemplate(bitmap, cx, cy, half, templates);
            if (byTemplate != UNKNOWN) return byTemplate;
        }
        if (rgb == NO_PIXELS) return UNKNOWN;
        float[] hsv = new float[3];
        Color.colorToHSV(rgb, hsv);
        /* 冰块关卡（第 5 关类）：大片亮白/浅蓝白障碍格 S≈0.18-0.4、V≈0.9+，
         * 部分格饱和度越过 0.18 阈值后被读成河马，编出十几个假走法（诊断包 audit 实锤）。
         * 动物棋子饱和度实测最低 0.6+，0.45 分界留足余量。 */
        if (hsv[2] >= 0.85f && hsv[1] < 0.45f) return 'I';
        /* 空位：露出棋盘底（暗、冷色）——与 autoDetectBoard 的棋盘掩码同一色域。
         * 落子洞/布局洞不是棋子，读了必是假色（此前被读成河马导致第三行乱报）。 */
        if (hsv[2] < 0.5f && hsv[0] >= 170f && hsv[0] <= 300f) return GAP_CELL;
        if (hsv[1] < 0.18f || hsv[2] < 0.15f) {
            return matchTemplate(bitmap, cx, cy, half, templates);
        }
        float h = hsv[0];
        if (h >= 345 || h < 14) return 'R';
        if (h < 38) return 'O';
        if (h < 68) return 'Y';
        if (h < 165) return 'G';
        if (h < 262) return 'B';
        return 'P';
    }

    /** 以 (cx,cy) 为中心、half 为半径取平均色，返回 packed RGB；区域越界无像素时返回 NO_PIXELS。 */
    private static int avgColor(Bitmap bitmap, int cx, int cy, int half) {
        long sumR = 0, sumG = 0, sumB = 0, n = 0;
        for (int y = Math.max(0, cy - half); y <= Math.min(bitmap.getHeight() - 1, cy + half); y++) {
            for (int x = Math.max(0, cx - half); x <= Math.min(bitmap.getWidth() - 1, cx + half); x++) {
                int px = bitmap.getPixel(x, y);
                sumR += Color.red(px);
                sumG += Color.green(px);
                sumB += Color.blue(px);
                n++;
            }
        }
        if (n == 0) return NO_PIXELS;
        return Color.rgb((int) (sumR / n), (int) (sumG / n), (int) (sumB / n));
    }

    /** Color.rgb 打包后恒为负数（alpha 占高位），所以「取不到像素」必须用落在有效色域外的哨兵。 */
    private static final int NO_PIXELS = Integer.MIN_VALUE;

    /**
     * 空格判定阈值：中心与四角的逐通道平均色差上限。棋子会盖住中心、盖不住四角。
     * 真机 1235 帧抽帧标定（55860 个正确格格的 d 分布）：有棋子的格 99.42% 落在 d≥60，
     * d≤12 只有 4 格且目视确为空棋盘底；13–59 那一小撮全是跨格边界的棋子、雪块障碍
     * 和遮罩弹窗，不是空格。所以这个值只能往下调不能往上调——调到 20/40/59 会分别把
     * 16/57/322 个真棋子念成「空」。实测数据见 research/board-recognition/REAL_VIDEO_FINDINGS.md
     */
    static final int EMPTY_COLOR_DISTANCE = 12;

    /** 格心与格四角（棋子覆盖不到的位置）的逐通道平均色差。空格≈0，有子时远大于此。 */
    static int centerCornerDistance(Bitmap bitmap, int cx, int cy, int cellW, int cellH) {
        return cellAppearance(bitmap, cx, cy, cellW, cellH).distance;
    }

    private static final class CellAppearance {
        final int centre, distance, backed;
        CellAppearance(int centre, int distance, int backed) {
            this.centre = centre; this.distance = distance; this.backed = backed;
        }
    }

    /** Read the existing centre/corner stencil once for both emptiness and footprint. */
    private static CellAppearance cellAppearance(Bitmap bitmap, int cx, int cy, int cellW, int cellH) {
        int centre = avgColor(bitmap, cx, cy, Math.max(3, Math.min(cellW, cellH) / 8));
        if (centre == NO_PIXELS) return new CellAppearance(centre, Integer.MAX_VALUE, 0);
        int ox = cellW * 42 / 100, oy = cellH * 42 / 100;
        int probe = Math.max(2, Math.min(cellW, cellH) / 16);
        int[][] corners = {
                {cx - ox, cy - oy}, {cx + ox, cy - oy}, {cx - ox, cy + oy}, {cx + ox, cy + oy}
        };
        long total = 0;
        int counted = 0, backed = 0;
        float[] hsv = new float[3];
        for (int[] p : corners) {
            int c = avgColor(bitmap, p[0], p[1], probe);
            if (c == NO_PIXELS) continue;
            total += Math.abs(Color.red(centre) - Color.red(c))
                    + Math.abs(Color.green(centre) - Color.green(c))
                    + Math.abs(Color.blue(centre) - Color.blue(c));
            counted++;
            Color.colorToHSV(c, hsv);
            if (hsv[2] < .5f && hsv[0] >= 170f && hsv[0] <= 300f) backed++;
        }
        return new CellAppearance(centre, counted == 0 ? Integer.MAX_VALUE
                : (int) (total / (counted * 3L)), backed);
    }

    /** 棋盘底色常落在 HSV 弃权闸门（v<0.15）之上，光靠颜色阈值挡不住空格，改用格心与格角的局部对比。 */
    static boolean looksEmpty(Bitmap bitmap, int cx, int cy, int cellW, int cellH) {
        return centerCornerDistance(bitmap, cx, cy, cellW, cellH) <= EMPTY_COLOR_DISTANCE;
    }

    /** 颜色判不出的格子 → 与特殊棋子模板比对（16×16 缩放后平均绝对差），阈值内取最像的。 */
    private static char matchTemplate(Bitmap bitmap, int cx, int cy, int half,
                                      List<SpecialTemplate> templates) {
        if (templates == null || templates.isEmpty()) return UNKNOWN;
        Bitmap cell = cropSquare(bitmap, cx, cy, half * 4);
        if (cell == null) return UNKNOWN;
        Bitmap small = null;
        try {
            small = Bitmap.createScaledBitmap(cell, 16, 16, true);
            int[] pixels = new int[16 * 16];
            small.getPixels(pixels, 0, 16, 0, 0, 16, 16);
            SpecialTemplate bestTemplate = null;
            float best = Float.MAX_VALUE;
            for (SpecialTemplate t : templates) {
                float diff = meanAbsDiff(pixels, t.comparisonPixels);
                if (diff < best) {
                    best = diff;
                    bestTemplate = t;
                }
            }
            if (best > 30f) return UNKNOWN;
            return bestTemplate == null ? UNKNOWN : bestTemplate.code;
        } finally {
            if (small != null && small != cell && small != bitmap) small.recycle();
            if (cell != bitmap) cell.recycle();
        }
    }

    /** 学习到的动物名 → 矩阵字母（基础棋子模板用固定字母，与播报名一致）。 */
    static char nameToLetter(String name) {
        if (name == null) return UNKNOWN;
        // A color substring in “蓝色冰块” or “红色木箱” does not turn an obstacle
        // into an animal. Only explicit aliases of the six basic animals qualify.
        switch (name.trim()) {
            case "狐狸": case "红狐狸": case "狐狸红": case "红": case "红色": return 'R';
            case "小鸡": case "黄小鸡": case "小鸡黄": case "黄": case "黄色": return 'Y';
            case "青蛙": case "绿青蛙": case "青蛙绿": case "绿": case "绿色": return 'G';
            case "河马": case "蓝河马": case "河马蓝": case "蓝": case "蓝色": return 'B';
            case "棕熊": case "熊": case "棕熊棕": case "棕": case "棕色": return 'O';
            case "紫猫": case "紫猫紫": case "紫": case "紫色": return 'P';
            default: return UNKNOWN;
        }
    }

    /** 模板名 → 矩阵字母。字母由本轮载入的模板集合按名字排序稳定分配，不撞车。 */
    static synchronized char templateCode(String name) {
        Character code = NAME_TO_CODE.get(name);
        return code != null ? code : UNKNOWN;
    }

    /** 矩阵字母 → 玩家学的棋子名（播报层用）。非特殊棋子字母返回 null。 */
    static synchronized String nameForCode(char code) {
        return CODE_TO_NAME.get(code);
    }

    /** 按模板集合重建字母分配表。字母池用满后多余的模板返回 UNKNOWN 而非挤占同一字母。 */
    private static synchronized void assignCodes(List<SpecialTemplate> templates) {
        CODE_TO_NAME.clear();
        NAME_TO_CODE.clear();
        List<String> names = new ArrayList<>();
        for (SpecialTemplate t : templates) {
            if (nameToLetter(t.name) == UNKNOWN) names.add(t.name);
        }
        Collections.sort(names);
        for (int i = 0; i < names.size() && i < CODE_POOL.length; i++) {
            CODE_TO_NAME.put(CODE_POOL[i], names.get(i));
            NAME_TO_CODE.put(names.get(i), CODE_POOL[i]);
        }
        for (SpecialTemplate template : templates) {
            char animal = nameToLetter(template.name);
            template.code = animal != UNKNOWN ? animal : templateCode(template.name);
        }
    }

    /** 数字 9 个 + 小写字母 26 个：避开 R/O/Y/G/B/P 六个基础色字母与 '.'。 */
    private static final char[] CODE_POOL = buildCodePool();

    private static char[] buildCodePool() {
        char[] pool = new char[9 + 26];
        int i = 0;
        for (char c = '1'; c <= '9'; c++) pool[i++] = c;
        for (char c = 'a'; c <= 'z'; c++) pool[i++] = c;
        return pool;
    }

    private static final Map<Character, String> CODE_TO_NAME = new LinkedHashMap<>();
    private static final Map<String, Character> NAME_TO_CODE = new LinkedHashMap<>();

    private static Bitmap cropSquare(Bitmap bitmap, int cx, int cy, int halfSide) {
        int side = Math.max(8, halfSide);
        int l = Math.max(0, cx - side), t = Math.max(0, cy - side);
        int r = Math.min(bitmap.getWidth(), cx + side), b = Math.min(bitmap.getHeight(), cy + side);
        if (r - l < 8 || b - t < 8) return null;
        return Bitmap.createBitmap(bitmap, l, t, r - l, b - t);
    }

    static float meanAbsDiff(int[] a, int[] b) {
        long diff = 0;
        for (int i = 0; i < 16 * 16; i++) {
            diff += Math.abs(Color.red(a[i]) - Color.red(b[i]))
                    + Math.abs(Color.green(a[i]) - Color.green(b[i]))
                    + Math.abs(Color.blue(a[i]) - Color.blue(b[i]));
        }
        return diff / (16f * 16f * 3f);
    }

    /* ---------- 棋盘自动适配：检测深色棋盘格区域的包围盒 ---------- */

    /**
     * 纯逻辑：给定逐像素「是棋盘格」掩码（降采样网格），返回棋盘包围盒（百分比）。
     * v3（真机视频对拍后重设计，见 research/board-recognition/REAL_VIDEO_FINDINGS.md）：
     * 行阈值 cols/20（真机棋子几乎填满格子，暗底只在格缝露，cols/4 会把行带切碎→检测失败）；
     * 带内列阈值 15% 定左右；方形约束取边长；带内滑窗取暗底密度最高处锚定
     * （侧边栏/底部导航等暗色 UI 污染列剖面时不跑偏）；窗口密度 <5% 判不可信返回 null。
     * 返回 {l,t,r,b}（百分比，0-100）；检测不到返回 null。
     */
    static int[] detectBoundsFromMask(boolean[][] mask) {
        int rows = mask.length, cols = mask[0].length;
        int[] rowCount = new int[rows];
        for (int r = 0; r < rows; r++) {
            int n = 0;
            for (int c = 0; c < cols; c++) if (mask[r][c]) n++;
            rowCount[r] = n;
        }
        int minRowCount = Math.max(2, cols / 20);
        int bestTop = -1, bestBottom = -1, bestLen = 0;
        int top = -1;
        for (int r = 0; r <= rows; r++) {
            boolean dense = r < rows && rowCount[r] >= minRowCount;
            if (dense && top < 0) top = r;
            if ((!dense || r == rows) && top >= 0) {
                int len = r - top;
                if (len > bestLen) { bestLen = len; bestTop = top; bestBottom = r - 1; }
                top = -1;
            }
        }
        if (bestTop < 0 || bestLen < rows / 8) return null;
        int[] colCount = new int[cols];
        for (int r = bestTop; r <= bestBottom; r++) {
            for (int c = 0; c < cols; c++) if (mask[r][c]) colCount[c]++;
        }
        int bandRows = bestBottom - bestTop + 1;
        int minColCount = Math.max(2, bandRows * 15 / 100);
        int left = -1, right = -1;
        for (int c = 0; c < cols; c++) {
            if (colCount[c] >= minColCount) { if (left < 0) left = c; right = c; }
        }
        if (left < 0 || right - left < cols / 10) return null;
        /* 方形约束 v3b：边长=列宽。左右边框暗线贯通棋盘全高（含冰块区），而行密度在
         * 冰块关卡会断成孤岛（亮色冰格＋浅色缝，暗底只在格缝露）——用「列宽定边长、
         * 带顶锚上缘、向下延展成方形」，冰块全新局面才不会把棋盘缩成 1/4（诊断实锤） */
        int side = right - left + 1;
        side = Math.min(side, rows - bestTop);                   // 不越过屏幕底
        if (side * 10 < Math.min(rows, cols) * 3) return null;   // 边长不足短边 30%
        long[][] integral = new long[rows + 1][cols + 1];
        for (int r = 0; r < rows; r++) {
            long rowSum = 0;
            for (int c = 0; c < cols; c++) {
                rowSum += mask[r][c] ? 1 : 0;
                integral[r + 1][c + 1] = integral[r][c + 1] + rowSum;
            }
        }
        long bestSum = -1;
        int anchorTop = bestTop, anchorLeft = left;
        int maxRowStart = Math.min(bestBottom, bestTop + 40);
        int maxColStart = Math.min(right - side + 1, left + 40);
        for (int rt = bestTop; rt <= maxRowStart; rt++) {
            if (rt + side > rows) continue;
            for (int cl = left; cl <= maxColStart; cl++) {
                if (cl + side > cols) continue;
                long s = integral[rt + side][cl + side] - integral[rt][cl + side]
                        - integral[rt + side][cl] + integral[rt][cl];
                if (s > bestSum) { bestSum = s; anchorTop = rt; anchorLeft = cl; }
            }
        }
        if (bestSum * 20 < (long) side * side) return null;      // 窗口内暗底 <5%，不可信
        return new int[]{
                Math.min(100, anchorLeft * 100 / cols), Math.min(100, anchorTop * 100 / rows),
                Math.min(100, (anchorLeft + side) * 100 / cols),
                Math.min(100, (anchorTop + side) * 100 / rows)
        };
    }

    /**
     * 对一帧做自动适配：降采样 → 逐像素判「深色棋盘格」（开心消消乐棋盘底为深蓝黑）→ 包围盒。
     * 检测不到返回 null（保持手动标定）。
     */
    static int[] autoDetectBoard(Bitmap frame) {
        int[] pixels = autoDetectPixels(frame);
        if (pixels == null) return null;
        return new BoardGeometry(frame.getWidth(), frame.getHeight(), pixels[0], pixels[1],
                pixels[2], pixels[3], 1, 1).percentages();
    }

    static BoardGeometry autoDetectGeometry(Bitmap frame) {
        int[] b = autoDetectPixels(frame);
        return b == null ? null : verifiedGeometry(frame, b);
    }

    static BoardGeometry verifiedGeometry(Bitmap frame, int[] pixels) {
        int cols = detectGridAxis(frame, pixels, false);
        int rows = detectGridAxis(frame, pixels, true);
        if(!squareGrid(frame,pixels,rows,cols)) {
            // Large objects and empty areas dominate the average brightness.
            // Their contents need not share a period, while repeated colour
            // boundaries still provide independent horizontal/vertical evidence.
            cols=detectGridAxis(frame,pixels,false,true);
            rows=detectGridAxis(frame,pixels,true,true);
        }
        if(!squareGrid(frame,pixels,rows,cols))return null;
        return new BoardGeometry(frame.getWidth(), frame.getHeight(), pixels[0], pixels[1],
                pixels[2], pixels[3], rows, cols);
    }
    private static boolean squareGrid(Bitmap frame,int[] pixels,int rows,int cols) {
        if(rows<6 || cols<6)return false;
        float cellWidth = (pixels[2] - pixels[0]) / (float) cols;
        float cellHeight = (pixels[3] - pixels[1]) / (float) rows;
        return Math.abs(cellWidth - cellHeight) <= 2 * Math.max(1,
                Math.min(frame.getWidth(), frame.getHeight()) / 160);
    }

    private static int[] autoDetectPixels(Bitmap frame) {
        // Keep the same sampling density when the full display rotates.
        int step = Math.max(1, Math.min(frame.getWidth(), frame.getHeight()) / 160);
        int cols = frame.getWidth() / step, rows = frame.getHeight() / step;
        boolean[][] mask = new boolean[rows][cols];
        float[] hsv = new float[3];
        int width = frame.getWidth();
        int[] band = new int[width * step];
        for (int r = 0; r < rows; r++) {
            // Occupancy pooling preserves borders thinner than the coarse mask.
            // A single centre sample erased the lower component of level 43 at
            // 1220px, although the 432px diagnostic JPEG kept the whole board.
            // Read one bounded band; the colour domain is unchanged. Stop at the
            // first supporting pixel and avoid HSV work on bright background.
            frame.getPixels(band, 0, width, 0, r * step, width, step);
            for (int c = 0; c < cols; c++) {
                for (int y = 0; y < step && !mask[r][c]; y++) for (int x = 0; x < step; x++) {
                    int rgb = band[y * width + c * step + x];
                    if (boardBackground(rgb, hsv)) {
                        mask[r][c] = true;
                        break;
                    }
                }
            }
        }
        int[] box = connectedBoardBounds(mask);
        if (box == null) return null;
        int l = box[0] * step, t = box[1] * step;
        int r = Math.min(frame.getWidth(), box[2] * step);
        int b = Math.min(frame.getHeight(), box[3] * step);
        // Pooling protects connectivity but must not enlarge the reported grid.
        // Refine only the four edge bands to recover actual supporting pixels.
        return new int[]{l + backgroundExtent(frame, l, t, step, b - t, false, false),
                t + backgroundExtent(frame, l, t, r - l, step, true, false),
                r - step + backgroundExtent(frame, r - step, t, step, b - t, false, true) + 1,
                b - step + backgroundExtent(frame, l, b - step, r - l, step, true, true) + 1};
    }

    private static boolean boardBackground(int rgb, float[] hsv) {
        if (Math.max(Color.red(rgb), Math.max(Color.green(rgb), Color.blue(rgb))) >= .45f * 255f)
            return false;
        Color.colorToHSV(rgb, hsv);
        return hsv[0] >= 170f && hsv[0] <= 300f;
    }

    private static int backgroundExtent(Bitmap frame, int left, int top, int width, int height,
                                        boolean vertical, boolean maximum) {
        int[] pixels = new int[width * height];
        frame.getPixels(pixels, 0, width, left, top, width, height);
        int extent = maximum ? -1 : (vertical ? height : width);
        float[] hsv = new float[3];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if (!boardBackground(pixels[y * width + x], hsv)) continue;
            int at = vertical ? y : x;
            extent = maximum ? Math.max(extent, at) : Math.min(extent, at);
        }
        return extent;
    }

    /** Components keep disconnected letterbox strips out of the board rectangle. */
    static int[] connectedBounds(boolean[][] mask) {
        return componentBounds(mask, false);
    }

    /** Bright layers split the dark border; overlapping component envelopes
     * remain one candidate, with original dark support and independent grid checks. */
    static int[] connectedBoardBounds(boolean[][] mask) {
        return componentBounds(mask, true);
    }

    private static int[] componentBounds(boolean[][] mask, boolean joinEnvelopes) {
        int height = mask.length, width = mask[0].length;
        boolean[][] seen = new boolean[height][width];
        int[] queue = new int[height * width];
        int bestArea = 0;
        int[] best = null;
        List<int[]> components = new ArrayList<>();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if (!mask[y][x] || seen[y][x]) continue;
            int head = 0, tail = 1, left = x, top = y, right = x, bottom = y;
            queue[0] = y * width + x; seen[y][x] = true;
            while (head < tail) {
                int p = queue[head++], r = p / width, c = p % width;
                left = Math.min(left, c); right = Math.max(right, c);
                top = Math.min(top, r); bottom = Math.max(bottom, r);
                // No boxed coordinates or per-pixel allocation on the capture worker.
                if (c > 0 && mask[r][c - 1] && !seen[r][c - 1]) {
                    seen[r][c - 1] = true; queue[tail++] = p - 1;
                }
                if (c + 1 < width && mask[r][c + 1] && !seen[r][c + 1]) {
                    seen[r][c + 1] = true; queue[tail++] = p + 1;
                }
                if (r > 0 && mask[r - 1][c] && !seen[r - 1][c]) {
                    seen[r - 1][c] = true; queue[tail++] = p - width;
                }
                if (r + 1 < height && mask[r + 1][c] && !seen[r + 1][c]) {
                    seen[r + 1][c] = true; queue[tail++] = p + width;
                }
            }
            int[] component = {left, top, right + 1, bottom + 1, tail};
            if (joinEnvelopes) for (int i = 0; i < components.size();) {
                int[] other = components.get(i);
                if (component[0] < other[2] && other[0] < component[2]
                        && component[1] < other[3] && other[1] < component[3]) {
                    component[0] = Math.min(component[0], other[0]);
                    component[1] = Math.min(component[1], other[1]);
                    component[2] = Math.max(component[2], other[2]);
                    component[3] = Math.max(component[3], other[3]);
                    component[4] += other[4]; components.remove(i); i = 0;
                } else i++;
            }
            components.add(component);
        }
        for (int[] component : components) {
            int bw = component[2] - component[0], bh = component[3] - component[1];
            int shortSide = Math.min(bw, bh), longSide = Math.max(bw, bh);
            // Reuse the existing 30% minimum extent and 5% dark-pixel support.
            // Supported 6..9 rectangular grids bound the legal aspect ratio.
            // A pooled bounding box can round an edge by one mask cell. Apply
            // the aspect screen to that interval; refined pixel bounds and the
            // independent axis checks still enforce square individual cells.
            if (shortSide * 10 < Math.min(width, height) * 3 || (shortSide + 1) * 9 < (longSide - 1) * 6
                    || component[4] * 20L < (long) bw * bh) continue;
            if (component[4] > bestArea) {
                bestArea = component[4]; best = new int[]{component[0], component[1], component[2], component[3]};
            }
        }
        return best;
    }

    /** 单格最小可信边长（像素）：1080p 宽下等于 60，与 detectGridCount 自己的弃权门
     *  （cw/ch < 60 就数不出周期）同一把尺；窄屏按比例放宽、下限 40，
     *  免得把小屏上的有效标定误杀。 */
    static int minPlausibleCell(int screenWidthPx) {
        return Math.max(40, screenWidthPx / 18);
    }

    /** 标定框可信性闸门：按屏幕像素＋行列数算单格边长，任一边不足即判不可信。
     *  cellOut（长度 ≥2）回填实测单格宽/高，供日志与提示里复述具体数字。
     *  立此闸门的实据（bugreport 2026-10-07）：存机框 33%x14% 在 1080x2400 上
     *  裁出 356x336，按 8x8 切出单格 44x42，采样点全落在格子缝隙 → 整盘未知 → 全程静默。 */
    static boolean plausibleCalibration(int screenWidthPx, int screenHeightPx,
                                        int l, int t, int r, int b,
                                        int rows, int cols, int[] cellOut) {
        int boxW = screenWidthPx * (r - l) / 100;
        int boxH = screenHeightPx * (b - t) / 100;
        int cellW = cols > 0 ? boxW / cols : 0;
        int cellH = rows > 0 ? boxH / rows : 0;
        if (cellOut != null && cellOut.length >= 2) {
            cellOut[0] = cellW;
            cellOut[1] = cellH;
        }
        int min = minPlausibleCell(screenWidthPx);
        return screenWidthPx > 0 && screenHeightPx > 0 && rows > 0 && cols > 0
                && l >= 0 && t >= 0 && r <= 100 && b <= 100 && r > l && b > t
                && cellW >= min && cellH >= min;
    }

    /**
     * 格数自检：棋盘裁剪区 V 通道列均值剖面的自相关周期 ≈ 格宽
     * （棋子以格宽为周期重复；开心消消乐真机美术没有可见格线，暗线计数不可行，
     * 自相关在真机 7×7 抽帧上逐帧命中 7，合成 7×7 有守卫测试锁住；
     * 但只在按棋盘裁剪后的框内可信——喂进含天空／道具栏的宽框会自信地数成 9，
     * 调用方须先拿到 autoDetectBoard 的框。实测数据见 research/board-recognition/REAL_VIDEO_FINDINGS.md）。
     * 返回 6..9；不可信返回 -1。实时辅助不得把存储格数当作校验通过。
     */
    static int detectGridCount(Bitmap frame, int[] boundsPct) {
        return detectGridAxis(frame, new int[]{frame.getWidth() * boundsPct[0] / 100,
                frame.getHeight() * boundsPct[1] / 100, frame.getWidth() * boundsPct[2] / 100,
                frame.getHeight() * boundsPct[3] / 100}, false);
    }

    private static int detectGridAxis(Bitmap frame, int[] box, boolean vertical) {
        return detectGridAxis(frame,box,vertical,false);
    }
    private static int detectGridAxis(Bitmap frame,int[] box,boolean vertical,boolean edges) {
        int x0 = box[0], y0 = box[1], cw = box[2] - x0, ch = box[3] - y0;
        if (x0 < 0 || y0 < 0 || box[2] > frame.getWidth() || box[3] > frame.getHeight()
                || cw < 60 || ch < 60) return -1;
        int length = vertical ? ch : cw, cross = vertical ? cw : ch;
        // Narrow cell boundaries alias when a sparse board's edge signal is
        // decimated. Keep their spatial samples; brightness keeps its old path.
        int stride = edges?1:Math.max(1, length / 240), n = length / stride;
        if (n < 30) return -1;
        double[] profile = new double[n];
        int[] line = new int[length];
        int samples = 0;
        // V is exactly max(R,G,B)/255; no per-pixel HSV conversion is necessary.
        for (int at = 0; at < cross; at += 2) {
            if (vertical) frame.getPixels(line, 0, 1, x0 + at, y0, 1, ch);
            else frame.getPixels(line, 0, cw, x0, y0 + at, cw, 1);
            samples++;
            for (int c = 0; c < n; c++) {
                int rgb = line[c * stride];
                if(edges) {
                    int next=line[Math.min(length-1,c*stride+stride)];
                    profile[c]+=(Math.abs(Color.red(rgb)-Color.red(next))+Math.abs(Color.green(rgb)-Color.green(next))
                            +Math.abs(Color.blue(rgb)-Color.blue(next)))/765.0;
                } else profile[c] += Math.max(Color.red(rgb), Math.max(Color.green(rgb), Color.blue(rgb))) / 255.0;
            }
        }
        double mean = 0, variance = 0;
        for (int c = 0; c < n; c++) { profile[c] /= samples; mean += profile[c]; }
        mean /= n;
        for (int c = 0; c < n; c++) { profile[c] -= mean; variance += profile[c] * profile[c]; }
        variance /= n;
        if (variance < 1e-6) return -1;
        int bestD = -1; double best = -2;
        for (int d = Math.max(2, n / 10); d <= Math.min(n / 5, n - 1); d++) {
            double score = 0;
            for (int c = 0; c + d < n; c++) score += profile[c] * profile[c + d];
            score /= (n - d) * variance;
            if (score > best) { best = score; bestD = d; }
        }
        int count = bestD < 0 ? -1 : (int) Math.round(n / (double) bestD);
        return count >= 6 && count <= 9 && (!edges || best>0) ? count : -1;
    }

    /* ---------- 特殊棋子模板存取（app 私有目录 special_templates/） ---------- */

    static File templateDir(android.content.Context context) {
        File dir = new File(context.getFilesDir(), "special_templates");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static List<SpecialTemplate> loadTemplates(android.content.Context context) {
        List<SpecialTemplate> out = new ArrayList<>();
        File dir = templateDir(context);
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".png")) continue;
            String name = f.getName().substring(0, f.getName().length() - 4);
            Bitmap bmp = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bmp != null) out.add(new SpecialTemplate(name, bmp));
        }
        assignCodes(out);
        return out;
    }

    static void saveTemplate(android.content.Context context, String name, Bitmap cell) throws IOException {
        if (name == null || name.trim().isEmpty() || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0)
            throw new IOException("棋子名称不能包含路径分隔符");
        Bitmap thumb = Bitmap.createScaledBitmap(cell, 32, 32, true);
        File out = new File(templateDir(context), name + ".png");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            thumb.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } finally {
            if (thumb != cell) thumb.recycle();
        }
    }
}
