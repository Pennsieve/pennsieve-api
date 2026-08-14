#!/usr/bin/env python3
"""
fix_swagger.py — auto-fix the recurring, machine-generated errors in the
Pennsieve Swagger 2.0 spec (and specs produced by the same generator).

It targets the specific mistakes this generator keeps making, all of which
cause editor.swagger.io / swagger-parser (and stricter validators) to reject
the document:

  1. Nested `enum` values.
       "enum": [["Name", "UpdatedAt"]]   ->   "enum": ["Name", "UpdatedAt"]
     An enum must be a flat list of scalars. The generator wraps the list in
     an extra array, so the validator sees one array where it expects strings.

  2. `default` values typed as strings on non-string parameters.
       {"type": "boolean", "default": "false"}  -> ... "default": false
       {"type": "integer", "default": "25"}     -> ... "default": 25
     The default must match the declared type.

  3. Definition names (and the `$ref`s pointing at them) containing
     characters that aren't legal in a URI reference — chiefly the square
     brackets, commas, and spaces the generator emits for generic types.
       "PagedResponse[FileDTO]"                    -> "PagedResponse_FileDTO"
       "AnnotationAggregateWindowResult[Option[long]]"
                                    -> "AnnotationAggregateWindowResult_Option_long"
       "Tuple2[String, String]"                    -> "Tuple2_String_String"
     editor.swagger.io rejects these with:
       "'$ref' value must be an RFC3986-compliant URI reference".

  4. (opt-in, --fix-operation-ids) operationIds containing spaces.
       "operationId": "merge user accounts" -> "mergeUserAccounts"
     Valid per spec but breaks many client-code generators. Off by default
     because it changes an identifier other tooling may reference.

Usage:
    python fix_swagger.py input.json                 # -> input.fixed.json
    python fix_swagger.py input.json -o out.json
    python fix_swagger.py input.json --fix-operation-ids
    python fix_swagger.py input.json --check          # report only, exit 1 if issues

Only the three error classes above are touched; everything else is preserved
byte-for-byte in structure (re-serialized with 2-space indent).
"""

import argparse
import json
import re
import sys


def flatten_nested_enums(node, path="", fixes=None):
    """Flatten any `enum` whose value is a single-element list containing a list."""
    if isinstance(node, dict):
        for key, value in node.items():
            if (
                    key == "enum"
                    and isinstance(value, list)
                    and len(value) == 1
                    and isinstance(value[0], list)
            ):
                fixes.append((f"{path}/enum", value, value[0]))
                node[key] = value[0]
            else:
                flatten_nested_enums(value, f"{path}/{key}", fixes)
    elif isinstance(node, list):
        for i, item in enumerate(node):
            flatten_nested_enums(item, f"{path}[{i}]", fixes)


def coerce_defaults(node, path="", fixes=None):
    """Coerce a string `default` to match a boolean/integer/number `type`."""
    if isinstance(node, dict):
        if "default" in node and "type" in node and isinstance(node["default"], str):
            t, raw = node["type"], node["default"]
            new = None
            if t == "boolean" and raw in ("true", "false"):
                new = raw == "true"
            elif t == "integer":
                try:
                    new = int(raw)
                except ValueError:
                    pass
            elif t == "number":
                try:
                    new = float(raw)
                except ValueError:
                    pass
            if new is not None:
                fixes.append((f"{path}/default", raw, new))
                node["default"] = new
        for key, value in node.items():
            coerce_defaults(value, f"{path}/{key}", fixes)
    elif isinstance(node, list):
        for i, item in enumerate(node):
            coerce_defaults(item, f"{path}[{i}]", fixes)


def _sanitize_name(name):
    """Make a definition name safe for use in a $ref URI fragment.

    Keeps letters, digits, and `_.-`; every other character (brackets,
    commas, spaces, ...) becomes `_`. Repeated underscores are collapsed
    and any leading/trailing underscores are stripped.
    """
    safe = re.sub(r"[^A-Za-z0-9_.-]", "_", name)
    safe = re.sub(r"_+", "_", safe).strip("_")
    return safe or "_"


def sanitize_definition_names(spec, fixes):
    """Rename definitions with URI-illegal characters and rewrite every $ref."""
    defs = spec.get("definitions")
    if not isinstance(defs, dict):
        return

    # Build a rename map, guaranteeing the results stay unique.
    taken = set(defs.keys())
    rename = {}
    for name in list(defs.keys()):
        safe = _sanitize_name(name)
        if safe == name:
            continue
        if safe in taken and safe != name:
            base, i = safe, 2
            while safe in taken:
                safe = f"{base}_{i}"
                i += 1
        taken.add(safe)
        rename[name] = safe

    if not rename:
        return

    # Apply the rename to the definitions block (order preserved).
    spec["definitions"] = {rename.get(k, k): v for k, v in defs.items()}
    for old, new in rename.items():
        fixes.append((f"definitions/{old}", old, new))

    # Rewrite every $ref that points at a renamed definition.
    def rewrite(node):
        if isinstance(node, dict):
            for k, v in node.items():
                if k == "$ref" and isinstance(v, str) and v.startswith("#/definitions/"):
                    target = v[len("#/definitions/"):]
                    if target in rename:
                        node[k] = "#/definitions/" + rename[target]
                else:
                    rewrite(v)
        elif isinstance(node, list):
            for v in node:
                rewrite(v)

    rewrite(spec)


def _camel(text):
    parts = re.split(r"\s+", text.strip())
    if not parts:
        return text
    return parts[0].lower() + "".join(p[:1].upper() + p[1:] for p in parts[1:])


def fix_operation_ids(spec, fixes):
    """Replace spaces in operationIds with a camelCase equivalent."""
    for path, methods in spec.get("paths", {}).items():
        if not isinstance(methods, dict):
            continue
        for method, op in methods.items():
            if isinstance(op, dict) and isinstance(op.get("operationId"), str):
                oid = op["operationId"]
                if " " in oid:
                    new = _camel(oid)
                    fixes.append((f"paths {path} {method} operationId", oid, new))
                    op["operationId"] = new


def check_refs(spec):
    """Return a sorted list of $ref targets that have no matching definition."""
    defs = spec.get("definitions", {})
    missing = set()

    def walk(n):
        if isinstance(n, dict):
            for k, v in n.items():
                if k == "$ref" and isinstance(v, str) and v.startswith("#/definitions/"):
                    if v[len("#/definitions/"):] not in defs:
                        missing.add(v)
                else:
                    walk(v)
        elif isinstance(n, list):
            for v in n:
                walk(v)

    walk(spec)
    return sorted(missing)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Fix recurring Pennsieve Swagger 2.0 errors.")
    ap.add_argument("input", help="path to the Swagger/OpenAPI 2.0 JSON file")
    ap.add_argument("-o", "--output", help="output path (default: <input>.fixed.json)")
    ap.add_argument("--fix-operation-ids", action="store_true",
                    help="also camelCase operationIds that contain spaces")
    ap.add_argument("--check", action="store_true",
                    help="report issues without writing; exit 1 if any are found")
    args = ap.parse_args(argv)

    with open(args.input, encoding="utf-8") as f:
        spec = json.load(f)

    enum_fixes, default_fixes, name_fixes, opid_fixes = [], [], [], []
    flatten_nested_enums(spec, fixes=enum_fixes)
    coerce_defaults(spec, fixes=default_fixes)
    sanitize_definition_names(spec, fixes=name_fixes)
    if args.fix_operation_ids:
        fix_operation_ids(spec, opid_fixes)

    print(f"Nested enums flattened      : {len(enum_fixes)}")
    for path, before, after in enum_fixes:
        print(f"    {path}: {before} -> {after}")
    print(f"Defaults re-typed           : {len(default_fixes)}")
    for path, before, after in default_fixes:
        print(f'    {path}: "{before}" -> {after!r}')
    print(f"Definition names sanitized  : {len(name_fixes)}")
    for path, before, after in name_fixes:
        print(f'    "{before}" -> "{after}"')
    if args.fix_operation_ids:
        print(f"operationIds de-spaced      : {len(opid_fixes)}")
        for path, before, after in opid_fixes:
            print(f'    {path}: "{before}" -> "{after}"')

    missing = check_refs(spec)
    if missing:
        print(f"WARNING: {len(missing)} unresolved $ref(s) (not auto-fixable):")
        for m in missing:
            print(f"    {m}")

    total = len(enum_fixes) + len(default_fixes) + len(name_fixes) + len(opid_fixes)

    if args.check:
        if total or missing:
            print(f"\n{total} issue(s) found.")
            return 1
        print("\nNo issues found.")
        return 0

    out = args.output or re.sub(r"\.json$", "", args.input) + ".fixed.json"
    with open(out, "w", encoding="utf-8") as f:
        json.dump(spec, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"\nFixed {total} issue(s). Wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
