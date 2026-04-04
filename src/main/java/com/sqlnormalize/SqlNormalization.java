package com.sqlnormalize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 他アプリケーションから SQL 正規化機能を利用するためのエントリポイント。
 * <p>
 * Swing や GUI に依存せず、{@link SqlNormalizer} の処理へ委譲する。
 * 複数文の分割（引用符外の {@code ;}）もここで提供する。
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
     * 引用符外の {@code ;} で SQL を文ごとに分割する（{@code '} は {@code ''} エスケープを考慮）。
     *
     * @param text 複数文を含む可能性のある文字列（null の場合は空リスト）
     * @return 前後空白を除いた各文（空要素なし）
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
                } else if (c == ';') {
                    String s = cur.toString().trim();
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                    cur.setLength(0);
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

    /**
     * {@link #splitStatements(String)} で分割した各文を {@link #normalize(String)} する。
     *
     * @param text 複数文を含む可能性のある文字列
     * @return 各文の正規化結果（入力が null・空のときは空の不変リスト）
     */
    public static List<String> normalizeScript(String text) {
        List<String> parts = splitStatements(text);
        if (parts.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(parts.size());
        for (String p : parts) {
            out.add(ENGINE.normalize(p));
        }
        return out;
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
