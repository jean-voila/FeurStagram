"""Check revision discovery using the updater's existing version comparison."""
from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ReleaseVersionTests(unittest.TestCase):
    def test_numeric_revision_update_and_future_bases(self):
        source = ROOT / 'extensions/extension/src/main/java/com/feurstagram/extension'
        updater = (source / 'UpdateChecker.java').read_text()
        methods = []
        for signature in ('static String normalize', 'static boolean isNewer', 'private static int parse'):
            match = re.search(r'    ' + re.escape(signature) + r'\(.*?\n    }', updater, re.S)
            self.assertIsNotNone(match)
            methods.append(match.group(0))
        harness = '''package com.feurstagram.extension;
public class RevisionHarness {
''' + '\n'.join(methods) + '''
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        String base = "455.0.0.51.72";
        String latest = normalize("v455-0-0-51-72-1");
        check(isNewer(latest, base)); // Current public build detects the revision.
        check(latest.equals(ReleaseVersion.forInstagram(base)));
        check(!isNewer(latest, ReleaseVersion.forInstagram(base))); // No update loop.
        check(isNewer("455.0.0.51.72.2", ReleaseVersion.forInstagram(base)));
        check(isNewer("456.0.0.1.1", ReleaseVersion.forInstagram(base)));
        check("456.0.0.1.1".equals(ReleaseVersion.forInstagram("456.0.0.1.1")));
        check(ReleaseVersion.forInstagram(null) == null);
        System.out.println("Release revision: old-client discovery, no repeat prompt, next revision and future bases OK");
    }
}
'''
        home = Path(os.environ.get('JAVA_HOME', '/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home'))
        javac = str(home / 'bin/javac') if (home / 'bin/javac').exists() else shutil.which('javac')
        java = str(home / 'bin/java') if (home / 'bin/java').exists() else shutil.which('java')
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            (temp / 'RevisionHarness.java').write_text(harness)
            subprocess.run([javac, '-d', directory, str(source / 'ReleaseVersion.java'), str(temp / 'RevisionHarness.java')], check=True)
            subprocess.run([java, '-cp', directory, 'com.feurstagram.extension.RevisionHarness'], check=True)


if __name__ == '__main__':
    unittest.main()
