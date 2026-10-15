import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("apk_contract", Path(__file__).parents[1] / "tools/apk_contract.py")
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)


class ContractTests(unittest.TestCase):
    def test_inline_alias_is_not_a_declared_api(self):
        text = """com.openkhub.sensefield.BoardGeometry -> a:
    int rows -> b
    1:2:boolean sameGrid(com.openkhub.sensefield.BoardGeometry):4:5 -> c
    1:2:boolean sameGrid(com.openkhub.sensefield.BoardGeometry):4:5 -> c
    7:8:boolean sameGrid(com.openkhub.sensefield.BoardGeometry):4:5 -> d
    1:2:boolean Other.sameGrid(com.openkhub.sensefield.BoardGeometry):4:5 -> x
"""
        classes, members = M.parse_map(text)
        definitions = {"fields": {("La;", "b", "I")}, "methods": {("La;", "c", "(La;)Z")}}
        bound = M.bind_members(classes, members, definitions, ["BoardGeometry"])
        self.assertEqual(bound["BoardGeometry"]["methods"], {
            "sameGrid(com.openkhub.sensefield.BoardGeometry)": {"name": "c", "parameters": ["a"], "descriptor": "(La;)Z"}})
        self.assertEqual(bound["BoardGeometry"]["fields"]["rows"]["name"], "b")

    def test_descriptor_includes_array_and_return_type(self):
        self.assertEqual(M.descriptor("int[][]", {}), "[[I")
        self.assertEqual(M.descriptor("A[]", {"A": "r.X"}), "[Lr/X;")
        classes, members = M.parse_map("com.openkhub.sensefield.BoardGeometry -> a:\n    boolean sameGrid(int) -> b\n")
        self.assertFalse(M.bind_members(classes, members, {"fields": set(), "methods": {("La;", "b", "(I)I")}},
                                       ["BoardGeometry"])["BoardGeometry"]["methods"])

    def test_kept_members_omitted_from_mapping_are_bound_to_dex(self):
        c, m = M.parse_map("com.openkhub.sensefield.BoardGeometry -> a:\n")
        d = {"fields": {("La;", "rows", "I")}, "methods": {("La;", "kept", "([I[[La;)V")}}
        x = M.bind_members(c, m, d, ["BoardGeometry"])["BoardGeometry"]
        self.assertEqual(x["fields"]["rows"], {"name": "rows", "descriptor": "I"})
        self.assertIn("kept(int[],com.openkhub.sensefield.BoardGeometry[][])", x["methods"])

    def test_ambiguous_retained_alias_is_rejected(self):
        c, m = M.parse_map("com.openkhub.sensefield.BoardGeometry -> a:\n    boolean sameGrid(int) -> b\n    boolean sameGrid(int) -> c\n")
        with self.assertRaisesRegex(ValueError, "Ambiguous"):
            M.bind_members(c, m, {"fields": set(), "methods": {("La;", "b", "(I)Z"), ("La;", "c", "(I)Z")}}, ["BoardGeometry"])

    def test_uleb_limits_and_invalid_dex(self):
        self.assertEqual(M.uleb(bytes([0xe5, 0x8e, 0x26]), 0), (624485, 3))
        with self.assertRaises(ValueError):
            M.uleb(bytes([0x80] * 5), 0)
        with self.assertRaises(ValueError):
            M.dex_definitions(b"not a dex")

    def test_map_is_bound_to_the_actual_apk_r8_marker(self):
        with tempfile.TemporaryDirectory() as d:
            apk, mapping = Path(d) / "a.apk", Path(d) / "mapping.txt"
            with zipfile.ZipFile(apk, "w") as z:
                z.writestr("classes.dex", b'dex\n~~R8{"pg-map-id":"actual-id"}\0')
            mapping.write_text("# pg_map_id: wrong-id\ncom.openkhub.sensefield.BoardGeometry -> a:\n")
            with patch.object(M, "dex_definitions", return_value={"fields": set(), "methods": set()}):
                with self.assertRaisesRegex(ValueError, "fingerprint differs"):
                    M.contract(apk, mapping)
                mapping.write_text("# pg_map_id: actual-id\ncom.openkhub.sensefield.BoardGeometry -> a:\n")
                result = M.contract(apk, mapping)
                self.assertEqual(result["apk_sha256"], M.sha(apk.read_bytes()))
                self.assertEqual(result["mapping_sha256"], M.sha(mapping.read_bytes()))

    def test_multi_dex_mismatching_marker_is_not_silently_ignored(self):
        with tempfile.TemporaryDirectory() as d:
            apk, mapping = Path(d) / "a.apk", Path(d) / "mapping.txt"
            with zipfile.ZipFile(apk, "w") as z:
                for n, marker in enumerate(["one", "two"], 1):
                    z.writestr(f"classes{n}.dex", b'dex\n~~R8' + json.dumps({"pg-map-id": marker}).encode() + b'\0')
            mapping.write_text("# pg_map_id: one\ncom.openkhub.sensefield.BoardGeometry -> a:\n")
            with patch.object(M, "dex_definitions", return_value={"fields": set(), "methods": set()}):
                with self.assertRaisesRegex(ValueError, "fingerprint differs"):
                    M.contract(apk, mapping)


if __name__ == "__main__":
    unittest.main()
