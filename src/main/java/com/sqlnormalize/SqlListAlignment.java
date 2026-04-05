package com.sqlnormalize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 正規化済み SQL 文のリスト同士を、Needleman–Wunsch（グローバルアライメント）で対応付ける。
 * <p>
 * 置換コストは {@link SubstitutionCostMode} に応じて、文字レベルのレーベンシュタイン距離、
 * または一致 0 / 不一致 1 のいずれか。
 * </p>
 */
public final class SqlListAlignment {

    private SqlListAlignment() {
    }

    /** 文ペアの置換コストの定義。 */
    public enum SubstitutionCostMode {
        /** 2 文の文字列に対するレーベンシュタイン距離（編集距離）。 */
        LEVENSHTEIN,
        /** 文字列が等しければ 0、そうでなければ 1。 */
        BINARY
    }

    /** アライメントの 1 行。{@code -1} はその側に文が無い（ギャップ）。 */
    public static final class AlignStep {
        public final int beforeIndex;
        public final int afterIndex;

        public AlignStep(int beforeIndex, int afterIndex) {
            this.beforeIndex = beforeIndex;
            this.afterIndex = afterIndex;
        }
    }

    /**
     * コスト最小のグローバルアライメントを求め、上から順のステップ列を返す。
     *
     * @param before      移行前の文（空リスト可）
     * @param after       移行後の文
     * @param gapPenalty  挿入・削除（ギャップ）1 あたりのコスト（1 未満は 1 に丸める）
     * @param mode        置換コスト
     * @return 長さが両者のギャップ込みアライメント長のステップ（両方 -1 になる要素は含めない）
     */
    public static List<AlignStep> needlemanWunsch(
            List<String> before,
            List<String> after,
            int gapPenalty,
            SubstitutionCostMode mode) {
        int gp = Math.max(1, gapPenalty);
        if (before == null) {
            before = Collections.emptyList();
        }
        if (after == null) {
            after = Collections.emptyList();
        }
        int m = before.size();
        int n = after.size();
        if (m == 0 && n == 0) {
            return Collections.emptyList();
        }

        long[][] dp = new long[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            dp[i][0] = dp[i - 1][0] + gp;
        }
        for (int j = 1; j <= n; j++) {
            dp[0][j] = dp[0][j - 1] + gp;
        }

        for (int i = 1; i <= m; i++) {
            String ai = before.get(i - 1);
            for (int j = 1; j <= n; j++) {
                long sub = substitutionCost(ai, after.get(j - 1), mode);
                long diag = dp[i - 1][j - 1] + sub;
                long up = dp[i - 1][j] + gp;
                long left = dp[i][j - 1] + gp;
                dp[i][j] = Math.min(diag, Math.min(up, left));
            }
        }

        List<AlignStep> rev = new ArrayList<>(m + n);
        int i = m;
        int j = n;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0) {
                long sub = substitutionCost(before.get(i - 1), after.get(j - 1), mode);
                long diag = dp[i - 1][j - 1] + sub;
                if (dp[i][j] == diag) {
                    rev.add(new AlignStep(i - 1, j - 1));
                    i--;
                    j--;
                    continue;
                }
            }
            if (i > 0) {
                long up = dp[i - 1][j] + gp;
                if (j == 0 || dp[i][j] == up) {
                    rev.add(new AlignStep(i - 1, -1));
                    i--;
                    continue;
                }
            }
            rev.add(new AlignStep(-1, j - 1));
            j--;
        }
        Collections.reverse(rev);
        return rev;
    }

    private static long substitutionCost(String a, String b, SubstitutionCostMode mode) {
        if (mode == SubstitutionCostMode.BINARY) {
            return (a == null ? "" : a).equals(b == null ? "" : b) ? 0L : 1L;
        }
        return levenshteinDistance(a == null ? "" : a, b == null ? "" : b);
    }

    /**
     * レーベンシュタイン距離（挿入・削除・置換のコストはいずれも 1）。
     * 戻り値は int 範囲に収まる想定（極端に長い文では時間がかかる）。
     */
    public static int levenshteinDistance(String s, String t) {
        int m = s.length();
        int n = t.length();
        if (m == 0) {
            return n;
        }
        if (n == 0) {
            return m;
        }
        int[] prev = new int[n + 1];
        int[] cur = new int[n + 1];
        for (int j = 0; j <= n; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= m; i++) {
            cur[0] = i;
            char sc = s.charAt(i - 1);
            for (int j = 1; j <= n; j++) {
                int cost = sc == t.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1], prev[j]) + 1, prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[n];
    }
}
