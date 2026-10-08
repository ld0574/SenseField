import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import prepare_bundled_speech as speech
from verify_android_release import elf_load_alignments


class BundledSpeechGateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.assets = Path(self.directory.name) / 'speech'
        self.assets.mkdir()
        self.inventory = {kind: [{'id': hashlib.sha256(text.encode()).hexdigest(), 'text': text}]
                          for kind, text in [('fixed', '左上'), ('guide', '说明结束。')]}
        data = b'OggS' + b'\0' * 16 + b'OpusHead\x01\x01' + b'\0' * 16
        self.manifest = {'schema': 1, 'test_fixture': True, 'files': []}
        for (voice, rate, identity), text in speech.expected(self.inventory).items():
            relative = (f'guide/{identity}.ogg' if text == '说明结束。'
                        else f'fixed/{voice}/{rate:03}/{identity}.ogg')
            path = self.assets / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            self.manifest['files'].append(speech.entry({'id': identity, 'text': text},
                                                      voice, rate, path, self.assets, .5))
        self.save()

    def save(self):
        (self.assets / 'manifest.json').write_text(json.dumps(self.manifest))

    def test_fixture_is_complete_but_cannot_be_delivered(self):
        speech.verify(self.assets, self.inventory, allow_fixture=True)
        with self.assertRaisesRegex(ValueError, 'Test audio'):
            speech.verify(self.assets, self.inventory)

    def test_changed_audio_bytes_are_rejected(self):
        (self.assets / self.manifest['files'][0]['path']).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
            speech.verify(self.assets, self.inventory, True)

    def test_incomplete_catalog_is_rejected(self):
        self.manifest['files'].pop()
        self.save()
        with self.assertRaisesRegex(ValueError, 'Incomplete recordings'):
            speech.verify(self.assets, self.inventory, True)

    def test_swapped_voice_path_is_rejected_even_when_hash_matches(self):
        self.manifest['files'][0]['path'] = self.manifest['files'][17]['path']
        self.save()
        with self.assertRaisesRegex(ValueError, 'Invalid audio path'):
            speech.verify(self.assets, self.inventory, True)

    def test_failed_import_does_not_leave_partial_assets(self):
        destination = Path(self.directory.name) / 'failed-import'
        with patch.object(speech, 'wav_segments', return_value=[b'pcm']), \
                patch.object(speech, 'render_web', side_effect=ValueError('encoding failure')):
            with self.assertRaisesRegex(ValueError, 'encoding failure'):
                speech.import_web(Path(self.directory.name), destination, self.inventory)
        self.assertFalse(destination.exists())
        self.assertFalse(list(destination.parent.glob('.speech-import-*')))

    def fish_source(self, voice='game_female'):
        source = Path(self.directory.name) / voice
        source.mkdir()
        recording = self.inventory['fixed'][0]
        data = b'one independently downloaded recording'
        (source / '01.mp3').write_bytes(data)
        metadata = {'voice': voice, 'model_id': speech.FISH_VOICES[voice][0],
                    'source_speed': .9, 'style_selected_by_user': True,
                    'model': 'Fish Audio S2.1 Pro', 'generation_page': 'https://fish.audio/',
                    'source_rights': 'test provenance',
                    'files': [{'text': recording['text'], 'text_sha256': recording['id'],
                               'request_text': '[emphasis]' + recording['text'] + '。',
                               'source_file': '01.mp3', 'sha256': hashlib.sha256(data).hexdigest(),
                               'generation_id': 'audio_' + '1' * 32}]}
        (source / 'source.json').write_text(json.dumps(metadata))
        return source, metadata

    def test_changed_individual_source_cannot_reach_encoding(self):
        source, _ = self.fish_source()
        (source / '01.mp3').write_bytes(b'wrong recording')
        with self.assertRaisesRegex(ValueError, 'source checksum mismatch'):
            speech.fish_sources(source, self.inventory)

    def test_duplicate_or_missing_individual_phrase_is_rejected(self):
        source, metadata = self.fish_source()
        metadata['files'] *= 2
        (source / 'source.json').write_text(json.dumps(metadata))
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            speech.fish_sources(source, self.inventory)
        metadata['files'] = []
        (source / 'source.json').write_text(json.dumps(metadata))
        with self.assertRaisesRegex(ValueError, 'Incomplete'):
            speech.fish_sources(source, self.inventory)

    def test_individual_source_path_cannot_escape_source_directory(self):
        source, metadata = self.fish_source()
        metadata['files'][0]['source_file'] = '../01.mp3'
        (source / 'source.json').write_text(json.dumps(metadata))
        with self.assertRaisesRegex(ValueError, 'Invalid Fish source'):
            speech.fish_sources(source, self.inventory)

    def test_failed_individual_encoding_preserves_baseline(self):
        source, _ = self.fish_source()
        self.manifest.update(test_fixture=False, source_selected_by_user=True)
        self.save()
        before = (self.assets / 'manifest.json').read_bytes()
        destination = Path(self.directory.name) / 'failed-fish-import'
        inventory = {**self.inventory, 'voices': ['game_female', 'yunxi']}
        with patch.object(speech, 'encode', side_effect=ValueError('encoding failure')):
            with self.assertRaisesRegex(ValueError, 'encoding failure'):
                speech.import_fish(source, self.assets, destination, inventory)
        self.assertEqual(before, (self.assets / 'manifest.json').read_bytes())
        self.assertFalse(destination.exists())
        self.assertFalse(list(destination.parent.glob('.speech-import-*')))

    def test_voice_identity_and_selected_pace_cannot_be_swapped(self):
        source, metadata = self.fish_source('game_male')
        speech.fish_sources(source, self.inventory)
        for changed in [{'model_id': speech.FISH_VOICES['game_female'][0]},
                        {'source_speed': .8}, {'style_selected_by_user': False}]:
            (source / 'source.json').write_text(json.dumps({**metadata, **changed}))
            with self.assertRaisesRegex(ValueError, 'provenance'):
                speech.fish_sources(source, self.inventory)

    def test_current_ui_task_id_requires_its_matching_voice_and_page(self):
        source, metadata = self.fish_source()
        recording = metadata['files'][0]
        recording['generation_id'] = '2' * 32
        url = ('https://fish.audio/zh-CN/app/text-to-speech/?modelId='
               + metadata['model_id'] + '&taskId=' + recording['generation_id'])
        recording['source_url'] = url
        (source / 'source.json').write_text(json.dumps(metadata))
        speech.fish_sources(source, self.inventory)
        for changed in ['', url.replace('taskId=' + '2' * 32, 'taskId=' + '3' * 32),
                        url.replace(metadata['model_id'], speech.FISH_VOICES['game_male'][0])]:
            recording['source_url'] = changed
            (source / 'source.json').write_text(json.dumps(metadata))
            with self.assertRaisesRegex(ValueError, 'task URL'):
                speech.fish_sources(source, self.inventory)

    def test_duplicate_voice_batch_is_rejected_before_encoding(self):
        source, _ = self.fish_source()
        destination = Path(self.directory.name) / 'duplicate-import'
        inventory = {**self.inventory, 'voices': ['game_female', 'game_male']}
        with patch.object(speech, 'encode') as encode:
            with self.assertRaisesRegex(ValueError, 'Duplicate or inactive'):
                speech.import_fish(source, self.assets, destination, inventory, [source])
        encode.assert_not_called()
        self.assertFalse(destination.exists())

    def test_audition_pauses_and_changed_request_cannot_reach_production(self):
        source, metadata = self.fish_source('game_male')
        for text in ['[emphasis]左，上。', '[emphasis]左上。[long pause]', '右上。']:
            metadata['files'][0]['request_text'] = text
            (source / 'source.json').write_text(json.dumps(metadata))
            with self.assertRaisesRegex(ValueError, 'request text'):
                speech.fish_sources(source, self.inventory)

    def test_two_voice_import_preserves_guide_and_opposite_voice(self):
        female, _ = self.fish_source()
        male, _ = self.fish_source('game_male')
        self.manifest.update(test_fixture=False, source_selected_by_user=True)
        self.save()
        inventory = {**self.inventory, 'voices': ['game_female', 'game_male']}
        both = Path(self.directory.name) / 'both'
        replacement = Path(self.directory.name) / 'male-replaced'

        def encode(source, target, rate):
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b'OggS' + b'\0' * 16 + b'OpusHead\x01\x01' + b'\0' * 16)
            return .5

        with patch.object(speech, 'encode', side_effect=encode):
            speech.import_fish(female, self.assets, both, inventory, [male])
            original = json.loads((both / 'manifest.json').read_text())
            speech.import_fish(male, both, replacement, inventory)
        updated = json.loads((replacement / 'manifest.json').read_text())
        speech.verify(replacement, inventory)
        self.assertEqual(35, len(updated['files']))
        for recording in original['files']:
            if recording['voice'] != 'game_male':
                self.assertIn(recording, updated['files'])
                self.assertEqual((both / recording['path']).read_bytes(),
                                 (replacement / recording['path']).read_bytes())
        self.assertEqual(['fish-game-female-fixed', 'fish-game-male-fixed'],
                         [s['batch'] for s in updated['sources']])


class ElfAlignmentGateTest(unittest.TestCase):
    def elf(self, alignment=16384, offset=16384, virtual=32768):
        data = bytearray(128)
        data[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<Q', data, 32, 64)
        struct.pack_into('<HH', data, 54, 56, 1)
        struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, offset, virtual, 0, 100, 100, alignment)
        return bytes(data)

    def test_16kb_segment_is_accepted(self):
        self.assertEqual([16384], elf_load_alignments(self.elf()))

    def test_4kb_segment_or_incongruent_offset_is_rejected(self):
        for data in [self.elf(alignment=4096), self.elf(offset=4096)]:
            with self.assertRaisesRegex(ValueError, '16 KB'):
                elf_load_alignments(data)


if __name__ == '__main__':
    unittest.main()
