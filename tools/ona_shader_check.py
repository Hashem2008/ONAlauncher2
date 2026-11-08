import re, os, subprocess, sys
root = sys.argv[1]; out = sys.argv[2]
props = open(os.path.join(root, 'shaders.properties')).read()
profiles = dict(re.findall(r'^profile\.(\w+)=(.*)$', props, re.M))
def assemble(path, opts):
    def load(p):
        txt = open(p).read()
        def inc(m):
            return load(os.path.join(root, m.group(1).lstrip('/')))
        return re.sub(r'^#include "([^"]+)"\s*$', inc, txt, flags=re.M)
    src = load(path)
    # apply profile options the way Iris does (toggle #defines, values)
    for tok in opts.split():
        if '=' in tok:
            k, v = tok.split('=')
            src = re.sub(r'^(\s*(?://)?\s*#define\s+%s\s+)\S+' % k, r'#define %s %s' % (k, v), src, flags=re.M)
            src = re.sub(r'(const\s+\w+\s+%s\s*=\s*)[^;]+;' % k, r'\g<1>%s;' % v, src)
        else:
            on = not tok.startswith('!'); k = tok.lstrip('!')
            src = re.sub(r'^\s*(?://)?\s*#define\s+%s\b' % k, ('#define %s' % k) if on else ('//#define %s' % k), src, flags=re.M)
    return src
fails = 0; total = 0
for prof, opts in profiles.items():
    for f in sorted(os.listdir(root)):
        if not (f.endswith('.vsh') or f.endswith('.fsh')): continue
        stage = 'vert' if f.endswith('.vsh') else 'frag'
        src = assemble(os.path.join(root, f), opts)
        tmp = os.path.join(out, f'{prof}_{f}.{stage}')
        open(tmp, 'w').write(src)
        r = subprocess.run(['glslangValidator', tmp], capture_output=True, text=True)
        total += 1
        if r.returncode != 0:
            fails += 1
            print(f'FAIL {prof} {f}:\n' + '\n'.join(l for l in r.stdout.splitlines() if 'ERROR' in l)[:1500])
print(f'{total - fails}/{total} compiled')
