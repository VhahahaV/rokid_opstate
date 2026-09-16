"""Tamper probes for the full export verifier (independent of the Android exporter)."""
import hashlib
import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('runtime_trace',ROOT/'tools/validate_runtime_trace.py')
validator=importlib.util.module_from_spec(spec);spec.loader.exec_module(validator)


def dump(value): return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))
def sha(value): return hashlib.sha256(value.encode()).hexdigest()


def rehash(directory):
    path=directory/'session_manifest.json'
    manifest=json.loads(path.read_text())
    files=sorted(p for p in directory.rglob('*') if p.is_file() and p.name not in ('session_manifest.json','SHA256SUMS.txt'))
    manifest['files']=[{'path':p.relative_to(directory).as_posix(),'sha256':validator.digest(p),'bytes':p.stat().st_size} for p in files]
    path.write_text(dump(manifest))
    (directory/'SHA256SUMS.txt').write_text(''.join(f'{validator.digest(p)}  {p.relative_to(directory).as_posix()}\n' for p in files+[path]))


def fixture(directory):
    for stream in validator.STREAMS: (directory/(stream+'.jsonl')).write_text('')
    payload=dump({'session_id':'integrity-fixture'})
    (directory/'runtime_events.jsonl').write_text(payload+'\n')
    chain=sha(f'0|SESSION_START|100|{sha(payload)}|'+64*'0')
    audit={'sequence':0,'event_type':'SESSION_START','event_at_epoch_ms':100,'stream':'runtime_events','payload_sha256':sha(payload),
           'previous_chain_sha256':'0'*64,'chain_sha256':chain,'canonical_payload':payload}
    (directory/'audit.jsonl').write_text(dump(audit)+'\n')
    manifest={'schema_version':'opstate_session_export_v2','session_id':'integrity-fixture','session_epoch':100,'scenario_id':'integrity',
        'condition_code':'CONTRACT_REPLAY','started_at_epoch_ms':100,'ended_at_epoch_ms':101,'phone_app_version':'test',
        'glasses_app_version':'not_applicable','runtime_config_sha256':'0'*64,'model_id':'mock','prompt_sha256':'0'*64,
        'preprocess_sha256':'0'*64,'parser_sha256':'0'*64,'files':[],'frozen':{'contract':{}},'journal_entries':1,'audit_head_sha256':chain}
    (directory/'session_manifest.json').write_text(dump(manifest));rehash(directory)


class SessionExportTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.directory=Path(self.temp.name);fixture(self.directory)
    def tearDown(self): self.temp.cleanup()
    def test_valid_integrity_snapshot(self):
        result=validator.validate(self.directory)
        self.assertEqual('PASS',result['status'],result)
        self.assertEqual('FULL_EXPORT',result['scope'])
    def test_changed_file_is_detected(self):
        (self.directory/'runtime_events.jsonl').write_text('{"session_id":"changed"}\n')
        result=validator.validate(self.directory)
        self.assertEqual('FAIL',result['status'])
        self.assertTrue(any('checksum mismatch' in error for error in result['errors']))
    def test_rehashed_but_unaudited_payload_is_detected(self):
        (self.directory/'runtime_events.jsonl').write_text('{"session_id":"changed"}\n');rehash(self.directory)
        result=validator.validate(self.directory)
        self.assertTrue(any('audit/stream mismatch' in error for error in result['errors']),result)
    def test_truncated_audit_even_with_updated_file_checksums_is_detected(self):
        (self.directory/'audit.jsonl').write_text('');rehash(self.directory)
        result=validator.validate(self.directory)
        self.assertTrue(any('audit count/head mismatch' in error for error in result['errors']),result)
    def test_unlisted_file_is_detected(self):
        (self.directory/'unexpected.txt').write_text('unexpected')
        result=validator.validate(self.directory)
        self.assertTrue(any('inventory' in error for error in result['errors']),result)

    def test_actual_direct_api_export_keeps_provider_and_normalized_evidence(self):
        shutil.copytree(ROOT/'tests/fixtures/direct_api_runtime',self.directory,dirs_exist_ok=True)
        result=validator.validate(self.directory)
        self.assertEqual('PASS',result['status'],result)
        self.assertEqual(1,result['counts']['provider_responses'])

    def test_rehashed_normalized_decision_cannot_disagree_with_provider(self):
        shutil.copytree(ROOT/'tests/fixtures/direct_api_runtime',self.directory,dirs_exist_ok=True)
        path=self.directory/'raw_responses.jsonl'
        rows=[json.loads(line) for line in path.read_text().splitlines()]
        for row in rows:
            if row.get('raw_format')=='OPSTATE_V2_NORMALIZED':
                body=json.loads(row['raw_response_json'])
                body.update(decision='SERVICE',service={'mode':'PREPARATION','title':'Changed','body':'Changed decision'})
                row['raw_response_json']=dump(body);row['raw_sha256']=sha(row['raw_response_json'])
        path.write_text(''.join(dump(row)+'\n' for row in rows))
        path=self.directory/'decisions.jsonl'
        rows=[json.loads(line) for line in path.read_text().splitlines()]
        for row in rows:row.update(decision='SERVICE',service_mode='PREPARATION',title='Changed',body='Changed decision')
        path.write_text(''.join(dump(row)+'\n' for row in rows))
        streams={name:iter((self.directory/(name+'.jsonl')).read_text().splitlines()) for name in validator.STREAMS}
        audit=[json.loads(line) for line in (self.directory/'audit.jsonl').read_text().splitlines()]
        previous='0'*64
        for row in audit:
            payload=next(streams[row['stream']]);payload_hash=sha(payload)
            chain=sha(f"{row['sequence']}|{row['event_type']}|{row['event_at_epoch_ms']}|{payload_hash}|{previous}")
            row.update(canonical_payload=payload,payload_sha256=payload_hash,previous_chain_sha256=previous,chain_sha256=chain)
            previous=chain
        (self.directory/'audit.jsonl').write_text(''.join(dump(row)+'\n' for row in audit))
        manifest=json.loads((self.directory/'session_manifest.json').read_text());manifest['audit_head_sha256']=previous
        (self.directory/'session_manifest.json').write_text(dump(manifest));rehash(self.directory)
        result=validator.validate(self.directory)
        self.assertTrue(any('normalized response differs from provider output' in error for error in result['errors']),result)


if __name__=='__main__': unittest.main()
