package com.sqlnormalize;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * SQL文を正規化するクラス。
 * <p>
 * 移行前後のSQLを比較しやすくするため、以下を行う。
 * <ul>
 *   <li>テーブルエイリアスを除去（同一テーブル複数回使用時は テーブル名_1, テーブル名_2 で区別）</li>
 *   <li>全カラムを テーブル名.カラム名 で修飾</li>
 *   <li>SELECT項目のエイリアス（AS 別名）を除去</li>
 *   <li>SQLキーワード・空白の正規化（大文字統一、連続空白を1スペースに）</li>
 * </ul>
 * </p>
 */
public class SqlNormalizer {

    /** 連続する空白を1つにまとめるための正規表現。 */
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    /** 大文字に統一するSQLキーワード（SELECT, FROM, NEXTVAL など）。 */
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "SELECT", "FROM", "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER",
            "ON", "AND", "OR", "AS", "ORDER", "BY", "GROUP", "HAVING", "LIMIT",
            "OFFSET", "UNION", "ALL", "DISTINCT", "INSERT", "INTO", "VALUES",
            "UPDATE", "SET", "DELETE", "CASE", "WHEN", "THEN", "ELSE", "END",
            "NEXTVAL", "CURRVAL", "SYSDATE", "ROWNUM", "LEVEL", "NULL", "TRUE", "FALSE"
    ));

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
            return normalizeParsed(stmt);
        } catch (Exception e) {
            return normalizeFallback(trimmed);
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
            SelectBody body = select.getSelectBody();
            if (body instanceof PlainSelect) {
                normalizePlainSelect((PlainSelect) body);
            } else if (body instanceof SetOperationList) {
                for (SelectBody sub : ((SetOperationList) body).getSelects()) {
                    if (sub instanceof PlainSelect) {
                        normalizePlainSelect((PlainSelect) sub);
                    }
                }
            }
        }
        String raw = stmt.toString();
        return normalizeWhitespaceAndKeywords(raw);
    }

    /**
     * PlainSelect（単一SELECT文）を正規化する。
     * テーブルエイリアスを除去し同一テーブル複数回時は テーブル名_1, テーブル名_2 を付与。
     * 全カラムを テーブル名.カラム名 で修飾し、SELECT項目のエイリアスを除去する。
     *
     * @param plain 正規化対象のPlainSelect
     */
    private void normalizePlainSelect(PlainSelect plain) {
        // 出現順に (キー: エイリアスまたはテーブル名, 正規名) を構築。同一テーブルは 名前, 名前_1, 名前_2
        Map<String, String> aliasToCanonical = new LinkedHashMap<>();
        Map<String, Integer> tableOccurrence = new HashMap<>();
        String defaultTableCanonical = null;

        FromItem from = plain.getFromItem();
        List<Join> joins = plain.getJoins();

        // 再帰用: サブクエリを先に正規化
        if (from != null && from instanceof SubSelect) {
            SelectBody sub = ((SubSelect) from).getSelectBody();
            if (sub instanceof PlainSelect) normalizePlainSelect((PlainSelect) sub);
        }
        if (joins != null) {
            for (Join j : joins) {
                if (j.getRightItem() instanceof SubSelect) {
                    SelectBody sub = ((SubSelect) j.getRightItem()).getSelectBody();
                    if (sub instanceof PlainSelect) normalizePlainSelect((PlainSelect) sub);
                }
            }
        }

        // FROM/JOIN を走査して正規名を決定
        List<FromItem> fromItems = new ArrayList<>();
        if (from != null) fromItems.add(from);
        if (joins != null) {
            for (Join j : joins) {
                if (j.getRightItem() != null) fromItems.add(j.getRightItem());
            }
        }
        for (FromItem item : fromItems) {
            String baseName = getBaseTableName(item);
            int occ = tableOccurrence.getOrDefault(baseName, 0);
            tableOccurrence.put(baseName, occ + 1);
            String canonical = (occ == 0) ? baseName : (baseName + "_" + occ);

            String key = getAliasOrTableName(item);
            if (key != null && !key.isEmpty()) {
                aliasToCanonical.put(key, canonical);
            }
            if (defaultTableCanonical == null) {
                defaultTableCanonical = canonical;
            }
            setFromItemCanonicalAlias(item, canonical);
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
        // 全カラムを テーブル名.カラム名 に統一（エイリアス→正規名、未修飾にはデフォルトテーブル付与）
        ExpressionVisitorAdapter columnQualifier = new ExpressionVisitorAdapter() {
            @Override
            public void visit(Column col) {
                Table t = col.getTable();
                String canonical;
                if (t != null && t.getName() != null && !t.getName().trim().isEmpty()) {
                    String name = t.getName().replace("\"", "").trim();
                    canonical = aliasToCanonical.get(name);
                    if (canonical == null) canonical = name;
                } else {
                    canonical = defaultTable;
                }
                if (canonical != null) {
                    col.setTable(new Table(canonical));
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
     * FROM句の要素からベースのテーブル名（またはサブクエリ時の識別子）を取得する。
     *
     * @param item FROM/JOIN の FromItem
     * @return テーブル名、サブクエリ時は "sub"、それ以外は "t"
     */
    private String getBaseTableName(FromItem item) {
        if (item instanceof Table) {
            return ((Table) item).getName().replace("\"", "").trim();
        }
        if (item instanceof SubSelect) {
            return "sub";
        }
        return "t";
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
     * @param canonical 正規名（テーブル名 または テーブル名_1, テーブル名_2 など）
     */
    private void setFromItemCanonicalAlias(FromItem item, String canonical) {
        item.setAlias(new Alias(canonical, false));
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
        return normalizeWhitespaceAndKeywords(sql);
    }
}
