---
title: CLOSE statement [YSQL]
headerTitle: CLOSE
linkTitle: CLOSE
description: Use the CLOSE statement to 'drop' a cursor.
menu:
  preview:
    identifier: dml_close
    parent: statements
type: docs
---

{{< warning title="YSQL currently supports only fetching rows from a cursor consecutively in the forward direction." >}}
See the subsection [Beware Issue #6514](../../../cursors/#beware-issue-6514) in the generic section [Cursors](../../../cursors/).
{{< /warning >}}

## Synopsis

Use the `CLOSE` statement to "drop" a _cursor_. See the generic section [Cursors](../../../cursors/). The `CLOSE` statement is used jointly with the [`DECLARE`](../dml_declare), [`MOVE`](../dml_move), and [`FETCH`](../dml_fetch) statements.

## Syntax

<ul class="nav nav-tabs nav-tabs-yb">
  <li >
    <a href="#grammar" class="nav-link active" id="grammar-tab" data-toggle="tab" role="tab" aria-controls="grammar" aria-selected="true">
      <img src="/icons/file-lines.svg" alt="Grammar Icon">
      Grammar
    </a>
  </li>
  <li>
    <a href="#diagram" class="nav-link" id="diagram-tab" data-toggle="tab" role="tab" aria-controls="diagram" aria-selected="false">
      <img src="/icons/diagram.svg" alt="Diagram Icon">
      Diagram
    </a>
  </li>
</ul>

<div class="tab-content">
  <div id="grammar" class="tab-pane fade show active" role="tabpanel" aria-labelledby="grammar-tab">
  {{% includeMarkdown "../../syntax_resources/the-sql-language/statements/close.grammar.md" %}}
  </div>
  <div id="diagram" class="tab-pane fade" role="tabpanel" aria-labelledby="diagram-tab">
  {{% includeMarkdown "../../syntax_resources/the-sql-language/statements/close.diagram.md" %}}
  </div>
</div>

## Semantics

`CLOSE` drops a _cursor_. Use this statement so that you can shorten the lifetime a _cursor_—typically in order to save resources.

{{< note title="CLOSE is outside the scope of rolling back to a savepoint." >}}
If a _cursor_ is closed after a savepoint to which you later roll back, the effect of `CLOSE` is _not_ rolled back—in other words the closed _cursor_ continues no longer to exist.
{{< /note >}}

### *name*

A _cursor_ is identified only by an unqualified name and is visible only in the session that declares it. This determines the uniqueness scope for its name. (The name of a  _cursor_ is like that of a _prepared statement_ in this respect.)

Using the keyword `ALL` in place of the name of an extant _cursor_ closes every extant _cursor_.

## Simple example


```plpgsql
close all;

start transaction;
  declare "Cur-One" no scroll cursor without hold for
  select 17 as v;

  declare "Cur-Two" no scroll cursor with hold for
  select 42 as v;

  select name, is_holdable::text, is_scrollable::text
  from pg_cursors
  order by name;
  
  close "Cur-One";
commit;

select name, is_holdable::text, is_scrollable::text
from pg_cursors
order by name;

fetch all from "Cur-Two";
```

This is the result from the first _pg_cursors_ query:

```output
  name   | is_holdable | is_scrollable 
---------+-------------+---------------
 Cur-One | false       | false
 Cur-Two | true        | false
```

This is the result from the second _pg_cursors_ query:

```output
  name   | is_holdable | is_scrollable 
---------+-------------+---------------
 Cur-Two | true        | false
```

And this is the result from _fetch all from "Cur-Two"_:

```output
 v  
----
 42
```

## See also

- [`DECLARE`](../dml_declare)
- [`MOVE`](../dml_move)
- [`FETCH`](../dml_fetch)
