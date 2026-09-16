#!/usr/bin/env python3
"""Summarize verified exports. Missing measurements never become passing hardware gates."""
import argparse
import json
import math
import statistics
from pathlib import Path
import importlib.util

_spec=importlib.util.spec_from_file_location('opstate_trace_verifier',Path(__file__).with_name('validate_runtime_trace.py'))
_verifier=importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_verifier)


def rows(directory, name):
    with (directory/name).open() as source:
        for line in source:
            if line.strip():
                yield json.loads(line)


def distribution(values):
    values=sorted(value for value in values if value is not None and value>=0)
    if not values:
        return {'count':0,'median_ms':None,'p95_ms':None,'max_ms':None}
    return {'count':len(values),'median_ms':statistics.median(values),
            'p95_ms':values[math.ceil(.95*len(values))-1],'max_ms':values[-1]}


def percentile_gate(metric, threshold, minimum=20):
    if metric['count']<minimum:
        return 'INSUFFICIENT_SAMPLES'
    return 'PASS' if metric['p95_ms']<threshold else 'FAIL'


def summarize(directory):
    verification=_verifier.validate(directory)
    if verification['status']!='PASS' or verification['scope']!='FULL_EXPORT':
        return {'status':'INVALID_EXPORT','verification':verification}
    manifest=json.loads((directory/'session_manifest.json').read_text())
    contract=manifest['frozen'].get('contract',{})
    requested={};completed={};primary=set();camera_times=[];transfer_times=[];recorded_round_trips={}
    for row in rows(directory,'captures.jsonl'):
        capture=row.get('capture_id')
        if row.get('status')=='REQUESTED':
            requested[capture]=row['requested_at_epoch_ms']
            if row.get('quality_retry',0)==0:
                primary.add(capture)
        elif row.get('status')=='COMPLETED':
            completed[capture]=row.get('received_at_epoch_ms')
            camera_times.append(row.get('camera_duration_ms'));transfer_times.append(row.get('transfer_duration_ms'))
            if row.get('capture_round_trip_ms') is not None:
                recorded_round_trips[capture]=row['capture_round_trip_ms']
    live_photos=valid_photos=0;channels={}
    for row in rows(directory,'observations.jsonl'):
        if row.get('asset_source')=='LIVE_CAMERA':
            live_photos+=1
            valid_photos+=row.get('quality_valid') is True
            channel=row.get('transfer_channel','UNKNOWN')
            channels[channel]=channels.get(channel,0)+1
    requests={row['request_id']:row['anchor_time_epoch_ms'] for row in rows(directory,'inference_requests.jsonl')}
    decisions={row['decision_id']:row for row in rows(directory,'decisions.jsonl')}
    latencies={}
    for row in rows(directory,'raw_responses.jsonl'):
        if row.get('latency_ms') is not None:
            latencies[row['request_id']]=row['latency_ms']
    visible=[];anchor_visible=[]
    for receipt in rows(directory,'receipts.jsonl'):
        decision=decisions[receipt['decision_id']]
        visible.append(receipt['visible_at_epoch_ms']-decision['produced_at_epoch_ms'])
        anchor_visible.append(receipt['visible_at_epoch_ms']-requests[decision['request_id']])
    failures=[];interruptions=[];cancellations=[];command_times={}
    for row in rows(directory,'audit.jsonl'):
        if row['event_type']=='CAPTURE_COMMAND_ACK':
            payload=json.loads(row['canonical_payload'])
            command_times.setdefault(payload['capture_id'],payload['command_ack_rtt_ms'])
        if row['event_type'] in ('TECHNICAL_FAILURE','SESSION_LINK_INTERRUPTED','SESSION_INTERRUPTED','INFERENCE_CANCELLED'):
            payload=json.loads(row['canonical_payload'])
            if row['event_type']=='TECHNICAL_FAILURE':
                failures.append({'request_id':payload.get('request_id'),'code':payload.get('code')})
            elif row['event_type']=='INFERENCE_CANCELLED':
                cancellations.append(payload.get('request_id'))
            else:
                interruptions.append(row['event_type'])
    capture=distribution([recorded_round_trips.get(cid,at-requested[cid]) for cid,at in completed.items() if cid in requested and at is not None])
    model=distribution(latencies.values());display=distribution(visible);end_to_end=distribution(anchor_visible)
    command=distribution(command_times.values());camera=distribution(camera_times);transfer=distribution(transfer_times)
    def measured_gate(metric,threshold):
        return percentile_gate(metric,threshold) if metric['count'] else 'MEASUREMENT_NOT_RECORDED'
    successful_primary=len(primary & completed.keys())
    valid_decisions=len({row['request_id'] for row in decisions.values()})
    return {
        'status':'ANALYSIS_ONLY','session_id':manifest['session_id'],'export_verified':True,
        'model_source':'MOCK' if contract.get('mock') else 'REAL_PROVIDER',
        'primary_capture_requests':len(primary),'primary_capture_successes':successful_primary,
        'primary_capture_success_rate':successful_primary/len(primary) if primary else None,
        'actual_photos_recorded':live_photos,'quality_valid_photos':valid_photos,'transfer_channels':channels,
        'model_requests':len(requests),'valid_model_decisions':valid_decisions,
        'technical_failures':failures,'cancelled_inferences':cancellations,'recorded_interruptions':interruptions,
        'measurements':{'capture_round_trip':capture,'command_ack_rtt':command,'camera_to_jpeg_ready':camera,
                        'image_offer_to_received':transfer,'model':model,'decision_to_visible':display,'anchor_to_visible':end_to_end},
        'threshold_checks':{
            'model_p95_under_8000_ms':percentile_gate(model,8000),
            'decision_to_visible_p95_under_500_ms':percentile_gate(display,500),
            'anchor_to_visible_p95_under_12000_ms':percentile_gate(end_to_end,12000),
            'fifty_primary_capture_completion':('INSUFFICIENT_SAMPLES' if len(primary)<50 else 'PASS' if successful_primary/len(primary)>=.95 else 'FAIL'),
            'thirty_minutes_uninterrupted_ready':'NOT_ESTABLISHED_BY_EXPORT',
            'command_rtt_p95_under_300_ms':measured_gate(command,300),
            'camera_to_jpeg_ready_p95_under_2500_ms':measured_gate(camera,2500),
            'image_transfer_p95_under_3000_ms':measured_gate(transfer,3000),
        },
        'quantile_method':'median and nearest-rank P95; threshold comparisons require at least 20 samples',
        'measurement_definitions':{'command_ack_rtt':'Phone request until first correlated CAPTURE_STARTED; phone monotonic clock.',
            'camera_to_jpeg_ready':'Glasses camera call until JPEG bytes ready; glasses monotonic clock.',
            'image_offer_to_received':'Phone receives image offer until complete verified image bytes; phone monotonic clock.'},
        'limits':['Capture completion and image quality are separate counts.',
                  'Capture round-trip includes camera and transfer; it cannot replace either individual latency gate.',
                  'No elapsed session duration is treated as proof of continuous link readiness.',
                  'A passing timing comparison does not establish model accuracy or semantic alignment.'],
    }


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('export_dir',type=Path)
    args=parser.parse_args()
    report=summarize(args.export_dir)
    print(json.dumps(report,ensure_ascii=False,indent=2))
    raise SystemExit(report['status']=='INVALID_EXPORT')


if __name__=='__main__':
    main()
