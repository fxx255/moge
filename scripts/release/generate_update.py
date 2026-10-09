"""Generate public update metadata from an APK; never accepts hand-entered APK fields."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request

MAX_APK_BYTES = 512 * 1024 * 1024
MAX_JSON_BYTES = 256 * 1024
APK_NAME = "Moge-arm64.apk"
PACKAGE_NAME = "com.moge.app"


def validate_repository(value):
    if not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?/[A-Za-z0-9_.-]{1,100}", value):
        raise ValueError("Use a public GitHub owner/repo identifier")
    if value.split("/")[1] in (".", ".."):
        raise ValueError("Invalid repository")
    return value


def release_urls(repository, tag):
    project = f"https://github.com/{validate_repository(repository)}"
    encoded_tag = urllib.parse.quote(tag, safe="")
    return project, f"{project}/releases/tag/{encoded_tag}", f"{project}/releases/download/{encoded_tag}/{APK_NAME}"


def parse_badging(text):
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", text, re.MULTILINE)
    sdk = re.search(r"^sdkVersion:'(\d+)'", text, re.MULTILINE)
    native = re.search(r"^native-code: (.+)$", text, re.MULTILINE)
    if not package or not sdk or not native:
        raise ValueError("APK metadata is incomplete")
    name, code, version = package.groups()
    if name != PACKAGE_NAME or not 1 <= int(code) <= 2_100_000_000:
        raise ValueError("APK package or versionCode is invalid")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[.-][A-Za-z0-9.-]+)?", version) or len(version) > 80:
        raise ValueError("APK versionName is invalid")
    if not 26 <= int(sdk.group(1)) <= 100:
        raise ValueError("APK minimum SDK is invalid")
    if re.findall(r"'([^']+)'", native.group(1)) != ["arm64-v8a"]:
        raise ValueError("Official APK must contain only arm64-v8a native libraries")
    if re.search(r"^application-debuggable(?:\s|$)", text, re.MULTILINE):
        raise ValueError("Official APK must not be debuggable")
    return {"packageName": name, "versionCode": int(code), "versionName": version, "minSdk": int(sdk.group(1))}


def parse_signer(text):
    if re.search(r"Signer #\d+ certificate DN:.*(?:CN=Android Debug|CN=AndroidDebug)", text, re.IGNORECASE):
        raise ValueError("A debug certificate cannot sign an official APK")
    hashes = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})", text)
    if len(hashes) != 1:
        raise ValueError("Require one verified official APK signer")
    return hashes[0].lower()


def tool_path(name):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("Set ANDROID_HOME to an Android SDK with Build Tools 36.0.0")
    extension = ".bat" if os.name == "nt" and name == "apksigner" else ".exe" if os.name == "nt" else ""
    path = Path(sdk) / "build-tools" / "36.0.0" / (name + extension)
    if not path.is_file():
        raise ValueError(f"Required SDK tool is missing: {name}")
    return str(path)


def build_manifest(apk, repository, tag, badging, signing, release_notes=""):
    if len(release_notes) > 16_000:
        raise ValueError("Release notes exceed 16000 characters")
    fields = parse_badging(badging)
    if tag != f"v{fields['versionName']}":
        raise ValueError("Release tag must equal v plus APK versionName")
    size = apk.stat().st_size
    if not 1 <= size <= MAX_APK_BYTES or apk.name != APK_NAME:
        raise ValueError("Invalid APK size or asset name")
    project, release, download = release_urls(repository, tag)
    with apk.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    return dict(schemaVersion=1, **fields, size=size, sha256=digest,
                signingCertificateSha256=parse_signer(signing), abi="arm64-v8a",
                repositoryUrl=project, releaseUrl=release, apkUrl=download, tag=tag, releaseNotes=release_notes)


class PublicRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        target = urllib.parse.urlsplit(newurl)
        if target.scheme != "https" or target.hostname not in (
                "github.com", "api.github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"):
            raise ValueError("Unexpected update redirect")
        if target.username or target.password or target.port not in (None, 443):
            raise ValueError("Unexpected update redirect credentials")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def public_json(url):
    # Intentionally unauthenticated: clients must be able to retrieve every published asset.
    request = urllib.request.Request(url, headers={"User-Agent": "Moge-release-check", "Accept": "application/json"})
    with urllib.request.build_opener(PublicRedirectHandler()).open(request, timeout=30) as response:
        content = response.read(MAX_JSON_BYTES + 1)
    if len(content) > MAX_JSON_BYTES:
        raise ValueError("Public metadata is too large")
    return json.loads(content)


class RejectApiRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Authenticated GitHub API redirects are not allowed")


def github_api_json(url):
    """Try public API first; a runner token can recover an exhausted anonymous quota."""
    origin = urllib.parse.urlsplit(url)
    if (origin.scheme != "https" or origin.hostname != "api.github.com" or
            origin.username or origin.password or origin.port not in (None, 443) or
            not origin.path.startswith("/repos/")):
        raise ValueError("Require the GitHub repository API origin")
    try:
        return public_json(url)
    except urllib.error.HTTPError as error:
        limited = error.code == 429 or (error.code == 403 and error.headers.get("X-RateLimit-Remaining") == "0")
        token = os.environ.get("GH_TOKEN", "")
        if not limited or not token:
            raise
    # This token is for API metadata only, never APK/update.json downloads or redirects.
    request = urllib.request.Request(url, headers={"User-Agent": "Moge-release-check",
        "Accept": "application/vnd.github+json", "Authorization": "Bearer " + token,
        "X-GitHub-Api-Version": "2022-11-28"})
    with urllib.request.build_opener(RejectApiRedirects()).open(request, timeout=30) as response:
        content = response.read(MAX_JSON_BYTES + 1)
    if len(content) > MAX_JSON_BYTES:
        raise ValueError("GitHub API metadata is too large")
    return json.loads(content)


def assert_monotonic(current, published):
    if current["packageName"] != published.get("packageName"):
        raise ValueError("Published APK package differs")
    previous_code = published.get("versionCode")
    if type(previous_code) is not int or current["versionCode"] <= previous_code:
        raise ValueError("versionCode must increase beyond every published official release")
    if current["signingCertificateSha256"] != published.get("signingCertificateSha256"):
        raise ValueError("Published signing identity differs; do not break installed updates")


def check_published(manifest):
    repository = manifest["repositoryUrl"].removeprefix("https://github.com/")
    # Also prove this repository is public; private repositories cannot bootstrap a public updater.
    info = github_api_json(f"https://api.github.com/repos/{repository}")
    if info.get("private") is not False:
        raise ValueError("Update distribution repository must be public")
    # Check every stable release, not only latest: a newer tag may contain a lower code.
    for page in range(1, 101):
        releases = github_api_json(f"https://api.github.com/repos/{repository}/releases?per_page=20&page={page}")
        if not isinstance(releases, list):
            raise ValueError("Invalid releases response")
        for release in releases:
            if release.get("draft") or release.get("prerelease"):
                continue
            assets = [asset for asset in release.get("assets", []) if asset.get("name") == "update.json"]
            if len(assets) != 1:
                raise ValueError("Existing official release lacks unique update metadata")
            tag = release.get("tag_name", "")
            expected = f"https://github.com/{repository}/releases/download/{urllib.parse.quote(tag, safe='')}/update.json"
            if assets[0].get("browser_download_url") != expected:
                raise ValueError("Existing update metadata has unexpected origin")
            assert_monotonic(manifest, public_json(expected))
        if len(releases) < 20:
            return
    raise ValueError("Too many releases to verify within bounded pagination")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check-published", action="store_true")
    parser.add_argument("--notes-file", type=Path)
    args = parser.parse_args()
    validate_repository(args.repository)
    badging = subprocess.run([tool_path("aapt"), "dump", "badging", str(args.apk)], check=True, capture_output=True, text=True).stdout
    signing = subprocess.run([tool_path("apksigner"), "verify", "--verbose", "--print-certs", str(args.apk)], check=True, capture_output=True, text=True).stdout
    notes = args.notes_file.read_text(encoding="utf-8") if args.notes_file else ""
    manifest = build_manifest(args.apk, args.repository, args.tag, badging, signing, notes)
    if args.check_published:
        check_published(manifest)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Validated APK metadata: {manifest['versionName']} ({manifest['versionCode']}); arm64; dedicated signing")


if __name__ == "__main__":
    main()
