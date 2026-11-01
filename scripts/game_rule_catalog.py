#!/usr/bin/env python3
"""Validate any game's declarative rule catalog against the cross-game v2 envelope.

The envelope lives in android/app/src/main/assets/game-rule-catalog-v2.schema.json and
is the normative input: the 13 per-rule field names, the five answer enumerations and the
invariant list are read from it at run time, never duplicated as literals here.
Both formats are accepted, so the frozen legacy match3 catalog validates without
being edited; new catalogs use game-rule-catalog-v2.

This ruler checks the envelope only. The entry-coverage and native-registry
cross-checks stay with scripts/match3_rules.py, which remains the authority for
match3 reference coverage.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SCHEMA = ROOT / 'android/app/src/main/assets/game-rule-catalog-v2.schema.json'

TYPE_NAMES = {'object': dict, 'array': list, 'string': str, 'boolean': bool, 'null': type(None)}


class EnvelopeError(ValueError):
    """A catalog that does not satisfy the envelope. Carries the rule it names."""


def load_json(path: Path):
    try:
        text = path.read_bytes()
    except OSError as exc:
        raise EnvelopeError('cannot read %s: %s' % (path, exc))
    try:
        return json.loads(text.decode('utf-8'))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise EnvelopeError('%s is not readable UTF-8 JSON: %s' % (path, exc))


def resolve(node, root_schema):
    """Resolve a local '#/$defs/name' reference."""
    ref = node.get('$ref')
    if not ref:
        return node
    if not ref.startswith('#/$defs/'):
        raise EnvelopeError('unsupported $ref in the envelope: ' + ref)
    name = ref.split('/')[-1]
    target = root_schema.get('$defs', {}).get(name)
    if not isinstance(target, dict):
        raise EnvelopeError('envelope $ref has no target: ' + ref)
    return target


def type_ok(value, typeName):
    if typeName == 'integer':
        return isinstance(value, int) and not isinstance(value, bool)
    if typeName == 'number':
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    expected = TYPE_NAMES.get(typeName)
    if expected is None:
        raise EnvelopeError('envelope declares an unknown type: ' + typeName)
    if typeName == 'boolean':
        return isinstance(value, bool)
    return isinstance(value, expected) and not (typeName != 'boolean' and isinstance(value, bool))


def schema_check(value, schema, root_schema, where):
    """Interpret the JSON Schema subset the envelope is written in."""
    schema = resolve(schema, root_schema)
    typeName = schema.get('type')
    if typeName and not type_ok(value, typeName):
        raise EnvelopeError('%s must be of type %s' % (where, typeName))
    if 'enum' in schema and value not in schema['enum']:
        raise EnvelopeError('%s has value %r, outside the frozen enumeration %s'
                            % (where, value, sorted(map(str, schema['enum']))))
    if isinstance(value, str):
        if 'minLength' in schema and len(value) < schema['minLength']:
            raise EnvelopeError('%s is shorter than %d characters' % (where, schema['minLength']))
        if 'pattern' in schema and not re.fullmatch(schema['pattern'], value):
            raise EnvelopeError('%s does not match the required shape /%s/' % (where, schema['pattern']))
    if isinstance(value, int) and not isinstance(value, bool) and 'minimum' in schema:
        if value < schema['minimum']:
            raise EnvelopeError('%s is below the minimum of %d' % (where, schema['minimum']))
    if isinstance(value, dict):
        props = schema.get('properties', {})
        for key in schema.get('required', []):
            if key not in value:
                raise EnvelopeError('%s is missing the required field %r' % (where, key))
        allowed = schema.get('additionalProperties', False)
        extra = sorted(set(value) - set(props))
        if extra and not (allowed is True or isinstance(allowed, dict)):
            if isinstance(allowed, list):
                undeclared = [k for k in extra if k not in allowed]
                if undeclared:
                    raise EnvelopeError('%s declares field names outside the envelope: %s'
                                        % (where, ', '.join(sorted(undeclared))))
            else:
                raise EnvelopeError('%s declares field names outside the envelope: %s'
                                    % (where, ', '.join(extra)))
        for key, sub in sorted(value.items()):
            if key in props:
                schema_check(sub, props[key], root_schema, '%s.%s' % (where, key))
        if 'minProperties' in schema and len(value) < schema['minProperties']:
            raise EnvelopeError('%s must declare at least %d entries' % (where, schema['minProperties']))
        adds = schema.get('additionalProperties')
        if isinstance(adds, dict):
            for key, sub in sorted(value.items()):
                if key not in props:
                    schema_check(sub, adds, root_schema, '%s[%s]' % (where, key))
    if isinstance(value, list):
        if 'minItems' in schema and len(value) < schema['minItems']:
            raise EnvelopeError('%s must hold at least %d items' % (where, schema['minItems']))
        items = schema.get('items')
        if isinstance(items, dict):
            for index, sub in enumerate(value):
                label = '%s[%d]' % (where, index)
                if isinstance(sub, dict) and isinstance(sub.get('rule_id'), str) and sub['rule_id']:
                    label += '(rule_id=%s)' % sub['rule_id']
                schema_check(sub, items, root_schema, label)
    return value


def rule_where(rule, index):
    rule_id = rule.get('rule_id') if isinstance(rule, dict) else None
    label = rule_id if isinstance(rule_id, str) and rule_id else '<unnamed>'
    return 'catalog.rules[%d](rule_id=%s)' % (index, label)


def check_invariant(name, spec, catalog, rules, root, source_bytes, source_text, profile):
    if name == 'unique_rule_ids':
        seen = {}
        for index, rule in enumerate(rules):
            key = rule.get('rule_id')
            if key in seen:
                raise EnvelopeError('%s repeats rule_id=%r, already declared at %s; %s'
                                    % (rule_where(rule, index), key, seen[key], spec['message']))
            seen[key] = 'catalog.rules[%d]' % index
    elif name == 'source_sha256_matches_file':
        digest = hashlib.sha256(source_bytes).hexdigest()
        declared = catalog['source']['sha256']
        if declared != digest:
            raise EnvelopeError('catalog is not pinned to the evidence bytes it quotes: declared %s, actual %s; %s'
                                % (declared, digest, spec['message']))
    elif name == 'referenced_paths_exist':
        for label, path in (('source.path', catalog['source']['path']),
                            ('engineering_entry', catalog.get('engineering_entry'))):
            if isinstance(path, str) and not (root / path).exists():
                raise EnvelopeError('%s points at a missing file: %s; %s' % (label, path, spec['message']))
        for index, rule in enumerate(rules):
            legacy = rule.get('legacy_source')
            if isinstance(legacy, str) and not (root / legacy).exists():
                raise EnvelopeError('%s legacy_source points at a missing file: %s; %s'
                                    % (rule_where(rule, index), legacy, spec['message']))
    elif name == 'unanchored_rule_provenance':
        escape = spec.get('escape_reference_evidence')
        non_empty = spec.get('escape_non_empty_field')
        if not escape or not non_empty:
            raise EnvelopeError('invariant %s does not declare its escape wording' % name)
        for index, rule in enumerate(rules):
            if rule.get('source_entries'):
                continue
            if rule.get('legacy_source'):
                continue
            if rule.get('reference_evidence') == escape and rule.get(non_empty):
                continue
            raise EnvelopeError('%s has no source_entries, no legacy_source, and does not qualify for the '
                                'unverified escape (%s=%r and a non-empty %s); %s'
                                % (rule_where(rule, index), 'reference_evidence', escape, non_empty,
                                   spec['message']))
    elif name == 'direct_effect_needs_reference_evidence':
        guarded = spec.get('guarded_engine_status')
        if not guarded:
            raise EnvelopeError('invariant %s declares no guarded_engine_status' % name)
        allowed = set(spec.get('allowed_reference_evidence', []))
        if not allowed:
            raise EnvelopeError('invariant %s declares no allowed_reference_evidence' % name)
        for index, rule in enumerate(rules):
            if rule.get('engine_status') == guarded and rule.get('reference_evidence') not in allowed:
                raise EnvelopeError('%s grants %s on reference_evidence=%r; %s'
                                    % (rule_where(rule, index), guarded, rule.get('reference_evidence'),
                                       spec['message']))
    elif name == 'verbatim_entry_anchor_when_enabled':
        if not profile.get('entry_anchor_check'):
            return
        for index, rule in enumerate(rules):
            for entry in rule.get('source_entries', []):
                if entry not in source_text:
                    raise EnvelopeError('%s anchors %r which is not a verbatim substring of source.path; %s'
                                        % (rule_where(rule, index), entry, spec['message']))
    else:
        raise EnvelopeError('envelope declares an invariant this ruler does not implement: ' + name)


def validate(catalog, schema, root=ROOT, catalog_bytes=None):
    if not isinstance(catalog, dict):
        raise EnvelopeError('a rule catalog must be a JSON object')
    envelope = schema.get('x-envelope')
    if not isinstance(envelope, dict):
        raise EnvelopeError('the envelope spec carries no x-envelope block')
    formats = envelope.get('formats', {})
    rules = catalog.get('rules')
    if catalog.get('format') not in formats:
        raise EnvelopeError('unsupported catalog format %r, the envelope accepts %s'
                            % (catalog.get('format'), sorted(formats)))
    profile = formats[catalog['format']]

    schema_check(catalog, schema, schema, 'catalog')

    for key in profile['required_top_level']:
        if key not in catalog:
            raise EnvelopeError('a %s catalog must declare %s' % (catalog['format'], key))
    for key in profile['forbidden_top_level']:
        if key in catalog:
            raise EnvelopeError('%s is a single-game field name and may not appear in a %s catalog; '
                                'put that vocabulary under "budgets" instead' % (key, catalog['format']))

    source_path = catalog['source']['path']
    resolved = root / source_path
    if not resolved.is_file():
        raise EnvelopeError('source.path does not resolve to a file: ' + source_path)
    source_bytes = resolved.read_bytes()
    source_text = source_bytes.decode('utf-8', 'replace')

    for spec in envelope['invariants']:
        check_invariant(spec['name'], spec, catalog, rules, root, source_bytes, source_text, profile)

    five_questions = envelope['five_questions']
    enumerations = schema['$defs']['rule']['properties']
    frozen = {}
    coverage = {}
    for field in sorted(five_questions):
        values = sorted({rule[field] for rule in rules}, key=repr)
        coverage[field] = {value: sum(1 for rule in rules if rule[field] == value) for value in values}
        declared = enumerations[field]['enum']
        unexpected = sorted(set(values) - set(declared), key=repr)
        if unexpected:
            raise EnvelopeError('%s uses values outside the frozen enumeration: %s'
                                % (field, ', '.join(map(repr, unexpected))))
        frozen[field] = declared

    support = {status: sum(1 for rule in rules if rule['engine_status'] == status)
               for status in enumerations['engine_status']['enum']}
    report = {
        'format': catalog['format'],
        'game': catalog.get('game'),
        'id': catalog['id'],
        'catalog_version': catalog['version'],
        'rules': len(rules),
        'source_path': source_path,
        'source_sha256': catalog['source']['sha256'],
        'catalog_sha256': hashlib.sha256(catalog_bytes).hexdigest(),
        'support': support,
        'five_question_coverage': coverage,
        'frozen_enumerations': frozen,
        'legacy_anchored_rules': [rule['rule_id'] for rule in rules
                                  if not rule.get('source_entries') and rule.get('legacy_source')],
        'declared_holes': [rule['rule_id'] for rule in rules
                           if not rule.get('source_entries') and not rule.get('legacy_source')],
        'reference_evidence_unverified': sorted(rule['rule_id'] for rule in rules
                                                if rule['reference_evidence'] == 'unverified'),
        'visual_acceptance': 'pending',
        'independent_acceptance': 'pending',
    }
    return report


def load_catalog(path: Path):
    try:
        raw = path.read_bytes()
    except OSError as exc:
        raise EnvelopeError('cannot read %s: %s' % (path, exc))
    try:
        return json.loads(raw.decode('utf-8')), raw
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise EnvelopeError('%s is not readable UTF-8 JSON: %s' % (path, exc))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalog', type=Path, required=True, help='path to the rule catalog to validate')
    parser.add_argument('--schema', type=Path, default=DEFAULT_SCHEMA, help='envelope spec')
    parser.add_argument('--root', type=Path, default=ROOT, help='repository root the catalog paths resolve against')
    parser.add_argument('--json', type=Path, help='also write the report here')
    try:
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    except (AttributeError, ValueError):
        pass
    args = parser.parse_args(argv)
    try:
        catalog, raw = load_catalog(args.catalog)
        report = validate(catalog, load_json(args.schema), args.root, raw)
    except EnvelopeError as exc:
        print('INVALID: %s' % exc, file=sys.stderr)
        return 1
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(text + '\n', encoding='utf-8')
    return 0


if __name__ == '__main__':
    sys.exit(main())
