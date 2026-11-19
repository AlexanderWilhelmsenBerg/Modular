#!/usr/bin/env python3
"""Device proof that an independently built UI updates without updating Core."""
from pathlib import Path
import re
import subprocess

RUNNER = 'io.github.alexanderwilhelmsenberg.modular.debug.test/androidx.test.runner.AndroidJUnitRunner'
TESTS = (
    ('io.github.alexanderwilhelmsenberg.modular.ui.WorkspaceSurfaceStateTest', 2),
    ('io.github.alexanderwilhelmsenberg.modular.capabilities.FilesCrossApkHomeTest', 1),
    ('io.github.alexanderwilhelmsenberg.modular.capabilities.NativeIndependentSurfaceTest', 1),
)
OUT = Path('build/reports/native-surface')
OUT.mkdir(parents=True, exist_ok=True)
failures = []
for class_name, expected in TESTS:
    name = class_name.rsplit('.', 1)[-1]
    try:
        result = subprocess.run(['adb', 'shell', 'am', 'instrument', '-w', '-e', 'class', class_name, RUNNER],
                                text=True, capture_output=True, timeout=360)
        output = result.stdout + '\n' + result.stderr
        passed = result.returncode == 0 and re.search(rf'^OK \({expected} tests?\)\s*$', result.stdout, re.MULTILINE)
    except subprocess.TimeoutExpired as error:
        output = str(error) + '\nINSTRUMENTATION TIMED OUT\n'
        passed = False
    (OUT / f'{name}.log').write_text(output)
    print(f'{name}: {"passed" if passed else "failed"}', flush=True)
    if not passed:
        failures.append(name)
        subprocess.run(['adb', 'shell', 'uiautomator', 'dump', '/sdcard/native-surface-failure.xml'],
                       capture_output=True, timeout=30)
        subprocess.run(['adb', 'pull', '/sdcard/native-surface-failure.xml', str(OUT / f'{name}.xml')],
                       capture_output=True, timeout=30)
        with (OUT / f'{name}.png').open('wb') as screenshot:
            subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=screenshot, timeout=30)
subprocess.run(['adb', 'pull', '/sdcard/Pictures/ModularNativeSurfaces/.', str(OUT / 'proof')],
               capture_output=True, timeout=60)
if failures:
    raise SystemExit('Native surface tests failed: ' + ', '.join(failures))
print('Native surface proof passed (4 device tests)', flush=True)
