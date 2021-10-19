#!/usr/bin/env python

# Copyright (c) Yugabyte, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
# in compliance with the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
# or implied.  See the License for the specific language governing permissions and limitations
# under the License.

"""
This is a command-line tool that allows to get the download URL for a prebuilt third-party
dependencies archive for a particular configuration, as well as to update these URLs based
on the recent releases in the https://github.com/yugabyte/yugabyte-db-thirdparty repository.
"""

import sys
import re
import os
import logging
import argparse
from autorepr import autorepr  # type: ignore

from github import Github
from github.GitRelease import GitRelease

from typing import DefaultDict, Dict, List, Any, Optional, Pattern, Tuple
from datetime import datetime

from yb.common_util import (
    init_env, YB_SRC_ROOT, read_file, load_yaml_file, write_yaml_file, to_yaml_str)
from sys_detection import local_sys_conf, SHORT_OS_NAME_REGEX_STR

from collections import defaultdict

THIRDPARTY_ARCHIVES_REL_PATH = os.path.join('build-support', 'thirdparty_archives.yml')
MANUAL_THIRDPARTY_ARCHIVES_REL_PATH = os.path.join(
    'build-support', 'thirdparty_archives_manual.yml')

NUM_TOP_COMMITS = 10

DOWNLOAD_URL_PREFIX = 'https://github.com/yugabyte/yugabyte-db-thirdparty/releases/download/'

ARCH_REGEX_STR = '|'.join(['x86_64', 'aarch64'])


def get_arch_regex(index: int) -> str:
    """
    There are two places where the architecture could appear in the third-party archive release tag.
    We make them available under "architecture1" and "architecture2" capture group names.
    """
    return r'(?:-(?P<architecture%d>%s))?' % (index, ARCH_REGEX_STR)


TAG_RE = re.compile(''.join([
    r'^v(?:(?P<branch_name>[0-9.]+)-)?',
    r'(?P<timestamp>[0-9]+)-',
    r'(?P<sha_prefix>[0-9a-f]+)',
    get_arch_regex(1),
    r'(?:-(?P<os>(?:%s)[a-z0-9.]*))' % SHORT_OS_NAME_REGEX_STR,
    get_arch_regex(2),
    r'(?:-(?P<is_linuxbrew>linuxbrew))?',
    # "devtoolset" really means just "gcc" here. We should replace it with "gcc" in release names.
    r'(?:-(?P<compiler_type>(?:gcc|clang|devtoolset-?)[a-z0-9.]+))?',
    r'$',
]))

# We will store the SHA1 to be used for the local third-party checkout under this key.
SHA_FOR_LOCAL_CHECKOUT_KEY = 'sha_for_local_checkout'

UBUNTU_OS_TYPE_RE = re.compile(r'^(ubuntu)([0-9]{2})([0-9]{2})$')
RHEL_FAMILY_RE = re.compile(r'^(almalinux|centos|rhel)([0-9]+)$')

# Skip these problematic tags.
BROKEN_TAGS = set(['v20210907234210-47a70bc7dc-centos7-x86_64-linuxbrew-gcc5'])


def get_archive_name_from_tag(tag: str) -> str:
    return f'yugabyte-db-thirdparty-{tag}.tar.gz'


def adjust_os_type(os_type: str) -> str:
    match = UBUNTU_OS_TYPE_RE.match(os_type)
    if match:
        # Convert e.g. ubuntu2004 -> ubuntu20.04 for clarity.
        return f'{match.group(1)}{match.group(2)}.{match.group(3)}'
    return os_type


def compatible_os(archive_os: str, target_os: str) -> bool:
    rhel_like1 = RHEL_FAMILY_RE.match(archive_os)
    rhel_like2 = RHEL_FAMILY_RE.match(target_os)
    if rhel_like1 and rhel_like2 and rhel_like1.group(2) == rhel_like2.group(2):
        return True
    return archive_os == target_os


class YBDependenciesRelease:

    # The list of fields without the release tag. The tag is special because it includes the
    # timestamp, so by repeating a build on the same commit in yugabyte-db-thirdparty, we could get
    # multiple releases that have the same OS/architecture/compiler type/SHA but different tags.
    # Therefore we distinguish between "key with tag" and "key with no tag"
    KEY_FIELDS_NO_TAG = ['os_type', 'architecture', 'compiler_type', 'sha']
    KEY_FIELDS_WITH_TAG = KEY_FIELDS_NO_TAG + ['tag']

    os_type: str
    architecture: str
    compiler_type: str
    sha: str
    tag: str

    github_release: GitRelease

    timestamp: str
    url: str
    branch_name: Optional[str]

    def __init__(self, github_release: GitRelease) -> None:
        self.github_release = github_release
        self.sha = self.github_release.target_commitish

        tag = self.github_release.tag_name
        tag_match = TAG_RE.match(tag)
        if not tag_match:
            raise ValueError(f"Could not parse tag: {tag}, does not match regex: {TAG_RE}")

        group_dict = tag_match.groupdict()

        sha_prefix = tag_match.group('sha_prefix')
        if not self.sha.startswith(sha_prefix):
            raise ValueError(
                f"SHA prefix {sha_prefix} extracted from tag {tag} is not a prefix of the "
                f"SHA corresponding to the release/tag: {self.sha}.")

        self.timestamp = group_dict['timestamp']
        self.os_type = adjust_os_type(group_dict['os'])

        arch1 = group_dict['architecture1']
        arch2 = group_dict['architecture2']
        if arch1 is not None and arch2 is not None and arch1 != arch2:
            raise ValueError("Contradicting values of arhitecture in tag '%s'" % tag)
        self.architecture = arch1 or arch2
        self.is_linuxbrew = bool(group_dict.get('is_linuxbrew'))

        compiler_type = group_dict.get('compiler_type')
        if compiler_type is None and self.os_type == 'macos':
            compiler_type = 'clang'
        if compiler_type is None and self.is_linuxbrew:
            compiler_type = 'gcc'

        if compiler_type is None:
            raise ValueError(
                f"Could not determine compiler type from tag {tag}. Matches: {group_dict}.")
        compiler_type = compiler_type.strip('-')
        self.tag = tag
        self.compiler_type = compiler_type

        branch_name = group_dict.get('branch_name')
        if branch_name is not None:
            branch_name = branch_name.rstrip('-')
        self.branch_name = branch_name

    def validate_url(self) -> None:
        asset_urls = [asset.browser_download_url for asset in self.github_release.get_assets()]

        if len(asset_urls) != 2:
            raise ValueError(
                "Expected to find exactly two asset URLs for a release "
                "(one for the .tar.gz, the other for the checksum), "
                f"but found {len(asset_urls)}: {asset_urls}")

        non_checksum_urls = [url for url in asset_urls if not url.endswith('.sha256')]
        assert(len(non_checksum_urls) == 1)
        self.url = non_checksum_urls[0]
        if not self.url.startswith(DOWNLOAD_URL_PREFIX):
            raise ValueError(
                f"Expected archive download URL to start with {DOWNLOAD_URL_PREFIX}, found "
                f"{self.url}")

        url_suffix = self.url[len(DOWNLOAD_URL_PREFIX):]
        url_suffix_components = url_suffix.split('/')
        assert(len(url_suffix_components) == 2)

        archive_basename = url_suffix_components[1]
        expected_basename = get_archive_name_from_tag(self.tag)
        if archive_basename != expected_basename:
            raise ValueError(
                f"Expected archive name based on tag: {expected_basename}, "
                f"actual name: {archive_basename}, url: {self.url}")

    def as_dict(self) -> Dict[str, str]:
        return {k: getattr(self, k) for k in self.KEY_FIELDS_WITH_TAG}

    def get_sort_key(self, include_tag: bool = True) -> Tuple[str, ...]:
        return tuple(
            getattr(self, k) for k in
            (self.KEY_FIELDS_WITH_TAG if include_tag else self.KEY_FIELDS_NO_TAG))

    def is_consistent_with_yb_version(self, yb_version: str) -> bool:
        return (self.branch_name is None or
                yb_version.startswith((self.branch_name + '.', self.branch_name + '-')))

    __str__ = __repr__ = autorepr(KEY_FIELDS_WITH_TAG)


class ReleaseGroup:
    sha: str
    releases: List[YBDependenciesRelease]
    creation_timestamps: List[datetime]

    def __init__(self, sha: str) -> None:
        self.sha = sha
        self.releases = []
        self.creation_timestamps = []

    def add_release(self, release: YBDependenciesRelease) -> None:
        if release.sha != self.sha:
            raise ValueError(
                f"Adding a release with wrong SHA. Expected: {self.sha}, got: "
                f"{release.sha}.")
        self.releases.append(release)
        self.creation_timestamps.append(release.github_release.created_at)

    def get_max_creation_timestamp(self) -> datetime:
        return max(self.creation_timestamps)

    def get_min_creation_timestamp(self) -> datetime:
        return min(self.creation_timestamps)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        '--github-token-file',
        help='Read GitHub token from this file. Authenticated requests have a higher rate limit. '
             'If this is not specified, we will still use the GITHUB_TOKEN environment '
             'variable.')
    parser.add_argument(
        '--update', '-u', action='store_true',
        help=f'Update the third-party archive metadata in in {THIRDPARTY_ARCHIVES_REL_PATH}.')
    parser.add_argument(
        '--get-sha1',
        action='store_true',
        help='Show the Git SHA1 of the commit to use in the yugabyte-db-thirdparty repo '
             'in case we are building the third-party dependencies from scratch.')
    parser.add_argument(
        '--save-download-url-to-file',
        help='Determine the third-party archive download URL for the combination of criteria, '
             'including the compiler type, and write it to the file specified by this argument.')
    parser.add_argument(
        '--compiler-type',
        help='Compiler type, to help us decide which third-party archive to choose. '
             'The default value is determined by the YB_COMPILER_TYPE environment variable.',
        default=os.getenv('YB_COMPILER_TYPE'))
    parser.add_argument(
        '--os-type',
        help='Operating system type, to help us decide which third-party archive to choose. '
             'The default value is determined automatically based on the current OS.')
    parser.add_argument(
        '--architecture',
        help='Machine architecture, to help us decide which third-party archive to choose. '
             'The default value is determined automatically based on the current platform.')
    parser.add_argument(
        '--verbose',
        help='Verbose debug information')
    parser.add_argument(
        '--tag-filter-regex',
        help='Only look at tags satisfying this regular expression.')

    if len(sys.argv) == 1:
        parser.print_help(sys.stderr)
        sys.exit(1)

    return parser.parse_args()


def get_archive_metadata_file_path() -> str:
    return os.path.join(YB_SRC_ROOT, THIRDPARTY_ARCHIVES_REL_PATH)


def get_manual_archive_metadata_file_path() -> str:
    return os.path.join(YB_SRC_ROOT, MANUAL_THIRDPARTY_ARCHIVES_REL_PATH)


def get_github_token(token_file_path: Optional[str]) -> Optional[str]:
    github_token: Optional[str]
    if token_file_path:
        github_token = read_file(token_file_path).strip()
    else:
        github_token = os.getenv('GITHUB_TOKEN')
    if github_token is None:
        return github_token

    if len(github_token) != 40:
        raise ValueError(f"Invalid GitHub token length: {len(github_token)}, expected 40.")
    return github_token


class MetadataUpdater:
    github_token_file_path: str
    tag_filter_pattern: Optional[Pattern]

    def __init__(
            self,
            github_token_file_path: str,
            tag_filter_regex_str: Optional[str]) -> None:
        self.github_token_file_path = github_token_file_path
        if tag_filter_regex_str:
            self.tag_filter_pattern = re.compile(tag_filter_regex_str)
        else:
            self.tag_filter_pattern = None

    def update_archive_metadata_file(self) -> None:
        yb_version = read_file(os.path.join(YB_SRC_ROOT, 'version.txt')).strip()

        archive_metadata_path = get_archive_metadata_file_path()
        logging.info(f"Updating third-party archive metadata file in {archive_metadata_path}")

        github_client = Github(get_github_token(self.github_token_file_path))
        repo = github_client.get_repo('yugabyte/yugabyte-db-thirdparty')

        releases_by_commit: Dict[str, ReleaseGroup] = {}
        num_skipped_old_tag_format = 0
        num_skipped_wrong_branch = 0
        num_releases_found = 0

        for release in repo.get_releases():
            sha: str = release.target_commitish
            assert(isinstance(sha, str))
            tag_name = release.tag_name
            if len(tag_name.split('-')) <= 2:
                logging.debug(f"Skipping release tag: {tag_name} (old format, too few components)")
                num_skipped_old_tag_format += 1
                continue
            if self.tag_filter_pattern and not self.tag_filter_pattern.match(tag_name):
                logging.info(f'Skipping tag {tag_name}, does not match the filter')
                continue

            yb_dep_release = YBDependenciesRelease(release)
            if not yb_dep_release.is_consistent_with_yb_version(yb_version):
                logging.debug(
                    f"Skipping release tag: {tag_name} (does not match version {yb_version}")
                num_skipped_wrong_branch += 1
                continue

            if sha not in releases_by_commit:
                releases_by_commit[sha] = ReleaseGroup(sha)

            num_releases_found += 1
            logging.info(f"Found release: {yb_dep_release}")
            releases_by_commit[sha].add_release(yb_dep_release)

        if num_skipped_old_tag_format > 0:
            logging.info(f"Skipped {num_skipped_old_tag_format} releases due to old tag format")
        if num_skipped_wrong_branch > 0:
            logging.info(f"Skipped {num_skipped_wrong_branch} releases due to branch mismatch")
        logging.info(
            f"Found {num_releases_found} releases for {len(releases_by_commit)} different commits")
        latest_group_by_max = max(
            releases_by_commit.values(), key=ReleaseGroup.get_max_creation_timestamp)
        latest_group_by_min = max(
            releases_by_commit.values(), key=ReleaseGroup.get_min_creation_timestamp)
        if latest_group_by_max is not latest_group_by_min:
            raise ValueError(
                "Overlapping releases for different commits. No good way to identify latest "
                "release: e.g. {latest_group_by_max.sha} and {latest_group_by_min.sha}.")

        latest_group = latest_group_by_max

        sha = latest_group.sha
        logging.info(
            f"Latest released yugabyte-db-thirdparty commit: f{sha}. "
            f"Released at: {latest_group.get_max_creation_timestamp()}.")

        new_metadata: Dict[str, Any] = {
            SHA_FOR_LOCAL_CHECKOUT_KEY: sha,
            'archives': []
        }
        releases_for_one_commit = [
            rel for rel in latest_group.releases
            if rel.tag not in BROKEN_TAGS
        ]

        releases_by_key_without_tag: DefaultDict[Tuple[str, ...], List[YBDependenciesRelease]] = \
            defaultdict(list)

        for yb_thirdparty_release in releases_for_one_commit:
            yb_thirdparty_release.validate_url()
            releases_by_key_without_tag[
                yb_thirdparty_release.get_sort_key(include_tag=False)
            ].append(yb_thirdparty_release)

        keys_with_duplicate_releases: List[Tuple[str, ...]] = []
        for key_without_tag, releases_for_key in releases_by_key_without_tag.items():
            if len(releases_for_key) > 1:
                logging.info(
                    "Multiple releases found for the same key (excluding the tag). "
                    "Key: %s, releases: %s" % (
                        key_without_tag,
                        releases_for_key))
                keys_with_duplicate_releases.append(key_without_tag)
        if keys_with_duplicate_releases:
            raise ValueError(
                "Multiple releases found for these keys: %s" % keys_with_duplicate_releases)

        releases_for_one_commit.sort(key=YBDependenciesRelease.get_sort_key)

        for yb_thirdparty_release in releases_for_one_commit:
            new_metadata['archives'].append(yb_thirdparty_release.as_dict())

        write_yaml_file(new_metadata, archive_metadata_path)
        logging.info(
            f"Wrote information for {len(releases_for_one_commit)} pre-built "
            f"yugabyte-db-thirdparty archives to {archive_metadata_path}.")


def load_metadata() -> Dict[str, Any]:
    return load_yaml_file(get_archive_metadata_file_path())


def load_manual_metadata() -> Dict[str, Any]:
    return load_yaml_file(get_manual_archive_metadata_file_path())


def filter_for_os(archive_candidates: List[Dict[str, str]], os_type: str) -> List[Dict[str, str]]:
    filtered_exactly = [
        candidate for candidate in archive_candidates if candidate['os_type'] == os_type
    ]
    if filtered_exactly:
        return filtered_exactly
    return [
        candidate for candidate in archive_candidates
        if compatible_os(candidate['os_type'], os_type)
    ]


def get_download_url(
        metadata: Dict[str, Any],
        compiler_type: str,
        os_type: Optional[str],
        architecture: Optional[str]) -> str:
    if not os_type:
        os_type = local_sys_conf().short_os_name_and_version()
    if not architecture:
        architecture = local_sys_conf().architecture

    candidates: List[Any] = []
    available_archives = metadata['archives']
    for archive in available_archives:
        if archive['compiler_type'] == compiler_type and archive['architecture'] == architecture:
            candidates.append(archive)
    candidates = filter_for_os(candidates, os_type)

    if len(candidates) == 1:
        tag = candidates[0]['tag']
        return f'{DOWNLOAD_URL_PREFIX}{tag}/{get_archive_name_from_tag(tag)}'

    if not candidates:
        if os_type == 'centos7' and compiler_type == 'gcc':
            logging.info(
                "Assuming that the compiler type of 'gcc' means 'gcc5'. "
                "This will change when we stop using Linuxbrew and update the compiler.")
            return get_download_url(metadata, 'gcc5', os_type, architecture)
        if os_type == 'ubuntu18.04' and compiler_type == 'gcc':
            logging.info(
                "Assuming that the compiler type of 'gcc' means 'gcc7'. "
                "This will change when we stop using Linuxbrew and update the compiler.")
            return get_download_url(metadata, 'gcc7', os_type, architecture)

        logging.info(f"Available release archives:\n{to_yaml_str(available_archives)}")
        raise ValueError(
            "Could not find a third-party release archive to download for "
            f"OS type '{os_type}', "
            f"compiler type '{compiler_type}', and "
            f"architecture '{architecture}'. "
            "Please see the list of available thirdparty archives above.")

    i = 1
    for candidate in candidates:
        logging.warning("Third-party release archive candidate #%d: %s", i, candidate)
        i += 1

    raise ValueError(
        f"Found too many third-party release archives to download for OS type "
        f"{os_type} and compiler type matching {compiler_type}: {candidates}.")


def main() -> None:
    args = parse_args()
    init_env(verbose=args.verbose)
    if args.update:
        updater = MetadataUpdater(
            github_token_file_path=args.github_token_file,
            tag_filter_regex_str=args.tag_filter_regex)
        updater.update_archive_metadata_file()
        return

    metadata = load_metadata()
    manual_metadata = load_manual_metadata()
    if args.get_sha1:
        print(metadata[SHA_FOR_LOCAL_CHECKOUT_KEY])
        return

    metadata['archives'].extend(manual_metadata['archives'])

    if args.save_download_url_to_file:
        if not args.compiler_type:
            raise ValueError("Compiler type not specified")
        url = get_download_url(
            metadata=metadata,
            compiler_type=args.compiler_type,
            os_type=args.os_type,
            architecture=args.architecture)
        if url is None:
            raise RuntimeError("Could not determine download URL")
        logging.info(f"Download URL for the third-party dependencies: {url}")
        output_file_dir = os.path.dirname(os.path.abspath(args.save_download_url_to_file))
        os.makedirs(output_file_dir, exist_ok=True)
        with open(args.save_download_url_to_file, 'w') as output_file:
            output_file.write(url)


if __name__ == '__main__':
    main()
