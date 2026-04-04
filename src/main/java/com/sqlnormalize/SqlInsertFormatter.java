package com.sqlnormalize;

import net.sf.jsqlparser.expression.OracleHint;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.MultiExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.List;

/**
 * {@code INSERT} 文の整形。
 * <p>
 * 正規化用では列リスト・{@code VALUES} 内の式を {@code 式, 式, …} で 1 行。
 * 整形ペイン用（{@link #formatInsert(Insert, boolean, boolean)} の第 3 引数 {@code true}）では列リストと {@code VALUES} 内の式を行頭 {@code , } で複行にする。
 * </p>
 */
public final class SqlInsertFormatter {

    private static final String IND = "    ";

    private SqlInsertFormatter() {}

    /**
     * パース済み {@link Insert} を整形する（キーワード大文字化は呼び出し側で行う想定）。
     * {@code VALUES} 各式の直後の列名コメントは付けない（正規化・コピー用）。
     */
    public static String formatInsert(Insert insert) {
        return formatInsert(insert, false, false);
    }

    /**
     * @param valueColumnComments {@code true} かつ列リストと式の個数が一致するとき、{@code VALUES} 各式の直後に列名のブロックコメントを付ける
     */
    public static String formatInsert(Insert insert, boolean valueColumnComments) {
        return formatInsert(insert, valueColumnComments, false);
    }

    /**
     * @param leadingCommaLists {@code true} のとき列リストと {@code VALUES} 各式を行頭カンマ付きで複行（整形ペイン用）
     */
    @SuppressWarnings("rawtypes")
    public static String formatInsert(Insert insert, boolean valueColumnComments, boolean leadingCommaLists) {
        if (insert == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendWith(sb, insert, leadingCommaLists);

        sb.append("INSERT");
        OracleHint hint = insert.getOracleHint();
        if (hint != null) {
            sb.append(" ").append(hint);
        }
        if (insert.getModifierPriority() != null) {
            sb.append(" ").append(insert.getModifierPriority().name());
        }
        if (insert.isModifierIgnore()) {
            sb.append(" IGNORE");
        }
        sb.append("\nINTO ");
        sb.append(insert.getTable().toString());

        List columns = insert.getColumns();
        if (columns != null && !columns.isEmpty()) {
            if (leadingCommaLists) {
                sb.append("( \n");
                appendLeadingCommaLines(sb, columns, IND);
                sb.append(") \n");
            } else {
                sb.append(" (");
                appendCommaSeparated(sb, columns);
                sb.append(")\n");
            }
        } else {
            sb.append("\n");
        }

        if (insert.isUseSet()) {
            appendSetClause(sb, insert, leadingCommaLists);
        } else if (insert.isUseValues() && insert.getItemsList() instanceof ExpressionList) {
            appendSingleValues(sb, (ExpressionList) insert.getItemsList(), columns, valueColumnComments, leadingCommaLists);
        } else if (insert.isUseValues() && insert.getItemsList() instanceof MultiExpressionList) {
            appendMultiValues(sb, (MultiExpressionList) insert.getItemsList(), columns, valueColumnComments, leadingCommaLists);
        } else if (insert.getSelect() != null) {
            appendInsertSelect(sb, insert.getSelect(), insert.isUseSelectBrackets(), leadingCommaLists);
        } else if (insert.getItemsList() != null) {
            sb.append(insert.getItemsList().toString());
        }

        appendDuplicate(sb, insert, leadingCommaLists);
        appendReturning(sb, insert);

        return sb.toString().trim();
    }

    @SuppressWarnings("rawtypes")
    private static void appendWith(StringBuilder sb, Insert insert, boolean leadingCommaLists) {
        List withList = insert.getWithItemsList();
        if (withList == null || withList.isEmpty()) {
            return;
        }
        sb.append("WITH");
        for (int i = 0; i < withList.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\n");
            WithItem w = (WithItem) withList.get(i);
            sb.append(IND).append(w.getName()).append(" AS (\n");
            SelectBody wb = w.getSubSelect().getSelectBody();
            SqlA5Formatter.appendSelectBody(sb, wb, IND + IND, leadingCommaLists);
            sb.append("\n").append(IND).append(")");
        }
        sb.append("\n");
    }

    private static void appendCommaSeparated(StringBuilder sb, List<?> items) {
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(items.get(i).toString());
        }
    }

    private static void appendLeadingCommaLines(StringBuilder sb, List<?> items, String indent) {
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            sb.append(indent);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(items.get(i).toString());
            sb.append("\n");
        }
    }

    @SuppressWarnings("rawtypes")
    private static void appendSingleValues(StringBuilder sb, ExpressionList el, List columns, boolean valueColumnComments,
            boolean leadingCommaLists) {
        List exps = el.getExpressions();
        if (leadingCommaLists) {
            sb.append("VALUES ( \n");
            appendValueLines(sb, exps, columns, valueColumnComments, IND);
            sb.append(")");
        } else {
            sb.append("VALUES (");
            appendValueExpressionsCommaSeparated(sb, exps, columns, valueColumnComments);
            sb.append(")");
        }
    }

    @SuppressWarnings("rawtypes")
    private static void appendMultiValues(StringBuilder sb, MultiExpressionList mel, List columns, boolean valueColumnComments,
            boolean leadingCommaLists) {
        List rows = mel.getExpressionLists();
        if (rows == null || rows.isEmpty()) {
            sb.append("VALUES ()");
            return;
        }
        if (leadingCommaLists) {
            sb.append("VALUES ( \n");
            ExpressionList first = (ExpressionList) rows.get(0);
            appendValueLines(sb, first.getExpressions(), columns, valueColumnComments, IND);
            sb.append(")\n");
            for (int r = 1; r < rows.size(); r++) {
                sb.append(IND).append(", (\n");
                ExpressionList row = (ExpressionList) rows.get(r);
                appendValueLines(sb, row.getExpressions(), columns, valueColumnComments, IND);
                sb.append(")\n");
            }
        } else {
            sb.append("VALUES ");
            for (int r = 0; r < rows.size(); r++) {
                if (r > 0) {
                    sb.append(",\n").append(IND);
                }
                sb.append("(");
                ExpressionList row = (ExpressionList) rows.get(r);
                appendValueExpressionsCommaSeparated(sb, row.getExpressions(), columns, valueColumnComments);
                sb.append(")");
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private static void appendValueLines(StringBuilder sb, List expressions, List columns, boolean valueColumnComments, String indent) {
        if (expressions == null) {
            return;
        }
        int n = expressions.size();
        boolean useComments = valueColumnComments && columns != null && columns.size() == n;
        for (int i = 0; i < n; i++) {
            sb.append(indent);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(expressions.get(i).toString());
            if (useComments) {
                sb.append(" /* ").append(columnLabelForComment(columns.get(i))).append(" */");
            }
            sb.append("\n");
        }
    }

    @SuppressWarnings("rawtypes")
    private static void appendValueExpressionsCommaSeparated(StringBuilder sb, List expressions, List columns,
            boolean valueColumnComments) {
        if (expressions == null) {
            return;
        }
        int n = expressions.size();
        boolean useComments = valueColumnComments && columns != null && columns.size() == n;
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(expressions.get(i).toString());
            if (useComments) {
                sb.append(" /* ").append(columnLabelForComment(columns.get(i))).append(" */");
            }
        }
    }

    private static String columnLabelForComment(Object colObj) {
        if (colObj instanceof Column) {
            Column c = (Column) colObj;
            String n = c.getColumnName();
            if (n != null) {
                return n.replace("\"", "").trim();
            }
        }
        return colObj != null ? colObj.toString() : "";
    }

    private static void appendInsertSelect(StringBuilder sb, Select select, boolean useBrackets, boolean leadingCommaLists) {
        if (useBrackets) {
            sb.append("(\n");
        }
        String a5 = SqlA5Formatter.formatSelect(select, leadingCommaLists);
        if (a5 == null) {
            a5 = select.toString();
        }
        for (String line : a5.split("\\R", -1)) {
            sb.append(IND).append(line).append("\n");
        }
        if (useBrackets) {
            sb.append(")");
        }
    }

    @SuppressWarnings("rawtypes")
    private static void appendSetClause(StringBuilder sb, Insert insert, boolean leadingCommaLists) {
        List cols = insert.getSetColumns();
        List exprs = insert.getSetExpressionList();
        if (cols == null || exprs == null || cols.isEmpty()) {
            return;
        }
        if (leadingCommaLists) {
            sb.append("SET\n");
            for (int i = 0; i < cols.size(); i++) {
                sb.append(IND);
                if (i > 0) {
                    sb.append(", ");
                }
                Object c = cols.get(i);
                Object e = exprs.get(i);
                sb.append(c != null ? c.toString() : "").append(" = ").append(e != null ? e.toString() : "");
                sb.append("\n");
            }
        } else {
            sb.append("SET ");
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                Object c = cols.get(i);
                Object e = exprs.get(i);
                sb.append(c != null ? c.toString() : "").append(" = ").append(e != null ? e.toString() : "");
            }
            sb.append("\n");
        }
    }

    @SuppressWarnings("rawtypes")
    private static void appendDuplicate(StringBuilder sb, Insert insert, boolean leadingCommaLists) {
        if (!insert.isUseDuplicate()) {
            return;
        }
        List dupCols = insert.getDuplicateUpdateColumns();
        List dupExprs = insert.getDuplicateUpdateExpressionList();
        if (dupCols == null || dupExprs == null || dupCols.isEmpty()) {
            return;
        }
        if (leadingCommaLists) {
            sb.append("\nON DUPLICATE KEY UPDATE\n");
            for (int i = 0; i < dupCols.size(); i++) {
                sb.append(IND);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(dupCols.get(i).toString()).append(" = ").append(dupExprs.get(i).toString());
                sb.append("\n");
            }
        } else {
            sb.append("\nON DUPLICATE KEY UPDATE ");
            for (int i = 0; i < dupCols.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(dupCols.get(i).toString()).append(" = ").append(dupExprs.get(i).toString());
            }
            sb.append("\n");
        }
    }

    @SuppressWarnings("rawtypes")
    private static void appendReturning(StringBuilder sb, Insert insert) {
        if (insert.isReturningAllColumns()) {
            sb.append("\nRETURNING *");
            return;
        }
        List ret = insert.getReturningExpressionList();
        if (ret == null || ret.isEmpty()) {
            return;
        }
        sb.append("\nRETURNING ");
        for (int i = 0; i < ret.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ret.get(i).toString());
        }
    }
}
