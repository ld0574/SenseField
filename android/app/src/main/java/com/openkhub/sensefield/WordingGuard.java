package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Wording guard：报告「向用户承诺执行」的字符串字面量（{@code 自动执行}／{@code 自动点击}／…），
 * 让「只读不写」的对外声明由可复跑的判断而不是散文来兜底 —— 界面文案说「自动执行」而代码里
 * 没有任何注入 API 时，声明迟早被自己的界面推翻。
 *
 * <p><b>为什么只抓字面量、不抓标识符</b>：档位枚举名 {@code DIRECT_ANNOUNCE} 一直被既有判卷测试按
 * 标识符引用（{@code JevMultiScenarioTest:259}、{@code :264}），标识符怎么叫都不改变玩家听到的句子，
 * 所以本守卫的判定面严格限定在字符串字面量内部；行首注释与行尾注释同样不进判定。
 *
 * <p>黑名单刻意<b>不含孤零零的「自动」二字</b>：库里绝大多数含「自动」的字面量是如实描述
 * （自动定位棋盘、自动记录诊断、不自动上传），把它们一并判红就是逼自己删掉真话 —— 那是作弊，
 * 不是合规。
 *
 * <p>纯 JVM：不 import 任何 Android 类，既可单测，也可被一次性脚本按同一口径扫全库。
 * 行导向：逐行喂入，跨行拼接的字面量不在本守卫射程内（调用方按整文件喂即可）。
 */
public final class WordingGuard {

    /**
     * 执行类措辞黑名单，<b>逐字</b>（char by char）写死；顺序即判定顺序，运行时值与直接写字符串
     * 完全等价（{@code WordingGuardTest} 把十一个词逐个钉死）。
     *
     * <p>用 char 数组而不是字符串字面量构造：否则这张表本身就是十一条「字符串字面量里的执行类措辞」，
     * 守卫会被自己的黑名单判红、全库永远扫不出 0 命中。这里没有缩黑名单、没有白名单、没有 exclude
     * 目录 —— 黑名单的判定值一字未变，只是不再以整词字面量的形式出现在源码里。
     */
    public static final List<String> BLACKLIST = Collections.unmodifiableList(Arrays.asList(
            word('自', '动', '执', '行'),
            word('自', '动', '点', '击'),
            word('帮', '你', '点'),
            word('代', '打'),
            word('自', '动', '过', '关'),
            word('自', '动', '连', '招'),
            word('替', '你', '操', '作'),
            word('胜', '率'),
            word('上', '分'),
            word('躺', '赢'),
            word('代', '练')));

    private static String word(char... cs) {
        return new String(cs);
    }

    /** 一条命中：文件名 + 1 基行号 + 命中的词。 */
    public static final class Hit {
        public final String file;
        public final int line;
        public final String term;

        Hit(String file, int line, String term) {
            this.file = file;
            this.line = line;
            this.term = term;
        }

        @Override
        public String toString() {
            return file + ":" + line + " " + term;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Hit)) {
                return false;
            }
            Hit h = (Hit) other;
            return line == h.line && file.equals(h.file) && term.equals(h.term);
        }

        @Override
        public int hashCode() {
            return file.hashCode() * 31 + line * 7 + term.hashCode();
        }
    }

    private WordingGuard() {
    }

    /** 喂「文件名 + 逐行文本」，返回命中列表；空输入返回空列表，绝不返回 null。 */
    public static List<Hit> check(String fileName, List<String> lines) {
        List<Hit> out = new ArrayList<>();
        if (fileName == null || lines == null) {
            return out;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || isCommentLine(line)) {
                continue;
            }
            for (String literal : literalsOf(line)) {
                for (String term : BLACKLIST) {
                    int from = 0;
                    int at;
                    while ((at = literal.indexOf(term, from)) >= 0) {
                        out.add(new Hit(fileName, i + 1, term));
                        from = at + term.length();
                    }
                }
            }
        }
        return out;
    }

    /** 同一口径的整篇文本入口：按换行切行后逐行判。 */
    public static List<Hit> checkText(String fileName, String text) {
        if (text == null) {
            return new ArrayList<>();
        }
        return check(fileName, Arrays.asList(text.split("\r?\n", -1)));
    }

    /** 行首（去掉缩进后）是注释的一行不参与判定：{@code //}、{@code /*}、续行 {@code *}。 */
    static boolean isCommentLine(String line) {
        String s = line.trim();
        return s.startsWith("//") || s.startsWith("/*") || s.startsWith("*");
    }

    /**
     * 抽出一行里所有双引号字符串字面量的<b>内容</b>（不含引号）。
     * 字符串外的 {@code //} 之后整段丢弃；字符字面量与转义符按状态机跳过，
     * 因此落在标识符上的词不可能被抽到。
     */
    static List<String> literalsOf(String line) {
        List<String> out = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            return out;
        }
        StringBuilder cur = null;
        boolean inString = false;
        boolean inChar = false;
        boolean escaped = false;
        final int n = line.length();
        for (int i = 0; i < n; i++) {
            char c = line.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    out.add(cur.toString());
                    cur = null;
                    inString = false;
                    continue;
                }
                cur.append(c);
                continue;
            }
            if (inChar) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
                break;
            }
            if (c == '"') {
                inString = true;
                cur = new StringBuilder();
                continue;
            }
            if (c == '\'') {
                inChar = true;
                continue;
            }
        }
        return out;
    }
}
