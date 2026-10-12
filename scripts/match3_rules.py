#!/usr/bin/env python3
"""Audit the finite rule catalog and explicit human-reviewed state bindings.

The reference is evidence, not an executable recipe. This validates metadata;
legal actions and events still come from the finite native implementation.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / 'android/app/src/main/assets/match3-rules-v1.json'
REFERENCE = ROOT / 'docs/research/开心消消乐元素规则参考.md'
DIRECT = {'ordinary_uncovered_animal', 'line_horizontal', 'line_vertical', 'magic_bird',
          'combo_bird_color', 'combo_bird_bird', 'combo_dye_star', 'snow', 'frozen_ice',
          'locked_animal', 'coin', 'furball', 'dye_egg', 'gold_pod', 'key',
          'stationary_single_layer_ice', 'single_clear_honey'}
PARTIAL = {'blast', 'poison', 'honey_cover', 'chest', 'transport', 'invincible_furball',
           'cage', 'penguin', 'snow_bear', 'jellyfish', 'cannon', 'magic_stone', 'cuttlefish',
           'multistage_iceflower', 'gray_furball'}
OPPORTUNITY = {'egg', 'chick_hatch_goal'}
PENDING = {'combo_bird_line', 'combo_bird_blast', 'combo_line_line', 'combo_line_blast',
           'combo_blast_blast', 'combo_dye_dye', 'combo_star_star', 'combo_energy_energy',
           'combo_dye_egg_bird', 'tool_hammer', 'tool_shuffle', 'tool_extra_steps',
           'tool_start_special', 'tool_undo'}


def support(rule_id):
    return ('direct_effect' if rule_id in DIRECT else 'partial_effect' if rule_id in PARTIAL
            else 'direct_opportunity' if rule_id in OPPORTUNITY else 'pending' if rule_id in PENDING else 'state_only')


def inventory(reference):
    entries = set()
    for line in reference.splitlines():
        if line.startswith('### ') and '特效两两组合效果' not in line:
            entries.add(re.sub(r'\s*\[待核实.*$', '', line[4:]).strip())
    combo = reference.split('### 特效两两组合效果')[1].split('## 三')[0]
    entries.update('组合:' + line.split('|')[1].strip() for line in combo.splitlines()
                   if line.startswith('| ') and not line.startswith('| 组合'))
    tools = reference.split('## 六')[1].split('## 来源清单')[0]
    entries.update('道具:' + line.split('|')[1].strip() for line in tools.splitlines()
                   if line.startswith('| ') and not line.startswith('| 道具'))
    return entries


def validate(catalog, reference_bytes, native_text=None):
    if catalog.get('format') != 'match3-rule-catalog-v1' or catalog.get('version') != 1:
        raise ValueError('unsupported rule catalog')
    if catalog.get('source', {}).get('sha256') != hashlib.sha256(reference_bytes).hexdigest():
        raise ValueError('reference SHA differs from the catalog; review the changes')
    rules = catalog.get('rules', [])
    ids = [rule.get('rule_id') for rule in rules]
    if not ids or len(ids) != len(set(ids)) or any(not isinstance(x, str) or not re.fullmatch('[a-z][a-z0-9_]*', x) for x in ids):
        raise ValueError('rule ids must be unique and finite')
    covered = set()
    for rule in rules:
        if rule.get('version') != 1 or rule.get('engine_status') != support(rule['rule_id']):
            raise ValueError('catalog/native engineering support disagrees: ' + rule['rule_id'])
        if rule.get('reference_evidence') not in {'documented', 'confirmed', 'partial', 'unverified', 'legacy_reviewed'}:
            raise ValueError('invalid reference evidence')
        if rule.get('parameter_completeness') not in {'complete', 'partial', 'missing'}:
            raise ValueError('invalid parameter completeness')
        if rule.get('visual_status') != 'pending' or rule.get('live_validation') != 'pending':
            raise ValueError('this engineering catalog cannot assert new live/visual acceptance')
        if rule['reference_evidence'] in {'partial', 'unverified'} and rule['rule_id'] in DIRECT:
            raise ValueError('unverified reference cannot grant a complete new effect')
        if not isinstance(rule.get('required_evidence'), list) or not isinstance(rule.get('parameters'), dict):
            raise ValueError('missing parameter/evidence metadata')
        sources = rule.get('source_entries')
        if not isinstance(sources, list) or not sources and not rule.get('legacy_source'):
            raise ValueError('missing source provenance')
        covered.update(sources)
    expected = inventory(reference_bytes.decode('utf-8'))
    if covered != expected:
        raise ValueError(f'reference coverage differs: missing={sorted(expected-covered)}, extra={sorted(covered-expected)}')
    if native_text is not None:
        # The registry ends before knownRule; exclude helper literals and enum mappings.
        registry = native_text.split('Arrays.asList(', 1)[1].split(')));', 1)[0]
        native_ids = set(re.findall(r'"([a-z][a-z0-9_]*)"', registry))
        if native_ids != set(ids):
            raise ValueError('native finite rule registry differs from the catalog')
    return {'format': catalog['format'], 'rules': len(ids), 'reference_entries': len(expected),
            'source_sha256': catalog['source']['sha256'],
            'support': {status: sum(r['engine_status'] == status for r in rules)
                        for status in ('direct_effect', 'partial_effect', 'direct_opportunity', 'state_only', 'pending')},
            'visual_acceptance': 'pending', 'independent_acceptance': 'pending'}


KIND_RULE = {
    'BIRD': 'magic_bird', 'POISON': 'poison', 'CHEST': 'chest', 'FURBALL': 'furball',
    'INVINCIBLE_FURBALL': 'invincible_furball', 'SNOWFLAKE': 'snowflake', 'LEAF': 'leaf',
    'CLOUD': 'cloud', 'FLOWER': 'flower_cluster', 'EGG_SHELL': 'egg_shell', 'ICE_BARRIER': 'ice_barrier',
    'NEST': 'chick_hatch_goal', 'PENGUIN': 'penguin', 'SNOW_BEAR': 'snow_bear', 'SPRITE': 'sprite',
    'ONE_EYE': 'one_eye', 'DOLPHIN': 'dolphin', 'GOLD_POD': 'gold_pod', 'KEY': 'key', 'GEM': 'gem',
    'STAR': 'star', 'JELLYFISH': 'jellyfish', 'CANNON': 'cannon', 'SHELL': 'shell', 'PUFFER': 'puffer',
    'MAGIC_STONE': 'magic_stone', 'DYE_EGG': 'dye_egg', 'DYE_BABY': 'dye_baby', 'STAR_BOTTLE': 'star_bottle',
    'ENERGY_BOTTLE': 'energy_bottle', 'OCTOPUS': 'octopus', 'CUTTLEFISH': 'cuttlefish', 'MOVING_FLOOR': 'moving_floor',
    'COIN': 'coin', 'SNOW': 'snow', 'ICEFLOWER': 'multistage_iceflower', 'HONEY': 'single_clear_honey',
    'EGG': 'egg', 'COOKIE': 'identity_only_2x2', 'ANIMAL': 'ordinary_uncovered_animal', 'UNKNOWN':'unknown'}

GOAL_KINDS = {**dict(zip('ROYGBP',('RED','BEAR','CHICK','FROG','HIPPO','CAT'))),
              'coin':'COIN','snow':'SNOW','honey':'HONEY','honey_cover':'HONEY_COVER','iceflower':'ICEFLOWER',
              'cookie':'COOKIE','ice':'ICE','egg':'EGG','lock':'LOCK','flower':'FLOWER','gray_furball':'GRAY_FURBALL',
              **{kind.lower():kind for kind in ('POISON','PENGUIN','SNOW_BEAR','GOLD_POD','KEY','FURBALL',
                  'DOLPHIN','SPRITE','GEM','STAR','STAR_BOTTLE','DYE_BABY','CANNON','MAGIC_STONE')}}
GOAL_MODES = {**{kind:{'CLEAR'} for kind in ('RED','BEAR','CHICK','FROG','HIPPO','CAT','COIN','SNOW',
                  'ICE','ICEFLOWER','HONEY','HONEY_COVER','POISON','FURBALL','GRAY_FURBALL')},
              'CHICK':{'CLEAR','HATCH'},**{kind:{'RESCUE'} for kind in ('PENGUIN','SNOW_BEAR','DOLPHIN','CAGE')},
              'GOLD_POD':{'DELIVER'},'KEY':{'DELIVER'},'STAR_BOTTLE':{'CHARGE'},'DYE_BABY':{'CHARGE'},
              'CANNON':{'TRIGGER'},'MAGIC_STONE':{'TRIGGER'},'LOCK':{'UNLOCK'}}

def validate_goal_binding(binding, kind):
    if not isinstance(binding,dict) or binding.get('reviewed') is not True or not isinstance(binding.get('evidence'),str) \
            or not binding['evidence'].strip() or len(binding['evidence'])>160:
        raise ValueError('goal mode needs separate explicit review and evidence')
    if binding.get('event_mode') not in GOAL_MODES.get(kind,set()) | {'UNKNOWN'}:
        raise ValueError('goal event mode contradicts documented identity')
    return binding


def validate_binding(binding, kind=None, color=None):
    if not isinstance(binding, dict) or binding.get('reviewed') is not True or not isinstance(binding.get('evidence'),str) \
            or not binding['evidence'].strip() or len(binding['evidence'])>160:
        raise ValueError('state binding needs independent explicit human review and evidence')
    if binding.get('rule_version') != 1 or type(binding['rule_version']) is not int:
        raise ValueError('unsupported binding rule version')
    state = binding.get('state', {})
    actual_kind = state.get('kind')
    actual_color = state.get('color', '')
    if actual_kind not in KIND_RULE or kind is not None and actual_kind != kind or color is not None and actual_color != color:
        raise ValueError('binding conflicts with reviewed identity')
    effect = state.get('effect', 'UNKNOWN' if actual_kind == 'ANIMAL' else 'NONE')
    if effect not in {'NONE', 'HORIZONTAL', 'VERTICAL', 'BLAST', 'UNKNOWN'} or actual_kind != 'ANIMAL' and effect != 'NONE':
        raise ValueError('invalid effect identity')
    rule = {'HORIZONTAL': 'line_horizontal', 'VERTICAL': 'line_vertical', 'BLAST': 'blast'}.get(effect, KIND_RULE[actual_kind])
    if binding.get('rule_id') != rule or actual_kind == 'ANIMAL' and (not isinstance(actual_color,str)
            or len(actual_color)!=1 or actual_color not in 'ROYGBP') or actual_kind != 'ANIMAL' and actual_color:
        raise ValueError('binding rule conflicts with identity/effect')
    if actual_kind=='ANIMAL' and state.get('layers',-1) not in {-1,0}:
        raise ValueError('animal foreground layers must be observed zero or unknown')
    for key, low, high in (('layers', -1, 5), ('floor_ice', -1, 1), ('cover_layers', -1, 5),
                           ('charge', -1, 9999), ('capacity', -1, 9999), ('direction', -1, 3)):
        value = state.get(key, -1)
        if type(value) is not int or not low <= value <= high:
            raise ValueError('invalid binding state: ' + key)
    cover = state.get('cover', 'UNKNOWN' if actual_kind == 'ANIMAL' else 'NONE')
    if cover not in {'NONE', 'FROZEN_ICE', 'LOCK', 'HONEY', 'CAGE', 'UNKNOWN', 'GRAY_FURBALL'} or state.get('gate', 'UNKNOWN') not in {'OPEN', 'CLOSED', 'UNKNOWN'}:
        raise ValueError('invalid covering/gate')
    if cover == 'NONE' and state.get('cover_layers', 0) != 0 or cover != 'NONE' and state.get('cover_layers', -1) == 0:
        raise ValueError('cover/layer mismatch')
    if cover == 'FROZEN_ICE' and state.get('cover_layers', -1) > 2 or cover in {'LOCK','GRAY_FURBALL'} and state.get('cover_layers', -1) > 1:
        raise ValueError('unsupported cover count')
    if actual_kind=='UNKNOWN' and (cover!='GRAY_FURBALL' or state.get('layers',-1)!=-1):
        raise ValueError('unknown foreground needs independently reviewed gray cover')
    if state.get('capacity', -1) >= 0 and state.get('charge', -1) > state['capacity']:
        raise ValueError('charge exceeds capacity')
    if state.get('floor_ice', -1) > 0 and state.get('floor_rule_id') != 'stationary_single_layer_ice':
        raise ValueError('stationary ice needs separate floor provenance')
    if 'object_id' in state or 'topology' in state:
        raise ValueError('appearance families cannot grant frame-specific object membership/topology')
    if state.get('gate_rule_id','') not in {'','stone_gate'}:
        raise ValueError('unsupported gate source')
    return binding

def state_identity(binding):
    validate_binding(binding)
    state=binding['state'];animal=state['kind']=='ANIMAL'
    cover=state.get('cover','UNKNOWN' if animal else 'NONE')
    defaults={'color':'','layers':-1,'floor_ice':-1,'floor_rule_id':'','cover':cover,
        'cover_layers':0 if cover=='NONE' else -1,'effect':'UNKNOWN' if animal else 'NONE',
        'gate':'UNKNOWN','gate_rule_id':'','charge':-1,'capacity':-1,'direction':-1}
    return {'rule_id':binding['rule_id'],'rule_version':binding['rule_version'],
            'state':{'kind':state['kind'],**{key:state.get(key,value) for key,value in defaults.items()}}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--json', type=Path)
    args = parser.parse_args()
    report = validate(json.loads(CATALOG.read_text()), REFERENCE.read_bytes(),
                      (ROOT / 'android/app/src/main/java/com/openkhub/sensefield/Match3Rules.java').read_text())
    report['catalog_sha256'] = hashlib.sha256(CATALOG.read_bytes()).hexdigest()
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
