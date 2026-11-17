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
            for name, value in {"verified-sdk.ref": "a" * 40, "verified-source.ref": "b" * 40,
                                "verified-jar.sha256": hashlib.sha256(artifact.read_bytes()).hexdigest()}.items():
                (root / "target" / name).write_text(value)
            with patch("subprocess.check_output", side_effect=["", "b" * 40]), contextlib.redirect_stdout(io.StringIO()):
                runpy.run_path(str(script), run_name="__main__")
            with zipfile.ZipFile(root / "target/adapter-jira-0.4.0-SNAPSHOT.zip") as release:
                metadata = json.loads(release.read("build-metadata.json"))
            self.assertEqual(metadata["sdkApiVersion"], "9.8")
            self.assertEqual(metadata["adapterId"], "io.givops.adapter-jira")
            self.assertEqual(metadata["domains"], "ticketing")


if __name__ == "__main__":
    unittest.main()
