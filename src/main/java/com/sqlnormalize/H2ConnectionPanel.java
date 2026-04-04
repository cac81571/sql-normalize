package com.sqlnormalize;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

/**
 * H2データベースへのJDBC接続とSQL実行を行うパネル。
 * <p>
 * AutoCommitのON/OFF切り替え、トランザクション開始・コミット・ロールバックを制御可能。
 * </p>
 */
public class H2ConnectionPanel extends JPanel {

    /** デフォルトの接続URL（P6Spy経由でH2メモリDB。発行SQLは sqy.log に出力される）。 */
    private static final String DEFAULT_URL = "jdbc:p6spy:h2:mem:test;DB_CLOSE_DELAY=-1";
    /** デフォルトの接続ユーザ名。 */
    private static final String DEFAULT_USER = "sa";
    /** デフォルトの接続パスワード。 */
    private static final String DEFAULT_PASSWORD = "";

    /** URL・ユーザの保存先ファイル名（カレントディレクトリ）。 */
    private static final String CONNECTION_STATE_FILE = "h2-connection-state.txt";
    /** 履歴に保持するURLの最大数。 */
    private static final int MAX_URL_HISTORY = 30;

    /** JDBC接続。接続中のみ非null。 */
    private Connection connection;

    /** 接続URLコンボボックス（編集可能・履歴保存）。 */
    private JComboBox<String> urlCombo;
    /** ユーザ名入力欄。 */
    private JTextField userField;
    /** パスワード入力欄。 */
    private JPasswordField passwordField;
    /** 接続ボタン。 */
    private JButton connectBtn;
    /** 切断ボタン。 */
    private JButton disconnectBtn;
    /** AutoCommit ON/OFF のチェックボックス。 */
    private JCheckBox autoCommitCheckBox;
    /** トランザクション開始ボタン（AutoCommitをOFFにする）。 */
    private JButton beginTxBtn;
    /** コミットボタン。 */
    private JButton commitBtn;
    /** ロールバックボタン。 */
    private JButton rollbackBtn;
    /** SQL入力エリア。 */
    private JTextArea sqlArea;
    /** SQL実行ボタン。 */
    private JButton executeBtn;
    /** 実行結果（SELECT）を表示するテーブル。 */
    private JTable resultTable;
    /** 結果テーブルのモデル。 */
    private DefaultTableModel resultTableModel;
    /** ステータスメッセージ表示ラベル。 */
    private JLabel statusLabel;

    static {
        try {
            Class.forName("com.p6spy.engine.spy.P6SpyDriver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("P6Spy Driver not found", e);
        }
    }

    public H2ConnectionPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(8, 8, 8, 8));

        // 接続設定
        JPanel connPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        connPanel.add(new JLabel("URL:"));
        urlCombo = new JComboBox<>();
        urlCombo.setEditable(true);
        urlCombo.setPreferredSize(new Dimension(400, urlCombo.getPreferredSize().height));
        urlCombo.putClientProperty("JTextField.columns", 45);
        connPanel.add(urlCombo);
        connPanel.add(new JLabel("User:"));
        userField = new JTextField(DEFAULT_USER, 8);
        connPanel.add(userField);
        connPanel.add(new JLabel("Password:"));
        passwordField = new JPasswordField(DEFAULT_PASSWORD, 10);
        connPanel.add(passwordField);
        connectBtn = new JButton("接続");
        connectBtn.addActionListener(e -> doConnect());
        connPanel.add(connectBtn);
        disconnectBtn = new JButton("切断");
        disconnectBtn.setEnabled(false);
        disconnectBtn.addActionListener(e -> doDisconnect());
        connPanel.add(disconnectBtn);
        add(connPanel, BorderLayout.NORTH);

        // AutoCommit / トランザクション制御
        JPanel txPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        autoCommitCheckBox = new JCheckBox("AutoCommit", true);
        autoCommitCheckBox.setEnabled(false);
        autoCommitCheckBox.addActionListener(e -> updateAutoCommit());
        txPanel.add(autoCommitCheckBox);
        beginTxBtn = new JButton("トランザクション開始");
        beginTxBtn.setEnabled(false);
        beginTxBtn.addActionListener(e -> doBeginTransaction());
        txPanel.add(beginTxBtn);
        commitBtn = new JButton("コミット");
        commitBtn.setEnabled(false);
        commitBtn.addActionListener(e -> doCommit());
        txPanel.add(commitBtn);
        rollbackBtn = new JButton("ロールバック");
        rollbackBtn.setEnabled(false);
        rollbackBtn.addActionListener(e -> doRollback());
        txPanel.add(rollbackBtn);
        // SQL入力 + 実行
        JPanel sqlPanel = new JPanel(new BorderLayout(4, 4));
        sqlPanel.setBorder(new TitledBorder(BorderFactory.createLineBorder(Color.GRAY), "SQL", TitledBorder.LEFT, TitledBorder.TOP));
        sqlArea = new JTextArea(6, 60);
        sqlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        sqlArea.setTabSize(4);
        JScrollPane sqlScroll = new JScrollPane(sqlArea);
        sqlScroll.setMinimumSize(new Dimension(0, 72));
        sqlPanel.add(sqlScroll, BorderLayout.CENTER);
        executeBtn = new JButton("実行");
        executeBtn.setEnabled(false);
        executeBtn.addActionListener(e -> doExecute());
        JPanel execBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        execBtnPanel.add(executeBtn);
        sqlPanel.add(execBtnPanel, BorderLayout.SOUTH);

        // 結果
        JPanel resultPanel = new JPanel(new BorderLayout(4, 4));
        resultPanel.setBorder(new TitledBorder(BorderFactory.createLineBorder(Color.GRAY), "実行結果", TitledBorder.LEFT, TitledBorder.TOP));
        resultTableModel = new DefaultTableModel();
        resultTable = new JTable(resultTableModel);
        resultTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        // 行数が多いと preferred 高さが膨らみ SQL 領域が潰れるのを防ぐ（スクロールで表示）
        resultTable.setPreferredScrollableViewportSize(new Dimension(480, 160));
        JScrollPane resultScroll = new JScrollPane(resultTable);
        resultScroll.setMinimumSize(new Dimension(0, 64));
        resultPanel.add(resultScroll, BorderLayout.CENTER);
        statusLabel = new JLabel(" ");
        resultPanel.add(statusLabel, BorderLayout.SOUTH);

        JPanel topSplit = new JPanel(new BorderLayout(4, 4));
        topSplit.add(txPanel, BorderLayout.NORTH);
        topSplit.add(sqlPanel, BorderLayout.CENTER);
        topSplit.setMinimumSize(new Dimension(0, 96));

        resultPanel.setMinimumSize(new Dimension(0, 88));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topSplit, resultPanel);
        split.setResizeWeight(0.42);
        split.setContinuousLayout(true);
        split.setOneTouchExpandable(true);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        loadState();
    }

    /**
     * 接続を確立する。URL・ユーザ・パスワードからDriverManager.getConnection で接続し、
     * 接続後はAutoCommitをチェックボックスの状態に合わせる。
     */
    private void doConnect() {
        Object item = urlCombo.getEditor().getItem();
        String url = (item != null ? item.toString() : "").trim();
        String user = userField.getText().trim();
        String password = new String(passwordField.getPassword());
        try {
            connection = DriverManager.getConnection(url, user, password);
            connection.setAutoCommit(autoCommitCheckBox.isSelected());
            setConnected(true);
            addUrlToHistory(url);
            saveState();
            setStatus("接続しました。");
        } catch (SQLException ex) {
            setStatus("接続エラー: " + ex.getMessage());
            JOptionPane.showMessageDialog(this, "接続エラー: " + ex.getMessage(), "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 接続を切断する。Connection を close し、接続中フラグをクリアする。
     */
    private void doDisconnect() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {}
            connection = null;
        }
        setConnected(false);
        saveState();
        setStatus("切断しました。");
    }

    /**
     * チェックボックスの状態を Connection#setAutoCommit に反映する。
     */
    private void updateAutoCommit() {
        if (connection != null) {
            try {
                connection.setAutoCommit(autoCommitCheckBox.isSelected());
                setStatus(autoCommitCheckBox.isSelected() ? "AutoCommit ON" : "AutoCommit OFF（トランザクション中）");
            } catch (SQLException ex) {
                setStatus("AutoCommit設定エラー: " + ex.getMessage());
            }
        }
    }

    /**
     * トランザクション開始。Connection#setAutoCommit(false) で明示的にトランザクションを開始する。
     */
    private void doBeginTransaction() {
        if (connection != null) {
            try {
                connection.setAutoCommit(false);
                autoCommitCheckBox.setSelected(false);
                setStatus("トランザクション開始（AutoCommit OFF）");
            } catch (SQLException ex) {
                setStatus("エラー: " + ex.getMessage());
            }
        }
    }

    /**
     * 現在のトランザクションをコミットする。
     */
    private void doCommit() {
        if (connection != null) {
            try {
                connection.commit();
                setStatus("コミットしました。");
            } catch (SQLException ex) {
                setStatus("コミットエラー: " + ex.getMessage());
            }
        }
    }

    /**
     * 現在のトランザクションをロールバックする。
     */
    private void doRollback() {
        if (connection != null) {
            try {
                connection.rollback();
                setStatus("ロールバックしました。");
            } catch (SQLException ex) {
                setStatus("ロールバックエラー: " + ex.getMessage());
            }
        }
    }

    /**
     * SQLを実行する。SELECTの場合は結果をテーブルに表示し、更新系の場合は更新件数をステータスに表示する。
     */
    private void doExecute() {
        if (connection == null) return;
        String sql = sqlArea.getText().trim();
        if (sql.isEmpty()) {
            setStatus("SQLを入力してください。");
            return;
        }
        try {
            try (Statement stmt = connection.createStatement()) {
                boolean hasResultSet = stmt.execute(sql);
                if (hasResultSet) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        showResultSet(rs);
                    }
                } else {
                    int count = stmt.getUpdateCount();
                    resultTableModel.setRowCount(0);
                    resultTableModel.setColumnCount(0);
                    setStatus(count >= 0 ? count + " 件更新しました。" : "実行しました。");
                }
            }
        } catch (SQLException ex) {
            resultTableModel.setRowCount(0);
            resultTableModel.setColumnCount(0);
            setStatus("エラー: " + ex.getMessage());
            JOptionPane.showMessageDialog(this, ex.getMessage(), "SQLエラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * ResultSetの内容を resultTable に表示する。列名と行データを DefaultTableModel に設定する。
     *
     * @param rs 表示するResultSet（カーソルは先頭でよい）
     */
    private void showResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        Vector<String> columnNames = new Vector<>();
        for (int i = 1; i <= cols; i++) {
            columnNames.add(meta.getColumnLabel(i));
        }
        Vector<Vector<Object>> rows = new Vector<>();
        while (rs.next()) {
            Vector<Object> row = new Vector<>();
            for (int i = 1; i <= cols; i++) {
                row.add(rs.getObject(i));
            }
            rows.add(row);
        }
        resultTableModel.setDataVector(rows, columnNames);
        setStatus(rows.size() + " 件取得しました。");
    }

    /**
     * 接続状態に応じて接続・切断・トランザクション・実行の各コントロールの有効/無効を切り替える。
     *
     * @param connected 接続中なら true
     */
    private void setConnected(boolean connected) {
        connectBtn.setEnabled(!connected);
        disconnectBtn.setEnabled(connected);
        autoCommitCheckBox.setEnabled(connected);
        beginTxBtn.setEnabled(connected);
        commitBtn.setEnabled(connected);
        rollbackBtn.setEnabled(connected);
        executeBtn.setEnabled(connected);
        urlCombo.setEnabled(!connected);
        userField.setEnabled(!connected);
        passwordField.setEnabled(!connected);
    }

    /** ステータスラベルにメッセージを表示する。 */
    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private Path getStatePath() {
        return Paths.get(System.getProperty("user.dir", "."), CONNECTION_STATE_FILE);
    }

    /**
     * 保存済みのURL履歴と前回のURL・ユーザを読み込み、コンボとユーザ欄に反映する。
     */
    private void loadState() {
        Path path = getStatePath();
        if (!Files.isRegularFile(path)) {
            urlCombo.addItem(DEFAULT_URL);
            urlCombo.setSelectedItem(DEFAULT_URL);
            userField.setText(DEFAULT_USER);
            return;
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            String lastUrl = DEFAULT_URL;
            String lastUser = DEFAULT_USER;
            List<String> urlList = new ArrayList<>();
            boolean inUrls = false;
            for (String line : lines) {
                if ("---".equals(line.trim())) {
                    inUrls = true;
                    continue;
                }
                if (line.startsWith("url=")) {
                    lastUrl = line.substring(4).trim();
                } else if (line.startsWith("user=")) {
                    lastUser = line.substring(5).trim();
                } else if (inUrls && !line.trim().isEmpty()) {
                    urlList.add(line.trim());
                }
            }
            urlCombo.removeAllItems();
            if (urlList.isEmpty()) {
                urlList.add(DEFAULT_URL);
                if (!lastUrl.equals(DEFAULT_URL) && !lastUrl.isEmpty()) {
                    urlList.add(0, lastUrl);
                }
            }
            int count = 0;
            for (String u : urlList) {
                if (count++ >= MAX_URL_HISTORY) break;
                urlCombo.addItem(u);
            }
            urlCombo.setSelectedItem(lastUrl);
            urlCombo.getEditor().setItem(lastUrl);
            userField.setText(lastUser);
        } catch (IOException ignored) {
            urlCombo.addItem(DEFAULT_URL);
            urlCombo.setSelectedItem(DEFAULT_URL);
            userField.setText(DEFAULT_USER);
        }
    }

    /**
     * 現在のURLを履歴に追加し、先頭に置く。最大件数を超えた分は削除する。
     */
    private void addUrlToHistory(String url) {
        if (url == null || url.isEmpty()) return;
        DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) urlCombo.getModel();
        for (int i = 0; i < model.getSize(); i++) {
            if (url.equals(model.getElementAt(i))) {
                model.removeElementAt(i);
                break;
            }
        }
        model.insertElementAt(url, 0);
        urlCombo.setSelectedIndex(0);
        while (model.getSize() > MAX_URL_HISTORY) {
            model.removeElementAt(model.getSize() - 1);
        }
    }

    /**
     * 現在のURL・ユーザとURL履歴をファイルに保存する。
     */
    private void saveState() {
        Object item = urlCombo.getEditor().getItem();
        String currentUrl = (item != null ? item.toString() : "").trim();
        if (currentUrl.isEmpty()) currentUrl = DEFAULT_URL;
        String currentUser = userField.getText();
        DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) urlCombo.getModel();
        List<String> lines = new ArrayList<>();
        lines.add("url=" + currentUrl);
        lines.add("user=" + currentUser);
        lines.add("---");
        for (int i = 0; i < model.getSize(); i++) {
            String u = model.getElementAt(i);
            if (u != null && !u.trim().isEmpty()) {
                lines.add(u);
            }
        }
        try {
            Files.write(getStatePath(), lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }
}
