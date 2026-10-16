"""Recording guards: device routing, scoped stop, and deleted-source status."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock


spec = importlib.util.spec_from_file_location(
    "demo_recorder", Path(__file__).resolve().parents[1] / "scripts/record_android_demo.py")
recorder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recorder)


class RecordingGuardsTest(unittest.TestCase):
    def test_same_phone_usb_is_preferred_over_wireless(self):
        listing = "List of devices attached\n192.0.2.1:5555 device product:p\nUSB123 device usb:1-1 product:p\n"
        self.assertEqual(recorder.choose_device(listing)["serial"], "USB123")
        with self.assertRaises(RuntimeError):
            recorder.choose_device(listing, "192.0.2.1:5555")
        self.assertTrue(recorder.choose_device(listing, "192.0.2.1:5555", True)["wireless"])

    def test_multiple_usb_devices_need_an_explicit_serial(self):
        with self.assertRaises(RuntimeError):
            recorder.choose_device("USB1 device usb:1\nUSB2 device usb:2\n")

    def test_microphone_name_survives_index_changes(self):
        listing = "AVFoundation video devices:\n[x] [0] Camera\nAVFoundation audio devices:\n[x] [0] Iriun Webcam Audio\n[x] [2] MacBook Pro麦克风\n"
        self.assertEqual(recorder.select_microphone(recorder.microphone_devices(listing), "MacBook Pro麦克风"), (2, "MacBook Pro麦克风"))
        with self.assertRaises(RuntimeError):
            recorder.select_microphone([(0, "USB mic 1"), (1, "USB mic 2")], "USB mic")

    def test_default_output_is_checked_instead_of_any_connected_headphone(self):
        data = {"SPAudioDataType": [{"_items": [
            {"_name": "耳机", "coreaudio_device_output": 2},
            {"_name": "MacBook Pro扬声器", "coreaudio_default_audio_output_device": "spaudio_yes"},
        ]}]}
        self.assertTrue(recorder.default_audio_output(data)["speaker_detected"])
        data["SPAudioDataType"][0]["_items"][1]["_name"] = "外置耳机"
        self.assertFalse(recorder.default_audio_output(data)["speaker_detected"])

    def test_deleted_directory_is_not_reported_as_a_ready_video(self):
        with tempfile.TemporaryDirectory() as temporary:
            active = Path(temporary) / "active.json"
            active.write_text(json.dumps({"directory": str(Path(temporary) / "removed"),
                                          "status": "stopped_pending_quality_review", "pids": {}}))
            with mock.patch.object(recorder, "ACTIVE", active):
                self.assertEqual(recorder.recording_status()["status"], "sources_missing")

    def test_stop_targets_only_the_java_server_for_this_capture(self):
        host = "400 scrcpy --record /tmp/ours\n400 adb shell app_process / com.genymobile.scrcpy.Server scid=abc123\n500 adb shell app_process / com.genymobile.scrcpy.Server scid=def456\n"
        remote = "10 app_process / com.genymobile.scrcpy.Server scid=def456\n11 sh -c app_process / com.genymobile.scrcpy.Server scid=abc123\n12 app_process / com.genymobile.scrcpy.Server scid=abc123\n13 app_process / com.genymobile.scrcpy.CleanUp scid=abc123\n"
        with mock.patch.object(recorder.subprocess, "check_output", side_effect=[host, remote]), \
                mock.patch.object(recorder.subprocess, "run") as execute:
            result = recorder.disconnect_owned_server(mock.Mock(pid=400), "USB123")
        self.assertEqual(result["server_pid"], 12)
        execute.assert_called_once_with([recorder.ADB, "-s", "USB123", "shell", "kill", "-TERM", "12"], check=True, timeout=3)

    def test_ambiguous_server_is_never_killed(self):
        host = "400 adb shell app_process / com.genymobile.scrcpy.Server scid=abc123\n"
        remote = "12 app_process / com.genymobile.scrcpy.Server scid=abc123\n14 app_process / com.genymobile.scrcpy.Server scid=abc123\n"
        with mock.patch.object(recorder.subprocess, "check_output", side_effect=[host, remote]), \
                mock.patch.object(recorder.subprocess, "run") as execute:
            with self.assertRaises(RuntimeError):
                recorder.disconnect_owned_server(mock.Mock(pid=400), "USB123")
        execute.assert_not_called()


if __name__ == "__main__":
    unittest.main()
