import pytest

from scripts.verify_android_release import configured_version


@pytest.mark.parametrize("name,code", [("0.4.7", 24), ("0.4.9", 26), ("1.0.0", 100)])
def test_release_identity_tracks_gradle_instead_of_a_fixed_release(tmp_path, name, code):
    build = tmp_path / "build.gradle"
    build.write_text(f"android {{\n defaultConfig {{\n versionName '{name}'\n versionCode {code}\n }}\n}}\n")
    assert configured_version(build) == (name, code)


@pytest.mark.parametrize("declaration", [
    "versionName '0.4.9'\nversionCode 0\n",
    "versionName '0.4.9-test'\nversionCode 26\n",
    "versionName '0.4.9'\nversionCode 26\nversionCode 27\n",
    "versionName '0.4.9'\nversionName '0.4.8'\nversionCode 26\n",
])
def test_invalid_or_ambiguous_release_identity_is_rejected(tmp_path, declaration):
    build = tmp_path / "build.gradle"
    build.write_text(declaration)
    with pytest.raises(ValueError, match="literal release versionName"):
        configured_version(build)
