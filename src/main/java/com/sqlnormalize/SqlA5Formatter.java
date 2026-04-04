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
 * 方針: SELECT リストは列ごとに改行し、2 列目以降は行頭に {@code , }（カンマ＋半角空白）を付ける。GROUP BY / ORDER BY のカンマ区切りも同様。FROM/JOIN/WHERE を句ごとに改行、
 * WHERE / HAVING では式木上の {@code AND}・{@code OR} の前で改行する。JOIN の {@code ON} 句も同様だが、{@code AND}/{@code OR} 行は {@code ON} と同じインデントに揃える。
 * {@code EXISTS} / {@code IN (SELECT …)} 内の {@link SubSelect} は {@link #appendSelectBody} で複行整形する。
 * </p>
 */
public final class SqlA5Formatter {

    private static final String IND = "    ";

    private SqlA5Formatter() {}

    /**
     * Statement を A5 風に整形する。SELECT 以外は null を返す（呼び出し側で従来の整形にフォールバック）。
     */
    public static String formatSelect(Statement stmt) {
        if (!(stmt instanceof Select)) {
            return null;
        }
        Select select = (Select) stmt;
        StringBuilder sb = new StringBuilder();
        List<WithItem> withList = select.getWithItemsList();
        if (withList != null && !withList.isEmpty()) {
            sb.append("WITH");
            for (int i = 0; i < withList.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\n");
                WithItem w = withList.get(i);
                sb.append(IND).append(w.getName()).append(" AS (\n");
                SelectBody wb = w.getSubSelect().getSelectBody();
                appendSelectBody(sb, wb, IND + IND);
                sb.append("\n").append(IND).append(")");
            }
            sb.append("\n");
        }
        appendSelectBody(sb, select.getSelectBody(), "");
        return sb.toString().trim();
    }

    /** INSERT の WITH 内サブクエリなどからも利用する。 */
    static void appendSelectBody(StringBuilder sb, SelectBody body, String prefix) {
        if (body instanceof PlainSelect) {
            appendPlainSelect(sb, (PlainSelect) body, prefix);
        } else if (body instanceof SetOperationList) {
            SetOperationList list = (SetOperationList) body;
            List<SelectBody> selects = list.getSelects();
            List<SetOperation> ops = list.getOperations();
            for (int i = 0; i < selects.size(); i++) {
                if (i > 0) {
                    SetOperation op = ops.get(i - 1);
                    sb.append("\n").append(op.toString()).append("\n");
                }
                appendSelectBody(sb, selects.get(i), prefix);
            }
        } else {
            sb.append(prefix).append(body.toString());
        }
    }

    private static void appendPlainSelect(StringBuilder sb, PlainSelect plain, String prefix) {
        sb.append(prefix).append("SELECT");
        if (plain.getDistinct() != null) {
            sb.append(" ").append(plain.getDistinct());
        }
        sb.append("\n");

        List<SelectItem> items = plain.getSelectItems();
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                sb.append(prefix).append(IND);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(items.get(i).toString());
                sb.append("\n");
            }
        }

        FromItem from = plain.getFromItem();
        if (from != null) {
            sb.append(prefix).append("FROM\n");
            sb.append(prefix).append(IND).append(fromToString(from)).append("\n");
        }

        List<Join> joins = plain.getJoins();
        if (joins != null) {
            for (Join j : joins) {
                appendJoin(sb, j, prefix);
            }
        }

        Expression where = plain.getWhere();
        if (where != null) {
            sb.append(prefix).append("WHERE\n");
            appendWhereConditions(sb, where, prefix + IND);
        }

        if (plain.getGroupBy() != null) {
            sb.append(prefix).append("GROUP BY\n");
            List<Expression> exps = plain.getGroupBy().getGroupByExpressions();
            if (exps != null) {
                for (int i = 0; i < exps.size(); i++) {
                    sb.append(prefix).append(IND);
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(exps.get(i).toString());
                    sb.append("\n");
                }
            }
        }

        Expression having = plain.getHaving();
        if (having != null) {
            sb.append(prefix).append("HAVING\n");
            sb.append(prefix).append(IND);
            appendExpressionBrokenOnAndOr(sb, having, prefix + IND);
            sb.append("\n");
        }

        List<OrderByElement> orderBy = plain.getOrderByElements();
        if (orderBy != null && !orderBy.isEmpty()) {
            sb.append(prefix).append("ORDER BY\n");
            for (int i = 0; i < orderBy.size(); i++) {
                sb.append(prefix).append(IND);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(orderBy.get(i).toString());
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

    private static String fromToString(FromItem from) {
        if (from instanceof SubSelect) {
            SubSelect ss = (SubSelect) from;
            StringBuilder inner = new StringBuilder();
            inner.append("(\n");
            appendSelectBody(inner, ss.getSelectBody(), IND);
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
        sb.append(prefix).append(IND).append(joinTypeName(j)).append(" ");
        sb.append(j.getRightItem().toString());
        sb.append("\n");
        Expression on = j.getOnExpression();
        if (on != null) {
            // JOIN 行より一段深く。AND/OR 行は ON と同じインデント（「ON 」より後ろには下げない）
            String onHead = prefix + IND + IND;
            sb.append(onHead).append("ON ");
            appendExpressionBrokenOnAndOr(sb, on, onHead);
            sb.append("\n");
        }
    }

    private static String joinTypeName(Join join) {
        if (join.isCross()) {
            return "CROSS JOIN";
        }
        if (join.isNatural()) {
            if (join.isLeft()) return "NATURAL LEFT JOIN";
            if (join.isRight()) return "NATURAL RIGHT JOIN";
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

    private static void appendWhereConditions(StringBuilder sb, Expression where, String condIndent) {
        sb.append(condIndent);
        appendExpressionBrokenOnAndOr(sb, where, condIndent);
        sb.append("\n");
    }

    /**
     * 式木をたどり、{@code AND} / {@code OR} の直前で改行する（括弧内が AND/OR のときは括弧内も改行）。
     *
     * @param contIndent 改行後に付けるインデント（{@code AND}/{@code OR} 行頭にも同じ幅を使う）
     */
    /** {@code UPDATE} / {@code SELECT} の {@code WHERE}・{@code ON} 等で共有する。 */
    static void appendExpressionBrokenOnAndOr(StringBuilder sb, Expression e, String contIndent) {
        if (e instanceof AndExpression) {
            AndExpression a = (AndExpression) e;
            appendExpressionBrokenOnAndOr(sb, a.getLeftExpression(), contIndent);
            sb.append("\n").append(contIndent).append("AND ");
            appendExpressionBrokenOnAndOr(sb, a.getRightExpression(), contIndent);
        } else if (e instanceof OrExpression) {
            OrExpression o = (OrExpression) e;
            appendExpressionBrokenOnAndOr(sb, o.getLeftExpression(), contIndent);
            sb.append("\n").append(contIndent).append("OR ");
            appendExpressionBrokenOnAndOr(sb, o.getRightExpression(), contIndent);
        } else if (e instanceof Parenthesis) {
            Parenthesis p = (Parenthesis) e;
            Expression inner = p.getExpression();
            if (inner == null) {
                sb.append("()");
                return;
            }
            if (inner instanceof AndExpression || inner instanceof OrExpression) {
                sb.append("(\n").append(contIndent + IND);
                appendExpressionBrokenOnAndOr(sb, inner, contIndent + IND);
                sb.append("\n").append(contIndent).append(")");
            } else {
                SubSelect ss = unwrapToSubSelect(inner);
                if (ss != null) {
                    appendBracketedSubSelect(sb, ss, contIndent);
                } else {
                    sb.append("(").append(inner.toString()).append(")");
                }
            }
        } else if (e instanceof ExistsExpression) {
            ExistsExpression ex = (ExistsExpression) e;
            sb.append(ex.isNot() ? "NOT EXISTS " : "EXISTS ");
            appendFormattedSubSelectFromExpr(sb, ex.getRightExpression(), contIndent);
        } else if (e instanceof InExpression) {
            InExpression in = (InExpression) e;
            if (in.getLeftExpression() != null) {
                appendExpressionBrokenOnAndOr(sb, in.getLeftExpression(), contIndent);
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
                appendBracketedSubSelect(sb, inSs, contIndent);
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
            appendExpressionBrokenOnAndOr(sb, ne.getExpression(), contIndent);
        } else if (e instanceof SubSelect) {
            appendBracketedSubSelect(sb, (SubSelect) e, contIndent);
        } else {
            sb.append(e.toString());
        }
    }

    /** {@code ( SELECT ... )} を {@link #appendSelectBody} で整形して付与する。 */
    private static void appendBracketedSubSelect(StringBuilder sb, SubSelect ss, String contIndent) {
        if (ss == null || ss.getSelectBody() == null) {
            sb.append("()");
            return;
        }
        String innerPrefix = contIndent + IND;
        sb.append("(\n");
        appendSelectBody(sb, ss.getSelectBody(), innerPrefix);
        sb.append("\n").append(contIndent).append(")");
    }

    private static void appendFormattedSubSelectFromExpr(StringBuilder sb, Expression expr, String contIndent) {
        SubSelect ss = unwrapToSubSelect(expr);
        if (ss != null) {
            appendBracketedSubSelect(sb, ss, contIndent);
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
