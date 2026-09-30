# Outrigger

A language server that sits in front of [jdtls](https://github.com/eclipse-jdtls/eclipse.jdt.ls)
and adds analysis it lacks, without changing jdtls or the editor.

```
editor ──LSP──▶ outrigger ──LSP──▶ jdtls
       ◀──────             ◀──────
```

Every message is forwarded byte-for-byte, so jdtls extensions (nvim-jdtls
commands, debugging, test runners) keep working. Outrigger only rewrites what it
has something to add to. Right now that is the diagnostics jdtls publishes.

## Features

### Null analysis

Eclipse's null analysis only treats a variable as checked after a literal
`x == null` test, and assumes unannotated methods never return null. Outrigger:

- **Understands library null checks.** It drops jdtls' "may be null" warnings
  when a check provably rules null out:
  - `ObjectUtils.anyNull` / `allNotNull`
  - `Objects.isNull` / `nonNull` / `requireNonNull`
  - `StringUtils.isBlank` / `isEmpty` / `isNotBlank` / `isNotEmpty`

  It follows `!`, `||`, `&&`, if/else, ternaries, `while` and early
  `return`/`throw`, and gives up at any re-assignment of the variable.
- **Infers nullable methods.** A method whose `return` can yield `null` (directly,
  via a ternary, or via another such method) is treated as `@Nullable` without
  annotating it:
  - it reports uses of the result without a null check (source `outrigger`),
  - it drops jdtls' "Dead code" on null checks of the result,
  - it drops jdtls' "Null type mismatch" on the method's own `return`s.

Limits: inference covers methods in the same file, matched by name and argument
count. Check methods are recognised by name, whatever class they come from.

## Build

Needs Java 21 and Maven.

```sh
mvn package        # runs the tests, builds target/outrigger.jar
```

## Use

Put Outrigger in front of the command that starts jdtls:

```sh
outrigger -- java -jar .../org.eclipse.equinox.launcher_*.jar -configuration ... -data ...
```

`bin/outrigger` runs the jar with `$OUTRIGGER_JAVA`, or `java` from `PATH`.
Logs go to stderr, which editors usually collect in their LSP log. stdout is the
LSP channel.

### Neovim with nvim-jdtls

Prefix the jdtls command in the config passed to `start_or_attach`:

```lua
local jar = vim.fn.expand("~/dev/projects/outrigger/target/outrigger.jar")
if vim.fn.filereadable(jar) == 1 then
	config.cmd = vim.list_extend({ "java", "-jar", jar, "--" }, config.cmd)
end
require("jdtls").start_or_attach(config)
```

## How it works

| Package | Role |
|---|---|
| `rpc` | LSP base protocol: `Content-Length` framing, raw message bodies |
| `proxy` | Two pumps (client→server, server→client), `Feature` hooks |
| `document` | Mirrors open documents from `didOpen`/`didChange`/`didClose`, including the negotiated position encoding (UTF-8/16/32) |
| `nullness` | The null analysis, on a [JavaParser](https://javaparser.org) AST |

A `Feature` decides which diagnostics it wants to see and rewrites them.
Diagnostics for files that are not open are only analysed when they contain
something the feature cares about, since the file has to be read from disk.
Diagnostics published for an older document version than the one Outrigger
holds are passed through unchanged, and so is anything a feature fails on.

## Ideas

- Cross-file inference: resolve callees through jdtls (`textDocument/definition`)
  so methods in other classes are covered.
- Code actions, e.g. "add `@Nullable` to this method".
- Hover text explaining why a value may be null.
