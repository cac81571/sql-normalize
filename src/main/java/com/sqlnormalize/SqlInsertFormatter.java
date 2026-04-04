package com.sqlnormalize;

import net.sf.jsqlparser.expression.OracleHint;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.MultiExpressionList;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.List;

/**
 * {@code INSERT} 文を、列リスト・{@code VALUES} ともに行頭カンマ（{@code , }）で複行整形する。
 */
public final class SqlInsertFormatter {

    private static final String IND = "    ";

    private SqlInsertFormatter() {}

    /**
     * パース済み {@link Insert} を整形する（キーワード大文字化は呼び出し側で行う想定）。
     */
    @SuppressWarnings("rawtypes")
    public static String formatInsert(Insert insert) {
        if (insert == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendWith(sb, insert);

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
            sb.append("( \n");
            appendLeadingCommaLines(sb, columns, IND);
            sb.append(") \n");
        } else {
            sb.append("\n");
        }

        if (insert.isUseSet()) {
            appendSetClause(sb, insert);
        } else if (insert.isUseValues() && insert.getItemsList() instanceof ExpressionList) {
            appendSingleValues(sb, (ExpressionList) insert.getItemsList());
        } else if (insert.isUseValues() && insert.getItemsList() instanceof MultiExpressionList) {
            appendMultiValues(sb, (MultiExpressionList) insert.getItemsList());
        } else if (insert.getSelect() != null) {
            appendInsertSelect(sb, insert.getSelect(), insert.isUseSelectBrackets());
        } else if (insert.getItemsList() != null) {
            sb.append(insert.getItemsList().toString());
        }

        appendDuplicate(sb, insert);
        appendReturning(sb, insert);

        return sb.toString().trim();
    }

    @SuppressWarnings("rawtypes")
    private static void appendWith(StringBuilder sb, Insert insert) {
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
            SqlA5Formatter.appendSelectBody(sb, wb, IND + IND);
            sb.append("\n").append(IND).append(")");
        }
        sb.append("\n");
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
    private static void appendSingleValues(StringBuilder sb, ExpressionList el) {
        List exps = el.getExpressions();
        sb.append("VALUES ( \n");
        appendValueLines(sb, exps, IND);
        sb.append(")");
    }

    @SuppressWarnings("rawtypes")
    private static void appendMultiValues(StringBuilder sb, MultiExpressionList mel) {
        List rows = mel.getExpressionLists();
        if (rows == null || rows.isEmpty()) {
            sb.append("VALUES ()");
            return;
        }
        sb.append("VALUES ( \n");
        ExpressionList first = (ExpressionList) rows.get(0);
        appendValueLines(sb, first.getExpressions(), IND);
        sb.append(")\n");
        for (int r = 1; r < rows.size(); r++) {
            sb.append(IND).append(", (\n");
            ExpressionList row = (ExpressionList) rows.get(r);
            appendValueLines(sb, row.getExpressions(), IND);
            sb.append(")\n");
        }
    }

    /** {@code VALUES} 内の式を行頭カンマ付きで 1 式 1 行に並べる。 */
    @SuppressWarnings("rawtypes")
    private static void appendValueLines(StringBuilder sb, List expressions, String indent) {
        if (expressions == null) {
            return;
        }
        int n = expressions.size();
        for (int i = 0; i < n; i++) {
            sb.append(indent);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(expressions.get(i).toString());
            sb.append("\n");
        }
    }

    private static void appendInsertSelect(StringBuilder sb, Select select, boolean useBrackets) {
        if (useBrackets) {
            sb.append("(\n");
        }
        String a5 = SqlA5Formatter.formatSelect(select);
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
    private static void appendSetClause(StringBuilder sb, Insert insert) {
        List cols = insert.getSetColumns();
        List exprs = insert.getSetExpressionList();
        if (cols == null || exprs == null || cols.isEmpty()) {
            return;
        }
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
    }

    @SuppressWarnings("rawtypes")
    private static void appendDuplicate(StringBuilder sb, Insert insert) {
        if (!insert.isUseDuplicate()) {
            return;
        }
        List dupCols = insert.getDuplicateUpdateColumns();
        List dupExprs = insert.getDuplicateUpdateExpressionList();
        if (dupCols == null || dupExprs == null || dupCols.isEmpty()) {
            return;
        }
        sb.append("\nON DUPLICATE KEY UPDATE\n");
        for (int i = 0; i < dupCols.size(); i++) {
            sb.append(IND);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(dupCols.get(i).toString()).append(" = ").append(dupExprs.get(i).toString());
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
