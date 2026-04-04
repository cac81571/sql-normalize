package com.sqlnormalize;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.OracleHint;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

import java.util.ArrayList;
import java.util.List;

import net.sf.jsqlparser.statement.select.PlainSelect;

/**
 * {@code UPDATE} 文を {@code SET} は行頭カンマ、{@code WHERE} は {@code AND}/{@code OR} 前改行で整形する。
 */
public final class SqlUpdateFormatter {

    private static final String IND = "    ";

    private SqlUpdateFormatter() {}

    @SuppressWarnings("rawtypes")
    public static String formatUpdate(Update update) {
        if (update == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendWith(sb, update);

        sb.append("UPDATE ");
        OracleHint hint = update.getOracleHint();
        if (hint != null) {
            sb.append(hint).append(" ");
        }
        sb.append(update.getTable().toString());

        List startJoins = update.getStartJoins();
        if (startJoins != null) {
            for (Object j : startJoins) {
                sb.append(", ").append(j.toString());
            }
        }
        sb.append("\n");

        appendSetClause(sb, update);
        appendFromAndJoins(sb, update);

        Expression where = update.getWhere();
        if (where != null) {
            sb.append("WHERE\n");
            sb.append(IND);
            SqlA5Formatter.appendExpressionBrokenOnAndOr(sb, where, IND);
            sb.append("\n");
        }

        List orderBy = update.getOrderByElements();
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

        Limit limit = update.getLimit();
        if (limit != null) {
            sb.append(limit.toString()).append("\n");
        }

        appendReturning(sb, update);

        return sb.toString().trim();
    }

    @SuppressWarnings("rawtypes")
    private static void appendWith(StringBuilder sb, Update update) {
        List withList = update.getWithItemsList();
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

    @SuppressWarnings("rawtypes")
    private static void appendSetClause(StringBuilder sb, Update update) {
        ArrayList updateSets = update.getUpdateSets();
        if (updateSets == null || updateSets.isEmpty()) {
            return;
        }
        sb.append("SET\n");
        boolean firstAssign = true;
        for (Object o : updateSets) {
            UpdateSet us = (UpdateSet) o;
            firstAssign = appendUpdateSetAssignments(sb, us, firstAssign);
        }
    }

    /** @return 続く {@link UpdateSet} 用の「先頭の代入行かどうか」（先頭なら {@code true}） */
    @SuppressWarnings("rawtypes")
    private static boolean appendUpdateSetAssignments(StringBuilder sb, UpdateSet us, boolean firstAssign) {
        List cols = us.getColumns();
        List exprs = us.getExpressions();
        if (cols == null || exprs == null || cols.isEmpty()) {
            return firstAssign;
        }

        if (us.isUsingBracketsForColumns() || us.isUsingBracketsForValues()) {
            sb.append(IND);
            if (!firstAssign) {
                sb.append(", ");
            }
            if (us.isUsingBracketsForColumns()) {
                sb.append("(");
            }
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(cols.get(i).toString());
            }
            if (us.isUsingBracketsForColumns()) {
                sb.append(")");
            }
            sb.append(" = ");
            if (us.isUsingBracketsForValues()) {
                sb.append("(");
            }
            for (int i = 0; i < exprs.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(exprs.get(i).toString());
            }
            if (us.isUsingBracketsForValues()) {
                sb.append(")");
            }
            sb.append("\n");
            return false;
        }

        int n = Math.min(cols.size(), exprs.size());
        for (int i = 0; i < n; i++) {
            sb.append(IND);
            if (!firstAssign) {
                sb.append(", ");
            }
            firstAssign = false;
            sb.append(cols.get(i).toString()).append(" = ").append(exprs.get(i).toString());
            sb.append("\n");
        }
        return firstAssign;
    }

    @SuppressWarnings("rawtypes")
    private static void appendFromAndJoins(StringBuilder sb, Update update) {
        FromItem fromItem = update.getFromItem();
        if (fromItem == null) {
            return;
        }
        sb.append("FROM\n");
        sb.append(IND).append(fromItem.toString()).append("\n");
        List joins = update.getJoins();
        if (joins != null) {
            for (Object j : joins) {
                SqlA5Formatter.appendJoin(sb, (Join) j, "");
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private static void appendReturning(StringBuilder sb, Update update) {
        if (update.isReturningAllColumns()) {
            sb.append("\nRETURNING *");
            return;
        }
        List ret = update.getReturningExpressionList();
        if (ret == null || ret.isEmpty()) {
            return;
        }
        sb.append("\nRETURNING ").append(PlainSelect.getStringList(ret, true, false));
    }
}
