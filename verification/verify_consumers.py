"""Verify Maven/Gradle publications using isolated consumer projects and a loopback MCP server."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

parser = argparse.ArgumentParser()
for name in ["group", "version", "boot"]:
    parser.add_argument("--" + name, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
repository = root / "build/verification-repository"
modules = ["spring-ai-mcp-sigv4", "spring-ai-mcp-sigv4-spring-boot-autoconfigure", "spring-ai-mcp-sigv4-spring-boot-starter"]
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
checksums = []
for module in modules:
    directory = repository / args.group.replace(".", "/") / module / args.version
    def artifact_file(extension, classifier=""):
        version = args.version
        if version.endswith("-SNAPSHOT"):
            metadata = ET.parse(directory / "maven-metadata.xml").getroot()
            entries = metadata.findall("versioning/snapshotVersions/snapshotVersion")
            match = next(entry for entry in entries if entry.findtext("extension") == extension
                         and (entry.findtext("classifier") or "") == classifier)
            version = match.findtext("value")
        suffix = ("-" + classifier if classifier else "") + "." + extension
        return directory / (module + "-" + version + suffix)
    for extension, classifier in [("jar", ""), ("jar", "sources"), ("jar", "javadoc"), ("pom", ""), ("module", "")]:
        artifact = artifact_file(extension, classifier)
        if not artifact.is_file():
            raise SystemExit("Missing publication artifact: " + artifact.name)
        digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        published_checksum = artifact.with_name(artifact.name + ".sha256")
        if not published_checksum.is_file() or published_checksum.read_text().strip() != digest:
            raise SystemExit("Published SHA-256 mismatch: " + artifact.name)
        checksums.append(digest + "  " + str(artifact.relative_to(repository)))
    pom = ET.parse(artifact_file("pom")).getroot()
    for field in ["licenses/m:license/m:name", "scm/m:connection", "developers/m:developer/m:id"]:
        if pom.find("m:" + field, ns) is None:
            raise SystemExit("Missing POM identity metadata in " + module)
    metadata = json.loads((artifact_file("module")).read_text())
    if metadata["component"]["version"] != args.version:
        raise SystemExit("Unexpected Gradle module version")
    for variant in metadata["variants"]:
        for dependency in variant.get("dependencies", []):
            if not dependency.get("version", {}).get("requires"):
                raise SystemExit("Published dependency has no resolved version in " + module)
    with zipfile.ZipFile(artifact_file("jar")) as jar:
        for item in jar.namelist():
            if item.endswith(".class") and int.from_bytes(jar.read(item)[6:8], "big") != 61:
                raise SystemExit("Published bytecode must target Java 17: " + item)
        if module.endswith("autoconfigure"):
            imports = jar.read("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports").decode()
            if "McpSigV4AutoConfiguration" not in imports:
                raise SystemExit("Missing auto-configuration registration")
            properties = {item["name"]: item for item in json.loads(
                jar.read("META-INF/spring-configuration-metadata.json"))["properties"]}
            prefix = "mcp.sigv4.connections"
            expected = {"region": None, "service-name": "bedrock-agentcore",
                        "allow-insecure-http": False, "signing.additional-unsigned-headers": []}
            if not properties[prefix].get("description"):
                raise SystemExit("Missing connection-map metadata description")
            for name, default in expected.items():
                item = properties.get(prefix + ".*." + name, {})
                if not item.get("description") or item.get("defaultValue") != default:
                    raise SystemExit("Missing or incorrect generated property metadata: " + name)
(root / "build/verification-artifacts.sha256").write_text("\n".join(checksums) + "\n")
print("Publication metadata, JAR contents, Java 17 bytecode, and checksums: PASS", flush=True)
for kind in ["gradle", "maven", "core"]:
    target = root / "build/consumer-verification" / kind
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(root / "verification" / (kind + "-consumer"), target)
    if kind != "core":
        shutil.copytree(root / "verification/consumer/src", target / "src")
    properties = {"verificationRepository": repository.as_uri(), "sigv4Group": args.group,
                  "sigv4Version": args.version, "bootVersion": args.boot}
    if kind == "gradle":
        command = [str(root / "gradlew"), "--no-daemon", "--no-build-cache", "--refresh-dependencies", "--project-dir", str(target), "test"]
        command += ["-P" + key + "=" + value for key, value in properties.items()]
    else:
        # -U refreshes snapshots, but existing releases may still come from the local
        # cache. Remove only this build's artifacts so a reused RC version is verified
        # against the publication just produced, while retaining third-party downloads.
        for module in modules:
            cached = root / "build/consumer-verification/maven-local" / args.group.replace(".", "/") / module / args.version
            if cached.exists():
                shutil.rmtree(cached)
        command = ["mvn", "-U", "--batch-mode", "--no-transfer-progress", "-Dmaven.repo.local=" + str(root / "build/consumer-verification/maven-local"), "-f", str(target / "pom.xml"), "verify"]
        command += ["-D" + key + "=" + value for key, value in properties.items()]
    log = target / "verification.log"
    with log.open("w") as output:
        result = subprocess.run(command, cwd=root, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        print(kind + " consumer: FAIL; " + str(log), flush=True)
        sys.exit(result.returncode)
    if kind == "core":
        report = next((target / "target/surefire-reports").glob("TEST-*.xml"))
        properties = {item.get("name"): item.get("value") for item in
                      ET.parse(report).getroot().findall("properties/property")}
        classpath = properties["java.class.path"].replace("\\", "/")
        if any(value in classpath for value in ["/org/springframework/ai/", "/org/springframework/boot/",
                                                "jackson-databind", "mcp-json-jackson"]):
            raise SystemExit("Core consumer unexpectedly requires Spring AI, Boot, or a JSON mapper")
        print("Core consumer signed UTF-8 delivery without Spring AI, Boot, or a JSON mapper: PASS", flush=True)
    else:
        print(kind + " consumer auto-configuration and signed MCP delivery: PASS", flush=True)
