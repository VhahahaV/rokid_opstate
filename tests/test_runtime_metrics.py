import importlib.util
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('runtime_metrics',ROOT/'tools/report_runtime_metrics.py')
metrics=importlib.util.module_from_spec(spec)
spec.loader.exec_module(metrics)


class RuntimeMetricsTest(unittest.TestCase):
    def test_small_or_missing_samples_cannot_pass_a_percentile_gate(self):
        self.assertEqual('INSUFFICIENT_SAMPLES',metrics.percentile_gate(metrics.distribution([]),500))
        self.assertEqual('INSUFFICIENT_SAMPLES',metrics.percentile_gate(metrics.distribution([1]),500))
        self.assertEqual(19,metrics.distribution(range(1,21))['p95_ms'])
        self.assertEqual('FAIL',metrics.percentile_gate(metrics.distribution([500]*20),500))

    def test_a_fixture_export_is_not_reported_as_physical_link_success(self):
        report=metrics.summarize(ROOT/'tests/fixtures/direct_api_runtime')
        self.assertEqual('ANALYSIS_ONLY',report['status'])
        self.assertEqual(0,report['actual_photos_recorded'])
        self.assertEqual('NOT_ESTABLISHED_BY_EXPORT',report['threshold_checks']['thirty_minutes_uninterrupted_ready'])
        self.assertEqual('INSUFFICIENT_SAMPLES',report['threshold_checks']['fifty_primary_capture_completion'])


if __name__=='__main__':
    unittest.main()
