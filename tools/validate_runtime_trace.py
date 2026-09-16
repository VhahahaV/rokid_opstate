#!/usr/bin/env python3
"""Check exported session integrity and causal/receipt invariants; PASS is not a hardware or model-accuracy claim."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
from collections import defaultdict
from jsonschema import Draft202012Validator
from referencing import Registry, Resource

ROOT=Path(__file__).resolve().parents[1]
SCHEMAS={path.name:json.loads(path.read_text()) for path in (ROOT/'schemas').glob('*.schema.json')}
REGISTRY=Registry()
for definition in SCHEMAS.values():
    if '$id' in definition: REGISTRY=REGISTRY.with_resource(definition['$id'],Resource.from_contents(definition))
def schema(name): return Draft202012Validator(SCHEMAS[name],registry=REGISTRY)

STREAMS=('observations','sensor_windows','context_events','episodes','captures','anchors','inference_requests',
         'raw_responses','decisions','deliveries','receipts','user_feedback','runtime_events')


def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda:source.read(1024*1024),b''): h.update(block)
    return h.hexdigest()


def sha(text): return hashlib.sha256(text.encode()).hexdigest()


def validate(directory: Path):
    errors=[]
    def fail(message):
        if len(errors)<100: errors.append(message)
    def lines(name):
        path=directory/name
        if not path.is_file(): fail(f'missing trace file: {name}');return
        with path.open(encoding='utf-8') as source:
            for number,line in enumerate(source,1):
                if not line.strip(): continue
                try:
                    value=json.loads(line)
                    if not isinstance(value,dict): raise ValueError('expected a JSON object')
                    yield value
                except ValueError as error: fail(f'{name}:{number}: {error}')
    def index(rows,key,required=()):
        result={}
        for row in rows:
            if any(k not in row for k in (key,*required)): fail(f'trace row missing fields: {key}, {required}');continue
            value=row[key]
            if not isinstance(value,str) or not value: fail(f'{key} must be a nonempty string');continue
            if value in result: fail(f'duplicate {key}: {value}')
            result[value]=row
        return result
    if not directory.is_dir(): fail(f'missing export directory: {directory}')
    obs=index(lines('observations.jsonl'),'observation_id',('available_at_epoch_ms',))
    anchors=index(lines('anchors.jsonl'),'anchor_id',('anchor_time_epoch_ms',))
    decisions=index(lines('decisions.jsonl'),'decision_id')
    receipts=index(lines('receipts.jsonl'),'receipt_id')
    deliveries=defaultdict(list)
    for row in lines('deliveries.jsonl'):
        if 'decision_id' not in row or 'status' not in row: fail('delivery missing decision_id/status');continue
        deliveries[row['decision_id']].append(row)
    for decision in decisions.values():
        anchor=anchors.get(decision.get('anchor_id'))
        if not anchor: fail(f'decision {decision.get("decision_id")} missing anchor');continue
        for evidence in decision.get('evidence_observation_ids',[]):
            source=obs.get(evidence) or receipts.get(evidence)
            if not source: fail(f'decision {decision["decision_id"]} missing evidence {evidence}')
            elif source.get('available_at_epoch_ms',source.get('visible_at_epoch_ms',float('inf')))>anchor['anchor_time_epoch_ms']:
                fail(f'future evidence {evidence} used by {decision["decision_id"]}')
        if decision.get('decision')=='NO_SERVICE' and any(d['status']=='DISPLAY_VISIBLE' for d in deliveries[decision['decision_id']]):
            fail(f'NO_SERVICE displayed: {decision["decision_id"]}')
    for receipt in receipts.values():
        ds=deliveries.get(receipt.get('decision_id'),[])
        if not any(d['status']=='DISPLAY_VISIBLE' for d in ds): fail(f'receipt {receipt["receipt_id"]} lacks visible delivery')
        if receipt.get('visible_ack_frame_count',0)<2: fail(f'receipt {receipt["receipt_id"]} has insufficient visible frames')
    manifest_path=directory/'session_manifest.json'
    full=manifest_path.is_file()
    counts={}
    if full:
        try:
            manifest=json.loads(manifest_path.read_text())
            schema('session_manifest.schema.json').validate(manifest)
            actual={p.relative_to(directory).as_posix() for p in directory.rglob('*') if p.is_file()}
            inventory={row['path']:row for row in manifest['files']}
            if len(inventory)!=len(manifest['files']): fail('duplicate manifest file path')
            if set(inventory)!=actual-{'session_manifest.json','SHA256SUMS.txt'}: fail('manifest file inventory differs from archive contents')
            if not {s+'.jsonl' for s in STREAMS}|{'audit.jsonl'} <= set(inventory): fail('manifest is missing required streams')
            for name,row in inventory.items():
                path=directory/name
                if Path(name).is_absolute() or '..' in Path(name).parts or path.is_symlink(): fail(f'unsafe file path: {name}');continue
                if not path.is_file() or path.stat().st_size!=row['bytes'] or digest(path)!=row['sha256']: fail(f'file checksum mismatch: {name}')
            checksums={}
            for line in (directory/'SHA256SUMS.txt').read_text().splitlines():
                checksum,name=line.split('  ',1)
                if name in checksums: fail(f'duplicate SHA256SUMS path: {name}')
                checksums[name]=checksum
            if set(checksums)!=actual-{'SHA256SUMS.txt'}: fail('SHA256SUMS inventory mismatch')
            for name,checksum in checksums.items():
                if Path(name).is_absolute() or '..' in Path(name).parts: fail(f'unsafe checksum path: {name}');continue
                if not (directory/name).is_file() or digest(directory/name)!=checksum: fail(f'SHA256SUMS mismatch: {name}')
            # Stream records must exactly match the audited payload sequence, including ordering.
            streams={name:iter(lines(name+'.jsonl')) for name in STREAMS}
            previous='0'*64;count=0;configuration=None
            for row in lines('audit.jsonl'):
                payload=row['canonical_payload'];payload_hash=sha(payload)
                expected=sha(f'{count}|{row["event_type"]}|{row["event_at_epoch_ms"]}|{payload_hash}|{previous}')
                if row['sequence']!=count or row['payload_sha256']!=payload_hash or row['previous_chain_sha256']!=previous or row['chain_sha256']!=expected:
                    fail(f'audit chain mismatch at sequence {count}')
                if row['event_type']=='SESSION_CONFIGURATION': configuration=payload
                stream=row['stream']
                if stream not in streams or next(streams[stream],None)!=json.loads(payload): fail(f'audit/stream mismatch at sequence {count}')
                previous=row['chain_sha256'];count+=1
            if configuration is not None:
                if sha(configuration)!=manifest['runtime_config_sha256']: fail('frozen runtime configuration hash mismatch')
                if json.loads(configuration).get('contract')!=manifest['frozen'].get('contract'): fail('frozen contract differs from audited configuration')
            if count!=manifest.get('journal_entries') or previous!=manifest.get('audit_head_sha256'): fail('audit count/head mismatch')
            for name,iterator in streams.items():
                if next(iterator,None) is not None: fail(f'unaudited rows in {name}')
            for row in obs.values():
                if 'local_relative_path' in row:
                    name=row['local_relative_path']
                    if inventory.get(name,{}).get('sha256')!=row.get('sha256'): fail(f'observation image mismatch: {row["observation_id"]}')
            by_request={d.get('request_id'):d for d in decisions.values()}
            request_schema=schema('inference_request_v2.schema.json')
            request_meta={};config=manifest['frozen'].get('contract',{})
            for request in lines('inference_requests.jsonl'):
                try: request_schema.validate(request)
                except Exception as error: fail(f'request schema invalid: {str(error)[:200]}');continue
                rid=request['request_id'];at=request['anchor_time_epoch_ms']
                if rid in request_meta: fail(f'duplicate request_id: {rid}')
                request_meta[rid]=(request['anchor_id'],{o['observation_id'] for o in request['observations']}|{r['receipt_id'] for r in request['receipts']})
                if anchors.get(request['anchor_id'],{}).get('anchor_time_epoch_ms')!=at: fail(f'request/anchor mismatch: {rid}')
                for key in ('protocol_version','model_id','prompt_hash','preprocess_hash','parser_hash'):
                    if request.get(key)!=config.get(key): fail(f'unfrozen request {key}: {rid}')
                if len({o['observation_id'] for o in request['observations']})!=len(request['observations']): fail(f'duplicate request observation: {rid}')
                for row in request['observations']:
                    persisted=obs.get(row['observation_id'])
                    if persisted is None: fail(f'unpersisted request observation: {rid}');continue
                    if row['available_at_epoch_ms']>at or row['occurred_at_epoch_ms']>row['available_at_epoch_ms']: fail(f'future evidence in request: {rid}')
                    for key in ('available_at_epoch_ms','occurred_at_epoch_ms','asset_id','episode_handle','material_revision'):
                        if row.get(key)!=persisted.get(key): fail(f'request observation differs from journal: {rid}/{key}')
                for row in request['receipts']:
                    persisted=receipts.get(row['receipt_id'])
                    if persisted is None or row['visible_at_epoch_ms']>at: fail(f'unknown/future request receipt: {rid}')
                decision=by_request.get(rid)
                if decision and not set(decision.get('evidence_observation_ids',[]))<=request_meta[rid][1]: fail(f'decision evidence absent from request: {rid}')
                if not any(row.get('asset_id')==request['current_asset_id'] and row.get('sha256')==request['current_image_sha256'] for row in obs.values()): fail(f'request image not persisted: {rid}')
            raw_ids=set();providers={};normalized={}
            response_schema=schema('inference_response_v2.schema.json')
            for row in lines('raw_responses.jsonl'):
                rid=row['request_id'];raw_ids.add(rid)
                if sha(row['raw_response_json'])!=row['raw_sha256']: fail(f'raw response hash mismatch: {rid}')
                if rid not in request_meta: fail(f'raw response has no request: {rid}');continue
                raw_format=row.get('raw_format','OPSTATE_V2')
                if raw_format in ('PROVIDER_RESPONSES','PROVIDER_CHAT_COMPLETIONS'):
                    providers[rid]=None
                    if 200<=row['http_status']<300:
                        try:
                            provider=json.loads(row['raw_response_json'])
                            if provider.get('model')!=config.get('model_id'): raise ValueError('provider model mismatch')
                            if raw_format=='PROVIDER_RESPONSES':
                                if provider.get('status')!='completed': raise ValueError('incomplete provider response')
                                parts=[p for item in provider.get('output',[]) if item.get('type')=='message' for p in item.get('content',[])]
                                if any(p.get('type')=='refusal' for p in parts): raise ValueError('provider refusal')
                                texts=[p['text'] for p in parts if p.get('type')=='output_text']
                                if len(texts)!=1: raise ValueError('ambiguous provider text')
                                model=json.loads(texts[0])
                            else:
                                choices=provider['choices']
                                if len(choices)!=1 or choices[0].get('finish_reason')!='stop' or choices[0]['message'].get('refusal'): raise ValueError('incomplete provider response')
                                model=json.loads(choices[0]['message']['content'])
                            if set(model)!= {'decision','service','evidence_refs'}: raise ValueError('unexpected model fields')
                            providers[rid]=model
                        except (ValueError,KeyError,TypeError):
                            pass # Invalid raw output is valid audit evidence of a technical failure, never a decision.
                    continue
                if raw_format not in ('OPSTATE_V2','OPSTATE_V2_NORMALIZED'):
                    fail(f'unknown raw response format: {rid}');continue
                if 200<=row['http_status']<300:
                    response=json.loads(row['raw_response_json'])
                    try: response_schema.validate(response)
                    except Exception as error: fail(f'response schema invalid: {str(error)[:200]}');continue
                    if response['request_id']!=rid: fail(f'response request ID mismatch: {rid}')
                    decision=by_request.get(rid)
                    if decision and (decision['decision_id']!=response['decision_id'] or decision['decision']!=response['decision'] or set(decision.get('evidence_observation_ids',[]))!=set(response['evidence_refs'])):
                        fail(f'parsed decision differs from raw response: {rid}')
                    if raw_format=='OPSTATE_V2_NORMALIZED': normalized[rid]=response
            for rid,response in normalized.items():
                model=providers.get(rid)
                if model is None or any(model[key]!=response[key] for key in ('decision','service','evidence_refs')):
                    fail(f'normalized response differs from provider output: {rid}')
            for rid in by_request:
                if rid not in request_meta or rid not in raw_ids: fail(f'decision has no recorded request/raw response: {rid}')
                if (config.get('model_config') or {}).get('connection_mode')=='DIRECT_API_KEY' and rid not in normalized:
                    fail(f'direct API decision has no normalized/provider evidence: {rid}')
            for receipt in receipts.values():
                decision=decisions.get(receipt.get('decision_id'))
                if not decision or decision.get('decision')!='SERVICE' or not decision['produced_at_epoch_ms']<=receipt.get('visible_at_epoch_ms',0)<decision['expires_at_epoch_ms']:
                    fail(f'receipt has no valid visible SERVICE: {receipt["receipt_id"]}')
            if manifest['condition_code']=='CONTRACT_REPLAY':
                if receipts or any(d['status']=='DISPLAY_VISIBLE' for values in deliveries.values() for d in values): fail('replay must not claim visible display or receipts')
            counts={'requests':len(request_meta),'raw_responses':len(raw_ids),'provider_responses':len(providers),'audit_entries':count}
        except Exception as error: fail(f'export validation error: {type(error).__name__}: {str(error)[:400]}')
    return {'status':'PASS' if not errors else 'FAIL','scope':'FULL_EXPORT' if full else 'LEGACY_CAUSAL_TRACE',
            'counts':{**counts,'observations':len(obs),'anchors':len(anchors),'decisions':len(decisions),'receipts':len(receipts)},'errors':errors}


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('export_dir',type=Path)
    args=parser.parse_args();result=validate(args.export_dir)
    print(json.dumps(result,ensure_ascii=False,indent=2));raise SystemExit(result['status']!='PASS')


if __name__=='__main__': main()
