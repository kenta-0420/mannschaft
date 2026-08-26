import re
import subprocess
import os

base_dir = os.path.dirname(os.path.abspath(__file__))

def resolve_conflict_sections(content, strategy):
    """
    strategy: dict mapping conflict_index -> 'ours' | 'theirs' | ('replace', head_text, merge_text)
    """
    pattern = re.compile(
        r'<<<<<<< HEAD:.*?\n(.*?)=======\n(.*?)>>>>>>> .*?\n',
        re.DOTALL
    )
    idx = 0
    result = []
    pos = 0
    for m in pattern.finditer(content):
        result.append(content[pos:m.start()])
        head_part = m.group(1)
        theirs_part = m.group(2)
        s = strategy.get(idx, 'ours')
        if s == 'ours':
            result.append(head_part)
        elif s == 'theirs':
            result.append(theirs_part)
        elif isinstance(s, tuple) and s[0] == 'custom':
            result.append(s[1])
        idx += 1
        pos = m.end()
    result.append(content[pos:])
    return ''.join(result)


# ===== matches pages: take route.params.slug + teamId.value for API =====
for fname in [
    'frontend/app/pages/teams/[slug]/matches/[matchId]/live.vue',
    'frontend/app/pages/teams/[slug]/matches/index.vue',
    'frontend/app/pages/teams/[slug]/matches/new.vue',
]:
    path = os.path.join(base_dir, fname)
    with open(path, encoding='utf-8') as f:
        content = f.read()

    # Conflict 0: route.params declaration
    # HEAD: const teamIdStr = String(route.params.slug)\nconst teamSlug = Number(teamIdStr)\n
    # theirs: const teamIdStr = String(route.params.id)\n
    # Resolution: const teamIdStr = String(route.params.slug)\n  (no Number conversion)
    custom0 = 'const teamIdStr = String(route.params.slug)\n'

    # Conflict 1: API call
    # HEAD: uses teamSlug (wrong, NaN)
    # theirs: uses teamId.value (correct)
    # Resolution: take theirs

    resolved = resolve_conflict_sections(content, {
        0: ('custom', custom0),
        1: 'theirs',
    })

    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(resolved)
    print(f'Resolved: {fname}')


# ===== [slug]/index.vue: take main's empty for conflict, replace teamId in URLs =====
path = os.path.join(base_dir, 'frontend/app/pages/teams/[slug]/index.vue')
with open(path, encoding='utf-8') as f:
    content = f.read()

# Conflict 0: HEAD has friends TabPanel in value="6", main has empty
# Resolution: take theirs (empty) - main moved friends to value="7" below
resolved = resolve_conflict_sections(content, {0: 'theirs'})

# Replace ${teamId} in URL templates with ${teamSlug}
# (teamId was the old publicId variable name, teamSlug is the new one)
resolved = resolved.replace('${teamId}/', '${teamSlug}/')

with open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(resolved)
print('Resolved: [slug]/index.vue')

print('All conflicts resolved.')
