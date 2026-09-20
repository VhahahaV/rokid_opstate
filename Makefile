PYTHON ?= python3
VENV := .venv
PY := $(VENV)/bin/python

.PHONY: help setup test test-core test-python demo serve android apks quick-check check-all device-apks device-test cxr-repair
help:
	@echo 'make setup      Install Python dependencies in .venv'
	@echo 'make test       Run core, simulations, Python and schema/trace checks'
	@echo 'make demo       Run both Kotlin simulations'
	@echo 'make serve      Start the local mock harness on 127.0.0.1:8765'
	@echo 'make android    Build both Android libraries and run phone contract tests (SDK required)'
	@echo 'make apks       Build installable phone and glasses debug APKs'
	@echo 'make quick-check  Python, core, protocol and phone JVM tests; no device/soak tests'
	@echo 'make check-all  Run all checks, including Android lint (SDK required)'
	@echo 'make device-test PHONE_SERIAL=... GLASSES_SERIAL=...  Run device smoke tests (make serve first)'
	@echo 'make cxr-repair PHONE_SERIAL=... GLASSES_SERIAL=...  Re-point the phone at the current CXR identity'

setup:
	$(PYTHON) -m venv $(VENV)
	$(PY) -m pip install -r requirements.txt

test:
	$(PY) tools/validate_bundle.py

test-core:
	./code/core/run_tests.sh

test-python:
	$(PY) -m unittest discover -s tests -p 'test_*.py' -v

demo:
	./tools/gradle.sh --console=plain :opstate-core:demo :opstate-core:meetingDemo

serve:
	$(PY) -m uvicorn mock_harness:app --app-dir code/server-contract --host 127.0.0.1 --port 8765

android:
	./tools/gradle.sh --console=plain -PwithAndroid=true :opstate-phone:assembleDebug :opstate-glasses:assembleDebug :opstate-phone:testDebugUnitTest

apks:
	./tools/gradle.sh --console=plain -PwithAndroid=true :phone-app:assembleDebug :glasses-app:assembleDebug

quick-check: test-python
	./tools/gradle.sh --console=plain -PwithAndroid=true :opstate-core:coreTest :opstate-protocol:test :opstate-phone:testDebugUnitTest

check-all: test
	./tools/gradle.sh --console=plain -PwithAndroid=true build

device-apks:
	./tools/gradle.sh --console=plain -PwithAndroid=true :opstate-phone:assembleDebugAndroidTest :opstate-glasses:assembleDebugAndroidTest

# Puts the phone back on the glasses' current CXR socket identity after a re-pairing (see
# docs/FAILURE_RECOVERY.md §8). Needs both devices attached.
cxr-repair:
	. ./.local/environment.sh; ADB="$$(command -v adb)" GLASSES_SERIAL="$(GLASSES_SERIAL)" PHONE_SERIAL="$(PHONE_SERIAL)" ./tools/cxr_repair.sh

device-test: device-apks
	. ./.local/environment.sh; ADB="$$(command -v adb)" $(PY) tools/device_smoke.py --phone "$(PHONE_SERIAL)" --glasses "$(GLASSES_SERIAL)"
