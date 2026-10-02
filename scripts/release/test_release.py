import contextlib
import hashlib
import io
import json
from pathlib import Path
import subprocess
import shutil
import tempfile
import unittest
from unittest.mock import patch

import generate_update as generate
import publish_release as publish

BADGING = """package: name='com.moge.app' versionCode='2' versionName='0.2.0' platformBuildVersionName=''
sdkVersion:'26'
native-code: 'arm64-v8a'
"""
SIGNING = "Signer #1 certificate DN: CN=Moge Release\nSigner #1 certificate SHA-256 digest: " + "a" * 64 + "\n"


class ReleaseTests(unittest.TestCase):
    def fixture(self, directory):
        apk = Path(directory) / generate.APK_NAME
        apk.write_bytes(b"fixture APK bytes")
        manifest = generate.build_manifest(apk, "test-owner/moge", "v0.2.0", BADGING, SIGNING)
        metadata = Path(directory) / "update.json"
        metadata.write_text(json.dumps(manifest), encoding="utf-8")
        return apk, metadata, manifest

    def test_repository_cannot_be_a_url_or_path(self):
        self.assertEqual(generate.validate_repository("owner/Moge.android"), "owner/Moge.android")
        for value in ("", "https://github.com/owner/repo", "owner/../repo", "owner/..", "owner/repo?token=x", "-owner/repo"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                generate.validate_repository(value)

    def test_reads_actual_apk_fields_and_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, _, manifest = self.fixture(directory)
            self.assertEqual(manifest["versionCode"], 2)
            self.assertEqual(manifest["size"], apk.stat().st_size)
            self.assertEqual(manifest["sha256"], hashlib.sha256(apk.read_bytes()).hexdigest())
            self.assertEqual(manifest["signingCertificateSha256"], "a" * 64)

    def test_rejects_non_arm64_debuggable_and_foreign_package(self):
        for badging in (BADGING.replace("arm64-v8a", "x86_64"), BADGING + "application-debuggable\n",
                        BADGING.replace("com.moge.app", "com.moge.app.debug"), BADGING.replace("'2'", "'0'")):
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                generate.parse_badging(badging)

    def test_rejects_debug_and_multiple_signers(self):
        for signing in (SIGNING.replace("CN=Moge Release", "CN=Android Debug"),
                        SIGNING + "Signer #2 certificate SHA-256 digest: " + "b" * 64):
            with self.subTest(signing=signing), self.assertRaises(ValueError):
                generate.parse_signer(signing)

    def test_tag_must_match_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, _, _ = self.fixture(directory)
            with self.assertRaises(ValueError):
                generate.build_manifest(apk, "test-owner/moge", "v0.3.0", BADGING, SIGNING)

    def test_version_code_and_signing_identity_must_progress_safely(self):
        current = dict(packageName="com.moge.app", versionCode=2, signingCertificateSha256="a" * 64)
        generate.assert_monotonic(current, dict(current, versionCode=1))
        for previous in (current, dict(current, versionCode=3), dict(current, versionCode="1"),
                         dict(current, versionCode=1, signingCertificateSha256="b" * 64)):
            with self.subTest(previous=previous), self.assertRaises(ValueError):
                generate.assert_monotonic(current, previous)

    def test_draft_assets_must_match_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, metadata, manifest = self.fixture(directory)
            release = {"assets": [{"name": generate.APK_NAME, "size": manifest["size"]}, {"name": "update.json"}]}
            publish.verify_assets(release, manifest, apk, metadata)
            apk.write_bytes(b"bad APK bytes".ljust(manifest["size"], b"!"))
            with self.assertRaises(ValueError):
                publish.verify_assets(release, manifest, apk, metadata)

    def test_upload_failure_never_publishes(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, metadata, _ = self.fixture(directory)
            arguments = ["publish_release.py", "--repository", "test-owner/moge", "--tag", "v0.2.0",
                         "--apk", str(apk), "--manifest", str(metadata)]
            with patch("sys.argv", arguments), patch.object(publish, "gh") as gh:
                gh.side_effect = ["", subprocess.CalledProcessError(1, "gh upload")]
                with self.assertRaises(subprocess.CalledProcessError):
                    publish.main()
                self.assertEqual(gh.call_count, 2)
                self.assertFalse(any(call.args[:2] == ("release", "edit") for call in gh.call_args_list))

    def test_publish_runs_only_after_both_draft_assets_are_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, metadata, manifest = self.fixture(directory)
            arguments = ["publish_release.py", "--repository", "test-owner/moge", "--tag", "v0.2.0",
                         "--apk", str(apk), "--manifest", str(metadata)]
            release = {"draft": True, "prerelease": False, "assets": [
                {"name": generate.APK_NAME, "size": manifest["size"]}, {"name": "update.json"}]}
            calls = []
            def fake_gh(*args):
                calls.append(args[:2])
                if args[0] == "api":
                    return json.dumps(release)
                if args[:2] == ("release", "download"):
                    target = Path(args[args.index("--dir") + 1])
                    shutil.copyfile(apk, target / generate.APK_NAME)
                    shutil.copyfile(metadata, target / "update.json")
                return ""
            with patch("sys.argv", arguments), patch.object(publish, "gh", side_effect=fake_gh), contextlib.redirect_stdout(io.StringIO()):
                publish.main()
            self.assertEqual(calls[-2:], [("release", "download"), ("release", "edit")])

    def test_draft_validation_failure_never_publishes(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, metadata, _ = self.fixture(directory)
            arguments = ["publish_release.py", "--repository", "test-owner/moge", "--tag", "v0.2.0",
                         "--apk", str(apk), "--manifest", str(metadata)]
            release = {"draft": True, "prerelease": False, "assets": []}
            with patch("sys.argv", arguments), patch.object(publish, "gh") as gh:
                gh.side_effect = ["", "", json.dumps(release), ""]
                with self.assertRaises(ValueError):
                    publish.main()
                self.assertFalse(any(call.args[:2] == ("release", "edit") for call in gh.call_args_list))

    def test_older_published_release_also_blocks_downgrade(self):
        with tempfile.TemporaryDirectory() as directory:
            _, _, current = self.fixture(directory)
            release = {"draft": False, "prerelease": False, "tag_name": "v0.1.0", "assets": [{
                "name": "update.json", "browser_download_url": "https://github.com/test-owner/moge/releases/download/v0.1.0/update.json"}]}
            with patch.object(generate, "public_json", side_effect=[{"private": False}, [release], dict(current, versionCode=3)]):
                with self.assertRaises(ValueError):
                    generate.check_published(current)


if __name__ == "__main__":
    unittest.main()
