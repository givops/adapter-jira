"""Verify release metadata against a synthetic verified provider artifact."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import runpy
import shutil
import tempfile
import unittest
from unittest.mock import patch
import zipfile


class PackageReleaseTest(unittest.TestCase):
    def test_zip_uses_built_artifact_sdk_metadata(self):
        self.check_bundle(package_only=False)

    def test_package_only_bundle_does_not_claim_verification(self):
        self.check_bundle(package_only=True)

    def check_bundle(self, package_only):
        source = Path(__file__).with_name("package-release.py")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            (root / "target").mkdir()
            script = root / "scripts/package-release.py"
            shutil.copyfile(source, script)
            (root / "pom.xml").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><version>0.4.0-SNAPSHOT</version></project>')
            (root / "LICENSE").write_text("Synthetic license")
            (root / "README.md").write_text("Synthetic provider")
            (root / "sdk.ref").write_text("a" * 40)
            artifact = root / "target/adapter-jira-0.4.0-SNAPSHOT.jar"
            with zipfile.ZipFile(artifact, "w") as provider:
                provider.writestr("META-INF/givops-adapter.properties", "adapterId=io.givops.adapter-jira\nsdkApiVersion=9.8\ndomains=ticketing\n")
            if not package_only:
                for name, value in {"verified-sdk.ref": "a" * 40, "verified-source.ref": "b" * 40,
                                    "verified-jar.sha256": hashlib.sha256(artifact.read_bytes()).hexdigest()}.items():
                    (root / "target" / name).write_text(value)
            arguments = [str(script)] + (["--package-only"] if package_only else [])
            with patch("sys.argv", arguments), patch("subprocess.check_output", side_effect=["", "b" * 40]), contextlib.redirect_stdout(io.StringIO()):
                runpy.run_path(str(script), run_name="__main__")
            with zipfile.ZipFile(root / "target/adapter-jira-0.4.0-SNAPSHOT.zip") as release:
                metadata = json.loads(release.read("build-metadata.json"))
            self.assertEqual(metadata["sdkApiVersion"], "9.8")
            self.assertEqual(metadata["adapterId"], "io.givops.adapter-jira")
            self.assertEqual(metadata["domains"], "ticketing")
            self.assertEqual(metadata["buildMode"], "package-only" if package_only else "locally-verified")
            self.assertEqual(metadata["jarSha256"], hashlib.sha256(artifact.read_bytes()).hexdigest())
            # Packaging-only output cannot satisfy the locally verified mode.
            (root / "target" / "verified-jar.sha256").write_text("0" * 64)
            with patch("sys.argv", [str(script)]), patch("subprocess.check_output", side_effect=["", "b" * 40]):
                with self.assertRaisesRegex(SystemExit, "Run pinned scripts/verify.sh"):
                    runpy.run_path(str(script), run_name="__main__")


if __name__ == "__main__":
    unittest.main()
