# SQL 正規化・比較ツール（システムマイグレーション用）

移行前と移行後で発行されるSQL文の**エイリアス差異を吸収**し、正規化したうえで比較できる Swing アプリです。

## 技術要素

- **Java 11**
- **JSqlParser**（SQL パース・AST 操作）
- **Maven**
- **Swing**（GUI）

## 機能

- **移行前 SQL** / **移行後 SQL** をそれぞれテキストエリアに貼り付け
- **「正規化して比較」** ボタンで正規化を実行
- 正規化内容:
  - **テーブルエイリアス** → 除去。同一テーブル複数回使用時は `テーブル名_1`, `テーブル名_2` で区別
  - **全カラム** → `テーブル名.カラム名` で修飾
  - **SELECT項目のエイリアス** → 除去
  - **SQLキーワード** → 大文字に統一（SELECT, FROM, NEXTVAL など）
  - **空白** → 連続空白を1スペースに
- 正規化結果を同じウィンドウの下に表示し、**一致 / 差異** を表示
- 終了時に入力内容を保存し、次回起動時に復元

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
| `SELECT a.id FROM users a` | `SELECT u.id FROM users u` | `SELECT users.id FROM users users` |
| `FROM users a JOIN users b ON a.id = b.parent_id` | 同様に別エイリアス | `FROM users users JOIN users users_1 ON users.id = users_1.parent_id` |

エイリアス名の違いだけなら正規化後に一致し、「差異があります」と出なければ実質同じSQLと判断できます。

---

## メソッド一覧と説明

### クラス: `SqlNormalizeApp`

GUI のメインクラス。ウィンドウの構築・イベント処理・入力の保存・復元を行う。

| メソッド | 説明 |
|----------|------|
| `main(String[] args)` | エントリポイント。Look and Feel を設定し、Swing UI を EDT で起動する。 |
| `createAndShow()` | メインウィンドウを構築し表示する。上部に移行前/移行後入力、中央にボタン、下部に正規化結果を配置。 |
| `createSqlArea(String title)` | 編集可能なSQL入力用テキストエリアを生成する。 |
| `createReadOnlySqlArea()` | 読み取り専用の正規化結果表示用テキストエリアを生成する。 |
| `wrapWithScroll(JTextArea area, String title)` | テキストエリアをタイトル付きボーダーとスクロールでラップする。 |
| `onNormalize()` | 「正規化して比較」ボタン押下時の処理。両入力のSQLを正規化し、結果エリアに表示し、一致/差異をラベルに表示。 |
| `getStatePath()` | 入力値の保存・読込に使うファイルのパス（カレントディレクトリ直下の `sql-normalize-state.txt`）を返す。 |
| `loadState()` | 前回保存した移行前・移行後SQLをファイルから読み込み、入力エリアに復元する。 |
| `saveState()` | 現在の移行前・移行後SQLをファイルに保存する。ウィンドウ終了時に呼ばれる。 |

### クラス: `SqlNormalizer`

SQL 文を正規化するクラス。テーブル/カラムのエイリアス統一・キーワード大文字化・空白正規化を行う。

| メソッド | 説明 |
|----------|------|
| `normalize(String sql)` | **公開API。** SQL 文字列を正規化する。null の場合は空文字を返す。パースに失敗した場合は空白・キーワードのみ正規化した文字列を返す。 |
| `normalizeParsed(Statement stmt)` | パース済みの Statement を正規化し、文字列に戻して返す。 |
| `normalizePlainSelect(PlainSelect plain)` | PlainSelect（単一 SELECT 文）を正規化する。テーブルエイリアス除去（同一テーブル複数回時は テーブル名_1, テーブル名_2）、全カラムの テーブル名.カラム名 修飾、SELECT 項目のエイリアス除去を行う。 |
| `getBaseTableName(FromItem item)` | FROM 句の要素からベースのテーブル名（またはサブクエリ時の識別子 "sub"）を取得する。 |
| `getAliasOrTableName(FromItem item)` | FromItem のエイリアス名、またはエイリアスが無い場合はテーブル名を返す。 |
| `setFromItemCanonicalAlias(FromItem item, String canonical)` | FROM 句の要素に正規化後のテーブル名（別名）を設定する。 |
| `normalizeWhitespaceAndKeywords(String sql)` | 空白を単一スペースにし、KEYWORDS に含まれる語を大文字に変換する。 |
| `normalizeFallback(String sql)` | パースに失敗した場合のフォールバック。空白とキーワードのみ正規化する。 |

### 主要フィールド（SqlNormalizer）

| フィールド | 説明 |
|------------|------|
| `MULTI_SPACE` | 連続する空白を 1 つにまとめるための正規表現。 |
| `KEYWORDS` | 大文字に統一する SQL キーワードの集合（SELECT, FROM, NEXTVAL, CURRVAL など）。 |
