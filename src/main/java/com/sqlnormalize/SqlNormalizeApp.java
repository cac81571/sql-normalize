package com.sqlnormalize;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 移行前・移行後SQLを貼り付けて正規化し、比較するSwingアプリケーションのメインクラス。
 * <p>
 * 上部に移行前/移行後SQLの入力エリア、中央に正規化ボタン、下部に正規化結果を表示する。
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

    /** SQL正規化処理を行うインスタンス。 */
    private final SqlNormalizer normalizer = new SqlNormalizer();
    /** 移行前SQLの入力エリア。 */
    private JTextArea beforeSqlArea;
    /** 移行後SQLの入力エリア。 */
    private JTextArea afterSqlArea;
    /** 移行前SQLの正規化結果表示エリア。 */
    private JTextArea beforeNormalizedArea;
    /** 移行後SQLの正規化結果表示エリア。 */
    private JTextArea afterNormalizedArea;
    /** 一致/差異を表示するラベル。 */
    private JLabel diffLabel;

    /**
     * エントリポイント。Look and Feel を設定し、Swing UI を EDT で起動する。
     *
     * @param args コマンドライン引数（未使用）
     */
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new SqlNormalizeApp().createAndShow());
    }

    /**
     * メインウィンドウを構築し、表示する。
     * 上部に移行前/移行後入力、中央にボタン、下部に正規化結果を配置する。
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
        inputPanel.add(wrapWithScroll(beforeSqlArea, "移行前 SQL"));
        inputPanel.add(wrapWithScroll(afterSqlArea, "移行後 SQL"));
        main.add(inputPanel, BorderLayout.NORTH);

        // 正規化ボタン（直下に結果、余白なし）
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        JButton normalizeBtn = new JButton("正規化して比較");
        normalizeBtn.setFont(normalizeBtn.getFont().deriveFont(Font.BOLD, 14f));
        normalizeBtn.addActionListener(e -> onNormalize());
        buttonPanel.add(normalizeBtn);

        JPanel resultPanel = new JPanel(new GridLayout(1, 2, 12, 0));
        beforeNormalizedArea = createReadOnlySqlArea();
        afterNormalizedArea = createReadOnlySqlArea();
        resultPanel.add(wrapWithScroll(beforeNormalizedArea, "移行前（正規化）"));
        resultPanel.add(wrapWithScroll(afterNormalizedArea, "移行後（正規化）"));
        JPanel resultWrapper = new JPanel(new BorderLayout(8, 4));
        resultWrapper.add(resultPanel, BorderLayout.CENTER);
        diffLabel = new JLabel(" ");
        diffLabel.setFont(diffLabel.getFont().deriveFont(Font.BOLD, 14f));
        JPanel labelPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        labelPanel.add(diffLabel);
        resultWrapper.add(labelPanel, BorderLayout.SOUTH);

        JPanel centerPanel = new JPanel(new BorderLayout(0, 0));
        centerPanel.add(buttonPanel, BorderLayout.NORTH);
        centerPanel.add(resultWrapper, BorderLayout.CENTER);
        main.add(centerPanel, BorderLayout.CENTER);

        loadState();

        frame.setContentPane(main);
        frame.setSize(920, 720);
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
        JTextArea area = new JTextArea(8, 40);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setLineWrap(false);
        area.setTabSize(4);
        return area;
    }

    /**
     * 読み取り専用の正規化結果表示用テキストエリアを生成する。
     *
     * @return 編集不可の等幅 JTextArea
     */
    private JTextArea createReadOnlySqlArea() {
        JTextArea area = new JTextArea(10, 50);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setLineWrap(false);
        area.setTabSize(4);
        area.setEditable(false);
        return area;
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

    /**
     * 「正規化して比較」ボタン押下時の処理。
     * 両入力エリアのSQLを正規化し、結果エリアに表示。一致/差異をラベルに表示する。
     */
    private void onNormalize() {
        String before = beforeSqlArea.getText();
        String after = afterSqlArea.getText();

        String normBefore = normalizer.normalize(before);
        String normAfter = normalizer.normalize(after);

        beforeNormalizedArea.setText(normBefore);
        afterNormalizedArea.setText(normAfter);

        boolean same = normBefore.equals(normAfter);
        diffLabel.setText(same ? "一致しています。" : "差異があります。");
        diffLabel.setForeground(same ? new Color(0, 120, 0) : Color.RED);
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
