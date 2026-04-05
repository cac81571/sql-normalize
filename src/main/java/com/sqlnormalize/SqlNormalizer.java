package com.sqlnormalize;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.select.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL文を正規化するクラス。
 * <p>
 * 移行前後のSQLを比較しやすくするため、以下を行う。
 * <ul>
 *   <li>テーブルエイリアスを {@code t1}, {@code t2}, …（FROM/JOIN の出現順）に統一する。Oracle の {@code DUAL} には別名を付けず、列も {@code tN} 修飾しない</li>
 *   <li>全カラムを {@code tN}.{@code カラム名} 形式で修飾（{@code DUAL} 由来の列は修飾なし）。{@code t} 番号は文全体で連番。相関参照は外側スコープの {@code tN} に置換</li>
 *   <li>SELECT項目のエイリアス（AS 別名）を除去</li>
 *   <li>SQLキーワード・空白の正規化（大文字統一、連続空白を1スペースに）</li>
 *   <li>SELECT 文は A5:SQL Mk-2 に近い整形（正規化結果では SELECT/GROUP BY/ORDER BY の列リストは {@code 列, 列} と 1 行。整形ペインでは行頭 {@code , } で複行。FROM 以降は句ごとの改行、WHERE/ON/HAVING の AND・OR 前改行などは共通）</li>
 *   <li>INSERT 文は正規化結果では {@code INTO 表 (列, 列, …)} と {@code VALUES (式, 式, …)} を 1 行。整形ペインでは列リスト・{@code VALUES} 各式を行頭カンマで複行。複数行 {@code VALUES} は {@code ),} 改行後に次のタプルをインデント。{@code INSERT} の {@code VALUES} 列名コメントは {@link #formatNormalizedSqlForDisplayPane} のみ</li>
 *   <li>UPDATE 文は正規化結果では {@code SET} を {@code 列 = 式, …} で 1 行。整形ペインでは行頭 {@code , } で複行。{@code WHERE} は {@code AND}/{@code OR} 前改行で複行整形</li>
 *   <li>DELETE 文は {@code DELETE} / {@code FROM} / 表名を改行で分け、{@code WHERE} を {@code AND}/{@code OR} 前改行で複行整形</li>
 *   <li>上記以外の文は従来の句単位整形（{@link #formatPretty}）</li>
 * </ul>
 * </p>
 */
public class SqlNormalizer {

    /** 連続する空白を1つにまとめるための正規表現。 */
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    /** 整形時に直前で改行するキーワード（長いものから順にマッチ）。 */
    private static final String[] PRETTY_BREAK_KEYWORDS = {
            "UNION ALL", "GROUP BY", "ORDER BY",
            "LEFT OUTER JOIN", "RIGHT OUTER JOIN", "FULL OUTER JOIN",
            "INNER JOIN", "CROSS JOIN", "LEFT JOIN", "RIGHT JOIN",
            "FETCH FIRST", "FOR UPDATE",
            "FROM", "WHERE", "HAVING", "LIMIT", "OFFSET", "UNION",
            "JOIN", "ON"
    };

    /** 継続行のインデント。 */
    private static final String PRETTY_INDENT = "    ";

    /** 大文字に統一するSQLキーワード（SELECT, FROM, NEXTVAL など）。 */
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "SELECT", "FROM", "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER",
            "ON", "AND", "OR", "AS", "ORDER", "BY", "GROUP", "HAVING", "LIMIT",
            "OFFSET", "UNION", "ALL", "DISTINCT", "INSERT", "INTO", "VALUES",
            "UPDATE", "SET", "DELETE", "CASE", "WHEN", "THEN", "ELSE", "END",
            "NEXTVAL", "CURRVAL", "SYSDATE", "ROWNUM", "LEVEL", "NULL", "TRUE", "FALSE",
            "WITH", "ASC", "DESC", "IS", "NOT", "IN", "BETWEEN", "LIKE", "EXISTS",
            "FETCH", "FIRST", "ROWS", "ONLY", "NATURAL", "CROSS", "FULL", "FOR"
    ));

    /** 正規化SQL表示用: {@link #KEYWORDS} を単語境界でマッチ（長い語を先にマッチ）。 */
    private static final Pattern KEYWORD_HIGHLIGHT_PATTERN = buildKeywordHighlightPattern();

    private static Pattern buildKeywordHighlightPattern() {
        List<String> words = new ArrayList<>(KEYWORDS);
        words.sort((a, b) -> Integer.compare(b.length(), a.length()));
        StringBuilder alt = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) {
                alt.append('|');
            }
            alt.append(Pattern.quote(words.get(i)));
        }
        return Pattern.compile("\\b(?:" + alt + ")\\b");
    }

    /**
     * 正規化結果ペインなどで SQL キーワードを着色するためのパターン（{@code KEYWORDS} と一致）。
     */
    public static Pattern keywordHighlightPattern() {
        return KEYWORD_HIGHLIGHT_PATTERN;
    }

    /**
     * SQL文字列を正規化する。
     *
     * @param sql 正規化対象のSQL文（null の場合は空文字を返す）
     * @return 正規化後のSQL文。パースに失敗した場合は空白・キーワードのみ正規化した文字列
     */
    public String normalize(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "";
        }
        String trimmed = sql.trim();
        try {
            Statement stmt = CCJSqlParserUtil.parse(trimmed);
            return stripSpacesBeforeCommaOutsideStrings(normalizeParsed(stmt));
        } catch (Exception e) {
            return stripSpacesBeforeCommaOutsideStrings(normalizeFallback(trimmed));
        }
    }

    /**
     * 整形ペイン（および Excel 用 HTML 表の SQL セル）表示用。
     * 既に正規化済みの 1 文を再パースし、SELECT / INSERT / UPDATE について列リスト・{@code VALUES}・{@code SET} を
     * 行頭カンマ＋改行＋インデントの複行にしたうえでキーワード大文字化・カンマ前空白除去を適用する。
     * {@code DELETE} は {@link SqlDeleteFormatter#formatDelete} による複行整形を適用する。
     * {@code INSERT} で列リストと式の個数が一致するときは {@code VALUES} 各式の直後に列名のブロックコメントを付ける。
     * 上記以外の文種、またはパースに失敗した場合は {@code normalizedSql} をそのまま返す。
     *
     * @param normalizedSql {@link #normalize(String)} 済みなどの 1 文
     */
    public String formatNormalizedSqlForDisplayPane(String normalizedSql) {
        if (normalizedSql == null || normalizedSql.trim().isEmpty()) {
            return "";
        }
        String trimmed = normalizedSql.trim();
        try {
            Statement stmt = CCJSqlParserUtil.parse(trimmed);
            String formatted;
            if (stmt instanceof Select) {
                formatted = SqlA5Formatter.formatSelect(stmt, true);
            } else if (stmt instanceof Insert) {
                formatted = SqlInsertFormatter.formatInsert((Insert) stmt, true, true);
            } else if (stmt instanceof Update) {
                formatted = SqlUpdateFormatter.formatUpdate((Update) stmt, true);
            } else if (stmt instanceof Delete) {
                formatted = SqlDeleteFormatter.formatDelete((Delete) stmt);
            } else {
                return normalizedSql;
            }
            return stripSpacesBeforeCommaOutsideStrings(uppercaseKeywordsMultiline(formatted));
        } catch (Exception e) {
            return normalizedSql;
        }
    }

    /**
     * パース済みのStatementを正規化し、文字列に戻して返す。
     *
     * @param stmt JSqlParserでパース済みのStatement
     * @return 正規化後のSQL文字列
     */
    private String normalizeParsed(Statement stmt) throws Exception {
        if (stmt instanceof Select) {
            Select select = (Select) stmt;
            int[] tableOrdinal = new int[] { 1 };
            List<WithItem> withItems = select.getWithItemsList();
            if (withItems != null) {
                for (WithItem w : withItems) {
                    if (w.getSubSelect() != null && w.getSubSelect().getSelectBody() != null) {
                        normalizeSelectBodyRecursive(w.getSubSelect().getSelectBody(), tableOrdinal, null);
                    }
                }
            }
            normalizeSelectBodyRecursive(select.getSelectBody(), tableOrdinal, null);
        }
        if (stmt instanceof Select) {
            String a5 = SqlA5Formatter.formatSelect(stmt);
            return uppercaseKeywordsMultiline(a5);
        }
        if (stmt instanceof Insert) {
            return uppercaseKeywordsMultiline(SqlInsertFormatter.formatInsert((Insert) stmt));
        }
        if (stmt instanceof Update) {
            return uppercaseKeywordsMultiline(SqlUpdateFormatter.formatUpdate((Update) stmt));
        }
        if (stmt instanceof Delete) {
            return uppercaseKeywordsMultiline(SqlDeleteFormatter.formatDelete((Delete) stmt));
        }
        String raw = stmt.toString();
        String oneLine = normalizeWhitespaceAndKeywords(raw);
        return formatPretty(oneLine);
    }

    /**
     * 複行 SQL の各行について、行頭インデントは保ちつつキーワード大文字化・行内空白の正規化を行う。
     */
    private String uppercaseKeywordsMultiline(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        String[] lines = sql.split("\\R", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            int p = 0;
            while (p < line.length() && (line.charAt(p) == ' ' || line.charAt(p) == '\t')) {
                p++;
            }
            String lead = line.substring(0, p);
            String rest = line.substring(p);
            if (rest.trim().isEmpty()) {
                out.append(line);
                continue;
            }
            out.append(lead).append(normalizeWhitespaceAndKeywords(rest));
        }
        return out.toString();
    }

    /**
     * @param tableOrdinal 単一 SELECT ツリー全体で共有する {@code t} 番号カウンタ（[0] が次に採番する値）
     * @param enclosingAliases 外側スコープの (元別名/表名 → tN)。相関参照の解決に使う。トップレベルは {@code null}
     */
    private void normalizeSelectBodyRecursive(SelectBody body, int[] tableOrdinal, Map<String, String> enclosingAliases) {
        if (body instanceof PlainSelect) {
            normalizePlainSelect((PlainSelect) body, tableOrdinal, enclosingAliases);
        } else if (body instanceof SetOperationList) {
            for (SelectBody sub : ((SetOperationList) body).getSelects()) {
                normalizeSelectBodyRecursive(sub, tableOrdinal, enclosingAliases);
            }
        }
    }

    /** 外側スコープと当該 {@link PlainSelect} の FROM マップをマージ（子サブクエリの相関解決用）。 */
    private static Map<String, String> mergeEnclosingAliases(Map<String, String> enclosing, Map<String, String> localFrom) {
        Map<String, String> m = new LinkedHashMap<>();
        if (enclosing != null) {
            m.putAll(enclosing);
        }
        if (localFrom != null) {
            m.putAll(localFrom);
        }
        return m;
    }

    /**
     * PlainSelect（単一SELECT文）を正規化する。
     * テーブルエイリアスを FROM/JOIN の出現順で {@code t1}, {@code t2}, … に付け替える。
     * 全カラムを {@code tN}.カラム名 で修飾し、SELECT項目のエイリアスを除去する。
     * {@code WHERE}/{@code HAVING}/{@code ON}/SELECT 式内の {@link SubSelect}（{@code EXISTS}、{@code IN}、スカラサブクエリ等）も再帰的に正規化する。
     *
     * @param plain              正規化対象の PlainSelect
     * @param tableOrdinal       文全体で共有する {@code t} 番号（サブクエリで 1 に戻さない）
     * @param enclosingAliases   外側の別名→tN（相関列の修飾に使用）
     */
    private void normalizePlainSelect(PlainSelect plain, int[] tableOrdinal, Map<String, String> enclosingAliases) {
        // 当スコープの FROM/JOIN: (元別名または表名 → tN)
        Map<String, String> aliasToCanonical = new LinkedHashMap<>();
        String defaultTableCanonical = null;

        FromItem from = plain.getFromItem();
        List<Join> joins = plain.getJoins();

        // FROM/JOIN を走査して正規名を決定し、各 FromItem 直後にその中の SubSelect を正規化
        List<FromItem> fromItems = new ArrayList<>();
        if (from != null) fromItems.add(from);
        if (joins != null) {
            for (Join j : joins) {
                if (j.getRightItem() != null) fromItems.add(j.getRightItem());
            }
        }
        Set<String> dualTableKeys = new HashSet<>();
        for (FromItem item : fromItems) {
            if (isDualFromItem(item)) {
                registerDualKeys(item, dualTableKeys);
                item.setAlias(null);
                if (item instanceof SubSelect) {
                    SelectBody subBody = ((SubSelect) item).getSelectBody();
                    if (subBody != null) {
                        Map<String, String> forChild = mergeEnclosingAliases(enclosingAliases, aliasToCanonical);
                        normalizeSelectBodyRecursive(subBody, tableOrdinal, forChild);
                    }
                }
                continue;
            }

            String canonical = "t" + tableOrdinal[0]++;

            String key = getAliasOrTableName(item);
            if (key != null && !key.isEmpty()) {
                aliasToCanonical.put(key, canonical);
            }
            if (defaultTableCanonical == null) {
                defaultTableCanonical = canonical;
            }
            setFromItemCanonicalAlias(item, canonical);

            if (item instanceof SubSelect) {
                SelectBody subBody = ((SubSelect) item).getSelectBody();
                if (subBody != null) {
                    Map<String, String> forChild = mergeEnclosingAliases(enclosingAliases, aliasToCanonical);
                    normalizeSelectBodyRecursive(subBody, tableOrdinal, forChild);
                }
            }
        }

        // SELECT 項目のエイリアス除去
        List<SelectItem> items = plain.getSelectItems();
        if (items != null) {
            for (SelectItem item : items) {
                if (item instanceof SelectExpressionItem) {
                    ((SelectExpressionItem) item).setAlias(null);
                }
            }
        }

        final String defaultTable = defaultTableCanonical;
        final Map<String, String> enc = enclosingAliases;
        final Set<String> dualKeys = dualTableKeys;
        // 全カラムを tN.カラム名 に統一（当スコープ→外側スコープの順で別名解決、未修飾は先頭 t）
        ExpressionVisitorAdapter columnQualifier = new ExpressionVisitorAdapter() {
            @Override
            public void visit(SubSelect subSelect) {
                SelectBody body = subSelect.getSelectBody();
                if (body != null) {
                    Map<String, String> forChild = mergeEnclosingAliases(enc, aliasToCanonical);
                    normalizeSelectBodyRecursive(body, tableOrdinal, forChild);
                }
                super.visit(subSelect);
            }

            @Override
            public void visit(Column col) {
                Table t = col.getTable();
                if (t != null && t.getName() != null && !t.getName().trim().isEmpty()) {
                    String name = t.getName().replace("\"", "").trim();
                    if (dualKeys.contains(name.toUpperCase(Locale.ROOT))) {
                        col.setTable(null);
                        super.visit(col);
                        return;
                    }
                }
                String canonical;
                if (t != null && t.getName() != null && !t.getName().trim().isEmpty()) {
                    String name = t.getName().replace("\"", "").trim();
                    canonical = aliasToCanonical.get(name);
                    if (canonical == null && enc != null) {
                        canonical = enc.get(name);
                    }
                    if (canonical == null) {
                        canonical = name;
                    }
                } else {
                    canonical = defaultTable;
                }
                if (canonical != null) {
                    col.setTable(new Table(canonical));
                } else {
                    col.setTable(null);
                }
                super.visit(col);
            }
        };

        if (plain.getWhere() != null) plain.getWhere().accept(columnQualifier);
        if (plain.getHaving() != null) plain.getHaving().accept(columnQualifier);
        if (joins != null) {
            for (Join j : joins) {
                if (j.getOnExpression() != null) j.getOnExpression().accept(columnQualifier);
            }
        }
        if (items != null) {
            for (SelectItem item : items) {
                if (item instanceof SelectExpressionItem) {
                    ((SelectExpressionItem) item).getExpression().accept(columnQualifier);
                }
            }
        }
        if (plain.getOrderByElements() != null) {
            for (OrderByElement ob : plain.getOrderByElements()) {
                if (ob.getExpression() != null) ob.getExpression().accept(columnQualifier);
            }
        }
        if (plain.getGroupBy() != null && plain.getGroupBy().getGroupByExpressions() != null) {
            for (Expression ex : plain.getGroupBy().getGroupByExpressions()) {
                if (ex != null) ex.accept(columnQualifier);
            }
        }
    }

    /**
     * FromItemのエイリアス名、またはエイリアスが無い場合はテーブル名を返す。
     *
     * @param item FROM/JOIN の FromItem
     * @return エイリアスまたはテーブル名。取得できない場合は null
     */
    private String getAliasOrTableName(FromItem item) {
        Alias a = item.getAlias();
        if (a != null && a.getName() != null && !a.getName().trim().isEmpty()) {
            return a.getName().replace("\"", "").trim();
        }
        if (item instanceof Table) {
            return ((Table) item).getName().replace("\"", "").trim();
        }
        return null;
    }

    /**
     * FROM句の要素に正規化後のテーブル名（別名）を設定する。
     *
     * @param item     FROM/JOIN の FromItem
     * @param canonical 正規別名（{@code t1}, {@code t2}, …）
     */
    private void setFromItemCanonicalAlias(FromItem item, String canonical) {
        item.setAlias(new Alias(canonical, false));
    }

    private static boolean isDualFromItem(FromItem item) {
        if (!(item instanceof Table)) {
            return false;
        }
        String n = ((Table) item).getName();
        if (n == null) {
            return false;
        }
        return "DUAL".equalsIgnoreCase(n.replace("\"", "").trim());
    }

    /** {@code DUAL} およびユーザー別名を、修飾除去のキーとして登録する。 */
    private static void registerDualKeys(FromItem item, Set<String> dualTableKeys) {
        if (item instanceof Table) {
            String tn = ((Table) item).getName();
            if (tn != null && !tn.trim().isEmpty()) {
                dualTableKeys.add(tn.replace("\"", "").trim().toUpperCase(Locale.ROOT));
            }
        }
        Alias a = item.getAlias();
        if (a != null && a.getName() != null && !a.getName().trim().isEmpty()) {
            dualTableKeys.add(a.getName().replace("\"", "").trim().toUpperCase(Locale.ROOT));
        }
    }

    /**
     * 空白を単一スペースにし、KEYWORDSに含まれる語を大文字に変換する。
     *
     * @param sql 対象のSQL文字列
     * @return 正規化後の文字列
     */
    private String normalizeWhitespaceAndKeywords(String sql) {
        String oneSpace = MULTI_SPACE.matcher(sql).replaceAll(" ").trim();
        StringBuilder sb = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < oneSpace.length(); i++) {
            char c = oneSpace.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                word.append(c);
            } else {
                if (word.length() > 0) {
                    String w = word.toString();
                    if (KEYWORDS.contains(w.toUpperCase())) {
                        sb.append(w.toUpperCase());
                    } else {
                        sb.append(w);
                    }
                    word.setLength(0);
                }
                sb.append(c);
            }
        }
        if (word.length() > 0) {
            String w = word.toString();
            if (KEYWORDS.contains(w.toUpperCase())) {
                sb.append(w.toUpperCase());
            } else {
                sb.append(w);
            }
        }
        return MULTI_SPACE.matcher(sb.toString()).replaceAll(" ").trim();
    }

    /**
     * パースに失敗した場合のフォールバック。空白とキーワードのみ正規化する。
     *
     * @param sql 元のSQL文字列
     * @return 空白・キーワード正規化後の文字列
     */
    private String normalizeFallback(String sql) {
        return formatPretty(normalizeWhitespaceAndKeywords(sql));
    }

    /**
     * 単引用符リテラル以外で、直前の半角スペースのみを除いてからカンマを付与する。
     * 改行直後のインデント（行頭カンマ用の {@code \\n    ,}）はそのまま残す。
     */
    private static String stripSpacesBeforeCommaOutsideStrings(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql == null ? "" : sql;
        }
        StringBuilder out = new StringBuilder(sql.length());
        boolean inSingle = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inSingle) {
                out.append(c);
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        out.append(sql.charAt(++i));
                    } else {
                        inSingle = false;
                    }
                }
            } else if (c == '\'') {
                inSingle = true;
                out.append(c);
            } else if (c == ',') {
                int j = out.length() - 1;
                while (j >= 0 && out.charAt(j) == ' ') {
                    j--;
                }
                if (j >= 0 && out.charAt(j) == '\n') {
                    out.append(',');
                } else {
                    out.setLength(j + 1);
                    out.append(',');
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * 1行に潰したSQLを、主要キーワードの前で改行しインデントして整形する。
     * 比較用途では {@link #compareIgnoreLayout(String, String)} でレイアウト差を無視できる。
     *
     * @param oneLine 空白正規化済みのSQL（1行想定）
     * @return 改行・インデント付きSQL
     */
    public String formatPretty(String oneLine) {
        if (oneLine == null || oneLine.trim().isEmpty()) {
            return "";
        }
        String s = oneLine.trim();
        for (String kw : PRETTY_BREAK_KEYWORDS) {
            String kwRegex = kw.replace(" ", "\\s+");
            Pattern p = Pattern.compile("(\\S)(\\s+)(" + kwRegex + ")\\b");
            s = p.matcher(s).replaceAll(mr ->
                    Matcher.quoteReplacement(mr.group(1) + "\n" + PRETTY_INDENT + kw));
        }
        return s.trim();
    }

    /**
     * 改行・先頭の連続スペース（インデント）を除いた文字列で比較する。
     *
     * @param a 正規化SQL1
     * @param b 正規化SQL2
     * @return レイアウトを除いて同一なら true
     */
    public static boolean compareIgnoreLayout(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return collapseLayout(a).equals(collapseLayout(b));
    }

    private static String collapseLayout(String sql) {
        String[] lines = sql.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.replaceFirst("^\\s+", "").trim();
            if (!t.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(t);
            }
        }
        return sb.toString();
    }
}
