"""Create a draft and publish only after both uploaded asset bytes are verified."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

from generate_update import APK_NAME, validate_repository


def gh(*arguments):
    return subprocess.run(["gh", *arguments], check=True, capture_output=True, text=True, encoding="utf-8").stdout


def verify_assets(release, manifest, downloaded_apk, downloaded_manifest):
    assets = {asset["name"]: asset for asset in release.get("assets", [])}
    if len(release.get("assets", [])) != 2 or set(assets) != {APK_NAME, "update.json"}:
        raise ValueError("Draft must contain exactly both release assets")
    if assets[APK_NAME]["size"] != manifest["size"] or downloaded_apk.stat().st_size != manifest["size"]:
        raise ValueError("Draft APK size mismatch")
    with downloaded_apk.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != manifest["sha256"]:
            raise ValueError("Draft APK hash mismatch")
    if json.loads(downloaded_manifest.read_text(encoding="utf-8")) != manifest:
        raise ValueError("Draft update manifest mismatch")


def find_draft(repository, tag):
    # The by-tag endpoint only reliably finds published releases. The authenticated
    # release list includes drafts and lets us require one exact matching draft.
    releases = json.loads(gh("api", f"repos/{repository}/releases?per_page=100"))
    matches = [release for release in releases if release.get("tag_name") == tag]
    if len(matches) != 1:
        raise ValueError("Require one existing release for this tag")
    release = matches[0]
    if release.get("draft") is not True or release.get("prerelease") is not False:
        raise ValueError("Release must still be a stable draft")
    return release


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--notes-file", type=Path)
    parser.add_argument("--resume-draft", action="store_true",
                        help="Verify already uploaded draft assets without replacing them, then publish")
    args = parser.parse_args()
    validate_repository(args.repository)
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    if manifest["tag"] != args.tag or manifest["repositoryUrl"] != f"https://github.com/{args.repository}":
        raise ValueError("Release configuration differs from verified metadata")
    # gh create fails if the tag already has a release; never overwrite a published release.
    notes_arguments = ["--notes-file", str(args.notes_file)] if args.notes_file else ["--generate-notes"]
    if args.notes_file and args.notes_file.read_text(encoding="utf-8") != manifest.get("releaseNotes"):
        raise ValueError("Release notes differ from update metadata")
    if not args.resume_draft:
        gh("release", "create", args.tag, "--repo", args.repository, "--verify-tag", "--draft",
           "--title", f"Moge {manifest['versionName']}", *notes_arguments)
        # Any failure below leaves a draft, which the default updater never observes.
        gh("release", "upload", args.tag, str(args.apk), str(args.manifest), "--repo", args.repository)
    release = find_draft(args.repository, args.tag)
    with tempfile.TemporaryDirectory(prefix="moge-draft-") as directory:
        gh("release", "download", args.tag, "--repo", args.repository, "--dir", directory)
        verify_assets(release, manifest, Path(directory) / APK_NAME, Path(directory) / "update.json")
    gh("release", "edit", args.tag, "--repo", args.repository, "--draft=false", "--latest")
    print("Published verified APK and metadata together")


if __name__ == "__main__":
    main()
