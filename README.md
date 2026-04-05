# SQL normalization and comparison tool (for system migration)

A Swing application that **absorbs alias differences** between pre- and post-migration SQL, normalizes statements, and compares them.

## Stack

- **Java 11**
- **JSqlParser** (SQL parsing and AST manipulation)
- **Maven**
- **Swing** (GUI)
- **FlatLaf** (light theme)
- **H2** (JDBC connection and SQL execution)
- **P6Spy** (logs executed SQL → `sqy.log`)

## Features

- Paste **pre-migration SQL** and **post-migration SQL** into text areas (**multiple statements separated by newlines**; newlines inside `'` string literals do not split statements). **Prefer one statement per line** (a newline in the middle splits into another statement; for multi-line SQL, keep it on one line or put newlines only inside string literals). For **log-style** lines like `timestamp|type|SELECT ...`, only the part **to the right of the last `|` outside single or double quotes** is treated as SQL (`SqlNormalization.extractSqlAfterLastUnquotedPipe`). Lines where the part after the pipe is empty or only `;` (optional whitespace) are skipped (`isBlankOrSemicolonOnlySql`).
- **Normalize and compare** normalizes each statement and shows one row per alignment step in the **pre (normalized)** / **post (normalized)** grids. Columns: **#**, **Compare**, **Distance** (Levenshtein for aligned pairs; **—** for gaps), **SQL** (preview; header switches between **normalized SQL** and **original SQL**). Alignment uses Needleman–Wunsch (substitution cost = Levenshtein). Pairs that match after normalization show **一致** (match); otherwise **差異** (diff); rows with a statement on only one side show **—**. **GAP cost** slider and **Re-align** recompute alignment (moving the slider alone does not). **Show diff rows only** filters to **差異** rows (**#** keeps original statement numbers). Selecting a row shows the full statement in the **pre (formatted)** / **post (formatted)** panes below. Radio buttons **Normalized SQL** / **Original SQL** switch both the grid SQL column and the formatted panes (original text is still pretty-printed like the panes; line-level diff highlighting applies in both modes).
- **Copy normalized results** (under the grid, next to **Show diff rows only**) copies the latest comparison to the **clipboard** as **HTML (CF_HTML)** and **plain text (TSV)**. In Excel, the **HTML** flavor is used: columns **#**, **Compare**, **Distance**, **Pre (original SQL)**, **Post (original SQL)**, **Pre (normalized SQL)**, **Post (normalized SQL)**—each SQL cell uses the same formatting as the panes (syntax colors and line-level diff red). Borders are set on each `th`/`td` (no nested borderless tables). Long SQL is split into multiple rows at about **30 lines** per cell; **#**, **Compare**, and **Distance** are **repeated on each split row** (no `rowspan`) so Excel **filters** work per row. SQL uses multi-line cells (`br` with `mso-data-placement:same-cell` and `white-space:pre-wrap`). If text is clipped, turn on **Wrap text** in Excel. **Plain text** is TSV (SQL collapsed to one line, **Pre** columns get a trailing `;` when missing, UTF-8 BOM); Notepad and similar use this flavor.
- Normalization behavior:
  - **Table aliases** → `t1`, `t2`, … in **FROM** / **JOIN** order (Oracle **`DUAL`** gets no alias; columns from DUAL are not `tN`-qualified).
  - **All columns** → qualified as `tN.column` (subqueries in `EXISTS` / `IN` are normalized recursively the same way; DUAL columns stay unqualified).
  - **SELECT list aliases** → removed.
  - **SQL keywords** → uppercased (SELECT, FROM, NEXTVAL, etc.).
  - **Whitespace** → runs of whitespace collapsed to a single space.
  - **Layout (for normalized grid / comparison)** → **SELECT** / **GROUP BY** / **ORDER BY** lists as **`col, col, …`** (no space before comma, space after) on **one line**. **INSERT**: `INTO table (col, …)` and **`VALUES (expr, …)`** also **one line** (multi-row `VALUES`: `),` then newline and indented next `(…)`). **INSERT … SET** / **ON DUPLICATE KEY UPDATE** as `col = expr, …` on **one line**. **UPDATE** **SET** on **one line**. Shared rules: line breaks for `FROM` / `JOIN` / `ON` / `WHERE`, `AND`/`OR` before breaks in **WHERE** / **ON** / **HAVING**, and multi-line bodies inside **`EXISTS` / `IN (SELECT …)`**.
  - **Layout (formatted panes and Excel HTML)** → same clause structure, but **SELECT** / **GROUP BY** / **ORDER BY** items split with **leading `, `** on new lines + indent. **INSERT** column lists and **`VALUES`** expressions likewise (column-name `/* … */` comments on VALUES only in this mode). **UPDATE** **SET** assignments with leading comma per line. **DELETE** and other statement types use the normalized string or dedicated DELETE formatting where applicable.
  - Statements **other than SELECT / INSERT / UPDATE / DELETE** get line breaks only before major clause keywords (`formatPretty`). Layout is A5:SQL Mk-2–inspired, not a pixel-perfect replica of every option screen.
- The grid **Compare** column shows match/diff per row. Pane colors: **line-level diff** **red**, **keywords** **blue**, **string and numeric literals** **purple**, **block comments** **green**, normalized aliases **`tN.`** and the **`tN`** word right after **`FROM` / `JOIN`** only **gray** (e.g. `LOG_SEQUENCE.NEXTVAL` is not grayed).
- On exit, inputs are saved and restored on next launch.
- The **H2DB (P6Spy)** tab is **hidden by default** (`SHOW_H2_DB_TAB = false` in `SqlNormalizeApp`). Set it to `true` to show the tab again for JDBC execution (auto-commit toggle, commit/rollback).
- When H2 is used through **P6Spy**, executed SQL is written to **sqy.log** in the working directory.
- **spy.properties** is created on first run from **spy.properties.template** (classpath resource). Edit **spy.properties** to change the log file name and other P6Spy settings.

## Build and run

```bash
# Build
mvn compile

# Run (Swing GUI)
mvn exec:java
```

## Example

| Pre-migration | Post-migration | After normalization (identical) |
|----------------|----------------|----------------------------------|
| `SELECT a.id FROM users a` | `SELECT u.id FROM users u` | `SELECT t1.id FROM users t1` |
| `FROM users a JOIN users b ON a.id = b.parent_id` | Same with different aliases | `FROM users t1 JOIN users t2 ON t1.id = t2.parent_id` |

If only alias names differ, normalized text matches; if every **Compare** cell is **一致**, you can treat the statements as the same SQL for comparison purposes.

---

## Method reference

### Class: `SqlNormalizeApp`

Main GUI class: window layout, events, save/restore of inputs.

| Method | Description |
|--------|-------------|
| `main(String[] args)` | Entry point: sets look-and-feel and starts the Swing UI on the EDT. |
| `createAndShow()` | Builds and shows the main window. SQL comparison uses vertical splits (inputs + normalize / results; results split into grid + copy row + formatted panes). |
| `createSqlArea(String title)` | Creates an editable SQL text area. |
| `createReadOnlySqlPane()` | Creates a read-only `JTextPane` for formatted SQL. |
| `setNormalizedSqlDisplay` (pane, SQL, diff ranges) | Renders SQL with keyword/alias colors, then applies red for line-level diffs (via [java-diff-utils](https://github.com/java-diff-utils/java-diff-utils)). |
| `wrapWithScroll(JTextArea area, String title)` | Wraps a text area in a titled border and scroll pane. |
| `onNormalize()` | Splits statements by newline, normalizes, refreshes the grid (#, Compare, Distance, SQL), selects the first row, updates formatted panes. |
| `copyNormalizedResultsToClipboard(Component)` | Copies HTML table (CF_HTML) and TSV via `HtmlWindowsClipboard`; Excel uses HTML for colors and line breaks. |
| `getStatePath()` | Path to the state file (`sql-normalize-state.txt` in the working directory). |
| `loadState()` | Restores saved pre/post SQL into the text areas. |
| `saveState()` | Saves current pre/post SQL; called on window close. |

### Class: `SqlA5Formatter`

Formats SELECT statements in an A5:SQL Mk-2–like layout. `formatSelect(Statement)` keeps list clauses on one line (normalization). `formatSelect(Statement, boolean)` with `true` splits SELECT / GROUP BY / ORDER BY with leading commas (display panes). WHERE / ON / HAVING break before `AND`/`OR`; subqueries use `appendSelectBody`.

### Class: `SqlInsertFormatter`

`formatInsert(Insert)` keeps columns and VALUES on one line (normalization). `formatInsert(Insert, valueColumnComments, leadingCommaLists)` can emit leading-comma multiline layout and column comments (display panes). WITH bodies delegate to `appendSelectBody`.

### Class: `SqlUpdateFormatter`

`formatUpdate(Update)` keeps SET on one line (normalization). `formatUpdate(Update, true)` uses leading-comma multiline SET (display panes). WHERE uses `appendExpressionBrokenOnAndOr`; ORDER BY breaks per element. `UPDATE … FROM` joins use `appendJoin`.

### Class: `SqlDeleteFormatter`

Formats `DELETE` with `DELETE` / `FROM` / table on separate indented lines; WHERE uses `appendExpressionBrokenOnAndOr` (`formatDelete` is public). Supports multi-table forms, USING, JOIN, ORDER BY, LIMIT.

### Class: `SqlNormalizer`

Normalizes SQL: alias unification, keyword casing, whitespace, and A5-style layout.

| Method | Description |
|--------|-------------|
| `normalize(String sql)` | **Public API.** Normalizes a SQL string (with layout). Returns `""` for null. On parse failure, normalizes whitespace and keywords only. |
| `formatNormalizedSqlForDisplayPane(String)` | Re-parses one statement and formats for the panes / Excel (leading-comma lists for SELECT/INSERT/UPDATE; DELETE via `SqlDeleteFormatter`; VALUES column comments for INSERT when applicable). |
| `keywordHighlightPattern()` | `Pattern` for keyword highlighting (longer tokens first). Static. |
| `formatPretty(String oneLine)` | Inserts line breaks and indent before major keywords on a single-line SQL string. |
| `compareIgnoreLayout(String a, String b)` | Compares strings ignoring newlines and leading indentation on each line. Static. |
| `normalizeParsed(Statement stmt)` | Normalizes a parsed `Statement` and returns the string. |
| `normalizePlainSelect` (internal) | Normalizes one `PlainSelect`; `t` numbers are shared with the caller (not reset per subquery). Correlated columns use outer alias → `tN`. |
| `getAliasOrTableName(FromItem item)` | Returns alias or, if absent, table name. |
| `setFromItemCanonicalAlias(FromItem item, String canonical)` | Sets normalized alias (`t1`, `t2`, …) on a FROM item. |
| `normalizeWhitespaceAndKeywords(String sql)` | Collapses whitespace and uppercases tokens in `KEYWORDS`. |
| `normalizeFallback(String sql)` | Fallback when parsing fails: whitespace and keywords only. |

### Main fields (`SqlNormalizer`)

| Field | Description |
|-------|-------------|
| `MULTI_SPACE` | Regex collapsing runs of whitespace. |
| `KEYWORDS` | Keywords uppercased during normalization (SELECT, FROM, NEXTVAL, CURRVAL, etc.). |
