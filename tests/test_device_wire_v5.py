import copy
import json
import re
import unittest
from pathlib import Path
from jsonschema import Draft202012Validator

ROOT=Path(__file__).resolve().parents[1]


class DeviceWireSchemaTest(unittest.TestCase):
    def setUp(self):
        self.schema=json.loads((ROOT/'schemas/device_message_v5.schema.json').read_text())
        self.validator=Draft202012Validator(self.schema)
        self.fixtures=[json.loads(line) for line in (ROOT/'tests/fixtures/wire_v5.jsonl').read_text().splitlines()]

    def test_current_encoder_fixtures_and_runtime_message_surface_have_a_v5_contract(self):
        for row in self.fixtures:
            self.validator.validate(row)
        paths=['code/glasses-app/src/main/kotlin/ai/opstate/rokid/glasses/GlassesStudyService.kt',
               'code/android-overlay/phone/src/main/kotlin/ai/opstate/rokid/phone/transport/LegacyCxrTransport.kt',
               'code/android-overlay/phone/src/main/kotlin/ai/opstate/rokid/phone/runtime/StudyRuntimeEngine.kt']
        types=set()
        for path in paths:
            text=(ROOT/path).read_text()
            types.update(re.findall(r'(?:controlMessage|send)\("([A-Z_]+)"',text))
            types.update(re.findall(r'type\s*=\s*"([A-Z_]+)"',text))
        self.assertLessEqual(types,set(self.schema['properties']['type']['enum']))

    def test_legacy_version_single_frame_ack_and_overlong_service_are_rejected(self):
        old=copy.deepcopy(self.fixtures[0]);old['protocol_version']='rokid-study-v4'
        ack=copy.deepcopy(next(row for row in self.fixtures if row['type']=='DISPLAY_VISIBLE_ACK'));ack['payload']['render_frame_count']=1
        long=copy.deepcopy(next(row for row in self.fixtures if row['type']=='SHOW_SERVICE_CARD'));long['payload']['body']='长'*141
        bad_image=copy.deepcopy(next(row for row in self.fixtures if row['type']=='IMAGE_OFFER'));bad_image['payload']['image']['rotation_degrees']=45
        for invalid in (old,ack,long,bad_image):
            self.assertTrue(list(self.validator.iter_errors(invalid)))
