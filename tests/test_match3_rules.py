import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]

def module(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/f'{name}.py')
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value

rules=module('match3_rules');labels=module('match3_apply_cell_labels');flywheel=module('match3_flywheel');ranking=module('evaluate_match3_ranking')

class RuleContractTests(unittest.TestCase):
    def binding(self):
        return {'reviewed':True,'evidence':'hand_reviewed_frozen_fox','rule_id':'ordinary_uncovered_animal','rule_version':1,
                'state':{'kind':'ANIMAL','color':'R','layers':0,'floor_ice':0,'cover':'FROZEN_ICE','cover_layers':2,
                         'effect':'NONE','gate':'OPEN'}}
    def test_catalog_covers_every_reference_entry_and_native_registry(self):
        result=rules.validate(json.loads(rules.CATALOG.read_text()),rules.REFERENCE.read_bytes(),
            (ROOT/'android/app/src/main/java/com/openkhub/sensefield/Match3Rules.java').read_text())
        self.assertEqual(67,result['rules']);self.assertEqual(67,result['reference_entries'])
        self.assertEqual('pending',result['independent_acceptance'])
    def test_catalog_does_not_upgrade_missing_evidence_or_accept_changed_reference(self):
        catalog=json.loads(rules.CATALOG.read_text())
        with self.assertRaises(ValueError):rules.validate(catalog,rules.REFERENCE.read_bytes()+b' changed')
        catalog['rules'][0]['live_validation']='verified'
        with self.assertRaises(ValueError):rules.validate(catalog,rules.REFERENCE.read_bytes())
    def test_cover_variant_requires_separate_review_and_cannot_grant_movement(self):
        truth=labels.record('R','frozen',False,self.binding())
        self.assertEqual('FROZEN_ICE',truth['rule_binding']['state']['cover']);self.assertFalse(truth['swappable'])
        for arch,permission in [('ordinary',False),('frozen',True),('unknown',None)]:
            with self.assertRaises(ValueError):labels.record('R',arch,permission,self.binding())
    def test_name_only_typed_identity_remains_unverified_and_does_not_grant_actions(self):
        for code in ('bird','gold_pod','cannon','dye_baby','energy_bottle'):
            truth=labels.record(code,'state',None)
            self.assertEqual('unverified',truth['rule']);self.assertNotIn('rule_binding',truth)
        with self.assertRaises(ValueError):labels.record('bird','state',True)
    def test_binding_cannot_invent_layers_energy_direction_or_object_topology(self):
        for key,value in [('cover_layers',3),('charge',True),('direction',4),('object_id',8),('topology',{})]:
            binding=self.binding();binding['state'][key]=value
            with self.assertRaises(ValueError):rules.validate_binding(binding)
        binding=self.binding();binding['rule_version']=2
        with self.assertRaises(ValueError):rules.validate_binding(binding)
        for color in ('',None,'RR'):
            binding=self.binding();binding['state']['color']=color
            with self.assertRaises(ValueError):rules.validate_binding(binding)
    def test_equal_state_identity_does_not_depend_on_review_provenance_or_omitted_defaults(self):
        first=self.binding();second=copy.deepcopy(first);second['evidence']='another_human_review'
        second['state'].update(charge=-1,capacity=-1,direction=-1)
        self.assertEqual(rules.state_identity(first),rules.state_identity(second))
    def test_pipeline_compiles_only_reviewed_bound_states_from_training_sessions(self):
        sample={'id':'a:r0c0','source':'train/a:r0c0','group':'train','pixels':[0x445566]*256,'patch':[0x335577]*256}
        family={'family':'frozen-fox','role':'cell','samples':[sample]}
        registry={'families':[family]};binding=self.binding()
        flywheel.apply_review(registry,{'registry_sha256':flywheel.digest(registry),'labels':{'frozen-fox':'R'},
            'archetypes':{'frozen-fox':'frozen'},'swappable':{'frozen-fox':False},'reviewed':{'frozen-fox':True},
            'bindings':{'frozen-fox':binding}})
        gallery=flywheel.build_gallery(registry,{'groups':{'train':'train','unseen':'test'}})
        self.assertEqual(1,len(gallery['cells']));self.assertEqual([],gallery['ordinary_envelopes'])
        self.assertEqual(binding,gallery['cells'][0]['rule_binding'])
        self.assertEqual(hashlib.sha256(rules.CATALOG.read_bytes()).hexdigest(),gallery['metadata']['rule_catalog_sha256'])
        family['samples'][0]['group']='unseen'
        with self.assertRaises(ValueError):flywheel.build_gallery(registry,{'groups':{'train':'train','unseen':'test'}})
    def test_new_action_evaluation_scores_taps_and_preserves_legacy_swap_format(self):
        tap={'action_type':'ACTIVATE','from_row':0,'from_col':0,'to_row':0,'to_col':0,
             'rule_id':'line_horizontal','rule_version':1,'direct_units':1}
        predictions={'rankings':[{'id':'a','candidates':[tap]}],'boards':{'a':{'rows':2,'cols':2}}}
        truth={'boards':[{'id':'a','reviewed':True,'relevant_actions':[tap],'legal_actions':[tap],
                        'rule_truth':[{**tap,'certain_units':1}]}]}
        result=ranking.evaluate(predictions,truth)
        self.assertEqual(1,result['top1_hits']);self.assertEqual(0,result['illegal_actions']);self.assertEqual(0,result['wrong_rule_claims'])
        bad=copy.deepcopy(predictions);bad['rankings'][0]['candidates'][0]['direct_units']=4
        self.assertEqual(1,ranking.evaluate(bad,truth)['wrong_rule_claims'])
        with self.assertRaises(ValueError):ranking.action_key({**tap,'to_col':1})
        self.assertEqual(('SWAP',((0,0),(0,1))),ranking.action_key([0,0,0,1]))
    def test_goal_icon_cannot_import_cell_specific_state_bindings(self):
        # Modes need task evidence, separately from an animal/cover state.
        binding=self.binding();binding['state']['floor_ice']=1
        with self.assertRaises(ValueError):rules.validate_binding(binding)
    def test_goal_mode_is_reviewed_separately_from_appearance_and_cover_state(self):
        goal={'reviewed':True,'event_mode':'HATCH','evidence':'human_confirmed_level_hatch_task'}
        registry={'families':[{'family':'chick-task','role':'goal','samples':[{
            'id':'a:goal0','source':'train/a:goal0','group':'train','pixels':[0x445566]*256}]}]}
        review={'registry_sha256':flywheel.digest(registry),'labels':{'chick-task':'Y'},
            'archetypes':{'chick-task':'ordinary'},'swappable':{'chick-task':None},
            'reviewed':{'chick-task':True},'goal_bindings':{'chick-task':goal}}
        flywheel.apply_review(registry,review)
        gallery=flywheel.build_gallery(registry,{'groups':{'train':'train','test':'test'}})
        self.assertEqual(goal,gallery['goals'][0]['goal_binding'])
        for binding in ({**goal,'reviewed':False},{**goal,'event_mode':'DELIVER'},{**goal,'evidence':''}):
            with self.assertRaises(ValueError):rules.validate_goal_binding(binding,'CHICK')
        registry['families'][0]['role']='cell';review['registry_sha256']=flywheel.digest(registry)
        with self.assertRaises(ValueError):flywheel.apply_review(registry,review)
    def test_vine_goal_uses_unlock_and_cannot_grant_a_cell_identity(self):
        goal={'reviewed':True,'event_mode':'UNLOCK','evidence':'reviewed_vine_hud_and_reference_unlock_rule'}
        self.assertEqual(goal,rules.validate_goal_binding(goal,'LOCK'))
        with self.assertRaises(ValueError):rules.validate_goal_binding({**goal,'event_mode':'CLEAR'},'LOCK')
        with self.assertRaises(ValueError):rules.validate_goal_binding(goal,'COOKIE')
        registry={'families':[{'family':'vine','role':'goal','samples':[{'id':'a:goal0','source':'train/a:goal0','group':'train','pixels':[0x445566]*256}]}]}
        review={'registry_sha256':flywheel.digest(registry),'labels':{'vine':'lock'},'archetypes':{'vine':'cover'},
                'swappable':{'vine':None},'reviewed':{'vine':True},'goal_bindings':{'vine':goal}}
        flywheel.apply_review(registry,review)
        gallery=flywheel.build_gallery(registry,{'groups':{'train':'train','test':'test'}})
        self.assertEqual('LOCK',gallery['goals'][0]['kind']);self.assertEqual(goal,gallery['goals'][0]['goal_binding'])
        registry['families'][0]['role']='cell';review['registry_sha256']=flywheel.digest(registry)
        with self.assertRaises(ValueError):flywheel.apply_review(registry,review)
    def test_independent_typed_replay_requires_rule_gallery_source_and_legality_provenance(self):
        tap={'action_type':'ACTIVATE','from_row':0,'from_col':0,'to_row':0,'to_col':0,
             'rule_id':'line_horizontal','rule_version':1,'direct_units':1}
        provenance={'independent':True,'manifest_sha256':'a'*64,'frame_sha256':{'a':'b'*64},
            'source_sha256':'c'*64,'rule_catalog_sha256':'d'*64,'gallery_sha256':'e'*64,'rule_catalog_id':'test-rules'}
        predicted={**provenance,'frozen':True,'format':'match3-actions-replay-v1','rankings':[{'id':'a','candidates':[tap]}]}
        truth={**provenance,'boards':[{'id':'a','reviewed':True,'relevant_actions':[tap],'legal_actions':[tap],
            'rule_truth':[{**tap,'certain_units':1}]}]}
        self.assertTrue(ranking.evaluate(predicted,truth,True)['independent'])
        for key in ('source_sha256','rule_catalog_sha256','gallery_sha256','rule_catalog_id'):
            bad=copy.deepcopy(truth);bad.pop(key)
            with self.assertRaises(ValueError):ranking.evaluate(predicted,bad,True)
        bad=copy.deepcopy(truth);bad['boards'][0].pop('legal_actions')
        with self.assertRaises(ValueError):ranking.evaluate(predicted,bad,True)

if __name__=='__main__':unittest.main()
