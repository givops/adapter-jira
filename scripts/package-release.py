"""Package the verified provider JAR with its license and source/SDK identities."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
version = ET.parse(root / "pom.xml").findtext("m:version", namespaces=ns)
artifact = root / "target" / f"adapter-jira-{version}.jar"
if not artifact.is_file():
    raise SystemExit("Run scripts/verify.sh before packaging.")
if subprocess.check_output(["git", "-C", str(root), "status", "--porcelain"], text=True).strip():
    raise SystemExit("Release packaging requires a clean committed checkout.")
sdk = (root / "sdk.ref").read_text().strip()
if not re.fullmatch(r"[0-9a-f]{40}", sdk):
    raise SystemExit("Invalid pinned SDK commit.")
commit = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
for filename, expected in [("verified-sdk.ref", sdk), ("verified-source.ref", commit), ("verified-jar.sha256", digest)]:
    evidence = root / "target" / filename
    if not evidence.is_file() or evidence.read_text().strip() != expected:
        raise SystemExit("Run pinned scripts/verify.sh on this commit before release packaging.")
identity = {"adapterId": "io.givops.adapter-jira", "version": version, "sourceCommit": commit,
            "sdkCommit": sdk, "sdkApiVersion": "0.2", "domains": "ticketing", "jarSha256": digest}
output = root / "target" / f"adapter-jira-{version}.zip"
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
    for source in [artifact, root / "LICENSE", root / "README.md"]:
        archive.write(source, source.name)
    archive.writestr("build-metadata.json", json.dumps(identity, indent=2) + "\n")
print(output)
