package com.sqlnormalize;

import com.formdev.flatlaf.FlatLightLaf;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import com.github.difflib.patch.DeltaType;
import com.github.difflib.patch.Patch;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.ParagraphView;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 移行前・移行後SQLを貼り付けて正規化し、比較するSwingアプリケーションのメインクラス。
 * <p>
 * 上部に移行前/移行後SQLの入力エリア（複数文は {@code ;} 区切り）、中央に正規化ボタン、正規化済み文の一覧グリッド、正規化結果コピーボタン、整形詳細ペインを表示する。
 * 終了時に入力内容をファイルへ保存し、次回起動時に復元する。
 * </p>
 */
public class SqlNormalizeApp {

    /** 入力値の保存先ファイル名（カレントディレクトリに作成）。 */
    private static final String STATE_FILE = "sql-normalize-state.txt";
    /** 保存ファイル内で「移行前」ブロックの区切り文字列。 */
    private static final String DELIM_BEFORE = "\n---BEFORE---\n";
    /** 保存ファイル内で「移行後」ブロックの区切り文字列。 */
    private static final String DELIM_AFTER = "\n---AFTER---\n";

    /** P6Spy 設定のテンプレートリソース名。 */
    private static final String SPY_PROPERTIES_TEMPLATE = "/spy.properties.template";
    /** 作業ディレクトリに作成する P6Spy 設定ファイル名。 */
    private static final String SPY_PROPERTIES_FILE = "spy.properties";

    /** SQL 比較タブの「正規化して比較」「正規化結果をコピー」ボタンサイズ（幅×高さ）。 */
    private static final Dimension SQL_COMPARE_ACTION_BUTTON_SIZE = new Dimension(130, 30);

    /** SQL正規化処理を行うインスタンス。 */
    private final SqlNormalizer normalizer = new SqlNormalizer();
    /** 移行前SQLの入力エリア（複数文は {@code ;} 区切り。単引用符内の {@code ;} は区切りにしない）。 */
    private JTextArea beforeSqlArea;
    /** 移行後SQLの入力エリア。 */
    private JTextArea afterSqlArea;
    /** 移行前: 正規化済み各文（グリッド行と対応）。 */
    private final List<String> beforeNormStatements = new ArrayList<>();
    /** 移行後: 正規化済み各文。 */
    private final List<String> afterNormStatements = new ArrayList<>();
    /** 移行前: #・比較・SQL 列のグリッド。 */
    private JTable beforeStmtGrid;
    /** 移行後: #・比較・SQL 列のグリッド。 */
    private JTable afterStmtGrid;
    /** プログラムによるグリッド選択（連動・正規化直後）のとき、リスナーによる二重更新・再入を抑止する。 */
    private boolean gridSelectionProgrammatic;
    /** 移行前SQLの正規化結果表示（キーワード青・エイリアス等は灰色）。 */
    private JTextPane beforeNormalizedArea;
    /** 移行後SQLの正規化結果表示（キーワード青・エイリアス等は灰色）。 */
    private JTextPane afterNormalizedArea;

    /** SQL キーワードの表示色。 */
    private static final Color SQL_KEYWORD_FOREGROUND = new Color(0, 90, 180);

    /** 移行前後の正規化結果で差分がある行の表示色（キーワード青・灰色より後に適用）。 */
    private static final Color DIFF_LINE_FOREGROUND = new Color(255, 0, 0);

    /** 正規化テーブル別名 {@code t1}. ～ {@code t9…} の表示色。 */
    private static final Color TABLE_ALIAS_FOREGROUND = new Color(188, 188, 188);

    /** 文字列・数値リテラルの表示色。 */
    private static final Color SQL_LITERAL_FOREGROUND = new Color(128, 0, 160);

    /** ブロックコメント（{@code /}{@code *} … {@code *}{@code /}）の表示色。 */
    private static final Color SQL_BLOCK_COMMENT_FOREGROUND = new Color(0, 140, 0);

    private static final Pattern BLOCK_COMMENT_PATTERN = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** 列修飾 {@code tN.}（正規化エイリアスのみ灰色。シーケンス名等は対象外） */
    private static final Pattern TABLE_QUALIFIER_WITH_DOT =
            Pattern.compile("(?<![A-Za-z0-9_])(t[1-9]\\d*\\.)");

    private static final Pattern NORMALIZED_TABLE_ALIAS_WORD = Pattern.compile("t[1-9]\\d*");

    /**
     * FROM 直後の `テーブル エイリアス`（2語目＝エイリアスを灰色）。サブクエリ {@code FROM (} は除外。
     */
    private static final Pattern FROM_TABLE_ALIAS =
            Pattern.compile("FROM\\s+([^(\\s]\\S*)\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    /**
     * {@code … JOIN 表 エイリアス} のエイリアス語を灰色（{@code LEFT OUTER JOIN} 等も {@code JOIN} で捕捉）。
     * サブクエリ {@code JOIN (} は除外。
     */
    private static final Pattern JOIN_TABLE_ALIAS =
            Pattern.compile("\\bJOIN\\s+([^(\\s]\\S*)\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    /**
     * エントリポイント。{@link FlatLightLaf} を適用し、Swing UI を EDT で起動する。
     *
     * @param args コマンドライン引数（未使用）
     */
    public static void main(String[] args) {
        ensureSpyPropertiesFromTemplate();
        try {
            FlatLightLaf.setup();
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new SqlNormalizeApp().createAndShow());
    }

    /**
     * 作業ディレクトリに spy.properties が無い場合、クラスパスのテンプレートから作成する。
     * P6Spy がログ出力先等を読むため、H2接続前に呼ぶこと。
     */
    private static void ensureSpyPropertiesFromTemplate() {
        Path target = Paths.get(System.getProperty("user.dir", "."), SPY_PROPERTIES_FILE);
        if (Files.exists(target)) return;
        try (InputStream in = SqlNormalizeApp.class.getResourceAsStream(SPY_PROPERTIES_TEMPLATE)) {
            if (in != null) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {}
    }

    private static void setSqlCompareActionButtonSize(JButton b) {
        Dimension d = SQL_COMPARE_ACTION_BUTTON_SIZE;
        b.setPreferredSize(d);
        b.setMinimumSize(d);
        b.setMaximumSize(d);
    }

    /**
     * メインウィンドウを構築し、表示する。
     * 上部に移行前/移行後入力、中央に正規化ボタンと移行前（正規化）/移行後（正規化）グリッド、グリッドと整形ペインの間に正規化結果コピーボタン、整形ペインを配置する。
     */
    private void createAndShow() {
        JFrame frame = new JFrame("SQL 正規化・比較 - システムマイグレーション");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveState();
                frame.dispose();
                System.exit(0);
            }
        });

        JPanel main = new JPanel(new BorderLayout(8, 8));
        main.setBorder(new EmptyBorder(12, 12, 12, 12));

        // 上部: 移行前・移行後 入力
        JPanel inputPanel = new JPanel(new GridLayout(1, 2, 12, 0));
        beforeSqlArea = createSqlArea("移行前 SQL");
        afterSqlArea = createSqlArea("移行後 SQL");
        JScrollPane beforeInputScroll = wrapWithScroll(beforeSqlArea, "移行前 SQL（; 区切りで複数可）");
        JScrollPane afterInputScroll = wrapWithScroll(afterSqlArea, "移行後 SQL（; 区切りで複数可）");
        linkScrollPaneSync(beforeInputScroll, afterInputScroll);
        inputPanel.add(beforeInputScroll);
        inputPanel.add(afterInputScroll);
        main.add(inputPanel, BorderLayout.NORTH);

        // 正規化ボタン（直下に結果、余白なし）
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        JButton normalizeBtn = new JButton("正規化して比較");
        setSqlCompareActionButtonSize(normalizeBtn);
        normalizeBtn.addActionListener(e -> onNormalize());
        buttonPanel.add(normalizeBtn);

        DefaultTableModel beforeStmtModel = new DefaultTableModel(new Object[] { "#", "比較", "SQL" }, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        DefaultTableModel afterStmtModel = new DefaultTableModel(new Object[] { "#", "比較", "SQL" }, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        beforeStmtGrid = new JTable(beforeStmtModel);
        afterStmtGrid = new JTable(afterStmtModel);
        DefaultTableCellRenderer compareColRenderer = new CompareColumnRenderer();
        beforeStmtGrid.getColumnModel().getColumn(1).setCellRenderer(compareColRenderer);
        afterStmtGrid.getColumnModel().getColumn(1).setCellRenderer(compareColRenderer);
        Font gridFont = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        beforeStmtGrid.setFont(gridFont);
        afterStmtGrid.setFont(gridFont);
        beforeStmtGrid.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        afterStmtGrid.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        beforeStmtGrid.getTableHeader().setReorderingAllowed(false);
        afterStmtGrid.getTableHeader().setReorderingAllowed(false);
        if (beforeStmtGrid.getColumnModel().getColumnCount() > 2) {
            beforeStmtGrid.getColumnModel().getColumn(0).setPreferredWidth(40);
            beforeStmtGrid.getColumnModel().getColumn(0).setMaxWidth(56);
            beforeStmtGrid.getColumnModel().getColumn(1).setPreferredWidth(56);
            beforeStmtGrid.getColumnModel().getColumn(1).setMaxWidth(72);
        }
        if (afterStmtGrid.getColumnModel().getColumnCount() > 2) {
            afterStmtGrid.getColumnModel().getColumn(0).setPreferredWidth(40);
            afterStmtGrid.getColumnModel().getColumn(0).setMaxWidth(56);
            afterStmtGrid.getColumnModel().getColumn(1).setPreferredWidth(56);
            afterStmtGrid.getColumnModel().getColumn(1).setMaxWidth(72);
        }
        beforeStmtGrid.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !gridSelectionProgrammatic) {
                syncPeerGridRowSelection(beforeStmtGrid, afterStmtGrid);
                refreshFormattedPanesFromGridSelection();
            }
        });
        afterStmtGrid.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !gridSelectionProgrammatic) {
                syncPeerGridRowSelection(afterStmtGrid, beforeStmtGrid);
                refreshFormattedPanesFromGridSelection();
            }
        });

        JPanel stmtListPanel = new JPanel(new GridLayout(1, 2, 12, 0));
        JScrollPane beforeGridScroll = wrapWithScroll(beforeStmtGrid, "移行前（正規化）");
        JScrollPane afterGridScroll = wrapWithScroll(afterStmtGrid, "移行後（正規化）");
        beforeGridScroll.setPreferredSize(new Dimension(200, 220));
        afterGridScroll.setPreferredSize(new Dimension(200, 220));
        linkScrollPaneSync(beforeGridScroll, afterGridScroll);
        stmtListPanel.add(beforeGridScroll);
        stmtListPanel.add(afterGridScroll);

        JPanel copyRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        JButton copyNormBtn = new JButton("正規化結果をコピー");
        setSqlCompareActionButtonSize(copyNormBtn);
        copyNormBtn.addActionListener(e -> copyNormalizedResultsToClipboard(frame));
        copyRow.add(copyNormBtn);

        JPanel resultPanel = new JPanel(new GridLayout(1, 2, 12, 0));
        beforeNormalizedArea = createReadOnlySqlPane();
        afterNormalizedArea = createReadOnlySqlPane();
        JScrollPane beforeNormScroll = wrapWithScroll(beforeNormalizedArea, "移行前（整形）");
        JScrollPane afterNormScroll = wrapWithScroll(afterNormalizedArea, "移行後（整形）");
        linkScrollPaneSync(beforeNormScroll, afterNormScroll);
        resultPanel.add(beforeNormScroll);
        resultPanel.add(afterNormScroll);

        JPanel gridAndFormatted = new JPanel(new BorderLayout(8, 8));
        gridAndFormatted.add(copyRow, BorderLayout.NORTH);
        gridAndFormatted.add(resultPanel, BorderLayout.CENTER);

        JPanel resultWrapper = new JPanel(new BorderLayout(8, 8));
        resultWrapper.add(stmtListPanel, BorderLayout.NORTH);
        resultWrapper.add(gridAndFormatted, BorderLayout.CENTER);

        JPanel centerPanel = new JPanel(new BorderLayout(0, 0));
        centerPanel.add(buttonPanel, BorderLayout.NORTH);
        centerPanel.add(resultWrapper, BorderLayout.CENTER);
        main.add(centerPanel, BorderLayout.CENTER);

        loadState();

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("SQL比較", main);
        tabs.addTab("H2接続・実行", new H2ConnectionPanel());

        frame.setContentPane(tabs);
        frame.setSize(960, 760);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /**
     * 編集可能なSQL入力用テキストエリアを生成する。
     *
     * @param title タイトル（表示用、未使用）
     * @return 等幅フォント・折り返しなしの JTextArea
     */
    private JTextArea createSqlArea(String title) {
        JTextArea area = new JTextArea(6, 40);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setLineWrap(false);
        area.setTabSize(4);
        return area;
    }

    /**
     * 読み取り専用の正規化結果表示用 JTextPane を生成する（リッチテキスト用・行の折り返しなし）。
     *
     * @return 編集不可の等幅 JTextPane
     */
    private JTextPane createReadOnlySqlPane() {
        JTextPane pane = new JTextPane() {
            /**
             * 内容がビューポートより狭いときは幅追従で白背景を全面に広げる。
             * 長い行のときは false で横スクロール（折り返し無効・重なり対策と併用）。
             */
            @Override
            public boolean getScrollableTracksViewportWidth() {
                Container parent = getParent();
                if (parent == null || parent.getWidth() <= 0) {
                    return true;
                }
                int prefW = getUI().getPreferredSize(this).width;
                return prefW <= parent.getWidth();
            }
        };
        pane.setEditorKit(new NoWrapStyledEditorKit());
        pane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        pane.setEditable(false);
        pane.setBackground(UIManager.getColor("TextArea.background"));
        if (pane.getBackground() == null) {
            pane.setBackground(Color.WHITE);
        }
        return pane;
    }

    /**
     * 正規化済みSQLをペインに表示する。キーワード青・{@code tN} 系灰色・リテラル紫・ブロックコメント緑。
     * {@code diffRedRanges} があれば最後にその範囲を赤字にする（行単位の差分）。
     *
     * @param diffRedRanges {@code [start, end)} の文字オフセットのリスト。{@code null} なら差分着色なし。
     */
    private void setNormalizedSqlDisplay(JTextPane pane, String sql, List<int[]> diffRedRanges) {
        String text = sql == null ? "" : sql;
        StyledDocument doc = pane.getStyledDocument();
        SimpleAttributeSet base = new SimpleAttributeSet();
        StyleConstants.setFontFamily(base, Font.MONOSPACED);
        StyleConstants.setFontSize(base, 13);
        StyleConstants.setForeground(base, pane.getForeground() != null ? pane.getForeground() : Color.BLACK);

        SimpleAttributeSet keywordAttr = new SimpleAttributeSet();
        StyleConstants.setFontFamily(keywordAttr, Font.MONOSPACED);
        StyleConstants.setFontSize(keywordAttr, 13);
        StyleConstants.setForeground(keywordAttr, SQL_KEYWORD_FOREGROUND);

        SimpleAttributeSet aliasAttr = new SimpleAttributeSet();
        StyleConstants.setFontFamily(aliasAttr, Font.MONOSPACED);
        StyleConstants.setFontSize(aliasAttr, 13);
        StyleConstants.setForeground(aliasAttr, TABLE_ALIAS_FOREGROUND);

        SimpleAttributeSet literalAttr = new SimpleAttributeSet();
        StyleConstants.setFontFamily(literalAttr, Font.MONOSPACED);
        StyleConstants.setFontSize(literalAttr, 13);
        StyleConstants.setForeground(literalAttr, SQL_LITERAL_FOREGROUND);

        SimpleAttributeSet commentAttr = new SimpleAttributeSet();
        StyleConstants.setFontFamily(commentAttr, Font.MONOSPACED);
        StyleConstants.setFontSize(commentAttr, 13);
        StyleConstants.setForeground(commentAttr, SQL_BLOCK_COMMENT_FOREGROUND);

        SimpleAttributeSet diffAttr = new SimpleAttributeSet();
        StyleConstants.setFontFamily(diffAttr, Font.MONOSPACED);
        StyleConstants.setFontSize(diffAttr, 13);
        StyleConstants.setForeground(diffAttr, DIFF_LINE_FOREGROUND);

        try {
            doc.remove(0, doc.getLength());
            doc.insertString(0, text, base);

            List<int[]> stringRanges = findSingleQuotedStringRanges(text);
            List<int[]> blockCommentRanges = findBlockCommentRangesOutsideStrings(text, stringRanges);

            Matcher mkw = SqlNormalizer.keywordHighlightPattern().matcher(text);
            while (mkw.find()) {
                if (!rangeOverlapsProtected(mkw.start(), mkw.end(), stringRanges, blockCommentRanges)) {
                    doc.setCharacterAttributes(mkw.start(), mkw.end() - mkw.start(), keywordAttr, true);
                }
            }
            for (int[] sr : stringRanges) {
                doc.setCharacterAttributes(sr[0], sr[1] - sr[0], literalAttr, true);
            }
            Pattern numericLiteral = Pattern.compile("(?<![A-Za-z0-9_])(\\d+(?:\\.\\d+)?)(?![A-Za-z0-9_])");
            Matcher mn = numericLiteral.matcher(text);
            while (mn.find()) {
                if (!rangeOverlapsProtected(mn.start(1), mn.end(1), stringRanges, blockCommentRanges)) {
                    doc.setCharacterAttributes(mn.start(1), mn.group(1).length(), literalAttr, true);
                }
            }

            Matcher mq = TABLE_QUALIFIER_WITH_DOT.matcher(text);
            while (mq.find()) {
                if (!rangeOverlapsProtected(mq.start(), mq.end(), stringRanges, blockCommentRanges)) {
                    int start = mq.start(1);
                    doc.setCharacterAttributes(start, mq.group(1).length(), aliasAttr, true);
                }
            }
            Matcher mf = FROM_TABLE_ALIAS.matcher(text);
            while (mf.find()) {
                String aliasWord = mf.group(2);
                if (NORMALIZED_TABLE_ALIAS_WORD.matcher(aliasWord).matches()
                        && !rangeOverlapsProtected(mf.start(2), mf.end(2), stringRanges, blockCommentRanges)) {
                    doc.setCharacterAttributes(mf.start(2), aliasWord.length(), aliasAttr, true);
                }
            }
            Matcher mj = JOIN_TABLE_ALIAS.matcher(text);
            while (mj.find()) {
                String aliasWord = mj.group(2);
                if (NORMALIZED_TABLE_ALIAS_WORD.matcher(aliasWord).matches()
                        && !rangeOverlapsProtected(mj.start(2), mj.end(2), stringRanges, blockCommentRanges)) {
                    doc.setCharacterAttributes(mj.start(2), aliasWord.length(), aliasAttr, true);
                }
            }
            for (int[] cr : blockCommentRanges) {
                doc.setCharacterAttributes(cr[0], cr[1] - cr[0], commentAttr, true);
            }
            if (diffRedRanges != null) {
                for (int[] r : diffRedRanges) {
                    int start = r[0];
                    int end = r[1];
                    if (start < 0 || end <= start || start > text.length()) {
                        continue;
                    }
                    end = Math.min(end, text.length());
                    doc.setCharacterAttributes(start, end - start, diffAttr, true);
                }
            }
            pane.revalidate();
            pane.repaint();
        } catch (BadLocationException ignored) {}
    }

    /** {@code '…'} リテラル（{@code ''} エスケープ）の {@code [start,end)} 区間一覧。 */
    private static List<int[]> findSingleQuotedStringRanges(String text) {
        List<int[]> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        boolean inSingle = false;
        int segStart = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!inSingle) {
                if (c == '\'') {
                    inSingle = true;
                    segStart = i;
                }
            } else {
                if (c == '\'') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        out.add(new int[] { segStart, i + 1 });
                        inSingle = false;
                    }
                }
            }
        }
        return out;
    }

    private static boolean rangeOverlapsAny(int start, int end, List<int[]> ranges) {
        for (int[] r : ranges) {
            if (end > r[0] && start < r[1]) {
                return true;
            }
        }
        return false;
    }

    private static boolean rangeOverlapsProtected(int start, int end, List<int[]> stringRanges,
            List<int[]> blockCommentRanges) {
        return rangeOverlapsAny(start, end, stringRanges) || rangeOverlapsAny(start, end, blockCommentRanges);
    }

    /** 単引用符リテラル外のブロックコメント区間（ネスト非対応）。 */
    private static List<int[]> findBlockCommentRangesOutsideStrings(String text, List<int[]> stringRanges) {
        List<int[]> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        Matcher m = BLOCK_COMMENT_PATTERN.matcher(text);
        while (m.find()) {
            if (!rangeOverlapsAny(m.start(), m.end(), stringRanges)) {
                out.add(new int[] { m.start(), m.end() });
            }
        }
        return out;
    }

    /** 移行前・移行後の正規化テキストを行単位に比較し、片側に赤字を付ける範囲を求める。 */
    private static List<int[]> computeDiffRedRanges(String sideText, boolean forOriginalSide,
            String normBefore, String normAfter) {
        List<String> a = linesForDiff(normBefore);
        List<String> b = linesForDiff(normAfter);
        Patch<String> patch = DiffUtils.diff(a, b);
        int[] lineStarts = lineStartOffsets(sideText);
        int textLen = sideText.length();
        List<int[]> ranges = new ArrayList<>();
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            DeltaType t = delta.getType();
            if (forOriginalSide) {
                if (t == DeltaType.DELETE || t == DeltaType.CHANGE) {
                    addChunkLineRange(ranges, lineStarts, textLen, delta.getSource());
                }
            } else {
                if (t == DeltaType.INSERT || t == DeltaType.CHANGE) {
                    addChunkLineRange(ranges, lineStarts, textLen, delta.getTarget());
                }
            }
        }
        return mergeIntRanges(ranges);
    }

    private static List<String> linesForDiff(String s) {
        if (s == null || s.isEmpty()) {
            return Arrays.asList("");
        }
        return Arrays.asList(s.split("\\R", -1));
    }

    private static int[] lineStartOffsets(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        Matcher m = Pattern.compile("\\R").matcher(text);
        while (m.find()) {
            starts.add(m.end());
        }
        int[] arr = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) {
            arr[i] = starts.get(i);
        }
        return arr;
    }

    private static void addChunkLineRange(List<int[]> out, int[] lineStarts, int textLen, Chunk<String> chunk) {
        if (chunk == null || chunk.getLines() == null || chunk.getLines().isEmpty()) {
            return;
        }
        int linePos = chunk.getPosition();
        int lineCount = chunk.getLines().size();
        if (linePos < 0 || linePos >= lineStarts.length) {
            return;
        }
        int start = lineStarts[linePos];
        int endLine = linePos + lineCount;
        int end = endLine < lineStarts.length ? lineStarts[endLine] : textLen;
        if (end > start) {
            out.add(new int[] { start, end });
        }
    }

    private static List<int[]> mergeIntRanges(List<int[]> ranges) {
        if (ranges.isEmpty()) {
            return ranges;
        }
        ranges.sort(Comparator.comparingInt(a -> a[0]));
        List<int[]> merged = new ArrayList<>();
        int[] cur = new int[] { ranges.get(0)[0], ranges.get(0)[1] };
        for (int i = 1; i < ranges.size(); i++) {
            int[] n = ranges.get(i);
            if (n[0] <= cur[1]) {
                cur[1] = Math.max(cur[1], n[1]);
            } else {
                merged.add(cur);
                cur = new int[] { n[0], n[1] };
            }
        }
        merged.add(cur);
        return merged;
    }

    /**
     * テキストエリアをタイトル付きボーダーとスクロールでラップする。
     *
     * @param area  ラップする JTextArea
     * @param title ボーダーに表示するタイトル
     * @return スクロールペイン
     */
    private JScrollPane wrapWithScroll(JTextArea area, String title) {
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createCompoundBorder(
                new TitledBorder(BorderFactory.createLineBorder(Color.GRAY, 1), title, TitledBorder.LEFT, TitledBorder.TOP),
                new EmptyBorder(4, 4, 4, 4)));
        return scroll;
    }

    private static JScrollPane wrapWithScroll(JTable table, String title) {
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createCompoundBorder(
                new TitledBorder(BorderFactory.createLineBorder(Color.GRAY, 1), title, TitledBorder.LEFT, TitledBorder.TOP),
                new EmptyBorder(4, 4, 4, 4)));
        return scroll;
    }

    /**
     * 2つの {@link JScrollPane} の縦・横スクロール位置を同期する（一方を動かすともう一方が追従）。
     */
    private static void linkScrollPaneSync(JScrollPane a, JScrollPane b) {
        boolean[] syncing = { false };
        linkScrollBarSync(a.getVerticalScrollBar(), b.getVerticalScrollBar(), syncing);
        linkScrollBarSync(b.getVerticalScrollBar(), a.getVerticalScrollBar(), syncing);
        linkScrollBarSync(a.getHorizontalScrollBar(), b.getHorizontalScrollBar(), syncing);
        linkScrollBarSync(b.getHorizontalScrollBar(), a.getHorizontalScrollBar(), syncing);
    }

    private static void linkScrollBarSync(JScrollBar from, JScrollBar to, boolean[] syncing) {
        from.addAdjustmentListener(e -> {
            if (syncing[0]) {
                return;
            }
            syncing[0] = true;
            try {
                int v = from.getValue();
                if (to.getValue() != v) {
                    to.setValue(v);
                }
            } finally {
                syncing[0] = false;
            }
        });
    }

    /**
     * JTextPane をタイトル付きボーダーとスクロールでラップする。
     */
    private JScrollPane wrapWithScroll(JTextPane pane, String title) {
        JScrollPane scroll = new JScrollPane(pane);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createCompoundBorder(
                new TitledBorder(BorderFactory.createLineBorder(Color.GRAY, 1), title, TitledBorder.LEFT, TitledBorder.TOP),
                new EmptyBorder(4, 4, 4, 4)));
        return scroll;
    }

    /**
     * 正規化結果の JTextPane 用。段落の折り返しを行わず、横スクロールで長い行を表示する。
     */
    private static final class NoWrapStyledEditorKit extends StyledEditorKit {
        @Override
        public ViewFactory getViewFactory() {
            ViewFactory defaults = super.getViewFactory();
            return elem -> {
                View v = defaults.create(elem);
                if (v instanceof ParagraphView) {
                    return new NoWrapParagraphView(elem);
                }
                return v;
            };
        }
    }

    /**
     * 折り返さない {@link ParagraphView}（横スクロールで長い行を表示）。
     */
    private static final class NoWrapParagraphView extends ParagraphView {
        NoWrapParagraphView(Element elem) {
            super(elem);
        }

        @Override
        public int getFlowSpan(int index) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * 「正規化して比較」ボタン押下時の処理。
     * 入力を {@code ;} で分割して各文を正規化し、一覧グリッドに載せる。先頭行を選択して整形ペインに表示する。
     * 同じ番号の文同士の一致/差異をグリッドの「比較」列に表示する。
     */
    private void onNormalize() {
        String before = beforeSqlArea.getText();
        String after = afterSqlArea.getText();

        List<String> partsBefore = SqlNormalization.splitStatements(before);
        List<String> partsAfter = SqlNormalization.splitStatements(after);

        beforeNormStatements.clear();
        afterNormStatements.clear();
        for (String p : partsBefore) {
            beforeNormStatements.add(normalizer.normalize(p));
        }
        for (String p : partsAfter) {
            afterNormStatements.add(normalizer.normalize(p));
        }

        fillStatementGrids();

        gridSelectionProgrammatic = true;
        try {
            if (!beforeNormStatements.isEmpty()) {
                beforeStmtGrid.setRowSelectionInterval(0, 0);
            } else {
                beforeStmtGrid.clearSelection();
            }
            if (!afterNormStatements.isEmpty()) {
                afterStmtGrid.setRowSelectionInterval(0, 0);
            } else {
                afterStmtGrid.clearSelection();
            }
        } finally {
            gridSelectionProgrammatic = false;
        }

        refreshFormattedPanesFromGridSelection();
    }

    /**
     * 現在の正規化結果を TSV 形式でクリップボードにコピーする。
     * 列は {@code #}・比較結果・移行前SQL・移行後SQL。SQL 列は {@link #sqlCollapseToOneLine} で引用符外の改行・インデントを除き 1 行にする（省略なし。グリッドの {@link #sqlGridPreview} とは異なる）。
     * 移行前SQL 列のみ、非空かつ末尾が {@code ;} でなければ {@code ;} を付与する。
     * タブ・改行・{@code "} を含むセルはダブルクォートで囲む（Excel 貼り付け向けに先頭に BOM）。
     */
    private void copyNormalizedResultsToClipboard(Component parent) {
        int n = Math.max(beforeNormStatements.size(), afterNormStatements.size());
        if (n == 0) {
            JOptionPane.showMessageDialog(parent,
                    "コピーする正規化結果がありません。「正規化して比較」を先に実行してください。",
                    "正規化結果をコピー",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String text = buildNormalizedResultsTsvForClipboard();
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        JOptionPane.showMessageDialog(parent,
                "クリップボードにコピーしました。",
                "正規化結果をコピー",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private String buildNormalizedResultsTsvForClipboard() {
        int n = Math.max(beforeNormStatements.size(), afterNormStatements.size());
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append(tsvRow("#", "比較結果", "移行前SQL", "移行後SQL")).append('\n');
        for (int i = 0; i < n; i++) {
            String before = i < beforeNormStatements.size() ? beforeNormStatements.get(i) : "";
            String after = i < afterNormStatements.size() ? afterNormStatements.get(i) : "";
            sb.append(tsvRow(String.valueOf(i + 1), pairCompareLabel(i),
                            sqlClipboardBeforeColumn(before), sqlCollapseToOneLine(after)))
                    .append('\n');
        }
        return sb.toString();
    }

    /** クリップボード TSV の移行前SQL 列用: {@link #sqlCollapseToOneLine} 後、非空なら末尾に {@code ;}（既に {@code ;} なら付けない）。 */
    private static String sqlClipboardBeforeColumn(String normalizedStmt) {
        String one = sqlCollapseToOneLine(normalizedStmt);
        if (one.isEmpty()) {
            return "";
        }
        return one.endsWith(";") ? one : one + ";";
    }

    private static String tsvEscapeCell(String s) {
        if (s == null) {
            s = "";
        }
        boolean needQuote = s.indexOf('\t') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0
                || s.indexOf('"') >= 0;
        String e = s.replace("\"", "\"\"");
        return needQuote ? "\"" + e + "\"" : e;
    }

    private static String tsvRow(String... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append('\t');
            }
            sb.append(tsvEscapeCell(cells[i]));
        }
        return sb.toString();
    }

    /**
     * 引用符外のインデント・改行を除き、連続空白を半角スペース 1 つにまとめて 1 行にする（全文、省略なし）。
     * {@code '} 内は空白はそのまま、改行のみスペース化（{@code ''} エスケープ考慮）。{@code "} は識別子用にトグルし内側は潰さない。
     */
    private static String sqlCollapseToOneLine(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(sql.length());
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    sb.append(c);
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        sb.append(sql.charAt(++i));
                    } else {
                        inSingle = false;
                    }
                } else if (c == '\r') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\n') {
                        i++;
                    }
                    appendSingleSpaceIfNeeded(sb);
                } else if (c == '\n' || c == '\t') {
                    appendSingleSpaceIfNeeded(sb);
                } else {
                    sb.append(c);
                }
            } else if (inDouble) {
                sb.append(c);
                if (c == '"') {
                    inDouble = false;
                }
            } else {
                if (c == '\'') {
                    inSingle = true;
                    sb.append(c);
                } else if (c == '"') {
                    inDouble = true;
                    sb.append(c);
                } else if (Character.isWhitespace(c)) {
                    while (i + 1 < sql.length() && Character.isWhitespace(sql.charAt(i + 1))) {
                        i++;
                    }
                    appendSingleSpaceIfNeeded(sb);
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * 正規化グリッドの SQL 列用: {@link #sqlCollapseToOneLine} の結果を最大 240 文字で {@code …} 省略する。
     */
    private static String sqlGridPreview(String sql) {
        String oneLine = sqlCollapseToOneLine(sql);
        int max = 240;
        if (oneLine.length() <= max) {
            return oneLine;
        }
        return oneLine.substring(0, max) + "…";
    }

    private static void appendSingleSpaceIfNeeded(StringBuilder sb) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
            sb.append(' ');
        }
    }

    /**
     * 同じ番号（インデックス）の移行前後の正規化SQLを比較した結果。
     * 片方にしか行が無いときは {@code —}。
     */
    private String pairCompareLabel(int index) {
        if (index < 0 || index >= beforeNormStatements.size() || index >= afterNormStatements.size()) {
            return "—";
        }
        return beforeNormStatements.get(index).equals(afterNormStatements.get(index)) ? "一致" : "差異";
    }

    /** 移行前・移行後グリッドを、比較列付きで再構築する。 */
    private void fillStatementGrids() {
        fillOneStatementGrid(beforeStmtGrid, beforeNormStatements);
        fillOneStatementGrid(afterStmtGrid, afterNormStatements);
    }

    private void fillOneStatementGrid(JTable table, List<String> sideStatements) {
        DefaultTableModel m = (DefaultTableModel) table.getModel();
        m.setRowCount(0);
        for (int i = 0; i < sideStatements.size(); i++) {
            m.addRow(new Object[] { String.valueOf(i + 1), pairCompareLabel(i), sqlGridPreview(sideStatements.get(i)) });
        }
    }

    /** 「比較」列: 中央寄せ、一致は緑・差異は赤。 */
    private static final class CompareColumnRenderer extends DefaultTableCellRenderer {
        CompareColumnRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (!isSelected) {
                String v = value != null ? value.toString() : "";
                if ("一致".equals(v)) {
                    c.setForeground(new Color(0, 120, 0));
                } else if ("差異".equals(v)) {
                    c.setForeground(Color.RED);
                } else {
                    c.setForeground(table.getForeground());
                }
            }
            return c;
        }
    }

    /**
     * 一方のグリッドで選んだ行番号を、他方でも同じインデックスで選ぶ（行が無ければ選択解除）。
     * {@link #gridSelectionProgrammatic} で反対側のリスナーを抑止する。
     */
    private void syncPeerGridRowSelection(JTable source, JTable peer) {
        int row = source.getSelectedRow();
        gridSelectionProgrammatic = true;
        try {
            if (row < 0) {
                peer.clearSelection();
            } else if (row < peer.getRowCount()) {
                peer.setRowSelectionInterval(row, row);
            } else {
                peer.clearSelection();
            }
        } finally {
            gridSelectionProgrammatic = false;
        }
    }

    /** 整形ペイン用: 非空かつ末尾が {@code ;} でなければ 1 つ付与する（表示のみ。正規化結果本体は変えない）。 */
    private static String sqlForFormattedPane(String normalizedStmt) {
        if (normalizedStmt == null || normalizedStmt.isEmpty()) {
            return "";
        }
        return normalizedStmt.endsWith(";") ? normalizedStmt : normalizedStmt + ";";
    }

    /** グリッドの選択行に対応する整形SQLを下段ペインに表示し、選択ペア同士で差分着色する。 */
    private void refreshFormattedPanesFromGridSelection() {
        int rb = beforeStmtGrid.getSelectedRow();
        int ra = afterStmtGrid.getSelectedRow();
        String left = rb >= 0 && rb < beforeNormStatements.size() ? beforeNormStatements.get(rb) : "";
        String right = ra >= 0 && ra < afterNormStatements.size() ? afterNormStatements.get(ra) : "";
        List<int[]> redL = computeDiffRedRanges(left, true, left, right);
        List<int[]> redR = computeDiffRedRanges(right, false, left, right);
        setNormalizedSqlDisplay(beforeNormalizedArea, sqlForFormattedPane(left), redL);
        setNormalizedSqlDisplay(afterNormalizedArea, sqlForFormattedPane(right), redR);
    }

    /**
     * 入力値の保存・読込に使うファイルのパスを返す。
     *
     * @return カレントディレクトリ直下の STATE_FILE の Path
     */
    private Path getStatePath() {
        return Paths.get(System.getProperty("user.dir", "."), STATE_FILE);
    }

    /**
     * 前回保存した移行前・移行後SQLをファイルから読み込み、入力エリアに復元する。
     * ファイルが無い・読めない場合は何もしない。
     */
    private void loadState() {
        Path path = getStatePath();
        if (!Files.isRegularFile(path)) return;
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            int afterIdx = content.indexOf(DELIM_AFTER);
            if (afterIdx < 0) return;
            String beforePart = content.substring(0, afterIdx);
            int beforeIdx = beforePart.indexOf(DELIM_BEFORE);
            if (beforeIdx < 0) return;
            String before = beforePart.substring(beforeIdx + DELIM_BEFORE.length());
            String after = content.substring(afterIdx + DELIM_AFTER.length());
            beforeSqlArea.setText(before);
            afterSqlArea.setText(after);
        } catch (IOException ignored) {}
    }

    /**
     * 現在の移行前・移行後SQLをファイルに保存する。
     * ウィンドウ終了時に呼ばれる。
     */
    private void saveState() {
        Path path = getStatePath();
        try {
            String content = DELIM_BEFORE + beforeSqlArea.getText() + DELIM_AFTER + afterSqlArea.getText();
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
    }
}
