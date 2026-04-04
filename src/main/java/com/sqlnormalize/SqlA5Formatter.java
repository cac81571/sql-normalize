package com.sqlnormalize;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;

import java.util.List;


/**
 * A5:SQL Mk-2 に近い読みやすさを目指した SQL 整形（SELECT 中心）。
 * <p>
 * 正規化用（{@link #formatSelect(Statement)}）では SELECT / GROUP BY / ORDER BY の列リストを {@code 式, 式, …} と 1 行に並べる。
 * 整形ペイン用（{@link #formatSelect(Statement, boolean)} で {@code true}）では行頭 {@code , } ＋改行＋インデントで複行にする。
 * FROM/JOIN/WHERE を句ごとに改行、WHERE / HAVING では {@code AND}・{@code OR} の前で改行する。
 * {@code EXISTS} / {@code IN (SELECT …)} 内の {@link SubSelect} は {@link #appendSelectBody} で複行整形する。
 * </p>
 */
public final class SqlA5Formatter {

    private static final String IND = "    ";

    private SqlA5Formatter() {}

    /**
     * Statement を A5 風に整形する。SELECT 以外は null を返す（呼び出し側で従来の整形にフォールバック）。
     * 列リストは {@code 式, 式} で 1 行（正規化・グリッド用）。
     */
    public static String formatSelect(Statement stmt) {
        return formatSelect(stmt, false);
    }

    /**
     * @param leadingCommaItemLists {@code true} のとき SELECT / GROUP BY / ORDER BY の要素を行頭カンマ付きで複行にする（整形ペイン用）
     */
    public static String formatSelect(Statement stmt, boolean leadingCommaItemLists) {
        if (!(stmt instanceof Select)) {
            return null;
        }
        Select select = (Select) stmt;
        StringBuilder sb = new StringBuilder();
        List<WithItem> withList = select.getWithItemsList();
        if (withList != null && !withList.isEmpty()) {
            sb.append("WITH");
            for (int i = 0; i < withList.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append("\n");
                WithItem w = withList.get(i);
                sb.append(IND).append(w.getName()).append(" AS (\n");
                SelectBody wb = w.getSubSelect().getSelectBody();
                appendSelectBody(sb, wb, IND + IND, leadingCommaItemLists);
                sb.append("\n").append(IND).append(")");
            }
            sb.append("\n");
        }
        appendSelectBody(sb, select.getSelectBody(), "", leadingCommaItemLists);
        return sb.toString().trim();
    }

    /** INSERT の WITH 内サブクエリなどからも利用する（列リストは 1 行）。 */
    static void appendSelectBody(StringBuilder sb, SelectBody body, String prefix) {
        appendSelectBody(sb, body, prefix, false);
    }

    static void appendSelectBody(StringBuilder sb, SelectBody body, String prefix, boolean leadingCommaItemLists) {
        if (body instanceof PlainSelect) {
            appendPlainSelect(sb, (PlainSelect) body, prefix, leadingCommaItemLists);
        } else if (body instanceof SetOperationList) {
            SetOperationList list = (SetOperationList) body;
            List<SelectBody> selects = list.getSelects();
            List<SetOperation> ops = list.getOperations();
            for (int i = 0; i < selects.size(); i++) {
                if (i > 0) {
                    SetOperation op = ops.get(i - 1);
                    sb.append("\n").append(op.toString()).append("\n");
                }
                appendSelectBody(sb, selects.get(i), prefix, leadingCommaItemLists);
            }
        } else {
            sb.append(prefix).append(body.toString());
        }
    }

    private static void appendPlainSelect(StringBuilder sb, PlainSelect plain, String prefix, boolean leadingComma) {
        sb.append(prefix).append("SELECT");
        if (plain.getDistinct() != null) {
            sb.append(" ").append(plain.getDistinct());
        }
        List<SelectItem> items = plain.getSelectItems();
        if (items != null && !items.isEmpty()) {
            if (leadingComma) {
                sb.append("\n");
                for (int i = 0; i < items.size(); i++) {
                    sb.append(prefix).append(IND);
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(items.get(i).toString());
                    sb.append("\n");
                }
            } else {
                sb.append(" ");
                for (int i = 0; i < items.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(items.get(i).toString());
                }
                sb.append("\n");
            }
        } else {
            sb.append("\n");
        }

        FromItem from = plain.getFromItem();
        if (from != null) {
            sb.append(prefix).append("FROM\n");
            sb.append(prefix).append(IND).append(fromToString(from, leadingComma)).append("\n");
        }

        List<Join> joins = plain.getJoins();
        if (joins != null) {
            for (Join j : joins) {
                appendJoin(sb, j, prefix, leadingComma);
            }
        }

        Expression where = plain.getWhere();
        if (where != null) {
            sb.append(prefix).append("WHERE\n");
            appendWhereConditions(sb, where, prefix + IND, leadingComma);
        }

        if (plain.getGroupBy() != null) {
            sb.append(prefix).append("GROUP BY");
            List<Expression> exps = plain.getGroupBy().getGroupByExpressions();
            if (exps != null && !exps.isEmpty()) {
                if (leadingComma) {
                    sb.append("\n");
                    for (int i = 0; i < exps.size(); i++) {
                        sb.append(prefix).append(IND);
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(exps.get(i).toString());
                        sb.append("\n");
                    }
                } else {
                    sb.append(" ");
                    for (int i = 0; i < exps.size(); i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(exps.get(i).toString());
                    }
                    sb.append("\n");
                }
            } else {
                sb.append("\n");
            }
        }

        Expression having = plain.getHaving();
        if (having != null) {
            sb.append(prefix).append("HAVING\n");
            sb.append(prefix).append(IND);
            appendExpressionBrokenOnAndOr(sb, having, prefix + IND, leadingComma);
            sb.append("\n");
        }

        List<OrderByElement> orderBy = plain.getOrderByElements();
        if (orderBy != null && !orderBy.isEmpty()) {
            sb.append(prefix).append("ORDER BY");
            if (leadingComma) {
                sb.append("\n");
                for (int i = 0; i < orderBy.size(); i++) {
                    sb.append(prefix).append(IND);
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(orderBy.get(i).toString());
                    sb.append("\n");
                }
            } else {
                sb.append(" ");
                for (int i = 0; i < orderBy.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(orderBy.get(i).toString());
                }
                sb.append("\n");
            }
        }

        if (plain.getLimit() != null) {
            sb.append(prefix).append(plain.getLimit().toString()).append("\n");
        }
        if (plain.getOffset() != null) {
            sb.append(prefix).append(plain.getOffset().toString()).append("\n");
        }
        if (plain.getFetch() != null) {
            sb.append(prefix).append(plain.getFetch().toString()).append("\n");
        }
    }

    private static String fromToString(FromItem from, boolean leadingComma) {
        if (from instanceof SubSelect) {
            SubSelect ss = (SubSelect) from;
            StringBuilder inner = new StringBuilder();
            inner.append("(\n");
            appendSelectBody(inner, ss.getSelectBody(), IND, leadingComma);
            inner.append("\n) ");
            if (ss.getAlias() != null) {
                inner.append(ss.getAlias().toString());
            }
            return inner.toString().trim();
        }
        return from.toString();
    }

    /** {@code UPDATE … FROM} の {@code JOIN} 行でも利用する。 */
    static void appendJoin(StringBuilder sb, Join j, String prefix) {
        appendJoin(sb, j, prefix, false);
    }

    static void appendJoin(StringBuilder sb, Join j, String prefix, boolean leadingCommaSelectItems) {
        sb.append(prefix).append(IND).append(joinTypeName(j)).append(" ");
        sb.append(j.getRightItem().toString());
        sb.append("\n");
        Expression on = j.getOnExpression();
        if (on != null) {
            String onHead = prefix + IND + IND;
            sb.append(onHead).append("ON ");
            appendExpressionBrokenOnAndOr(sb, on, onHead, leadingCommaSelectItems);
            sb.append("\n");
        }
    }

    private static String joinTypeName(Join join) {
        if (join.isCross()) {
            return "CROSS JOIN";
        }
        if (join.isNatural()) {
            if (join.isLeft()) {
                return "NATURAL LEFT JOIN";
            }
            if (join.isRight()) {
                return "NATURAL RIGHT JOIN";
            }
            return "NATURAL JOIN";
        }
        if (join.isFull()) {
            return "FULL OUTER JOIN";
        }
        if (join.isRight()) {
            return join.isOuter() ? "RIGHT OUTER JOIN" : "RIGHT JOIN";
        }
        if (join.isLeft()) {
            return join.isOuter() ? "LEFT OUTER JOIN" : "LEFT JOIN";
        }
        if (join.isOuter()) {
            return "OUTER JOIN";
        }
        return "INNER JOIN";
    }

    private static void appendWhereConditions(StringBuilder sb, Expression where, String condIndent, boolean leadingComma) {
        sb.append(condIndent);
        appendExpressionBrokenOnAndOr(sb, where, condIndent, leadingComma);
        sb.append("\n");
    }

    /**
     * 式木をたどり、{@code AND} / {@code OR} の直前で改行する。
     */
    static void appendExpressionBrokenOnAndOr(StringBuilder sb, Expression e, String contIndent) {
        appendExpressionBrokenOnAndOr(sb, e, contIndent, false);
    }

    static void appendExpressionBrokenOnAndOr(StringBuilder sb, Expression e, String contIndent, boolean leadingCommaSelectItems) {
        if (e instanceof AndExpression) {
            AndExpression a = (AndExpression) e;
            appendExpressionBrokenOnAndOr(sb, a.getLeftExpression(), contIndent, leadingCommaSelectItems);
            sb.append("\n").append(contIndent).append("AND ");
            appendExpressionBrokenOnAndOr(sb, a.getRightExpression(), contIndent, leadingCommaSelectItems);
        } else if (e instanceof OrExpression) {
            OrExpression o = (OrExpression) e;
            appendExpressionBrokenOnAndOr(sb, o.getLeftExpression(), contIndent, leadingCommaSelectItems);
            sb.append("\n").append(contIndent).append("OR ");
            appendExpressionBrokenOnAndOr(sb, o.getRightExpression(), contIndent, leadingCommaSelectItems);
        } else if (e instanceof Parenthesis) {
            Parenthesis p = (Parenthesis) e;
            Expression inner = p.getExpression();
            if (inner == null) {
                sb.append("()");
                return;
            }
            if (inner instanceof AndExpression || inner instanceof OrExpression) {
                sb.append("(\n").append(contIndent + IND);
                appendExpressionBrokenOnAndOr(sb, inner, contIndent + IND, leadingCommaSelectItems);
                sb.append("\n").append(contIndent).append(")");
            } else {
                SubSelect ss = unwrapToSubSelect(inner);
                if (ss != null) {
                    appendBracketedSubSelect(sb, ss, contIndent, leadingCommaSelectItems);
                } else {
                    sb.append("(").append(inner.toString()).append(")");
                }
            }
        } else if (e instanceof ExistsExpression) {
            ExistsExpression ex = (ExistsExpression) e;
            sb.append(ex.isNot() ? "NOT EXISTS " : "EXISTS ");
            appendFormattedSubSelectFromExpr(sb, ex.getRightExpression(), contIndent, leadingCommaSelectItems);
        } else if (e instanceof InExpression) {
            InExpression in = (InExpression) e;
            if (in.getLeftExpression() != null) {
                appendExpressionBrokenOnAndOr(sb, in.getLeftExpression(), contIndent, leadingCommaSelectItems);
            }
            sb.append(in.isNot() ? " NOT IN " : " IN ");
            SubSelect inSs = null;
            if (in.getRightExpression() != null) {
                inSs = unwrapToSubSelect(in.getRightExpression());
            }
            if (inSs == null && in.getRightItemsList() instanceof SubSelect) {
                inSs = (SubSelect) in.getRightItemsList();
            }
            if (inSs != null) {
                appendBracketedSubSelect(sb, inSs, contIndent, leadingCommaSelectItems);
            } else if (in.getRightItemsList() != null) {
                sb.append(in.getRightItemsList().toString());
            } else if (in.getRightExpression() != null) {
                sb.append(in.getRightExpression().toString());
            } else {
                sb.append("()");
            }
        } else if (e instanceof NotExpression) {
            NotExpression ne = (NotExpression) e;
            sb.append("NOT ");
            appendExpressionBrokenOnAndOr(sb, ne.getExpression(), contIndent, leadingCommaSelectItems);
        } else if (e instanceof SubSelect) {
            appendBracketedSubSelect(sb, (SubSelect) e, contIndent, leadingCommaSelectItems);
        } else {
            sb.append(e.toString());
        }
    }

    private static void appendBracketedSubSelect(StringBuilder sb, SubSelect ss, String contIndent, boolean leadingComma) {
        if (ss == null || ss.getSelectBody() == null) {
            sb.append("()");
            return;
        }
        String innerPrefix = contIndent + IND;
        sb.append("(\n");
        appendSelectBody(sb, ss.getSelectBody(), innerPrefix, leadingComma);
        sb.append("\n").append(contIndent).append(")");
    }

    private static void appendFormattedSubSelectFromExpr(StringBuilder sb, Expression expr, String contIndent, boolean leadingComma) {
        SubSelect ss = unwrapToSubSelect(expr);
        if (ss != null) {
            appendBracketedSubSelect(sb, ss, contIndent, leadingComma);
        } else if (expr != null) {
            sb.append(expr.toString());
        }
    }

    private static SubSelect unwrapToSubSelect(Expression expr) {
        if (expr instanceof SubSelect) {
            return (SubSelect) expr;
        }
        if (expr instanceof Parenthesis) {
            Parenthesis p = (Parenthesis) expr;
            if (p.getExpression() == null) {
                return null;
            }
            return unwrapToSubSelect(p.getExpression());
        }
        return null;
    }
}
