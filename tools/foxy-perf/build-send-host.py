#!/usr/bin/env python3
"""Extend the neutral host with an independent chat-Send diagnostic activity."""
from pathlib import Path
import shutil
import subprocess
import sys

here = Path(__file__).resolve().parent
output = Path(sys.argv[1]).resolve()
source = output.parent/(output.name+'-source')
shutil.copytree(here/'host', source)
for path in (here/'send-host').glob('*.java'):
    shutil.copy2(path, source/path.name)
manifest = source/'AndroidManifest.xml'
text = manifest.read_text().replace('</application>', '''
        <activity android:name=".SendHost" android:exported="true" android:screenOrientation="portrait"
            android:windowSoftInputMode="adjustResize|stateAlwaysVisible" />
    </application>''').replace('</manifest>', '''
    <instrumentation android:name=".SendReplay" android:targetPackage="io.github.sandyyur.inputbench"
        android:label="Candidate then client Send diagnostic" />
</manifest>''')
manifest.write_text(text)
subprocess.run(['bash',str(here.parent/'screen-perf/build-host.sh'),str(output),str(source)],check=True)
