package com.sqlnormalize;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.OracleHint;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.List;

/**
 * {@code DELETE} 文を {@code DELETE} / {@code FROM} / 表名を改行で分け、{@code WHERE} は {@code AND}/{@code OR} 前改行で整形する。
 */
public final class SqlDeleteFormatter {

    private static final String IND = "    ";

    private SqlDeleteFormatter() {}

    @SuppressWarnings("rawtypes")
    public static String formatDelete(Delete delete) {
        if (delete == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendWith(sb, delete);

        sb.append("DELETE");
        OracleHint hint = delete.getOracleHint();
        if (hint != null) {
            sb.append(" ").append(hint);
        }
        sb.append("\n");

        List tables = delete.getTables();
        if (tables != null && !tables.isEmpty()) {
            for (int i = 0; i < tables.size(); i++) {
                sb.append(IND);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(tables.get(i).toString());
                sb.append("\n");
            }
        }

        if (delete.isHasFrom()) {
            sb.append("FROM\n");
            if (delete.getTable() != null) {
                sb.append(IND).append(delete.getTable().toString()).append("\n");
            }
        } else if (delete.getTable() != null) {
            sb.append(IND).append(delete.getTable().toString()).append("\n");
        }

        List usingList = delete.getUsingList();
        if (usingList != null && !usingList.isEmpty()) {
            sb.append("USING\n");
            for (int i = 0; i < usingList.size(); i++) {
                sb.append(IND);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(usingList.get(i).toString());
                sb.append("\n");
            }
        }

        List joins = delete.getJoins();
        if (joins != null) {
            for (Object j : joins) {
                SqlA5Formatter.appendJoin(sb, (Join) j, "");
            }
        }

        Expression where = delete.getWhere();
        if (where != null) {
            sb.append("WHERE\n");
            sb.append(IND);
            SqlA5Formatter.appendExpressionBrokenOnAndOr(sb, where, IND);
            sb.append("\n");
        }

        List orderBy = delete.getOrderByElements();
        if (orderBy != null && !orderBy.isEmpty()) {
            sb.append("ORDER BY\n");
            for (int i = 0; i < orderBy.size(); i++) {
                sb.append(IND);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(((OrderByElement) orderBy.get(i)).toString());
                sb.append("\n");
            }
        }

        Limit limit = delete.getLimit();
        if (limit != null) {
            sb.append(limit.toString()).append("\n");
        }

        return sb.toString().trim();
    }

    @SuppressWarnings("rawtypes")
    private static void appendWith(StringBuilder sb, Delete delete) {
        List withList = delete.getWithItemsList();
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
}
