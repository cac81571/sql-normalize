# SQL 正規化・比較ツール（システムマイグレーション用）

移行前と移行後で発行されるSQL文の**エイリアス差異を吸収**し、正規化したうえで比較できる Swing アプリです。

## 技術要素

- **Java 11**
- **JSqlParser**（SQL パース・AST 操作）
- **Maven**
- **Swing**（GUI）
- **FlatLaf**（Light テーマ）
- **H2**（JDBC 接続・SQL 実行）
- **P6Spy**（発行 SQL のログ出力 → `sqy.log`）

## 機能

- **移行前 SQL** / **移行後 SQL** をテキストエリアに貼り付け（**複数文は `;` で区切る**。`'` で囲んだ文字列内の `;` は区切りにしない）
- **「正規化して比較」** ボタンで各文を正規化し、**移行前（正規化）** / **移行後（正規化）** グリッドに 1 行ずつ表示する。列は **#**・**比較**・**SQL**（プレビュー）。同じ番号の移行前後の正規化SQLが等しければ **一致**、異なれば **差異**。片方にしか行が無い番号は **—**。行を選ぶと下の **移行前（整形）** / **移行後（整形）** にその文の全文を表示する
- **「正規化結果をコピー」**（グリッドと整形ペインのあいだ）で、直近の正規化結果をタブ区切りテキストとして **クリップボード** にコピーする。列は **#**・**比較結果**・**移行前SQL**・**移行後SQL**。SQL 列は正規化後の内容を **引用符外の改行・インデントを除いた 1 行の全文**（グリッドの **SQL** 列のような `…` 省略はしない）。**移行前SQL** 列のみ、非空なら末尾に **`;`**（既に `;` なら付けない）。改行・タブ・`"` を含むセルはダブルクォートでエスケープ。Excel 貼り付け向けに先頭に **BOM** を付与
- 正規化内容:
  - **テーブルエイリアス** → FROM / JOIN の出現順で `t1`, `t2`, … に統一（Oracle の **`DUAL`** は別名を付けず、列も `tN` 修飾しない）
  - **全カラム** → `tN.カラム名` で修飾（`EXISTS` / `IN` 内のサブクエリも同様に再帰正規化。`DUAL` 上の列は修飾なし）
  - **SELECT項目のエイリアス** → 除去
  - **SQLキーワード** → 大文字に統一（SELECT, FROM, NEXTVAL など）
  - **空白** → 連続空白を1スペースに
  - **整形** → **SELECT** は [A5:SQL Mk-2](https://a5m2.mmatsubara.com/) に近いレイアウト（`SELECT` 列は1行ずつ、2列目以降は**行頭**に `, `（カンマ＋半角空白）。`GROUP BY` / `ORDER BY` の複数要素も同様。`FROM` / `JOIN` / `ON` / `WHERE` を句ごとに改行、**WHERE** / **ON** / **HAVING** では `AND`・`OR` の前で改行、**`EXISTS` / `IN (SELECT …)`** 内のサブクエリも複行整形）。**INSERT** は `INSERT` と `INTO 表(` を改行で分け、列リストと `VALUES (` 内の式リストをそれぞれ行頭 `, ` で複行化。**UPDATE** は `SET` で代入を行頭 `, ` 付きで複行化し、`WHERE` は `AND` / `OR` の前で改行。**DELETE** は `DELETE` / `FROM` / 表名を改行で分け、`WHERE` は `AND` / `OR` の前で改行。**SELECT / INSERT / UPDATE / DELETE 以外**の文は句キーワード前での改行のみ（`formatPretty`）。A5 と完全同一ではなく、オプション画面の細かい設定までは再現していません
- グリッドの **比較** 列で行ごとの一致/差異を表示。整形ペインの着色: **行単位の差分**は**赤**、**SQLキーワード**は**青**、**文字列・数値リテラル**は**紫**、**ブロックコメント**は**緑**、正規化別名 **`tN.`** および **`FROM` / `JOIN` 直後の `tN` 語**のみ**灰色**（`LOG_SEQUENCE.NEXTVAL` 等は灰色にしない）
- 終了時に入力内容を保存し、次回起動時に復元
- **H2接続・実行** タブで JDBC 接続し SQL を実行可能（AutoCommit 切替、コミット・ロールバック対応）
- H2 接続時は **P6Spy** 経由のため、発行した SQL が **sqy.log**（実行ディレクトリ直下）に出力される
- P6Spy の設定は **spy.properties.template**（リソース）を元に、初回起動時に実行ディレクトリへ **spy.properties** が自動作成される。ログファイル名等を変えたい場合は **spy.properties** を編集する

## ビルド・実行

```bash
# ビルド
mvn compile

# 実行（Swing GUI が起動）
mvn exec:java
```

## 例

| 移行前 | 移行後 | 正規化後（同一） |
|--------|--------|------------------|
| `SELECT a.id FROM users a` | `SELECT u.id FROM users u` | `SELECT t1.id FROM users t1` |
| `FROM users a JOIN users b ON a.id = b.parent_id` | 同様に別エイリアス | `FROM users t1 JOIN users t2 ON t1.id = t2.parent_id` |

エイリアス名の違いだけなら正規化後に一致し、グリッドの **比較** 列がすべて **一致** なら実質同じ SQL と判断できます。

---

## メソッド一覧と説明

### クラス: `SqlNormalizeApp`

GUI のメインクラス。ウィンドウの構築・イベント処理・入力の保存・復元を行う。

| メソッド | 説明 |
|----------|------|
| `main(String[] args)` | エントリポイント。Look and Feel を設定し、Swing UI を EDT で起動する。 |
| `createAndShow()` | メインウィンドウを構築し表示する。入力欄、正規化ボタン、移行前（正規化）/移行後（正規化）グリッド、正規化結果コピーボタン、整形ペインを配置。 |
| `createSqlArea(String title)` | 編集可能なSQL入力用テキストエリアを生成する。 |
| `createReadOnlySqlPane()` | 読み取り専用の正規化結果表示用 JTextPane を生成する。 |
| `setNormalizedSqlDisplay`（ペイン, SQL, 差分範囲） | 正規化SQLを表示。キーワード青・修飾/別名灰色の後、移行前後で異なる行に相当する文字範囲を赤字にする（行単位比較は [java-diff-utils](https://github.com/java-diff-utils/java-diff-utils)）。 |
| `wrapWithScroll(JTextArea area, String title)` | テキストエリアをタイトル付きボーダーとスクロールでラップする。 |
| `onNormalize()` | `;` 区切りで文を分割し正規化、グリッド（#・比較・SQL）を更新、先頭行を選択して整形ペインに表示。 |
| `copyNormalizedResultsToClipboard(Component)` | 正規化済み文リストを TSV 形式（BOM 付き）でクリップボードにコピーする。SQL 列は `sqlCollapseToOneLine` で 1 行化（省略なし）。移行前SQL 列は `sqlClipboardBeforeColumn` で末尾 `;`。 |
| `getStatePath()` | 入力値の保存・読込に使うファイルのパス（カレントディレクトリ直下の `sql-normalize-state.txt`）を返す。 |
| `loadState()` | 前回保存した移行前・移行後SQLをファイルから読み込み、入力エリアに復元する。 |
| `saveState()` | 現在の移行前・移行後SQLをファイルに保存する。ウィンドウ終了時に呼ばれる。 |

### クラス: `SqlA5Formatter`

SELECT 文を A5:SQL Mk-2 に近い複行レイアウトに整形するユーティリティ（`formatSelect` のみ公開）。SELECT / GROUP BY / ORDER BY の複数要素は行頭 `, ` で区切る。WHERE / ON / HAVING の式は `AND`・`OR` の前で改行し、`EXISTS` / `IN (SELECT …)` のサブクエリ本体も `appendSelectBody` で複行にする。

### クラス: `SqlInsertFormatter`

`INSERT` 文を `INSERT` / `INTO 表(` / 列・`VALUES` リストを行頭カンマ付きで複行整形する（`formatInsert` のみ公開）。`WITH` 句付き INSERT の CTE 内 SELECT は `SqlA5Formatter.appendSelectBody` を利用する。

### クラス: `SqlUpdateFormatter`

`UPDATE` 文を `SET` の代入を行頭カンマ付きで複行化し、`WHERE` は `SqlA5Formatter.appendExpressionBrokenOnAndOr` で `AND` / `OR` 前改行する（`formatUpdate` のみ公開）。`UPDATE … FROM` の `JOIN` は `SqlA5Formatter.appendJoin` を利用する。

### クラス: `SqlDeleteFormatter`

`DELETE` 文を `DELETE` / `FROM` / 対象表を改行＋インデントで整形し、`WHERE` は `appendExpressionBrokenOnAndOr` を利用する（`formatDelete` のみ公開）。多表指定・`USING`・`JOIN`・`ORDER BY`・`LIMIT` にも対応する。

### クラス: `SqlNormalizer`

SQL 文を正規化するクラス。テーブル/カラムのエイリアス統一・キーワード大文字化・空白正規化・上記 A5 風整形を行う。

| メソッド | 説明 |
|----------|------|
| `normalize(String sql)` | **公開API。** SQL 文字列を正規化する（整形付き）。null の場合は空文字を返す。パースに失敗した場合は空白・キーワードのみ正規化し整形する。 |
| `keywordHighlightPattern()` | 正規化表示用。`KEYWORDS` を単語境界でマッチする `Pattern`（長い語を先にマッチ）。静的メソッド。 |
| `formatPretty(String oneLine)` | 1行に潰したSQLを、主要キーワードの前で改行・インデントする。単体利用も可。 |
| `compareIgnoreLayout(String a, String b)` | 改行・行頭インデントを除いた文字列同士で比較する（静的メソッド）。 |
| `normalizeParsed(Statement stmt)` | パース済みの Statement を正規化し、文字列に戻して返す。 |
| `normalizePlainSelect`（内部） | 1 つの PlainSelect を正規化。`t` 番号は呼び出し元で共有（サブクエリで 1 に戻さない）。相関列は外側スコープの別名→`tN` で修飾。 |
| `getAliasOrTableName(FromItem item)` | FromItem のエイリアス名、またはエイリアスが無い場合はテーブル名を返す。 |
| `setFromItemCanonicalAlias(FromItem item, String canonical)` | FROM 句の要素に正規化後の別名（`t1`, `t2`, …）を設定する。 |
| `normalizeWhitespaceAndKeywords(String sql)` | 空白を単一スペースにし、KEYWORDS に含まれる語を大文字に変換する。 |
| `normalizeFallback(String sql)` | パースに失敗した場合のフォールバック。空白とキーワードのみ正規化する。 |

### 主要フィールド（SqlNormalizer）

| フィールド | 説明 |
|------------|------|
| `MULTI_SPACE` | 連続する空白を 1 つにまとめるための正規表現。 |
| `KEYWORDS` | 大文字に統一する SQL キーワードの集合（SELECT, FROM, NEXTVAL, CURRVAL など）。 |
