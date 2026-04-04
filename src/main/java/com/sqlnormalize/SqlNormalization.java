package com.sqlnormalize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 他アプリケーションから SQL 正規化機能を利用するためのエントリポイント。
 * <p>
 * Swing や GUI に依存せず、{@link SqlNormalizer} の処理へ委譲する。
 * 複数文の分割（引用符外の改行）と、ログ行末尾側の {@code |} より後ろだけを SQL として取り出す処理もここで提供する。
 * </p>
 */
public final class SqlNormalization {

    private static final SqlNormalizer ENGINE = new SqlNormalizer();

    private SqlNormalization() {
    }

    /**
     * 1 文の SQL を正規化する（{@link SqlNormalizer#normalize(String)} と同じ）。
     *
     * @param sql 正規化対象（null または空白のみの場合は空文字）
     * @return 正規化後の SQL
     */
    public static String normalize(String sql) {
        return ENGINE.normalize(sql);
    }

    /**
     * 引用符外の改行（{@code \n} / {@code \r\n} / {@code \r}）で SQL を文ごとに分割する。
     * {@code '} は {@code ''} エスケープを考慮し、単引用符内の改行では分割しない（複行リテラル用）。
     *
     * @param text 複数文を含む可能性のある文字列（null の場合は空リスト）
     * @return 行ごとに前後空白を除いた各文（空行は無視）
     */
    public static List<String> splitStatements(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (inSingle) {
                cur.append(c);
                if (c == '\'') {
                    if (i + 1 < t.length() && t.charAt(i + 1) == '\'') {
                        cur.append(t.charAt(++i));
                    } else {
                        inSingle = false;
                    }
                }
            } else if (inDouble) {
                cur.append(c);
                if (c == '"') {
                    inDouble = false;
                }
            } else {
                if (c == '\'') {
                    inSingle = true;
                    cur.append(c);
                } else if (c == '"') {
                    inDouble = true;
                    cur.append(c);
                } else if (c == '\r') {
                    flushStatementLine(out, cur);
                    if (i + 1 < t.length() && t.charAt(i + 1) == '\n') {
                        i++;
                    }
                } else if (c == '\n') {
                    flushStatementLine(out, cur);
                } else {
                    cur.append(c);
                }
            }
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }

    private static void flushStatementLine(List<String> out, StringBuilder cur) {
        String s = cur.toString().trim();
        if (!s.isEmpty()) {
            out.add(s);
        }
        cur.setLength(0);
    }

    /**
     * 1 行について、単引用符・二重引用符の<strong>外側</strong>で<strong>最後</strong>に現れる {@code |} より右側を SQL とみなす。
     * 例: {@code 2026-03-10 11:04:17.498|statement|SELECT ...} → {@code SELECT ...}（末尾の {@code ;} もそのまま残る）。
     * {@code |} が 1 つも無い行は、前後空白を除いた行全体を返す。
     *
     * @param line 1 行（ログ行や素の SQL のどちらでも可）
     * @return 取り出した SQL 相当の部分（null のときは空文字）
     */
    public static String extractSqlAfterLastUnquotedPipe(String line) {
        if (line == null) {
            return "";
        }
        String t = line.trim();
        if (t.isEmpty()) {
            return "";
        }
        int lastPipe = -1;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    if (i + 1 < t.length() && t.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inSingle = false;
                    }
                }
            } else if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                }
            } else {
                if (c == '\'') {
                    inSingle = true;
                } else if (c == '"') {
                    inDouble = true;
                } else if (c == '|') {
                    lastPipe = i;
                }
            }
        }
        if (lastPipe < 0) {
            return t;
        }
        return t.substring(lastPipe + 1).trim();
    }

    /**
     * 正規化の対象にしない（スキップする）か。前後空白を除いた結果が空、または {@code ;} のみ（例 {@code ;}、{@code ;;}）のとき {@code true}。
     *
     * @param sqlFragment {@link #extractSqlAfterLastUnquotedPipe(String)} 後などの断片
     */
    public static boolean isBlankOrSemicolonOnlySql(String sqlFragment) {
        if (sqlFragment == null || sqlFragment.isEmpty()) {
            return true;
        }
        String t = sqlFragment.trim();
        if (t.isEmpty()) {
            return true;
        }
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) != ';') {
                return false;
            }
        }
        return true;
    }

    /**
     * {@link #splitStatements(String)} で分割した各文を {@link #normalize(String)} する。
     * {@link #extractSqlAfterLastUnquotedPipe(String)} のあと {@link #isBlankOrSemicolonOnlySql(String)} なら結果に含めない。
     *
     * @param text 複数文を含む可能性のある文字列
     * @return 各文の正規化結果（入力が null・空のとき、またはすべてスキップ対象のときは空の不変リスト）
     */
    public static List<String> normalizeScript(String text) {
        List<String> parts = splitStatements(text);
        if (parts.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(parts.size());
        for (String p : parts) {
            String q = extractSqlAfterLastUnquotedPipe(p);
            if (isBlankOrSemicolonOnlySql(q)) {
                continue;
            }
            out.add(ENGINE.normalize(q));
        }
        return out.isEmpty() ? Collections.emptyList() : out;
    }

    /**
     * 1 行に潰した SQL を句単位で改行整形する（{@link SqlNormalizer#formatPretty(String)}）。
     */
    public static String formatPretty(String oneLineSql) {
        return ENGINE.formatPretty(oneLineSql);
    }

    /**
     * インデント・改行・連続空白の違いを無視して 2 つの SQL が同じか（{@link SqlNormalizer#compareIgnoreLayout}）。
     */
    public static boolean compareIgnoreLayout(String a, String b) {
        return SqlNormalizer.compareIgnoreLayout(a, b);
    }

    /**
     * キーワード着色など用のパターン（{@link SqlNormalizer#keywordHighlightPattern()}）。
     */
    public static Pattern keywordHighlightPattern() {
        return SqlNormalizer.keywordHighlightPattern();
    }
}
