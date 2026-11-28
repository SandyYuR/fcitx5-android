#!/usr/bin/env python3
"""Validate a configurable frozen-fixture artifact before downloading it."""
import argparse
import json
import os
from pathlib import Path
import re
import urllib.request


def source_record(repository, run_id, artifact_id, token):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Expected a GitHub owner/repository')
    if any(not re.fullmatch(r'[1-9][0-9]*', value) for value in (run_id, artifact_id)):
        raise ValueError('fixture_run and fixture_artifact_id must be positive decimal IDs; supply a live copy of the frozen fixture')
    request = urllib.request.Request(
        f'https://api.github.com/repos/{repository}/actions/artifacts/{artifact_id}',
        headers={'Authorization': 'Bearer ' + token, 'User-Agent': 'latency-study',
                 'Accept': 'application/vnd.github+json'})
    with urllib.request.urlopen(request, timeout=60) as response:
        artifact = json.load(response)
    if (str(artifact.get('id')) != artifact_id or
            str(artifact.get('workflow_run', {}).get('id')) != run_id):
        raise ValueError('Fixture artifact does not belong to the requested run')
    if artifact.get('expired') is not False:
        raise ValueError('Fixture artifact is expired; provide another live artifact with the same verified fixture contents')
    return {'repository': repository, 'run_id': run_id, 'artifact_id': artifact_id,
            'name': artifact.get('name'), 'created_at': artifact.get('created_at'),
            'expires_at': artifact.get('expires_at'), 'artifact_digest': artifact.get('digest'),
            'source_sha': artifact.get('workflow_run', {}).get('head_sha')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    record = source_record(os.environ['GITHUB_REPOSITORY'], os.environ.get('FIXTURE_RUN', ''),
                           os.environ.get('FIXTURE_ARTIFACT_ID', ''), os.environ['GH_TOKEN'])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(record, indent=2) + '\n')


if __name__ == '__main__':
    main()
