#!/usr/bin/env python3
"""ONA Mobile Shaders: every option in the menus / profiles must be declared in lib/settings.glsl,
referenced the way Iris and OptiFine recognise (an on/off option needs an #ifdef / #ifndef line;
`#if defined X` alone makes it an "Invalid pack option"), and labelled in en_us and ar_sa.
Usage: python3 -I tools/ona_shader_options_check.py <shaders dir>"""
import glob, os, re, sys

root = sys.argv[1] if len(sys.argv) > 1 else '.'
os.chdir(root)
props = open('shaders.properties').read()
settings = open('lib/settings.glsl').read()
allsrc = '\n'.join(open(f).read() for f in glob.glob('**/*.*', recursive=True) if f.endswith(('.glsl', '.fsh', '.vsh')))
names = set()
for m in re.finditer(r'^(screen(?:\.\w+)?|sliders)=(.*)$', props, re.M):
    names |= {t for t in m.group(2).split() if not t.startswith(('[', '<'))}
for m in re.finditer(r'^profile\.\w+=(.*)$', props, re.M):
    names |= {t.lstrip('!').split('=')[0] for t in m.group(1).split()}
labels = lambda f: {l.split('=')[0] for l in open(f, encoding='utf-8') if '=' in l}
en, ar = labels('lang/en_us.lang'), labels('lang/ar_sa.lang')
bad = []
for n in sorted(names):
    if n in ('shadowMapResolution', 'shadowDistance'):
        continue
    d = re.search(r'^\s*(//)?\s*#define\s+' + n + r'\b(.*)$', settings, re.M)
    if not d:
        bad.append((n, 'not declared in lib/settings.glsl'))
        continue
    value = d.group(2).strip()
    on_off = value == '' or value.startswith('//')
    if on_off:
        used = re.search(r'^\s*#\s*(ifdef|ifndef)\s+' + n + r'\b', allsrc, re.M)
    else:
        used = re.search(r'\b' + n + r'\b', re.sub(r'^\s*(//)?\s*#define\s+' + n + r'\b.*$', '', allsrc, flags=re.M))
    if not used:
        bad.append((n, 'no #ifdef/#ifndef' if on_off else 'never used'))
    if 'option.' + n not in en or 'option.' + n not in ar:
        bad.append((n, 'no en/ar label'))
print(f'{len(names)} options, problems: {bad}')
sys.exit(1 if bad else 0)
